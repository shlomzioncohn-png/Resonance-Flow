package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;

public class UnauthenticatedException extends InboxException {

    public UnauthenticatedException(String message) {
        super(message, "UNAUTHENTICATED", HttpStatus.UNAUTHORIZED);
    }
}
