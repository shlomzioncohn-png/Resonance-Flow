package com.rnd.gateway.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Extracts JWT from HttpOnly cookies instead of Authorization headers.
 * 
 * Looks for "accessToken" cookie in the request.
 * Falls back to Authorization: Bearer header if cookie not found.
 */
public class CookieJwtAuthenticationConverter {

    private static final Logger logger = LoggerFactory.getLogger(CookieJwtAuthenticationConverter.class);
    private final JwtDecoder jwtDecoder;

    public CookieJwtAuthenticationConverter(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * Extract authentication from request cookies or Authorization header.
     */
    public Authentication extractAuthentication(HttpServletRequest request) {
        // Try to get token from cookie first
        String token = extractTokenFromCookie(request);
        
        // Fall back to Authorization header
        if (token == null) {
            token = extractTokenFromHeader(request);
        }
        
        if (token == null) {
            logger.debug("No JWT token found in cookie or Authorization header");
            return null;
        }
        
        try {
            Jwt jwt = jwtDecoder.decode(token);
            logger.debug("JWT decoded successfully, sub: {}", jwt.getSubject());
            
            // Create authentication with empty authorities (we'll use role from JWT claims)
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(jwt, token, authorities);
            authentication.setDetails(jwt);
            
            return authentication;
        } catch (JwtException e) {
            logger.warn("Failed to decode JWT: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract JWT from "accessToken" cookie.
     */
    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, "accessToken");
        if (cookie != null) {
            logger.debug("Found accessToken cookie");
            return cookie.getValue();
        }
        return null;
    }

    /**
     * Extract JWT from Authorization: Bearer header.
     */
    private String extractTokenFromHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            logger.debug("Found Authorization Bearer header");
            return header.substring(7);
        }
        return null;
    }
}
