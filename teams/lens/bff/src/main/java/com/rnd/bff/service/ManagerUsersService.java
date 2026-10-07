package com.rnd.bff.service;

import com.rnd.bff.client.InboxUserClient;
import com.rnd.bff.client.InboxUserPageResponse;
import com.rnd.bff.client.InboxUserRecord;
import com.rnd.bff.dto.UserDetailsDTO;
import com.rnd.bff.dto.UserListResponseDTO;
import com.rnd.bff.dto.UserRowDTO;
import com.rnd.bff.exception.InboxResponseException;
import com.rnd.bff.exception.SelfStatusChangeException;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Admin "Users" screen (task spec: manager-users-screen-task.md). Calls
 * Inbox for real through InboxUserClient — see that class's javadoc.
 *
 * Field allowlisting for edits happens twice by design: once in Gateway's
 * ManagerUsersController (so nothing outside displayName/firstName/lastName/
 * phone ever leaves the Gateway), and again here (so BFF doesn't depend on
 * Gateway having done it right — task spec §4: "גם אם ה-frontend לא מציג
 * את השדות האלה, ה-BFF לא מעביר ל-Inbox אף שדה מחוץ לרשימה המותרת").
 */
@Service
public class ManagerUsersService {

    private static final Logger logger = LoggerFactory.getLogger(ManagerUsersService.class);
    private static final Set<String> EDITABLE_FIELDS = Set.of("displayName", "firstName", "lastName", "phone");
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "SUSPENDED", "DISABLED");

    private final InboxUserClient inboxUserClient;
    private final ObjectMapper objectMapper;

    public ManagerUsersService(InboxUserClient inboxUserClient, ObjectMapper objectMapper) {
        this.inboxUserClient = inboxUserClient;
        this.objectMapper = objectMapper;
    }

    public UserListResponseDTO listUsers(String actorId, Integer page, Integer size, String sort) {
        logger.info("Fetching users from Inbox - actorId: {}, page: {}, size: {}, sort: {}",
                actorId, page, size, sort);

        InboxUserPageResponse response = call(() -> inboxUserClient.listUsers(actorId, page, size, sort));

        return UserListResponseDTO.builder()
                .items(response.getContent().stream().map(this::toUserRowDTO).toList())
                .page(response.getPage())
                .size(response.getSize())
                .totalElements(response.getTotalElements())
                .totalPages(response.getTotalPages())
                .build();
    }

    public UserDetailsDTO getUser(String actorId, String userId) {
        logger.info("Fetching user from Inbox - actorId: {}, userId: {}", actorId, userId);

        InboxUserRecord record = call(() -> inboxUserClient.getUser(actorId, userId));
        return toUserDetailsDTO(record);
    }

    public UserDetailsDTO updateUser(String actorId, String userId, Map<String, Object> fields) {
        Map<String, Object> allowed = new LinkedHashMap<>();
        for (String key : EDITABLE_FIELDS) {
            if (fields.containsKey(key)) {
                allowed.put(key, fields.get(key));
            }
        }

        logger.info("Updating user via Inbox - actorId: {}, userId: {}, fields: {}", actorId, userId, allowed.keySet());

        InboxUserRecord record = call(() -> inboxUserClient.updateUser(actorId, userId, allowed));
        return toUserDetailsDTO(record);
    }

    public UserDetailsDTO updateStatus(String actorId, String userId, String status) {
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new InboxResponseException(HttpStatus.BAD_REQUEST,
                    "status must be one of ACTIVE, SUSPENDED, DISABLED");
        }
        if (userId.equals(actorId)) {
            throw new SelfStatusChangeException("A manager cannot change their own status.");
        }

        logger.info("Updating user status via Inbox - actorId: {}, userId: {}, newStatus: {}", actorId, userId, status);

        InboxUserRecord record = call(() -> inboxUserClient.updateStatus(actorId, userId, status));
        return toUserDetailsDTO(record);
    }

    // -------------------------------------------------------------------------
    // Shared plumbing
    // -------------------------------------------------------------------------

    /**
     * Error mapping, task spec §5: 403/404/409/400 from Inbox pass through
     * as InboxResponseException (same status, Inbox's own message); timeout
     * or unreachable/5xx becomes ServiceUnavailableException (503).
     */
    private <T> T call(Supplier<T> inboxCall) {
        try {
            T result = inboxCall.get();
            if (result == null) {
                throw new ServiceUnavailableException("inbox", "Inbox returned an empty response");
            }
            return result;

        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("Inbox read timeout", e);
            } else {
                logger.error("Inbox unreachable", e);
            }
            throw new ServiceUnavailableException("inbox", "Inbox is currently unavailable", e);

        } catch (HttpStatusCodeException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND || status == HttpStatus.CONFLICT
                    || status == HttpStatus.BAD_REQUEST || status == HttpStatus.FORBIDDEN) {
                logger.warn("Inbox returned {}", status);
                throw new InboxResponseException(status, extractInboxMessage(e, status));
            }
            logger.error("Inbox returned unexpected status {}", e.getStatusCode(), e);
            throw new ServiceUnavailableException("inbox", "Inbox returned " + e.getStatusCode(), e);

        } catch (RestClientException e) {
            logger.error("Unexpected error calling Inbox", e);
            throw new ServiceUnavailableException("inbox", "Unexpected error communicating with Inbox", e);
        }
    }

    /** Inbox's ErrorResponseDto carries a human-readable "message" field (e.g. "User &lt;id&gt; not found"). */
    private String extractInboxMessage(HttpStatusCodeException e, HttpStatus fallbackStatus) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsByteArray());
            JsonNode message = body.get("message");
            if (message != null && message.isTextual()) {
                return message.asText();
            }
        } catch (Exception parseFailure) {
            // Fall through — Inbox's body wasn't the expected shape (or there was none).
        }
        return "Inbox request failed: " + fallbackStatus.getReasonPhrase();
    }

    private UserRowDTO toUserRowDTO(InboxUserRecord record) {
        return UserRowDTO.builder()
                .id(record.getId())
                .displayName(record.getDisplayName())
                .email(record.getEmail())
                .role(LensRoleMapper.toLensRole(record.getRole()))
                .status(record.getStatus())
                .lastLoginAt(record.getLastLoginAt())
                .build();
    }

    private UserDetailsDTO toUserDetailsDTO(InboxUserRecord record) {
        return UserDetailsDTO.detailsBuilder()
                .id(record.getId())
                .displayName(record.getDisplayName())
                .email(record.getEmail())
                .role(LensRoleMapper.toLensRole(record.getRole()))
                .status(record.getStatus())
                .lastLoginAt(record.getLastLoginAt())
                .firstName(record.getFirstName())
                .lastName(record.getLastName())
                .phone(record.getPhone())
                .username(record.getUsername())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
