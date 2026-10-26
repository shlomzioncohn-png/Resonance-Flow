package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.shared.messaging.kafka.EventValidationException;
import com.rnd.inbox.shared.messaging.kafka.EventValidator;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.task.model.SourceType;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class TaskCreationRequestEventValidationTest {

    private static ValidatorFactory validatorFactory;
    private static EventValidator<TaskCreationRequestEventDto> validator;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = new BeanValidationEventValidator<>(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private TaskCreationRequestEventDto validEvent() {
        TaskCreationRequestEventDto dto = new TaskCreationRequestEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setCorrelationId(UUID.randomUUID().toString());
        dto.setTenantId(UUID.randomUUID().toString());
        dto.setTitle("Send the revised pricing deck to Acme");
        dto.setSourceType("SLACK");
        dto.setSourceRef("slack:T012AB3CD:C024BE91L:1756550400.001900");
        dto.setConfidence(0.87);
        dto.setHasDependencies(false);
        return dto;
    }

    private void assertRejects(TaskCreationRequestEventDto dto, String... expectedFields) {
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
        TaskCreationRequestEventDto dto = validEvent();
        dto.setProjectId(UUID.randomUUID().toString());
        dto.setDescription("Extracted from the #sales-acme thread");
        dto.setTier("TACTICAL");
        dto.setRoleContext("SALESPERSON");
        dto.setUrgency(4);
        dto.setEffortEstimateMinutes(45);
        dto.setDeadline("2026-09-02T12:00:00Z");
        dto.setStatus("NEW");
        dto.setEffortEstimateConfidence(0.6);
        dto.setSuggestedOwnerUserId(UUID.randomUUID().toString());
        dto.setSuggestedOwnerConfidence(0.8);

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(SourceType.class)
    void acceptsEverySourceTypeIncludingTheStructuredOnes(SourceType sourceType) {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setSourceType(sourceType.name());

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownSourceType() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setSourceType("NOT_A_PLATFORM");

        assertRejects(dto, "sourceType");
    }

    @Test
    void rejectsAnEventWithoutACorrelationId() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setCorrelationId(null);

        assertRejects(dto, "correlationId");
    }

    @Test
    void rejectsACorrelationIdThatIsNotAUuid() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setCorrelationId("not-a-uuid");

        assertRejects(dto, "correlationId");
    }

    @Test
    void rejectsAnEventWithoutHasDependencies() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setHasDependencies(null);

        assertRejects(dto, "hasDependencies");
    }

    @Test
    void rejectsAnEventWithoutConfidenceBecauseThisPathIsMlInferred() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setConfidence(null);

        assertRejects(dto, "confidence");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    void rejectsAConfidenceOutsideZeroToOne(double confidence) {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setConfidence(confidence);

        assertRejects(dto, "confidence");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0})
    void acceptsSuggestionConfidencesAtTheBoundaries(double confidence) {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setEffortEstimateConfidence(confidence);
        dto.setSuggestedOwnerUserId(UUID.randomUUID().toString());
        dto.setSuggestedOwnerConfidence(confidence);

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    void rejectsAnEffortEstimateConfidenceOutsideZeroToOne(double confidence) {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setEffortEstimateConfidence(confidence);

        assertRejects(dto, "effortEstimateConfidence");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    void rejectsASuggestedOwnerConfidenceOutsideZeroToOne(double confidence) {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setSuggestedOwnerUserId(UUID.randomUUID().toString());
        dto.setSuggestedOwnerConfidence(confidence);

        assertRejects(dto, "suggestedOwnerConfidence");
    }

    @Test
    void rejectsASuggestedOwnerUserIdThatIsNotAUuid() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setSuggestedOwnerUserId("not-a-uuid");

        assertRejects(dto, "suggestedOwnerUserId");
    }

    @Test
    void acceptsASuggestedOwnerWithoutAConfidence() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setSuggestedOwnerUserId(UUID.randomUUID().toString());

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsASuggestedOwnerConfidenceWithoutASuggestedOwner() {
        TaskCreationRequestEventDto dto = validEvent();
        dto.setSuggestedOwnerConfidence(0.8);

        assertRejects(dto, "suggestedOwnerConfidence");
    }

    @Test
    void rejectsAnEventMissingTheFieldsSharedWithTheStructuredPath() {
        TaskCreationRequestEventDto dto = new TaskCreationRequestEventDto();
        dto.setCorrelationId(UUID.randomUUID().toString());
        dto.setConfidence(0.5);
        dto.setHasDependencies(false);

        assertRejects(dto, "tenantId", "title", "sourceType", "sourceRef");
    }
}
