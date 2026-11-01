package com.rnd.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.gateway.exception.GatewayTimeoutException;
import com.rnd.gateway.exception.ServiceUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;

/**
 * Manager flow-health page (read-only) - routes GET /api/manager/flow-health to BFF.
 * The body is passed through untouched (BFF owns the shape); identity is mocked like
 * ManagerDashboardController until Auth stage 2.
 */
@RestController
@RequestMapping("/api/manager/flow-health")
@Tag(name = "Manager Flow Health", description = "Flow-health flags (recommendation only) — forwards to BFF")
public class FlowHealthController {

    private static final Logger logger = LoggerFactory.getLogger(FlowHealthController.class);

    // TODO (Auth stage 2): the user id and role from the validated JWT
    private static final String MOCK_USER_ID = "mgr_001";
    private static final String MOCK_USER_ROLE = "MANAGER";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${bff.service.url:http://localhost:8082}")
    private String bffServiceUrl;

    @Value("${bff.read-timeout-ms:10000}")
    private long bffReadTimeoutMs;

    public FlowHealthController(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Flow-health flags",
            description = "Patterns over time with task names; a recommendation only. 503 if Chain or Inbox is unavailable.")
    @GetMapping
    public ResponseEntity<JsonNode> getFlowHealth() {
        String url = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path("/manager/flow-health")
                .encode()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", MOCK_USER_ID);
        headers.set("X-User-Role", MOCK_USER_ROLE);

        try {
            JsonNode body = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            if (body == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            return ResponseEntity.ok(body);

        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout - url: {}", url, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable - url: {}", url, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} - url: {}", e.getStatusCode(), url);
            throw new ResponseStatusException(e.getStatusCode(), bffMessage(e));
        }
    }

    private String bffMessage(HttpStatusCodeException e) {
        try {
            JsonNode message = objectMapper.readTree(e.getResponseBodyAsByteArray()).get("message");
            if (message != null && message.isTextual()) {
                return message.asText();
            }
        } catch (Exception parseFailure) {
            // Not BFF's error shape - fall through to the generic message.
        }
        return "Request failed: " + e.getStatusCode();
    }
}
