package com.rnd.inbox.task.exception;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class InvalidTaskStatusTransitionException extends InboxException {

    public InvalidTaskStatusTransitionException(String message) {
        super(message, "INVALID_TASK_STATUS_TRANSITION", HttpStatus.BAD_REQUEST);
    }
}
