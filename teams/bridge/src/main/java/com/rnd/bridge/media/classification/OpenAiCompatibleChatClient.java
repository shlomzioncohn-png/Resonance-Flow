package com.rnd.bridge.media.classification;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * {@link ChatCompletionClient} over the OpenAI chat-completions wire format
 * ({@code POST {base-url}/chat/completions}), which Gemini, DeepSeek and Groq all expose - so the
 * provider is chosen purely by bridge.media.classification.model.*. temperature 0 keeps the same
 * input classifying the same way across runs.
 * <p>
 * Deliberately no response_format parameter: support for it differs between providers, and
 * {@link ModelMediaClassifier} already tolerates a reply wrapped in prose or code fences.
 * <p>
 * Transient failures are retried up to {@link #MAX_RETRIES} times: 429 (rate limit), 502/503/504
 * (an overloaded or restarting provider - Gemini's free tier returns 503 "high demand" under load)
 * and network errors, waiting the provider's Retry-After when it sends one, else a growing backoff.
 * Anything else (400 bad request, 401/403 bad key, 404 unknown model) fails at once - retrying
 * can't fix it. When retries run out the exception propagates, and MediaClassificationService
 * lands the item unclassified, as for any other failure.
 */
@Component
public class OpenAiCompatibleChatClient implements ChatCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    static final int MAX_RETRIES = 2;
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5));
    // A provider asking for longer than this is better treated as "unavailable" than waited on.
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final MediaClassificationProperties.Model model;
    private final Consumer<Duration> sleeper;

    @Autowired
    public OpenAiCompatibleChatClient(RestClient.Builder restClientBuilder, MediaClassificationProperties properties) {
        this(restClientBuilder, properties, OpenAiCompatibleChatClient::sleep);
    }

    OpenAiCompatibleChatClient(RestClient.Builder restClientBuilder, MediaClassificationProperties properties,
                               Consumer<Duration> sleeper) {
        this.sleeper = sleeper;
        this.model = properties.model();
        this.restClient = restClientBuilder
                .baseUrl(stripTrailingSlash(model.baseUrl()))
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(model.timeout())
                        .withReadTimeout(model.timeout())))
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userText, List<byte[]> pngImages) {
        for (int attempt = 0; ; attempt++) {
            try {
                return completeOnce(systemPrompt, userText, pngImages);
            } catch (HttpStatusCodeException | ResourceAccessException e) {
                if (attempt >= MAX_RETRIES || !isTransient(e)) {
                    throw e;
                }
                Duration wait = retryAfter(e).orElse(BACKOFF.get(attempt));
                log.warn("Model call failed ({}) - retry {}/{} in {}s",
                        describe(e), attempt + 1, MAX_RETRIES, wait.toSeconds());
                sleeper.accept(wait);
            }
        }
    }

    private String completeOnce(String systemPrompt, String userText, List<byte[]> pngImages) {
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userText));
        for (byte[] pngImage : pngImages) {
            String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngImage);
            userContent.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        Map<String, Object> request = Map.of(
                "model", model.name(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)));

        JsonNode response = restClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + model.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        JsonNode content = response == null ? null
                : response.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual()) {
            throw new IllegalStateException("Chat completion response has no choices[0].message.content");
        }
        return content.asText();
    }

    private static boolean isTransient(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        int status = ((HttpStatusCodeException) e).getStatusCode().value();
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    /** The provider's Retry-After (whole seconds), when present, sane and not absurdly long. */
    private static Optional<Duration> retryAfter(RuntimeException e) {
        if (!(e instanceof HttpStatusCodeException statusException) || statusException.getResponseHeaders() == null) {
            return Optional.empty();
        }
        String value = statusException.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            Duration wait = Duration.ofSeconds(Long.parseLong(value == null ? "" : value.strip()));
            return wait.isNegative() || wait.compareTo(MAX_RETRY_AFTER) > 0
                    ? Optional.empty() : Optional.of(wait);
        } catch (NumberFormatException notSeconds) {
            return Optional.empty();
        }
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
