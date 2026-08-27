package main.java.com.rnd.lens.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Error Response DTO - Unified error format
 *
 * Used by:
 * - Gateway (routes errors from BFF)
 * - BFF (sends errors to Gateway)
 * - Frontend (displays error messages)
 *
 * Example:
 * {
 *   "status": "ERROR",
 *   "errorCode": "TASK_NOT_FOUND",
 *   "message": "Task with ID 'task_999' does not exist",
 *   "path": "/api/tasks/task_999",
 *   "timestamp": "2026-08-27T14:30:45.123Z",
 *   "details": "Check the task ID and try again"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /**
     * Status indicator
     * Always: "ERROR"
     */
    private String status = "ERROR";

    /**
     * Error code (machine-readable)
     * Examples: "TASK_NOT_FOUND", "INVALID_REQUEST", "UNAUTHORIZED", "SERVICE_UNAVAILABLE"
     */
    private String errorCode;

    /**
     * Error message (human-readable)
     * Example: "Task with ID 'task_999' does not exist"
     */
    private String message;

    /**
     * API path where error occurred
     * Example: "/api/tasks/task_999"
     */
    private String path;

    /**
     * Timestamp when error occurred
     * ISO-8601 format
     */
    private LocalDateTime timestamp;

    /**
     * Additional error details (optional)
     * Example: "Check the task ID and try again"
     * Null fields are not serialized (JsonInclude.NON_NULL)
     */
    private String details;

    /**
     * Constructor for basic error
     * Sets status="ERROR" and timestamp=now()
     */
    public ErrorResponse(String errorCode, String message, String path) {
        this.errorCode = errorCode;
        this.message = message;
        this.path = path;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * Constructor with details
     */
    public ErrorResponse(String errorCode, String message, String path, String details) {
        this(errorCode, message, path);
        this.details = details;
    }
}
