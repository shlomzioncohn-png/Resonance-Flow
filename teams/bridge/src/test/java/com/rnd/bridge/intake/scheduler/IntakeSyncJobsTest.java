package com.rnd.bridge.intake.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.sync.SyncJob;
import com.rnd.bridge.intake.service.UnstructuredIntakeService;
import com.rnd.bridge.raw.model.RawSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code IntakeSyncJobs}' {@code @Bean} methods are called directly as plain Java methods, not
 * through a Spring context - they take their {@code @Value}-injected parameters as plain
 * arguments, so this stays a unit test.
 */
class IntakeSyncJobsTest {

    private final UnstructuredIntakeService intakeService = mock(UnstructuredIntakeService.class);
    private final IntakeSyncJobs jobs = new IntakeSyncJobs();

    @Test
    void emailJobReflectsItsConfigAndCallsTheControllerWithEmail() {
        SyncJob job = jobs.emailSyncJob(intakeService, false, "0 */15 * * * ?");

        assertEquals("email", job.name());
        assertFalse(job.enabled());
        assertEquals("0 */15 * * * ?", job.cron());

        IntakeResult expected = new IntakeResult(1, 1, 0, List.of());
        when(intakeService.run(RawSourceType.EMAIL)).thenReturn(expected);

        assertEquals(expected, job.runOnce());
        verify(intakeService).run(RawSourceType.EMAIL);
    }

    @Test
    void calendarJobReflectsItsConfigAndCallsTheControllerWithCalendar() {
        SyncJob job = jobs.calendarSyncJob(intakeService, true, "0 0 * * * ?");

        assertEquals("calendar", job.name());
        assertTrue(job.enabled());
        assertEquals("0 0 * * * ?", job.cron());

        job.runOnce();

        verify(intakeService).run(RawSourceType.CALENDAR);
    }

    @Test
    void driveJobReflectsItsConfigAndCallsTheControllerWithDrive() {
        SyncJob job = jobs.driveSyncJob(intakeService, true, "0 30 * * * ?");

        assertEquals("drive", job.name());
        assertTrue(job.enabled());
        assertEquals("0 30 * * * ?", job.cron());

        job.runOnce();

        verify(intakeService).run(RawSourceType.DRIVE);
    }
}
