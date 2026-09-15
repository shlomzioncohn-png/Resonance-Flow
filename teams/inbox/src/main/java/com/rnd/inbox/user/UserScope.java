package com.rnd.inbox.user;

import java.util.UUID;

public sealed interface UserScope {

    record All() implements UserScope {
    }

    record WithinTenant(UUID tenantId) implements UserScope {
    }

    record WithinTeamsLedBy(UUID leaderId) implements UserScope {
    }

    record SelfOnly(UUID userId) implements UserScope {
    }
}
