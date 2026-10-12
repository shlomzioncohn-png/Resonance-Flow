package com.rnd.bridge.media.classification;

import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.MeetingCategory;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * bridge.media.classification.* - see application.yml for what each setting does.
 * <p>
 * enabled=false (the default) turns the whole stage off: every item goes out INCLUDED with no
 * classification, exactly as before this stage existed. enabled=true with no model.api-key runs
 * the deterministic rules only.
 */
@ConfigurationProperties(prefix = "bridge.media.classification")
public record MediaClassificationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0.6") double minConfidenceToExclude,
        @DefaultValue("60s") Duration minMeetingDuration,
        @DefaultValue("4000") int transcriptExcerptChars,
        @DefaultValue({"TEST", "SOCIAL"}) Set<MeetingCategory> excludedMeetingCategories,
        @DefaultValue({"BLANK", "ADVERTISEMENT"}) Set<DocumentCategory> excludedDocumentCategories,
        @DefaultValue Model model) {

    /**
     * Any OpenAI-compatible chat-completions endpoint (Gemini, DeepSeek, Groq, ...) - switching
     * provider is a change to these settings only.
     */
    public record Model(
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta/openai") String baseUrl,
            @DefaultValue("gemini-3.8-flash") String name,
            @DefaultValue("") String apiKey,
            @DefaultValue("v1") String promptVersion,
            @DefaultValue("60s") Duration timeout) {

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
