package com.rnd.bridge.ai;

/**
 * Anything that went wrong calling a model. {@code retryable} says whether trying again (or
 * falling back to another model) could help: a timeout, rate limit or 5xx is; a bad key, bad
 * request or a blocked call is not.
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    public LlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
