package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The employee's current task, for the My Workspace chat/blocking-dependency
 * demo. Mirrors com.rnd.gateway.dto.EmployeeTaskDTO and the frontend's
 * EmployeeTask (services/employeeApi.ts) field-for-field — each layer owns
 * its own copy of the DTO rather than sharing a library module, same as
 * TaskDTO.
 *
 * due/createdAt are already-formatted display strings ("Today, 5:00 PM"),
 * not ISO timestamps — this mirrors what the frontend previously hardcoded
 * itself (see EmployeeClient's javadoc), not a real schema shape like
 * InboxTaskRecord's, since no service owns this data yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
