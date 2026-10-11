package com.rnd.inbox.shared.messaging.kafka;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CompositeEventValidatorTest {

    private record Event(String value) {
    }

    @Test
    void runsEveryValidatorWhenAllOfThemPass() {
        @SuppressWarnings("unchecked")
        EventValidator<Event> first = mock(EventValidator.class);
        @SuppressWarnings("unchecked")
        EventValidator<Event> second = mock(EventValidator.class);
        Event event = new Event("ok");

        new CompositeEventValidator<>(List.of(first, second)).validate(event);

        verify(first).validate(event);
        verify(second).validate(event);
    }

    @Test
    void stopsAtTheFirstFailureAndNeverRunsLaterValidators() {
        @SuppressWarnings("unchecked")
        EventValidator<Event> first = mock(EventValidator.class);
        @SuppressWarnings("unchecked")
        EventValidator<Event> second = mock(EventValidator.class);
        Event event = new Event("bad");
        EventValidationException failure = new EventValidationException("bad event", List.of("value bad"));
        doThrowOnValidate(first, event, failure);

        assertThatThrownBy(() -> new CompositeEventValidator<>(List.of(first, second)).validate(event))
                .isSameAs(failure);

        verify(second, never()).validate(event);
    }

    @Test
    void anEmptyValidatorListAcceptsAnything() {
        assertThatCode(() -> new CompositeEventValidator<Event>(List.of()).validate(new Event("anything")))
                .doesNotThrowAnyException();
    }

    private void doThrowOnValidate(EventValidator<Event> validator, Event event, RuntimeException exception) {
        org.mockito.Mockito.doThrow(exception).when(validator).validate(event);
    }
}
