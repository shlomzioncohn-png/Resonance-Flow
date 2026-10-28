package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskBlockedSince;
import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TenantStateHistoryPage;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.repository.TaskStateHistoryRepository;
import com.rnd.chain.testsupport.PgRepos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against the real Postgres repositories (rolled back per test), like {@link TaskStateHistoryServiceTest}. */
@Tag("postgres")
@SpringBootTest
@Transactional
class TenantStateHistoryServiceTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");

    // Random tenants: the shared local DB may hold committed history under the default tenant.
    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID taskA = UUID.randomUUID();
    private final UUID taskB = UUID.randomUUID();
    private final UUID otherTenantTask = UUID.randomUUID();
    private TaskStateHistoryRepository history;
    private TenantStateHistoryService service;

    @BeforeEach
    void setUp() {
        PgRepos.tasks(List.of(task(taskA, tenantId), task(taskB, tenantId), task(otherTenantTask, otherTenantId)));
        history = PgRepos.stateHistory();
        service = new TenantStateHistoryService(history, PgRepos.transactionRunner());
    }

    private static Task task(UUID id, UUID tenant) {
        return Task.builder().id(id).tenantId(tenant).status(TaskStatus.ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private UUID status(UUID taskId, Instant at) {
        UUID id = UUID.randomUUID();
        history.record(TaskStateHistoryEntry.statusChanged(id, taskId, TaskStatus.PENDING, TaskStatus.ACTIVE, at));
        return id;
    }

    private void blocked(UUID taskId, Instant at) {
        history.record(TaskStateHistoryEntry.blocked(UUID.randomUUID(), taskId, 1, at));
    }

    private void unblocked(UUID taskId, Instant at) {
        history.record(TaskStateHistoryEntry.unblocked(UUID.randomUUID(), taskId, 1, at));
    }

    private TenantStateHistoryPage page(Instant from, Instant to, TaskStateEventType type, String after, int size) {
        return service.getHistory(tenantId, from, to, type, after, size);
    }

    @Test
    void getHistory_returnsOnlyTheCallersTenant() {
        UUID mine = status(taskA, T1);
        status(otherTenantTask, T1);

        assertThat(page(FROM, TO, null, null, 20).content())
                .extracting(TaskStateHistoryEntry::id).containsExactly(mine);
    }

    @Test
    void getHistory_oldestFirst_fromInclusiveToExclusive() {
        UUID first = status(taskA, T1);
        UUID second = status(taskB, T2);
        status(taskA, T3);

        assertThat(page(T1, T3, null, null, 20).content())
                .extracting(TaskStateHistoryEntry::id).containsExactly(first, second);
    }

    @Test
    void getHistory_eventTypeFilter_keepsOnlyThatType() {
        status(taskA, T1);
        blocked(taskA, T2);

        assertThat(page(FROM, TO, TaskStateEventType.BLOCKED, null, 20).content())
                .extracting(TaskStateHistoryEntry::eventType).containsExactly(TaskStateEventType.BLOCKED);
    }

    @Test
    void getHistory_missingFromOrTo_throwsInvalidRequest() {
        assertThatThrownBy(() -> page(null, TO, null, null, 20)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> page(FROM, null, null, null, 20)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getHistory_fromAfterTo_throwsInvalidRequest() {
        assertThatThrownBy(() -> page(TO, FROM, null, null, 20)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getHistory_rangeOfExactly90DaysIsAllowed_beyondItIsRejected() {
        Instant ninetyDays = FROM.plus(Duration.ofDays(90));

        assertThat(page(FROM, ninetyDays, null, null, 20).content()).isEmpty();
        assertThatThrownBy(() -> page(FROM, ninetyDays.plusSeconds(1), null, null, 20))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getHistory_invalidSize_throwsInvalidRequest() {
        assertThatThrownBy(() -> page(FROM, TO, null, null, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> page(FROM, TO, null, null, 101)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getHistory_invalidCursor_throwsInvalidRequest() {
        assertThatThrownBy(() -> page(FROM, TO, null, "not-a-cursor", 20))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getHistory_cursorPaging_neverSkipsOrRepeatsEvenWithIdenticalCreatedAt() {
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inserted.add(status(i % 2 == 0 ? taskA : taskB, T1));
        }
        inserted.add(status(taskA, T2));

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            TenantStateHistoryPage page = page(FROM, TO, null, cursor, 2);
            page.content().forEach(e -> seen.add(e.id()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(inserted);
    }

    @Test
    void getHistory_lastPageHasNoNextCursor() {
        status(taskA, T1);
        status(taskA, T2);

        TenantStateHistoryPage page = page(FROM, TO, null, null, 2);

        assertThat(page.content()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void getBlockedSince_reblockedAfterUnblocked_showsTheNewBlockTime() {
        blocked(taskA, T1);
        unblocked(taskA, T2);
        blocked(taskA, T3);

        assertThat(service.getBlockedSince(tenantId, null))
                .containsExactly(new TaskBlockedSince(taskA, T3));
    }

    @Test
    void getBlockedSince_unblockedTaskIsNotReturned() {
        blocked(taskA, T1);
        unblocked(taskA, T2);
        blocked(taskB, T2);

        assertThat(service.getBlockedSince(tenantId, null))
                .containsExactly(new TaskBlockedSince(taskB, T2));
    }

    @Test
    void getBlockedSince_otherTenantsTasksAreNeverReturned_evenWhenRequestedByTaskId() {
        blocked(taskA, T1);
        blocked(otherTenantTask, T1);

        assertThat(service.getBlockedSince(tenantId, null)).extracting(TaskBlockedSince::taskId)
                .containsExactly(taskA);
        assertThat(service.getBlockedSince(tenantId, List.of(taskA, otherTenantTask)))
                .extracting(TaskBlockedSince::taskId).containsExactly(taskA);
    }

    @Test
    void getBlockedSince_taskIdsFilterNarrowsTheResult() {
        blocked(taskA, T1);
        blocked(taskB, T2);

        assertThat(service.getBlockedSince(tenantId, List.of(taskB)))
                .containsExactly(new TaskBlockedSince(taskB, T2));
    }
}
