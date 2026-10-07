package com.rnd.inbox.shared.security;

import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.UUID;

/**
 * Enforces {@link RequireRole} at the controller boundary: before a handler method annotated with
 * it runs, checks that the acting user's {@link UserRole} is one of the allowed values, rejecting
 * the request otherwise. A handler with no {@code @RequireRole} is unaffected.
 */
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public RoleAuthorizationInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            return true;
        }

        UUID actorId = ActorHeaderExtractor.resolveActorId(request.getHeader(ActorHeaderExtractor.ACTOR_HEADER));
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Unknown actor: " + actorId));

        if (!Arrays.asList(requireRole.value()).contains(actor.getRole())) {
            throw new ForbiddenActionException(
                    "Role " + actor.getRole() + " is not permitted to perform this action");
        }

        return true;
    }

}
