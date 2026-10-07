package com.rnd.inbox.user.exception;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class DuplicateUsernameException extends InboxException {

    public DuplicateUsernameException(String username) {
        super("Username " + username + " already exists", "DUPLICATE_USERNAME", HttpStatus.CONFLICT);
    }
}
