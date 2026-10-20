package com.rnd.inbox.project.exception;

import com.rnd.inbox.shared.exception.InboxException;
import org.springframework.http.HttpStatus;

import java.util.Collection;

public class InvalidProjectSortException extends InboxException {

    public InvalidProjectSortException(String property, Collection<String> supported) {
        super("Projects cannot be sorted by '" + property + "'. Supported: " + String.join(", ", supported),
                "INVALID_PROJECT_SORT", HttpStatus.BAD_REQUEST);
    }
}
