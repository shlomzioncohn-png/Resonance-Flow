package com.rnd.inbox.seedgen.generator.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UserSeedTest {

    @Test
    void constructor_affiliatedLead_doesNotThrow() {
        assertThatCode(() -> new UserSeed("Large", 3, 0, 0, true)).doesNotThrowAnyException();
    }

    @Test
    void constructor_unaffiliatedNonLead_doesNotThrow() {
        assertThatCode(() -> new UserSeed("Large", 3, null, 5, false)).doesNotThrowAnyException();
    }

    @Test
    void constructor_unaffiliatedLead_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new UserSeed("Large", 3, null, 5, true));
    }

    @Test
    void constructor_negativeUserIndex_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new UserSeed("Large", 3, 0, -1, false));
    }

    @Test
    void constructor_negativeTeamIndex_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new UserSeed("Large", 3, -1, 0, false));
    }

    @Test
    void unaffiliated_reflectsNullTeamIndex() {
        UserSeed affiliated = new UserSeed("Large", 3, 0, 0, true);
        UserSeed unaffiliated = new UserSeed("Large", 3, null, 5, false);

        org.assertj.core.api.Assertions.assertThat(affiliated.unaffiliated()).isFalse();
        org.assertj.core.api.Assertions.assertThat(unaffiliated.unaffiliated()).isTrue();
    }
}
