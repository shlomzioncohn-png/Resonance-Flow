package com.rnd.chain.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * מעטפה גנרית לכל האירועים שChain מפרסם או צורך.
 *
 * TODO: זהו placeholder זמני. לפי המוסכמות הכלליות של הפרויקט (כנראה
 * מוגדרות ע"י צוות Relay), ייתכן שהמבנה האמיתי שונה - שדות נוספים,
 * שמות שדות אחרים, או פורמט serialization שונה (למשל Avro במקום JSON).
 * יש לוודא זאת מול הצוות ולעדכן מחלקה זו בהתאם ברגע שמתברר המבנה הסופי.
 *
 * @param <T> הטיפוס של ה-payload הספציפי לאירוע (למשל TaskSchedulingUpdatedPayload)
 */
public class EventEnvelope<T> {

    private final UUID eventId;
    private final String eventType;
    private final Instant occurredAt;
    private final String correlationId;
    private final T payload;

    public EventEnvelope(String eventType, String correlationId, T payload) {
        this(UUID.randomUUID(), eventType, Instant.now(), correlationId, payload);
    }

    public EventEnvelope(UUID eventId, String eventType, Instant occurredAt,
                         String correlationId, T payload) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.correlationId = correlationId;
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public T getPayload() {
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventEnvelope<?> that)) return false;
        return eventId.equals(that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "EventEnvelope{" +
                "eventId=" + eventId +
                ", eventType='" + eventType + '\'' +
                ", occurredAt=" + occurredAt +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
}