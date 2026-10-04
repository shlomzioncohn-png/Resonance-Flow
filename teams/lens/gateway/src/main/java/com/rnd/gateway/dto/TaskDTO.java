package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Task DTO - Represents a task/work item in the system.
 *
 * This is the core data model shared between:
 * - Frontend (displays task information)
 * - Gateway (routes task data)
 * - BFF (orchestrates tasks from team services)
 *
 * Used by:
 * - Mind (task detection, scoring)
 * - Chain (dependency graph, feasibility)
 * - Inbox (task lifecycle management)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskDTO {

    /**
     * Unique task identifier
     * Example: "task_001", "task_review_api"
     */
    private String id;

    /**
     * Task title/summary
     * Example: "Review API changes"
     */
    private String title;

    /**
     * Detailed task description
     * Example: "Review the new API endpoints and provide feedback on design"
     */
    private String description;

    /**
     * Task status
     * Values: READY, IN_PROGRESS, BLOCKED, COMPLETED
     */
    private String status;

    /**
     * Task priority/importance
     * Values: LOW, MEDIUM, HIGH
     */
    private String priority;

    /**
     * Estimated effort in minutes
     * Example: 35 (means ~35 minutes to complete)
     */
    private Integer effortEstimate;

    /**
     * Due date and time
     * Example: "2026-08-28T17:00:00"
     */
    private LocalDateTime dueDate;

    /**
     * Task owner/assigned to (usually manager name)
     * Example: "Rachel Zahler"
     */
    private String owner;

    /**
     * Project or team this task belongs to
     * Example: "Platform Service", "Chain Team"
     */
    private String project;

    /**
     * List of task IDs this task depends on
     * Example: ["task_002", "task_003"]
     * Empty list means no dependencies
     */
    private List<String> dependencies;

    /**
     * List of constraints/blockers preventing task completion
     * Example: ["approval_needed", "waiting_for_customer"]
     * Empty list means no constraints
     */
    private List<String> constraints;

    /**
     * External source of this task
     * Example: "GitHub – PR #4821", "Jira – PROJ-123"
     * TODO: confirm exact format with Inbox/Bridge teams
     */
    private String source;

    /**
     * Sprint this task belongs to
     * Example: "Sprint 24 (May 6 – May 20)"
     * TODO: confirm field name and format with Inbox team
     */
    private String sprint;

    /**
     * Tags associated with this task
     * Example: ["backend", "api", "testing"]
     * TODO: confirm with Inbox team whether tags are free-text or enum
     */
    private List<String> tags;

    /**
     * Reason this task is blocked (from a dependency or manual input)
     * Example: "Waiting for the API specification to be finalized"
     * Null if task is not blocked
     */
    private String blockingReason;

    /**
     * Constructor for minimal task creation
     * Used when only essential info is available
     */
    public TaskDTO(String id, String title, String status, String priority) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.priority = priority;
    }

    /**
     * Constructor for task recommendation flow
     * Used when returning recommended task from BFF
     */
    public TaskDTO(String id, String title, String status, String priority,
                   Integer effortEstimate, LocalDateTime dueDate, String owner) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.priority = priority;
        this.effortEstimate = effortEstimate;
        this.dueDate = dueDate;
        this.owner = owner;
    }
}
