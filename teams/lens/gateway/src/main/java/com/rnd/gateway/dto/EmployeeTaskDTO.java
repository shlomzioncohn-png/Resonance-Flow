package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The employee's current task, for the My Workspace chat/blocking-dependency
 * demo. Mirrors com.rnd.bff.dto.EmployeeTaskDTO field-for-field — each layer
 * owns its own copy of the DTO rather than sharing a library module, same as
 * TaskDTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmployeeTaskDTO {
    private String id;
    private String title;
    private String team;

    /** High / Medium / Low */
    private String priority;

    /** Ready / Blocked */
    private String status;

    private String description;
    private String owner;
    private String due;
    private Integer estimatedMinutes;
    private String createdAt;
    private String source;
    private String sprint;
    private List<String> tags;
    private List<TaskRefDTO> blockedBy;
    private List<TaskRefDTO> blocks;
    private String blockingReason;
}
