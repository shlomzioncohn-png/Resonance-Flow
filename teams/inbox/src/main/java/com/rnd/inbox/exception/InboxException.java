package com.rnd.inbox.exception;

public abstract class InboxException extends RuntimeException {

    protected InboxException(String message) {
        super(message);
    }

    public abstract String getErrorCode();
}
