package com.rnd.inbox.exception;

import com.rnd.inbox.dto.ErrorResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Test
    void handleInboxException_buildsErrorResponseFromTheExceptionsOwnCodeAndStatus() {
        InboxException ex = new TestInboxException(
                "Widget 123 not found", "WIDGET_NOT_FOUND", HttpStatus.NOT_FOUND);

        ResponseEntity<ErrorResponseDto> response = handler.handleInboxException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getError()).isEqualTo("WIDGET_NOT_FOUND");
        assertThat(body.getMessage()).isEqualTo("Widget 123 not found");
        assertThat(body.getTimestamp()).isEqualTo(FIXED_NOW);
        assertThat(body.getFieldErrors()).isNull();
    }

    @Test
    void handleValidation_buildsAValidationErrorResponseListingEveryFailedField() throws NoSuchMethodException {
        MethodArgumentNotValidException ex = validationExceptionWith(
                new FieldError("target", "displayName", "must not be blank"),
                new FieldError("target", "email", "must be a well-formed email address"));

        ResponseEntity<ErrorResponseDto> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getError()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.getTimestamp()).isEqualTo(FIXED_NOW);
        assertThat(body.getFieldErrors()).hasSize(2);
        assertThat(body.getFieldErrors().get(0).getField()).isEqualTo("displayName");
        assertThat(body.getFieldErrors().get(0).getMessage()).isEqualTo("must not be blank");
        assertThat(body.getFieldErrors().get(1).getField()).isEqualTo("email");
        assertThat(body.getFieldErrors().get(1).getMessage()).isEqualTo("must be a well-formed email address");
    }

    @Test
    void handleUnexpectedException_buildsGenericInternalErrorResponseWithoutLeakingDetails() {
        Exception ex = new NullPointerException("something null deep in a service");

        ResponseEntity<ErrorResponseDto> response = handler.handleUnexpectedException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getError()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.getMessage()).isEqualTo("An unexpected error occurred");
        assertThat(body.getMessage()).doesNotContain("NullPointerException", "something null deep in a service");
        assertThat(body.getTimestamp()).isEqualTo(FIXED_NOW);
        assertThat(body.getFieldErrors()).isNull();
    }

    private MethodArgumentNotValidException validationExceptionWith(FieldError... fieldErrors) throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        for (FieldError fieldError : fieldErrors) {
            bindingResult.addError(fieldError);
        }
        Method dummyMethod = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyValidatedMethod", String.class);
        return new MethodArgumentNotValidException(new MethodParameter(dummyMethod, 0), bindingResult);
    }

    @SuppressWarnings("unused")
    private void dummyValidatedMethod(String arg) {
    }

    private static class TestInboxException extends InboxException {
        TestInboxException(String message, String errorCode, HttpStatus httpStatus) {
            super(message, errorCode, httpStatus);
        }
    }
}
