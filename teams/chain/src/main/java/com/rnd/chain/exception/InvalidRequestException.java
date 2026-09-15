package com.rnd.chain.exception;

/**
 * Thrown for request-validation failures specific to this module's API
 * (e.g. a missing sourceRef, an out-of-range page/size) - kept distinct
 * from the JDK's own IllegalArgumentException so GlobalExceptionHandler
 * only maps this module's validation errors to 400, not unrelated bugs.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
