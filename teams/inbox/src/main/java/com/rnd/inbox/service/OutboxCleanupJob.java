package com.rnd.inbox.service;

import com.rnd.inbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Daily job that deletes old PUBLISHED and FAILED outbox rows.
 * <p>
 * PENDING rows are never touched here - they still need processing by
 * OutboxWorker. PUBLISHED and FAILED use separate retention windows:
 * PUBLISHED rows succeeded and mainly matter for short-term audit, while
 * FAILED rows are a permanent delivery failure worth keeping longer for
 * investigation.
 * <p>
 * Each status is deleted with a single DELETE statement (no batching loop) -
 * appropriate while row counts stay in a range a single DELETE can clear
 * quickly. Both queries use idx_outbox_status_created_at, so they stay
 * efficient as the table grows.
 */
@Component
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxEventRepository outboxEventRepository;
    private final Duration publishedRetention;
    private final Duration failedRetention;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository,
                            @Value("${outbox.cleanup.published-retention-days:7}") long publishedRetentionDays,
                            @Value("${outbox.cleanup.failed-retention-days:30}") long failedRetentionDays) {
        this.outboxEventRepository = outboxEventRepository;
        this.publishedRetention = Duration.ofDays(publishedRetentionDays);
        this.failedRetention = Duration.ofDays(failedRetentionDays);
    }

    @Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanup() {
        Instant now = Instant.now();

        try {
            int deletedPublished = outboxEventRepository.deletePublishedOlderThan(now.minus(publishedRetention));
            int deletedFailed = outboxEventRepository.deleteFailedOlderThan(now.minus(failedRetention));

            log.info("Outbox cleanup: removed {} published row(s) older than {} day(s), {} failed row(s) older than {} day(s)",
                    deletedPublished, publishedRetention.toDays(), deletedFailed, failedRetention.toDays());
        } catch (Exception e) {
            // Both deletes run in the same transaction, so a failure in either one
            // aborts both - there is no partial cleanup to report. Logging here
            // just gives a clear message instead of a raw framework stack trace;
            // the next scheduled run will retry both.
            log.error("Outbox cleanup failed; will retry on next scheduled run", e);
        }
    }
}