package com.rnd.bridge.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the media intake folders on a fixed delay, so a file dropped into a configured folder is
 * picked up without anyone calling {@code POST /api/bridge/media/run}. Runs the exact same
 * {@link MediaIntakeOrchestrator#runIntake()} the endpoint does - one code path, two triggers.
 * <p>
 * Off by default (bridge.media.schedule.enabled). Registered only when enabled, rather than
 * checking a flag on every tick like CrmSyncScheduler: at a minutes-scale interval a "disabled"
 * log line per tick would just be noise. Fixed delay, not fixed rate - the next scan starts
 * interval after the previous one finished, so a slow run (a model call per item) never stacks
 * up runs. A failed run is logged and the next tick tries again.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.media.schedule", name = "enabled", havingValue = "true")
public class MediaIntakeScheduler {

    private static final Logger log = LoggerFactory.getLogger(MediaIntakeScheduler.class);

    private final MediaIntakeOrchestrator orchestrator;

    public MediaIntakeScheduler(MediaIntakeOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(initialDelayString = "${bridge.media.schedule.initial-delay:PT10S}",
               fixedDelayString = "${bridge.media.schedule.interval:PT2M}")
    public void pollIntakeFolders() {
        try {
            MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();
            int handled = summary.uploadedObjectKeys().size() + summary.duplicateFiles().size()
                    + summary.rejectedFiles().size() + summary.failedFiles().size();
            if (handled > 0) {
                log.info("Scheduled media intake: {} uploaded, {} duplicate, {} rejected, {} failed",
                        summary.uploadedObjectKeys().size(), summary.duplicateFiles().size(),
                        summary.rejectedFiles().size(), summary.failedFiles().size());
            }
        } catch (RuntimeException e) {
            log.error("Scheduled media intake failed - retrying on the next tick", e);
        }
    }
}
