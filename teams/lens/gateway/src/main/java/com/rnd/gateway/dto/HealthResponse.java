package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Gateway health response.
 * Provides status of gateway and all downstream services.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HealthResponse {
    
    private String status;                    // "UP", "DEGRADED", "DOWN"
    private String message;                   // Human-readable status
    private LocalDateTime timestamp;          // When health check was performed
    private List<ServiceHealth> services;     // Health of each downstream service
    private Integer servicesUp;               // Count of healthy services
    private Integer servicesDown;             // Count of unhealthy services
    
    public HealthResponse(String status, String message) {
        this.status = status;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }
    
    public HealthResponse(String status, String message, List<ServiceHealth> services) {
        this.status = status;
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.services = services;
        
        // Calculate counts
        if (services != null) {
            this.servicesUp = (int) services.stream().filter(s -> "UP".equals(s.getStatus())).count();
            this.servicesDown = (int) services.stream().filter(s -> "DOWN".equals(s.getStatus())).count();
        }
    }
    
    // Getters and Setters
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    public List<ServiceHealth> getServices() {
        return services;
    }
    
    public void setServices(List<ServiceHealth> services) {
        this.services = services;
    }
    
    public Integer getServicesUp() {
        return servicesUp;
    }
    
    public void setServicesUp(Integer servicesUp) {
        this.servicesUp = servicesUp;
    }
    
    public Integer getServicesDown() {
        return servicesDown;
    }
    
    public void setServicesDown(Integer servicesDown) {
        this.servicesDown = servicesDown;
    }
}
