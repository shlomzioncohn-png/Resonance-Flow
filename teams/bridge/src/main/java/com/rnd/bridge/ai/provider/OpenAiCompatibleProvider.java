package com.rnd.bridge.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.rnd.bridge.ai.AiProperties;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmQuotaExhaustedException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * {@link LlmProvider} over the OpenAI chat-completions wire format
 * ({@code POST {base-url}/chat/completions}), which Gemini, Groq, OpenAI and DeepSeek all expose.
 * temperature 0 keeps the same input answering the same way across runs. max_tokens and
 * reasoning_effort are sent only when the model sets them (max-output-tokens, reasoning-effort).
 * <p>
 * Deliberately no response_format parameter: support for it differs between providers, and
 * callers parse the reply themselves.
 * <p>
 * Transient failures are retried up to {@link #MAX_RETRIES} times: 429 (rate limit), 502/503/504
 * (an overloaded or restarting provider - Gemini's free tier returns 503 "high demand" under load)
 * and network errors, waiting the provider's Retry-After (header, or Gemini's "retry in 21s" in
 * the body) when it sends one, else a growing backoff. Anything else (400 bad request, 401/403 bad
 * key, 404 unknown model) fails at once - retrying can't fix it. Either way the failure leaves as
 * an {@link LlmException}, retryable when it was transient, so a fallback model is only tried
 * when the next one could actually help.
 * <p>
 * Free-tier quota: a 429 that asks to wait longer than {@link #MAX_RETRY_AFTER} is a spent quota
 * (Gemini's daily cap answers "retry in 18h..."), not a rate blip. It is not retried, and the model
 * is marked exhausted until then: further calls to it fail at once, without a request, instead of
 * spending another round trip per item to hear the same answer. Both throw
 * {@link LlmQuotaExhaustedException} with that time; still retryable, so a fallback model with its
 * own quota is tried.
 * <p>
 * A reply cut off at max_tokens (finish_reason "length") or withheld by the provider's safety
 * filter ("content_filter") is a failure, not an answer, and isn't retried: the same request gets
 * the same result. The response's {@code usage} block is read for token counts; a provider that
 * leaves it out gets null counts (unknown), never 0.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    public static final String TYPE = "openai-compatible";

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    static final int MAX_RETRIES = 2;
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5));
    // A provider asking for longer than this is better treated as "unavailable" than waited on.
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    // The wait some providers put in a 429 body: Gemini "Please retry in 18h19m34.9s.",
    // Groq "Please try again in 7.66s" / "try again in 1m30s".
    private static final Pattern RETRY_IN = Pattern.compile(
            "(?:retry|try again) in (?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?", Pattern.CASE_INSENSITIVE);

    private final String providerName;
    private final AiProperties.Provider config;
    private final RestClient restClient;
    private final Consumer<Duration> sleeper;
    private final Clock clock;
    private final Map<String, Instant> quotaExhaustedUntil = new ConcurrentHashMap<>();

    public OpenAiCompatibleProvider(String providerName, AiProperties.Provider config,
                                    RestClient.Builder restClientBuilder, Clock clock) {
        this(providerName, config, restClientBuilder, OpenAiCompatibleProvider::sleep, clock);
    }

    OpenAiCompatibleProvider(String providerName, AiProperties.Provider config,
                             RestClient.Builder restClientBuilder, Consumer<Duration> sleeper, Clock clock) {
        this.providerName = providerName;
        this.config = config;
        this.sleeper = sleeper;
        this.clock = clock;
        // clone(): one shared builder serves every provider, each with its own base URL.
        this.restClient = restClientBuilder.clone()
                .baseUrl(stripTrailingSlash(config.baseUrl()))
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(config.timeout())
                        .withReadTimeout(config.timeout())))
                .build();
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    @Override
    public LlmResponse complete(String modelName, LlmRequest request) {
        if (!configured()) {
            throw new LlmException("Provider '" + providerName + "' has no api-key", false);
        }
        Instant exhaustedUntil = quotaExhaustedUntil.get(modelName);
        if (exhaustedUntil != null) {
            if (clock.instant().isBefore(exhaustedUntil)) {
                throw new LlmQuotaExhaustedException(quotaMessage(modelName, exhaustedUntil) + " - not sent",
                        exhaustedUntil, true, null);
            }
            quotaExhaustedUntil.remove(modelName, exhaustedUntil);
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return completeOnce(modelName, request);
            } catch (HttpStatusCodeException | ResourceAccessException e) {
                boolean transientFailure = isTransient(e);
                Optional<Duration> requestedWait = retryAfter(e);
                if (requestedWait.isPresent() && requestedWait.get().compareTo(MAX_RETRY_AFTER) > 0) {
                    Instant until = clock.instant().plus(requestedWait.get());
                    quotaExhaustedUntil.put(modelName, until);
                    log.warn("{} - further calls to it fail fast until then", quotaMessage(modelName, until));
                    throw new LlmQuotaExhaustedException(quotaMessage(modelName, until), until, true, e);
                }
                if (attempt >= MAX_RETRIES || !transientFailure) {
                    throw new LlmException("Model call to " + providerName + "/" + modelName
                            + " failed (" + describe(e) + ")", transientFailure, e);
                }
                Duration wait = requestedWait.orElse(BACKOFF.get(attempt));
                log.warn("Model call failed ({}) - retry {}/{} in {}s",
                        describe(e), attempt + 1, MAX_RETRIES, wait.toSeconds());
                sleeper.accept(wait);
            }
        }
    }

    private LlmResponse completeOnce(String modelName, LlmRequest request) {
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", request.userText()));
        for (byte[] pngImage : request.pngImages()) {
            String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngImage);
            userContent.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", 0);
        if (request.maxOutputTokens() != null) {
            body.put("max_tokens", request.maxOutputTokens());
        }
        if (request.reasoningEffort() != null) {
            body.put("reasoning_effort", request.reasoningEffort());
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", userContent)));

        JsonNode response = restClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode choice = response == null ? null : response.path("choices").path(0);
        String finishReason = choice == null ? "" : choice.path("finish_reason").asText("");
        if ("length".equals(finishReason)) {
            throw new LlmException("Reply from " + modelName + " was cut off at max_tokens="
                    + request.maxOutputTokens() + " - raise max-output-tokens or shorten the request", false);
        }
        if ("content_filter".equals(finishReason)) {
            throw new LlmException("Reply from " + modelName + " was withheld by the provider's safety filter", false);
        }
        JsonNode content = choice == null ? null : choice.path("message").path("content");
        if (content == null || !content.isTextual()) {
            throw new LlmException("Chat completion response has no choices[0].message.content", false);
        }
        JsonNode usage = response.path("usage");
        return new LlmResponse(content.asText(), modelName,
                intOrNull(usage.path("prompt_tokens")), intOrNull(usage.path("completion_tokens")));
    }

    private String quotaMessage(String modelName, Instant until) {
        return "Quota for " + providerName + "/" + modelName + " is spent until " + until;
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isIntegralNumber() ? node.asInt() : null;
    }

    private static boolean isTransient(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        int status = ((HttpStatusCodeException) e).getStatusCode().value();
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    /**
     * How long the provider asked us to wait: its Retry-After header (whole seconds), else a
     * "retry in 1h2m3.4s" in the body of a 429. Empty when it said nothing usable.
     */
    static Optional<Duration> retryAfter(RuntimeException e) {
        if (!(e instanceof HttpStatusCodeException statusException)) {
            return Optional.empty();
        }
        String header = statusException.getResponseHeaders() == null ? null
                : statusException.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null) {
            try {
                Duration wait = Duration.ofSeconds(Long.parseLong(header.strip()));
                return wait.isNegative() ? Optional.empty() : Optional.of(wait);
            } catch (NumberFormatException notSeconds) {
                // an HTTP-date - fall through to the body
            }
        }
        if (statusException.getStatusCode().value() != 429) {
            return Optional.empty();
        }
        Matcher matcher = RETRY_IN.matcher(statusException.getResponseBodyAsString());
        while (matcher.find()) {
            if (matcher.group(1) == null && matcher.group(2) == null && matcher.group(3) == null) {
                continue;
            }
            long millis = (matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1)) * 3_600_000L)
                    + (matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2)) * 60_000L)
                    + (matcher.group(3) == null ? 0 : Math.round(Double.parseDouble(matcher.group(3)) * 1000));
            return Optional.of(Duration.ofMillis(millis));
        }
        return Optional.empty();
    }

    private static String describe(RuntimeException e) {
        return e instanceof HttpStatusCodeException statusException
                ? "HTTP " + statusException.getStatusCode().value() : e.getClass().getSimpleName();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off a model call", e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
