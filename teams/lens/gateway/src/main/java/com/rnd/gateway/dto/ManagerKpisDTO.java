package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET /api/manager/dashboard/kpis - mirrors BFF's ManagerKpisDTO. avgBlockedDays
 * is null until Chain provides it, and stays in the JSON as null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerKpisDTO {

    private int inProgress;
    private int blocked;
    private int delayed;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Double avgBlockedDays;
}
