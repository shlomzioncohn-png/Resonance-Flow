package com.rnd.bridge.crm.repository.hubspot;

/**
 * A HubSpot HTTP call failed - a fatal non-2xx response, or an exhausted retry budget.
 * The message never includes the bearer token or any request header.
 */
public class HubSpotClientException extends RuntimeException {

    public HubSpotClientException(String message) {
        super(message);
    }

    public HubSpotClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
