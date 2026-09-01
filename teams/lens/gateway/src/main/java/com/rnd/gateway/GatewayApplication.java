package com.rnd.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
    
    /**
     * RestTemplate bean with explicit connect and read timeouts.
     *
     * Without timeouts a hung BFF would pin a Gateway thread per caller
     * until the thread pool is exhausted, taking down the entire Gateway.
     *
     * connect-timeout: time to establish the TCP connection to BFF.
     * read-timeout:    time to wait for BFF to return the first byte after connecting.
     *
     * Both values are configurable via environment variables so they can be
     * tuned per environment without recompiling:
     *   BFF_CONNECT_TIMEOUT_MS (default 2 000 ms)
     *   BFF_READ_TIMEOUT_MS    (default 10 000 ms)
     */
    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${bff.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${bff.read-timeout-ms:10000}") long readTimeoutMs) {
        return builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
