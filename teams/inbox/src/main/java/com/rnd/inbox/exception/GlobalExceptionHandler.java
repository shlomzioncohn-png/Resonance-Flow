package com.rnd.inbox.exception;

import com.rnd.inbox.dto.ErrorResponseDto;
import com.rnd.inbox.dto.FieldErrorDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorDto> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDto(fe.getField(), fe.getDefaultMessage()))
                .toList();

        ErrorResponseDto body = new ErrorResponseDto(
                400, "VALIDATION_ERROR", "Request validation failed", Instant.now(clock), fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }
}
