package com.rnd.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoints for the Gateway.
 * 
 * Gateway checks its own health AND the health of BFF (Backend For Frontend).
 * Each downstream service (Bridge, Chain, Inbox, Mind) maintains its own health.
 * 
 * GET /health - Gateway health + BFF dependency status
 * GET /health/ready - Gateway readiness + BFF availability
 */
@RestController
public class HealthController {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);
    private static final String BFF_URL = "http://bff-service:8080/health";
    
    @Autowired(required = false)
    private RestTemplate restTemplate;
    
    /**
     * Gateway health check - checks Gateway status AND BFF dependency
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        logger.debug("Health check requested");
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "gateway");
        response.put("version", "0.1.0");
        response.put("timestamp", Instant.now().toString());
        
        // Check BFF availability
        Map<String, Object> dependencies = new LinkedHashMap<>();
        String bffStatus = checkBFFHealth();
        dependencies.put("bff", bffStatus);
        dependencies.put("bff_url", BFF_URL);
        
        response.put("dependencies", dependencies);
        
        logger.info("Gateway health: UP, BFF: {}", bffStatus);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Gateway readiness check - checks Gateway readiness AND BFF availability
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> healthReady() {
        logger.debug("Readiness check requested");
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "gateway");
        response.put("ready", true);
        response.put("timestamp", Instant.now().toString());
        
        // Check BFF availability for readiness
        Map<String, Object> dependencies = new LinkedHashMap<>();
        String bffStatus = checkBFFHealth();
        dependencies.put("bff", bffStatus);
        dependencies.put("bff_ready", "UP".equals(bffStatus));
        
        response.put("dependencies", dependencies);
        
        logger.info("Gateway readiness: UP, BFF: {}", bffStatus);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Check if BFF service is available
     * @return "UP" if BFF is reachable, "DOWN" otherwise
     */
    private String checkBFFHealth() {
        if (restTemplate == null) {
            logger.warn("RestTemplate not available, cannot check BFF health");
            return "UNKNOWN";
        }
        
        try {
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.getForEntity(BFF_URL, String.class);
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
        } catch (Exception e) {
            logger.error("Unexpected error checking BFF health", e);
            return "UNKNOWN";
        }
    }
}
