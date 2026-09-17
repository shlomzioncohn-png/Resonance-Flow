package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PostgresDependencyHistoryRepositoryTest {

    @Autowired
    private PostgresDependencyHistoryRepository repository;

    private static DependencyHistoryEntry.Builder newEntry(UUID taskId, UUID dependsOnTaskId) {
        return DependencyHistoryEntry.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .dependsOnTaskId(dependsOnTaskId)
                .action(DependencyChangeAction.ADDED)
                .source(DependencyChangeSource.USER)
                .createdAt(Instant.now());
    }

    @Test
    void record_persistsEntry_retrievableByTaskId() {
        UUID taskId = UUID.randomUUID();
        DependencyHistoryEntry entry = newEntry(taskId, UUID.randomUUID()).build();

        repository.record(entry);

        PageResponse<DependencyHistoryEntry> page = repository.findByTaskId(taskId, 0, 10);
        assertThat(page.content()).containsExactly(entry);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void record_isIdempotent_forSameSourceAndSourceRef() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        DependencyHistoryEntry first = newEntry(taskId, dependsOnTaskId)
                .source(DependencyChangeSource.MIND).sourceRef("evt-1").build();
        DependencyHistoryEntry second = newEntry(taskId, dependsOnTaskId)
                .source(DependencyChangeSource.MIND).sourceRef("evt-1").build();

        DependencyHistoryEntry recordedFirst = repository.record(first);
        DependencyHistoryEntry recordedSecond = repository.record(second);

        assertThat(recordedSecond.getId()).isEqualTo(recordedFirst.getId());
        assertThat(repository.findByTaskId(taskId, 0, 10).totalElements()).isEqualTo(1);
    }

    @Test
    void findBySourceAndSourceRef_returnsEmpty_whenSourceRefIsNull() {
        assertThat(repository.findBySourceAndSourceRef(DependencyChangeSource.USER, null)).isEmpty();
    }

    @Test
    void findByTaskId_ordersNewestFirst() {
        UUID taskId = UUID.randomUUID();
        DependencyHistoryEntry older = newEntry(taskId, UUID.randomUUID())
                .createdAt(Instant.now().minusSeconds(60)).build();
        DependencyHistoryEntry newer = newEntry(taskId, UUID.randomUUID())
                .createdAt(Instant.now()).build();

        repository.record(older);
        repository.record(newer);

        PageResponse<DependencyHistoryEntry> page = repository.findByTaskId(taskId, 0, 10);
        assertThat(page.content()).extracting(DependencyHistoryEntry::getId)
                .containsExactly(newer.getId(), older.getId());
    }
}
