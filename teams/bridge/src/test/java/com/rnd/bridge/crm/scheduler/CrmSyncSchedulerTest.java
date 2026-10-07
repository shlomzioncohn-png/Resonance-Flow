package com.rnd.bridge.crm.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * syncEnabled is set via ReflectionTestUtils, not a constructor arg - it's
 * {@code @Value}-injected in production the same way GitHubSyncScheduler's
 * is, and this stays a plain unit test rather than a full context load.
 */
class CrmSyncSchedulerTest {

    private final CrmLane crmLane = mock(CrmLane.class);
    private final DealRepository dealRepository = mock(DealRepository.class);
    private final CrmSyncScheduler scheduler = new CrmSyncScheduler(crmLane, dealRepository);

    private static DealSnapshot snapshotAt(Instant asOf) {
        return new DealSnapshot(asOf, List.of(), Map.of(), List.of());
    }

    @Test
    void doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", false);

        scheduler.syncCrmTasks();

        verify(dealRepository, never()).snapshot();
        verify(crmLane, never()).run(any(Stream.class), any());
    }

    @Test
    void runsTheLaneWithTheSnapshotsAsOfWhenEnabled() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        Instant asOf = Instant.parse("2026-09-07T03:00:00Z");
        when(dealRepository.snapshot()).thenReturn(snapshotAt(asOf));
        when(crmLane.run(any(Stream.class), eq(asOf))).thenReturn(new IntakeResult(5, 5, 0, List.of()));

        scheduler.syncCrmTasks();

        verify(crmLane).run(any(Stream.class), eq(asOf));
    }

    @Test
    void aRuntimeExceptionFromTheLaneIsCaughtNotPropagated() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        when(dealRepository.snapshot()).thenReturn(snapshotAt(Instant.now()));
        when(crmLane.run(any(Stream.class), any()))
                .thenThrow(new RuntimeException("HubSpot unreachable"));

        assertDoesNotThrow(scheduler::syncCrmTasks);
    }

    @Test
    void aRuntimeExceptionFromTheSnapshotFetchItselfIsCaughtNotPropagated() {
        // HubSpot unreachable / retries exhausted - the fetch inside snapshot() throws before
        // crmLane.run() is ever called.
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        when(dealRepository.snapshot()).thenThrow(new RuntimeException("HubSpot unreachable"));

        assertDoesNotThrow(scheduler::syncCrmTasks);
    }
}
