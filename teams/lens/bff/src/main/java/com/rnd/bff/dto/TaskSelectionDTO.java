package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * "Which tasks should I do in N minutes?" - the tasks that fit the available
 * time together, in suggested order. Comes from Inbox's task selection
 * (GET /api/v1/tasks/selection). Mirrors
 * com.rnd.gateway.dto.TaskSelectionDTO field-for-field.
 *
 * Used by: GET /workspace/tasks/selection?availableMinutes=
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskSelectionDTO {

    /** The minutes asked for (1-480). */
    private Integer availableMinutes;

    /** In suggested execution order. Empty when nothing can be done. */
    private List<SelectedTaskDTO> selectedTasks;

    private Integer totalEffortMinutes;
    private Integer remainingMinutes;

    /**
     * True when no task fit the window, so the shortest workable task was
     * picked anyway - it takes longer than availableMinutes (reason says so).
     */
    private Boolean usedFallback;

    /** Human-readable note from Inbox, e.g. why the window was widened. May be null. */
    private String reason;
}
