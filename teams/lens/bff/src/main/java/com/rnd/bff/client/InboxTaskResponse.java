package com.rnd.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * The JSON shape of one task as Inbox's Task API returns it — mirrors
 * Inbox's TaskResponseDto (teams/inbox, branch inbox-team). Only used inside
 * InboxClient, which translates it to TaskDTO (the BFF's frontend-facing
 * contract) — same split as InboxTaskRecord for the mock.
 *
 * Inbox sends snake_case field names (project_id, owner_id, ...), enums as
 * UPPERCASE names and ids as UUID strings; enums and ids are kept as plain
 * strings here. Unknown fields are ignored, so Inbox adding a field never
 * breaks the BFF.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InboxTaskResponse {

    private String id;
    private TenantSummary tenant;
    private String projectId;
    private String title;
    private String description;
    private String sourceType;
    private String sourceRef;
    private String tier;
    private String roleContext;
    private Integer urgency;
    private Integer effortEstimateMinutes;
    private Instant deadline;
    private String status;
    private Boolean available;
    private Double priorityScore;
    private Instant snoozedUntil;
    private String extractionProvenance;
    private Double confidence;
    private String ownerId;
    private Double effortEstimateConfidence;
    private String suggestedOwnerUserId;
    private Double suggestedOwnerConfidence;
    private String createdById;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Map<String, Object> metadata;

    /** Inbox nests the tenant as { id, name, slug } instead of a bare tenantId. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TenantSummary {
        private String id;
        private String name;
        private String slug;
    }
}
