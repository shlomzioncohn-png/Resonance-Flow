package com.rnd.inbox.task.exception;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class TaskNotFoundException extends InboxException {

    public TaskNotFoundException(UUID taskId) {
        super("Task " + taskId + " not found", "TASK_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}