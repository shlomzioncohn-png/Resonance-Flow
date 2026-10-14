package com.rnd.bridge.task.enrichment;

import com.rnd.bridge.task.model.SourceType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for the rule-based task enrichment engine.
 * Bound from {@code llm.enrichment.*} in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "llm.enrichment")
public class EnrichmentProperties {

    /** Master switch for the enrichment engine. */
    private boolean enabled = false;

    /** Source types that are allowed to be enriched. */
    private Set<SourceType> supportedSources = Set.of();

    /** Keyword -> urgency level (1..5). Overrides the built-in defaults when non-empty. */
    private Map<String, Integer> urgencyKeywords = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<SourceType> getSupportedSources() {
        return supportedSources;
    }

    public void setSupportedSources(Set<SourceType> supportedSources) {
        this.supportedSources = supportedSources;
    }

    public Map<String, Integer> getUrgencyKeywords() {
        return urgencyKeywords;
    }

    public void setUrgencyKeywords(Map<String, Integer> urgencyKeywords) {
        this.urgencyKeywords = urgencyKeywords;
    }
}
