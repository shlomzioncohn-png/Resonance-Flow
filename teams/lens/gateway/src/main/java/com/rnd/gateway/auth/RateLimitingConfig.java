package com.rnd.gateway.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration for rate limiting on auth endpoints.
 * 
 * Rate limits per IP:
 * - Login: 5 failed attempts per minute
 * - Register: 10 registrations per hour
 * 
 * Uses in-memory Bucket4j. For multi-replica deployments,
 * this should be replaced with Redis or similar shared store.
 */
@Configuration
public class RateLimitingConfig {

    /**
     * Bucket for login attempts.
     * Limit: 5 requests per minute per IP
     */
    @Bean
    public RateLimiter loginRateLimiter() {
        return new RateLimiter(
                Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1))),
                "login"
        );
    }

    /**
     * Bucket for registration attempts.
     * Limit: 10 requests per hour per IP
     */
    @Bean
    public RateLimiter registerRateLimiter() {
        return new RateLimiter(
                Bandwidth.classic(10, Refill.intervally(10, Duration.ofHours(1))),
                "register"
        );
    }

    /**
     * Rate limiter container.
     * Holds per-IP buckets and enforces limits.
     */
    public static class RateLimiter {
        private final Bandwidth bandwidth;
        private final String name;
        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

        public RateLimiter(Bandwidth bandwidth, String name) {
            this.bandwidth = bandwidth;
            this.name = name;
        }

        /**
         * Check if IP has available tokens.
         * Consumes 1 token if available.
         * 
         * @param ip client IP address
         * @return true if token consumed (allowed), false if limit exceeded
         */
        public boolean allowRequest(String ip) {
            Bucket bucket = buckets.computeIfAbsent(ip, key -> 
                    Bucket4j.builder()
                            .addLimit(bandwidth)
                            .build()
            );
            return bucket.tryConsume(1);
        }

        /**
         * Get remaining tokens for IP.
         * Useful for response headers (Retry-After, etc).
         * 
         * @param ip client IP address
         * @return remaining tokens, or 0 if bucket doesn't exist
         */
        public long getRemainingTokens(String ip) {
            Bucket bucket = buckets.get(ip);
            if (bucket == null) {
                return bandwidth.getCapacity();
            }
            return bucket.getAvailableTokens();
        }

        /**
         * Get name of this limiter (for logging).
         */
        public String getName() {
            return name;
        }
    }
}
