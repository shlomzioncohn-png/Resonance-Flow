package com.rnd.inbox.shared.security;

import com.rnd.inbox.shared.exception.UnauthenticatedException;

import java.util.UUID;

/**
 * Reads the {@code X-Actor-Id} header shared by {@link CurrentActorArgumentResolver} and
 * {@link RoleAuthorizationInterceptor} — both need the same raw actor id off the same header,
 * one to resolve a {@code @CurrentActor} parameter, the other to check {@link RequireRole}. This
 * is a stand-in for real authentication (see the "Known gap" note in {@code CLAUDE.md} §9) and is
 * expected to be replaced once a real token exists — keeping the parsing in one place means that
 * replacement happens here, not in two places that have to be kept in sync by hand.
 */
final class ActorHeaderExtractor {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private ActorHeaderExtractor() {
    }

    /**
     * Parses the raw {@code X-Actor-Id} header value into a {@link UUID}. Callers fetch the
     * header themselves (the two call sites read it off different Spring request abstractions —
     * {@code NativeWebRequest} vs. {@code HttpServletRequest} — that share no common interface
     * for it), and pass the raw value in here for the validation both call sites need.
     */
    static UUID resolveActorId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new UnauthenticatedException("Missing " + ACTOR_HEADER + " header");
        }
        try {
            return UUID.fromString(headerValue);
        } catch (IllegalArgumentException e) {
            throw new UnauthenticatedException(ACTOR_HEADER + " header is not a valid UUID");
        }
    }
}
