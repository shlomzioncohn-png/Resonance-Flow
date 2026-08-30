package com.rnd.chain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskNotFoundExceptionTest {

    @Test
    void constructor_withMessageAndCause_setsBoth() {
        Throwable cause = new RuntimeException("root cause");

        TaskNotFoundException exception = new TaskNotFoundException("message", cause);

        assertThat(exception.getMessage()).isEqualTo("message");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
