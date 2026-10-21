package com.rnd.inbox.task.dto;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.rnd.inbox.shared.json.DisplayDateFormat;
import com.rnd.inbox.task.assignment.AssigneeType;
import com.rnd.inbox.task.assignment.AssignmentRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public final class AssignmentResponseDto {

    private final AssigneeType assigneeType;
    private final UUID assigneeId;
    private final AssignmentRole assignmentRole;
    @JsonSerialize(using = DisplayDateFormat.DateTimeSerializer.class)
    @Schema(type = "string", example = "01/10/2026 08:12")
    private final Instant assignedAt;
    private final UUID assignedById;

    public AssignmentResponseDto(AssigneeType assigneeType, UUID assigneeId, AssignmentRole assignmentRole,
                                 Instant assignedAt, UUID assignedById) {
        this.assigneeType = assigneeType;
        this.assigneeId = assigneeId;
        this.assignmentRole = assignmentRole;
        this.assignedAt = assignedAt;
        this.assignedById = assignedById;
    }

    public AssigneeType getAssigneeType() {
        return assigneeType;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public AssignmentRole getAssignmentRole() {
        return assignmentRole;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public UUID getAssignedById() {
        return assignedById;
    }
}
