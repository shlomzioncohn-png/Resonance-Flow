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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * syncEnabled/cronExpression are set via ReflectionTestUtils, not constructor args - they're
 * {@code @Value}-injected in production, same as the old {@code CrmSyncScheduler}'s syncEnabled.
 * That class's enabled/disabled branch and its catch-and-log-on-failure behavior both moved to
 * {@code SyncJobScheduler} (shared by every {@code SyncJob} - see SyncJobSchedulerTest for that
 * coverage); this class only tests {@link CrmSyncJob#runOnce()} and its two accessors, so a
 * RuntimeException from either the lane or the snapshot fetch is now expected to propagate
 * rather than be swallowed here.
 */
class CrmSyncJobTest {

    private final CrmLane crmLane = mock(CrmLane.class);
    private final DealRepository dealRepository = mock(DealRepository.class);
    private final CrmSyncJob job = new CrmSyncJob(crmLane, dealRepository);

    private static DealSnapshot snapshotAt(Instant asOf) {
        return new DealSnapshot(asOf, List.of(), Map.of(), List.of(), 0);
    }

    @Test
    void nameIsCrm() {
        assertEquals("crm", job.name());
    }

    @Test
    void enabledAndCronReflectTheInjectedValues() {
        ReflectionTestUtils.setField(job, "syncEnabled", true);
        ReflectionTestUtils.setField(job, "cronExpression", "0 0 3 * * ?");

        assertTrue(job.enabled());
        assertEquals("0 0 3 * * ?", job.cron());

        ReflectionTestUtils.setField(job, "syncEnabled", false);

        assertFalse(job.enabled());
    }

    @Test
    void runOnceRunsTheLaneWithTheSnapshotsAsOf() {
        Instant asOf = Instant.parse("2026-09-07T03:00:00Z");
        when(dealRepository.snapshot()).thenReturn(snapshotAt(asOf));
        when(crmLane.run(any(Stream.class), eq(asOf))).thenReturn(new IntakeResult(5, 5, 0, List.of()));

        IntakeResult result = job.runOnce();

        verify(crmLane).run(any(Stream.class), eq(asOf));
        assertEquals(5, result.published());
    }

    @Test
    void aRuntimeExceptionFromTheLanePropagatesOutOfRunOnce() {
        when(dealRepository.snapshot()).thenReturn(snapshotAt(Instant.now()));
        when(crmLane.run(any(Stream.class), any())).thenThrow(new RuntimeException("HubSpot unreachable"));

        assertThrows(RuntimeException.class, job::runOnce);
    }

    @Test
    void aRuntimeExceptionFromTheSnapshotFetchItselfPropagatesOutOfRunOnce() {
        // HubSpot unreachable / retries exhausted - the fetch inside snapshot() throws before
        // crmLane.run() is ever called.
        when(dealRepository.snapshot()).thenThrow(new RuntimeException("HubSpot unreachable"));

        assertThrows(RuntimeException.class, job::runOnce);
    }
}
