package com.rnd.bridge.media.classification;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link ChatCompletionClient} over the OpenAI chat-completions wire format
 * ({@code POST {base-url}/chat/completions}), which Gemini, DeepSeek and Groq all expose - so the
 * provider is chosen purely by bridge.media.classification.model.*. temperature 0 keeps the same
 * input classifying the same way across runs.
 * <p>
 * Deliberately no response_format parameter: support for it differs between providers, and
 * {@link ModelMediaClassifier} already tolerates a reply wrapped in prose or code fences.
 */
@Component
public class OpenAiCompatibleChatClient implements ChatCompletionClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final MediaClassificationProperties.Model model;

    public OpenAiCompatibleChatClient(RestClient.Builder restClientBuilder, MediaClassificationProperties properties) {
        this.model = properties.model();
        this.restClient = restClientBuilder
                .baseUrl(stripTrailingSlash(model.baseUrl()))
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(model.timeout())
                        .withReadTimeout(model.timeout())))
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userText, byte[] pngImage) {
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userText));
        if (pngImage != null) {
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

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
