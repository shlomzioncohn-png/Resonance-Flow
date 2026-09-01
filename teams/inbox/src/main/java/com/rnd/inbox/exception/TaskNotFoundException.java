package com.rnd.inbox.exception;

import java.util.UUID;

public class TaskNotFoundException extends InboxException {

    private static final String ERROR_CODE = "TASK_NOT_FOUND";

    public TaskNotFoundException(UUID taskId) {
        super("Task " + taskId + " not found");
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
