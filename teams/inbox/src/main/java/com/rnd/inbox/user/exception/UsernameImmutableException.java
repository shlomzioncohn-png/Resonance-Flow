package com.rnd.inbox.user.exception;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class UsernameImmutableException extends InboxException {

    public UsernameImmutableException() {
        super("Username cannot be changed", "USERNAME_IMMUTABLE", HttpStatus.BAD_REQUEST);
    }
}
