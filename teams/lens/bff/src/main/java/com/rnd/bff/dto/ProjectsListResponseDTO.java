package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Projects List Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectsListResponseDTO {
    private List<ProjectSummaryDTO> projects;
    private Integer total;
}
