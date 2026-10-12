package com.rnd.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.gateway.dto.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes 401/403 responses from the security filter chain in the same JSON
 * format GlobalExceptionHandler uses (see GATEWAY_CONTRACT.md).
 *
 * GlobalExceptionHandler can't do this itself: authentication runs in a
 * servlet filter, before any controller, so @RestControllerAdvice never
 * sees these errors.
 *
 * The status code and the standard WWW-Authenticate header
 * (e.g. Bearer error="invalid_token") are set by Spring's bearer-token
 * handlers; this class only adds the JSON body.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger logger = LoggerFactory.getLogger(SecurityErrorHandler.class);

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401 — missing, malformed, forged, expired or wrong-issuer token. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        logger.warn("Rejected unauthenticated request - Path: {}, Reason: {}",
                request.getRequestURI(), authException.getMessage());

        bearerEntryPoint.commence(request, response, authException);
        writeBody(response, new ErrorResponse(
                "UNAUTHORIZED",
                "Authentication required. Please provide valid credentials.",
                request.getRequestURI()));
    }

    /** 403 — valid token, but the user isn't allowed to access this resource. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        logger.warn("Forbidden access attempt - Path: {}", request.getRequestURI());

        bearerAccessDeniedHandler.handle(request, response, accessDeniedException);
        writeBody(response, new ErrorResponse(
                "FORBIDDEN",
                "You don't have permission to access this resource.",
                request.getRequestURI()));
    }

    private void writeBody(HttpServletResponse response, ErrorResponse error) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
