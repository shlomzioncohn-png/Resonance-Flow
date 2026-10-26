package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * GET /workspace/tasks/{taskId}/activity - a task's history from Chain, both
 * histories merged into one list, newest first. Read-only.
 *
 * If one of Chain's two histories couldn't be loaded, the other's items are
 * still here and the matching *Unavailable flag is true, so the page can say
 * what's missing instead of failing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskActivityDTO {

    private List<Item> items;

    @Schema(description = "true when Chain's status/blocking history couldn't be loaded")
    private boolean statusHistoryUnavailable;

    @Schema(description = "true when Chain's dependency history couldn't be loaded")
    private boolean dependencyHistoryUnavailable;

    /**
     * One event. Only the fields for its type are sent. There is deliberately
     * no actor: Chain doesn't record who made a change.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {

        @Schema(description = "Chain's event id")
        private String id;

        @Schema(description = "STATUS_CHANGED, BLOCKED, UNBLOCKED, DEPENDENCY_ADDED or DEPENDENCY_REMOVED")
        private String type;

        private Instant createdAt;

        @Schema(description = "STATUS_CHANGED only: Chain status PENDING / ACTIVE / DONE")
        private String fromStatus;

        @Schema(description = "STATUS_CHANGED only")
        private String toStatus;

        @Schema(description = "BLOCKED / UNBLOCKED only: how many tasks block this one after the change")
        private Integer blockingCount;

        @Schema(description = "DEPENDENCY_* only")
        private String dependsOnTaskId;

        @Schema(description = "DEPENDENCY_* only: the title of dependsOnTaskId, from Inbox")
        private String dependsOnTitle;

        @Schema(description = "DEPENDENCY_* only: MIND, USER or SYSTEM")
        private String source;
    }
}
