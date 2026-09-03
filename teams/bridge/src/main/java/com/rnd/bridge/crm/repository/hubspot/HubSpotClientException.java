package com.rnd.bridge.crm.repository.hubspot;

/**
 * A HubSpot HTTP call failed - a fatal non-2xx response (anything other than
 * a retried 429 or 502/503/504), or the retry budget exhausted on 429s,
 * 502/503/504s, or transport-level failures. The message names the endpoint
 * and the kind of failure only; it never includes the bearer token or any
 * request header.
 */
public class HubSpotClientException extends RuntimeException {

    public HubSpotClientException(String message) {
        super(message);
    }

    public HubSpotClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
