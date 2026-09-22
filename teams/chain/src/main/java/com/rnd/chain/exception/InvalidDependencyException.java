package com.rnd.chain.exception;

/**
 * Thrown when a dependency edge would be invalid regardless of what
 * already exists in storage - currently: a task depending on itself
 * (taskId equals dependsOnTaskId).
 */
public class InvalidDependencyException extends RuntimeException {

    public InvalidDependencyException(String message) {
        super(message);
    }

    public InvalidDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
