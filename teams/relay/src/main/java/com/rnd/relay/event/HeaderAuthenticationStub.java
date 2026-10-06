package com.rnd.relay.event;

import org.springframework.stereotype.Component;

// No-op stand-in for real authentication: trusts X-Org-Id/X-User-Id as-is until
// requests are actually authenticated upstream (e.g. by lens-gateway).
@Component
class HeaderAuthenticationStub {

    AuthenticatedPrincipal authenticate(String orgId, String userId) {
        return new AuthenticatedPrincipal(orgId, userId);
    }
}
