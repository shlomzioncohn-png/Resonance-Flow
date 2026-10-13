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
 * Daily CRM sync as a {@link SyncJob} - the team lead's call (2026-09-07) is to stay on a
 * once-a-day pull for now, not move to real-time, until told otherwise. {@link #runOnce()} takes
 * exactly one {@link DealSnapshot} per run and calls {@code crmLane.run(Stream, Instant)} against
 * its {@code latestPerDeal()}/{@code asOf()} together - the same single-snapshot-per-run shape
 * {@code CrmBridgeController} uses, so a scheduled run and a manual trigger agree on what "one
 * sync" means, and neither can see a mix of two different fetches the way calling
 * {@code dealRepository.snapshot()} and a separate read could.
 * <p>
 * Enabled/disabled checking, start/complete logging, and catching a failed run so it never
 * propagates are now {@link SyncJobScheduler}'s job, shared by every {@link SyncJob} - this class
 * (formerly {@code CrmSyncScheduler}) only still logs {@code skippedAtRead}, which has no
 * equivalent on {@link IntakeResult} or any other job.
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
        log.info("CRM sync: {} skipped at read.", snapshot.skippedAtRead());
        return result;
    }
}
