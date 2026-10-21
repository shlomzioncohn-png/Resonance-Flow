package com.rnd.inbox.project.exception;

import com.rnd.inbox.shared.exception.InboxException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ProjectHasTasksException extends InboxException {

    public ProjectHasTasksException(UUID id, long taskCount) {
        super("Project " + id + " has " + taskCount + " tasks and cannot be deleted",
                "PROJECT_HAS_TASKS", HttpStatus.CONFLICT);
    }
}
