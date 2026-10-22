package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.entity.TaskEntity;
import com.rnd.chain.repository.jpa.SpringDataTaskJpaRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Proves the native {@code INSERT ... ON CONFLICT} upsert directly, against real PostgreSQL. */
@Tag("postgres")
@SpringBootTest
@Transactional
class PostgresTaskRepositoryTest {

    @Autowired
    private PostgresTaskRepository postgresTaskRepository;

    @Autowired
    private SpringDataTaskJpaRepository jpaRepository;

    @Test
    void save_upsertsViaNativeInsertOnConflict() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        Task first = Task.builder()
                .id(id).status(TaskStatus.ACTIVE)
                .createdAt(now).updatedAt(now).build();

        assertThatCode(() -> postgresTaskRepository.save(first)).doesNotThrowAnyException();
        long versionAfterFirst = jpaRepository.findById(id).orElseThrow().getVersion();

        Task second = Task.builder()
                .id(id).status(TaskStatus.DONE)
                .createdAt(now).updatedAt(now).build();

        assertThatCode(() -> postgresTaskRepository.save(second)).doesNotThrowAnyException();

        List<TaskEntity> rowsForId = jpaRepository.findAllById(List.of(id));
        assertThat(rowsForId).hasSize(1);

        TaskEntity persisted = rowsForId.get(0);
        assertThat(persisted.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(persisted.getVersion()).isGreaterThan(versionAfterFirst);
    }

    @Test
    void save_persistsConfidenceAndPriorityScore() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        Task task = Task.builder()
                .id(id).status(TaskStatus.ACTIVE)
                .score(0.8).confidence(0.5).priorityScore(7.66)
                .createdAt(now).updatedAt(now).build();

        postgresTaskRepository.save(task);

        Task persisted = postgresTaskRepository.findById(id).orElseThrow();
        assertThat(persisted.getScore()).isEqualTo(0.8);
        assertThat(persisted.getConfidence()).isEqualTo(0.5);
        assertThat(persisted.getPriorityScore()).isEqualTo(7.66);
    }

    @Test
    void save_clearingScoreAlsoClearsConfidenceAndPriorityScore() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        Task scored = Task.builder()
                .id(id).status(TaskStatus.ACTIVE)
                .score(0.8).confidence(0.5).priorityScore(7.66)
                .createdAt(now).updatedAt(now).build();
        postgresTaskRepository.save(scored);

        Task cleared = Task.builder()
                .id(id).status(TaskStatus.ACTIVE)
                .createdAt(now).updatedAt(now).build(); // score/confidence/priorityScore left null
        postgresTaskRepository.save(cleared);

        Task persisted = postgresTaskRepository.findById(id).orElseThrow();
        assertThat(persisted.getScore()).isNull();
        assertThat(persisted.getConfidence()).isNull();
        assertThat(persisted.getPriorityScore()).isNull();
    }

    @Test
    void insertIfAbsent_persistsTheTenantId() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        boolean inserted = postgresTaskRepository.insertIfAbsent(Task.builder()
                .id(id).tenantId(tenantId).status(TaskStatus.PENDING)
                .createdAt(now).updatedAt(now).build());

        assertThat(inserted).isTrue();
        assertThat(postgresTaskRepository.findById(id).orElseThrow().getTenantId()).isEqualTo(tenantId);
    }
}
