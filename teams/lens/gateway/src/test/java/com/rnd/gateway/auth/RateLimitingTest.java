package com.rnd.gateway.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for rate limiting.
 * 
 * Tests Bucket4j-based rate limiting for auth endpoints.
 */
public class RateLimitingTest {

    private RateLimitingConfig.RateLimiter rateLimiter;

    @BeforeEach
    public void setUp() {
        // Create a rate limiter with 5 requests per minute for testing
        Bandwidth bandwidth = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)));
        rateLimiter = new RateLimitingConfig.RateLimiter(bandwidth, "test");
    }

    @Test
    public void testAllowRequestWithinLimit() {
        // Given
        String ip = "192.168.1.100";

        // When & Then
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.allowRequest(ip), 
                "Should allow request " + (i + 1) + " within limit");
        }
    }

    @Test
    public void testDenyRequestExceedingLimit() {
        // Given
        String ip = "192.168.1.100";

        // When
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest(ip);
        }

        // Then
        assertFalse(rateLimiter.allowRequest(ip), 
            "Should deny 6th request exceeding limit");
    }

    @Test
    public void testDifferentIPsIndependent() {
        // Given
        String ip1 = "192.168.1.100";
        String ip2 = "192.168.1.101";

        // When
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest(ip1);
        }

        // Then
        assertFalse(rateLimiter.allowRequest(ip1), "IP1 should be limited");
        assertTrue(rateLimiter.allowRequest(ip2), "IP2 should have separate limit");
    }

    @Test
    public void testRemainingTokensTracking() {
        // Given
        String ip = "192.168.1.100";

        // When & Then
        long initialTokens = rateLimiter.getRemainingTokens(ip);
        assertTrue(initialTokens >= 4 && initialTokens <= 5, 
            "Should have approximately 5 tokens initially, got: " + initialTokens);

        rateLimiter.allowRequest(ip);
        long afterOne = rateLimiter.getRemainingTokens(ip);
        assertTrue(afterOne >= 3 && afterOne <= 4, 
            "Should have approximately 4 tokens after 1 request, got: " + afterOne);

        for (int i = 0; i < 3; i++) {
            rateLimiter.allowRequest(ip);
        }
        long afterFive = rateLimiter.getRemainingTokens(ip);
        assertTrue(afterFive >= 0 && afterFive <= 1, 
            "Should have approximately 0 tokens after 5 requests, got: " + afterFive);
    }
}
