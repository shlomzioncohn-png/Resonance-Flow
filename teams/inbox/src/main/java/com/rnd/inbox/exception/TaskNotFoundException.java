package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class TaskNotFoundException extends InboxException {

    public TaskNotFoundException(UUID taskId) {
        super("Task " + taskId + " not found", "TASK_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
