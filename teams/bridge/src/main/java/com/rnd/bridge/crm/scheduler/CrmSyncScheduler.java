package com.rnd.bridge.crm.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.repository.DealRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily CRM sync - the team lead's call (2026-09-07) is to stay on a
 * once-a-day pull for now, not move to real-time, until told otherwise.
 * <p>
 * Runs the exact same {@code crmLane.run(...)} call {@code CrmBridgeController}'s
 * {@code POST /bridge/crm/sync} makes - calling the lane directly, not looping
 * back through Bridge's own HTTP endpoint - so a scheduled run and a manual
 * trigger are the same code path exercised two different ways. Mirrors
 * {@code GitHubSyncScheduler}'s shape for the same reason, with one deliberate
 * difference: a failure here is caught and logged rather than left to
 * propagate, so one bad scheduled run doesn't produce an unhandled-exception
 * log line in place of a clear "sync failed" message - {@code crmLane.run()}
 * already turns per-record failures into {@link IntakeResult#rejected()}
 * rather than throwing, so anything that does throw here is a run-level
 * failure (e.g. HubSpot unreachable, retries exhausted) worth its own message.
 */
@Service
public class CrmSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrmSyncScheduler.class);

    private final CrmLane crmLane;
    private final DealRepository dealRepository;

    @Value("${bridge.crm.sync.cron.enabled:false}")
    private boolean syncEnabled;

    public CrmSyncScheduler(CrmLane crmLane, DealRepository dealRepository) {
        this.crmLane = crmLane;
        this.dealRepository = dealRepository;
    }

    @Scheduled(cron = "${bridge.crm.sync.cron.expression:0 0 3 * * ?}")
    public void syncCrmTasks() {
        if (!syncEnabled) {
            log.info("CRM sync scheduler is disabled.");
            return;
        }

        log.info("Starting scheduled CRM sync.");
        try {
            IntakeResult result = crmLane.run(ReadCursor.full(), dealRepository.snapshot().asOf());
            log.info("Scheduled CRM sync complete: {} read, {} published, {} rejected.",
                    result.read(), result.published(), result.rejected());
        } catch (RuntimeException e) {
            log.error("Scheduled CRM sync failed", e);
        }
    }
}
