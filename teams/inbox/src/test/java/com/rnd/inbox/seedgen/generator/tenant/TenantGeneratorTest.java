package com.rnd.inbox.seedgen.generator.tenant;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TenantGeneratorTest {

    @Test
    void generate_always_returnsExactlyOneHundredTenants() {
        assertThat(TenantGenerator.generate()).hasSize(100);
    }

    @Test
    void generate_always_taskCountsSumToExactlyOneHundredThousand() {
        int total = TenantGenerator.generate().stream()
                .mapToInt(TenantSeed::taskCount)
                .sum();

        assertThat(total).isEqualTo(DatasetConfig.TOTAL_TASKS);
    }

    @Test
    void generate_always_matchesEachTiersTenantCountFromDesignDoc() {
        Map<String, Long> countByTier = TenantGenerator.generate().stream()
                .collect(Collectors.groupingBy(TenantSeed::tierName, Collectors.counting()));

        for (DatasetConfig.TenantTier tier : DatasetConfig.TENANT_TIERS) {
            assertThat(countByTier).containsEntry(tier.name(), (long) tier.tenantCount());
        }
    }

    @Test
    void generate_always_everyNonMegaTenantStaysWithinItsTierRanges() {
        List<TenantSeed> tenants = TenantGenerator.generate();

        for (TenantSeed tenant : tenants) {
            if (tenant.tierName().equals(DatasetConfig.MEGA.name())) {
                continue; // Mega absorbs the remainder — deliberately outside its (absent) range.
            }
            DatasetConfig.TenantTier tier = DatasetConfig.TENANT_TIERS.stream()
                    .filter(t -> t.name().equals(tenant.tierName()))
                    .findFirst()
                    .orElseThrow();

            assertThat(tenant.taskCount())
                    .isBetween(tier.tasks().min(), tier.tasks().max());
            assertThat(tenant.userCount())
                    .isBetween(tier.users().min(), tier.users().max());
        }
    }

    @Test
    void generate_always_megaTenantAbsorbsTheRemainderAndStaysDominant() {
        TenantSeed mega = TenantGenerator.generate().stream()
                .filter(t -> t.tierName().equals(DatasetConfig.MEGA.name()))
                .findFirst()
                .orElseThrow();

        // Sanity bound on the remainder itself, not a hard-coded expected value — a change to
        // any other tier's ranges is free to shift this without breaking the test.
        assertThat(mega.taskCount()).isGreaterThan(DatasetConfig.ENTERPRISE.tasks().max());
        assertThat(mega.userCount())
                .isBetween(DatasetConfig.MEGA.users().min(), DatasetConfig.MEGA.users().max());
    }

    @Test
    void generate_calledTwice_producesIdenticalOutput() {
        assertThat(TenantGenerator.generate()).isEqualTo(TenantGenerator.generate());
    }
}
