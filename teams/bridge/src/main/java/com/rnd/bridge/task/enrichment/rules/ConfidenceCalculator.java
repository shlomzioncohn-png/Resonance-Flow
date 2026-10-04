package com.rnd.bridge.task.enrichment.rules;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Computes a confidence score for the enrichment result based on which
 * signals were found. Returns null when nothing was inferred.
 */
@Component
public class ConfidenceCalculator {

    public Double calculate(Integer urgency, Instant deadline, String title) {
        double confidence = 0.0;
        if (urgency != null) {
            confidence = Math.max(confidence, 0.9);
        }
        if (deadline != null) {
            confidence = Math.max(confidence, 0.85);
        }
        if (title != null) {
            confidence = Math.max(confidence, 0.6);
        }
        return confidence > 0 ? confidence : null;
    }
}
