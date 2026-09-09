package com.rnd.chain.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** First @RestControllerAdvice in the repo - a new, minimal error-body convention ({error, message}). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({TaskNotFoundException.class, DependencyNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException e) {
        return status(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(DuplicateDependencyException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DuplicateDependencyException e) {
        return status(HttpStatus.CONFLICT, e);
    }

    // Also catches IllegalArgumentException from DTO builders (e.g. Task, DependencyHistoryEntry) -
    // fine while nothing builds those from request input, but revisit if a future endpoint does.
    @ExceptionHandler({InvalidDependencyException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException e) {
        return status(HttpStatus.BAD_REQUEST, e);
    }

    private static ResponseEntity<ErrorResponse> status(HttpStatus status, Exception e) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.getClass().getSimpleName(), e.getMessage()));
    }
}
