package com.rnd.bff.security;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Resolves @CurrentUser annotations in controller methods to UUID values.
 * Extracts X-User-Id header from the request and parses it as a UUID.
 * 
 * This keeps user resolution logic centralized and makes it easy to change auth mechanisms
 * in the future without modifying every controller.
 * 
 * The X-User-Id header comes from the Gateway after validating the JWT.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == UUID.class &&
               parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userIdHeader = webRequest.getHeader(USER_ID_HEADER);
        
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new MissingUserIdException("X-User-Id header is required");
        }

        try {
            return UUID.fromString(userIdHeader);
        } catch (IllegalArgumentException e) {
            throw new InvalidUserIdException("X-User-Id header must be a valid UUID", e);
        }
    }

    /**
     * Exception raised when X-User-Id header is missing.
     */
    public static class MissingUserIdException extends RuntimeException {
        public MissingUserIdException(String message) {
            super(message);
        }
    }

    /**
     * Exception raised when X-User-Id header is not a valid UUID.
     */
    public static class InvalidUserIdException extends RuntimeException {
        public InvalidUserIdException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
