package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Health status of a service.
 * Used in HealthStatus response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceHealth {
    
    private String name;              // Service name (e.g., "bridge", "mind")
    private String status;            // "UP", "DOWN", "UNKNOWN"
    private String url;               // Service URL
    private Long responseTime;        // Response time in ms
    private String lastError;         // Last error message if DOWN
    
    public ServiceHealth(String name, String status) {
        this.name = name;
        this.status = status;
    }
    
    public ServiceHealth(String name, String status, String url) {
        this.name = name;
        this.status = status;
        this.url = url;
    }
}
