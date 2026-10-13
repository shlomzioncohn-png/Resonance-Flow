package com.rnd.bridge.intake.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.sync.SyncJob;
import com.rnd.bridge.intake.controller.UnstructuredIntakeController;
import com.rnd.bridge.raw.model.RawSourceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Three {@link SyncJob} beans - EMAIL, CALENDAR, DRIVE - each a thin wrapper around
 * {@link UnstructuredIntakeController#run(RawSourceType)}, the same bean call
 * {@code POST /api/bridge/intake/{sourceType}/run} makes, called directly rather than over HTTP.
 * The controller and its cursor/lock handling are untouched - this only adds a scheduled trigger
 * for it. Off by default like every other job {@code SyncJobScheduler} runs
 * ({@code bridge.intake.<source>.sync.cron.enabled}); SLACK and WHATSAPP have no scheduled job
 * here because neither is a poll-on-a-timer source (Slack is webhook-driven, WhatsApp has no
 * export API to poll).
 */
@Configuration
class IntakeSyncJobs {

    @Bean
    SyncJob emailSyncJob(
            UnstructuredIntakeController controller,
            @Value("${bridge.intake.email.sync.cron.enabled:false}") boolean enabled,
            @Value("${bridge.intake.email.sync.cron.expression:0 */15 * * * ?}") String cron) {
        return new UnstructuredSourceSyncJob("email", enabled, cron, RawSourceType.EMAIL, controller);
    }

    @Bean
    SyncJob calendarSyncJob(
            UnstructuredIntakeController controller,
            @Value("${bridge.intake.calendar.sync.cron.enabled:false}") boolean enabled,
            @Value("${bridge.intake.calendar.sync.cron.expression:0 */15 * * * ?}") String cron) {
        return new UnstructuredSourceSyncJob("calendar", enabled, cron, RawSourceType.CALENDAR, controller);
    }

    @Bean
    SyncJob driveSyncJob(
            UnstructuredIntakeController controller,
            @Value("${bridge.intake.drive.sync.cron.enabled:false}") boolean enabled,
            @Value("${bridge.intake.drive.sync.cron.expression:0 */15 * * * ?}") String cron) {
        return new UnstructuredSourceSyncJob("drive", enabled, cron, RawSourceType.DRIVE, controller);
    }

    /** Record accessors (name/enabled/cron) satisfy {@link SyncJob} directly - only runOnce() needs a body. */
    private record UnstructuredSourceSyncJob(
            String name, boolean enabled, String cron, RawSourceType sourceType,
            UnstructuredIntakeController controller) implements SyncJob {

        @Override
        public IntakeResult runOnce() {
            return controller.run(sourceType);
        }
    }
}
