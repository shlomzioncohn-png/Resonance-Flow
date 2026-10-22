package com.rnd.bridge.ai.observability;

import com.rnd.bridge.ai.AiProperties;
import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that logs one {@code model_call} line per call to one model, in the same
 * {@code key=value} shape and with the same field names as Mind's observability/tracking.py, plus
 * {@code usage} - so one grep finds model calls in both services' logs. Over-limit calls also get
 * a {@code MODEL_CALL_SLOW} / {@code MODEL_CALL_EXPENSIVE} WARNING line, Mind's markers too.
 * <p>
 * Cost follows Mind's rule: a free model costs 0.0; a paid model is priced from its per-million
 * prices when the provider reported tokens, and is otherwise {@code unpriced} - unknown, never 0.
 * A failed call is logged with status=error and re-thrown unchanged.
 */
public class MeteredLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MeteredLlmClient.class);
    private static final double PER_MILLION = 1_000_000.0;

    private final LlmClient delegate;
    private final String usage;
    private final AiProperties.Model model;
    private final AiProperties.Monitoring limits;

    public MeteredLlmClient(LlmClient delegate, String usage, AiProperties.Model model,
                            AiProperties.Monitoring limits) {
        this.delegate = delegate;
        this.usage = usage;
        this.model = model;
        this.limits = limits;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long started = System.nanoTime();
        try {
            LlmResponse response = delegate.complete(request);
            record("success", elapsedMs(started), response.promptTokens(), response.completionTokens(),
                    cost(model, response.promptTokens(), response.completionTokens()));
            return response;
        } catch (RuntimeException e) {
            record("error", elapsedMs(started), null, null, null);
            throw e;
        }
    }

    /** USD for one call, or null when it can't be priced. */
    static Double cost(AiProperties.Model model, Integer promptTokens, Integer completionTokens) {
        if (model.free()) {
            return 0.0;
        }
        if (model.usdPerMillionInput() == null || model.usdPerMillionOutput() == null
                || promptTokens == null || completionTokens == null) {
            return null;
        }
        return promptTokens / PER_MILLION * model.usdPerMillionInput()
                + completionTokens / PER_MILLION * model.usdPerMillionOutput();
    }

    private void record(String status, double durationMs, Integer promptTokens, Integer completionTokens,
                        Double costUsd) {
        log.info("model_call usage={} model_name={} kind=llm status={} duration_ms={} prompt_tokens={} "
                        + "completion_tokens={} cost_usd={}",
                usage, model.name(), status, format(durationMs, "%.1f"),
                promptTokens == null ? "unreported" : promptTokens,
                completionTokens == null ? "unreported" : completionTokens,
                costUsd == null ? "unpriced" : format(costUsd, "%.6f"));

        double durationLimitMs = limits.llmMaxDuration().toMillis();
        if (durationMs > durationLimitMs) {
            log.warn("MODEL_CALL_SLOW usage={} model_name={} kind=llm status={} duration_ms={} limit_ms={} over={}",
                    usage, model.name(), status, format(durationMs, "%.1f"), format(durationLimitMs, "%.1f"),
                    timesOver(durationMs, durationLimitMs));
        }
        if (costUsd != null && costUsd > limits.llmMaxCostUsd()) {
            log.warn("MODEL_CALL_EXPENSIVE usage={} model_name={} kind=llm status={} cost_usd={} limit_usd={} over={}",
                    usage, model.name(), status, format(costUsd, "%.6f"), format(limits.llmMaxCostUsd(), "%.6f"),
                    timesOver(costUsd, limits.llmMaxCostUsd()));
        }
    }

    private static double elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    private static String timesOver(double value, double limit) {
        return limit > 0 ? format(value / limit, "%.1f") + "x" : "-";
    }

    private static String format(double value, String pattern) {
        return String.format(Locale.ROOT, pattern, value);
    }
}
