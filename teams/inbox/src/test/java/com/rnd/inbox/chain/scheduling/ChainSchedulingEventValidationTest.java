package com.rnd.inbox.chain.scheduling;

import com.rnd.inbox.messaging.kafka.EventValidationException;
import com.rnd.inbox.messaging.kafka.EventValidator;
import com.rnd.inbox.messaging.validation.BeanValidationEventValidator;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class ChainSchedulingEventValidationTest {

    private static ValidatorFactory validatorFactory;
    private static EventValidator<ChainSchedulingEventDto> validator;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = new BeanValidationEventValidator<>(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private ChainSchedulingEventDto validEvent() {
        ChainSchedulingEventDto dto = new ChainSchedulingEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setTaskId(UUID.randomUUID().toString());
        dto.setAvailable(true);
        return dto;
    }

    private void assertRejects(ChainSchedulingEventDto dto, String... expectedFields) {
        assertThatThrownBy(() -> validator.validate(dto))
                .asInstanceOf(type(EventValidationException.class))
                .satisfies(ex -> {
                    for (String field : expectedFields) {
                        assertThat(ex.getFieldErrors()).anySatisfy(msg -> assertThat(msg).contains(field));
                    }
                });
    }

    @Test
    void acceptsAnEventWithoutAPriorityScore() {
        assertThatCode(() -> validator.validate(validEvent())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 5.5, 10.0})
    void acceptsAPriorityScoreInsideTheAgreedRange(double priorityScore) {
        ChainSchedulingEventDto dto = validEvent();
        dto.setPriorityScore(priorityScore);

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.9, 10.1, 11.0, -3.0})
    void rejectsAPriorityScoreOutsideOneToTen(double priorityScore) {
        ChainSchedulingEventDto dto = validEvent();
        dto.setPriorityScore(priorityScore);

        assertRejects(dto, "priorityScore");
    }

    @Test
    void acceptsIsAvailableSetToFalse() {
        ChainSchedulingEventDto dto = validEvent();
        dto.setAvailable(false);

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEventWithoutIsAvailable() {
        ChainSchedulingEventDto dto = validEvent();
        dto.setAvailable(null);

        assertRejects(dto, "available");
    }

    @Test
    void rejectsAnEventWithoutATaskId() {
        ChainSchedulingEventDto dto = validEvent();
        dto.setTaskId(null);

        assertRejects(dto, "taskId");
    }

    @Test
    void rejectsATaskIdThatIsNotAUuid() {
        ChainSchedulingEventDto dto = validEvent();
        dto.setTaskId("not-a-uuid");

        assertRejects(dto, "taskId");
    }

    @Test
    void rejectsAnEventIdThatIsNotAUuid() {
        ChainSchedulingEventDto dto = validEvent();
        dto.setEventId("not-a-uuid");

        assertRejects(dto, "eventId");
    }

    @Test
    void acceptsAnEventWithoutAnEventId() {
        ChainSchedulingEventDto dto = validEvent();
        dto.setEventId(null);

        assertThatCode(() -> validator.validate(dto)).doesNotThrowAnyException();
    }
}
