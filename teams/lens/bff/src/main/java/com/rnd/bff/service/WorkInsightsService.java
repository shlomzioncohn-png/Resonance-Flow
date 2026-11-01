package com.rnd.bff.service;

import com.rnd.bff.client.InboxWorkInsightRecord;
import com.rnd.bff.client.WorkInsightsClient;
import com.rnd.bff.dto.InsightEvidenceDTO;
import com.rnd.bff.dto.WorkInsightDTO;
import com.rnd.bff.exception.InboxResponseException;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.function.Supplier;

/**
 * Employee "Work Insights" screen. Calls Inbox for real through WorkInsightsClient.
 *
 * The Gateway sends a mock userId ("emp_001") and never knows Inbox user ids,
 * so inbox.dev-actor-id (a seeded Inbox user UUID) replaces it here, same as
 * ManagerUsersService does. WorkInsightsClient is then called with the resolved
 * Inbox actor id.
 *
 * Timeout handling:
 * - First call of the day may take up to 90 seconds (AI processing)
 * - Subsequent calls within the same day are fast (cached in Inbox DB)
 * - ResourceAccessException with SocketTimeoutException is handled gracefully
 */
@Service
public class WorkInsightsService {

    private static final Logger logger = LoggerFactory.getLogger(WorkInsightsService.class);

    private final WorkInsightsClient workInsightsClient;
    private final ObjectMapper objectMapper;
    private final String devActorId;

    public WorkInsightsService(WorkInsightsClient workInsightsClient, ObjectMapper objectMapper,
                               @Value("${inbox.dev-actor-id:}") String devActorId) {
        this.workInsightsClient = workInsightsClient;
        this.objectMapper = objectMapper;
        this.devActorId = devActorId;
    }

    /**
     * Get today's work insight for the user.
     *
     * @param gatewayUserId the mock user id from the Gateway
     * @return WorkInsightDTO with status READY, NOT_ENOUGH_DATA, or NO_INSIGHT
     */
    public WorkInsightDTO getWorkInsight(String gatewayUserId) {
        String actorId = inboxActorId(gatewayUserId);
        logger.info("Fetching work insight from Inbox - actorId: {}", actorId);

        InboxWorkInsightRecord record = call(() -> workInsightsClient.getWorkInsight(actorId));
        return toWorkInsightDTO(record);
    }

    /**
     * Send feedback on a work insight.
     *
     * @param gatewayUserId the mock user id from the Gateway
     * @param insightId the id of the insight to provide feedback for
     * @param helpful whether the insight was helpful
     */
    public void sendInsightFeedback(String gatewayUserId, String insightId, Boolean helpful) {
        String actorId = inboxActorId(gatewayUserId);
        logger.info("Sending work insight feedback to Inbox - actorId: {}, insightId: {}, helpful: {}",
                actorId, insightId, helpful);

        call(() -> {
            workInsightsClient.sendInsightFeedback(actorId, insightId, helpful);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Shared plumbing
    // -------------------------------------------------------------------------

    /**
     * Wrapper for calling Inbox, handling errors consistently.
     * Transforms:
     * - SocketTimeoutException → ServiceUnavailableException (can retry)
     * - 401/400/404 → InboxResponseException (client error)
     * - 503 → ServiceUnavailableException (server error, can retry)
     * - Other → ServiceUnavailableException
     */
    private <T> T call(Supplier<T> operation) {
        try {
            T result = operation.get();
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
            // For work insights, we handle:
            // - 401: actor not found or invalid X-Actor-Id header
            // - 400: bad request (e.g., missing helpful field in feedback)
            // - 404: insight not found or not belonging to the user (INSIGHT_NOT_FOUND error)
            // - 503: AI unavailable (INSIGHT_AI_UNAVAILABLE error)
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.BAD_REQUEST
                    || status == HttpStatus.NOT_FOUND) {
                logger.warn("Inbox returned {}", status);
                throw new InboxResponseException(status, extractInboxMessage(e, status));
            }
            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                logger.warn("Inbox AI service unavailable");
                throw new InboxResponseException(status, extractInboxMessage(e, status));
            }
            logger.error("Inbox returned unexpected status {}", e.getStatusCode(), e);
            throw new ServiceUnavailableException("inbox", "Inbox returned " + e.getStatusCode(), e);

        } catch (RestClientException e) {
            logger.error("Unexpected error calling Inbox", e);
            throw new ServiceUnavailableException("inbox", "Unexpected error communicating with Inbox", e);
        }
    }

    /**
     * Inbox's ErrorResponseDto carries a human-readable "message" field.
     * Example: "INSIGHT_AI_UNAVAILABLE: AI service temporarily unavailable"
     */
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

    /**
     * Transform Inbox's snake_case response to camelCase WorkInsightDTO.
     */
    private WorkInsightDTO toWorkInsightDTO(InboxWorkInsightRecord record) {
        InsightEvidenceDTO evidence = null;
        if (record.getEvidence() != null) {
            evidence = InsightEvidenceDTO.builder()
                    .text(record.getEvidence().getText())
                    .achieved(record.getEvidence().getAchieved())
                    .total(record.getEvidence().getTotal())
                    .build();
        }

        return WorkInsightDTO.builder()
                .insightId(record.getInsightId())
                .status(record.getStatus())
                .periodDays(record.getPeriodDays())
                .insight(record.getInsight())
                .recommendation(record.getRecommendation())
                .evidence(evidence)
                .build();
    }

    /**
     * Resolve the Inbox actor id from the gateway's mock user id.
     * Currently, all requests resolve to the same seeded dev actor.
     */
    private String inboxActorId(String gatewayUserId) {
        if (devActorId != null && !devActorId.isBlank()) {
            return devActorId;
        }
        // Fallback — in production this wouldn't happen if auth is properly configured
        logger.warn("inbox.dev-actor-id not configured, using gatewayUserId: {}", gatewayUserId);
        return gatewayUserId;
    }
}
