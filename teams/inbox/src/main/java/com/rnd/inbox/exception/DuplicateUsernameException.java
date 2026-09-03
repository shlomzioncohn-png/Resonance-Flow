package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;

public class DuplicateUsernameException extends InboxException {

    public DuplicateUsernameException(String username) {
        super("Username " + username + " already exists", "DUPLICATE_USERNAME", HttpStatus.CONFLICT);
    }
}
