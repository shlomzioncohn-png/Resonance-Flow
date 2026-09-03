package com.rnd.bridge.crm.repository.hubspot;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;

/**
 * Talks to the HubSpot CRM v3 API: the pipeline lookup, and cursor-following
 * deal pagination with {@code propertiesWithHistory=dealstage}.
 * <p>
 * Three classes of failure are retried, up to {@code maxRetries} attempts,
 * each backing off before the next attempt:
 * <ul>
 *   <li>{@code 429} (quota exceeded) - backs off using the response's
 *       {@code Retry-After} header (seconds) when present, exponential
 *       backoff otherwise.</li>
 *   <li>{@code 502}/{@code 503}/{@code 504} (transient server errors) -
 *       always exponential backoff, HubSpot does not send a useful
 *       {@code Retry-After} for these.</li>
 *   <li>Transport-level failures (connect/read timeouts, DNS/IO errors) -
 *       always exponential backoff. Surfaced by {@code RestClient} two
 *       different ways for the same underlying problem: a failure before
 *       any response exists (refused connection, DNS, a connect timeout) is
 *       a {@link ResourceAccessException}; a read timeout that hits
 *       mid-body, after a 2xx status already came back, is a plain {@link
 *       RestClientException} from message-converter extraction instead.
 *       Both are recognised by walking the cause chain for an {@link
 *       IOException} rather than by exception type.</li>
 * </ul>
 * Any other non-2xx response (e.g. {@code 400}/{@code 401}/{@code 404}) is
 * fatal and fails immediately, not retried. Exhausting the retry budget on
 * any of the three retryable classes above, or hitting a fatal response,
 * fails with a {@link HubSpotClientException} - never containing the bearer
 * token.
 * <p>
 * {@link #production} configures explicit connect/read timeouts (see
 * {@code bridge.crm.hubspot.connect-timeout-millis} /
 * {@code read-timeout-millis}) so a server that accepts a connection and
 * then never responds - no status code, nothing for the retry logic above
 * to react to on its own - fails the request instead of blocking the
 * calling thread indefinitely, and is then retried like any other
 * transport failure.
 */
final class HubSpotClient {

    private static final String DEALS_PROPERTIES = "dealname,amount,closedate,dealstage";
    private static final String HISTORY_PROPERTY = "dealstage";
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String DEALS_ENDPOINT = "/crm/v3/objects/deals";
    private static final String PIPELINES_ENDPOINT = "/crm/v3/pipelines/deals";

    private final RestClient restClient;
    private final String token;
    private final int pageSize;
    private final int maxRetries;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final LongConsumer sleeper;

    HubSpotClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String token,
            int pageSize,
            int maxRetries,
            long initialBackoffMillis,
            long maxBackoffMillis,
            LongConsumer sleeper) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "HubSpot token is not configured - set the HUBSPOT_TOKEN environment variable");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.token = token;
        this.pageSize = pageSize;
        this.maxRetries = maxRetries;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.sleeper = sleeper;
    }

     static HubSpotClient production(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String token,
            int pageSize,
            int maxRetries,
            long initialBackoffMillis,
            long maxBackoffMillis,
            long connectTimeoutMillis,
            long readTimeoutMillis) {
        RestClient.Builder timeoutAwareBuilder = restClientBuilder.requestFactory(
                ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                        .withReadTimeout(Duration.ofMillis(readTimeoutMillis))));
        return new HubSpotClient(timeoutAwareBuilder, baseUrl, token, pageSize, maxRetries,
                initialBackoffMillis, maxBackoffMillis, HubSpotClient::sleep);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HubSpotClientException("Interrupted while backing off a rate-limited HubSpot request");
        }
    }

    List<HubSpotPipeline> fetchPipelines() {
        HubSpotPipelinesResponse response =
                execute(PIPELINES_ENDPOINT, UnaryOperator.identity(), HubSpotPipelinesResponse.class);
        return response.results() == null ? List.of() : response.results();
    }
    /** Follows {@code paging.next.after} until HubSpot stops returning one. */
    List<HubSpotDealRecord> fetchAllDeals() {
        List<HubSpotDealRecord> all = new ArrayList<>();
        String after = null;
        do {
            HubSpotDealsPage page = fetchDealsPage(after);
            if (page.results() != null) {
                all.addAll(page.results());
            }
            after = nextCursor(page);
        } while (after != null);
        return all;
    }

    private static String nextCursor(HubSpotDealsPage page) {
        if (page.paging() == null || page.paging().next() == null) {
            return null;
        }
        return page.paging().next().after();
    }

    private HubSpotDealsPage fetchDealsPage(String after) {
        return execute(DEALS_ENDPOINT, uriBuilder -> {
            uriBuilder.queryParam("limit", pageSize)
                    .queryParam("properties", DEALS_PROPERTIES)
                    .queryParam("propertiesWithHistory", HISTORY_PROPERTY);
            if (after != null) {
                uriBuilder.queryParam("after", after);
            }
            return uriBuilder;
        }, HubSpotDealsPage.class);
    }

    private <T> T execute(String path, UnaryOperator<UriBuilder> customizer, Class<T> responseType) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return attempt(path, customizer, responseType);
            } catch (RateLimited rateLimited) {
                if (attempt >= maxRetries) {
                    throw new HubSpotClientException(
                            "HubSpot rate limit exceeded after " + attempt + " attempts calling " + path);
                }
                sleeper.accept(delayFor(attempt, rateLimited.retryAfterMillis()));
            } catch (TransientServerError transientError) {
                if (attempt >= maxRetries) {
                    throw new HubSpotClientException(
                            "HubSpot request to " + path + " kept failing with transient status "
                                    + transientError.status() + " after " + attempt + " attempts");
                }
                sleeper.accept(delayFor(attempt, null));
            } catch (TransportFailure transportFailure) {
                if (attempt >= maxRetries) {
                    throw new HubSpotClientException(
                            "HubSpot request to " + path + " failed after " + attempt
                                    + " attempts due to a transport error: "
                                    + transportFailure.getCause().getClass().getSimpleName(),
                            transportFailure.getCause());
                }
                sleeper.accept(delayFor(attempt, null));
            } catch (HubSpotClientException clientException) {
                throw clientException;
            } catch (RuntimeException other) {
                throw new HubSpotClientException(
                        "HubSpot request to " + path + " failed: " + other.getClass().getSimpleName(), other);
            }
        }
    }

    private long delayFor(int attempt, Long retryAfterMillis) {
        long delay = retryAfterMillis != null ? retryAfterMillis : initialBackoffMillis * (1L << (attempt - 1));
        return Math.min(delay, maxBackoffMillis);
    }

    private <T> T attempt(String path, UnaryOperator<UriBuilder> customizer, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> customizer.apply(uriBuilder.path(path)).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            throw new RateLimited(retryAfterMillis(response.getHeaders()));
                        }
                        if (isTransientServerError(status)) {
                            throw new TransientServerError(status);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new HubSpotClientException(
                                    "HubSpot request to " + path + " failed with status " + status);
                        }
                        return response.bodyTo(responseType);
                    });
        } catch (RestClientException restClientFailure) {
            // Two different shapes reach here under the same root cause. A
            // connect timeout / DNS failure / connection reset before any
            // response exists surfaces directly as ResourceAccessException.
            // A read timeout that hits mid-body - the connection succeeded,
            // headers came back 2xx, then the body stalled - surfaces
            // instead as a plain RestClientException from message-converter
            // extraction (see DefaultRestClient.readWithMessageConverters),
            // with the IOException one or two levels down its cause chain
            // rather than as the exception type itself. Checking the cause
            // chain for IOException catches both without also catching a
            // fatal one, like a converter failing on a malformed body.
            if (isTransportFailure(restClientFailure)) {
                throw new TransportFailure(restClientFailure);
            }
            throw restClientFailure;
        }
    }

    private static boolean isTransientServerError(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static boolean isTransportFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static Long retryAfterMillis(HttpHeaders headers) {
        String retryAfter = headers.getFirst(RETRY_AFTER_HEADER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class RateLimited extends RuntimeException {
        private final Long retryAfterMillis;

        RateLimited(Long retryAfterMillis) {
            this.retryAfterMillis = retryAfterMillis;
        }

        Long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    /** A 502/503/504 - always exponential backoff, HubSpot sends no useful {@code Retry-After} for these. */
    private static final class TransientServerError extends RuntimeException {
        private final int status;

        TransientServerError(int status) {
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /**
     * Wraps an IOException-caused {@link RestClientException} (which
     * {@link ResourceAccessException} is a subtype of) so it retries like
     * the other transient failures above.
     */
    private static final class TransportFailure extends RuntimeException {
        TransportFailure(RestClientException cause) {
            super(cause);
        }
    }
}
