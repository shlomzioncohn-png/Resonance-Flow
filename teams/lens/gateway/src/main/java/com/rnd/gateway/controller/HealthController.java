package com.rnd.gateway.controller;

import com.rnd.gateway.dto.HealthResponse;
import com.rnd.gateway.dto.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Health check endpoints for the Gateway.
 * 
 * GET /health - Full health status of gateway and all services
 * GET /health/ready - Readiness check (all services ready?)
 */
@RestController
public class HealthController {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);
    
    private final WebClient webClient;
    
    @Value("${services.bridge.url:http://bridge:8080}")
    private String bridgeUrl;
    
    @Value("${services.mind.url:http://mind:8080}")
    private String mindUrl;
    
    @Value("${services.inbox.url:http://inbox:8080}")
    private String inboxUrl;
    
    @Value("${services.chain.url:http://chain:8080}")
    private String chainUrl;
    
    public HealthController(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("")
                .build();
    }
    
    /**
     * Full health check: Gateway + all downstream services
     * Returns 200 even if some services are down, but marks them
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        logger.info("Health check requested");
        
        // Define all services to check
        List<ServiceConfig> services = Arrays.asList(
            new ServiceConfig("bridge", bridgeUrl + "/health"),
            new ServiceConfig("mind", mindUrl + "/health"),
            new ServiceConfig("inbox", inboxUrl + "/health"),
            new ServiceConfig("chain", chainUrl + "/health")
        );
        
        // Check each service in parallel
        List<ServiceHealth> healthList = services.parallelStream()
            .map(this::checkService)
            .collect(Collectors.toList());
        
        // Determine overall status
        long downCount = healthList.stream().filter(h -> "DOWN".equals(h.getStatus())).count();
        String overallStatus = downCount == 0 ? "UP" : (downCount == healthList.size() ? "DOWN" : "DEGRADED");
        String message = String.format("%d/%d services healthy", 
            healthList.size() - downCount, 
            healthList.size());
        
        HealthResponse response = new HealthResponse(overallStatus, message, healthList);
        
        logger.info("Health check complete: {}", overallStatus);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Readiness check: Are all required services ready?
     * Returns 200 only if ALL services are UP
     * Returns 503 if any service is DOWN
     */
    @GetMapping("/health/ready")
    public ResponseEntity<HealthResponse> healthReady() {
        logger.info("Readiness check requested");
        
        List<ServiceConfig> services = Arrays.asList(
            new ServiceConfig("bridge", bridgeUrl + "/health"),
            new ServiceConfig("mind", mindUrl + "/health"),
            new ServiceConfig("inbox", inboxUrl + "/health"),
            new ServiceConfig("chain", chainUrl + "/health")
        );
        
        List<ServiceHealth> healthList = services.parallelStream()
            .map(this::checkService)
            .collect(Collectors.toList());
        
        long downCount = healthList.stream().filter(h -> "DOWN".equals(h.getStatus())).count();
        
        if (downCount > 0) {
            String message = String.format("Not ready: %d/%d services down", downCount, healthList.size());
            HealthResponse response = new HealthResponse("DOWN", message, healthList);
            logger.warn("Readiness check failed: {}", message);
            return ResponseEntity.status(503).body(response);
        }
        
        HealthResponse response = new HealthResponse("UP", "All services ready", healthList);
        logger.info("Readiness check passed: All services UP");
        return ResponseEntity.ok(response);
    }
    
    /**
     * Check health of a single service (non-blocking)
     */
    private ServiceHealth checkService(ServiceConfig config) {
        ServiceHealth health = new ServiceHealth(config.name, "UNKNOWN", config.url);
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Use a simple synchronous check with timeout
            // Since we're on a request thread, we can block briefly
            String response = webClient
                .get()
                .uri(config.url)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> {
                    logger.debug("Service {} health check error: {}", config.name, e.getMessage());
                })
                .timeout(java.time.Duration.ofMillis(500))
                .onErrorReturn("") // Return empty string on error
                .block();
            
            long responseTime = System.currentTimeMillis() - startTime;
            
            if (response != null) {
                health.setStatus("UP");
            } else {
                health.setStatus("DOWN");
                health.setLastError("No response");
            }
            
            health.setResponseTime(responseTime);
            logger.debug("Service {} health: {} ({}ms)", config.name, health.getStatus(), responseTime);
            
        } catch (Exception e) {
            health.setStatus("DOWN");
            health.setLastError(e.getClass().getSimpleName());
            logger.debug("Service {} health: DOWN - {}", config.name, e.getClass().getSimpleName());
        }
        
        return health;
    }
    
    /**
     * Internal config holder for service URL
     */
    private static class ServiceConfig {
        String name;
        String url;
        
        ServiceConfig(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
