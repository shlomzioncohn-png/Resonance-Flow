package com.rnd.bff.exception;

/**
 * Raised when email is already registered.
 * Returns 409 Conflict.
 */
public class EmailTakenException extends RuntimeException {

    public EmailTakenException(String email) {
        super("Email already registered: " + email);
    }

    public EmailTakenException(String message, Throwable cause) {
        super(message, cause);
    }
}
