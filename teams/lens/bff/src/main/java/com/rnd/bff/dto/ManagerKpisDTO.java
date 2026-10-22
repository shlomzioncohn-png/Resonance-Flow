package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET /manager/dashboard/kpis - the Manager Dashboard's KPI tiles, for the
 * acting manager's whole tenant (optionally one project).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerKpisDTO {

    @Schema(description = "Tasks with status IN_PROGRESS", example = "42")
    private int inProgress;

    @Schema(description = "Open tasks Chain marks as not available (is_available=false)", example = "9")
    private int blocked;

    @Schema(description = "Open tasks whose deadline has passed", example = "5")
    private int delayed;

    /** Always in the JSON, as null when unknown: the frontend shows "Not available yet" for it. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "Average days a task stays blocked. Always null for now: it needs data from Chain.",
            nullable = true, example = "3.2")
    private Double avgBlockedDays;
}
