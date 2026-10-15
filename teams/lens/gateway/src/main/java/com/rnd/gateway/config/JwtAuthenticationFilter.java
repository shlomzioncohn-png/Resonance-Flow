package com.rnd.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that extracts JWT from cookies (or Authorization headers)
 * and sets it in Spring Security context.
 * 
 * This allows protected endpoints to validate tokens from HttpOnly cookies.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final CookieJwtAuthenticationConverter converter;

    public JwtAuthenticationFilter(CookieJwtAuthenticationConverter converter) {
        this.converter = converter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // Try to extract authentication from cookie or Authorization header
        Authentication authentication = converter.extractAuthentication(request);
        
        if (authentication != null) {
            logger.debug("Setting authentication in SecurityContext for: {}", authentication.getName());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        
        filterChain.doFilter(request, response);
    }
}
