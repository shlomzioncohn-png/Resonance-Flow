package com.rnd.inbox.shared.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayDateFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dateTimeSerializer_inWinter_convertsToIsraelStandardTime() throws Exception {
        String json = objectMapper.writeValueAsString(new Dates(null, Instant.parse("2026-01-15T10:00:00Z")));

        assertThat(json).contains("\"moment\":\"15/01/2026 12:00\"");
    }

    @Test
    void dateTimeSerializer_inSummer_convertsToIsraelDaylightTime() throws Exception {
        String json = objectMapper.writeValueAsString(new Dates(null, Instant.parse("2026-07-15T10:00:00Z")));

        assertThat(json).contains("\"moment\":\"15/07/2026 13:00\"");
    }

    @Test
    void dateOnlySerializer_whenUtcIsStillThePreviousDay_usesTheIsraelDay() throws Exception {
        String json = objectMapper.writeValueAsString(new Dates(Instant.parse("2026-01-15T22:30:00Z"), null));

        assertThat(json).contains("\"day\":\"16/01/2026\"");
    }

    @Test
    void serializers_whenValueIsNull_writeNull() throws Exception {
        String json = objectMapper.writeValueAsString(new Dates(null, null));

        assertThat(json).isEqualTo("{\"day\":null,\"moment\":null}");
    }

    private record Dates(
            @JsonSerialize(using = DisplayDateFormat.DateOnlySerializer.class) Instant day,
            @JsonSerialize(using = DisplayDateFormat.DateTimeSerializer.class) Instant moment) {
    }
}
