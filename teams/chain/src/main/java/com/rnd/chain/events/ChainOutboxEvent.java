package com.rnd.chain.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A row in chain_outbox: an event that must be published to Kafka in the
 * same database transaction as whatever business state change produced it,
 * so a Kafka failure after that state is already committed can never lose
 * the event silently (PLAN.md D6). ChainOutboxWorker is the only reader -
 * see its Javadoc for the claim/publish/retry lifecycle.
 *
 * Not wired to any business entity yet - Chain has none of its own in the
 * database today. {@link #pending} is the factory a future @Transactional
 * business service calls alongside its own repository.save(...).
 */
@Entity
@Table(name = "chain_outbox")
public class ChainOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    @Column(name = "message_key", nullable = false)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChainOutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private short retryCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected ChainOutboxEvent() {
    }

    public static ChainOutboxEvent pending(String eventType, UUID aggregateId, String messageKey,
                                           Map<String, Object> payload, UUID correlationId, Instant now) {
        ChainOutboxEvent event = new ChainOutboxEvent();
        event.eventType = eventType;
        event.aggregateId = aggregateId;
        event.messageKey = messageKey;
        event.payload = payload;
        event.correlationId = correlationId;
        event.status = ChainOutboxStatus.PENDING;
        event.retryCount = 0;
        event.nextRetryAt = now;
        event.createdAt = now;
        return event;
    }

    public UUID getEventId() {
        return eventId;
    }

    // Only needed for constructing test fixtures without a real persistence
    // context (eventId is otherwise DB-generated - see @GeneratedValue above).
    void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public ChainOutboxStatus getStatus() {
        return status;
    }

    public void setStatus(ChainOutboxStatus status) {
        this.status = status;
    }

    public short getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(short retryCount) {
        this.retryCount = retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChainOutboxEvent that)) return false;
        return eventId != null && eventId.equals(that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }
}
