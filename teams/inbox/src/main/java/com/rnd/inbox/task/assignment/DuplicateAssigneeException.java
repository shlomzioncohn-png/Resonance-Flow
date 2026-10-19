package com.rnd.inbox.task.assignment;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class DuplicateAssigneeException extends InboxException {

    public DuplicateAssigneeException(AssigneeType type, UUID id) {
        super("Assignee " + type + ":" + id + " is listed more than once", "DUPLICATE_ASSIGNEE",
                HttpStatus.BAD_REQUEST);
    }
}
