package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class UserNotFoundException extends InboxException {

    public UserNotFoundException(UUID id) {
        super("User " + id + " not found", "USER_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
