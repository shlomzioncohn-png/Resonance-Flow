package com.rnd.inbox.task;

import com.rnd.inbox.shared.messaging.kafka.EventValidationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class AssigneeConsistencyValidatorTest {

    private record FakeEvent(List<AssigneeDto> assignees) {
    }

    private final AssigneeConsistencyValidator<FakeEvent> validator =
            new AssigneeConsistencyValidator<>(FakeEvent::assignees);

    private AssigneeDto assigneeDto(String assigneeType, String id) {
        AssigneeDto dto = new AssigneeDto();
        dto.setAssigneeType(assigneeType);
        dto.setId(id);
        dto.setAssignmentRole("ASSIGNEE");
        return dto;
    }

    @Test
    void acceptsANullAssigneesList() {
        assertThatCode(() -> validator.validate(new FakeEvent(null))).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnEmptyAssigneesList() {
        assertThatCode(() -> validator.validate(new FakeEvent(List.of()))).doesNotThrowAnyException();
    }

    @Test
    void acceptsDistinctAssignees() {
        FakeEvent event = new FakeEvent(List.of(
                assigneeDto("USER", UUID.randomUUID().toString()),
                assigneeDto("USER", UUID.randomUUID().toString())));

        assertThatCode(() -> validator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheSameIdAssignedAsBothAUserAndATeam() {
        String id = UUID.randomUUID().toString();
        FakeEvent event = new FakeEvent(List.of(assigneeDto("USER", id), assigneeDto("TEAM", id)));

        assertThatCode(() -> validator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsANullElementInTheArray() {
        List<AssigneeDto> assignees = new ArrayList<>();
        assignees.add(assigneeDto("USER", UUID.randomUUID().toString()));
        assignees.add(null);
        FakeEvent event = new FakeEvent(assignees);

        assertThatThrownBy(() -> validator.validate(event))
                .asInstanceOf(type(EventValidationException.class))
                .satisfies(e -> assertThat(e.getFieldErrors())
                        .anySatisfy(msg -> assertThat(msg).contains("assignees[1]").contains("must not be null")));
    }

    @Test
    void rejectsTheSameUserAssignedTwice() {
        String id = UUID.randomUUID().toString();
        FakeEvent event = new FakeEvent(List.of(assigneeDto("USER", id), assigneeDto("USER", id)));

        assertThatThrownBy(() -> validator.validate(event))
                .asInstanceOf(type(EventValidationException.class))
                .satisfies(e -> assertThat(e.getFieldErrors())
                        .anySatisfy(msg -> assertThat(msg).contains("assignees[1]").contains("duplicates")));
    }

    @Test
    void rejectsADuplicateIdRegardlessOfCasing() {
        String id = UUID.randomUUID().toString();
        FakeEvent event = new FakeEvent(List.of(
                assigneeDto("USER", id.toLowerCase()),
                assigneeDto("user", id.toUpperCase())));

        assertThatThrownBy(() -> validator.validate(event)).isInstanceOf(EventValidationException.class);
    }

    @Test
    void doesNotConfuseADuplicateTeamWithADuplicateUser() {
        String id = UUID.randomUUID().toString();
        FakeEvent event = new FakeEvent(List.of(
                assigneeDto("TEAM", id), assigneeDto("TEAM", id)));

        assertThatThrownBy(() -> validator.validate(event)).isInstanceOf(EventValidationException.class);
    }
}
