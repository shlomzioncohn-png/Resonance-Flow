package com.rnd.bff.exception;

/**
 * Thrown when Inbox is up but refuses the request with a 4xx - e.g. 401 (the
 * actor isn't a known Inbox user), 403 (task outside the actor's tenant), or
 * 400 (an invalid change, like SNOOZED without snoozedUntil).
 *
 * Kept apart from ServiceUnavailableException on purpose: "Inbox said no" is
 * not "Inbox is down", and the screen should say which one happened.
 * 404 never gets here - InboxClient turns it into "not found" (null).
 */
public class InboxRejectedException extends RuntimeException {

    private final int status;

    public InboxRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Inbox's HTTP status: 400, 401 or 403 (anything else 4xx is treated as 400). */
    public int getStatus() {
        return status;
    }
}
