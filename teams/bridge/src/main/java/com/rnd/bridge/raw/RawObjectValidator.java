package com.rnd.bridge.raw;

import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ItemCategory;
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
        validateClassification(event);
    }

    private void validateRef(RawObjectRef ref, int index) {
        requireNonNull(ref.role(), "objects[" + index + "].role");
        requireNonBlank(ref.objectKey(), "objects[" + index + "].objectKey");
        requireNonBlank(ref.contentHash(), "objects[" + index + "].contentHash");
        requireNonBlank(ref.contentType(), "objects[" + index + "].contentType");
        requireNonNull(ref.modality(), "objects[" + index + "].modality");
    }

    /**
     * Classification's own invariants (non-null labels, confidence in [0, 1]) are enforced by
     * its records' constructors - only the cross-field rule is left: the category must belong
     * to this event's source type (no MeetingCategory on a SCANNED_DOC event).
     */
    private static void validateClassification(RawObjectEvent event) {
        Classification classification = event.classification();
        if (classification == null) {
            return;
        }
        ItemCategory category = classification.category().value();
        if (!category.sourceTypes().contains(event.sourceType())) {
            throw new ValidationException("Invalid RawObjectEvent.classification.category = "
                    + category + ": belongs to source type(s) " + category.sourceTypes()
                    + ", event is " + event.sourceType());
        }
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
