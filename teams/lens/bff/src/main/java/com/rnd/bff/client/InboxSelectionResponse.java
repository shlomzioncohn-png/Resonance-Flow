package com.rnd.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Inbox's answer to "which tasks should I do in N minutes?" - mirrors Inbox's
 * TaskSelectionResponseDto (GET /api/v1/tasks/selection, inbox-team PR #550).
 * selectedTasks is in suggested execution order and fits the window together.
 * usedFallback=true means nothing fit, so Inbox widened the window to the
 * shortest eligible task (reason says so).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InboxSelectionResponse {

    private List<SelectedTask> selectedTasks;
    private int totalEffortMinutes;
    private double totalValue;
    private int remainingMinutes;
    private boolean usedFallback;
    private String reason;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class SelectedTask {
        private String id;
        private String title;
        private String description;
        private int effortEstimateMinutes;
        /** Already formatted by Inbox, e.g. "12/10/2026". */
        private String deadline;
        /** 0-1, higher is better. */
        private double selectionScore;
    }
}
