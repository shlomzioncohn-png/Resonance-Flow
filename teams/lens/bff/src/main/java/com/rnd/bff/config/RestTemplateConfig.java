package com.rnd.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate bean with explicit connect and read timeouts, mirroring
 * com.rnd.gateway.GatewayApplication's bean (same reasoning: without
 * timeouts a hung downstream pins a BFF thread per caller indefinitely).
 * Used by InboxUserClient — the BFF's first real outbound HTTP call.
 *
 *   INBOX_CONNECT_TIMEOUT_MS (default 2 000 ms)
 *   INBOX_READ_TIMEOUT_MS    (default 10 000 ms)
 *
 * Deliberately its own @Configuration class, not a @Bean method on
 * BffApplication: @WebMvcTest slices (e.g. WorkspaceControllerTest) use
 * BffApplication as their root @SpringBootConfiguration, so a @Bean defined
 * directly on it would load even in those narrow slices and fail there with
 * no RestTemplateBuilder bean available (RestTemplateAutoConfiguration isn't
 * part of a WebMvcTest slice). A separate @Configuration class is excluded
 * by the slice's component scan like any other, same as this package's
 * other config classes.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${inbox.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${inbox.read-timeout-ms:10000}") long readTimeoutMs) {
        return builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
