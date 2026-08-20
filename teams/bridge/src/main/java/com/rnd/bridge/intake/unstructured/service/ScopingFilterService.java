package com.rnd.bridge.intake.unstructured.service;

import com.rnd.bridge.intake.unstructured.dto.ScopeDecision;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import org.springframework.stereotype.Service;

/**
 * Kept as its own class deliberately: this is the seam that gets swapped out once real
 * work-related-only filtering is built. Per direct instruction, no filtering runs right
 * now — every record is INCLUDED unconditionally.
 */
@Service
public class ScopingFilterService {

    public ScopeDecision decide(SourceRecord record) {
        return ScopeDecision.INCLUDED;
    }
}
