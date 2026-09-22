package com.rnd.chain.exception;

import com.rnd.chain.dto.ApiError;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

        private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

        @ExceptionHandler(NotFoundException.class)
        public ResponseEntity<ApiError> handleNotFound(
                        NotFoundException ex,
                        HttpServletRequest request) {
                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(new ApiError(
                                                HttpStatus.NOT_FOUND.value(),
                                                "NOT_FOUND",
                                                ex.getMessage(),
                                                request.getRequestURI()));
        }

        // Duplicate dependency
        @ExceptionHandler(DuplicateDependencyException.class)
        public ResponseEntity<ApiError> handleDuplicateDependency(
                        DuplicateDependencyException ex,
                        HttpServletRequest request) {
                return ResponseEntity
                                .status(HttpStatus.CONFLICT)
                                .body(new ApiError(
                                                HttpStatus.CONFLICT.value(),
                                                "DUPLICATE_DEPENDENCY",
                                                ex.getMessage(),
                                                request.getRequestURI()));
        }

        @ExceptionHandler(BadRequestException.class)
        public ResponseEntity<ApiError> handleBadRequest(
                        BadRequestException ex,
                        HttpServletRequest request) {
                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(new ApiError(
                                                HttpStatus.BAD_REQUEST.value(),
                                                "BAD_REQUEST",
                                                ex.getMessage(),
                                                request.getRequestURI()));
        }

        // Invalid request field validation
        // Example: required field is missing
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ApiError> handleValidationError(
                        MethodArgumentNotValidException ex,
                        HttpServletRequest request) {
                // MethodArgumentNotValidException can carry only class-level errors
                // (getGlobalErrors()) with an empty getFieldErrors() list, e.g. a cross-field
                // constraint annotated on the class rather than on one property. Falling back to
                // getAllErrors() (field + global combined, guaranteed non-empty whenever this
                // exception exists at all) avoids an IndexOutOfBoundsException inside this
                // handler itself on that path.
                List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
                String message = fieldErrors.isEmpty()
                                ? ex.getBindingResult().getAllErrors().get(0).getDefaultMessage()
                                : fieldErrors.get(0).getDefaultMessage();

                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(new ApiError(
                                                HttpStatus.BAD_REQUEST.value(),
                                                "VALIDATION_ERROR",
                                                message,
                                                request.getRequestURI()));
        }

        // A request parameter/path variable couldn't be converted to its declared type.
        // Example: taskId cannot be converted to UUID, or page/size cannot be converted to int.
        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        public ResponseEntity<ApiError> handleTypeMismatch(
                        MethodArgumentTypeMismatchException ex,
                        HttpServletRequest request) {
                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(new ApiError(
                                                HttpStatus.BAD_REQUEST.value(),
                                                "TYPE_MISMATCH",
                                                "Invalid value for parameter '" + ex.getName() + "'",
                                                request.getRequestURI()));
        }

        // Invalid request payload
        // Example: malformed JSON or invalid enum value
        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<ApiError> handleInvalidRequestPayload(
                        HttpMessageNotReadableException ex,
                        HttpServletRequest request) {
                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(new ApiError(
                                                HttpStatus.BAD_REQUEST.value(),
                                                "INVALID_REQUEST_PAYLOAD",
                                                "Invalid request payload",
                                                request.getRequestURI()));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ApiError> handleUnexpectedException(
                        Exception ex,
                        HttpServletRequest request) {
                log.error("Unexpected server error", ex);

                return ResponseEntity
                                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(new ApiError(
                                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                                "INTERNAL_SERVER_ERROR",
                                                "Internal server error",
                                                request.getRequestURI()));
        }
}
