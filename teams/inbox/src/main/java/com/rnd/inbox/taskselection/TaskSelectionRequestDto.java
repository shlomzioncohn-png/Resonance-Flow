package com.rnd.inbox.taskselection;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class TaskSelectionRequestDto {

    @NotNull
    @Positive
    private Integer availableMinutes;

    public Integer getAvailableMinutes() {
        return availableMinutes;
    }

    public void setAvailableMinutes(Integer availableMinutes) {
        this.availableMinutes = availableMinutes;
    }
}
