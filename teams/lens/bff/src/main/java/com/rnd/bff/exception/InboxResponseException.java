package com.rnd.bff.exception;

import org.springframework.http.HttpStatus;

/**
 * Wraps a real 4xx response Inbox returned (404 not found, 409 conflict,
 * 400 bad input, 403 forbidden) so it reaches the Frontend with the same
 * status and message instead of being flattened to 503 — task spec §5
 * "מיפוי שגיאות". Unlike ServiceUnavailableException (which means Inbox
 * couldn't be reached or failed unexpectedly), this means Inbox was
 * reached and responded with a real, meaningful rejection.
 */
public class InboxResponseException extends RuntimeException {

    private final HttpStatus status;

    public InboxResponseException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
