package com.rnd.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.gateway.dto.ManagerKpisDTO;
import com.rnd.gateway.exception.GatewayTimeoutException;
import com.rnd.gateway.exception.ServiceUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.util.Optional;

/**
 * Manager Dashboard (/manager) - routes GET /api/manager/dashboard/kpis to BFF.
 *
 * Identity is still mocked (Auth stage 2), same as ManagerUsersController:
 * X-User-Id is mgr_001 and BFF maps it to a real Inbox user. The tenant is
 * never forwarded - Inbox scopes the counts to the acting user's own tenant.
 */
@RestController
@RequestMapping("/api/manager/dashboard")
@Tag(name = "Manager Dashboard", description = "Manager Dashboard data — forwards to BFF")
public class ManagerDashboardController {

    private static final Logger logger = LoggerFactory.getLogger(ManagerDashboardController.class);

    // TODO (Auth stage 2): the user id and role from the validated JWT
    private static final String MOCK_USER_ID = "mgr_001";
    private static final String MOCK_USER_ROLE = "MANAGER";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${bff.service.url:http://localhost:8082}")
    private String bffServiceUrl;

    @Value("${bff.read-timeout-ms:10000}")
    private long bffReadTimeoutMs;

    public ManagerDashboardController(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "KPI tiles",
            description = "In progress, blocked and delayed task counts for the manager's tenant; avgBlockedDays is null until Chain provides it.")
    @GetMapping("/kpis")
    public ResponseEntity<ManagerKpisDTO> getKpis(
            @Parameter(description = "7d, 30d or 90d - accepted, not applied yet")
            @RequestParam(required = false) String period,
            @Parameter(description = "Inbox project id; omit for the whole tenant")
            @RequestParam(required = false) String project) {

        logger.info("Manager KPIs request - period: {}, project: {}", period, project);
        String url = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path("/manager/dashboard/kpis")
                .queryParamIfPresent("period", Optional.ofNullable(period))
                .queryParamIfPresent("project", Optional.ofNullable(project))
                .encode()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", MOCK_USER_ID);
        headers.set("X-User-Role", MOCK_USER_ROLE);

        try {
            ManagerKpisDTO kpis = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), ManagerKpisDTO.class).getBody();
            if (kpis == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            return ResponseEntity.ok(kpis);

        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout - url: {}", url, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable - url: {}", url, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            // Keep BFF's status and message (e.g. 400 "period must be one of 7d, 30d, 90d").
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
