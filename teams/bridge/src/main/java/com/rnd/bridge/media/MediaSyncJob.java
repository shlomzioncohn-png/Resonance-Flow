package com.rnd.bridge.media;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.sync.SyncJob;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Polls the media intake folders on a cron schedule, through the shared {@link SyncJob}
 * mechanism every other polled source uses (see SyncJobScheduler) - a file dropped into a
 * configured folder is picked up without anyone calling {@code POST /api/bridge/media/run}. Runs
 * the exact same {@link MediaIntakeOrchestrator#runIntake()} the endpoint does: one code path, two
 * triggers, and the orchestrator's run lock keeps them from overlapping.
 * <p>
 * Off by default (bridge.media.sync.cron.enabled). SyncJobScheduler catches and logs a failed run;
 * the next tick tries again. A run that outlasts the interval delays the next one rather than
 * overlapping it.
 */
@Component
class MediaSyncJob implements SyncJob {

    private static final Logger log = LoggerFactory.getLogger(MediaSyncJob.class);

    private final MediaIntakeOrchestrator orchestrator;
    private final boolean enabled;
    private final String cron;

    MediaSyncJob(MediaIntakeOrchestrator orchestrator,
                 @Value("${bridge.media.sync.cron.enabled:false}") boolean enabled,
                 @Value("${bridge.media.sync.cron.expression:0 */5 * * * ?}") String cron) {
        this.orchestrator = orchestrator;
        this.enabled = enabled;
        this.cron = cron;
    }

    @Override
    public String name() {
        return "media";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String cron() {
        return cron;
    }

    /**
     * read counts every file the run handled; published counts objects landed; rejected covers
     * both files refused up front and files that failed to land. Duplicates are read, not
     * published - the per-outcome line below keeps the detail SyncJobScheduler's summary drops.
     */
    @Override
    public IntakeResult runOnce() {
        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();
        int uploaded = summary.uploadedObjectKeys().size();
        int duplicates = summary.duplicateFiles().size();
        int rejected = summary.rejectedFiles().size();
        int failed = summary.failedFiles().size();
        if (uploaded + duplicates + rejected + failed > 0) {
            log.info("Media intake: {} uploaded, {} duplicate, {} rejected, {} failed",
                    uploaded, duplicates, rejected, failed);
        }
        return new IntakeResult(uploaded + duplicates + rejected + failed, uploaded, rejected + failed, List.of());
    }
}
