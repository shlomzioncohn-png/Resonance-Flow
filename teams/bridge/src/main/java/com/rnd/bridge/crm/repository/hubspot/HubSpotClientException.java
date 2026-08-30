package com.rnd.bridge.crm.repository.hubspot;

/**
 * A HubSpot HTTP call failed - a non-2xx response other than a retried 429,
 * a retry budget exhausted on 429s, or a transport-level failure. The
 * message names the endpoint and the kind of failure only; it never
 * includes the bearer token or any request header.
 */
public class HubSpotClientException extends RuntimeException {

    public HubSpotClientException(String message) {
        super(message);
    }

    public HubSpotClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
