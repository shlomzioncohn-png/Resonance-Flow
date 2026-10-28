package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/workspace/tasks/{taskId}/activity - mirrors BFF's TaskActivityDTO:
 * Chain's task history, merged newest first. No actor on any item - Chain
 * doesn't record one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskActivityDTO {

    private List<Item> items;
    private boolean statusHistoryUnavailable;
    private boolean dependencyHistoryUnavailable;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {
        /** Chain's event id. */
        private String id;
        /** STATUS_CHANGED, BLOCKED, UNBLOCKED, DEPENDENCY_ADDED or DEPENDENCY_REMOVED. */
        private String type;
        private Instant createdAt;
        private String fromStatus;
        private String toStatus;
        private Integer blockingCount;
        private String dependsOnTaskId;
        private String dependsOnTitle;
        /** MIND, USER or SYSTEM. */
        private String source;
    }
}
