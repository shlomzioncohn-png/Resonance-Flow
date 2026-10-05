package com.rnd.bridge.raw;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawObjectValidatorTest {

    private final RawObjectValidator validator = new RawObjectValidator();

    private static RawObjectEvent eventWithTenantId(String tenantId) {
        return RawObjectEvent.create(
                "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, tenantId,
                Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                "sha256:" + "a".repeat(64), Modality.TEXT, List.of(), "application/json", 1L,
                Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, 0, 0, ChangeType.UPSERT);
    }

    @Test
    void validatePassesForAWellFormedEvent() {
        assertThatCode(() -> validator.validate(eventWithTenantId("kamatech"))).doesNotThrowAnyException();
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
    void validateRejectsNullCorrelationIdRegardlessOfTenantId() {
        RawObjectEvent event = RawObjectEvent.create(
                "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech",
                Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                "sha256:" + "a".repeat(64), Modality.TEXT, List.of(), "application/json", 1L,
                Instant.parse("2026-08-18T09:15:00Z"), null, 0, 0, 0, ChangeType.UPSERT);

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("correlationId");
    }
}
