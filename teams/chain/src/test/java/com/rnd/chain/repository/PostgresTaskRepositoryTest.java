package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.entity.TaskEntity;
import com.rnd.chain.repository.jpa.SpringDataTaskJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Proves the native {@code INSERT ... ON CONFLICT} upsert (PERSISTENCE_PLAN.md §4) directly,
 * against real PostgreSQL - replaces reliance on an unverified entityManager.merge()-is-upsert
 * assumption (Round 1 design audit finding #2). */
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
                .id(id).ownerId("owner-1").status(TaskStatus.ACTIVE)
                .blockingCount(1).openConstraintCount(0)
                .createdAt(now).updatedAt(now).build();

        assertThatCode(() -> postgresTaskRepository.save(first)).doesNotThrowAnyException();
        long versionAfterFirst = jpaRepository.findById(id).orElseThrow().getVersion();

        Task second = Task.builder()
                .id(id).ownerId("owner-2").status(TaskStatus.DONE)
                .blockingCount(5).openConstraintCount(2)
                .createdAt(now).updatedAt(now).build();

        assertThatCode(() -> postgresTaskRepository.save(second)).doesNotThrowAnyException();

        List<TaskEntity> rowsForId = jpaRepository.findAllById(List.of(id));
        assertThat(rowsForId).hasSize(1);

        TaskEntity persisted = rowsForId.get(0);
        assertThat(persisted.getOwnerId()).isEqualTo("owner-2");
        assertThat(persisted.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(persisted.getBlockingCount()).isEqualTo(5);
        assertThat(persisted.getOpenConstraintCount()).isEqualTo(2);
        assertThat(persisted.getVersion()).isGreaterThan(versionAfterFirst);
    }
}
