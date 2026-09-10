package com.rnd.chain.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionConstructorTest {

    /** Both constructor references for one exception type. */
    private record ExceptionConstructors(Function<String, RuntimeException> messageOnly,
                                          BiFunction<String, Throwable, RuntimeException> messageAndCause) {
    }

    private static Stream<Arguments> exceptionConstructors() {
        return Stream.of(
                Arguments.of("TaskNotFoundException",
                        new ExceptionConstructors(TaskNotFoundException::new, TaskNotFoundException::new)),
                Arguments.of("DependencyNotFoundException",
                        new ExceptionConstructors(DependencyNotFoundException::new, DependencyNotFoundException::new)),
                Arguments.of("DuplicateDependencyException",
                        new ExceptionConstructors(DuplicateDependencyException::new, DuplicateDependencyException::new)),
                Arguments.of("InvalidDependencyException",
                        new ExceptionConstructors(InvalidDependencyException::new, InvalidDependencyException::new))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exceptionConstructors")
    void constructor_withMessageAndCause_setsBoth(String exceptionName, ExceptionConstructors constructors) {
        Throwable cause = new RuntimeException("root cause");

        RuntimeException exception = constructors.messageAndCause().apply("message", cause);

        assertThat(exception.getMessage()).isEqualTo("message");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exceptionConstructors")
    void constructor_withMessageOnly_setsMessageAndLeavesCauseNull(String exceptionName, ExceptionConstructors constructors) {
        RuntimeException exception = constructors.messageOnly().apply("message");

        assertThat(exception.getMessage()).isEqualTo("message");
        assertThat(exception.getCause()).isNull();
    }
}
