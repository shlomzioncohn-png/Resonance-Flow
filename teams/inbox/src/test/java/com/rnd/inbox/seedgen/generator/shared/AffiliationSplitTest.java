package com.rnd.inbox.seedgen.generator.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AffiliationSplitTest {

    @Test
    void of_always_unaffiliatedAndAffiliatedSumBackToTenantUsers() {
        for (int tenantUsers : new int[] {1, 2, 3, 15, 200, 6500, 17_085}) {
            AffiliationSplit split = AffiliationSplit.of(tenantUsers);

            assertThat(split.unaffiliatedCount() + split.affiliatedCount()).isEqualTo(tenantUsers);
        }
    }

    @Test
    void of_twoHundredUsers_isApproximatelyFivePercentUnaffiliated() {
        AffiliationSplit split = AffiliationSplit.of(200);

        assertThat(split.unaffiliatedCount()).isEqualTo(10);
        assertThat(split.affiliatedCount()).isEqualTo(190);
    }

    @Test
    void of_neverReturnsNegativeCounts() {
        for (int tenantUsers = 0; tenantUsers <= 20; tenantUsers++) {
            AffiliationSplit split = AffiliationSplit.of(tenantUsers);

            assertThat(split.unaffiliatedCount()).isNotNegative();
            assertThat(split.affiliatedCount()).isNotNegative();
        }
    }
}
