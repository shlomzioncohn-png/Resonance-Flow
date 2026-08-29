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
        List<OutboxEvent> claimed = outboxEventRepository.claimPendingBatch(Instant.now(), batchSize);

        for (OutboxEvent event : claimed) {
            processOne(event);
        }
    }

    private void processOne(OutboxEvent outboxEvent) {
        try {
            Event<Map<String, Object>> event = Event.existing(
                    outboxEvent.getEventId(),
                    outboxEvent.getEventType(),
                    outboxEvent.getCreatedAt(),
                    SOURCE_SERVICE,
                    outboxEvent.getCorrelationId(),
                    outboxEvent.getPayload());

            eventPublisher.publish(outboxEvent.getEventType().getTopic(), outboxEvent.getMessageKey(), event).get();

            outboxEvent.setStatus(OutboxStatus.PUBLISHED);
            outboxEvent.setPublishedAt(Instant.now());
        } catch (ExecutionException e) {
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