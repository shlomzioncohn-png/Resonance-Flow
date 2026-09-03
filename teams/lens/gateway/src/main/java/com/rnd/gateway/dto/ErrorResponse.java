package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import java.time.LocalDateTime;

/**
 * Unified error response contract matching GATEWAY_CONTRACT.md
 * 
 * Used for all error responses from Gateway.
 * Ensures consistent error format across all consumers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    
    private String status           = "ERROR";           // ERROR
    private String errorCode;                             // e.g., NOT_FOUND, SERVICE_UNAVAILABLE, TIMEOUT
    private String message;                               // Human-readable error message
    private String path;                                  // Request path that caused error
    private LocalDateTime timestamp;                      // When error occurred
    private String details;                               // Optional: additional error details
    
    // Custom constructor with required fields
    public ErrorResponse(String errorCode, String message, String path) {
        this.status = "ERROR";
        this.errorCode = errorCode;
        this.message = message;
        this.path = path;
        this.timestamp = LocalDateTime.now();
    }
    
    // Custom constructor with all fields
    public ErrorResponse(String errorCode, String message, String path, String details) {
        this(errorCode, message, path);
        this.details = details;
    }
}
