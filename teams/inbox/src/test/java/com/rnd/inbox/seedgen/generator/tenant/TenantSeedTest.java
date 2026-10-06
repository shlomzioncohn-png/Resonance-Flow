package com.rnd.inbox.seedgen.generator.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantSeedTest {

    @Test
    void constructor_validValues_doesNotThrow() {
        assertThatCode(() -> new TenantSeed("Large", 3, 1200, 25)).doesNotThrowAnyException();
    }

    @Test
    void constructor_negativeTierIndex_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TenantSeed("Large", -1, 1200, 25));
    }

    @Test
    void constructor_negativeTaskCount_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TenantSeed("Large", 3, -1, 25));
    }

    @Test
    void constructor_negativeUserCount_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TenantSeed("Large", 3, 1200, -1));
    }

    @Test
    void constructor_blankTierName_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TenantSeed(" ", 3, 1200, 25));
    }
}
