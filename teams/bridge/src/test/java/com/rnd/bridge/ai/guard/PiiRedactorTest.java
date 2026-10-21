package com.rnd.bridge.ai.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "write to dani.cohen@company.co.il today | write to [EMAIL] today",
            "call 052-1234567 now                    | call [PHONE] now",
            "call 0521234567 now                     | call [PHONE] now",
            "office 03-1234567                       | office [PHONE]",
            "call +972 52 123 4567                   | call [PHONE]",
            "US line +1 415 555 0100                 | US line [PHONE]",
            "card 4580 1234 5678 9015 on file       | card [CREDIT_CARD] on file",
            "card 4111-1111-1111-1111                | card [CREDIT_CARD]",
            "id 000000018 attached                   | id [ID] attached",
            "IBAN IL62 0108 0000 0009 9999 999       | IBAN [IBAN]",
    })
    void redactsStructuredPersonalData(String input, String expected) {
        assertThat(redactor.redact(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "meeting on 2026-10-20 at 10:30",
            "budget is 1,500,000 NIS",
            "ticket #123456789 is not an id",
            "order 4580123456789010 fails Luhn",
            "sprint 42 planning, 3 people",
    })
    void leavesOrdinaryTextAndNumbersAlone(String input) {
        assertThat(redactor.redact(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no personal data here"})
    void textWithoutPiiIsUnchanged(String input) {
        assertThat(redactor.redact(input)).isEqualTo(input);
    }
}
