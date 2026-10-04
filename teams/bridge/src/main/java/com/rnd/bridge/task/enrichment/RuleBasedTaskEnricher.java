package com.rnd.bridge.task.enrichment;

import com.rnd.bridge.task.enrichment.rules.ConfidenceCalculator;
import com.rnd.bridge.task.enrichment.rules.DateExtractor;
import com.rnd.bridge.task.enrichment.rules.UrgencyKeywordScorer;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Rule-based enricher: fills missing urgency, deadline and title from
 * keywords and date patterns in the source text. Never throws; on any
 * doubt it returns {@link EnrichmentOutput#empty()}.
 */
@Component
public class RuleBasedTaskEnricher implements TaskEnricher {

    private final UrgencyKeywordScorer urgencyScorer;
    private final DateExtractor dateExtractor;
    private final ConfidenceCalculator confidenceCalculator;

    public RuleBasedTaskEnricher(UrgencyKeywordScorer urgencyScorer,
                                 DateExtractor dateExtractor,
                                 ConfidenceCalculator confidenceCalculator) {
        this.urgencyScorer = urgencyScorer;
        this.dateExtractor = dateExtractor;
        this.confidenceCalculator = confidenceCalculator;
    }

    @Override
    public EnrichmentOutput enrich(EnrichmentInput input) {
        Integer urgency = input.urgency() == null
                ? urgencyScorer.score(input.title(), input.description())
                : null;
        Instant deadline = input.deadline() == null
                ? dateExtractor.extract(input.description(), input.asOf())
                : null;
        String title = isBlank(input.title()) && !isBlank(input.description())
                ? firstLine(input.description())
                : null;

        if (urgency == null && deadline == null && title == null) {
            return EnrichmentOutput.empty();
        }

        Double confidence = confidenceCalculator.calculate(urgency, deadline, title);
        return new EnrichmentOutput(urgency, confidence, title, null, deadline, null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstLine(String text) {
        int idx = text.indexOf('\n');
        String line = idx >= 0 ? text.substring(0, idx) : text;
        return line.length() > 120 ? line.substring(0, 120) : line;
    }
}
