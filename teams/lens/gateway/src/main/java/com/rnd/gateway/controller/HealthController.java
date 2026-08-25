package com.rnd.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoints for the Gateway (BFF).
 * 
 * Gateway is a BFF (Backend For Frontend) - only responsible for its own health.
 * Each downstream service (Bridge, Chain, Inbox, Mind) maintains its own health.
 * 
 * GET /health - Gateway health status only
 * GET /health/ready - Gateway readiness check only
 */
@RestController
public class HealthController {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);
    
    public HealthController() {
        // No WebClient needed - Gateway only reports its own health
    }
    
    /**
     * Gateway health check - returns Gateway status only
     */
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
     * Gateway readiness check - returns Gateway readiness only
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> healthReady() {
        logger.debug("Readiness check requested");
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "gateway");
        response.put("ready", true);
        response.put("timestamp", Instant.now().toString());
        
        logger.info("Gateway readiness: UP");
        return ResponseEntity.ok(response);
    }
}
