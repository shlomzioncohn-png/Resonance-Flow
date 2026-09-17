package com.rnd.bff.exception;

/**
 * Exception thrown when a downstream service (Inbox, Chain, Mind) is
 * unavailable (503). Mirrors com.rnd.gateway.exception.ServiceUnavailableException
 * — see docs/workspace-screen-integration-plan.md, "Errors are uniform".
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;

    public ServiceUnavailableException(String serviceName, String message) {
        super(message);
        this.serviceName = serviceName;
    }

    public ServiceUnavailableException(String serviceName, String message, Throwable cause) {
        super(message, cause);
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
