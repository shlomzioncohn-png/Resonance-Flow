package com.rnd.inbox.shared.exception;

import com.rnd.inbox.shared.dto.ErrorResponseDto;
import com.rnd.inbox.shared.dto.FieldErrorDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(InboxException.class)
    public ResponseEntity<ErrorResponseDto> handleInboxException(InboxException ex) {
        ErrorResponseDto body = new ErrorResponseDto(
                ex.getHttpStatus().value(), ex.getErrorCode(), ex.getMessage(), Instant.now(clock));
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorDto> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDto(toSnakeCase(fe.getField()), fe.getDefaultMessage()))
                .toList();

        ErrorResponseDto body = new ErrorResponseDto(
                400, "VALIDATION_ERROR", "Request validation failed", Instant.now(clock), fieldErrors);
        return ResponseEntity.badRequest().headers(headers).body(body);
    }

    private static String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String errorCode = status != null ? status.name() : "HTTP_" + statusCode.value();
        String message = status != null ? status.getReasonPhrase() : "Request could not be processed";
        log.warn("Request rejected with {}: {}", statusCode.value(), ex.getMessage());

        ErrorResponseDto errorBody = new ErrorResponseDto(statusCode.value(), errorCode, message, Instant.now(clock));
        return super.handleExceptionInternal(ex, errorBody, headers, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error while handling request", ex);
        ErrorResponseDto body = new ErrorResponseDto(
                500, "INTERNAL_ERROR", "An unexpected error occurred", Instant.now(clock));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
