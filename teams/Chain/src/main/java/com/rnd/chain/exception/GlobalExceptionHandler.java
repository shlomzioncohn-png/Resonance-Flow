package com.rnd.chain.exception;

import com.rnd.chain.dto.ApiError;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Task not found
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleTaskNotFound(
            TaskNotFoundException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        HttpStatus.NOT_FOUND.value(),
                        ex.getMessage()
                ));
    }

    // Dependency not found
    @ExceptionHandler(DependencyNotFoundException.class)
    public ResponseEntity<ApiError> handleDependencyNotFound(
            DependencyNotFoundException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        HttpStatus.NOT_FOUND.value(),
                        ex.getMessage()
                ));
    }

    // Duplicate dependency
    @ExceptionHandler(DuplicateDependencyException.class)
    public ResponseEntity<ApiError> handleDuplicateDependency(
            DuplicateDependencyException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        HttpStatus.CONFLICT.value(),
                        ex.getMessage()
                ));
    }

    // Invalid dependency
    @ExceptionHandler(InvalidDependencyException.class)
    public ResponseEntity<ApiError> handleInvalidDependency(
            InvalidDependencyException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        ex.getMessage()
                ));
    }

    // Constraint not found
    @ExceptionHandler(ConstraintNotFoundException.class)
    public ResponseEntity<ApiError> handleConstraintNotFound(
            ConstraintNotFoundException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        HttpStatus.NOT_FOUND.value(),
                        ex.getMessage()
                ));
    }

    // Invalid constraint
    @ExceptionHandler(InvalidConstraintException.class)
    public ResponseEntity<ApiError> handleInvalidConstraint(
            InvalidConstraintException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        ex.getMessage()
                ));
    }

    // Invalid request field validation
    // Example: required field is missing
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationError(
            MethodArgumentNotValidException ex
    ) {
        String message =
                ex.getBindingResult()
                        .getFieldErrors()
                        .get(0)
                        .getDefaultMessage();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        message
                ));
    }

    // Invalid task ID
    // Example: taskId cannot be converted to UUID
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidTaskId(
            MethodArgumentTypeMismatchException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        "Invalid task ID"
                ));
    }

    // Invalid request payload
    // Example: malformed JSON or invalid enum value
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidRequestPayload(
            HttpMessageNotReadableException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        "Invalid request payload"
                ));
    }
}