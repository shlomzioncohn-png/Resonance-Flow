package com.rnd.bridge.raw;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawObjectValidatorTest {

    private final RawObjectValidator validator = new RawObjectValidator();

    private static RawObjectRef validRef() {
        return new RawObjectRef(
                Role.BODY, "EMAIL/kamatech/2026-08-18/x.json", "sha256:" + "a".repeat(64),
                "application/json", 1L, Modality.TEXT);
    }

    private static RawObjectEvent eventWithTenantId(String tenantId) {
        return RawObjectEvent.create(
                RawSourceType.EMAIL, tenantId, Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                List.of(), Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, ChangeType.UPSERT,
                List.of(validRef()));
    }

    @Test
    void validatePassesForAWellFormedEvent() {
        assertThatCode(() -> validator.validate(eventWithTenantId("00000000-0000-0000-0000-000000000001"))).doesNotThrowAnyException();
    }

    @Test
    void validateRejectsBlankTenantId() {
        assertThatThrownBy(() -> validator.validate(eventWithTenantId("   ")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void validateRejectsEmptyTenantId() {
        assertThatThrownBy(() -> validator.validate(eventWithTenantId("")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void validateRejectsNullTenantId() {
        assertThatThrownBy(() -> validator.validate(eventWithTenantId(null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void validateRejectsANonUuidTenantId() {
        // Mind passes tenant_id through to Inbox's tasks.tenant_id (a UUID) - "kamatech" used to
        // leave Bridge and only fail there.
        assertThatThrownBy(() -> validator.validate(eventWithTenantId("kamatech")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tenantId")
                .hasMessageContaining("UUID");
    }

    @Test
    void validateRejectsNullCorrelationIdRegardlessOfTenantId() {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "00000000-0000-0000-0000-000000000001", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                List.of(), Instant.parse("2026-08-18T09:15:00Z"), null, 0, ChangeType.UPSERT, List.of(validRef()));

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    void validateRejectsAnEmptyObjectList() {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "00000000-0000-0000-0000-000000000001", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                List.of(), Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, ChangeType.UPSERT, List.of());

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("objects");
    }

    @Test
    void validatePassesForADeleteReviewEventWithAnEmptyObjectList() {
        // DELETE_REVIEW's own contract (Calendar's cancellation, Drive's trashed, and now
        // Slack's emptied-group path) is an empty objects list - the logical item no longer
        // exists. Regression test: this used to be unconditionally rejected by
        // requireNonEmpty(objects), which would have blocked every DELETE_REVIEW event from ever
        // reaching Kafka through the real (non-mocked) validator.
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                List.of(), Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 1, ChangeType.DELETE_REVIEW, List.of());

        assertThatCode(() -> validator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void validateRejectsAnObjectMissingItsContentHash() {
        RawObjectRef invalidRef = new RawObjectRef(Role.BODY, "EMAIL/kamatech/2026-08-18/x.json", null,
                "application/json", 1L, Modality.TEXT);
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "00000000-0000-0000-0000-000000000001", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                List.of(), Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, ChangeType.UPSERT,
                List.of(invalidRef));

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("contentHash");
    }
}
