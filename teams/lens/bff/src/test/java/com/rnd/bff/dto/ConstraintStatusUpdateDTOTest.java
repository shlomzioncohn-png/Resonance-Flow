package com.rnd.bff.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ConstraintStatusUpdateDTOTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SATISFIED", "WAIVED"})
    void chainsStatusesAreValid(String status) {
        assertThat(validator.validate(new ConstraintStatusUpdateDTO(status))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "satisfied", "SATISFIED ", "OPEN"})
    void anyOtherStatusIsRejected(String status) {
        Set<ConstraintViolation<ConstraintStatusUpdateDTO>> violations =
                validator.validate(new ConstraintStatusUpdateDTO(status));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("status");
            assertThat(v.getMessage()).isEqualTo("status must be one of: PENDING, SATISFIED, WAIVED");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingStatusIsRejected(String status) {
        assertThat(validator.validate(new ConstraintStatusUpdateDTO(status)))
                .extracting(ConstraintViolation::getMessage)
                .contains("status is required");
    }
}
