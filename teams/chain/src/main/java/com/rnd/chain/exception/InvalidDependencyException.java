package com.rnd.chain.exception;

/**
 * Thrown when a dependency edge would be invalid regardless of what
 * already exists in storage - currently: a task depending on itself
 * (taskId equals dependsOnTaskId). Mapped to HTTP 400 by
 * GlobalExceptionHandler.
 */
public class InvalidDependencyException extends BadRequestException {

    public InvalidDependencyException(String message) {
        super(message);
    }

    public InvalidDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
