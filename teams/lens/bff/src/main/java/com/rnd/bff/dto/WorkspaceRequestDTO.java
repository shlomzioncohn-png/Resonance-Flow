package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Employee's request for a task recommendation.
 *
 * userId is intentionally not a field here — the Gateway extracts it from
 * the JWT and forwards it as the X-User-Id header instead (IDOR prevention).
 * See docs/workspace-screen-integration-plan.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceRequestDTO {

    @NotNull(message = "availableTime is required")
    @Min(value = 1, message = "availableTime must be at least 1 minute")
    private Integer availableTime;

    /** Optional: filter by project/team name. Null = any project. */
    private String projectFilter;

    /** Optional: minimum priority — "LOW" / "MEDIUM" / "HIGH". Null = any priority. */
    private String minPriority;
}
