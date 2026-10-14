package com.rnd.gateway.exception;
import com.rnd.gateway.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * Global exception handler for Gateway.
 * 
 * Catches all exceptions and returns unified error responses
 * matching GATEWAY_CONTRACT.md error format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    /**
     * Handle 404 NOT_FOUND errors from Spring servlet dispatcher.
     * Triggered when no handler is found for a request path.
     * This takes precedence over ResponseStatusException for servlet-level 404s.
     */
    @ExceptionHandler(value = {NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandlerFoundException(
            NoHandlerFoundException ex, WebRequest request) {
        
        String path = ex.getRequestURL();
        logger.warn("No handler found - Path: {}, Method: {}", path, ex.getHttpMethod());
        
        ErrorResponse error = new ErrorResponse(
                "NOT_FOUND",
                "Route not found. Check the request path and HTTP method.",
                path
        );
        
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * Handle 404 NOT_FOUND errors from downstream services (via ResponseStatusException).
     * Triggered when Gateway receives 404 from BFF or other ResponseStatusException errors.
     * NoHandlerFoundException has higher priority for servlet-level 404s.
     */
    @ExceptionHandler(value = {ResponseStatusException.class})
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.warn("ResponseStatus error: {} - Path: {}", ex.getReason(), path);
        
        // Get HTTP status code
        HttpStatus httpStatus = HttpStatus.resolve(ex.getStatusCode().value());
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        
        String errorCode = httpStatus.name();
        String message = ex.getReason() != null ? ex.getReason() : httpStatus.getReasonPhrase();
        
        ErrorResponse error = new ErrorResponse(
                errorCode,
                message,
                path
        );
        
        return new ResponseEntity<>(error, httpStatus);
    }
    
    /**
     * Handle service unavailable errors (503).
     * Triggered when downstream service is not responding.
     */
    @ExceptionHandler(value = {ServiceUnavailableException.class})
    public ResponseEntity<ErrorResponse> handleServiceUnavailableException(
            ServiceUnavailableException ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.error("Service unavailable: {} - Path: {}", ex.getMessage(), path);
        
        ErrorResponse error = new ErrorResponse(
                "SERVICE_UNAVAILABLE",
                "Downstream service is unavailable. Please try again later.",
                path,
                "Service: " + ex.getServiceName()
        );
        
        return new ResponseEntity<>(error, HttpStatus.SERVICE_UNAVAILABLE);
    }
    
    /**
     * Handle gateway timeout errors (504).
     * Triggered when downstream service takes too long to respond.
     */
    @ExceptionHandler(value = {GatewayTimeoutException.class})
    public ResponseEntity<ErrorResponse> handleGatewayTimeoutException(
            GatewayTimeoutException ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.error("Gateway timeout: {} - Path: {}", ex.getMessage(), path);
        
        ErrorResponse error = new ErrorResponse(
                "TIMEOUT",
                "Request to downstream service timed out. Service may be overloaded.",
                path,
                "Service: " + ex.getServiceName() + ", Timeout: " + ex.getTimeoutMs() + "ms"
        );
        
        return new ResponseEntity<>(error, HttpStatus.GATEWAY_TIMEOUT);
    }
    
    /**
     * Handle validation errors (400).
     * Triggered when @Valid fails on request body fields.
     */
    @ExceptionHandler(value = {MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {

        String path = getRequestPath(request);
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));

        logger.warn("Validation error - Path: {}, Details: {}", path, details);

        ErrorResponse error = new ErrorResponse(
                "BAD_REQUEST",
                "Invalid request parameters",
                path,
                details
        );

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle malformed JSON body (400).
     * Triggered when request body is not valid JSON.
     */
    @ExceptionHandler(value = {HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.warn("Malformed request body - Path: {}, Cause: {}", path, ex.getMostSpecificCause().getMessage());
        
        ErrorResponse error = new ErrorResponse(
                "BAD_REQUEST",
                "Malformed request body. Request body must be valid JSON.",
                path,
                ex.getMostSpecificCause().getMessage()
        );
        
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle bad request errors (400).
     * Triggered by malformed requests.
     */
    @ExceptionHandler(value = {IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.warn("Bad request: {} - Path: {}", ex.getMessage(), path);
        
        ErrorResponse error = new ErrorResponse(
                "BAD_REQUEST",
                "Invalid request format or parameters. Check your request.",
                path,
                ex.getMessage()
        );
        
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }
    
    /**
     * Handle authentication errors (401).
     * Triggered when request lacks valid credentials.
     */
    @ExceptionHandler(value = {UnauthorizedException.class})
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(
            UnauthorizedException ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.warn("Unauthorized access attempt - Path: {}", path);
        
        ErrorResponse error = new ErrorResponse(
                "UNAUTHORIZED",
                "Authentication required. Please provide valid credentials.",
                path
        );
        
        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }
    
    /**
     * Handle authorization errors (403).
     * Triggered when authenticated user lacks permission.
     */
    @ExceptionHandler(value = {ForbiddenException.class})
    public ResponseEntity<ErrorResponse> handleForbiddenException(
            ForbiddenException ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.warn("Forbidden access attempt - Path: {}", path);
        
        ErrorResponse error = new ErrorResponse(
                "FORBIDDEN",
                "You don't have permission to access this resource.",
                path
        );
        
        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }
    
    /**
     * Catch-all handler for unexpected errors (500).
     */
    @ExceptionHandler(value = {Exception.class})
    public ResponseEntity<ErrorResponse> handleGeneralException(
            Exception ex, WebRequest request) {
        
        String path = getRequestPath(request);
        logger.error("Unexpected error - Path: {}", path, ex);
        
        ErrorResponse error = new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred on the Gateway. Please try again later.",
                path,
                ex.getClass().getSimpleName()
        );
        
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
    
    /**
     * Extract request path from WebRequest.
     */
    private String getRequestPath(WebRequest request) {
        String path = request.getDescription(false);
        // Remove "uri=" prefix if present
        return path.startsWith("uri=") ? path.substring(4) : path;
    }
}
