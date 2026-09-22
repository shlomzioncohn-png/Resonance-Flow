package com.rnd.gateway.exception;

/**
 * Exception thrown when downstream service request times out (504).
 */
public class GatewayTimeoutException extends RuntimeException {
    
    private final String serviceName;
    private final long timeoutMs;
    
    public GatewayTimeoutException(String serviceName, long timeoutMs, String message) {
        super(message);
        this.serviceName = serviceName;
        this.timeoutMs = timeoutMs;
    }
    
    public GatewayTimeoutException(String serviceName, long timeoutMs, String message, Throwable cause) {
        super(message, cause);
        this.serviceName = serviceName;
        this.timeoutMs = timeoutMs;
    }
    
    public String getServiceName() {
        return serviceName;
    }
    
    public long getTimeoutMs() {
        return timeoutMs;
    }
}
