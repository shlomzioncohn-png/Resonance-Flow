package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TaskScheduler;

/**
 * Exercises SlackSessionScheduler's own responsibilities (deadline math, registry replacement
 * races, stale-timer revalidation, claim-then-publish routing) against a real GroupStateStore
 * (so revision/claimedRevision/published behave exactly as production) but a hand-rolled fake
 * TaskScheduler and a mutable Clock - so timer firing is simulated by directly invoking the
 * captured Runnable, never a real sleep, and "now" is advanced deterministically.
 * sessionPublisher is mocked here since this class's job is to verify the scheduler calls it
 * correctly (right state, right routing), not to re-prove the actual land/validate/publish/
 * confirm sequence - see SlackSessionSchedulerPublishConfirmationTest (intake.lane package) for
 * that, wired against a real SlackSessionPublisher end to end.
 */
class SlackSessionSchedulerTest {

    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore groupStateStore = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final SlackSessionPublisher sessionPublisher = mock(SlackSessionPublisher.class);
    private final FakeTaskScheduler taskScheduler = new FakeTaskScheduler();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T09:00:00Z"));

    private SlackSessionScheduler newScheduler(int sessionGapMinutes) {
        return new SlackSessionScheduler(
                taskScheduler, groupStateStore, new ChatSessionProperties(sessionGapMinutes), sessionPublisher, clock);
    }

    private GroupState persistGroup(String groupId, int revision, boolean published, String... messageTimestamps) {
        List<MessageSnapshot> messages = new ArrayList<>();
        for (int i = 0; i < messageTimestamps.length; i++) {
            messages.add(new MessageSnapshot("m" + i, "ruth", Instant.parse(messageTimestamps[i]), "hi", 0));
        }
        GroupState state = new GroupState(
                groupId, RawSourceType.SLACK, "kamatech", "chat-a", null, "corr-" + groupId, messages, published,
                revision, null);
        groupStateStore.save(state);
        return state;
    }

    @Test
    void scheduleOrRescheduleUsesConfiguredSessionGapNotAHardcodedValue() {
        SlackSessionScheduler scheduler = newScheduler(20); // deliberately not 45
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");

        scheduler.scheduleOrReschedule(state);

        assertThat(taskScheduler.scheduled).hasSize(1);
        assertThat(taskScheduler.scheduled.get(0).when()).isEqualTo(Instant.parse("2026-08-18T09:20:00Z"));
    }

    @Test
    void reschedulingCancelsThePreviousTimerAndUsesTheLatestActivity() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState first = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(first);
        FakeScheduledFuture firstFuture = taskScheduler.scheduled.get(0).future();

        // revision stays 0 - unpublished/unclaimed, so still not maybePublished (see
        // SlackSessionScheduler.maybePublished); otherwise this would use correctionDelay instead
        // of latestActivity+gap, which is not what this test is exercising.
        GroupState second = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z", "2026-08-18T09:10:00Z");
        scheduler.scheduleOrReschedule(second);

        assertThat(taskScheduler.scheduled).hasSize(2);
        assertThat(taskScheduler.scheduled.get(1).when()).isEqualTo(Instant.parse("2026-08-18T09:55:00Z"));
        assertThat(firstFuture.isCancelled()).isTrue();
    }

    @Test
    void reschedulingForAnEditUsesTheMessageTimestampNotTheEditArrivalTime() {
        SlackSessionScheduler scheduler = newScheduler(45);
        // "Now" is far after the message's own timestamp - as if this reschedule call happened
        // because of a late-arriving edit HTTP event, not because a new message just landed.
        clock.set(Instant.parse("2026-08-20T00:00:00Z"));
        // revision 0 - unpublished/unclaimed, so not maybePublished (see SlackSessionScheduler.
        // maybePublished); a maybePublished group would use correctionDelay-from-now instead,
        // which is exactly the behavior this test is distinguishing itself from.
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T10:00:00Z");

        scheduler.scheduleOrReschedule(state);

        assertThat(taskScheduler.scheduled).hasSize(1);
        // Deadline is derived from the message timestamp (10:00 + 45m), never from "now".
        assertThat(taskScheduler.scheduled.get(0).when()).isEqualTo(Instant.parse("2026-08-18T10:45:00Z"));
    }

    @Test
    void schedulingAPastDeadlineExecutesPromptlyRatherThanWaitingAFullSessionGap() throws Exception {
        // Verifies actual org.springframework.scheduling.TaskScheduler behavior for a one-shot
        // past-due Instant, rather than assuming it - relevant because an edit to a long-since-
        // published session computes a deadline that is already in the past.
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler realScheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        realScheduler.setPoolSize(1);
        realScheduler.initialize();
        try {
            CountDownLatch fired = new CountDownLatch(1);
            Instant pastDeadline = Instant.now().minusSeconds(3600);

            realScheduler.schedule(fired::countDown, pastDeadline);

            assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            realScheduler.shutdown();
        }
    }

    @Test
    void multipleEditsBeforePublicationOnlyRequireTheLatestRevisionToBePublished() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState r0 = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(r0);
        FakeScheduledFuture t0 = taskScheduler.scheduled.get(0).future();

        GroupState r1 = persistGroup("g1", 1, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(r1); // edit #1
        GroupState r2 = persistGroup("g1", 2, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(r2); // edit #2
        GroupState r3 = persistGroup("g1", 3, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(r3); // edit #3

        // Every earlier registration was replaced/cancelled - only the latest ever needs to fire.
        assertThat(taskScheduler.scheduled).hasSize(4);
        assertThat(t0.isCancelled()).isTrue();
        assertThat(taskScheduler.scheduled.get(1).future().isCancelled()).isTrue();
        assertThat(taskScheduler.scheduled.get(2).future().isCancelled()).isTrue();
        assertThat(taskScheduler.scheduled.get(3).future().isCancelled()).isFalse();

        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        doNothing().when(sessionPublisher).publishSession(any(), any());

        // Only the latest (T3) is invoked - the earlier ones were never live to fire at all.
        taskScheduler.scheduled.get(3).task().run();

        verify(sessionPublisher, times(1)).publishSession(any(), any());
    }

    @Test
    void timerFiresAfterDeadlineClaimsAndPublishesTheSession() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        doNothing().when(sessionPublisher).publishSession(any(), any());

        taskScheduler.scheduled.get(0).task().run();

        org.mockito.ArgumentCaptor<GroupState> captor = org.mockito.ArgumentCaptor.forClass(GroupState.class);
        verify(sessionPublisher).publishSession(captor.capture(), eq(clock.instant()));
        assertThat(captor.getValue().groupId()).isEqualTo("g1");
    }

    /**
     * Regression test for the stale-pre-claim-snapshot race: onDeadline used to call
     * sessionPublisher.publishSession with the GroupState loaded at the very top of the method,
     * before claimForPublication ran - so an attachment that finished landing (via
     * GroupStateStore.recordLandedAttachment, deliberately not revision-guarded) in the window
     * between that initial load and the claim succeeding would be silently missing from the
     * published event, with no retry once the group is confirmed published. Fixed by reloading
     * fresh after the claim succeeds and publishing THAT.
     * The race is forced deterministically (no sleeps) via a spy on GroupStateStore.load: the
     * very first call (onDeadline's own initial load) lands the attachment as a side effect,
     * simulating it completing right after that load but before the claim - every subsequent
     * load (claimForPublication's own precheck, and this fix's post-claim reload) then observes
     * it. A real SlackSessionPublisher (not mocked) is used so the actual published RawObjectEvent
     * can be inspected, proving the fix at the output, not just at which GroupState was passed in.
     */
    @Test
    void attachmentLandedJustAfterTheInitialLoadIsStillIncludedInThePublishedEvent() {
        GroupStateStore spyStore = spy(groupStateStore);
        ChatSessionProperties properties = new ChatSessionProperties(45);
        ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
                properties, new ChatSessionGrouper(properties), new ChatSourceRecordMapper(), spyStore);
        SourceRecordRefMapper refMapper =
                new SourceRecordRefMapper(new ObjectKeyBuilder(), new IntakeLandingService(storageClient));
        @SuppressWarnings("unchecked")
        PayloadPublisher<RawObjectEvent> kafkaPublisher = mock(PayloadPublisher.class);
        SlackSessionPublisher realPublisher = new SlackSessionPublisher(
                new AllowlistScopingFilter(), refMapper, new IntakeLandingService(storageClient),
                new RawObjectValidator(), kafkaPublisher, spyStore, new ChatSourceRecordMapper());
        SlackSessionScheduler scheduler = new SlackSessionScheduler(
                taskScheduler, spyStore, properties, realPublisher, clock);

        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("F1", "note.txt", "text/plain", "fake-bytes".getBytes());
        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                "listen to this", List.of(attachment), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult result = reconciler.reconcileNonThreadedMessage(message);
        assertThat(result.newAttachmentRecords()).hasSize(1);
        RawObjectRef attachmentRef =
                refMapper.toRef(result.newAttachmentRecords().get(0), Instant.parse("2026-08-18T09:00:00Z"));
        String groupId = result.groupState().groupId();

        scheduler.scheduleOrReschedule(result.groupState());
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));

        AtomicBoolean landed = new AtomicBoolean(false);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<GroupState> real = (Optional<GroupState>) invocation.callRealMethod();
            if (landed.compareAndSet(false, true)) {
                // Simulates the attachment's MinIO landing (SlackEventsController.landAttachments)
                // completing right after onDeadline's own initial load, i.e. exactly in the race
                // window this fix closes - every load after this one (including the claim's own
                // precheck and the post-claim reload) must observe it.
                groupStateStore.recordLandedAttachment(
                        RawSourceType.SLACK, "kamatech", groupId, "m1", "F1", attachmentRef);
            }
            return real;
        }).when(spyStore).load(RawSourceType.SLACK, "kamatech", groupId);

        taskScheduler.scheduled.get(0).task().run();

        org.mockito.ArgumentCaptor<RawObjectEvent> captor = org.mockito.ArgumentCaptor.forClass(RawObjectEvent.class);
        verify(kafkaPublisher).publish(captor.capture());
        assertThat(captor.getValue().objects()).hasSize(2);
        assertThat(captor.getValue().objects()).extracting(RawObjectRef::role)
                .containsExactlyInAnyOrder(Role.TRANSCRIPT, Role.ATTACHMENT);

        GroupState afterPublish = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterPublish.published()).isTrue();
    }

    @Test
    void staleEarlyTimerDoesNotPublishAndReschedulesUsingTheFreshDeadline() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState original = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(original);
        // Newer activity landed directly in the store (as a concurrent message arrival would),
        // without going through scheduleOrReschedule again - the ORIGINAL timer is still "current".
        persistGroup("g1", 0, false, "2026-08-18T09:00:00Z", "2026-08-18T09:30:00Z");
        clock.set(Instant.parse("2026-08-18T09:45:00Z")); // past the OLD deadline, not the new one

        taskScheduler.scheduled.get(0).task().run();

        verify(sessionPublisher, never()).publishSession(any(), any());
        // Detected the real deadline moved to 10:15 and rescheduled for it.
        assertThat(taskScheduler.scheduled).hasSize(2);
        assertThat(taskScheduler.scheduled.get(1).when()).isEqualTo(Instant.parse("2026-08-18T10:15:00Z"));
    }

    @Test
    void aLateFiringReplacedTimerCannotRemoveOrInterfereWithItsReplacement() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState first = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(first);

        GroupState second = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z", "2026-08-18T09:10:00Z");
        scheduler.scheduleOrReschedule(second); // installs T2, best-effort cancels T1

        clock.set(Instant.parse("2026-08-18T09:20:00Z")); // before EITHER deadline
        // T1 fires late/spuriously anyway (cancel(false) is not a correctness guarantee).
        taskScheduler.scheduled.get(0).task().run();

        // T1 must not have published, and must not have rescheduled a THIRD timer (it is no
        // longer the registry's current entry, so it does nothing beyond returning).
        verify(sessionPublisher, never()).publishSession(any(), any());
        assertThat(taskScheduler.scheduled).hasSize(2);

        // T2 still fires correctly once its own real deadline passes.
        clock.set(Instant.parse("2026-08-18T09:55:00Z"));
        doNothing().when(sessionPublisher).publishSession(any(), any());
        taskScheduler.scheduled.get(1).task().run();
        verify(sessionPublisher, times(1)).publishSession(any(), any());
    }

    @Test
    void onlyOneOfTwoConcurrentCallbacksForTheSameRevisionActuallyPublishes() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        doNothing().when(sessionPublisher).publishSession(any(), any());

        Runnable callback = taskScheduler.scheduled.get(0).task();
        callback.run();
        callback.run(); // simulates a second execution path attempting the same fire

        verify(sessionPublisher, times(1)).publishSession(any(), any());
    }

    @Test
    void alreadyPublishedCurrentRevisionIsNotRepublished() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, true, "2026-08-18T09:00:00Z"); // already published
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));

        taskScheduler.scheduled.get(0).task().run();

        verify(sessionPublisher, never()).publishSession(any(), any());
    }

    /**
     * Regression test for docs/plans/slack-superseded-fix.md: once a non-thread (channel) group's
     * root has been adopted into a new thread group (ChatGroupStateReconciler.
     * newThreadGroupState), the channel group's own pending inactivity timer - scheduled before
     * the migration ever happened - must still fire normally and publish, since the channel group
     * keeps living (its own copy of the root, plus whatever else landed in it) rather than being
     * retired. This replaces the old aSupersededGroupsPendingTimerBecomesANoOpInsteadOf... test,
     * whose premise (the channel group is dead once its root is threaded) no longer holds.
     */
    @Test
    void theChannelGroupsPendingTimerStillFiresAndPublishesNormallyAfterItsRootIsThreaded() {
        ChatSessionProperties properties = new ChatSessionProperties(45);
        ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
                properties, new ChatSessionGrouper(properties), new ChatSourceRecordMapper(), groupStateStore);
        SlackSessionScheduler scheduler = newScheduler(45);
        doNothing().when(sessionPublisher).publishSession(any(), any());

        ChatMessage root = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "malki", Instant.parse("2026-08-18T09:00:00Z"),
                "root question", List.of(), "r1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult rootResult = reconciler.reconcileNonThreadedMessage(root);
        String originalGroupId = rootResult.groupState().groupId();
        scheduler.scheduleOrReschedule(rootResult.groupState());

        // A reply names the root via thread_ts, migrating its content into a brand-new thread
        // group - the channel group keeps living (see ChatGroupStateReconciler.
        // newThreadGroupState/migratedRootSnapshot).
        ChatMessage reply = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:05:00Z"), "reply",
                List.of(), "reply1", "r1");
        reconciler.group(List.of(reply));

        // The channel group's own timer, scheduled before the migration, now fires - it must
        // still publish the channel group's own content (the root) normally.
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        taskScheduler.scheduled.get(0).task().run();

        verify(sessionPublisher, times(1)).publishSession(any(), any());
        GroupState originalAfterFiring =
                groupStateStore.load(RawSourceType.SLACK, "kamatech", originalGroupId).orElseThrow();
        assertThat(originalAfterFiring.messages()).extracting(MessageSnapshot::messageId).containsExactly("r1");
    }

    @Test
    void aGroupWithRevisionAboveZeroIsScheduledUsingCorrectionDelayNotTheSessionGap() {
        // revision > 0 alone counts as maybePublished (see SlackSessionScheduler.maybePublished) -
        // errs toward treating a genuine prior content change as possibly-already-published, even
        // though this particular group was never actually published/claimed.
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 1, false, "2026-08-18T09:00:00Z");

        scheduler.scheduleOrReschedule(state);

        assertThat(taskScheduler.scheduled).hasSize(1);
        assertThat(taskScheduler.scheduled.get(0).when()).isEqualTo(clock.instant().plus(Duration.ofSeconds(60)));
    }

    @Test
    void onDeadlineForAMaybePublishedGroupSkipsTheStalenessRecheckAndPublishesImmediately() {
        SlackSessionScheduler scheduler = newScheduler(45);
        // Last message is only 5 minutes old - under the ordinary latestActivity+gap rule this
        // would look "early" and get rescheduled, deferring all the way out to the full session
        // gap. maybePublished (revision=1) must skip that recheck entirely.
        GroupState state = persistGroup("g1", 1, false, "2026-08-18T08:55:00Z");
        scheduler.scheduleOrReschedule(state);
        clock.set(clock.instant().plus(Duration.ofSeconds(60)));
        doNothing().when(sessionPublisher).publishSession(any(), any());

        taskScheduler.scheduled.get(0).task().run();

        verify(sessionPublisher, times(1)).publishSession(any(), any());
        assertThat(taskScheduler.scheduled).hasSize(1); // never rescheduled a second timer
    }

    @Test
    void anEmptyGroupThatIsNotMaybePublishedIsNeverScheduledAndAnyPendingTimerIsCancelled() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState open = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(open);
        FakeScheduledFuture openFuture = taskScheduler.scheduled.get(0).future();

        GroupState emptied = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "corr-g1", List.of(), false, 0, null);
        groupStateStore.save(emptied);
        scheduler.scheduleOrReschedule(emptied);

        assertThat(openFuture.isCancelled()).isTrue();
        assertThat(taskScheduler.scheduled).hasSize(1); // no new timer installed
    }

    @Test
    void onDeadlineForAnEmptyMaybePublishedGroupClaimsAndPublishesADeleteReview() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState emptiedAfterPublish = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "corr-g1", List.of(), false, 1, null);
        groupStateStore.save(emptiedAfterPublish);
        scheduler.scheduleOrReschedule(emptiedAfterPublish);
        clock.set(clock.instant().plus(Duration.ofSeconds(60)));
        doNothing().when(sessionPublisher).publishSession(any(), any());

        taskScheduler.scheduled.get(0).task().run();

        org.mockito.ArgumentCaptor<GroupState> captor = org.mockito.ArgumentCaptor.forClass(GroupState.class);
        verify(sessionPublisher).publishSession(captor.capture(), eq(clock.instant()));
        assertThat(captor.getValue().messages()).isEmpty();
    }

    /**
     * True end-to-end version of onDeadlineForAnEmptyMaybePublishedGroupClaimsAndPublishesADeleteReview
     * (that one mocks sessionPublisher, so it only proves the scheduler calls publishSession - not
     * that the resulting Kafka event is actually a DELETE_REVIEW). Wires a real ChatGroupStateReconciler
     * + real SlackSessionPublisher + real ChatSourceRecordMapper, mocking only the Kafka
     * PayloadPublisher, mirroring attachmentLandedJustAfterTheInitialLoadIsStillIncludedInThePublishedEvent's
     * own setup above.
     */
    @Test
    void onDeadlineForAnEmptyMaybePublishedGroupPublishesARealDeleteReviewEventToKafka() {
        ChatSessionProperties properties = new ChatSessionProperties(45);
        ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
                properties, new ChatSessionGrouper(properties), new ChatSourceRecordMapper(), groupStateStore);
        SourceRecordRefMapper refMapper =
                new SourceRecordRefMapper(new ObjectKeyBuilder(), new IntakeLandingService(storageClient));
        @SuppressWarnings("unchecked")
        PayloadPublisher<RawObjectEvent> kafkaPublisher = mock(PayloadPublisher.class);
        SlackSessionPublisher realPublisher = new SlackSessionPublisher(
                new AllowlistScopingFilter(), refMapper, new IntakeLandingService(storageClient),
                new RawObjectValidator(), kafkaPublisher, groupStateStore, new ChatSourceRecordMapper());
        SlackSessionScheduler scheduler = new SlackSessionScheduler(
                taskScheduler, groupStateStore, properties, realPublisher, clock);

        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult first = reconciler.reconcileNonThreadedMessage(message);
        String groupId = first.groupState().groupId();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", groupId, first.groupState().revision());

        ChatGroupStateReconciler.RemovalResult removal =
                reconciler.removeMessage(RawSourceType.SLACK, "kamatech", "m1");
        assertThat(removal.groupState().messages()).isEmpty();
        scheduler.scheduleOrReschedule(removal.groupState());
        clock.set(clock.instant().plus(Duration.ofSeconds(60)));

        taskScheduler.scheduled.get(0).task().run();

        org.mockito.ArgumentCaptor<RawObjectEvent> captor = org.mockito.ArgumentCaptor.forClass(RawObjectEvent.class);
        verify(kafkaPublisher).publish(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.DELETE_REVIEW);
        assertThat(captor.getValue().objects()).isEmpty();
        GroupState afterPublish = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterPublish.published()).isTrue();
    }

    @Test
    void burstOfCorrectionsToAMaybePublishedGroupCollapsesIntoExactlyOnePublishAtTheFinalState() {
        // "maybePublished" (revision > 0 here) groups debounce via correctionDelay, using the
        // exact same cancel-and-replace timer logic as the ordinary session-gap debounce - so a
        // burst of rapid corrections collapses into one publish, at the LAST state, same as
        // multipleEditsBeforePublicationOnlyRequireTheLatestRevisionToBePublished above but for
        // the maybePublished/correctionDelay case specifically.
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState r1 = persistGroup("g1", 1, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(r1);
        FakeScheduledFuture t1 = taskScheduler.scheduled.get(0).future();

        GroupState r2 = persistGroup("g1", 2, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(r2); // another correction arrives within the delay window
        GroupState r3 = persistGroup("g1", 3, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(r3); // and another

        assertThat(taskScheduler.scheduled).hasSize(3);
        assertThat(t1.isCancelled()).isTrue();
        assertThat(taskScheduler.scheduled.get(1).future().isCancelled()).isTrue();
        assertThat(taskScheduler.scheduled.get(2).future().isCancelled()).isFalse();

        clock.set(clock.instant().plus(Duration.ofSeconds(60)));
        doNothing().when(sessionPublisher).publishSession(any(), any());
        taskScheduler.scheduled.get(2).task().run();

        org.mockito.ArgumentCaptor<GroupState> captor = org.mockito.ArgumentCaptor.forClass(GroupState.class);
        verify(sessionPublisher, times(1)).publishSession(captor.capture(), any());
        assertThat(captor.getValue().revision()).isEqualTo(3); // the final state, not an earlier one
    }

    @Test
    void aPartialDeletionOfAMaybePublishedGroupUsesTheSameCorrectionDelayTimingAsAnOrdinaryEdit() {
        // No special-casing at the scheduling layer for deletion vs. edit - computeDeadline only
        // ever looks at published()/revision()/claimedRevision()/messages().isEmpty(), never at
        // *why* the group changed. A partial deletion (still non-empty) that bumped revision
        // behaves identically to the edit case already covered by
        // aGroupWithRevisionAboveZeroIsScheduledUsingCorrectionDelayNotTheSessionGap.
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState afterPartialDeletion = persistGroup("g1", 1, false, "2026-08-18T09:00:00Z");

        scheduler.scheduleOrReschedule(afterPartialDeletion);

        assertThat(taskScheduler.scheduled).hasSize(1);
        assertThat(taskScheduler.scheduled.get(0).when()).isEqualTo(clock.instant().plus(Duration.ofSeconds(60)));
    }

    /**
     * Narrower case than aGenuinelyNewMessageArrivingWhileATimerHoldsAClaimGoesToANewGroupInsteadOfInvalidatingTheClaim
     * below: here the group is maybePublished purely via revision() > 0 while published() is
     * still false and nothing is claimed - isOpenForNewMessages (which only reads published/
     * claimedRevision) alone would NOT block a new message from joining. It's
     * withinSessionGap - the new message's timestamp landing far outside the stale latestActivity
     * - that actually prevents reuse here (see ChatGroupStateReconciler.mergeIntoCurrentChatGroup's
     * javadoc, "Narrow documented exception").
     */
    @Test
    void aGenuinelyNewMessageArrivingAfterAMaybePublishedCorrectionStartsItsOwnGroupInsteadOfJoining() {
        ChatSessionProperties properties = new ChatSessionProperties(45);
        ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
                properties, new ChatSessionGrouper(properties), new ChatSourceRecordMapper(), groupStateStore);

        ChatGroupStateReconciler.RealtimeReconcileResult first = reconciler.reconcileNonThreadedMessage(
                new ChatMessage(
                        RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                        List.of(), "m1", null));
        String xGroupId = first.groupState().groupId();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", xGroupId, first.groupState().revision());
        // A correction after confirmPublished - revision now > 0, published() flips back to
        // false, nothing claimed. maybePublished() is true purely via revision() > 0.
        ChatGroupStateReconciler.RealtimeReconcileResult corrected = reconciler.reconcileNonThreadedMessage(
                new ChatMessage(
                        RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                        "hi (edited)", List.of(), "m1", null));
        assertThat(corrected.groupState().revision()).isEqualTo(1);
        assertThat(corrected.groupState().published()).isFalse();
        assertThat(corrected.groupState().claimedRevision()).isNull();

        // A genuinely new message for the same chat, arriving well after X's own (stale)
        // latestActivity - withinSessionGap must still reject reuse.
        ChatGroupStateReconciler.RealtimeReconcileResult second = reconciler.reconcileNonThreadedMessage(
                new ChatMessage(
                        RawSourceType.SLACK, "kamatech", "chat-a", "malki", Instant.parse("2026-08-19T09:00:00Z"),
                        "unrelated", List.of(), "m2", null));

        assertThat(second.groupState().groupId()).isNotEqualTo(xGroupId);
        GroupState xAfterNewMessage = groupStateStore.load(RawSourceType.SLACK, "kamatech", xGroupId).orElseThrow();
        assertThat(xAfterNewMessage.messages()).hasSize(1); // m2 never merged into X
        assertThat(xAfterNewMessage.revision()).isEqualTo(1); // X's own revision untouched
    }

    @Test
    void missingGroupStateExitsSafely() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(state);
        // Simulate the group having vanished by the time the timer fires (shouldn't normally
        // happen - there is no delete path today - but the callback must tolerate it safely).
        backingStore.clear();
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));

        taskScheduler.scheduled.get(0).task().run();

        verify(sessionPublisher, never()).publishSession(any(), any());
    }

    @Test
    void publicationFailureAfterASuccessfulClaimLeavesTheGroupClaimedButNotPublished() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        doThrow(new RuntimeException("kafka down")).when(sessionPublisher).publishSession(any(), any());

        taskScheduler.scheduled.get(0).task().run(); // must not throw out of the callback

        GroupState after = groupStateStore.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(after.published()).isFalse();
        assertThat(after.claimedRevision()).isEqualTo(0);
    }

    /**
     * Regression test for the race identified in the session-lifecycle inspection: a genuinely-
     * new message arriving for the same chat WHILE the timer already claimed X's revision (but
     * before confirmPublished has run) used to bump X to revision+1 and clear the claim,
     * producing a stale-revision confirm later. With the OPEN/CLAIMED/CLOSED gate in
     * ChatGroupStateReconciler.mergeIntoCurrentChatGroup, the new message must now go to a
     * separate group Y instead, leaving X's claim untouched.
     * The interleaving is forced deterministically with latches (no sleeps): sessionPublisher is
     * stubbed to signal "claimed" once invoked (i.e. AFTER claimForPublication already succeeded
     * inside onDeadline) and then block until the test thread has reconciled the new message and
     * released it - pinning the new message's arrival to exactly the race window under test.
     */
    @Test
    void aGenuinelyNewMessageArrivingWhileATimerHoldsAClaimGoesToANewGroupInsteadOfInvalidatingTheClaim()
            throws Exception {
        ChatSessionProperties properties = new ChatSessionProperties(45);
        ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
                properties, new ChatSessionGrouper(properties), new ChatSourceRecordMapper(), groupStateStore);
        SlackSessionScheduler scheduler = newScheduler(45);

        ChatMessage first = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult firstResult = reconciler.reconcileNonThreadedMessage(first);
        String xGroupId = firstResult.groupState().groupId();
        scheduler.scheduleOrReschedule(firstResult.groupState());
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch newMessageReconciled = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            claimed.countDown();
            assertThat(newMessageReconciled.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(sessionPublisher).publishSession(any(), any());

        ExecutorService timerExecutor = Executors.newSingleThreadExecutor();
        try {
            timerExecutor.submit(taskScheduler.scheduled.get(0).task());
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

            // The claim already succeeded (proven by sessionPublisher.publishSession having been
            // invoked) - X must be frozen at this point.
            GroupState xWhileClaimed = groupStateStore.load(RawSourceType.SLACK, "kamatech", xGroupId).orElseThrow();
            assertThat(xWhileClaimed.revision()).isZero();
            assertThat(xWhileClaimed.claimedRevision()).isZero();

            ChatMessage second = new ChatMessage(
                    RawSourceType.SLACK, "kamatech", "chat-a", "malki", Instant.parse("2026-08-18T09:10:00Z"), "hey",
                    List.of(), "m2", null);
            ChatGroupStateReconciler.RealtimeReconcileResult secondResult = reconciler.reconcileNonThreadedMessage(second);

            assertThat(secondResult.groupState().groupId()).isNotEqualTo(xGroupId);
            GroupState xAfterNewMessage =
                    groupStateStore.load(RawSourceType.SLACK, "kamatech", xGroupId).orElseThrow();
            assertThat(xAfterNewMessage.revision()).isZero(); // unchanged - no more R -> R+1 bump
            assertThat(xAfterNewMessage.claimedRevision()).isZero(); // claim intact
            assertThat(xAfterNewMessage.messages()).hasSize(1); // m2 never merged into X

            newMessageReconciled.countDown();
            timerExecutor.shutdown();
            assertThat(timerExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            timerExecutor.shutdownNow();
        }

        // X's own claim resolves normally to a real, non-stale confirm.
        GroupState xFinal = groupStateStore.load(RawSourceType.SLACK, "kamatech", xGroupId).orElseThrow();
        assertThat(xFinal.published()).isFalse(); // this test's mocked publisher never calls confirmPublished
        assertThat(xFinal.revision()).isZero();
    }

    private record ScheduledEntry(Runnable task, Instant when, FakeScheduledFuture future) {
    }

    private static final class FakeTaskScheduler implements TaskScheduler {
        final List<ScheduledEntry> scheduled = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            FakeScheduledFuture future = new FakeScheduledFuture();
            scheduled.add(new ScheduledEntry(task, startTime, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeScheduledFuture implements ScheduledFuture<Object> {
        private boolean cancelled = false;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int compareTo(Delayed o) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
