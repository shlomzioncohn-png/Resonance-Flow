package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.generator.shared.TenantKey;

/**
 * Simple, deterministic templates for the {@code NOT NULL} content fields the schema requires
 * but the design doc never modeled statistically (tenant/team/user names, task titles) — chosen
 * over a fake-data library (design doc decision: plain templates, no new dependency). Not meant
 * to look like real company/people names; meant to be readable, unique where the schema requires
 * it, and traceable back to the entity's positional identity.
 */
public final class ContentFactory {

    private ContentFactory() {
    }

    public static String tenantName(TenantKey tenant) {
        return tenant.tierName() + " Org " + (tenant.tierIndex() + 1);
    }

    /** Globally unique (design doc's {@code uq_tenants_slug}) — one tenant, one slug. */
    public static String tenantSlug(TenantKey tenant) {
        return slugify(tenant.tierName()) + "-org-" + (tenant.tierIndex() + 1);
    }

    public static String teamName(int teamIndex) {
        return "Team " + (teamIndex + 1);
    }

    public static String userDisplayName(int userIndexInTenant) {
        return "User " + (userIndexInTenant + 1);
    }

    /** Globally unique (schema's {@code uq_users_email} spans every tenant, not just one) — the
     * tenant's own unique slug guarantees that across all ~1,300+ users. */
    public static String userEmail(TenantKey tenant, int userIndexInTenant) {
        return "user" + (userIndexInTenant + 1) + "@" + tenantSlug(tenant) + ".example";
    }

    /** Globally unique, same reasoning as {@link #userEmail}. */
    public static String userUsername(TenantKey tenant, int userIndexInTenant) {
        return "user" + (userIndexInTenant + 1) + "-" + tenantSlug(tenant);
    }

    public static String userRole(boolean teamLead) {
        return teamLead ? "team_lead" : "employee";
    }

    /** Not globally unique — titles don't need to be, and repeats across tenants are fine. */
    public static String taskTitle(String sourceType, int taskIndexInTenant) {
        return prettify(sourceType) + " task " + (taskIndexInTenant + 1);
    }

    private static String slugify(String value) {
        return value.toLowerCase().replace('/', '-').replace(' ', '-');
    }

    private static String prettify(String sourceType) {
        String spaced = sourceType.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
