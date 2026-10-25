package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.lane.DriveFileStateStore;
import com.rnd.bridge.intake.model.DriveFileState;
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
 * Covers the identity/revision decision in DriveFileStateReconciler.resolve - the direct Drive
 * analog of CalendarEventStateReconcilerTest (see its javadoc for the full reasoning this
 * mirrors), substituting fileId for eventId and headRevisionId for etag. Uses a real store
 * backed by an in-memory ControlPlaneStateStore/ObjectStorageClient pair rather than mocks,
 * since the property under test - atomic per-fileId read-modify-write - is exactly what a mock
 * would hide.
 */
class DriveFileStateReconcilerTest {

    private static final String TENANT = "kamatech";

    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final DriveFileStateStore store = new DriveFileStateStore(
            new InMemoryObjectStorageClient(backingStore), ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper());
    private final DriveFileStateReconciler reconciler = new DriveFileStateReconciler(store);

    @Test
    void firstSeenFileGetsANewUuidCorrelationIdAndRevisionZero() {
        DriveFileState state = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null);

        assertThat(UUID.fromString(state.correlationId())).isNotNull();
        assertThat(state.revision()).isZero();
        assertThat(state.published()).isFalse();
    }

    @Test
    void aGenuineUpdateReusesTheCorrelationIdAndIncrementsRevision() {
        DriveFileState first = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null);

        DriveFileState updated = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-2", null);

        assertThat(updated.correlationId()).isEqualTo(first.correlationId());
        assertThat(updated.revision()).isEqualTo(1);
        assertThat(updated.published()).isFalse();
    }

    @Test
    void aSecondGenuineUpdateIncrementsRevisionAgain() {
        reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null);
        reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-2", null);

        DriveFileState secondUpdate = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-3", null);

        assertThat(secondUpdate.revision()).isEqualTo(2);
    }

    /**
     * A publish failure means the next poll re-reads the identical Drive delta - same
     * headRevisionId, since nothing actually changed server-side. That retry must reuse the
     * same revision, not advance it again.
     */
    @Test
    void retryOfTheSameHeadRevisionIdAfterAFailedPublishReusesTheSameRevision() {
        reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null); // revision 0
        DriveFileState edited = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-2", null); // revision 1
        assertThat(edited.revision()).isEqualTo(1);

        DriveFileState retry = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-2", null);

        assertThat(retry.revision()).isEqualTo(1); // NOT 2
        assertThat(retry.correlationId()).isEqualTo(edited.correlationId());
        assertThat(retry.published()).isFalse();
    }

    @Test
    void twoDifferentFileIdsGetIndependentCorrelationIdsAndRevisions() {
        DriveFileState fileA = reconciler.resolve(TENANT, "file-a", "application/pdf", "rev-1", null);
        DriveFileState fileB = reconciler.resolve(TENANT, "file-b", "application/pdf", "rev-1", null);
        reconciler.resolve(TENANT, "file-a", "application/pdf", "rev-2", null); // only A changes

        DriveFileState fileAAfter = reconciler.resolve(TENANT, "file-a", "application/pdf", "rev-2", null);
        DriveFileState fileBAfter = reconciler.resolve(TENANT, "file-b", "application/pdf", "rev-1", null);

        assertThat(fileAAfter.correlationId()).isEqualTo(fileA.correlationId()).isNotEqualTo(fileB.correlationId());
        assertThat(fileAAfter.revision()).isEqualTo(1);
        assertThat(fileBAfter.revision()).isZero();
    }

    @Test
    void successfulPublishMarksTheExpectedRevisionPublished() {
        DriveFileState state = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null);

        store.confirmPublished(TENANT, "file-1", state.revision());

        DriveFileState confirmed = store.load(TENANT, "file-1").orElseThrow();
        assertThat(confirmed.published()).isTrue();
        assertThat(confirmed.revision()).isZero();
    }

    @Test
    void aStalePublishConfirmationCannotMarkANewerRevisionPublished() {
        reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null); // revision 0
        reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-2", null); // revision 1, unconfirmed

        store.confirmPublished(TENANT, "file-1", 0); // stale ack

        DriveFileState afterStaleAck = store.load(TENANT, "file-1").orElseThrow();
        assertThat(afterStaleAck.revision()).isEqualTo(1);
        assertThat(afterStaleAck.published()).isFalse();
    }

    /**
     * Being trashed, from the reconciler's point of view, is nothing special - it's just
     * another headRevisionId change (see RealDriveSourceReader.toTrashedSourceRecord: only the
     * resulting SourceRecord's changeType marks it as a deletion, never the reconciler's own
     * identity/revision decision).
     */
    @Test
    void aTrashedChangeAfterAPublishedUpsertReusesCorrelationIdAndIncrementsRevision() {
        DriveFileState published = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null);
        store.confirmPublished(TENANT, "file-1", published.revision());

        DriveFileState trashed = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-trashed", null);

        assertThat(trashed.correlationId()).isEqualTo(published.correlationId());
        assertThat(trashed.revision()).isEqualTo(1);
        assertThat(trashed.published()).isFalse();
    }

    @Test
    void retryOfTheSameTrashedHeadRevisionIdAfterAFailedPublishReusesTheSameRevision() {
        reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-1", null); // revision 0
        DriveFileState trashed = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-trashed", null); // revision 1
        assertThat(trashed.revision()).isEqualTo(1);

        DriveFileState retry = reconciler.resolve(TENANT, "file-1", "application/pdf", "rev-trashed", null);

        assertThat(retry.revision()).isEqualTo(1); // NOT 2
        assertThat(retry.correlationId()).isEqualTo(trashed.correlationId());
    }

    @Test
    void firstSeenTrashedFileStillGetsANewUuidCorrelationIdAndRevisionZero() {
        DriveFileState state = reconciler.resolve(TENANT, "file-never-seen", "application/pdf", "rev-trashed", null);

        assertThat(UUID.fromString(state.correlationId())).isNotNull();
        assertThat(state.revision()).isZero();
        assertThat(state.published()).isFalse();
    }

    @Test
    void confirmPublishedIsANoOpWhenNothingIsPersistedForThatFileId() {
        store.confirmPublished(TENANT, "never-seen", 0);

        assertThat(store.load(TENANT, "never-seen")).isEmpty();
    }

    @Test
    void concurrentUpdateAndTrashOperationsProduceACleanRevisionSequence() throws Exception {
        DriveFileState baseline = reconciler.resolve(TENANT, "file-race-3", "application/pdf", "rev-0", null); // revision 0
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<DriveFileState> updateFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return reconciler.resolve(TENANT, "file-race-3", "application/pdf", "rev-update", null);
            });
            Future<DriveFileState> trashFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return reconciler.resolve(TENANT, "file-race-3", "application/pdf", "rev-trashed", null);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            DriveFileState updateResult = updateFuture.get(10, TimeUnit.SECONDS);
            DriveFileState trashResult = trashFuture.get(10, TimeUnit.SECONDS);

            assertThat(updateResult.correlationId()).isEqualTo(baseline.correlationId());
            assertThat(trashResult.correlationId()).isEqualTo(baseline.correlationId());
            Set<Integer> revisions = Set.of(updateResult.revision(), trashResult.revision());
            assertThat(revisions).containsExactlyInAnyOrder(1, 2);

            DriveFileState finalState = store.load(TENANT, "file-race-3").orElseThrow();
            assertThat(finalState.revision()).isEqualTo(2);
            assertThat(finalState.correlationId()).isEqualTo(baseline.correlationId());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void concurrentFirstReadsOfTheSameNeverBeforeSeenFileAllAgreeOnOneCorrelationId() throws Exception {
        int callers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<DriveFileState>> futures = IntStream.range(0, callers)
                    .mapToObj(i -> pool.submit(() -> reconciler.resolve(TENANT, "file-race", "application/pdf", "rev-1", null)))
                    .toList();

            Set<String> correlationIds = new HashSet<>();
            Set<Integer> revisions = new HashSet<>();
            for (Future<DriveFileState> future : futures) {
                DriveFileState state = future.get(10, TimeUnit.SECONDS);
                correlationIds.add(state.correlationId());
                revisions.add(state.revision());
            }

            assertThat(correlationIds).hasSize(1);
            assertThat(revisions).containsExactly(0);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void concurrentUpdatesToTheSameFileProduceACleanRevisionSequence() throws Exception {
        reconciler.resolve(TENANT, "file-race-2", "application/pdf", "rev-0", null); // revision 0, baseline
        int updates = 10;
        ExecutorService pool = Executors.newFixedThreadPool(updates);
        CountDownLatch ready = new CountDownLatch(updates);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<DriveFileState>> futures = IntStream.range(0, updates)
                    .mapToObj(i -> pool.<DriveFileState>submit(() -> {
                        ready.countDown();
                        go.await();
                        return reconciler.resolve(TENANT, "file-race-2", "application/pdf", "rev-" + (i + 1), null);
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

            assertThat(revisions).containsExactlyElementsOf(IntStream.rangeClosed(1, updates).boxed().toList());
        } finally {
            pool.shutdown();
        }
    }

    // --- Native Google file (Doc/Sheet/Slide) change detection via version, not headRevisionId --

    private static final String NATIVE_MIME_TYPE = "application/vnd.google-apps.document";

    @Test
    void nativeGoogleDocFirstSeenStartsAtRevisionZero() {
        DriveFileState state = reconciler.resolve(TENANT, "doc-1", NATIVE_MIME_TYPE, null, 100L);

        assertThat(state.revision()).isZero();
        assertThat(state.headRevisionId()).isNull();
        assertThat(state.version()).isEqualTo(100L);
    }

    @Test
    void nativeGoogleDocEditIsDetectedByVersionEvenThoughHeadRevisionIdStaysNull() {
        reconciler.resolve(TENANT, "doc-1", NATIVE_MIME_TYPE, null, 100L); // revision 0

        DriveFileState edited = reconciler.resolve(TENANT, "doc-1", NATIVE_MIME_TYPE, null, 101L);

        assertThat(edited.revision()).isEqualTo(1);
        assertThat(edited.headRevisionId()).isNull();
    }

    @Test
    void nativeGoogleDocRetryWithTheSameVersionStaysUnchanged() {
        DriveFileState edited = reconciler.resolve(TENANT, "doc-1", NATIVE_MIME_TYPE, null, 101L);

        DriveFileState retry = reconciler.resolve(TENANT, "doc-1", NATIVE_MIME_TYPE, null, 101L);

        assertThat(retry.revision()).isEqualTo(edited.revision());
        assertThat(retry.correlationId()).isEqualTo(edited.correlationId());
    }

    /**
     * A record persisted before the `version` field existed deserializes with version == null
     * (Jackson leaves a missing JSON property as null for a reference-typed record component).
     * The first resolve() call afterwards that sees a real, non-null version from Drive must be
     * treated as a change - otherwise a native file upgraded from the old headRevisionId-only
     * behavior would stay stuck at its pre-upgrade revision forever, since headRevisionId alone
     * never distinguishes two different edits of a native file (both are always null).
     */
    @Test
    void firstChangeAfterUpgradeForAPreExistingNativeFileWithNoStoredVersionBumpsRevisionOnce() {
        String preUpgradeJson = "{\"fileId\":\"doc-legacy\",\"tenantId\":\"" + TENANT + "\","
                + "\"correlationId\":\"corr-legacy\",\"headRevisionId\":null,\"revision\":0,\"published\":false}";
        backingStore.put("_drive_files/" + TENANT + "/doc-legacy.json", preUpgradeJson.getBytes());

        DriveFileState afterUpgrade = reconciler.resolve(TENANT, "doc-legacy", NATIVE_MIME_TYPE, null, 7L);

        assertThat(afterUpgrade.correlationId()).isEqualTo("corr-legacy");
        assertThat(afterUpgrade.revision()).isEqualTo(1);
        assertThat(afterUpgrade.version()).isEqualTo(7L);

        DriveFileState retryAfterUpgrade = reconciler.resolve(TENANT, "doc-legacy", NATIVE_MIME_TYPE, null, 7L);

        assertThat(retryAfterUpgrade.revision()).isEqualTo(1);
    }
}
