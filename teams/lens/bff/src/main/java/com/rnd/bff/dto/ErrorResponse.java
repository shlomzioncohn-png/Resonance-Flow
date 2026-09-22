package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Unified error shape, matching com.rnd.gateway.dto.ErrorResponse so an
 * error looks the same regardless of which layer raised it. See
 * docs/workspace-screen-integration-plan.md, "Errors are uniform".
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private String status = "ERROR";
    private String errorCode;
    private String message;
    private String path;
    private LocalDateTime timestamp;
    private String details;

    public ErrorResponse(String errorCode, String message, String path, String details) {
        this.errorCode = errorCode;
        this.message = message;
        this.path = path;
        this.details = details;
        this.timestamp = LocalDateTime.now();
    }
}
