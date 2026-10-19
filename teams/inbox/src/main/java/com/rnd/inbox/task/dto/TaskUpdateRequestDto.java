package com.rnd.inbox.task.dto;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import com.rnd.inbox.validation.OptionalNotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.Instant;
import java.util.List;

public class TaskUpdateRequestDto {

    @OptionalNotBlank
    private String title;

    private JsonNullable<String> description = JsonNullable.undefined();

    @ValidUuid
    private JsonNullable<String> projectId = JsonNullable.undefined();

    @ValidUuid
    private JsonNullable<String> ownerId = JsonNullable.undefined();

    @Min(1)
    @Max(5)
    private JsonNullable<Short> urgency = JsonNullable.undefined();

    @Min(1)
    private JsonNullable<Integer> effortEstimateMinutes = JsonNullable.undefined();

    private JsonNullable<Instant> deadline = JsonNullable.undefined();

    private JsonNullable<List<@NotNull @Valid TaskAssigneeRequestDto>> assignees = JsonNullable.undefined();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public JsonNullable<String> getDescription() {
        return description;
    }

    public void setDescription(JsonNullable<String> description) {
        this.description = description;
    }

    public JsonNullable<String> getProjectId() {
        return projectId;
    }

    public void setProjectId(JsonNullable<String> projectId) {
        this.projectId = projectId;
    }

    public JsonNullable<String> getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(JsonNullable<String> ownerId) {
        this.ownerId = ownerId;
    }

    public JsonNullable<Short> getUrgency() {
        return urgency;
    }

    public void setUrgency(JsonNullable<Short> urgency) {
        this.urgency = urgency;
    }

    public JsonNullable<Integer> getEffortEstimateMinutes() {
        return effortEstimateMinutes;
    }

    public void setEffortEstimateMinutes(JsonNullable<Integer> effortEstimateMinutes) {
        this.effortEstimateMinutes = effortEstimateMinutes;
    }

    public JsonNullable<Instant> getDeadline() {
        return deadline;
    }

    public void setDeadline(JsonNullable<Instant> deadline) {
        this.deadline = deadline;
    }

    public JsonNullable<List<TaskAssigneeRequestDto>> getAssignees() {
        return assignees;
    }

    public void setAssignees(JsonNullable<List<TaskAssigneeRequestDto>> assignees) {
        this.assignees = assignees;
    }
}
