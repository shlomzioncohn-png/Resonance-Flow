package com.rnd.inbox.project.dto;

import com.rnd.inbox.project.ProjectStatus;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import com.rnd.inbox.validation.OptionalNotBlank;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDate;

public class ProjectUpdateRequestDto {

    @OptionalNotBlank
    @Size(max = 255)
    private String name;

    private JsonNullable<String> description = JsonNullable.undefined();

    @ValidUuid
    private JsonNullable<String> ownerId = JsonNullable.undefined();

    @ValidUuid
    private JsonNullable<String> teamId = JsonNullable.undefined();

    @OptionalNotBlank
    @ValidEnum(enumClass = ProjectStatus.class)
    private String status;

    private JsonNullable<LocalDate> startDate = JsonNullable.undefined();

    private JsonNullable<LocalDate> targetDate = JsonNullable.undefined();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNullable<String> getDescription() {
        return description;
    }

    public void setDescription(JsonNullable<String> description) {
        this.description = description;
    }

    public JsonNullable<String> getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(JsonNullable<String> ownerId) {
        this.ownerId = ownerId;
    }

    public JsonNullable<String> getTeamId() {
        return teamId;
    }

    public void setTeamId(JsonNullable<String> teamId) {
        this.teamId = teamId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNullable<LocalDate> getStartDate() {
        return startDate;
    }

    public void setStartDate(JsonNullable<LocalDate> startDate) {
        this.startDate = startDate;
    }

    public JsonNullable<LocalDate> getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(JsonNullable<LocalDate> targetDate) {
        this.targetDate = targetDate;
    }
}
