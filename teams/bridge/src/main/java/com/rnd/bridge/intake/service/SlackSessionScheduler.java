package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Owns the dynamic 45-minute-style inactivity timer for non-thread Slack time-gap sessions - the
 * trigger only. GroupState in MinIO remains the authoritative session state; every timer callback
 * reloads it fresh and revalidates before doing anything (see {@link #onDeadline}), never acting
 * on the GroupState captured when the timer was originally scheduled.
 * The in-memory registry (groupKey -> its currently-scheduled ScheduledFuture) is deliberately
 * NOT authoritative either - it only exists so a newer message can cancel/replace a stale timer,
 * and so a fired callback knows whether it's still "the" timer for its group (see the
 * conditional, identity-based {@code registry.remove(key, myFuture)} cleanup below) or has
 * already been superseded by a later reschedule. It is intentionally lost on restart - see class
 * javadoc's "not implemented" list; startup recovery is a separate future step.
 * Threaded groups never reach this class at all - SlackEventsController only calls
 * {@link #scheduleOrReschedule} from the non-thread reconcile path, for both genuinely-new
 * messages and edits, whenever the resulting GroupState is still unpublished (an edit can
 * supersede an OPEN, CLAIMED, or already-PUBLISHED group with a fresh unpublished revision that
 * still needs a live timer - see ChatGroupStateReconciler.mergeIntoGroupState); nothing here
 * special-cases threadId, because nothing here is ever handed a threaded GroupState in the first
 * place.
 */
@Component
public class SlackSessionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlackSessionScheduler.class);

    private final TaskScheduler taskScheduler;
    private final GroupStateStore groupStateStore;
    private final ChatSessionProperties properties;
    private final ChatSourceRecordMapper chatSourceRecordMapper;
    private final UnstructuredIntakeLane slackLane;
    private final Clock clock;
    private final ConcurrentHashMap<GroupKey, ScheduledFuture<?>> registry = new ConcurrentHashMap<>();

    public SlackSessionScheduler(
            TaskScheduler taskScheduler, GroupStateStore groupStateStore, ChatSessionProperties properties,
            ChatSourceRecordMapper chatSourceRecordMapper, List<UnstructuredIntakeLane> lanes, Clock clock) {
        this.taskScheduler = taskScheduler;
        this.groupStateStore = groupStateStore;
        this.properties = properties;
        this.chatSourceRecordMapper = chatSourceRecordMapper;
        this.slackLane = lanes.stream()
                .filter(lane -> lane.sourceType() == RawSourceType.SLACK)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SLACK UnstructuredIntakeLane configured"));
        this.clock = clock;
    }

    /**
     * Schedules (or replaces any existing schedule for) the publication timer for state's group,
     * derived entirely from state + the configured session gap - callers never compute a deadline
     * themselves. The deadline is {@code latestMessageTimestamp + ChatSessionProperties.
     * sessionGapMinutes()}, where latestMessageTimestamp is state.messages()'s last element -
     * always the maximum timestamp, since mergeSnapshots keeps GroupState.messages() sorted
     * ascending on every merge (see ChatGroupStateReconciler.mergeSnapshots).
     * Any previously-registered timer for the same group is replaced: the new ScheduledFuture is
     * installed in the registry first, and only then is the old one best-effort cancelled -
     * cancel(false) is not relied on for correctness (a cancelled callback may still run; see
     * onDeadline's own revalidation), only as an optimization to avoid an unnecessary wakeup.
     */
    public void scheduleOrReschedule(GroupState state) {
        if (state.messages().isEmpty()) {
            return;
        }
        GroupKey key = GroupKey.of(state);
        Instant deadline = latestActivity(state).plus(sessionGap());

        AtomicReference<ScheduledFuture<?>> selfRef = new AtomicReference<>();
        ScheduledFuture<?> future = taskScheduler.schedule(() -> onDeadline(key, selfRef.get()), deadline);
        selfRef.set(future);

        ScheduledFuture<?> previous = registry.put(key, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /**
     * Fires when a previously-scheduled deadline is reached - reloads GroupState from scratch
     * and treats everything about the original scheduling (including whether this callback's own
     * ScheduledFuture is still the registry's current entry for this group) as unreliable until
     * re-verified here.
     * myFuture identifies exactly which scheduling generation this particular callback belongs
     * to - registry cleanup below only ever removes the registry entry if it still equals
     * myFuture (ConcurrentHashMap's atomic remove(key, value)), so a late-firing, already-replaced
     * callback can never evict a newer timer a subsequent scheduleOrReschedule installed.
     */
    private void onDeadline(GroupKey key, ScheduledFuture<?> myFuture) {
        Optional<GroupState> maybeState = groupStateStore.load(key.sourceType(), key.tenantId(), key.groupId());
        if (maybeState.isEmpty()) {
            registry.remove(key, myFuture);
            return;
        }
        GroupState state = maybeState.get();
        if (state.published() || state.messages().isEmpty()) {
            registry.remove(key, myFuture);
            return;
        }

        Instant actualDeadline = latestActivity(state).plus(sessionGap());
        if (clock.instant().isBefore(actualDeadline)) {
            // Stale/early wakeup (e.g. a message arrived after this timer was originally
            // scheduled, pushing the real deadline out). Only reschedule if we're still the
            // registry's current entry - if a later scheduleOrReschedule has already replaced us,
            // that replacement is the correct, up-to-date timer; touching it here would risk
            // undoing it.
            if (registry.get(key) == myFuture) {
                scheduleOrReschedule(state);
            }
            return;
        }

        boolean claimed = groupStateStore.claimForPublication(
                key.sourceType(), key.tenantId(), key.groupId(), state.revision());
        if (!claimed) {
            // Another execution path already owns (or has already published) this revision.
            registry.remove(key, myFuture);
            return;
        }

        try {
            publishTranscript(state);
        } catch (RuntimeException e) {
            // A scheduled TaskScheduler Runnable's exception would otherwise only surface via
            // its (unread) Future, silently vanishing - caught here instead so the failure is at
            // least visible. Either way, no retry is attempted (see class javadoc:
            // startup/stale-claim recovery is a separate future step) - GroupState is left
            // claimed-but-unpublished by claimForPublication/confirmPublished's existing
            // contract, and nothing here marks it falsely published.
            log.warn("Failed to publish Slack session transcript for group {}", key, e);
        } finally {
            registry.remove(key, myFuture);
        }
    }

    /**
     * Publishes ONLY the final transcript record through the existing Slack lane - map -> MinIO
     * landing -> validate -> Kafka publish -> afterPublish -> confirmPublished, identical to
     * every other caller of AbstractIntakeLane.run(Stream, Instant) (see
     * SlackEventsController). newMessagesThisBatch is List.of() because this session's
     * attachments were already published immediately when their owning message first arrived
     * (see ChatGroupStateReconciler.reconcileNonThreadedMessage) - re-passing them here would
     * duplicate them. With an empty newMessagesThisBatch, ChatSourceRecordMapper.toSourceRecords
     * only ever emits the transcript record itself (attachmentIndex() == 0); the filter below is
     * an explicit belt-and-suspenders check against that existing convention, not a new one.
     */
    private void publishTranscript(GroupState state) {
        List<SourceRecord> transcriptOnly = chatSourceRecordMapper.toSourceRecords(state, List.of()).stream()
                .filter(record -> record.attachmentIndex() == 0)
                .toList();
        if (!transcriptOnly.isEmpty()) {
            slackLane.run(transcriptOnly.stream(), clock.instant());
        }
    }

    private Instant latestActivity(GroupState state) {
        List<MessageSnapshot> messages = state.messages();
        return messages.get(messages.size() - 1).timestamp();
    }

    private Duration sessionGap() {
        return Duration.ofMinutes(properties.sessionGapMinutes());
    }

    /** Groups alone aren't globally unique - the same groupId string could theoretically repeat
     * across tenants/sourceTypes, exactly like GroupStateStore's own storage key requires all
     * three components (see its groupKey/openChatIndexKey). */
    private record GroupKey(RawSourceType sourceType, String tenantId, String groupId) {
        static GroupKey of(GroupState state) {
            return new GroupKey(state.sourceType(), state.tenantId(), state.groupId());
        }
    }
}
