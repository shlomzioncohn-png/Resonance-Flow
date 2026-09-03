package com.rnd.inbox.bridge.task;

import com.rnd.inbox.messaging.kafka.EventValidationException;
import com.rnd.inbox.messaging.kafka.EventValidator;
import com.rnd.inbox.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.model.enums.SourceType;
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

class BridgeTaskEventValidationTest {

    private static ValidatorFactory validatorFactory;
    private static EventValidator<BridgeTaskEventDto> validator;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = new BeanValidationEventValidator<>(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private BridgeTaskEventDto validEvent() {
        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setOrganizationId(UUID.randomUUID().toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");
        dto.setStatus("NEW");
        dto.setUrgency(3);
        dto.setConfidence(0.5);
        return dto;
    }

    private void assertRejects(BridgeTaskEventDto dto, String... expectedFields) {
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
        BridgeTaskEventDto dto = validEvent();
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
        assertRejects(new BridgeTaskEventDto(), "organizationId", "title", "sourceRef", "sourceType");
    }

    @ParameterizedTest
    @EnumSource(value = SourceType.class, names = {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL"})
    void acceptsEveryStructuredPlatform(SourceType sourceType) {
        BridgeTaskEventDto dto = validEvent();
        dto.setSourceType(sourceType.name());

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = SourceType.class, names = {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL"},
            mode = EnumSource.Mode.EXCLUDE)
    void rejectsASourceTypeThatBelongsOnTheUnstructuredPath(SourceType sourceType) {
        BridgeTaskEventDto dto = validEvent();
        dto.setSourceType(sourceType.name());

        assertRejects(dto, "sourceType");
    }

    @Test
    void rejectsUnknownSourceType() {
        BridgeTaskEventDto dto = validEvent();
        dto.setSourceType("NOT_A_PLATFORM");

        assertRejects(dto, "sourceType");
    }

    @Test
    void rejectsUrgencyOutOfRange() {
        BridgeTaskEventDto dto = validEvent();
        dto.setUrgency(9);

        assertRejects(dto, "urgency");
    }

    @Test
    void rejectsConfidenceOutOfRange() {
        BridgeTaskEventDto dto = validEvent();
        dto.setConfidence(1.5);

        assertRejects(dto, "confidence");
    }

    @Test
    void rejectsNonPositiveEffortEstimate() {
        BridgeTaskEventDto dto = validEvent();
        dto.setEffortEstimateMinutes(0);

        assertRejects(dto, "effortEstimateMinutes");
    }

    @Test
    void rejectsInvalidOrganizationIdFormat() {
        BridgeTaskEventDto dto = validEvent();
        dto.setOrganizationId("not-a-uuid");

        assertRejects(dto, "organizationId");
    }

    @Test
    void rejectsInvalidOptionalUuids() {
        BridgeTaskEventDto dto = validEvent();
        dto.setProjectId("not-a-uuid");
        dto.setEventId("also-not-a-uuid");

        assertRejects(dto, "projectId", "eventId");
    }

    @Test
    void rejectsUnrecognizedEnumValues() {
        BridgeTaskEventDto dto = validEvent();
        dto.setStatus("NOT_A_STATUS");

        assertRejects(dto, "status");
    }

    @Test
    void rejectsMalformedDeadline() {
        BridgeTaskEventDto dto = validEvent();
        dto.setDeadline("not-a-date");

        assertRejects(dto, "deadline");
    }

    @Test
    void acceptsAnEventWithNoAssignmentDataAtAll() {
        assertThatCode(() -> validator.validate(validEvent())).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnAssignmentToAUser() {
        BridgeTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());
        dto.setAssignmentRole("ASSIGNEE");

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnAssignmentToATeamWithAnAssigner() {
        BridgeTaskEventDto dto = validEvent();
        dto.setAssigneeTeamId(UUID.randomUUID().toString());
        dto.setAssignmentRole("OWNER");
        dto.setAssignedByUserId(UUID.randomUUID().toString());

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnAssignmentWithBothAUserAndATeam() {
        BridgeTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());
        dto.setAssigneeTeamId(UUID.randomUUID().toString());
        dto.setAssignmentRole("ASSIGNEE");

        assertRejects(dto, "assigneeUserId");
    }

    @Test
    void rejectsAnAssignmentRoleWithoutAnAssignee() {
        BridgeTaskEventDto dto = validEvent();
        dto.setAssignmentRole("ASSIGNEE");

        assertRejects(dto, "assigneeUserId");
    }

    @Test
    void rejectsAnAssigneeWithoutAnAssignmentRole() {
        BridgeTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());

        assertRejects(dto, "assignmentRole");
    }

    @Test
    void rejectsUnrecognizedAssignmentRole() {
        BridgeTaskEventDto dto = validEvent();
        dto.setAssigneeUserId(UUID.randomUUID().toString());
        dto.setAssignmentRole("NOT_A_ROLE");

        assertRejects(dto, "assignmentRole");
    }

    @Test
    void reportsAMissingRequiredFieldExactlyOnce() {
        BridgeTaskEventDto dto = validEvent();
        dto.setOrganizationId("  ");

        assertThatThrownBy(() -> validator.validate(dto))
                .asInstanceOf(type(EventValidationException.class))
                .satisfies(e -> assertThat(e.getFieldErrors())
                        .filteredOn(msg -> msg.contains("organizationId"))
                        .hasSize(1));
    }
}
