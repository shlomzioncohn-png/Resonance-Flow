package com.rnd.bff.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /health — plain liveness check, in the shape the Gateway and
 * GATEWAY_CONTRACT.md §7 ("Your Team's Responsibility") document for every
 * downstream service. Spring Boot Actuator's own /actuator/health exists too
 * (different shape, different path) but Gateway's HealthController calls
 * {bff.service.url}/health specifically — this is that endpoint.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "bff");
        response.put("version", "0.1.0");
        response.put("timestamp", Instant.now().toString());
        return response;
    }
}
