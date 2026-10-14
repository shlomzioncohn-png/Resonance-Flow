package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.lane.CalendarEventStateStore;
import com.rnd.bridge.intake.model.CalendarEventState;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Covers the identity/revision decision in CalendarEventStateReconciler.resolve: first-seen
 * events, genuine updates (etag changed), retries of the same version (etag unchanged - the
 * case a frozen syncToken after a publish failure produces), and publish confirmation via the
 * underlying CalendarEventStateStore. Uses a real store backed by an in-memory
 * ControlPlaneStateStore/ObjectStorageClient pair (same pattern as ChatGroupStateReconcilerTest)
 * rather than mocks, since the property under test - atomic per-eventId read-modify-write - is
 * exactly what a mock would hide.
 */
class CalendarEventStateReconcilerTest {

    private static final String TENANT = "kamatech";

    // Shared so ObjectStorageClient (confirmPublished's pre-check read) and
    // ControlPlaneStateStore (the atomic update) see the same data - in production both hit the
    // same MinIO bucket, same reasoning as GroupStateStore's own test setup.
    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final CalendarEventStateStore store = new CalendarEventStateStore(
            new InMemoryObjectStorageClient(backingStore), ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper());
    private final CalendarEventStateReconciler reconciler = new CalendarEventStateReconciler(store);

    @Test
    void firstSeenEventGetsANewUuidCorrelationIdAndRevisionZero() {
        CalendarEventState state = reconciler.resolve(TENANT, "evt-1", "etag-1");

        assertThat(UUID.fromString(state.correlationId())).isNotNull();
        assertThat(state.revision()).isZero();
        assertThat(state.published()).isFalse();
    }

    @Test
    void aGenuineUpdateReusesTheCorrelationIdAndIncrementsRevision() {
        CalendarEventState first = reconciler.resolve(TENANT, "evt-1", "etag-1");

        CalendarEventState updated = reconciler.resolve(TENANT, "evt-1", "etag-2");

        assertThat(updated.correlationId()).isEqualTo(first.correlationId());
        assertThat(updated.revision()).isEqualTo(1);
        assertThat(updated.published()).isFalse();
    }

    @Test
    void aSecondGenuineUpdateIncrementsRevisionAgain() {
        reconciler.resolve(TENANT, "evt-1", "etag-1");
        reconciler.resolve(TENANT, "evt-1", "etag-2");

        CalendarEventState secondUpdate = reconciler.resolve(TENANT, "evt-1", "etag-3");

        assertThat(secondUpdate.revision()).isEqualTo(2);
    }

    /**
     * The retry scenario requirement 5 describes: event ABC arrives (revision becomes 1 after
     * an edit), its publish fails so the syncToken never advances, and the next poll re-reads
     * the identical Google delta - same etag, since nothing actually changed server-side. That
     * retry must reuse revision 1, not become revision 2.
     */
    @Test
    void retryOfTheSameEtagAfterAFailedPublishReusesTheSameRevision() {
        reconciler.resolve(TENANT, "evt-1", "etag-1"); // revision 0
        CalendarEventState edited = reconciler.resolve(TENANT, "evt-1", "etag-2"); // revision 1
        assertThat(edited.revision()).isEqualTo(1);

        // Publish of revision 1 failed - syncToken frozen, so the next poll re-reads the exact
        // same delta from Google: same eventId, same etag.
        CalendarEventState retry = reconciler.resolve(TENANT, "evt-1", "etag-2");

        assertThat(retry.revision()).isEqualTo(1); // NOT 2
        assertThat(retry.correlationId()).isEqualTo(edited.correlationId());
        assertThat(retry.published()).isFalse(); // still unconfirmed, same as before the retry
    }

    @Test
    void twoDifferentEventIdsGetIndependentCorrelationIdsAndRevisions() {
        CalendarEventState eventA = reconciler.resolve(TENANT, "evt-a", "etag-1");
        CalendarEventState eventB = reconciler.resolve(TENANT, "evt-b", "etag-1");
        reconciler.resolve(TENANT, "evt-a", "etag-2"); // only A changes

        CalendarEventState eventAAfter = reconciler.resolve(TENANT, "evt-a", "etag-2");
        CalendarEventState eventBAfter = reconciler.resolve(TENANT, "evt-b", "etag-1");

        assertThat(eventAAfter.correlationId()).isEqualTo(eventA.correlationId()).isNotEqualTo(eventB.correlationId());
        assertThat(eventAAfter.revision()).isEqualTo(1);
        assertThat(eventBAfter.revision()).isZero(); // untouched by A's update
    }

    @Test
    void successfulPublishMarksTheExpectedRevisionPublished() {
        CalendarEventState state = reconciler.resolve(TENANT, "evt-1", "etag-1");

        store.confirmPublished(TENANT, "evt-1", state.revision());

        CalendarEventState confirmed = store.load(TENANT, "evt-1").orElseThrow();
        assertThat(confirmed.published()).isTrue();
        assertThat(confirmed.revision()).isZero();
    }

    /**
     * A stale ack for an old revision must never mark a newer, since-superseded revision as
     * published - the same expected-revision guard GroupStateStore.confirmPublished uses.
     */
    @Test
    void aStalePublishConfirmationCannotMarkANewerRevisionPublished() {
        reconciler.resolve(TENANT, "evt-1", "etag-1"); // revision 0
        reconciler.resolve(TENANT, "evt-1", "etag-2"); // revision 1, unconfirmed

        // A late-arriving ack for revision 0 (already superseded by the edit above).
        store.confirmPublished(TENANT, "evt-1", 0);

        CalendarEventState afterStaleAck = store.load(TENANT, "evt-1").orElseThrow();
        assertThat(afterStaleAck.revision()).isEqualTo(1);
        assertThat(afterStaleAck.published()).isFalse();
    }

    /**
     * Cancellation, from the reconciler's point of view, is nothing special - it's just another
     * etag change (see RealCalendarSourceReader.toCancelledSourceRecord: only the resulting
     * SourceRecord's changeType marks it as a cancellation, never the reconciler's own
     * identity/revision decision). A published UPSERT at revision 0, cancelled next poll (a
     * different etag - Google's cancellation notice carries its own etag), reuses the same
     * correlationId and advances to revision 1, unconfirmed.
     */
    @Test
    void aCancellationAfterAPublishedUpsertReusesCorrelationIdAndIncrementsRevision() {
        CalendarEventState published = reconciler.resolve(TENANT, "evt-1", "etag-1");
        store.confirmPublished(TENANT, "evt-1", published.revision());

        CalendarEventState cancelled = reconciler.resolve(TENANT, "evt-1", "etag-cancelled");

        assertThat(cancelled.correlationId()).isEqualTo(published.correlationId());
        assertThat(cancelled.revision()).isEqualTo(1);
        assertThat(cancelled.published()).isFalse();
    }

    /** The cancellation counterpart to retryOfTheSameEtagAfterAFailedPublishReusesTheSameRevision:
     * a cancellation whose publish failed must not re-advance the revision on retry either. */
    @Test
    void retryOfTheSameCancellationEtagAfterAFailedPublishReusesTheSameRevision() {
        reconciler.resolve(TENANT, "evt-1", "etag-1"); // revision 0
        CalendarEventState cancelled = reconciler.resolve(TENANT, "evt-1", "etag-cancelled"); // revision 1
        assertThat(cancelled.revision()).isEqualTo(1);

        CalendarEventState retry = reconciler.resolve(TENANT, "evt-1", "etag-cancelled");

        assertThat(retry.revision()).isEqualTo(1); // NOT 2
        assertThat(retry.correlationId()).isEqualTo(cancelled.correlationId());
    }

    @Test
    void successfulCancellationPublishMarksTheCancellationRevisionPublished() {
        reconciler.resolve(TENANT, "evt-1", "etag-1"); // revision 0
        CalendarEventState cancelled = reconciler.resolve(TENANT, "evt-1", "etag-cancelled"); // revision 1

        store.confirmPublished(TENANT, "evt-1", cancelled.revision());

        CalendarEventState confirmed = store.load(TENANT, "evt-1").orElseThrow();
        assertThat(confirmed.revision()).isEqualTo(1);
        assertThat(confirmed.published()).isTrue();
    }

    /**
     * A stale ack for the pre-cancellation UPSERT (revision 0) must never be able to mark the
     * cancellation's revision (1) as published - same expected-revision guard as any other
     * stale ack, but specifically exercised across an UPSERT -> DELETE_REVIEW transition.
     */
    @Test
    void aStaleAckFromThePreCancellationUpsertCannotConfirmTheCancellationRevision() {
        CalendarEventState published = reconciler.resolve(TENANT, "evt-1", "etag-1"); // revision 0
        reconciler.resolve(TENANT, "evt-1", "etag-cancelled"); // revision 1, unconfirmed

        store.confirmPublished(TENANT, "evt-1", published.revision()); // stale ack for revision 0

        CalendarEventState afterStaleAck = store.load(TENANT, "evt-1").orElseThrow();
        assertThat(afterStaleAck.revision()).isEqualTo(1);
        assertThat(afterStaleAck.published()).isFalse();
    }

    /**
     * First-seen cancelled event: Bridge has no prior state for this eventId at all (e.g. the
     * event was created and cancelled between two polls, so only the cancellation ever appears
     * in a syncToken delta - see RealCalendarSourceReader's javadoc for why this is a real
     * scenario, not a hypothetical one). The reconciler treats it exactly like any other
     * never-before-seen eventId: mints a fresh UUID correlationId at revision 0 - it is the
     * SourceRecord layer, not the reconciler, that decides this one is a DELETE_REVIEW.
     */
    @Test
    void firstSeenCancelledEventStillGetsANewUuidCorrelationIdAndRevisionZero() {
        CalendarEventState state = reconciler.resolve(TENANT, "evt-never-seen", "etag-cancelled");

        assertThat(UUID.fromString(state.correlationId())).isNotNull();
        assertThat(state.revision()).isZero();
        assertThat(state.published()).isFalse();
    }

    /**
     * Concurrent update/cancel: several callers racing an UPDATE and a CANCEL (different etags)
     * against the same eventId must still produce a clean, uncorrupted revision sequence and a
     * single agreed-upon correlationId - the per-eventId lock doesn't distinguish between "this
     * etag change is an edit" and "this etag change is a cancellation notice", so the same
     * concurrency guarantee already proven for plain updates applies unchanged here.
     */
    @Test
    void concurrentUpdateAndCancelOperationsProduceACleanRevisionSequence() throws Exception {
        CalendarEventState baseline = reconciler.resolve(TENANT, "evt-race-3", "etag-0"); // revision 0
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<CalendarEventState> updateFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return reconciler.resolve(TENANT, "evt-race-3", "etag-update");
            });
            Future<CalendarEventState> cancelFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return reconciler.resolve(TENANT, "evt-race-3", "etag-cancelled");
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            CalendarEventState updateResult = updateFuture.get(10, TimeUnit.SECONDS);
            CalendarEventState cancelResult = cancelFuture.get(10, TimeUnit.SECONDS);

            // Both racing callers must agree on the same correlationId as the baseline - no
            // duplicate identity minted.
            assertThat(updateResult.correlationId()).isEqualTo(baseline.correlationId());
            assertThat(cancelResult.correlationId()).isEqualTo(baseline.correlationId());
            // Exactly one of the two distinct etags landed at revision 1, and whichever ran
            // second (seeing the other's already-applied change with a THIRD distinct etag
            // relative to its own) landed at revision 2 - never both at the same revision, and
            // never a gap.
            Set<Integer> revisions = Set.of(updateResult.revision(), cancelResult.revision());
            assertThat(revisions).containsExactlyInAnyOrder(1, 2);

            CalendarEventState finalState = store.load(TENANT, "evt-race-3").orElseThrow();
            assertThat(finalState.revision()).isEqualTo(2);
            assertThat(finalState.correlationId()).isEqualTo(baseline.correlationId());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void confirmPublishedIsANoOpWhenNothingIsPersistedForThatEventId() {
        store.confirmPublished(TENANT, "never-seen", 0);

        assertThat(store.load(TENANT, "never-seen")).isEmpty();
    }

    /**
     * Concurrency correctness: many callers racing to resolve the SAME never-before-seen
     * eventId for the first time must still mint exactly one correlationId/revision-0 state -
     * not a different UUID per racing thread, and not a corrupted mix. CalendarEventStateStore's
     * update() runs under ControlPlaneStateStore's per-key lock (keyed on this exact eventId),
     * so every caller either sees "nothing yet" and mints, or already sees a fully-formed state
     * from whichever caller won the race and returns it untouched (etag unchanged).
     */
    @Test
    void concurrentFirstReadsOfTheSameNeverBeforeSeenEventAllAgreeOnOneCorrelationId() throws Exception {
        int callers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<CalendarEventState>> futures = IntStream.range(0, callers)
                    .mapToObj(i -> pool.submit(() -> reconciler.resolve(TENANT, "evt-race", "etag-1")))
                    .toList();

            Set<String> correlationIds = new HashSet<>();
            Set<Integer> revisions = new HashSet<>();
            for (Future<CalendarEventState> future : futures) {
                CalendarEventState state = future.get(10, TimeUnit.SECONDS);
                correlationIds.add(state.correlationId());
                revisions.add(state.revision());
            }

            assertThat(correlationIds).hasSize(1); // every caller agreed on exactly one identity
            assertThat(revisions).containsExactly(0);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Concurrency correctness for genuine updates: several callers racing to apply DIFFERENT
     * etags (distinct content versions) to the same eventId must still produce a
     * strictly-increasing, uncorrupted revision sequence with no duplicate or skipped values -
     * proving the per-eventId lock actually serializes the read-modify-write instead of letting
     * two racing updates both read revision N and both write N+1.
     */
    @Test
    void concurrentUpdatesToTheSameEventProduceACleanRevisionSequence() throws Exception {
        reconciler.resolve(TENANT, "evt-race-2", "etag-0"); // revision 0, baseline
        int updates = 10;
        ExecutorService pool = Executors.newFixedThreadPool(updates);
        CountDownLatch ready = new CountDownLatch(updates);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<CalendarEventState>> futures = IntStream.range(0, updates)
                    .mapToObj(i -> pool.<CalendarEventState>submit(() -> {
                        ready.countDown();
                        go.await();
                        return reconciler.resolve(TENANT, "evt-race-2", "etag-" + (i + 1));
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> revisions = futures.stream().map(f -> {
                try {
                    return f.get(10, TimeUnit.SECONDS).revision();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).sorted().collect(Collectors.toList());

            // revision 0 was the baseline (etag-0); the 10 racing distinct-etag updates must
            // land on exactly revisions 1..10, once each - no duplicates, no gaps.
            assertThat(revisions).containsExactlyElementsOf(IntStream.rangeClosed(1, updates).boxed().toList());
        } finally {
            pool.shutdown();
        }
    }
}
