package com.rnd.inbox.auth.exception;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class TooManyLoginAttemptsException extends InboxException {

    public TooManyLoginAttemptsException() {
        super("Too many failed login attempts, try again later", "TOO_MANY_LOGIN_ATTEMPTS",
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
