package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        return new ConstraintHistoryEntry(UUID.randomUUID(), "c-" + UUID.randomUUID(), taskId, action,
                previous, next, "source-ref", createdAt);
    }

    @Test
    void record_createdEntryWithNullPreviousStatus_roundTrips() {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ConstraintHistoryEntry created = entry(taskId, ConstraintHistoryAction.CREATED,
                null, Constraint.ConstraintStatus.PENDING, now);

        historyRepository.record(created);

        assertThat(historyRepository.findByTaskId(taskId)).containsExactly(created);
        ConstraintHistoryEntry found = historyRepository.findByTaskId(taskId).get(0);
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
                .extracting(ConstraintHistoryEntry::id)
                .containsExactly(second.id(), first.id(), older.id());
    }

    @Test
    void findByTaskId_unknownTask_returnsEmptyList() {
        assertThat(historyRepository.findByTaskId(UUID.randomUUID())).isEmpty();
    }
}
