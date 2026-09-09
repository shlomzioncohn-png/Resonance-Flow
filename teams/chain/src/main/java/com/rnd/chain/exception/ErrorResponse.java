package com.rnd.chain.exception;

/** Minimal JSON error body used by {@link GlobalExceptionHandler}. */
public final class ErrorResponse {

    private final String error;
    private final String message;

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }
}
