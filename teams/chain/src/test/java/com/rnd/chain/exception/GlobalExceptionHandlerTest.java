package com.rnd.chain.exception;

import com.rnd.chain.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidationError_onlyGlobalErrors_fallsBackToAllErrors() throws NoSuchMethodException {
        // A class-level (cross-field) constraint violation produces only a global error -
        // getFieldErrors() is empty, unlike the field-level case every other test exercises.
        Object target = new Object();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        bindingResult.addError(new ObjectError("target", "must satisfy cross-field constraint"));
        assertTrue(bindingResult.getFieldErrors().isEmpty());

        Method dummyMethod = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", Object.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new MethodParameter(dummyMethod, 0), bindingResult);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/tasks/some-endpoint");

        ResponseEntity<ApiError> response = handler.handleValidationError(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("must satisfy cross-field constraint", response.getBody().getMessage());
    }

    @Test
    void handleValidationError_fieldError_usesFieldErrorMessage() throws NoSuchMethodException {
        Object target = new Object();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        bindingResult.addError(new org.springframework.validation.FieldError("target", "someField", "must not be null"));

        Method dummyMethod = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", Object.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new MethodParameter(dummyMethod, 0), bindingResult);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/tasks/some-endpoint");

        ResponseEntity<ApiError> response = handler.handleValidationError(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("must not be null", response.getBody().getMessage());
    }

    @Test
    void handleTypeMismatch_usesTheActualParameterName_notAHardcodedOne() throws NoSuchMethodException {
        Method dummyMethod = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", Object.class);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "size", new MethodParameter(dummyMethod, 0), new NumberFormatException());

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/tasks/some-endpoint/dependencies/history");

        ResponseEntity<ApiError> response = handler.handleTypeMismatch(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid value for parameter 'size'", response.getBody().getMessage());
    }

    private void dummyTarget(Object target) {
    }
}
