package com.rnd.bridge.crm.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.sync.SyncJob;
import com.rnd.bridge.core.sync.SyncJobScheduler;
import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Daily CRM sync as a {@link SyncJob} - once-a-day pull, not real-time (team lead's call, 2026-09-07).
 * {@link #runOnce()} takes one {@link DealSnapshot} per run, the same single-snapshot-per-run shape {@code CrmBridgeController} uses.
 */
@Component
public class CrmSyncJob implements SyncJob {

    private static final Logger log = LoggerFactory.getLogger(CrmSyncJob.class);

    private final CrmLane crmLane;
    private final DealRepository dealRepository;

    @Value("${bridge.crm.sync.cron.enabled:false}")
    private boolean syncEnabled;

    @Value("${bridge.crm.sync.cron.expression:0 0 3 * * ?}")
    private String cronExpression;

    public CrmSyncJob(CrmLane crmLane, DealRepository dealRepository) {
        this.crmLane = crmLane;
        this.dealRepository = dealRepository;
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
        DealSnapshot snapshot = dealRepository.snapshot();
        IntakeResult result = crmLane.run(snapshot.latestPerDeal().stream(), snapshot.asOf());
        if (snapshot.skippedAtRead() > 0) {
            log.warn("CRM sync: partial sync, {} deal(s) skipped at read.", snapshot.skippedAtRead());
        } else {
            log.info("CRM sync: {} skipped at read.", snapshot.skippedAtRead());
        }
        return result;
    }
}
