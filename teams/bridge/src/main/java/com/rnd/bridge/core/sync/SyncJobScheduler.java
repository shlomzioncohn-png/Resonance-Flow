package com.rnd.bridge.core.sync;

import com.rnd.bridge.core.IntakeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.List;

/**
 * One generic scheduler for every {@link SyncJob}, replacing a copy-pasted {@code @Scheduled}
 * method per source (CRM, GitHub and Jira each had their own, and {@code @EnableScheduling} only
 * lived in GitHub/Jira's own config, nowhere shared). Each job's enabled/disabled check is made
 * once here, at startup - a disabled job is logged once and never registered, rather than a
 * {@code @Scheduled} method firing on every tick just to immediately return. An enabled job's
 * {@link SyncJob#runOnce()} always goes through the same start/complete logging and
 * catch-log-never-rethrow handling, regardless of source, so one job's unhandled failure can
 * never propagate into the shared {@link TaskScheduler} thread pool.
 */
@Configuration
@EnableScheduling
public class SyncJobScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SyncJobScheduler.class);

    private final List<SyncJob> jobs;
    private final TaskScheduler taskScheduler;

    public SyncJobScheduler(List<SyncJob> jobs, TaskScheduler taskScheduler) {
        this.jobs = jobs;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler);
        for (SyncJob job : jobs) {
            if (!job.enabled()) {
                log.info("Sync job '{}' is disabled.", job.name());
                continue;
            }
            String cron = job.cron();
            if (cron == null || cron.isBlank()) {
                throw new IllegalStateException(
                        "Sync job '" + job.name() + "' is enabled but has a blank cron expression");
            }
            log.info("Sync job '{}' enabled with cron '{}'.", job.name(), cron);
            registrar.addCronTask(new CronTask(() -> runOnce(job), cron));
        }
    }

    private void runOnce(SyncJob job) {
        log.info("Starting scheduled sync for '{}'.", job.name());
        try {
            IntakeResult result = job.runOnce();
            log.info("Scheduled sync for '{}' complete: {} read, {} published, {} rejected.",
                    job.name(), result.read(), result.published(), result.rejected());
        } catch (RuntimeException e) {
            log.error("Scheduled sync for '{}' failed", job.name(), e);
        }
    }
}
