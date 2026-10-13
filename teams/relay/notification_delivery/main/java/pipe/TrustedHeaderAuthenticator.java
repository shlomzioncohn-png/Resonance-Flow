package pipe;

import org.springframework.stereotype.Component;

import java.util.Optional;

// Trusts X-Org-Id/X-User-Id as-is. The user's JWT is validated by lens-gateway and the
// identity reaches us as headers via lens-bff, the only caller allowed to reach this service
// (it has no Ingress). Rejects missing or blank values so they can't collapse into one shared
// "org:user" key and leak notifications between users.
@Component
class TrustedHeaderAuthenticator {

    Optional<AuthenticatedPrincipal> authenticate(String orgId, String userId) {
        if (isBlank(orgId) || isBlank(userId)) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedPrincipal(orgId, userId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
