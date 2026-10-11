package com.rnd.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS Configuration for the BFF.
 *
 * Allows Frontend (localhost:3000/3001) to call BFF endpoints directly.
 * In production, replace localhost origins with actual frontend domain.
 *
 * Note: In normal operation, Frontend calls Gateway, and Gateway calls BFF.
 * However, during development and testing, Frontend may call BFF directly.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Allow Frontend requests from localhost (dev mode)
        configuration.addAllowedOrigin("http://localhost:3000");
        configuration.addAllowedOrigin("http://localhost:3001");
        configuration.addAllowedOrigin("http://localhost:3002");
        configuration.addAllowedOrigin("http://127.0.0.1:3000");
        configuration.addAllowedOrigin("http://127.0.0.1:3001");
        configuration.addAllowedOrigin("http://127.0.0.1:3002");
        
        // Allow standard HTTP methods
        configuration.addAllowedMethod("GET");
        configuration.addAllowedMethod("POST");
        configuration.addAllowedMethod("PATCH");
        configuration.addAllowedMethod("PUT");
        configuration.addAllowedMethod("DELETE");
        configuration.addAllowedMethod("OPTIONS");
        
        // Allow all headers
        configuration.addAllowedHeader("*");
        
        // Allow credentials (cookies, auth headers)
        configuration.setAllowCredentials(true);
        
        // Cache preflight response for 1 hour
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply CORS to all endpoints
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}
