package com.rnd.bridge.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.ai.AiProperties;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleProvider provider;
    private final List<Duration> sleeps = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = provider("secret-key");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void postsOpenAiChatCompletionWithImageAndReturnsMessageContentAndUsage() throws Exception {
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"category\\\":\\\"MEMO\\\"}\"}}],"
                + "\"usage\":{\"prompt_tokens\":812,\"completion_tokens\":64}}"));

        LlmResponse reply = provider.complete("test-model",
                new LlmRequest("system prompt", "classify this", List.of(new byte[] {1, 2, 3}, new byte[] {4})));

        assertThat(reply.text()).isEqualTo("{\"category\":\"MEMO\"}");
        assertThat(reply.model()).isEqualTo("test-model");
        assertThat(reply.promptTokens()).isEqualTo(812);
        assertThat(reply.completionTokens()).isEqualTo(64);
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
    void missingUsageMeansUnknownTokensNotZero() {
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        LlmResponse reply = provider.complete("m", LlmRequest.text("system", "text"));

        assertThat(reply.promptTokens()).isNull();
        assertThat(reply.completionTokens()).isNull();
    }

    @Test
    void modelSettingsAreSentOnlyWhenSet() throws Exception {
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        provider.complete("m", LlmRequest.text("system", "text").withModelSettings(512, "none"));
        provider.complete("m", LlmRequest.text("system", "text"));

        JsonNode withSettings = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(withSettings.get("max_tokens").asInt()).isEqualTo(512);
        assertThat(withSettings.get("reasoning_effort").asText()).isEqualTo("none");
        JsonNode without = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(without.has("max_tokens")).isFalse();
        assertThat(without.has("reasoning_effort")).isFalse();
    }

    @Test
    void spentDailyQuotaIsNotRetriedAndLaterCallsFailWithoutARequest() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody(
                "[{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota. limit: 20. "
                        + "Please retry in 18h19m34.906s.\",\"status\":\"RESOURCE_EXHAUSTED\"}}]"));

        assertThatThrownBy(() -> provider.complete("m", LlmRequest.text("system", "text")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).contains("Quota", "spent until");
                });
        assertThatThrownBy(() -> provider.complete("m", LlmRequest.text("system", "text")))
                .isInstanceOf(LlmException.class).hasMessageContaining("not sent");
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(sleeps).isEmpty();

        clock.advance(Duration.ofHours(19));
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        assertThat(provider.complete("m", LlmRequest.text("system", "text")).text()).isEqualTo("ok");
    }

    @Test
    void shortRetryDelayInTheBodyIsWaitedOn() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"Please retry in 21s.\"}}"));
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        provider.complete("m", LlmRequest.text("system", "text"));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(21));
    }

    @Test
    void groqStyleWaitInTheBodyIsUnderstoodToo() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody(
                "{\"error\":{\"message\":\"Rate limit reached. Please try again in 7.5s.\"}}"));
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        provider.complete("m", LlmRequest.text("system", "text"));

        assertThat(sleeps).containsExactly(Duration.ofMillis(7500));
    }

    @Test
    void replyCutOffAtMaxTokensFailsAndIsNotRetryable() {
        server.enqueue(jsonResponse("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"cat\"}}]}"));

        assertThatThrownBy(() -> provider.complete("m", LlmRequest.text("system", "text").withModelSettings(16, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("cut off at max_tokens=16");
                });
    }

    @Test
    void replyWithheldBySafetyFilterFailsAndIsNotRetryable() {
        server.enqueue(jsonResponse("{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"\"}}]}"));

        assertThatThrownBy(() -> provider.complete("m", LlmRequest.text("system", "text")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void textOnlyRequestHasNoImagePart() throws Exception {
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        provider.complete("m", LlmRequest.text("system", "text"));

        JsonNode body = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.at("/messages/1/content").size()).isEqualTo(1);
    }

    @Test
    void transientOverloadIsRetriedThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":\"high demand\"}"));
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        assertThat(provider.complete("m", LlmRequest.text("system", "text")).text()).isEqualTo("ok");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void retriesRunOutAfterTwoRetriesAndTheFailureIsRetryable() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate limited\"}"));
        }

        assertThatThrownBy(() -> provider.complete("m", LlmRequest.text("system", "text")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @Test
    void providersRetryAfterIsHonoured() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "7"));
        server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        provider.complete("m", LlmRequest.text("system", "text"));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void nonTransientErrorFailsAtOnceAndIsNotRetryable() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));

        assertThatThrownBy(() -> provider.complete("m", LlmRequest.text("system", "text")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void responseWithoutContentIsRejected() {
        server.enqueue(jsonResponse("{\"choices\":[]}"));

        assertThatThrownBy(() -> provider.complete("m", LlmRequest.text("system", "text")))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void withoutAKeyNothingIsSent() {
        OpenAiCompatibleProvider keyless = provider("");

        assertThat(keyless.configured()).isFalse();
        assertThatThrownBy(() -> keyless.complete("m", LlmRequest.text("system", "text")))
                .isInstanceOf(LlmException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    private OpenAiCompatibleProvider provider(String apiKey) {
        AiProperties.Provider config = new AiProperties.Provider(OpenAiCompatibleProvider.TYPE,
                server.url("/v1beta/openai/").toString(), apiKey, Duration.ofSeconds(5));
        return new OpenAiCompatibleProvider("test", config, RestClient.builder(), sleeps::add, clock);
    }

    /** A clock tests can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
