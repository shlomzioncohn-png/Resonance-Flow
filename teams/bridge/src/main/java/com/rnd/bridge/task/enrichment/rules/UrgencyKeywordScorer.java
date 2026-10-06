package com.rnd.bridge.task.enrichment.rules;

import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Scores urgency 1..5 from keywords found in title + description.
 * The built-in map is the default; it can be overridden or extended via
 * {@code llm.enrichment.urgency-keywords} in application.yml.
 */
@Component
public class UrgencyKeywordScorer {

    private static final Map<String, Integer> DEFAULT_KEYWORDS = Map.ofEntries(
            Map.entry("blocker", 5),
            Map.entry("critical", 5),
            Map.entry("asap", 5),
            Map.entry("urgent", 5),
            Map.entry("production down", 5),
            Map.entry("high priority", 4),
            Map.entry("important", 4),
            Map.entry("needed soon", 4),
            Map.entry("medium", 3),
            Map.entry("todo", 3),
            Map.entry("needed", 3),
            Map.entry("low", 1),
            Map.entry("whenever", 1),
            Map.entry("nice to have", 1)
    );

    private final EnrichmentProperties properties;

    public UrgencyKeywordScorer(EnrichmentProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the highest urgency level found, or null when no keyword matches.
     */
    public Integer score(String title, String description) {
        String text = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .toLowerCase();
        Map<String, Integer> keywords = properties.getUrgencyKeywords().isEmpty()
                ? DEFAULT_KEYWORDS
                : properties.getUrgencyKeywords();

        Integer best = null;
        for (Map.Entry<String, Integer> entry : keywords.entrySet()) {
            if (text.contains(entry.getKey().toLowerCase())) {
                if (best == null || entry.getValue() > best) {
                    best = entry.getValue();
                }
            }
        }
        return best;
    }
}
