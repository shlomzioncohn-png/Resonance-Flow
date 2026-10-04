package com.rnd.bff.exception;

/**
 * Thrown when a task id doesn't exist in Inbox's task set (404). Only
 * possible now that getTask/updateTaskStatus do a real lookup instead of
 * echoing back a hardcoded task regardless of id.
 */
public class TaskNotFoundException extends RuntimeException {

    private final String taskId;

    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
