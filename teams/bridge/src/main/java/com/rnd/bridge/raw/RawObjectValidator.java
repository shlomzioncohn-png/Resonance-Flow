package com.rnd.bridge.raw;

import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
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
        requireNonNull(event.sourceType(), "sourceType");
        requireNonBlank(event.tenantId(), "tenantId");
        requireNonNull(event.ingestedAt(), "ingestedAt");
        requireNonNull(event.scopeDecision(), "scopeDecision");
        requireNonNull(event.correlationId(), "correlationId");
        requireNonEmpty(event.objects(), "objects");
        for (int i = 0; i < event.objects().size(); i++) {
            validateRef(event.objects().get(i), i);
        }
    }

    private void validateRef(RawObjectRef ref, int index) {
        requireNonNull(ref.role(), "objects[" + index + "].role");
        requireNonBlank(ref.objectKey(), "objects[" + index + "].objectKey");
        requireNonBlank(ref.contentHash(), "objects[" + index + "].contentHash");
        requireNonBlank(ref.contentType(), "objects[" + index + "].contentType");
        requireNonNull(ref.modality(), "objects[" + index + "].modality");
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new ValidationException("Invalid RawObjectEvent." + field + " = null: must not be null");
        }
    }

    private static void requireNonBlank(String value, String field) {
        requireNonNull(value, field);
        if (value.isBlank()) {
            throw new ValidationException("Invalid RawObjectEvent." + field + " = \"" + value + "\": must not be blank");
        }
    }

    private static void requireNonEmpty(java.util.List<?> value, String field) {
        if (value == null || value.isEmpty()) {
            throw new ValidationException("Invalid RawObjectEvent." + field + " = " + value + ": must not be empty");
        }
    }
}
