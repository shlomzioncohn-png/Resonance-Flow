package com.rnd.bridge.media.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleChatClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleChatClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        MediaClassificationProperties properties = ClassificationTestSupport.properties(
                true, "secret-key", server.url("/v1beta/openai/").toString(),
                new MediaClassificationProperties.Documents(5, 2));
        client = new OpenAiCompatibleChatClient(RestClient.builder(), properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void postsOpenAiChatCompletionWithImageAndReturnsMessageContent() throws Exception {
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"category\\\":\\\"MEMO\\\"}\"}}]}"));

        String reply = client.complete("system prompt", "classify this", List.of(new byte[] {1, 2, 3}, new byte[] {4}));

        assertThat(reply).isEqualTo("{\"category\":\"MEMO\"}");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1beta/openai/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret-key");
        JsonNode body = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("temperature").asInt()).isZero();
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("system prompt");
        assertThat(body.at("/messages/1/content/0/text").asText()).isEqualTo("classify this");
        assertThat(body.at("/messages/1/content/1/image_url/url").asText()).isEqualTo("data:image/png;base64,AQID");
        assertThat(body.at("/messages/1/content/2/image_url/url").asText()).isEqualTo("data:image/png;base64,BA==");
    }

    @Test
    void textOnlyRequestHasNoImagePart() throws Exception {
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        client.complete("system", "text", List.of());

        JsonNode body = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.at("/messages/1/content").size()).isEqualTo(1);
    }

    @Test
    void errorStatusPropagates() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate limited\"}"));

        assertThatThrownBy(() -> client.complete("system", "text", List.of()))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);
    }

    @Test
    void responseWithoutContentIsRejected() {
        server.enqueue(jsonResponse("{\"choices\":[]}"));

        assertThatThrownBy(() -> client.complete("system", "text", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
