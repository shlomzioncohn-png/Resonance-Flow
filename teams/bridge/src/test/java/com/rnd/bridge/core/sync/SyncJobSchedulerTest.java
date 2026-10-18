package com.rnd.bridge.core.sync;

import com.rnd.bridge.core.IntakeResult;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A plain {@link ScheduledTaskRegistrar} is used directly (never a mock) - it's a simple holder
 * whose {@code getCronTaskList()} getter is exactly what these tests need to inspect, and using
 * the real thing means no Spring context has to come up to exercise
 * {@link SyncJobScheduler#configureTasks(ScheduledTaskRegistrar)}.
 */
class SyncJobSchedulerTest {

    private static final TaskScheduler TASK_SCHEDULER = mock(TaskScheduler.class);

    private static SyncJob job(String name, boolean enabled, String cron, Runnable onRunOnce) {
        return new SyncJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public String cron() {
                return cron;
            }

            @Override
            public IntakeResult runOnce() {
                onRunOnce.run();
                return new IntakeResult(1, 1, 0, List.of());
            }
        };
    }

    @Test
    void anEnabledJobIsRegisteredWithItsCron() {
        SyncJob job = job("enabled-job", true, "0 0 3 * * ?", () -> { });
        SyncJobScheduler scheduler = new SyncJobScheduler(List.of(job), TASK_SCHEDULER);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        List<CronTask> tasks = registrar.getCronTaskList();
        assertEquals(1, tasks.size());
        assertEquals("0 0 3 * * ?", tasks.get(0).getExpression());
    }

    @Test
    void theConfiguredTaskSchedulerIsSetOnTheRegistrar() {
        SyncJob job = job("enabled-job", true, "0 0 3 * * ?", () -> { });
        SyncJobScheduler scheduler = new SyncJobScheduler(List.of(job), TASK_SCHEDULER);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        assertSame(TASK_SCHEDULER, registrar.getScheduler());
    }

    @Test
    void aDisabledJobIsNotRegistered() {
        SyncJob job = job("disabled-job", false, "0 0 3 * * ?", () -> { });
        SyncJobScheduler scheduler = new SyncJobScheduler(List.of(job), TASK_SCHEDULER);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        assertTrue(registrar.getCronTaskList().isEmpty());
    }

    @Test
    void aRuntimeExceptionFromRunOnceIsCaughtNotPropagated() {
        SyncJob job = job("boom-job", true, "0 0 3 * * ?", () -> {
            throw new RuntimeException("boom");
        });
        SyncJobScheduler scheduler = new SyncJobScheduler(List.of(job), TASK_SCHEDULER);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        Runnable registeredTask = registrar.getCronTaskList().get(0).getRunnable();

        assertDoesNotThrow(registeredTask::run);
    }

    @Test
    void aBlankCronOnAnEnabledJobFailsStartupNamingTheJob() {
        SyncJob job = job("blank-cron-job", true, "   ", () -> { });
        SyncJobScheduler scheduler = new SyncJobScheduler(List.of(job), TASK_SCHEDULER);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> scheduler.configureTasks(registrar));

        assertTrue(exception.getMessage().contains("blank-cron-job"));
    }

    @Test
    void multipleJobsAreEachRegisteredOrSkippedIndependently() {
        SyncJob enabled = job("enabled-job", true, "0 0 3 * * ?", () -> { });
        SyncJob disabled = job("disabled-job", false, "0 0 4 * * ?", () -> { });
        SyncJobScheduler scheduler = new SyncJobScheduler(List.of(enabled, disabled), TASK_SCHEDULER);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        List<CronTask> tasks = registrar.getCronTaskList();
        assertEquals(1, tasks.size());
        assertEquals("0 0 3 * * ?", tasks.get(0).getExpression());
    }
}
