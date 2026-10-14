package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.intake.service.ChatSourceRecordMapper;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.intake.service.SlackSessionScheduler;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import static org.mockito.Mockito.doAnswer;

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
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * End-to-end: a real SlackSessionScheduler wired against a real UnstructuredIntakeLane and
 * GroupStateStore, proving the timer callback actually reuses the existing lane's
 * afterPublish -> GroupStateStore.confirmPublished path (not a parallel one) - see
 * SlackSessionSchedulerTest (intake.service package) for the scheduler's own routing/race/
 * staleness behavior against a mocked lane.
 */
class SlackSessionSchedulerPublishConfirmationTest {

    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore groupStateStore = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ChatSessionProperties properties = new ChatSessionProperties(45);
    private final ChatSourceRecordMapper chatSourceRecordMapper = new ChatSourceRecordMapper();
    private final ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
            properties, new ChatSessionGrouper(properties), chatSourceRecordMapper, groupStateStore);

    @SuppressWarnings("unchecked")
    private final SourceReader<SourceRecord> sourceReader = mock(SourceReader.class);
    @SuppressWarnings("unchecked")
    private final PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper = mock(PayloadMapper.class);
    @SuppressWarnings("unchecked")
    private final PayloadValidator<RawObjectEvent> payloadValidator = mock(PayloadValidator.class);
    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> payloadPublisher = mock(PayloadPublisher.class);
    private final IntakeLandingService landingService = mock(IntakeLandingService.class);
    private final CalendarEventStateStore calendarEventStateStore = mock(CalendarEventStateStore.class);

    private final UnstructuredIntakeLane slackLane = new UnstructuredIntakeLane(
            RawSourceType.SLACK, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
            groupStateStore, calendarEventStateStore);

    private final FakeTaskScheduler taskScheduler = new FakeTaskScheduler();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T09:00:00Z"));
    private final SlackSessionScheduler sessionScheduler = new SlackSessionScheduler(
            taskScheduler, groupStateStore, properties, chatSourceRecordMapper, List.of(slackLane), clock);

    private RawObjectEvent eventFor(SourceRecord record) {
        return RawObjectEvent.create(
                "SLACK/kamatech/2026-08-18/" + record.naturalId() + ".json", record.sourceType(), record.tenantId(),
                Instant.parse("2026-08-18T09:00:00Z"), ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), null,
                record.participants(), "application/json", 2L, record.occurredAt(), record.correlationId(),
                record.attachmentIndex(), record.attachmentCount(), record.revision(), ChangeType.UPSERT);
    }

    @Test
    void timerFiredTranscriptGoesThroughTheRealLaneAndConfirmsPublication() {
        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult result = reconciler.reconcileNonThreadedMessage(message);
        sessionScheduler.scheduleOrReschedule(result.groupState());

        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        when(payloadMapper.map(any(), any())).thenAnswer(invocation -> eventFor(invocation.getArgument(0)));

        taskScheduler.scheduled.get(0).task().run();

        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.published()).isTrue();
        assertThat(state.claimedRevision()).isNull();
    }

    /**
     * A published group is edited: mergeIntoGroupState correctly produces a fresh unpublished
     * revision under the same correlationId. This proves that revision then actually gets
     * scheduled, claimed, and published through the real lane - the fix this test targets is
     * exactly that SlackEventsController now schedules based on groupState().published(), not on
     * newActivity(), so this edited revision is not silently stranded.
     */
    @Test
    void editOfAPublishedGroupGetsScheduledClaimedAndPublishedAsTheNewRevision() {
        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult first = reconciler.reconcileNonThreadedMessage(message);
        String groupId = first.groupState().groupId();
        String correlationId = first.groupState().correlationId();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", groupId, first.groupState().revision());
        assertThat(groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow().published()).isTrue();

        ChatMessage edit = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                "hi (edited)", List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult editResult = reconciler.reconcileNonThreadedMessage(edit);
        assertThat(editResult.groupState().published()).isFalse();
        assertThat(editResult.groupState().revision()).isEqualTo(1);

        // Mirrors SlackEventsController.reconcileAndPublish's fixed scheduling condition.
        if (!editResult.groupState().published()) {
            sessionScheduler.scheduleOrReschedule(editResult.groupState());
        }
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        when(payloadMapper.map(any(), any())).thenAnswer(invocation -> eventFor(invocation.getArgument(0)));

        assertThat(taskScheduler.scheduled).hasSize(1);
        taskScheduler.scheduled.get(0).task().run();

        GroupState finalState = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(finalState.published()).isTrue();
        assertThat(finalState.revision()).isEqualTo(1);
        assertThat(finalState.correlationId()).isEqualTo(correlationId);
    }

    /**
     * The deterministic concurrency regression required by this step:
     *     scheduler claims X/R -> publication held in-flight -> edit arrives ->
     *     X becomes R+1 and gets newly scheduled -> old publication/confirm completes ->
     *     stale confirm does not affect R+1 -> R+1 can subsequently publish successfully.
     * payloadPublisher.publish is stubbed to block (via latches, never a sleep) exactly while
     * revision R's Kafka publish is "in flight", so the edit is applied deterministically inside
     * that window - the same window identified as racy in the session-lifecycle inspection.
     */
    @Test
    void editArrivingWhileRIsInFlightGetsItsOwnScheduleAndIsUnaffectedByRsStaleConfirm() throws Exception {
        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult first = reconciler.reconcileNonThreadedMessage(message);
        String groupId = first.groupState().groupId();
        sessionScheduler.scheduleOrReschedule(first.groupState());
        clock.set(Instant.parse("2026-08-18T09:45:00Z"));
        when(payloadMapper.map(any(), any())).thenAnswer(invocation -> eventFor(invocation.getArgument(0)));

        CountDownLatch publishingR = new CountDownLatch(1);
        CountDownLatch editApplied = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishingR.countDown();
            assertThat(editApplied.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(payloadPublisher).publish(any());

        ExecutorService timerExecutor = Executors.newSingleThreadExecutor();
        try {
            timerExecutor.submit(taskScheduler.scheduled.get(0).task());
            assertThat(publishingR.await(10, TimeUnit.SECONDS)).isTrue();

            // R's Kafka publish is blocked mid-flight - apply the edit now, inside that window.
            ChatMessage edit = new ChatMessage(
                    RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                    "hi (edited)", List.of(), "m1", null);
            ChatGroupStateReconciler.RealtimeReconcileResult editResult = reconciler.reconcileNonThreadedMessage(edit);
            assertThat(editResult.groupState().revision()).isEqualTo(1);
            assertThat(editResult.groupState().claimedRevision()).isNull();
            if (!editResult.groupState().published()) {
                sessionScheduler.scheduleOrReschedule(editResult.groupState());
            }
            assertThat(taskScheduler.scheduled).hasSize(2); // T1 (R) still registered, T2 (R+1) now installed

            editApplied.countDown();
            timerExecutor.shutdown();
            assertThat(timerExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            timerExecutor.shutdownNow();
        }

        // R's afterPublish -> confirmPublished(0) ran against a GroupState already at revision 1
        // - the pre-existing stale-revision guard makes it a no-op.
        GroupState afterRsConfirm = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterRsConfirm.revision()).isEqualTo(1);
        assertThat(afterRsConfirm.published()).isFalse();

        // T2 (R+1) can still fire and publish successfully afterward.
        taskScheduler.scheduled.get(1).task().run();

        GroupState finalState = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(finalState.published()).isTrue();
        assertThat(finalState.revision()).isEqualTo(1);
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
