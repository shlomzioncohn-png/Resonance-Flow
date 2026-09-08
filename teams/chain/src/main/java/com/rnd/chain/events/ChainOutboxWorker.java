package com.rnd.chain.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodically claims PENDING rows from chain_outbox and publishes them to
 * Kafka, so a business write and its outbound event can never diverge the
 * way they can with KafkaEventPublisher.publish()'s fire-and-forget contract
 * (PLAN.md D6).
 *
 * The whole claim-and-publish cycle for a batch runs inside a single
 * transaction: ChainOutboxEventRepository#claimPendingBatch takes a row lock
 * (FOR UPDATE SKIP LOCKED) that other Chain instances respect only while
 * this transaction is open, so publishing happens while the claim is held.
 * Claimed entities are managed by the persistence context - updating their
 * fields below is enough for Hibernate to persist the change on commit, no
 * explicit save() call needed.
 *
 * publishAndWait() blocks on the underlying Kafka send so success/failure is
 * known before this method's own @Transactional commits - that keeps the
 * "claim, publish, update status" cycle atomic from the DB's point of view.
 * The trade-off, same as the reference implementation this mirrors
 * (Inbox's OutboxWorker): events in a batch are published one at a time,
 * not concurrently.
 *
 * Dormant until an environment gives Chain a real database - see
 * application.yml's spring.autoconfigure.exclude comment. There is no
 * business service writing PENDING rows yet either: Chain has no entity of
 * its own in the database today (see ChainOutboxEvent's Javadoc) - this
 * class is groundwork for when one exists.
 *
 * @ConditionalOnBean(ChainOutboxEventRepository.class) - not a property
 * flag like ChainEventConsumer's - because that repository bean itself only
 * exists once Spring Data JPA is actually autoconfigured. Without it, this
 * component would still be created by component scanning and fail fast
 * demanding a repository bean that JPA never registered, taking down the
 * whole application context (including contexts that have nothing to do
 * with the outbox, like ChainEventConsumerIntegrationTest's).
 */
@Component
@ConditionalOnBean(ChainOutboxEventRepository.class)
public class ChainOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(ChainOutboxWorker.class);

    private final ChainOutboxEventRepository repository;
    private final KafkaEventPublisher publisher;
    private final ChainOutboxProperties.Worker properties;

    public ChainOutboxWorker(ChainOutboxEventRepository repository,
                             KafkaEventPublisher publisher,
                             ChainOutboxProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties.worker();
    }

    @Scheduled(fixedDelayString = "${chain.outbox.worker.poll-interval-ms:5000}")
    @Transactional
    public void pollAndPublish() {
        List<ChainOutboxEvent> claimed;
        try {
            claimed = repository.claimPendingBatch(Instant.now(), properties.batchSize());
        } catch (Exception e) {
            // If the claim query itself fails (e.g. DB connection issue), log and
            // let the next scheduled run retry - there is nothing claimed yet to
            // roll back or mark as failed.
            log.error("Failed to claim pending outbox events; will retry on next scheduled run", e);
            return;
        }

        for (ChainOutboxEvent event : claimed) {
            processOne(event);
        }
    }

    void processOne(ChainOutboxEvent outboxEvent) {
        try {
            // eventId, correlationId and payload come from the outbox row
            // itself, not freshly generated, so the same eventId is sent on
            // every retry attempt for this row.
            EventEnvelope<?> event = new EventEnvelope<>(
                    outboxEvent.getEventId(),
                    outboxEvent.getEventType(),
                    outboxEvent.getCreatedAt(),
                    outboxEvent.getCorrelationId() != null ? outboxEvent.getCorrelationId().toString() : null,
                    outboxEvent.getPayload());

            publisher.publishAndWait(event);

            outboxEvent.setStatus(ChainOutboxStatus.PUBLISHED);
            outboxEvent.setPublishedAt(Instant.now());
        } catch (Exception e) {
            handleFailure(outboxEvent, e);
        }
    }

    void handleFailure(ChainOutboxEvent event, Throwable e) {
        int attempts = event.getRetryCount() + 1;
        event.setRetryCount((short) attempts);
        event.setLastError(truncate(e.getMessage()));

        if (attempts >= properties.maxRetries()) {
            event.setStatus(ChainOutboxStatus.FAILED);
            log.error("Outbox event {} failed permanently after {} attempts", event.getEventId(), attempts, e);
        } else {
            event.setNextRetryAt(Instant.now().plus(computeBackoff(attempts)));
            log.warn("Outbox event {} publish attempt {} failed, retrying later", event.getEventId(), attempts, e);
        }
    }

    Duration computeBackoff(int attempt) {
        long base = properties.backoffBaseSeconds();
        long max = properties.backoffMaxSeconds();
        long seconds = base * (1L << (attempt - 1));
        return Duration.ofSeconds(Math.min(seconds, max));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
