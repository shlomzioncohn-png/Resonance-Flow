package com.rnd.bridge.raw;

import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import org.springframework.stereotype.Component;

/**
 * Fails fast on a malformed {@link RawObjectEvent} before it leaves the
 * lane, mirroring task.TaskValidator's role for the Task side of the
 * pipeline. Wired into PointerEventPublisher/MediaEventPublisher as a gate
 * between building the event and sending it to Kafka.
 */
@Component
public final class RawObjectValidator implements PayloadValidator<RawObjectEvent> {

    @Override
    public void validate(RawObjectEvent event) {
        requireNonNull(event.sourceType(), "sourceType");
        requireNonNull(event.tenantId(), "tenantId");
        requireNonNull(event.correlationId(), "correlationId");
        requireNonNull(event.ingestedAt(), "ingestedAt");
        requireNonNull(event.scopeDecision(), "scopeDecision");
        requireNonNull(event.changeType(), "changeType");

        if (event.objects() == null || event.objects().isEmpty()) {
            throw new ValidationException("Invalid RawObjectEvent.objects: must not be empty");
        }
        for (RawObjectRef object : event.objects()) {
            requireNonNull(object.role(), "objects[].role");
            requireNonNull(object.objectKey(), "objects[].objectKey");
            requireNonNull(object.contentHash(), "objects[].contentHash");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new ValidationException("Invalid RawObjectEvent." + field + " = null: must not be null");
        }
    }
}
