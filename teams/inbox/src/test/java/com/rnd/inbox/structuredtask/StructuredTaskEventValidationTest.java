package com.rnd.inbox.structuredtask;

import com.rnd.inbox.shared.messaging.kafka.EventValidationException;
import com.rnd.inbox.shared.messaging.kafka.EventValidator;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.task.assignment.AssigneeDto;
import com.rnd.inbox.task.model.SourceType;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class StructuredTaskEventValidationTest {

    private static ValidatorFactory validatorFactory;
    private static EventValidator<StructuredTaskEventDto> validator;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = new BeanValidationEventValidator<>(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private StructuredTaskEventDto validEvent() {
        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setTenantId(UUID.randomUUID().toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");
        dto.setStatus("NEW");
        dto.setUrgency(3);
        dto.setConfidence(0.5);
        return dto;
    }

    private void assertRejects(StructuredTaskEventDto dto, String... expectedFields) {
        assertThatThrownBy(() -> validator.validate(dto))
                .asInstanceOf(type(EventValidationException.class))
                .satisfies(ex -> {
                    for (String field : expectedFields) {
                        assertThat(ex.getFieldErrors()).anySatisfy(msg -> assertThat(msg).contains(field));
                    }
                });
    }

    @Test
    void acceptsAValidEvent() {
        assertThatCode(() -> validator.validate(validEvent())).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnEventWithEveryOptionalFieldPopulated() {
        StructuredTaskEventDto dto = validEvent();
        dto.setProjectId(UUID.randomUUID().toString());
        dto.setDescription("The test is flaky on CI only");
        dto.setTier("TACTICAL");
        dto.setRoleContext("DEVELOPER");
        dto.setEffortEstimateMinutes(45);
        dto.setDeadline("2026-01-01T00:00:00Z");

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertRejects(new StructuredTaskEventDto(), "tenantId", "title", "sourceRef", "sourceType");
    }

    @ParameterizedTest
    @EnumSource(value = SourceType.class, names = {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL", "JIRA_ISSUE"})
    void acceptsEveryStructuredPlatform(SourceType sourceType) {
        StructuredTaskEventDto dto = validEvent();
        dto.setSourceType(sourceType.name());

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = SourceType.class, names = {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL", "JIRA_ISSUE"},
            mode = EnumSource.Mode.EXCLUDE)
    void rejectsASourceTypeThatBelongsOnTheUnstructuredPath(SourceType sourceType) {
        StructuredTaskEventDto dto = validEvent();
        dto.setSourceType(sourceType.name());

        assertRejects(dto, "sourceType");
    }

    @Test
    void rejectsUnknownSourceType() {
        StructuredTaskEventDto dto = validEvent();
        dto.setSourceType("NOT_A_PLATFORM");

        assertRejects(dto, "sourceType");
    }

    @Test
    void rejectsUrgencyOutOfRange() {
        StructuredTaskEventDto dto = validEvent();
        dto.setUrgency(9);

        assertRejects(dto, "urgency");
    }

    @Test
    void rejectsConfidenceOutOfRange() {
        StructuredTaskEventDto dto = validEvent();
        dto.setConfidence(1.5);

        assertRejects(dto, "confidence");
    }

    @Test
    void rejectsNonPositiveEffortEstimate() {
        StructuredTaskEventDto dto = validEvent();
        dto.setEffortEstimateMinutes(0);

        assertRejects(dto, "effortEstimateMinutes");
    }

    @Test
    void rejectsInvalidTenantIdFormat() {
        StructuredTaskEventDto dto = validEvent();
        dto.setTenantId("not-a-uuid");

        assertRejects(dto, "tenantId");
    }

    @Test
    void rejectsInvalidOptionalUuids() {
        StructuredTaskEventDto dto = validEvent();
        dto.setProjectId("not-a-uuid");
        dto.setEventId("also-not-a-uuid");

        assertRejects(dto, "projectId", "eventId");
    }

    @Test
    void rejectsUnrecognizedEnumValues() {
        StructuredTaskEventDto dto = validEvent();
        dto.setStatus("NOT_A_STATUS");

        assertRejects(dto, "status");
    }

    @Test
    void rejectsMalformedDeadline() {
        StructuredTaskEventDto dto = validEvent();
        dto.setDeadline("not-a-date");

        assertRejects(dto, "deadline");
    }

    @Test
    void acceptsAnEventWithNoAssignmentDataAtAll() {
        assertThatCode(() -> validator.validate(validEvent())).doesNotThrowAnyException();
    }

    private AssigneeDto assigneeDto(String assigneeType, String id, String assignmentRole) {
        AssigneeDto dto = new AssigneeDto();
        dto.setAssigneeType(assigneeType);
        dto.setId(id);
        dto.setAssignmentRole(assignmentRole);
        return dto;
    }

    @Test
    void acceptsAnAssignmentToAUser() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssignees(List.of(assigneeDto("USER", UUID.randomUUID().toString(), "ASSIGNEE")));

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnAssignmentToATeamWithAnAssigner() {
        StructuredTaskEventDto dto = validEvent();
        AssigneeDto assignee = assigneeDto("TEAM", UUID.randomUUID().toString(), "OWNER");
        assignee.setAssignedBy(UUID.randomUUID().toString());
        dto.setAssignees(List.of(assignee));

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void acceptsMultipleAssigneesInTheArray() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssignees(List.of(
                assigneeDto("USER", UUID.randomUUID().toString(), "OWNER"),
                assigneeDto("USER", UUID.randomUUID().toString(), "COLLABORATOR")));

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnAssigneeWithoutAnAssignmentRole() {
        StructuredTaskEventDto dto = validEvent();
        AssigneeDto assignee = assigneeDto("USER", UUID.randomUUID().toString(), null);
        dto.setAssignees(List.of(assignee));

        assertRejects(dto, "assignmentRole");
    }

    @Test
    void rejectsAnAssigneeWithAnInvalidAssigneeType() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssignees(List.of(assigneeDto("NOT_A_TYPE", UUID.randomUUID().toString(), "ASSIGNEE")));

        assertRejects(dto, "assigneeType");
    }

    @Test
    void rejectsAnAssigneeWithAnInvalidId() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssignees(List.of(assigneeDto("USER", "not-a-uuid", "ASSIGNEE")));

        assertRejects(dto, "id");
    }

    @Test
    void rejectsUnrecognizedAssignmentRole() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssignees(List.of(assigneeDto("USER", UUID.randomUUID().toString(), "NOT_A_ROLE")));

        assertRejects(dto, "assignmentRole");
    }

    @Test
    void reportsAMissingRequiredFieldExactlyOnce() {
        StructuredTaskEventDto dto = validEvent();
        dto.setTenantId("  ");

        assertThatThrownBy(() -> validator.validate(dto))
                .asInstanceOf(type(EventValidationException.class))
                .satisfies(e -> assertThat(e.getFieldErrors())
                        .filteredOn(msg -> msg.contains("tenantId"))
                        .hasSize(1));
    }
}
