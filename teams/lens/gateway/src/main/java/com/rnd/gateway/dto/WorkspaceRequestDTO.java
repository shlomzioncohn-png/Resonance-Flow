package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
 *
 * Note: userId is NOT part of this DTO.
 * The Gateway extracts the user identity from the JWT token
 * and forwards it to the BFF as the X-User-Id request header.
 * The Frontend never sends userId directly (IDOR prevention).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceRequestDTO {

    /**
     * Available time in minutes
     * Example: 45 (employee has 45 minutes free)
     */
    @NotNull(message = "availableTime is required")
    @Min(value = 1, message = "availableTime must be at least 1 minute")
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
