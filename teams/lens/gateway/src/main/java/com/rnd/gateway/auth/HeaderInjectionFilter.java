package com.rnd.gateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that extracts JWT claims and stores them in CurrentUserContext.
 * This makes user info available to BFF clients via dependency injection.
 * 
 * The filter runs after Spring Security's authentication filter, so the JWT
 * has already been validated at this point.
 * 
 * For requests without JWT (public endpoints), CurrentUserContext remains empty.
 */
@Component
public class HeaderInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HeaderInjectionFilter.class);
    
    private final CurrentUserContext currentUserContext;

    public HeaderInjectionFilter(CurrentUserContext currentUserContext) {
        this.currentUserContext = currentUserContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Extract JWT from SecurityContext (already authenticated by Spring Security)
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            currentUserContext.setFromJwt(jwt);
            log.debug("Set current user context from JWT: sub={}, role={}",
                    jwt.getSubject(), jwt.getClaimAsString("role"));
        }

        filterChain.doFilter(request, response);
    }
}
