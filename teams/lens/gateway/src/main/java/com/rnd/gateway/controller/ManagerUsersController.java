package com.rnd.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.gateway.dto.UserDetailsDTO;
import com.rnd.gateway.dto.UserListResponseDTO;
import com.rnd.gateway.dto.UserStatusUpdateRequestDTO;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Admin "Users" screen — MANAGER-only (task spec: manager-users-screen-task.md).
 * Routes GET/PATCH /api/manager/users/** to BFF, which calls Inbox for real
 * (see com.rnd.bff.client.InboxUserClient's javadoc).
 *
 * Identity flow (current: MOCK, Auth stage 2 — same as WorkspaceController):
 *   - X-User-Id is MOCK_USER_ID and X-User-Role is "MANAGER" for every
 *     request. Roles aren't checked yet either: like every /api/** route,
 *     this one only needs a valid token when app.auth.enabled=true.
 *   - The Gateway never knows Inbox user ids — BFF maps the mock id to a
 *     real Inbox user (inbox.dev-actor-id) before calling Inbox; see
 *     com.rnd.bff.service.ManagerUsersService.
 */
@RestController
@RequestMapping("/api/manager/users")
@Tag(name = "Manager Users", description = "User management for managers — proxies to BFF, which calls Inbox")
public class ManagerUsersController {

    private static final Logger logger = LoggerFactory.getLogger(ManagerUsersController.class);

    // TODO (Auth stage 2): the user id and role from the validated JWT
    private static final String MOCK_USER_ID = "mgr_001";
    private static final String MOCK_USER_ROLE = "MANAGER";

    /** Defense in depth: even if the Frontend sent more, only these ever leave the Gateway. */
    private static final Set<String> EDITABLE_FIELDS = Set.of("displayName", "firstName", "lastName", "phone");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${bff.service.url:http://localhost:8082}")
    private String bffServiceUrl;

    @Value("${bff.endpoints.manager-users:/manager/users}")
    private String bffUsersPath;

    @Value("${bff.read-timeout-ms:10000}")
    private long bffReadTimeoutMs;

    public ManagerUsersController(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "List users", description = "Paginated users list, MANAGER only.")
    @GetMapping
    public ResponseEntity<UserListResponseDTO> listUsers(
            @Parameter(description = "0-based page index") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, clamped to 1-100") @RequestParam(required = false) Integer size,
            @Parameter(description = "e.g. displayName,asc") @RequestParam(required = false) String sort) {

        if (size != null && size > 100) {
            size = 100;
        }

        HttpHeaders headers = buildForwardingHeaders();
        logger.info("Manager users list request - actorId: {}, page: {}, size: {}, sort: {}",
                headers.getFirst("X-User-Id"), page, size, sort);

        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(bffServiceUrl).path(bffUsersPath);
        if (page != null) {
            urlBuilder.queryParam("page", page);
        }
        if (size != null) {
            urlBuilder.queryParam("size", size);
        }
        if (sort != null) {
            urlBuilder.queryParam("sort", sort);
        }

        return forward(urlBuilder.toUriString(), HttpMethod.GET, headers, null, UserListResponseDTO.class);
    }

    @Operation(summary = "Get user details", description = "Single user, MANAGER only.")
    @GetMapping("/{id}")
    public ResponseEntity<UserDetailsDTO> getUser(@PathVariable String id) {
        HttpHeaders headers = buildForwardingHeaders();
        logger.info("Manager user details request - actorId: {}, userId: {}", headers.getFirst("X-User-Id"), id);

        String url = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path(bffUsersPath + "/{id}").buildAndExpand(id).toUriString();

        return forward(url, HttpMethod.GET, headers, null, UserDetailsDTO.class);
    }

    @Operation(summary = "Edit a user", description = "Only displayName/firstName/lastName/phone; everything else is dropped before it leaves the Gateway.")
    @PatchMapping("/{id}")
    public ResponseEntity<UserDetailsDTO> updateUser(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        Map<String, Object> allowed = new LinkedHashMap<>();
        for (String key : EDITABLE_FIELDS) {
            if (body.containsKey(key)) {
                allowed.put(key, body.get(key));
            }
        }
        validateEditFields(allowed);

        HttpHeaders headers = buildForwardingHeaders();
        logger.info("Manager user edit request - actorId: {}, userId: {}, fields: {}",
                headers.getFirst("X-User-Id"), id, allowed.keySet());

        String url = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path(bffUsersPath + "/{id}").buildAndExpand(id).toUriString();

        return forward(url, HttpMethod.PATCH, headers, allowed, UserDetailsDTO.class);
    }

    @Operation(summary = "Change a user's status", description = "ACTIVE | SUSPENDED | DISABLED only — PENDING isn't offered from this screen.")
    @PatchMapping("/{id}/status")
    public ResponseEntity<UserDetailsDTO> updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UserStatusUpdateRequestDTO body) {

        HttpHeaders headers = buildForwardingHeaders();
        logger.info("Manager user status change request - actorId: {}, userId: {}, newStatus: {}",
                headers.getFirst("X-User-Id"), id, body.getStatus());

        String url = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path(bffUsersPath + "/{id}/status").buildAndExpand(id).toUriString();

        return forward(url, HttpMethod.PATCH, headers, Map.of("status", body.getStatus()), UserDetailsDTO.class);
    }

    // -------------------------------------------------------------------------
    // Shared plumbing
    // -------------------------------------------------------------------------

    /**
     * displayName: non-blank, <=255 if present. firstName/lastName: <=100 if
     * present and non-null (null clears, per Inbox's JsonNullable contract —
     * see com.rnd.inbox.user.UserUpdateRequestDto). phone: <=30 if present
     * and non-null.
     */
    private void validateEditFields(Map<String, Object> fields) {
        if (fields.containsKey("displayName")) {
            Object value = fields.get("displayName");
            if (!(value instanceof String s) || s.isBlank() || s.length() > 255) {
                throw badRequest("displayName must be a non-blank string of at most 255 characters");
            }
        }
        requireStringOrNullWithinLength(fields, "firstName", 100);
        requireStringOrNullWithinLength(fields, "lastName", 100);
        requireStringOrNullWithinLength(fields, "phone", 30);
    }

    private void requireStringOrNullWithinLength(Map<String, Object> fields, String key, int maxLength) {
        if (!fields.containsKey(key)) {
            return;
        }
        Object value = fields.get(key);
        if (value != null && (!(value instanceof String s) || s.length() > maxLength)) {
            throw badRequest(key + " must be a string of at most " + maxLength + " characters, or null");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    /** The X-User-Id/X-User-Role headers forwarded to BFF — both mock, see this class's javadoc. */
    private HttpHeaders buildForwardingHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", MOCK_USER_ID);
        headers.set("X-User-Role", MOCK_USER_ROLE);
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
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable - url: {}", url, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            // Preserve BFF's status AND message (e.g. 409 "can't change your own status") —
            // GlobalExceptionHandler.handleResponseStatusException forwards ex.getReason() as-is.
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
