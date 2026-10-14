package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Project Summary for Dashboard List
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectSummaryDTO {
    private String projectId;
    private String projectName;
    private String description;
    private String status;
    private Integer totalTasks;
    private Integer completedTasks;
    private List<Map<String, Object>> team;
}
