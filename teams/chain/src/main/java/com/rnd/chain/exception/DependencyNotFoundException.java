package com.rnd.chain.exception;

/**
 * Thrown when removing a dependency edge that does not exist between
 * the given taskId and dependsOnTaskId.
 */
public class DependencyNotFoundException extends RuntimeException {

    public DependencyNotFoundException(String message) {
        super(message);
    }
}
