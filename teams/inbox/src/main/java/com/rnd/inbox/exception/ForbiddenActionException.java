package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenActionException extends InboxException {

    public ForbiddenActionException(String message) {
        super(message, "FORBIDDEN", HttpStatus.FORBIDDEN);
    }
}
