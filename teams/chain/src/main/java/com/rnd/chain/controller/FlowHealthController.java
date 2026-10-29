package com.rnd.chain.controller;

import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.service.FlowHealthFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only access to the flow-health flags written by the analytics job. */
@RestController
@RequestMapping("/flow-health")
@RequiredArgsConstructor
public class FlowHealthController {

    private final FlowHealthFlagService flowHealthFlagService;

    /** {@code pattern} is the enum name (e.g. CHRONIC_BLOCKER), {@code severity} is low/medium/high; omitted means any. */
    @GetMapping("/flags")
    public ResponseEntity<PageResponse<FlowHealthFlagResponse>> getFlags(
            @RequestParam(required = false) FlowHealthPattern pattern,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(
                flowHealthFlagService.getFlags(pattern, parseSeverity(severity), page, size)
                        .map(FlowHealthFlagResponse::from));
    }

    // Spring's enum converter is case-sensitive; severity is lower-case on the wire.
    private static FlowHealthSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FlowHealthSeverity.fromValue(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("severity must be one of low, medium, high");
        }
    }
}
