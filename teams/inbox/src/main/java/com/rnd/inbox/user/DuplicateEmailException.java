package com.rnd.inbox.user;

import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends InboxException {

    public DuplicateEmailException(String email) {
        super("Email " + email + " already exists", "DUPLICATE_EMAIL", HttpStatus.CONFLICT);
    }
}
