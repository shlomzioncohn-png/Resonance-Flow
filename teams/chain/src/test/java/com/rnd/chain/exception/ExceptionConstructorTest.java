package com.rnd.chain.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionConstructorTest {

    private static Stream<Arguments> exceptionConstructors() {
        return Stream.of(
                Arguments.of("TaskNotFoundException",
                        (BiFunction<String, Throwable, RuntimeException>) TaskNotFoundException::new),
                Arguments.of("DependencyNotFoundException",
                        (BiFunction<String, Throwable, RuntimeException>) DependencyNotFoundException::new),
                Arguments.of("DuplicateDependencyException",
                        (BiFunction<String, Throwable, RuntimeException>) DuplicateDependencyException::new),
                Arguments.of("InvalidDependencyException",
                        (BiFunction<String, Throwable, RuntimeException>) InvalidDependencyException::new)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exceptionConstructors")
    void constructor_withMessageAndCause_setsBoth(String exceptionName,
                                                   BiFunction<String, Throwable, RuntimeException> constructor) {
        Throwable cause = new RuntimeException("root cause");

        RuntimeException exception = constructor.apply("message", cause);

        assertThat(exception.getMessage()).isEqualTo("message");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
