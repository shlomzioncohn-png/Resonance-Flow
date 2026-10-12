package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A project card on the Manager Dashboard project grid. Mirrors
 * com.rnd.gateway.dto.ManagerProjectDTO and the frontend's Project
 * (services/projectsApi.ts) field-for-field — distinct from
 * ProjectSummaryDTO/ProjectDetailsDTO (com.rnd.bff.client.ProjectDetailsService),
 * which back the separate per-project drill-down page.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManagerProjectDTO {
    private String id;
    private String name;
    private String team;
    private String owner;

    /** healthy / warning / critical / neutral */
    private String status;

    private String statusLabel;
    private List<ProjectMemberDTO> members;

    /** 0-100 */
    private Integer progressPercent;

    private Integer totalCount;
    private Integer inProgressCount;
    private Integer blockedCount;

    /** ISO-8601 datetime string. */
    private String lastUpdatedAt;
}
