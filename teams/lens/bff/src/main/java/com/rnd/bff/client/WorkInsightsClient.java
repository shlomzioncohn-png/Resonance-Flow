package com.rnd.bff.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Client for Inbox's {@code /api/v1/work-insights} endpoints (real HTTP calls to Inbox service).
 *
 * Inbox trusts the {@code X-Actor-Id} header as the caller's identity and
 * scopes every call against that actor's own tenant.
 * The actor id is forwarded here from the Gateway as X-User-Id.
 *
 * Endpoints:
 * - GET /api/v1/work-insights - Get today's work insight
 * - POST /api/v1/work-insights/{insightId}/feedback - Send feedback on an insight
 *
 * Responses:
 * - Work Insights are returned in snake_case (Inbox's spring.jackson.property-naming-strategy)
 * - WorkInsightsService transforms to camelCase for the Frontend
 *
 * Long timeouts are used because:
 * - First call of the day: may take up to 90 seconds (AI processing to generate insight)
 * - Subsequent calls: fast (cached in Inbox DB)
 * - Must handle the AI latency gracefully
 */
@Service
public class WorkInsightsClient {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private final RestTemplate restTemplate;

    @Value("${inbox.service.url:http://inbox-service:8080}")
    private String inboxServiceUrl;

    public WorkInsightsClient(
            @Value("${inbox.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${inbox.read-timeout-work-insights-ms:120000}") long readTimeoutMs) {
        // JDK HttpClient with custom timeouts specifically for work-insights
        // (longer read timeout to allow AI processing on first call of the day)
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(requestFactory);
    }

    /**
     * Get today's work insight for the acting user.
     *
     * @param actorId the acting user's id, forwarded to Inbox as {@code X-Actor-Id}
     * @return InboxWorkInsightRecord with status READY, NOT_ENOUGH_DATA, or NO_INSIGHT
     */
    public InboxWorkInsightRecord getWorkInsight(String actorId) {
        String url = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path("/api/v1/work-insights")
                .toUriString();

        return restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders(actorId)),
                InboxWorkInsightRecord.class).getBody();
    }

    /**
     * Send feedback on a work insight.
     *
     * @param actorId the acting user's id, forwarded to Inbox as {@code X-Actor-Id}
     * @param insightId the id of the insight to provide feedback for
     * @param helpful whether the insight was helpful
     */
    public void sendInsightFeedback(String actorId, String insightId, Boolean helpful) {
        String url = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path("/api/v1/work-insights/{insightId}/feedback")
                .buildAndExpand(insightId)
                .toUriString();

        HttpHeaders headers = authHeaders(actorId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("helpful", helpful);

        restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    private HttpHeaders authHeaders(String actorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ACTOR_HEADER, actorId);
        return headers;
    }
}
