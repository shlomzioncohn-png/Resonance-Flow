package com.rnd.bridge.crm.repository.salesforce;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Talks to the Salesforce REST API: OAuth 2.0 Client Credentials Flow token acquisition, and SOQL
 * query execution with {@code nextRecordsUrl} pagination. {@code REQUEST_LIMIT_EXCEEDED} is fatal,
 * never retried; 500/502/503/504 and transport failures are, up to {@code maxRetries} with backoff.
 */
final class SalesforceClient {

    private static final String TOKEN_ENDPOINT = "/services/oauth2/token";

    private final RestClient restClient;
    private final String loginUrl;
    private final String clientId;
    private final String clientSecret;
    private final String queryEndpoint;
    private final int maxRetries;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final LongConsumer sleeper;

    private volatile AccessToken cachedToken;

    SalesforceClient(
            RestClient.Builder restClientBuilder,
            String loginUrl,
            String clientId,
            String clientSecret,
            String apiVersion,
            int maxRetries,
            long initialBackoffMillis,
            long maxBackoffMillis,
            LongConsumer sleeper) {
        if (loginUrl == null || loginUrl.isBlank()) {
            throw new IllegalStateException(
                    "Salesforce login URL is not configured - set the SALESFORCE_LOGIN_URL environment variable");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException(
                    "Salesforce client id is not configured - set the SALESFORCE_CLIENT_ID environment variable");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Salesforce client secret is not configured - set the SALESFORCE_CLIENT_SECRET environment variable");
        }
        if (apiVersion == null || apiVersion.isBlank()) {
            throw new IllegalStateException(
                    "Salesforce API version is not configured - set bridge.crm.salesforce.api-version "
                            + "(SALESFORCE_API_VERSION)");
        }
        this.restClient = restClientBuilder.build();
        this.loginUrl = stripTrailingSlash(loginUrl);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.queryEndpoint = "/services/data/v" + apiVersion + "/query/";
        this.maxRetries = maxRetries;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.sleeper = sleeper;
    }

    static SalesforceClient production(
            RestClient.Builder restClientBuilder,
            String loginUrl,
            String clientId,
            String clientSecret,
            String apiVersion,
            int maxRetries,
            long initialBackoffMillis,
            long maxBackoffMillis,
            long connectTimeoutMillis,
            long readTimeoutMillis) {
        RestClient.Builder timeoutAwareBuilder = restClientBuilder.requestFactory(
                ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                        .withReadTimeout(Duration.ofMillis(readTimeoutMillis))));
        return new SalesforceClient(timeoutAwareBuilder, loginUrl, clientId, clientSecret, apiVersion, maxRetries,
                initialBackoffMillis, maxBackoffMillis, SalesforceClient::sleep);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SalesforceClientException("Interrupted while backing off a Salesforce request");
        }
    }

    String instanceUrl() {
        return currentToken().instanceUrl();
    }

    List<Map<String, Object>> query(String soql) {
        List<Map<String, Object>> all = new ArrayList<>();
        String path = queryEndpoint + "?q=" + urlEncode(soql);
        SalesforceQueryResult page = executeQuery(path);
        all.addAll(recordsOf(page));
        while (!page.done()) {
            if (page.nextRecordsUrl() == null) {
                throw new SalesforceClientException(
                        "Salesforce query response was not done but had no nextRecordsUrl");
            }
            page = executeQuery(page.nextRecordsUrl());
            all.addAll(recordsOf(page));
        }
        return all;
    }

    private static List<Map<String, Object>> recordsOf(SalesforceQueryResult page) {
        return page.records() == null ? List.of() : page.records();
    }

    private SalesforceQueryResult executeQuery(String path) {
        return withRetry(path, () -> attemptWithTokenRefresh(path));
    }

    private SalesforceQueryResult attemptWithTokenRefresh(String path) {
        AccessToken token = currentToken();
        try {
            return attemptQuery(token, path);
        } catch (Unauthorized firstFailure) {
            AccessToken refreshed = forceRefreshToken();
            try {
                return attemptQuery(refreshed, path);
            } catch (Unauthorized secondFailure) {
                throw new SalesforceClientException(
                        "Salesforce request to " + path
                                + " failed with an invalid session even after refreshing the access token");
            }
        }
    }

    private synchronized AccessToken currentToken() {
        if (cachedToken == null) {
            cachedToken = acquireToken();
        }
        return cachedToken;
    }

    private synchronized AccessToken forceRefreshToken() {
        cachedToken = acquireToken();
        return cachedToken;
    }

    private AccessToken acquireToken() {
        return withRetry(TOKEN_ENDPOINT, this::attemptAcquireToken);
    }

    private AccessToken attemptAcquireToken() {
        String form = "grant_type=client_credentials"
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        try {
            SalesforceTokenResponse response = restClient.post()
                    .uri(URI.create(loginUrl + TOKEN_ENDPOINT))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((request, httpResponse) -> {
                        int status = httpResponse.getStatusCode().value();
                        if (isTransientServerError(status)) {
                            throw new TransientServerError(status);
                        }
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            throw new SalesforceClientException(
                                    "Salesforce token request failed with status " + status
                                            + errorBodySuffix(bodyAsString(httpResponse)));
                        }
                        return httpResponse.bodyTo(SalesforceTokenResponse.class);
                    });
            return new AccessToken(response.accessToken(), stripTrailingSlash(response.instanceUrl()));
        } catch (RestClientException restClientFailure) {
            if (isTransportFailure(restClientFailure)) {
                throw new TransportFailure(restClientFailure);
            }
            throw restClientFailure;
        }
    }

    private SalesforceQueryResult attemptQuery(AccessToken token, String path) {
        try {
            return restClient.get()
                    .uri(URI.create(token.instanceUrl() + path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) {
                            throw new Unauthorized();
                        }
                        if (isTransientServerError(status)) {
                            throw new TransientServerError(status);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw fatalFor(path, status, bodyAsString(response));
                        }
                        return response.bodyTo(SalesforceQueryResult.class);
                    });
        } catch (RestClientException restClientFailure) {
            if (isTransportFailure(restClientFailure)) {
                throw new TransportFailure(restClientFailure);
            }
            throw restClientFailure;
        }
    }

    private static SalesforceClientException fatalFor(String path, int status, String body) {
        if (status == 403 && body.contains("REQUEST_LIMIT_EXCEEDED")) {
            return new SalesforceClientException(
                    "Salesforce API request limit exceeded calling " + path
                            + " - this is a rolling 24-hour limit, retrying will not help");
        }
        return new SalesforceClientException(
                "Salesforce request to " + path + " failed with status " + status + errorBodySuffix(body));
    }

    private <T> T withRetry(String path, Supplier<T> action) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return action.get();
            } catch (TransientServerError transientError) {
                if (attempt >= maxRetries) {
                    throw new SalesforceClientException(
                            "Salesforce request to " + path + " kept failing with transient status "
                                    + transientError.status() + " after " + attempt + " attempts");
                }
                sleeper.accept(delayFor(attempt));
            } catch (TransportFailure transportFailure) {
                if (attempt >= maxRetries) {
                    throw new SalesforceClientException(
                            "Salesforce request to " + path + " failed after " + attempt
                                    + " attempts due to a transport error: "
                                    + transportFailure.getCause().getClass().getSimpleName(),
                            transportFailure.getCause());
                }
                sleeper.accept(delayFor(attempt));
            } catch (SalesforceClientException clientException) {
                throw clientException;
            } catch (RuntimeException other) {
                throw new SalesforceClientException(
                        "Salesforce request to " + path + " failed: " + other.getClass().getSimpleName(), other);
            }
        }
    }

    private long delayFor(int attempt) {
        long delay = initialBackoffMillis * (1L << (attempt - 1));
        return Math.min(delay, maxBackoffMillis);
    }

    private static boolean isTransientServerError(int status) {
        return status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static boolean isTransportFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    // Best-effort only - a failure here must not mask the real fatal-status exception.
    private static String bodyAsString(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            String body = response.bodyTo(String.class);
            return body == null ? "" : body;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String errorBodySuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return ": " + (trimmed.length() > 500 ? trimmed.substring(0, 500) + "..." : trimmed);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The access token and the {@code instance_url} it came with - always used together. */
    private record AccessToken(String value, String instanceUrl) {
    }

    private static final class Unauthorized extends RuntimeException {
    }

    /** A 500/502/503/504 - always exponential backoff. */
    private static final class TransientServerError extends RuntimeException {
        private final int status;

        TransientServerError(int status) {
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** Wraps an IOException-caused {@link RestClientException} so it retries like the other transient failures. */
    private static final class TransportFailure extends RuntimeException {
        TransportFailure(RestClientException cause) {
            super(cause);
        }
    }
}
