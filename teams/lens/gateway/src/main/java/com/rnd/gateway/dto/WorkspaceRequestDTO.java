package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * WorkspaceRequest DTO - Employee asking for task recommendation
 *
 * Flow:
 * Frontend (Employee) → Gateway → BFF
 *
 * Question: "I have X minutes available, what should I work on?"
 * Answer: Returns TaskRecommendationDTO with best task
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceRequestDTO {

    /**
     * Employee ID requesting the recommendation
     * Example: "emp_001", "user_rachel_123"
     */
    private String userId;

    /**
     * Available time in minutes
     * Example: 45 (employee has 45 minutes free)
     */
    private Integer availableTime;

    /**
     * Optional: filter by project/team
     * Example: "Platform Service"
     * Null = recommend from any project
     */
    private String projectFilter;

    /**
     * Optional: minimum priority level
     * Values: "LOW", "MEDIUM", "HIGH"
     * Null = any priority
     */
    private String minPriority;
}
