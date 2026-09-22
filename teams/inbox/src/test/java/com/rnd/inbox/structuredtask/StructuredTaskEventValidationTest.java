package com.rnd.inbox.structuredtask;

import com.rnd.inbox.shared.messaging.kafka.EventValidationException;
import com.rnd.inbox.shared.messaging.kafka.EventValidator;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.task.SourceType;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
    @EnumSource(value = SourceType.class, names = {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL"})
    void acceptsEveryStructuredPlatform(SourceType sourceType) {
        StructuredTaskEventDto dto = validEvent();
        dto.setSourceType(sourceType.name());

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = SourceType.class, names = {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL"},
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

    @Test
    void acceptsAnAssignmentToAUser() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());
        dto.setAssignmentRole("ASSIGNEE");

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnAssignmentToATeamWithAnAssigner() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssigneeTeamId(UUID.randomUUID().toString());
        dto.setAssignmentRole("OWNER");
        dto.setAssignedByUserId(UUID.randomUUID().toString());

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnAssignmentWithBothAUserAndATeam() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());
        dto.setAssigneeTeamId(UUID.randomUUID().toString());
        dto.setAssignmentRole("ASSIGNEE");

        assertRejects(dto, "assigneeUserId");
    }

    @Test
    void rejectsAnAssignmentRoleWithoutAnAssignee() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssignmentRole("ASSIGNEE");

        assertRejects(dto, "assigneeUserId");
    }

    @Test
    void rejectsAnAssigneeWithoutAnAssignmentRole() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());

        assertRejects(dto, "assignmentRole");
    }

    @Test
    void rejectsUnrecognizedAssignmentRole() {
        StructuredTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());
        dto.setAssignmentRole("NOT_A_ROLE");

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
