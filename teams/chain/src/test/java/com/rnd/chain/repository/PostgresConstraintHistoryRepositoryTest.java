package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link PostgresConstraintHistoryRepository} against a real Postgres instance.
 * {@code @Tag("postgres")}: excluded from the default {@code mvn test}, run with {@code -Ppostgres-integration}.
 */
@SpringBootTest
@Tag("postgres")
class PostgresConstraintHistoryRepositoryTest {

    @Autowired
    private ConstraintHistoryRepository historyRepository;

    private static ConstraintHistoryEntry entry(UUID taskId, ConstraintHistoryAction action,
                                                Constraint.ConstraintStatus previous,
                                                Constraint.ConstraintStatus next, Instant createdAt) {
        return new ConstraintHistoryEntry(null, "c-" + UUID.randomUUID(), taskId, action,
                previous, next, "source-ref", createdAt);
    }

    @Test
    void record_createdEntryWithNullPreviousStatus_roundTrips() {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ConstraintHistoryEntry created = entry(taskId, ConstraintHistoryAction.CREATED,
                null, Constraint.ConstraintStatus.PENDING, now);

        historyRepository.record(created);

        ConstraintHistoryEntry found = historyRepository.findByTaskId(taskId).get(0);
        assertThat(found).usingRecursiveComparison().ignoringFields("id").isEqualTo(created);
        assertThat(found.id()).isNotNull();
        assertThat(found.previousStatus()).isNull();
        assertThat(found.sourceRef()).isEqualTo("source-ref");
        assertThat(found.createdAt()).isEqualTo(now);
    }

    @Test
    void findByTaskId_returnsNewestFirst_andBreaksTimestampTiesBySequence() {
        UUID taskId = UUID.randomUUID();
        Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ConstraintHistoryEntry first = entry(taskId, ConstraintHistoryAction.CREATED,
                null, Constraint.ConstraintStatus.PENDING, sameInstant);
        ConstraintHistoryEntry second = entry(taskId, ConstraintHistoryAction.STATUS_CHANGED,
                Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED, sameInstant);
        ConstraintHistoryEntry older = entry(taskId, ConstraintHistoryAction.CREATED,
                null, Constraint.ConstraintStatus.PENDING, sameInstant.minusSeconds(60));

        historyRepository.record(first);
        historyRepository.record(second);
        historyRepository.record(older);

        assertThat(historyRepository.findByTaskId(taskId))
                .extracting(ConstraintHistoryEntry::constraintId)
                .containsExactly(second.constraintId(), first.constraintId(), older.constraintId());
    }

    @Test
    void findByTaskId_unknownTask_returnsEmptyList() {
        assertThat(historyRepository.findByTaskId(UUID.randomUUID())).isEmpty();
    }

    private List<ConstraintHistoryEntry> recordEntries(UUID taskId, Instant newest, int count) {
        List<ConstraintHistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ConstraintHistoryEntry e = entry(taskId, ConstraintHistoryAction.CREATED,
                    null, Constraint.ConstraintStatus.PENDING, newest.minusSeconds(i));
            historyRepository.record(e);
            entries.add(e);
        }
        return entries;
    }

    @Test
    void findByTaskId_paged_splitsNewestFirstAcrossPages() {
        UUID taskId = UUID.randomUUID();
        Instant newest = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<ConstraintHistoryEntry> entries = recordEntries(taskId, newest, 5);

        PageResponse<ConstraintHistoryEntry> second = historyRepository.findByTaskId(taskId, null, null, 1, 2);

        assertThat(second.content()).extracting(ConstraintHistoryEntry::constraintId)
                .containsExactly(entries.get(2).constraintId(), entries.get(3).constraintId());
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(second.totalPages()).isEqualTo(3);
    }

    @Test
    void findByTaskId_paged_breaksTimestampTiesBySequence() {
        UUID taskId = UUID.randomUUID();
        Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ConstraintHistoryEntry first = entry(taskId, ConstraintHistoryAction.CREATED,
                null, Constraint.ConstraintStatus.PENDING, sameInstant);
        ConstraintHistoryEntry second = entry(taskId, ConstraintHistoryAction.STATUS_CHANGED,
                Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED, sameInstant);
        historyRepository.record(first);
        historyRepository.record(second);

        assertThat(historyRepository.findByTaskId(taskId, null, null, 0, 10).content())
                .extracting(ConstraintHistoryEntry::constraintId)
                .containsExactly(second.constraintId(), first.constraintId());
    }

    @Test
    void findByTaskId_paged_fromIsInclusive_toIsExclusive() {
        UUID taskId = UUID.randomUUID();
        Instant newest = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<ConstraintHistoryEntry> entries = recordEntries(taskId, newest, 3);
        Instant middle = entries.get(1).createdAt();
        Instant oldest = entries.get(2).createdAt();

        assertThat(historyRepository.findByTaskId(taskId, middle, null, 0, 10).content())
                .extracting(ConstraintHistoryEntry::constraintId)
                .containsExactly(entries.get(0).constraintId(), entries.get(1).constraintId());
        assertThat(historyRepository.findByTaskId(taskId, null, middle, 0, 10).content())
                .extracting(ConstraintHistoryEntry::constraintId)
                .containsExactly(entries.get(2).constraintId());
        assertThat(historyRepository.findByTaskId(taskId, oldest, middle, 0, 10).content())
                .extracting(ConstraintHistoryEntry::constraintId)
                .containsExactly(entries.get(2).constraintId());
    }

    @Test
    void findByTaskId_paged_pageBeyondEnd_isEmptyButKeepsTotals() {
        UUID taskId = UUID.randomUUID();
        recordEntries(taskId, Instant.now().truncatedTo(ChronoUnit.MICROS), 3);

        PageResponse<ConstraintHistoryEntry> result = historyRepository.findByTaskId(taskId, null, null, 10, 2);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    void findByTaskId_paged_unknownTask_returnsEmptyPageWithZeroTotals() {
        PageResponse<ConstraintHistoryEntry> result =
                historyRepository.findByTaskId(UUID.randomUUID(), null, null, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void existsByTaskId_reflectsWhetherHistoryExists() {
        UUID taskId = UUID.randomUUID();
        assertThat(historyRepository.existsByTaskId(taskId)).isFalse();

        recordEntries(taskId, Instant.now().truncatedTo(ChronoUnit.MICROS), 1);

        assertThat(historyRepository.existsByTaskId(taskId)).isTrue();
    }
}
