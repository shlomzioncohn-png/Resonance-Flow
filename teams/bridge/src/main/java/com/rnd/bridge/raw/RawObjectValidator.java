package com.rnd.bridge.raw;

import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.model.RawObjectEvent;
import org.springframework.stereotype.Component;

/**
 * Fails fast on a malformed {@link RawObjectEvent} before it leaves the
 * lane, mirroring task.TaskValidator's role for the Task side of the
 * pipeline. Wired in as UnstructuredIntakeLane's PayloadValidator - the
 * template method calls it between UnstructuredPayloadMapper.map() and
 * KafkaRawObjectPublisher.publish().
 */
@Component
public final class RawObjectValidator implements PayloadValidator<RawObjectEvent> {

    @Override
    public void validate(RawObjectEvent event) {
        requireNonNull(event.objectKey(), "objectKey");
        requireNonNull(event.sourceType(), "sourceType");
        requireNonNull(event.tenantId(), "tenantId");
        requireNonNull(event.ingestedAt(), "ingestedAt");
        requireNonNull(event.scopeDecision(), "scopeDecision");
        requireNonNull(event.contentHash(), "contentHash");
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new ValidationException("Invalid RawObjectEvent." + field + " = null: must not be null");
        }
    }
}
