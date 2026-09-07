package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

/**
 * TaskRecommendation DTO - Response from BFF to Frontend
 *
 * Flow:
 * Frontend → Gateway → BFF (processes request with Mind + Chain)
 *            Gateway ← BFF (returns recommended task + reasoning)
 *            Frontend
 *
 * Contains:
 * - The recommended task
 * - Why it was selected (explanation)
 * - Context flags (fitsInTime, isHighPriority, hasNoDependencies)
 * - Alternative tasks (if recommendation cannot be done)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRecommendationDTO {

    /**
     * The best-fit task for the available time
     * Contains full task details (title, priority, effort, owner, etc)
     */
    private TaskDTO task;

    /**
     * Explanation for why this task was recommended
     * Example: "Fits in your 45 minutes, High importance, No blocking dependencies"
     */
    private String explanation;

    /**
     * Does this task fit in the available time?
     * Example: true (task effort: 35 min, available: 45 min)
     */
    private Boolean fitsInTime;

    /**
     * Is this task high priority?
     * Example: true (HIGH or MEDIUM priority tasks)
     */
    private Boolean isHighPriority;

    /**
     * Does this task have no blocking dependencies?
     * Example: true (all dependencies are complete or no dependencies)
     */
    private Boolean hasNoDependencies;

    /**
     * Confidence score for this recommendation
     * Range: 0-100 (from Mind scoring)
     * Higher = better recommendation
     */
    private Integer confidenceScore;

    /**
     * Alternative tasks if employee can't do the recommended one
     * Sorted by priority/feasibility
     * Null or empty = no alternatives
     */
    private List<TaskDTO> alternatives;

    /**
     * Simple constructor for basic recommendation
     */
    public TaskRecommendationDTO(TaskDTO task, String explanation) {
        this.task = task;
        this.explanation = explanation;
    }

    /**
     * Constructor with all context flags
     */
    public TaskRecommendationDTO(TaskDTO task, String explanation,
                                 Boolean fitsInTime, Boolean isHighPriority,
                                 Boolean hasNoDependencies) {
        this.task = task;
        this.explanation = explanation;
        this.fitsInTime = fitsInTime;
        this.isHighPriority = isHighPriority;
        this.hasNoDependencies = hasNoDependencies;
    }

    /**
     * Constructor with confidence score
     */
    public TaskRecommendationDTO(TaskDTO task, String explanation,
                                 Boolean fitsInTime, Boolean isHighPriority,
                                 Boolean hasNoDependencies, Integer confidenceScore) {
        this.task = task;
        this.explanation = explanation;
        this.fitsInTime = fitsInTime;
        this.isHighPriority = isHighPriority;
        this.hasNoDependencies = hasNoDependencies;
        this.confidenceScore = confidenceScore;
    }
}
