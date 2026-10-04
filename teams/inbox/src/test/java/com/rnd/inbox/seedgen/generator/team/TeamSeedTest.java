package com.rnd.inbox.seedgen.generator.team;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TeamSeedTest {

    @Test
    void constructor_validValues_doesNotThrow() {
        assertThatCode(() -> new TeamSeed("Large", 3, 0, 6)).doesNotThrowAnyException();
    }

    @Test
    void constructor_zeroSize_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TeamSeed("Large", 3, 0, 0));
    }

    @Test
    void constructor_negativeSize_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TeamSeed("Large", 3, 0, -6));
    }

    @Test
    void constructor_negativeTeamIndex_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TeamSeed("Large", 3, -1, 6));
    }
}
