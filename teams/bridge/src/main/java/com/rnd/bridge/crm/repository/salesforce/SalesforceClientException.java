package com.rnd.bridge.crm.repository.salesforce;

/**
 * A Salesforce HTTP call failed - a fatal non-2xx response, {@code REQUEST_LIMIT_EXCEEDED}, a
 * second 401 after refresh, or an exhausted retry budget. Never includes the client secret or access token.
 */
public class SalesforceClientException extends RuntimeException {

    public SalesforceClientException(String message) {
        super(message);
    }

    public SalesforceClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
