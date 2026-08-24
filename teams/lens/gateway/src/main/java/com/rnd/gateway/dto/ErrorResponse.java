package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/**
 * Unified error response contract matching GATEWAY_CONTRACT.md
 * 
 * Used for all error responses from Gateway.
 * Ensures consistent error format across all consumers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    
    private String status;           // ERROR
    private String errorCode;        // e.g., NOT_FOUND, SERVICE_UNAVAILABLE, TIMEOUT
    private String message;          // Human-readable error message
    private String path;             // Request path that caused error
    private LocalDateTime timestamp; // When error occurred
    private String details;          // Optional: additional error details
    
    // Constructor with required fields
    public ErrorResponse(String errorCode, String message, String path) {
        this.status = "ERROR";
        this.errorCode = errorCode;
        this.message = message;
        this.path = path;
        this.timestamp = LocalDateTime.now();
    }
    
    // Constructor with all fields
    public ErrorResponse(String errorCode, String message, String path, String details) {
        this(errorCode, message, path);
        this.details = details;
    }
    
    // Getters and Setters
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getPath() {
        return path;
    }
    
    public void setPath(String path) {
        this.path = path;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getDetails() {
        return details;
    }
    
    public void setDetails(String details) {
        this.details = details;
    }
    
    @Override
    public String toString() {
        return "ErrorResponse{" +
                "status='" + status + '\'' +
                ", errorCode='" + errorCode + '\'' +
                ", message='" + message + '\'' +
                ", path='" + path + '\'' +
                ", timestamp=" + timestamp +
                ", details='" + details + '\'' +
                '}';
    }
}
