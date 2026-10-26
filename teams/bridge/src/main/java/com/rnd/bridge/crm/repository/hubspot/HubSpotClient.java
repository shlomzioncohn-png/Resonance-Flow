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
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Talks to the HubSpot CRM v3 API: pipeline lookup and cursor-following deal pagination.
 * Retries 429/502-504/transport failures up to {@code maxRetries} with backoff; any other non-2xx is fatal, not retried.
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

    Optional<HubSpotDealRecord> fetchDeal(String dealId) {
        return executeOptional(DEALS_ENDPOINT + "/" + dealId, uriBuilder -> uriBuilder
                        .queryParam("properties", DEALS_PROPERTIES)
                        .queryParam("propertiesWithHistory", HISTORY_PROPERTY),
                HubSpotDealRecord.class);
    }

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
        return withRetry(path, () -> attempt(path, customizer, responseType));
    }

    private <T> Optional<T> executeOptional(String path, UnaryOperator<UriBuilder> customizer, Class<T> responseType) {
        return withRetry(path, () -> attemptOptional(path, customizer, responseType));
    }

    private <T> T withRetry(String path, Supplier<T> action) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return action.get();
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
                                    "HubSpot request to " + path + " failed with status " + status
                                            + errorBodySuffix(response));
                        }
                        return response.bodyTo(responseType);
                    });
        } catch (RestClientException restClientFailure) {
            // Connect/DNS failures and mid-body read timeouts both surface as RestClientException - IOException cause chain catches both.
            if (isTransportFailure(restClientFailure)) {
                throw new TransportFailure(restClientFailure);
            }
            throw restClientFailure;
        }
    }

    private <T> Optional<T> attemptOptional(String path, UnaryOperator<UriBuilder> customizer, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> customizer.apply(uriBuilder.path(path)).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return Optional.<T>empty();
                        }
                        if (status == 429) {
                            throw new RateLimited(retryAfterMillis(response.getHeaders()));
                        }
                        if (isTransientServerError(status)) {
                            throw new TransientServerError(status);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new HubSpotClientException(
                                    "HubSpot request to " + path + " failed with status " + status
                                            + errorBodySuffix(response));
                        }
                        return Optional.of(response.bodyTo(responseType));
                    });
        } catch (RestClientException restClientFailure) {
            if (isTransportFailure(restClientFailure)) {
                throw new TransportFailure(restClientFailure);
            }
            throw restClientFailure;
        }
    }

    // Best-effort only - a failure here must not mask the real fatal-status exception.
    private static String errorBodySuffix(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            String body = response.bodyTo(String.class);
            if (body == null || body.isBlank()) {
                return "";
            }
            String trimmed = body.strip();
            return ": " + (trimmed.length() > 500 ? trimmed.substring(0, 500) + "..." : trimmed);
        } catch (RuntimeException e) {
            return "";
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

    /** Wraps an IOException-caused {@link RestClientException} so it retries like the other transient failures. */
    private static final class TransportFailure extends RuntimeException {
        TransportFailure(RestClientException cause) {
            super(cause);
        }
    }
}
