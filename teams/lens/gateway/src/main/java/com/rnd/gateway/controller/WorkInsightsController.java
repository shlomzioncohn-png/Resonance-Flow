package com.rnd.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.gateway.dto.InsightFeedbackRequestDTO;
import com.rnd.gateway.dto.WorkInsightDTO;
import com.rnd.gateway.exception.GatewayTimeoutException;
import com.rnd.gateway.exception.ServiceUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;

/**
 * Employee "Work Insights" screen — routes GET/POST /api/work-insights** to BFF,
 * which calls Inbox for real (see com.rnd.bff.client.WorkInsightsClient's javadoc).
 *
 * Endpoints:
 * - GET /api/work-insights - Retrieve today's work insight
 * - POST /api/work-insights/{insightId}/feedback - Send feedback on an insight
 *
 * Identity flow (current: MOCK, Auth stage 2):
 *   - X-User-Id is MOCK_USER_ID for every request.
 *   - The Gateway never knows Inbox user ids — BFF maps the mock id to a
 *     real Inbox user (inbox.dev-actor-id) before calling Inbox.
 *
 * Timeouts:
 *   - First call of the day may take up to 90 seconds (AI processing).
 *   - Long timeout configured for this endpoint to allow AI response time.
 *   - Subsequent calls within the same day are fast (cached in Inbox DB).
 */
@RestController
@RequestMapping("/api/work-insights")
@Tag(name = "Work Insights", description = "Work insights for employees — proxies to BFF, which calls Inbox")
public class WorkInsightsController {

    private static final Logger logger = LoggerFactory.getLogger(WorkInsightsController.class);

    // TODO (Auth stage 2): the user id from the validated JWT
    private static final String MOCK_USER_ID = "emp_001";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${bff.service.url:http://localhost:8082}")
    private String bffServiceUrl;

    @Value("${bff.endpoints.work-insights:/work-insights}")
    private String bffWorkInsightsPath;

    @Value("${bff.read-timeout-work-insights-ms:120000}")
    private long bffWorkInsightsReadTimeoutMs;

    public WorkInsightsController(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Operation(
        summary = "Get today's work insight",
        description = "Returns today's AI-generated work insight for the current user. May take up to 90 seconds on first call of the day (AI processing)."
    )
    @GetMapping
    public ResponseEntity<WorkInsightDTO> getWorkInsight() {
        HttpHeaders headers = buildForwardingHeaders();
        logger.info("Work insight request - actorId: {}", headers.getFirst("X-User-Id"));

        String url = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path(bffWorkInsightsPath)
                .toUriString();

        return forward(url, HttpMethod.GET, headers, null, WorkInsightDTO.class);
    }

    @Operation(
        summary = "Send feedback on a work insight",
        description = "Submit feedback on whether a work insight was helpful. Returns 204 No Content on success."
    )
    @PostMapping("/{insightId}/feedback")
    public ResponseEntity<Void> sendInsightFeedback(
            @Parameter(description = "ID of the insight to provide feedback for") @PathVariable String insightId,
            @Valid @RequestBody InsightFeedbackRequestDTO body) {

        HttpHeaders headers = buildForwardingHeaders();
        logger.info("Work insight feedback request - actorId: {}, insightId: {}, helpful: {}",
                headers.getFirst("X-User-Id"), insightId, body.getHelpful());

        String url = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path(bffWorkInsightsPath + "/{insightId}/feedback")
                .buildAndExpand(insightId)
                .toUriString();

        return forwardWithoutBody(url, HttpMethod.POST, headers, body);
    }

    // -------------------------------------------------------------------------
    // Shared plumbing
    // -------------------------------------------------------------------------

    /** The X-User-Id header forwarded to BFF — currently mock, see this class's javadoc. */
    private HttpHeaders buildForwardingHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", MOCK_USER_ID);
        return headers;
    }

    private <T> ResponseEntity<T> forward(String url, HttpMethod method, HttpHeaders headers,
                                           Object body, Class<T> responseType) {
        try {
            ResponseEntity<T> bffResponse = restTemplate.exchange(
                    url, method, new HttpEntity<>(body, headers), responseType);

            T result = bffResponse.getBody();
            if (result == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            return ResponseEntity.status(bffResponse.getStatusCode()).body(result);

        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout - url: {}", url, e);
                throw new GatewayTimeoutException("bff", bffWorkInsightsReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable - url: {}", url, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} - url: {}", e.getStatusCode(), url);
            throw new ResponseStatusException(e.getStatusCode(), extractBffMessage(e));
        }
    }

    private ResponseEntity<Void> forwardWithoutBody(String url, HttpMethod method, HttpHeaders headers, Object body) {
        try {
            ResponseEntity<Void> bffResponse = restTemplate.exchange(
                    url, method, new HttpEntity<>(body, headers), Void.class);

            return ResponseEntity.status(bffResponse.getStatusCode()).build();

        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout - url: {}", url, e);
                throw new GatewayTimeoutException("bff", bffWorkInsightsReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable - url: {}", url, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} - url: {}", e.getStatusCode(), url);
            throw new ResponseStatusException(e.getStatusCode(), extractBffMessage(e));
        }
    }

    private String extractBffMessage(HttpStatusCodeException e) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsByteArray());
            JsonNode message = body.get("message");
            if (message != null && message.isTextual()) {
                return message.asText();
            }
        } catch (Exception parseFailure) {
            // Fall through to the generic message below — BFF's body wasn't the expected shape.
        }
        return "Request failed: " + e.getStatusCode();
    }
}
