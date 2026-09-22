package com.rnd.inbox.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ErrorResponseDto {

    private final int status;
    private final String error;
    private final String message;
    private final Instant timestamp;
    private final List<FieldErrorDto> fieldErrors;

    public ErrorResponseDto(int status, String error, String message, Instant timestamp) {
        this(status, error, message, timestamp, null);
    }

    public ErrorResponseDto(int status, String error, String message, Instant timestamp,
                             List<FieldErrorDto> fieldErrors) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.timestamp = timestamp;
        this.fieldErrors = fieldErrors;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public List<FieldErrorDto> getFieldErrors() {
        return fieldErrors;
    }
}
