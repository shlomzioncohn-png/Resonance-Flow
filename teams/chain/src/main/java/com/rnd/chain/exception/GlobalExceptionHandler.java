package com.rnd.chain.exception;

import com.rnd.chain.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Central error mapping for the Chain API, matching the shape already
 * established (unused so far) on the Riki-Kaplan branch. Only the two
 * mappings this task's endpoints can actually produce are handled here:
 * an unknown/soft-deleted task id (404), and a path variable that isn't a
 * valid UUID at all (400).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleTaskNotFound(TaskNotFoundException ex, WebRequest request) {
        ApiError body = new ApiError(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(),
                extractPath(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidTaskId(MethodArgumentTypeMismatchException ex, WebRequest request) {
        ApiError body = new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Invalid task ID",
                extractPath(request));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        // WebRequest#getDescription(false) returns "uri=/tasks/{id}/..." - strip the prefix.
        String prefix = "uri=";
        if (description.startsWith(prefix)) {
            return description.substring(prefix.length());
        }
        return description;
    }
}
