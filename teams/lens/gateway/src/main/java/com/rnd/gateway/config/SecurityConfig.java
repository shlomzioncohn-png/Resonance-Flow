package com.rnd.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the Gateway.
 *
 * Current state (local / dev): all endpoints are permitted without
 * authentication so the end-to-end flow can be verified without a
 * real JWT issuer. 
 *?""
 *
 * TODO: when Auth & Identity is implemented, restrict /api/** to
 * requests that carry a valid JWT and extract the userId from the
 * "sub" claim instead of using the MOCK_USER_ID in WorkspaceController.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — Gateway is a stateless API, no session cookies
            .csrf(AbstractHttpConfigurer::disable)
            // Permit all requests; JWT enforcement is a future task
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // Disable HTTP Basic auto-config (Spring Boot default)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
