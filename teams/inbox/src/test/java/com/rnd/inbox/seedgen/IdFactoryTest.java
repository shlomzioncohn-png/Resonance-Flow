package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdFactoryTest {

    private static final TenantKey TENANT = new TenantKey("Large", 7);

    @Test
    void tenantId_sameKey_alwaysReturnsTheSameUuid() {
        assertThat(IdFactory.tenantId(TENANT)).isEqualTo(IdFactory.tenantId(TENANT));
    }

    @Test
    void teamId_sameArguments_alwaysReturnsTheSameUuid() {
        assertThat(IdFactory.teamId(TENANT, 3)).isEqualTo(IdFactory.teamId(TENANT, 3));
    }

    @Test
    void differentEntityTypes_sameTenantAndIndex_produceDifferentUuids() {
        // teamId(TENANT, 0), userId(TENANT, 0) and taskId(TENANT, 0) share every input except
        // the type prefix — that alone must be enough to keep them apart.
        UUID team = IdFactory.teamId(TENANT, 0);
        UUID user = IdFactory.userId(TENANT, 0);
        UUID task = IdFactory.taskId(TENANT, 0);
        UUID tenant = IdFactory.tenantId(TENANT);

        assertThat(Set.of(team, user, task, tenant)).hasSize(4);
    }

    @Test
    void differentIndices_produceDifferentUuids() {
        assertThat(IdFactory.userId(TENANT, 0)).isNotEqualTo(IdFactory.userId(TENANT, 1));
    }

    @Test
    void differentTenants_sameIndex_produceDifferentUuids() {
        TenantKey other = new TenantKey("Large", 8);

        assertThat(IdFactory.userId(TENANT, 0)).isNotEqualTo(IdFactory.userId(other, 0));
    }

    @Test
    void realDataset_everyGeneratedIdIsUnique() {
        GeneratedDataset dataset = SeedGenerator.generate();
        Set<UUID> ids = new HashSet<>();

        dataset.tenants().forEach(t -> assertThat(ids.add(IdFactory.tenantId(t.tenantKey()))).isTrue());
        dataset.teams().forEach(t -> assertThat(ids.add(IdFactory.teamId(t.tenantKey(), t.teamIndex()))).isTrue());
        dataset.users().forEach(u -> assertThat(ids.add(IdFactory.userId(u.tenantKey(), u.userIndexInTenant()))).isTrue());
        for (var entry : dataset.tasksByTenant().entrySet()) {
            List<?> tasks = entry.getValue();
            for (int i = 0; i < tasks.size(); i++) {
                assertThat(ids.add(IdFactory.taskId(entry.getKey(), i))).isTrue();
            }
        }
    }
}
