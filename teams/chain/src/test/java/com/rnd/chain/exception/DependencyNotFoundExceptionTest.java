package com.rnd.chain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyNotFoundExceptionTest {

    @Test
    void constructor_withMessageAndCause_setsBoth() {
        Throwable cause = new RuntimeException("root cause");

        DependencyNotFoundException exception = new DependencyNotFoundException("message", cause);

        assertThat(exception.getMessage()).isEqualTo("message");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
