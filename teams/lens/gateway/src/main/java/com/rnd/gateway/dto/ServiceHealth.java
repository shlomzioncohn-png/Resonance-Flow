package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Health status of a service.
 * Used in HealthStatus response.
 */
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
    
    // Getters and Setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public Long getResponseTime() {
        return responseTime;
    }
    
    public void setResponseTime(Long responseTime) {
        this.responseTime = responseTime;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
