package com.rnd.inbox.security;

import com.rnd.inbox.exception.UnauthenticatedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Resolves the acting user from the {@code X-Actor-Id} header, trusting it as sent — there is
 * no token, signature, or session behind it, only a check that it is a real user's id. This is
 * a stand-in for real authentication, not a permanent design; see the "Known gap" note under
 * §9 REST API rules in {@code CLAUDE.md} before relying on it as an authentication boundary.
 */
public class CurrentActorArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentActor.class)
                && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(ACTOR_HEADER);
        if (header == null || header.isBlank()) {
            throw new UnauthenticatedException("Missing " + ACTOR_HEADER + " header");
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw new UnauthenticatedException(ACTOR_HEADER + " header is not a valid UUID");
        }
    }
}
