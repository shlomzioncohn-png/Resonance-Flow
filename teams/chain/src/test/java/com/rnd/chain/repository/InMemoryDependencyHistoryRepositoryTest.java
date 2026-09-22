package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.testsupport.ConcurrentTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDependencyHistoryRepositoryTest {

    private final UUID taskId = UUID.randomUUID();
    private final UUID dependsOnTaskId = UUID.randomUUID();
    private final Instant baseTime = Instant.parse("2026-01-01T12:00:00Z");

    private InMemoryDependencyHistoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDependencyHistoryRepository();
    }

    // --- record / dedup ---------------------------------------------------

    @Test
    void record_withNullSourceRef_alwaysInsertsNewEntry() {
        DependencyHistoryEntry first = repository.record(newEntry(DependencyChangeSource.USER, null, baseTime));
        DependencyHistoryEntry second = repository.record(newEntry(DependencyChangeSource.USER, null, baseTime));

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(repository.findByTaskId(taskId, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void record_withSameSourceAndSourceRef_isNoOp_returnsExistingEntry() {
        DependencyHistoryEntry first = repository.record(newEntry(DependencyChangeSource.MIND, "evt-1", baseTime));
        DependencyHistoryEntry second = repository.record(newEntry(DependencyChangeSource.MIND, "evt-1", baseTime.plusSeconds(60)));

        assertThat(second).isEqualTo(first);
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(repository.findByTaskId(taskId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void record_withDifferentSourceRef_insertsSeparateEntry() {
        repository.record(newEntry(DependencyChangeSource.MIND, "evt-1", baseTime));
        repository.record(newEntry(DependencyChangeSource.MIND, "evt-2", baseTime));

        assertThat(repository.findByTaskId(taskId, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void record_withSameSourceRefButDifferentSource_insertsSeparateEntry() {
        repository.record(newEntry(DependencyChangeSource.MIND, "evt-1", baseTime));
        repository.record(newEntry(DependencyChangeSource.SYSTEM, "evt-1", baseTime));

        assertThat(repository.findByTaskId(taskId, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void findBySourceAndSourceRef_isEmptyBeforeRecord_containsTheEntryAfter() {
        assertThat(repository.findBySourceAndSourceRef(DependencyChangeSource.MIND, "evt-1")).isEmpty();

        DependencyHistoryEntry recorded = repository.record(newEntry(DependencyChangeSource.MIND, "evt-1", baseTime));

        assertThat(repository.findBySourceAndSourceRef(DependencyChangeSource.MIND, "evt-1")).contains(recorded);
    }

    @Test
    void findBySourceAndSourceRef_isEmpty_forNullSourceRef() {
        assertThat(repository.findBySourceAndSourceRef(DependencyChangeSource.USER, null)).isEmpty();
    }

    // --- delete: compensates a speculatively-recorded entry ----------------

    @Test
    void delete_removesTheEntry_notReturnedByFindByTaskIdAfterwards() {
        DependencyHistoryEntry recorded = repository.record(newEntry(DependencyChangeSource.USER, null, baseTime));

        repository.delete(recorded.id());

        assertThat(repository.findByTaskId(taskId, 0, 20).content()).isEmpty();
    }

    @Test
    void delete_freesTheSourceRef_forARetryToReuse() {
        DependencyHistoryEntry recorded = repository.record(newEntry(DependencyChangeSource.MIND, "evt-1", baseTime));
        repository.delete(recorded.id());

        DependencyHistoryEntry second = repository.record(newEntry(DependencyChangeSource.MIND, "evt-1", baseTime.plusSeconds(60)));

        assertThat(second.id()).isNotEqualTo(recorded.id());
        assertThat(repository.findBySourceAndSourceRef(DependencyChangeSource.MIND, "evt-1")).contains(second);
    }

    @Test
    void delete_ofUnknownId_isNoOp() {
        repository.delete(UUID.randomUUID());

        assertThat(repository.findByTaskId(taskId, 0, 20).content()).isEmpty();
    }

    // --- findByTaskId: filtering, ordering, pagination ---------------------

    @Test
    void findByTaskId_returnsEmptyPage_whenNoEntries() {
        PageResponse<DependencyHistoryEntry> page = repository.findByTaskId(taskId, 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(0);
        assertThat(page.totalPages()).isEqualTo(0);
    }

    @Test
    void findByTaskId_onlyReturnsEntriesForThatTask() {
        repository.record(newEntry(taskId, DependencyChangeSource.USER, null, baseTime));
        repository.record(newEntry(UUID.randomUUID(), DependencyChangeSource.USER, null, baseTime));

        PageResponse<DependencyHistoryEntry> page = repository.findByTaskId(taskId, 0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).taskId()).isEqualTo(taskId);
    }

    @Test
    void findByTaskId_ordersNewestFirst() {
        DependencyHistoryEntry oldest = repository.record(newEntry(DependencyChangeSource.USER, null, baseTime));
        DependencyHistoryEntry middle = repository.record(newEntry(DependencyChangeSource.USER, null, baseTime.plusSeconds(60)));
        DependencyHistoryEntry newest = repository.record(newEntry(DependencyChangeSource.USER, null, baseTime.plusSeconds(120)));

        List<DependencyHistoryEntry> content = repository.findByTaskId(taskId, 0, 20).content();

        assertThat(content).extracting(DependencyHistoryEntry::id)
                .containsExactly(newest.id(), middle.id(), oldest.id());
    }

    @Test
    void findByTaskId_breaksSameTimestampTiesByTrueInsertionOrder_notById() {
        // Deliberately record the numerically-higher UUID FIRST and the lower one SECOND,
        // so a (wrong) tiebreak on id would give the opposite order from a (correct)
        // tiebreak on actual insertion order - this distinguishes the two, unlike a test
        // where id order happens to match insertion order.
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        repository.record(entryWithId(higherId, DependencyChangeSource.USER, null, baseTime));
        repository.record(entryWithId(lowerId, DependencyChangeSource.USER, null, baseTime));

        List<DependencyHistoryEntry> content = repository.findByTaskId(taskId, 0, 20).content();

        assertThat(content).extracting(DependencyHistoryEntry::id)
                .containsExactly(lowerId, higherId);
    }

    @Test
    void findByTaskId_paginates() {
        for (int i = 0; i < 3; i++) {
            repository.record(newEntry(DependencyChangeSource.USER, null, baseTime.plusSeconds(i * 60L)));
        }

        PageResponse<DependencyHistoryEntry> firstPage = repository.findByTaskId(taskId, 0, 1);
        PageResponse<DependencyHistoryEntry> secondPage = repository.findByTaskId(taskId, 1, 1);
        PageResponse<DependencyHistoryEntry> thirdPage = repository.findByTaskId(taskId, 2, 1);

        assertThat(firstPage.content()).hasSize(1);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(secondPage.content()).hasSize(1);
        assertThat(thirdPage.content()).hasSize(1);
        assertThat(firstPage.content().get(0).id())
                .isNotEqualTo(secondPage.content().get(0).id())
                .isNotEqualTo(thirdPage.content().get(0).id());
    }

    @Test
    void findByTaskId_pageBeyondLastPage_returnsEmptyContent() {
        repository.record(newEntry(DependencyChangeSource.USER, null, baseTime));

        PageResponse<DependencyHistoryEntry> page = repository.findByTaskId(taskId, 5, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void findByTaskId_hugePageNumber_doesNotOverflow_returnsEmptyContentInstead() {
        repository.record(newEntry(DependencyChangeSource.USER, null, baseTime));

        // page * size = 2_147_483_700, which overflows a 32-bit int (max 2_147_483_647) if
        // computed naively - must clamp to an empty page like any other out-of-range page,
        // not throw IndexOutOfBoundsException from a negative, wrapped-around index.
        PageResponse<DependencyHistoryEntry> page = repository.findByTaskId(taskId, 21_474_837, 100);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
    }

    // --- concurrency --------------------------------------------------------

    @Test
    void record_underConcurrentSameSourceRef_recordsExactlyOnce() throws InterruptedException {
        int threadCount = 20;
        ConcurrentHashMap<UUID, Boolean> resultIds = new ConcurrentHashMap<>();

        ConcurrentTestRunner.run(threadCount, () -> {
            DependencyHistoryEntry result = repository.record(newEntry(DependencyChangeSource.MIND, "evt-race", baseTime));
            resultIds.put(result.id(), true);
        });

        assertThat(resultIds).hasSize(1);
        assertThat(repository.findByTaskId(taskId, 0, 20).totalElements()).isEqualTo(1);
    }

    private DependencyHistoryEntry newEntry(DependencyChangeSource source, String sourceRef, Instant createdAt) {
        return newEntry(taskId, source, sourceRef, createdAt);
    }

    private DependencyHistoryEntry newEntry(UUID taskId, DependencyChangeSource source, String sourceRef, Instant createdAt) {
        return entryWithId(UUID.randomUUID(), taskId, source, sourceRef, createdAt);
    }

    private DependencyHistoryEntry entryWithId(UUID id, DependencyChangeSource source, String sourceRef, Instant createdAt) {
        return entryWithId(id, taskId, source, sourceRef, createdAt);
    }

    private DependencyHistoryEntry entryWithId(UUID id, UUID taskId, DependencyChangeSource source, String sourceRef, Instant createdAt) {
        return DependencyHistoryEntry.builder()
                .id(id)
                .taskId(taskId)
                .dependsOnTaskId(dependsOnTaskId)
                .action(DependencyChangeAction.ADDED)
                .source(source)
                .sourceRef(sourceRef)
                .createdAt(createdAt)
                .build();
    }
}
