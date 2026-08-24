package com.rnd.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security configuration for the Gateway (Reactive).
 * 
 * Local profile: Disables security for development
 * Production profile: Enables JWT/OAuth authentication
 */
@Configuration
public class SecurityConfig {
    
    /**
     * Local development: Disable all security
     */
    @Bean
    @Profile("local")
    public SecurityWebFilterChain securityFilterChainLocal(ServerHttpSecurity http) {
        http
            .csrf().disable()
            .authorizeExchange()
            .anyExchange().permitAll();
        
        return http.build();
    }
    
    /**
     * Production: Secure all endpoints (to be implemented with JWT)
     */
    @Bean
    @Profile("prod")
    public SecurityWebFilterChain securityFilterChainProd(ServerHttpSecurity http) {
        http
            .csrf().disable()
            .authorizeExchange()
            .pathMatchers("/health/**").permitAll()
            .anyExchange().authenticated();
        
        return http.build();
    }
}
