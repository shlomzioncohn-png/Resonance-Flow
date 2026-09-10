package com.rnd.chain.exception;

/**
 * Thrown when attempting to add a dependency edge (taskId,
 * dependsOnTaskId) that already exists.
 */
public class DuplicateDependencyException extends RuntimeException {

    public DuplicateDependencyException(String message) {
        super(message);
    }

    public DuplicateDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
