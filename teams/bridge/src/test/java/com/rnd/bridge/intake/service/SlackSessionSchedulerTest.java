package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TaskScheduler;

/**
 * Exercises SlackSessionScheduler's own responsibilities (deadline math, registry replacement
 * races, stale-timer revalidation, claim-then-publish routing) against a real GroupStateStore
 * (so revision/claimedRevision/published behave exactly as production) but a hand-rolled fake
 * TaskScheduler and a mutable Clock - so timer firing is simulated by directly invoking the
 * captured Runnable, never a real sleep, and "now" is advanced deterministically. slackLane is
 * mocked here since this class's job is to verify the scheduler calls it correctly (right
 * records, right routing), not to re-prove afterPublish/confirmPublished's own behavior - see
 * SlackSessionSchedulerPublishConfirmationTest (intake.lane package) for that, wired against a
 * real UnstructuredIntakeLane end to end.
 */
class SlackSessionSchedulerTest {

    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore groupStateStore = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ChatSourceRecordMapper chatSourceRecordMapper = new ChatSourceRecordMapper();
    private final UnstructuredIntakeLane slackLane = mockSlackLane();
    private final FakeTaskScheduler taskScheduler = new FakeTaskScheduler();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T09:00:00Z"));

    private static UnstructuredIntakeLane mockSlackLane() {
        UnstructuredIntakeLane lane = mock(UnstructuredIntakeLane.class);
        when(lane.sourceType()).thenReturn(RawSourceType.SLACK);
        return lane;
    }

    private SlackSessionScheduler newScheduler(int sessionGapMinutes) {
        return new SlackSessionScheduler(
                taskScheduler, groupStateStore, new ChatSessionProperties(sessionGapMinutes), chatSourceRecordMapper,
                List.of(slackLane), clock);
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

        GroupState second = persistGroup("g1", 1, false, "2026-08-18T09:00:00Z", "2026-08-18T09:10:00Z");
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
        GroupState state = persistGroup("g1", 1, false, "2026-08-18T10:00:00Z");

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
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        // Only the latest (T3) is invoked - the earlier ones were never live to fire at all.
        taskScheduler.scheduled.get(3).task().run();

        verify(slackLane, times(1)).run(any(Stream.class), any());
    }

    @Test
    void timerFiresAfterDeadlineClaimsAndPublishesOnlyTheTranscript() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        taskScheduler.scheduled.get(0).task().run();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Stream.class);
        verify(slackLane).run(captor.capture(), any());
        List<SourceRecord> published = (List<SourceRecord>) captor.getValue().toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).attachmentIndex()).isZero(); // transcript only, no attachments re-emitted
    }

    @Test
    void staleEarlyTimerDoesNotPublishAndReschedulesUsingTheFreshDeadline() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState original = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(original);
        // Newer activity landed directly in the store (as a concurrent message arrival would),
        // without going through scheduleOrReschedule again - the ORIGINAL timer is still "current".
        persistGroup("g1", 1, false, "2026-08-18T09:00:00Z", "2026-08-18T09:30:00Z");
        clock.set(Instant.parse("2026-08-18T09:45:00Z")); // past the OLD deadline, not the new one

        taskScheduler.scheduled.get(0).task().run();

        verify(slackLane, never()).run(any(Stream.class), any());
        // Detected the real deadline moved to 10:15 and rescheduled for it.
        assertThat(taskScheduler.scheduled).hasSize(2);
        assertThat(taskScheduler.scheduled.get(1).when()).isEqualTo(Instant.parse("2026-08-18T10:15:00Z"));
    }

    @Test
    void aLateFiringReplacedTimerCannotRemoveOrInterfereWithItsReplacement() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState first = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(first);

        GroupState second = persistGroup("g1", 1, false, "2026-08-18T09:00:00Z", "2026-08-18T09:10:00Z");
        scheduler.scheduleOrReschedule(second); // installs T2, best-effort cancels T1

        clock.set(Instant.parse("2026-08-18T09:20:00Z")); // before EITHER deadline
        // T1 fires late/spuriously anyway (cancel(false) is not a correctness guarantee).
        taskScheduler.scheduled.get(0).task().run();

        // T1 must not have published, and must not have rescheduled a THIRD timer (it is no
        // longer the registry's current entry, so it does nothing beyond returning).
        verify(slackLane, never()).run(any(Stream.class), any());
        assertThat(taskScheduler.scheduled).hasSize(2);

        // T2 still fires correctly once its own real deadline passes.
        clock.set(Instant.parse("2026-08-18T09:55:00Z"));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));
        taskScheduler.scheduled.get(1).task().run();
        verify(slackLane, times(1)).run(any(Stream.class), any());
    }

    @Test
    void onlyOneOfTwoConcurrentCallbacksForTheSameRevisionActuallyPublishes() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        Runnable callback = taskScheduler.scheduled.get(0).task();
        callback.run();
        callback.run(); // simulates a second execution path attempting the same fire

        verify(slackLane, times(1)).run(any(Stream.class), any());
    }

    @Test
    void alreadyPublishedCurrentRevisionIsNotRepublished() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, true, "2026-08-18T09:00:00Z"); // already published
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));

        taskScheduler.scheduled.get(0).task().run();

        verify(slackLane, never()).run(any(Stream.class), any());
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

        verify(slackLane, never()).run(any(Stream.class), any());
    }

    @Test
    void publicationFailureAfterASuccessfulClaimLeavesTheGroupClaimedButNotPublished() {
        SlackSessionScheduler scheduler = newScheduler(45);
        GroupState state = persistGroup("g1", 0, false, "2026-08-18T09:00:00Z");
        scheduler.scheduleOrReschedule(state);
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        when(slackLane.run(any(Stream.class), any())).thenThrow(new RuntimeException("kafka down"));

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
     * The interleaving is forced deterministically with latches (no sleeps): slackLane.run is
     * stubbed to signal "claimed" once invoked (i.e. AFTER claimForPublication already
     * succeeded inside onDeadline) and then block until the test thread has reconciled the new
     * message and released it - pinning the new message's arrival to exactly the race window
     * under test.
     */
    @Test
    void aGenuinelyNewMessageArrivingWhileATimerHoldsAClaimGoesToANewGroupInsteadOfInvalidatingTheClaim()
            throws Exception {
        ChatSessionProperties properties = new ChatSessionProperties(45);
        ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
                properties, new ChatSessionGrouper(properties), chatSourceRecordMapper, groupStateStore);
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
        when(slackLane.run(any(Stream.class), any())).thenAnswer(invocation -> {
            claimed.countDown();
            assertThat(newMessageReconciled.await(10, TimeUnit.SECONDS)).isTrue();
            return new IntakeResult(1, 1, 0, List.of());
        });

        ExecutorService timerExecutor = Executors.newSingleThreadExecutor();
        try {
            timerExecutor.submit(taskScheduler.scheduled.get(0).task());
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

            // The claim already succeeded (proven by slackLane.run having been invoked) - X must
            // be frozen at this point.
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
        assertThat(xFinal.published()).isFalse(); // this test's mocked lane never calls afterPublish
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
