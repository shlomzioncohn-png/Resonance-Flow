package com.rnd.inbox.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalNotBlankValidatorTest {

    private final OptionalNotBlankValidator validator = new OptionalNotBlankValidator();

    @Test
    void isValid_whenValueIsNull_returnsTrue() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void isValid_whenValueIsBlankOrEmpty_returnsFalse() {
        assertThat(validator.isValid("", null)).isFalse();
        assertThat(validator.isValid("   ", null)).isFalse();
    }

    @Test
    void isValid_whenValueHasContent_returnsTrue() {
        assertThat(validator.isValid("manager", null)).isTrue();
    }
}
