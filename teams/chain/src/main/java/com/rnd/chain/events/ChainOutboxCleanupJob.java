package com.rnd.chain.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically deletes chain_outbox rows that are no longer useful: rows
 * ChainOutboxWorker already published, and rows it gave up on after
 * exhausting retries. Keeps the pending-pickup index in
 * V1__create_chain_outbox_table.sql small instead of growing forever.
 *
 * Dormant until an environment gives Chain a real database, same as
 * ChainOutboxWorker - see application.yml's spring.autoconfigure.exclude
 * comment and ChainOutboxWorker's Javadoc for why @ConditionalOnBean
 * specifically.
 */
@Component
@ConditionalOnBean(ChainOutboxEventRepository.class)
public class ChainOutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ChainOutboxCleanupJob.class);

    private final ChainOutboxEventRepository repository;
    private final ChainOutboxProperties.Cleanup properties;

    public ChainOutboxCleanupJob(ChainOutboxEventRepository repository, ChainOutboxProperties properties) {
        this.repository = repository;
        this.properties = properties.cleanup();
    }

    @Scheduled(fixedDelayString = "${chain.outbox.cleanup.interval-ms:3600000}")
    @Transactional
    public void deleteOldRows() {
        Instant cutoff = Instant.now().minusSeconds(properties.retentionDays() * 24 * 60 * 60);
        try {
            int publishedDeleted = repository.deletePublishedOlderThan(cutoff);
            int failedDeleted = repository.deleteFailedOlderThan(cutoff);
            if (publishedDeleted > 0 || failedDeleted > 0) {
                log.info("Deleted {} published and {} failed outbox rows older than {}",
                        publishedDeleted, failedDeleted, cutoff);
            }
        } catch (Exception e) {
            log.error("Failed to clean up old outbox rows; will retry on next scheduled run", e);
        }
    }
}
