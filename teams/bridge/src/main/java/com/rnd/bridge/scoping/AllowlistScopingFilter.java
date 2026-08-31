package com.rnd.bridge.scoping;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.ScopeDecision;
import org.springframework.stereotype.Service;

/**
 * Kept as its own class deliberately: this is the seam that gets swapped out once real
 * work-related-only filtering is built. Per direct instruction, no filtering runs right
 * now — every record is INCLUDED unconditionally. Renamed from ScopingFilterService to
 * match the target package layout; behavior is unchanged (see the plan's Phase 3 note:
 * wiring this up to ScopingProperties' allowedDomains/allowedKeywords is a separate,
 * follow-up change, not part of this structural move).
 */
@Service
public class AllowlistScopingFilter {

    public ScopeDecision decide(SourceRecord record) {
        return ScopeDecision.INCLUDED;
    }
}
