package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Project Details DTO - Simple flat structure for ease of use
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectDetailsDTO {
    private String projectId;
    private String projectName;
    private String description;
    private String status;
    private String startDate;
    private String endDate;
    
    private Integer totalTasks;
    private Integer inProgressTasks;
    private Integer blockedTasks;
    private Integer completedTasks;
    private Integer progressPercentage;
    
    private List<Map<String, Object>> processStages;
    private List<Map<String, Object>> team;
    private List<Map<String, Object>> recentActivities;
}
