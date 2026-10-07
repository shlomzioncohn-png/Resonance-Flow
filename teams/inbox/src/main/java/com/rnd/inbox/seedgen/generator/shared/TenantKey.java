package com.rnd.inbox.seedgen.generator.shared;
import com.rnd.inbox.seedgen.generator.user.UserSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

/**
 * Identifies a tenant before it has a real id — the same {@code (tierName, tierIndex)} pair
 * {@link TenantSeed}, {@link TeamSeed} and {@link UserSeed} each already carry. A dedicated type
 * instead of a hand-built string (e.g. {@code tierName + "#" + tierIndex}) so two tenants can
 * never collide by accident and a typo in the field order is a compile error, not a runtime bug.
 */
public record TenantKey(String tierName, int tierIndex) {
}
