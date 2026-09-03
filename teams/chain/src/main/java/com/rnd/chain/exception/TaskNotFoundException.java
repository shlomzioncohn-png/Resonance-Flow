package com.rnd.chain.exception;

import java.util.UUID;

/**
 * Thrown when a traversal is requested for a task id that doesn't exist
 * (or is soft-deleted). Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
    }
}
