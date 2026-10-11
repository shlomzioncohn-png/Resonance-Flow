package com.rnd.inbox.shared.exception;

import com.rnd.inbox.shared.dto.ErrorResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, webRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponseDto body = (ErrorResponseDto) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getError()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.getTimestamp()).isEqualTo(FIXED_NOW);
        assertThat(body.getFieldErrors()).hasSize(2);
        assertThat(body.getFieldErrors().get(0).getField()).isEqualTo("display_name");
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

    @Test
    void handleException_whenPathVariableHasWrongType_returns400BadRequest() throws Exception {
        Method dummyMethod = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyValidatedMethod", String.class);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "id", new MethodParameter(dummyMethod, 0),
                new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

        ResponseEntity<Object> response = handler.handleException(ex, webRequest());

        assertGenericError(response, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad Request");
    }

    @Test
    void handleException_whenBodyIsUnreadable_returns400BadRequest() throws Exception {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected end-of-input", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<Object> response = handler.handleException(ex, webRequest());

        assertGenericError(response, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad Request");
    }

    @Test
    void handleException_whenPathDoesNotExist_returns404NotFound() throws Exception {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "api/v1/no-such-thing");

        ResponseEntity<Object> response = handler.handleException(ex, webRequest());

        assertGenericError(response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not Found");
    }

    @Test
    void handleException_whenMethodIsNotSupported_returns405WithAllowHeader() throws Exception {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "PATCH"));

        ResponseEntity<Object> response = handler.handleException(ex, webRequest());

        assertGenericError(response, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method Not Allowed");
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PATCH);
    }

    private void assertGenericError(ResponseEntity<Object> response, HttpStatus expectedStatus,
                                    String expectedCode, String expectedMessage) {
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isInstanceOf(ErrorResponseDto.class);
        ErrorResponseDto body = (ErrorResponseDto) response.getBody();
        assertThat(body.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(body.getError()).isEqualTo(expectedCode);
        assertThat(body.getMessage()).isEqualTo(expectedMessage);
        assertThat(body.getTimestamp()).isEqualTo(FIXED_NOW);
        assertThat(body.getFieldErrors()).isNull();
    }

    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
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
