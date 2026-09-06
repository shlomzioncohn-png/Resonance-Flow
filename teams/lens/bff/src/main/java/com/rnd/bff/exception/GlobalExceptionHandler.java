package com.rnd.bff.exception;

import com.rnd.bff.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.stream.Collectors;

/**
 * Matches com.rnd.gateway.exception.GlobalExceptionHandler's error shape
 * (errorCode/message/path/timestamp/details) so an error looks the same
 * regardless of which layer raised it — see
 * docs/workspace-screen-integration-plan.md, "Errors are uniform".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Triggered when @Valid fails on request body fields (e.g. missing availableTime). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        String path = getRequestPath(request);
        String details =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(e -> e.getField() + ": " + e.getDefaultMessage())
                        .collect(Collectors.joining(", "));

        logger.warn("Validation error - Path: {}, Details: {}", path, details);

        return new ResponseEntity<>(
                new ErrorResponse("BAD_REQUEST", "Invalid request parameters", path, details),
                HttpStatus.BAD_REQUEST);
    }

    /** Triggered when Gateway's required X-User-Id header is missing. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeaderException(
            MissingRequestHeaderException ex, WebRequest request) {
        String path = getRequestPath(request);
        logger.warn("Missing required header - Path: {}, Details: {}", path, ex.getMessage());

        return new ResponseEntity<>(
                new ErrorResponse("BAD_REQUEST", "Missing required header", path, ex.getMessage()),
                HttpStatus.BAD_REQUEST);
    }

    /** Triggered by malformed JSON or a field of the wrong type (e.g. availableTime: "abc"). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadableException(
            HttpMessageNotReadableException ex, WebRequest request) {
        String path = getRequestPath(request);
        logger.warn("Malformed request body - Path: {}, Details: {}", path, ex.getMessage());

        return new ResponseEntity<>(
                new ErrorResponse("BAD_REQUEST", "Malformed request body", path, ex.getMessage()),
                HttpStatus.BAD_REQUEST);
    }

    /** Triggered by calling an endpoint with the wrong HTTP method. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        String path = getRequestPath(request);
        logger.warn("Unsupported method - Path: {}, Details: {}", path, ex.getMessage());

        return new ResponseEntity<>(
                new ErrorResponse("METHOD_NOT_ALLOWED", "HTTP method not supported", path, ex.getMessage()),
                HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Triggered when a downstream dependency (Inbox, Chain, Mind) cannot be
     * reached and no recommendation is possible without it — see
     * WorkspaceService.recommend() and
     * docs/workspace-screen-integration-plan.md.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailableException(
            ServiceUnavailableException ex, WebRequest request) {
        String path = getRequestPath(request);
        logger.error("Service unavailable: {} - Path: {}", ex.getMessage(), path);

        return new ResponseEntity<>(
                new ErrorResponse(
                        "SERVICE_UNAVAILABLE",
                        "Downstream service is unavailable. Please try again later.",
                        path,
                        "Service: " + ex.getServiceName()),
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Triggered when a task id doesn't exist in Inbox's task set. */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotFoundException(
            TaskNotFoundException ex, WebRequest request) {
        String path = getRequestPath(request);
        logger.warn("Task not found - Path: {}, taskId: {}", path, ex.getTaskId());

        return new ResponseEntity<>(
                new ErrorResponse("NOT_FOUND", "Task not found", path, ex.getMessage()),
                HttpStatus.NOT_FOUND);
    }

    /** Catch-all for unexpected errors. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex, WebRequest request) {
        String path = getRequestPath(request);
        logger.error("Unexpected error - Path: {}", path, ex);

        return new ResponseEntity<>(
                new ErrorResponse(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred on the BFF. Please try again later.",
                        path,
                        ex.getClass().getSimpleName()),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String getRequestPath(WebRequest request) {
        String path = request.getDescription(false);
        return path.startsWith("uri=") ? path.substring(4) : path;
    }
}
