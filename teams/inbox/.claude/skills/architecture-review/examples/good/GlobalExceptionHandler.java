// Example only — illustrates a compliant slice for the architecture-review skill's test
// scenarios. Not wired into the Maven build.
package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(TaskNotFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidTaskStateException.class)
    public ResponseEntity<Object> handleInvalidState(InvalidTaskStateException ex) {
        return errorResponse(HttpStatus.CONFLICT, "INVALID_TASK_STATE", ex.getMessage());
    }

    private ResponseEntity<Object> errorResponse(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
