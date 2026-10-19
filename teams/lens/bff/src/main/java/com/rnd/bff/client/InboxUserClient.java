package com.rnd.bff.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client for Inbox's real {@code /api/v1/users} endpoints (Inbox owns the
 * canonical `users` table — see CLAUDE.md §1). Unlike InboxClient/ChainClient/
 * MindClient, this makes actual HTTP calls: Inbox's UserController already
 * exists and is live (teams/inbox/src/main/java/com/rnd/inbox/user/UserController.java),
 * so there is a real downstream to call, not a mock to stand in for one.
 *
 * Inbox trusts the {@code X-Actor-Id} header as the caller's identity and
 * scopes/authorizes every call against that actor's own tenant (see Inbox
 * CLAUDE.md §9 "Known gap — actor identity is trusted as sent, not verified").
 * The actor id is forwarded here unchanged from whatever ManagerUsersController
 * received as X-User-Id — see that class's javadoc on the still-mock identity
 * (the task's "auth" dependency, which would make this a real per-manager id,
 * doesn't exist in this repo).
 */
@Service
public class InboxUserClient {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private final RestTemplate restTemplate;

    @Value("${inbox.service.url:http://inbox-service:8080}")
    private String inboxServiceUrl;

    public InboxUserClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * @param actorId the acting user's id, forwarded to Inbox as {@code X-Actor-Id}
     * @param page    0-based page index, or null to let Inbox default it
     * @param size    page size, or null to let Inbox default it
     * @param sort    e.g. "displayName,asc", or null for Inbox's default order
     */
    public InboxUserPageResponse listUsers(String actorId, Integer page, Integer size, String sort) {
        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path("/api/v1/users");
        if (page != null) {
            urlBuilder.queryParam("page", page);
        }
        if (size != null) {
            urlBuilder.queryParam("size", size);
        }
        if (sort != null) {
            urlBuilder.queryParam("sort", sort);
        }

        return restTemplate.exchange(
                urlBuilder.toUriString(), HttpMethod.GET, new HttpEntity<>(authHeaders(actorId)),
                InboxUserPageResponse.class).getBody();
    }

    /** @param body Inbox's UserCreateRequestDto fields (camelCase keys) — tenantId and an Inbox role, already resolved by the caller. */
    public InboxUserRecord createUser(String actorId, Map<String, Object> body) {
        String url = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl).path("/api/v1/users").toUriString();

        HttpHeaders headers = authHeaders(actorId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(toSnakeCase(body), headers), InboxUserRecord.class).getBody();
    }

    public InboxUserRecord getUser(String actorId, String userId) {
        String url = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path("/api/v1/users/{id}").buildAndExpand(userId).toUriString();

        return restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders(actorId)), InboxUserRecord.class).getBody();
    }

    /** @param fields only the keys actually sent by the caller — absent keys must stay absent, not null, to match Inbox's JsonNullable PATCH semantics. */
    public InboxUserRecord updateUser(String actorId, String userId, Map<String, Object> fields) {
        String url = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path("/api/v1/users/{id}").buildAndExpand(userId).toUriString();

        HttpHeaders headers = authHeaders(actorId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange(
                url, HttpMethod.PATCH, new HttpEntity<>(toSnakeCase(fields), headers), InboxUserRecord.class).getBody();
    }

    public InboxUserRecord updateStatus(String actorId, String userId, String status) {
        String url = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path("/api/v1/users/{id}/status").buildAndExpand(userId).toUriString();

        HttpHeaders headers = authHeaders(actorId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange(
                url, HttpMethod.PATCH, new HttpEntity<>(Map.of("status", status), headers),
                InboxUserRecord.class).getBody();
    }

    /**
     * Inbox reads JSON in snake_case (spring.jackson.property-naming-strategy),
     * so "displayName" must go out as "display_name" — a camelCase key would
     * just be ignored. Keeps the caller's key order and any explicit nulls.
     */
    private static Map<String, Object> toSnakeCase(Map<String, Object> fields) {
        Map<String, Object> snake = new LinkedHashMap<>();
        fields.forEach((key, value) -> snake.put(key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(), value));
        return snake;
    }

    private HttpHeaders authHeaders(String actorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ACTOR_HEADER, actorId);
        return headers;
    }
}
