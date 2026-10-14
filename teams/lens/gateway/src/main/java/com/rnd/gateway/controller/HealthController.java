package com.rnd.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;

/**
 * Health check endpoints for the Gateway.
 *
 * GET /health       — Gateway liveness only. Answers "is this process alive?"
 *                     Does NOT call BFF. Docker/orchestrator uses this to decide
 *                     whether to restart the container — restarting the Gateway
 *                     cannot fix a broken BFF, so BFF state must not affect this.
 *
 * GET /health/ready — Gateway readiness + BFF dependency status.
 *                     Answers "is this Gateway ready to serve traffic?"
 *                     Calls BFF. Use this before routing traffic to the Gateway.
 */
@RestController
@Tag(name = "Health", description = "Gateway liveness/readiness — never checked by consumers other than orchestration")
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 2;
    private static final int HEALTH_CHECK_THREAD_POOL_SIZE = 4;

    @Value("${bff.service.url:http://bff-service:8080}")
    private String bffServiceUrl;

    @Autowired(required = false)
    private RestTemplate restTemplate;
    
    private final ExecutorService healthCheckExecutor = Executors.newFixedThreadPool(
        HEALTH_CHECK_THREAD_POOL_SIZE,
        r -> {
            Thread t = new Thread(r, "gateway-health-check-" + System.nanoTime());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> 
                logger.error("Uncaught exception in health check thread: {}", ex.getMessage(), ex)
            );
            return t;
        }
    );

    /**
     * Gracefully shut down health check executor on application shutdown.
     * Prevents resource leaks and ensures clean termination.
     */
    @PreDestroy
    public void shutdown() {
        if (healthCheckExecutor != null && !healthCheckExecutor.isShutdown()) {
            logger.info("Shutting down health check executor");
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Health check executor did not terminate within 5 seconds, forcing shutdown");
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted waiting for health check executor shutdown", e);
                healthCheckExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Gateway liveness check — Gateway process only, no downstream calls.
     *
     * Always returns 200 UP as long as the Gateway process is running.
     * Must not depend on BFF or any other service.
     */
    @Operation(summary = "Gateway liveness", description = "Always UP if the process is running. Never calls BFF.")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        logger.debug("Health check requested");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "gateway");
        response.put("version", "0.1.0");
        response.put("timestamp", Instant.now().toString());

        logger.info("Gateway health: UP");
        return ResponseEntity.ok(response);
    }

    /**
     * Gateway readiness check — Gateway + BFF dependency.
     *
     * Returns 200 regardless of BFF state (Gateway itself is ready).
     * Callers should inspect dependencies.bff to decide whether to route traffic.
     * If dependencies.bff = "DOWN", expect 503/504 on /api/** requests.
     */
    @Operation(summary = "Gateway readiness", description = "Includes BFF dependency status — check dependencies.bff.")
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> healthReady() {
        logger.debug("Readiness check requested");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "gateway");
        response.put("ready", true);
        response.put("timestamp", Instant.now().toString());

        // Check BFF availability and include in readiness response
        Map<String, Object> dependencies = new LinkedHashMap<>();
        String bffStatus = checkBFFHealth();
        dependencies.put("bff", bffStatus);
        dependencies.put("bff_url", bffServiceUrl + "/health");
        dependencies.put("bff_ready", "UP".equals(bffStatus));
        response.put("dependencies", dependencies);

        logger.info("Gateway readiness: UP, BFF: {}", bffStatus);
        return ResponseEntity.ok(response);
    }

    /**
     * Check if BFF service is available.
     * Only called from /health/ready — never from /health.
     * Health check calls timeout quickly to avoid blocking orchestration probes.
     * Uses dedicated thread pool to prevent exhaustion of common ForkJoinPool.
     *
     * @return "UP" if BFF responds 200 within 2 seconds, "DOWN" otherwise, "UNKNOWN" if RestTemplate unavailable
     */
    private String checkBFFHealth() {
        if (restTemplate == null) {
            logger.warn("RestTemplate not available, cannot check BFF health");
            return "UNKNOWN";
        }

        long startTime = System.currentTimeMillis();
        try {
            // Use dedicated executor with bounded threads + timeout to prevent blocking
            CompletableFuture<ResponseEntity<String>> future = 
                CompletableFuture.supplyAsync(() -> 
                    restTemplate.getForEntity(bffServiceUrl + "/health", String.class),
                    healthCheckExecutor
                );
            
            ResponseEntity<String> response = future.orTimeout(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS).get();
            long responseTime = System.currentTimeMillis() - startTime;

            if (response.getStatusCode() == HttpStatus.OK) {
                logger.debug("BFF is UP (response time: {}ms)", responseTime);
                return "UP";
            } else {
                logger.warn("BFF returned status: {}", response.getStatusCode());
                return "DOWN";
            }

        } catch (RestClientException e) {
            logger.warn("BFF health check failed: {} ({})", e.getClass().getSimpleName(), e.getMessage());
            return "DOWN";
        } catch (ExecutionException e) {
            logger.warn("BFF health check execution failed: {}", e.getMessage());
            return "DOWN";
        } catch (InterruptedException e) {
            logger.warn("BFF health check interrupted");
            Thread.currentThread().interrupt();
            return "DOWN";
        } catch (Exception e) {
            logger.error("Unexpected error checking BFF health", e);
            return "UNKNOWN";
        }
    }
}
