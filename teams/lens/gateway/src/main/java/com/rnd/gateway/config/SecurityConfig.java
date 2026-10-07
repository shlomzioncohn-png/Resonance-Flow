package com.rnd.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for the Gateway.
 *
 * The Gateway is the single authentication point of the system: every
 * /api/** request must carry a valid Keycloak-issued JWT
 * (Authorization: Bearer &lt;token&gt;). Signature (via Keycloak's JWKS),
 * expiry and issuer are validated by Spring's OAuth2 resource server —
 * see spring.security.oauth2.resourceserver.jwt in application.yml.
 * Requests without a valid token are rejected here with 401 and never
 * reach the BFF.
 *
 * Everything outside /api/** (health checks, Swagger docs, unknown routes
 * that 404) stays public.
 *
 * Enforcement is switched by app.auth.enabled (APP_AUTH_ENABLED). It's off
 * by default until the frontend logs in through Keycloak and sends the token
 * (Auth stage 3) — otherwise every screen would get 401. While off, /api/**
 * is open and any Authorization header is ignored, same as before Keycloak.
 *
 * TODO (Auth stage 2): extract userId from the validated JWT and forward it
 * to BFF instead of the MOCK_USER_ID in WorkspaceController/ManagerUsersController
 * — role is no longer a TODO: /api/manager/users/** is real, JWT-enforced
 * hasRole("MANAGER") below, backed by the "lens" realm's actual manager/employee
 * roles (teams/lens/keycloak/realm-lens.json), via KeycloakRoleConverter.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(CorsConfigurationSource corsConfigurationSource) {
        this.corsConfigurationSource = corsConfigurationSource;
    }

    /**
     * Maps Keycloak's realm_access.roles claim to ROLE_* authorities — see
     * KeycloakRoleConverter's javadoc. Only used when app.auth.enabled=true.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityErrorHandler securityErrorHandler,
                                           JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            // Enable CORS with the origin whitelist from CorsConfig, so browser preflight
            // (OPTIONS, which never carries a token) isn't rejected as 401
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            // Disable CSRF — Gateway is a stateless API, no session cookies
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (authEnabled) {
            http
                .authorizeHttpRequests(auth -> auth
                    // Most specific first — Spring Security uses first-match-wins.
                    .requestMatchers("/api/manager/users/**").hasRole("MANAGER")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                    .authenticationEntryPoint(securityErrorHandler)
                    .accessDeniedHandler(securityErrorHandler));
        } else {
            // No resource server at all, so a stale or invalid token can't cause a 401 either.
            // Known limitation while auth is off (the repo-wide default today): hasRole("MANAGER")
            // above never runs, so /api/manager/users/** is reachable by anyone, same as every
            // other /api/** route — see SecurityValidationConfig's dev-mode warning.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        http
            // Return JSON 401/403 in the contract's error format (not Spring's defaults)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(securityErrorHandler)
                .accessDeniedHandler(securityErrorHandler))
            // Disable HTTP Basic auto-config (Spring Boot default)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
