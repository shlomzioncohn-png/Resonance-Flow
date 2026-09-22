package com.rnd.inbox.task;

import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class InvalidTaskFilterException extends InboxException {

    public InvalidTaskFilterException(String message) {
        super(message, "INVALID_TASK_FILTER", HttpStatus.BAD_REQUEST);
    }
}
