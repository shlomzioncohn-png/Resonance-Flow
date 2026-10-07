package com.rnd.inbox.task.exception;

import com.rnd.inbox.shared.exception.InboxException;
import org.springframework.http.HttpStatus;

import java.util.Collection;

public class InvalidTaskSortException extends InboxException {
    public InvalidTaskSortException(String property, Collection<String> supported) {
        super("Tasks cannot be sorted by '" + property + "'. Supported: " + String.join(", ", supported),
                "INVALID_TASK_SORT", HttpStatus.BAD_REQUEST);
    }
}
