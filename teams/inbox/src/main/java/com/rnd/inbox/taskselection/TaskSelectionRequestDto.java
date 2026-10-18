package com.rnd.inbox.taskselection;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public class TaskSelectionRequestDto {

    public static final int MAX_AVAILABLE_MINUTES = 480;

    @NotNull
    @Positive
    @Max(MAX_AVAILABLE_MINUTES)
    private Integer availableMinutes;

    private UUID userId;

    public Integer getAvailableMinutes() {
        return availableMinutes;
    }

    public void setAvailableMinutes(Integer availableMinutes) {
        this.availableMinutes = availableMinutes;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }
}
