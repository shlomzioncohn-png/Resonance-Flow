package com.rnd.bff.exception;

/**
 * Thrown when Chain answers 404 to a constraint status change: the task
 * isn't in the user's tenant, or the constraint doesn't exist on that task.
 * Chain doesn't say which, so neither does this.
 */
public class ConstraintNotFoundException extends RuntimeException {

    private final String taskId;
    private final String constraintId;

    public ConstraintNotFoundException(String taskId, String constraintId) {
        super("Constraint " + constraintId + " not found on task " + taskId);
        this.taskId = taskId;
        this.constraintId = constraintId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getConstraintId() {
        return constraintId;
    }
}
