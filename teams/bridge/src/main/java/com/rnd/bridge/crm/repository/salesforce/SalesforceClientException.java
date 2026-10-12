package com.rnd.bridge.crm.repository.salesforce;

/**
 * A Salesforce HTTP call failed - a fatal non-2xx response (anything other than a retried
 * 500/502/503/504), {@code REQUEST_LIMIT_EXCEEDED} (never retried - see this package's
 * {@code SalesforceClient} Javadoc), a second {@code 401} after a token refresh, or the retry
 * budget exhausted on a transient status or a transport failure. The message names the endpoint
 * and the kind of failure only; it never includes the client secret or the access token.
 */
public class SalesforceClientException extends RuntimeException {

    public SalesforceClientException(String message) {
        super(message);
    }

    public SalesforceClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
