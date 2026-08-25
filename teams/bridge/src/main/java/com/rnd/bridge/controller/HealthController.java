package com.rnd.bridge.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoints for the Bridge service.
 */
@RestController
public class HealthController {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);
    
    /**
     * Bridge service health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        logger.debug("Health check requested");
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "bridge");
        response.put("version", "0.1.0");
        response.put("timestamp", Instant.now().toString());
        
        logger.info("Bridge health: UP");
        return ResponseEntity.ok(response);
    }
    
    /**
     * Bridge service readiness check
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> healthReady() {
        logger.debug("Readiness check requested");
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "bridge");
        response.put("ready", true);
        response.put("timestamp", Instant.now().toString());
        
        logger.info("Bridge readiness: UP");
        return ResponseEntity.ok(response);
    }
}
