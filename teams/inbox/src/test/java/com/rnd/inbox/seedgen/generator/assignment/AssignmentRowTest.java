package com.rnd.inbox.seedgen.generator.assignment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AssignmentRowTest {

    @Test
    void constructor_userTargeted_doesNotThrow() {
        assertThatCode(() -> new AssignmentRow("Large", 3, 5, 2, null)).doesNotThrowAnyException();
    }

    @Test
    void constructor_teamTargeted_doesNotThrow() {
        assertThatCode(() -> new AssignmentRow("Large", 3, 5, null, 1)).doesNotThrowAnyException();
    }

    @Test
    void constructor_neitherUserNorTeam_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AssignmentRow("Large", 3, 5, null, null));
    }

    @Test
    void constructor_bothUserAndTeam_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AssignmentRow("Large", 3, 5, 2, 1));
    }

    @Test
    void constructor_negativeTaskIndex_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AssignmentRow("Large", 3, -1, 2, null));
    }

    @Test
    void targetsTeam_reflectsWhichFieldIsSet() {
        AssignmentRow userRow = new AssignmentRow("Large", 3, 5, 2, null);
        AssignmentRow teamRow = new AssignmentRow("Large", 3, 5, null, 1);

        org.assertj.core.api.Assertions.assertThat(userRow.targetsTeam()).isFalse();
        org.assertj.core.api.Assertions.assertThat(teamRow.targetsTeam()).isTrue();
    }
}
