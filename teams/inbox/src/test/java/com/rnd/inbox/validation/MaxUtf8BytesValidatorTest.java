package com.rnd.inbox.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaxUtf8BytesValidatorTest {

    private final MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();

    @BeforeEach
    void setUp() {
        MaxUtf8Bytes annotation = mock(MaxUtf8Bytes.class);
        when(annotation.value()).thenReturn(72);
        validator.initialize(annotation);
    }

    @Test
    void isValid_whenValueIsNull_returnsTrue() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void isValid_whenAsciiValueIsExactlyMaxBytes_returnsTrue() {
        assertThat(validator.isValid("a".repeat(72), null)).isTrue();
    }

    @Test
    void isValid_whenMultibyteValueExceedsMaxBytes_returnsFalse() {
        String fortyHebrewLetters = "א".repeat(40);

        assertThat(fortyHebrewLetters).hasSize(40);
        assertThat(validator.isValid(fortyHebrewLetters, null)).isFalse();
    }
}
