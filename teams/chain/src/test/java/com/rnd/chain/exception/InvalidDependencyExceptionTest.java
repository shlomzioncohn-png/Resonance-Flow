package com.rnd.chain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidDependencyExceptionTest {

    @Test
    void constructor_withMessageAndCause_setsBoth() {
        Throwable cause = new RuntimeException("root cause");

        InvalidDependencyException exception = new InvalidDependencyException("message", cause);

        assertThat(exception.getMessage()).isEqualTo("message");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
