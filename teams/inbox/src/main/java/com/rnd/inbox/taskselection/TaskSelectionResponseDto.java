package com.rnd.inbox.taskselection;

import java.util.List;

public final class TaskSelectionResponseDto {

    private final List<SelectedTaskDto> selectedTasks;
    private final int totalEffortMinutes;
    private final double totalValue;
    private final int remainingMinutes;
    private final boolean usedFallback;
    private final String reason;

    public TaskSelectionResponseDto(List<SelectedTaskDto> selectedTasks, int totalEffortMinutes, double totalValue,
                                     int remainingMinutes, boolean usedFallback, String reason) {
        this.selectedTasks = selectedTasks;
        this.totalEffortMinutes = totalEffortMinutes;
        this.totalValue = totalValue;
        this.remainingMinutes = remainingMinutes;
        this.usedFallback = usedFallback;
        this.reason = reason;
    }

    public List<SelectedTaskDto> getSelectedTasks() {
        return selectedTasks;
    }

    public int getTotalEffortMinutes() {
        return totalEffortMinutes;
    }

    public double getTotalValue() {
        return totalValue;
    }

    public int getRemainingMinutes() {
        return remainingMinutes;
    }

    public boolean isUsedFallback() {
        return usedFallback;
    }

    public String getReason() {
        return reason;
    }
}
