package com.rnd.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET /api/manager/dashboard/projects - mirrors BFF's ProjectTaskCountsDTO:
 * one project's tasks counted by state, for the Dashboard's charts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectTaskCountsDTO {

    @Schema(description = "Inbox project id; null for the tasks that have no project", nullable = true)
    private String projectId;

    @Schema(description = "Project name; \"No project\" for the tasks that have none", example = "Checkout Revamp")
    private String project;

    @Schema(description = "Status IN_PROGRESS", example = "6")
    private int inProgress;

    @Schema(description = "Status SNOOZED", example = "1")
    private int snoozed;

    @Schema(description = "Not started yet: NEW, IN_REVIEW or APPROVED", example = "4")
    private int open;

    @Schema(description = "Status DONE", example = "13")
    private int done;

    @Schema(description = "Not done and past the deadline - part of the counts above, not extra", example = "1")
    private int overdue;
}
