package com.rnd.chain.dto;

import java.time.LocalDateTime;

public class ApiError {

    private final int status;
    private final String message;
    private final LocalDateTime timestamp;
    private final String error;
    private final String path;

    public ApiError(int status, String error, String message, String path) {
        this.status = status;
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.error = error;
        this.path = path;
    }

    public int getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getError() {
        return error;
    }

    public String getPath() {
        return path;
    }
}