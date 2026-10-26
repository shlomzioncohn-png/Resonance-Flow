package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
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
    private final SlackSessionPublisher sessionPublisher;
    private final Clock clock;
    private final ConcurrentHashMap<GroupKey, ScheduledFuture<?>> registry = new ConcurrentHashMap<>();

    public SlackSessionScheduler(
            TaskScheduler taskScheduler, GroupStateStore groupStateStore, ChatSessionProperties properties,
            SlackSessionPublisher sessionPublisher, Clock clock) {
        this.taskScheduler = taskScheduler;
        this.groupStateStore = groupStateStore;
        this.properties = properties;
        this.sessionPublisher = sessionPublisher;
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
        GroupKey key = GroupKey.of(state);
        Instant deadline = computeDeadline(state);
        if (deadline == null) {
            // Empty and not maybePublished - a group a deletion emptied before it was ever
            // actually at risk of being stale at Mind (see computeDeadline). Nothing to publish,
            // so any previously-registered timer for this group is cancelled rather than left to
            // fire pointlessly.
            ScheduledFuture<?> previous = registry.remove(key);
            if (previous != null) {
                previous.cancel(false);
            }
            return;
        }

        AtomicReference<ScheduledFuture<?>> selfRef = new AtomicReference<>();
        ScheduledFuture<?> future = taskScheduler.schedule(() -> onDeadline(key, selfRef.get()), deadline);
        selfRef.set(future);

        ScheduledFuture<?> previous = registry.put(key, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /**
     * The single deadline rule shared by scheduleOrReschedule and (conceptually) onDeadline's own
     * staleness recheck - see maybePublished's javadoc for why "current state, read once" is
     * always correct here, and this class's own javadoc update for why onDeadline must skip its
     * staleness recheck entirely once maybePublished is true (otherwise a correctionDelay-based
     * deadline, always in the future at the moment it's computed, would look "early" forever and
     * never actually fire).
     * Returns null only for the empty + not-maybePublished case - there is nothing to schedule,
     * and no legitimate deadline to compute (latestActivity would throw on an empty list); the
     * caller cancels any outstanding timer instead of installing a new one.
     */
    private Instant computeDeadline(GroupState state) {
        boolean maybePublished = maybePublished(state);
        if (state.messages().isEmpty()) {
            return maybePublished ? clock.instant().plus(correctionDelay()) : null;
        }
        if (maybePublished) {
            return clock.instant().plus(correctionDelay());
        }
        return latestActivity(state).plus(sessionGap());
    }

    /** published || revision > 0 || claimedRevision != null - see docs/plans/
     * slack-message-deleted.md §4 for the exhaustive trace proving this gives the same result on
     * the state entering a merge as on the state the merge produces, so reading it once, on
     * whatever GroupState is currently at hand, is always correct. */
    private boolean maybePublished(GroupState state) {
        return state.published() || state.revision() > 0 || state.claimedRevision() != null;
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
        boolean maybePublished = maybePublished(state);
        if (state.published() || (state.messages().isEmpty() && !maybePublished)) {
            registry.remove(key, myFuture);
            return;
        }

        if (!maybePublished) {
            // Ordinary still-open staleness recheck, unchanged from before this feature - only
            // ever reached for a non-empty, not-yet-possibly-published group.
            Instant actualDeadline = latestActivity(state).plus(sessionGap());
            if (clock.instant().isBefore(actualDeadline)) {
                // Stale/early wakeup (e.g. a message arrived after this timer was originally
                // scheduled, pushing the real deadline out). Only reschedule if we're still the
                // registry's current entry - if a later scheduleOrReschedule has already replaced
                // us, that replacement is the correct, up-to-date timer; touching it here would
                // risk undoing it.
                if (registry.get(key) == myFuture) {
                    scheduleOrReschedule(state);
                }
                return;
            }
        }
        // maybePublished==true skips the staleness recheck entirely and proceeds straight to
        // claim+publish - see computeDeadline's javadoc for why re-running it here would defeat
        // correctionDelay, and this class's own javadoc for why that race is safe once
        // maybePublished is true.

        boolean claimed = groupStateStore.claimForPublication(
                key.sourceType(), key.tenantId(), key.groupId(), state.revision());
        if (!claimed) {
            // Another execution path already owns (or has already published) this revision.
            registry.remove(key, myFuture);
            return;
        }

        try {
            // Reload fresh rather than publishing the snapshot loaded at the top of this method:
            // GroupStateStore.recordLandedAttachment is deliberately not revision-guarded (see
            // its javadoc), so an attachment can finish landing and be recorded on this exact
            // group between that initial load and the claim succeeding above. Publishing the
            // stale pre-claim state would silently omit any such attachment from the event, with
            // no way to retry it once this group is confirmed published. Guarded on revision
            // staying exactly what was claimed: if a concurrent edit already advanced past it (or
            // the group vanished) between the claim and this reload, that edit's own
            // scheduleOrReschedule call owns republishing the new revision - nothing to publish
            // for this now-stale claim.
            Optional<GroupState> freshState = groupStateStore.load(key.sourceType(), key.tenantId(), key.groupId());
            if (freshState.isPresent() && freshState.get().revision() == state.revision()) {
                sessionPublisher.publishSession(freshState.get(), clock.instant());
            }
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

    private Instant latestActivity(GroupState state) {
        List<MessageSnapshot> messages = state.messages();
        return messages.get(messages.size() - 1).timestamp();
    }

    private Duration sessionGap() {
        return Duration.ofMinutes(properties.sessionGapMinutes());
    }

    private Duration correctionDelay() {
        return Duration.ofSeconds(properties.correctionDelaySeconds());
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
