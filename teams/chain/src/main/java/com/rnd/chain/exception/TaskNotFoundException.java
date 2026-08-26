package com.rnd.chain.exception;

/**
 * Thrown when an operation references a taskId that does not exist
 * (or is soft-deleted) in {@code TaskRepository}.
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String message) {
        super(message);
    }
}
