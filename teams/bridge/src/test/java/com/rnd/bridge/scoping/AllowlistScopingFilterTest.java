package com.rnd.bridge.scoping;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Current behavior is deliberately trivial (see plan.md's schema update: "no filtering
 * right now, every event is INCLUDED unconditionally"). This test documents that decision
 * so a future swap to real filtering logic is a visible, intentional change here.
 */
class AllowlistScopingFilterTest {

    private final AllowlistScopingFilter service = new AllowlistScopingFilter();

    @Test
    void alwaysReturnsIncludedRegardlessOfContent() {
        SourceRecord personalEmail = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1", "corr-1", Instant.now(),
                "mom@gmail.com", List.of("mom@gmail.com"), "ארוחת ערב בשישי", "בואי נקבע ארוחת ערב",
                "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);
        SourceRecord recordWithNoSignal = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "id-2", "corr-2", Instant.now(),
                "malki@kamatech.org", List.of("malki@kamatech.org"), "IMG_2043.jpg", null,
                "{}".getBytes(StandardCharsets.UTF_8), "image/jpeg", null, 0, 0, List.of(), 0);

        assertThat(service.decide(personalEmail)).isEqualTo(ScopeDecision.INCLUDED);
        assertThat(service.decide(recordWithNoSignal)).isEqualTo(ScopeDecision.INCLUDED);
    }
}
