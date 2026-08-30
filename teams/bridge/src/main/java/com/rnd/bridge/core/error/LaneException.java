package com.rnd.bridge.core.error;

public class LaneException extends RuntimeException {

    public LaneException(String message) {
        super(message);
    }

    public LaneException(String message, Throwable cause) {
        super(message, cause);
    }
}
