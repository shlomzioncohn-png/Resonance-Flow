package com.rnd.chain.exception;

import java.util.UUID;

//Task not found
public class TaskNotFoundException extends NotFoundException {

    public TaskNotFoundException(String message) {
        super(message);
    }
    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
    }
}