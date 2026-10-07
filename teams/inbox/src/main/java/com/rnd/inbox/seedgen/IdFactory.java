package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.generator.shared.TenantKey;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns a positional identity (tenant tier+index, team index, user index, task index — see each
 * Seed type's Javadoc) into a real, stable {@link UUID} — the last step before a row can
 * actually be written to Postgres.
 *
 * <p>Deliberately not drawn from {@link com.rnd.inbox.seedgen.generator.shared.RandomUtil}'s shared
 * {@code Random}: an id is derived with {@link UUID#nameUUIDFromBytes} from a canonical string
 * built out of the entity's own positional identity (e.g. {@code "tenant:Large:7"}). Two
 * consequences: generating ids never consumes from the shared {@code Random} (so it can never
 * shift the sequence every other stage's reproducibility depends on), and any id can be
 * recomputed by hand from its identity string alone — nothing to look up, nothing hidden.
 */
public final class IdFactory {

    private IdFactory() {
    }

    public static UUID tenantId(TenantKey tenant) {
        return fromParts("tenant", tenant.tierName(), tenant.tierIndex());
    }

    public static UUID teamId(TenantKey tenant, int teamIndex) {
        return fromParts("team", tenant.tierName(), tenant.tierIndex(), teamIndex);
    }

    public static UUID userId(TenantKey tenant, int userIndexInTenant) {
        return fromParts("user", tenant.tierName(), tenant.tierIndex(), userIndexInTenant);
    }

    public static UUID taskId(TenantKey tenant, int taskIndexInTenant) {
        return fromParts("task", tenant.tierName(), tenant.tierIndex(), taskIndexInTenant);
    }

    private static UUID fromParts(Object... parts) {
        StringBuilder name = new StringBuilder();
        for (Object part : parts) {
            if (!name.isEmpty()) {
                name.append(':');
            }
            name.append(part);
        }
        return UUID.nameUUIDFromBytes(name.toString().getBytes(StandardCharsets.UTF_8));
    }
}
