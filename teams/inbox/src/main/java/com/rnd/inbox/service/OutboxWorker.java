package com.rnd.inbox.service;

import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.enums.OutboxStatus;
import com.rnd.inbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Periodically claims PENDING outbox events and publishes them via the
 * Generic Producer infrastructure.
 * <p>
 * The whole claim-and-publish cycle for a batch runs inside a single
 * transaction: OutboxEventRepository#claimPendingBatch takes a row lock
 * (FOR UPDATE SKIP LOCKED) that other INBOX instances respect only while
 * this transaction is open, so publishing happens while the claim is held.
 * <p>
 * Claimed entities are managed by the persistence context - updating their
 * fields below is enough for Hibernate to persist the change on commit, no
 * explicit save() call needed.
 * <p>
 * EventPublisher.publish(...) is asynchronous and already retries transient
 * Kafka failures internally (see KafkaEventPublisher). This worker blocks on
 * the returned CompletableFuture via .get() so that success/failure is known
 * before the surrounding @Transactional method commits - this keeps the
 * "claim, publish, update status" cycle atomic from the DB's point of view.
 * The trade-off: events in a batch are published one at a time, not
 * concurrently. This worker's own retry (next_retry_at / retryCount) only
 * kicks in for failures the publisher itself gave up on after its internal
 * attempts.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final String SOURCE_SERVICE = "inbox";

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final int batchSize;
    private final int maxRetries;
    private final Duration backoffBase;
    private final Duration backoffMax;

    public OutboxWorker(OutboxEventRepository outboxEventRepository,
                        EventPublisher eventPublisher,
                        @Value("${outbox.worker.batch-size:20}") int batchSize,
                        @Value("${outbox.worker.max-retries:3}") int maxRetries,
                        @Value("${outbox.worker.backoff-base-seconds:10}") long backoffBaseSeconds,
                        @Value("${outbox.worker.backoff-max-seconds:120}") long backoffMaxSeconds) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.backoffBase = Duration.ofSeconds(backoffBaseSeconds);
        this.backoffMax = Duration.ofSeconds(backoffMaxSeconds);
    }

    @Scheduled(fixedDelayString = "${outbox.worker.poll-interval-ms:5000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> claimed;
        try {
            claimed = outboxEventRepository.claimPendingBatch(Instant.now(), batchSize);
        } catch (Exception e) {
            // If the claim query itself fails (e.g. DB connection issue), log and
            // let the next scheduled run retry - there is nothing claimed yet to
            // roll back or mark as failed.
            log.error("Failed to claim pending outbox events; will retry on next scheduled run", e);
            return;
        }

        for (OutboxEvent event : claimed) {
            processOne(event);
        }
    }

    private void processOne(OutboxEvent outboxEvent) {
        try {
            // eventId, timestamp and correlationId come from the outbox row
            // itself (via Event.existing), not freshly generated, so the
            // same eventId is sent on every retry attempt for this row.
            Event<Map<String, Object>> event = Event.existing(
                    outboxEvent.getEventId(),
                    outboxEvent.getEventType(),
                    outboxEvent.getCreatedAt(),
                    SOURCE_SERVICE,
                    outboxEvent.getCorrelationId(),
                    outboxEvent.getPayload());

            // The topic is derived from EventType, not stored on the outbox row.
            eventPublisher.publish(outboxEvent.getEventType().getTopic(), outboxEvent.getMessageKey(), event).get();

            outboxEvent.setStatus(OutboxStatus.PUBLISHED);
            outboxEvent.setPublishedAt(Instant.now());
        } catch (ExecutionException e) {
            // Publisher exhausted its own internal retries - unwrap the real cause.
            handleFailure(outboxEvent, e.getCause() != null ? e.getCause() : e);
        } catch (Exception e) {
            handleFailure(outboxEvent, e);
        }
    }

    private void handleFailure(OutboxEvent event, Throwable e) {
        int attempts = event.getRetryCount() + 1;
        event.setRetryCount((short) attempts);
        event.setLastError(truncate(e.getMessage()));

        if (attempts >= maxRetries) {
            event.setStatus(OutboxStatus.FAILED);
            log.error("Outbox event {} failed permanently after {} attempts", event.getEventId(), attempts, e);
        } else {
            event.setNextRetryAt(Instant.now().plus(computeBackoff(attempts)));
            log.warn("Outbox event {} publish attempt {} failed, retrying later", event.getEventId(), attempts, e);
        }
    }

    private Duration computeBackoff(int attempt) {
        long seconds = backoffBase.getSeconds() * (1L << (attempt - 1));
        return Duration.ofSeconds(Math.min(seconds, backoffMax.getSeconds()));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}