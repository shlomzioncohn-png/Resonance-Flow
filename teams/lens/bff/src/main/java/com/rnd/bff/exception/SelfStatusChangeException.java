package com.rnd.bff.exception;

/**
 * A manager tried to change their own status (409) — task spec §4: this
 * guards against the one admin locking themselves out. Raised in
 * ManagerUsersService before Inbox is even called; Inbox never sees this
 * request.
 */
public class SelfStatusChangeException extends RuntimeException {

    public SelfStatusChangeException(String message) {
        super(message);
    }
}
