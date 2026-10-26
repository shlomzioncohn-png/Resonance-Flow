package com.rnd.bridge.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * bridge.ai.* - see application.yml for what each setting does. Three levels:
 * <ul>
 *   <li>{@code providers}: where to connect and with which key - each key appears once, here.</li>
 *   <li>{@code models}: the one shared list of models Bridge may use. Adding a model means adding
 *       it here, once, for everyone.</li>
 *   <li>{@code usages}: optional per-usage choices, always pointing at a model from the list.</li>
 * </ul>
 * Validated at startup by {@link LlmClientRegistry}, which refuses to start on anything that
 * points nowhere or, while free-only is on, on any model not marked free.
 */
@ConfigurationProperties(prefix = "bridge.ai")
public record AiProperties(
        @DefaultValue("true") boolean freeOnly,
        String defaultModel,
        Map<String, Provider> providers,
        Map<String, Model> models,
        Map<String, Usage> usages,
        @DefaultValue Defaults defaults,
        @DefaultValue Monitoring monitoring) {

    public AiProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        models = models == null ? Map.of() : Map.copyOf(models);
        usages = usages == null ? Map.of() : Map.copyOf(usages);
    }

    /**
     * @param type only "openai-compatible" today (Gemini, Groq, OpenAI and DeepSeek all speak it);
     *             another wire format is a new {@code LlmProvider} implementation
     */
    public record Provider(
            @DefaultValue("openai-compatible") String type,
            String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("60s") Duration timeout) {

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * @param name   the provider's own model id, e.g. gemini-3.6-flash
     * @param images whether it accepts images - a request with images is refused before it is
     *               sent to a model without this
     * @param free   whether calling it costs nothing. The real guarantee is a provider key with no
     *               billing attached; this flag is what lets Bridge refuse a paid model up front
     * @param usdPerMillionInput/usdPerMillionOutput only for a paid model; null means unpriced
     * @param maxOutputTokens cap on the reply, sent as max_tokens; null sends none. For a thinking
     *                        model it includes the thinking tokens, so keep it well above the answer
     * @param reasoningEffort sent as reasoning_effort; null sends none. "none" turns a thinking
     *                        model's thinking off - for a short classification it only adds
     *                        latency (Mind measured 101s with it on vs ~1.4s off) and quota
     */
    public record Model(
            String provider,
            String name,
            @DefaultValue("false") boolean images,
            @DefaultValue("false") boolean free,
            Double usdPerMillionInput,
            Double usdPerMillionOutput,
            Integer maxOutputTokens,
            String reasoningEffort) {
    }

    /** Every field optional: null means "use bridge.ai.default-model / bridge.ai.defaults". */
    public record Usage(String model, List<String> fallback, Boolean redactPii, Integer dailyCallLimit) {

        public Usage {
            fallback = fallback == null ? List.of() : List.copyOf(fallback);
        }
    }

    /** @param dailyCallLimit calls per usage per UTC day; 0 turns the limit off */
    public record Defaults(
            @DefaultValue("true") boolean redactPii,
            @DefaultValue("200") int dailyCallLimit) {
    }

    /** When a call is logged as MODEL_CALL_SLOW / MODEL_CALL_EXPENSIVE (same markers as Mind). */
    public record Monitoring(
            @DefaultValue("30s") Duration llmMaxDuration,
            @DefaultValue("0.02") double llmMaxCostUsd) {
    }
}
