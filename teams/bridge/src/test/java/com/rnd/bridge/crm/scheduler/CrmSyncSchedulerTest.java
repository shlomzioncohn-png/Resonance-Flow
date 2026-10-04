package com.rnd.bridge.crm.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.repository.DealRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

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

    @Test
    void doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", false);

        scheduler.syncCrmTasks();

        verify(crmLane, never()).run(any(), any());
    }

    @Test
    void runsTheLaneWithTheRepositorysAsOfWhenEnabled() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        Instant asOf = Instant.parse("2026-09-07T03:00:00Z");
        when(dealRepository.asOf()).thenReturn(asOf);
        when(crmLane.run(any(ReadCursor.class), eq(asOf))).thenReturn(new IntakeResult(5, 5, 0, List.of()));

        scheduler.syncCrmTasks();

        verify(crmLane).run(any(ReadCursor.class), eq(asOf));
    }

    @Test
    void aRuntimeExceptionFromTheLaneIsCaughtNotPropagated() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        when(dealRepository.asOf()).thenReturn(Instant.now());
        when(crmLane.run(any(ReadCursor.class), any()))
                .thenThrow(new RuntimeException("HubSpot unreachable"));

        assertDoesNotThrow(scheduler::syncCrmTasks);
    }
}
