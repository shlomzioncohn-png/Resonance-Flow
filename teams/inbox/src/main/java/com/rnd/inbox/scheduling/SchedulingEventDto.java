package com.rnd.inbox.scheduling;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class SchedulingEventDto {

    @ValidUuid
    private String eventId;

    @NotBlank
    @ValidUuid
    private String taskId;

    @NotNull
    @JsonProperty("is_available")
    private Boolean available;

    @DecimalMin("1.0")
    @DecimalMax("10.0")
    private Double priorityScore;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public Double getPriorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(Double priorityScore) {
        this.priorityScore = priorityScore;
    }
}
