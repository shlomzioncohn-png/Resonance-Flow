package com.rnd.bridge.intake.unstructured.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.unstructured.dto.ScopeDecision;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.dto.SourceType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Current behavior is deliberately trivial (see plan.md's schema update: "no filtering
 * right now, every event is INCLUDED unconditionally"). This test documents that decision
 * so a future swap to real filtering logic is a visible, intentional change here.
 */
class ScopingFilterServiceTest {

    private final ScopingFilterService service = new ScopingFilterService();

    @Test
    void alwaysReturnsIncludedRegardlessOfContent() {
        SourceRecord personalEmail = new SourceRecord(
                SourceType.EMAIL, "kamatech", "id-1", Instant.now(),
                "mom@gmail.com", "ארוחת ערב בשישי", "בואי נקבע ארוחת ערב", "{}", null);
        SourceRecord recordWithNoSignal = new SourceRecord(
                SourceType.DRIVE_DOC, "kamatech", "id-2", Instant.now(),
                "malki@kamatech.org", "IMG_2043.jpg", null, "{}", "image/jpeg");

        assertThat(service.decide(personalEmail)).isEqualTo(ScopeDecision.INCLUDED);
        assertThat(service.decide(recordWithNoSignal)).isEqualTo(ScopeDecision.INCLUDED);
    }
}
