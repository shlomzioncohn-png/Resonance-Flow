package com.rnd.chain.exception;

/**
 * Thrown when removing a dependency edge that does not exist between
 * the given taskId and dependsOnTaskId. Mapped to HTTP 404 by
 * GlobalExceptionHandler.
 */
public class DependencyNotFoundException extends NotFoundException {

    public DependencyNotFoundException(String message) {
        super(message);
    }

    public DependencyNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
