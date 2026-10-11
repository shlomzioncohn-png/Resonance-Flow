package com.rnd.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for the BFF.
 *
 * Current state (local / dev): all endpoints are permitted without
 * authentication so the end-to-end flow can be verified.
 *
 * TODO: when Auth & Identity is implemented, validate X-User-Id header
 * and enforce proper authorization.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(CorsConfigurationSource corsConfigurationSource) {
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Enable CORS with configured origin whitelist
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            // Disable CSRF — BFF is a stateless API, no session cookies
            .csrf(AbstractHttpConfigurer::disable)
            // Permit all requests; JWT enforcement is a future task
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // Disable HTTP Basic auto-config (Spring Boot default)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
