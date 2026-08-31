package com.rnd.gateway.exception;

/**
 * Exception thrown when authenticated user lacks permission (403).
 */
public class ForbiddenException extends RuntimeException {
    
    public ForbiddenException(String message) {
        super(message);
    }
    
    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
