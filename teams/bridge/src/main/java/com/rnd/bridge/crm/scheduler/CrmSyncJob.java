package com.rnd.bridge.crm.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.core.sync.SyncJob;
import com.rnd.bridge.core.sync.SyncJobScheduler;
import com.rnd.bridge.crm.CrmSourceResult;
import com.rnd.bridge.crm.CrmSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily CRM sync as a {@link SyncJob} - once-a-day pull, not real-time (team lead's call, 2026-09-07).
 * Delegates to {@link CrmSyncService#syncAll()}; throws after all sources ran if any failed.
 */
@Component
public class CrmSyncJob implements SyncJob {

    private static final Logger log = LoggerFactory.getLogger(CrmSyncJob.class);

    private final CrmSyncService crmSyncService;

    @Value("${bridge.crm.sync.cron.enabled:false}")
    private boolean syncEnabled;

    @Value("${bridge.crm.sync.cron.expression:0 0 3 * * ?}")
    private String cronExpression;

    public CrmSyncJob(CrmSyncService crmSyncService) {
        this.crmSyncService = crmSyncService;
    }

    @Override
    public String name() {
        return "crm";
    }

    @Override
    public boolean enabled() {
        return syncEnabled;
    }

    @Override
    public String cron() {
        return cronExpression;
    }

    @Override
    public IntakeResult runOnce() {
        List<CrmSourceResult> results = crmSyncService.syncAll();

        int read = 0;
        int published = 0;
        int rejected = 0;
        List<LaneException> errors = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (CrmSourceResult result : results) {
            read += result.read();
            published += result.published();
            rejected += result.rejected();
            result.errors().forEach(message -> errors.add(new LaneException(message)));

            if (result.error() != null) {
                log.error("CRM sync for source '{}' failed: {}", result.source(), result.error());
                failed.add(result.source() + " (" + result.error() + ")");
            } else if (result.skippedAtRead() > 0) {
                log.warn("CRM sync for source '{}': {} read, {} published, {} rejected, {} skipped at read.",
                        result.source(), result.read(), result.published(), result.rejected(), result.skippedAtRead());
            } else {
                log.info("CRM sync for source '{}': {} read, {} published, {} rejected.",
                        result.source(), result.read(), result.published(), result.rejected());
            }
        }

        IntakeResult summed = new IntakeResult(read, published, rejected, errors);
        if (!failed.isEmpty()) {
            throw new RuntimeException("CRM sync failed for source(s): " + String.join(", ", failed));
        }
        return summed;
    }
}
