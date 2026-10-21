package com.rnd.inbox.auth.exception;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends InboxException {

    public InvalidCredentialsException() {
        super("Invalid login or password", "INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
    }
}
