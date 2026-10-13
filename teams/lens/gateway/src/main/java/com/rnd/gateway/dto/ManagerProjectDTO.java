package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A project card on the Manager Dashboard project grid. Mirrors
 * com.rnd.bff.dto.ManagerProjectDTO field-for-field — each layer owns its
 * own copy of the DTO rather than sharing a library module, same as TaskDTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
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
