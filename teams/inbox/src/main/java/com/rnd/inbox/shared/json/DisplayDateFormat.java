package com.rnd.inbox.shared.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class DisplayDateFormat {

    public static final ZoneId ZONE = ZoneId.of("Asia/Jerusalem");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZONE);
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);

    private DisplayDateFormat() {
    }

    public static String formatDate(Instant instant) {
        return DATE.format(instant);
    }

    public static String formatDateTime(Instant instant) {
        return DATE_TIME.format(instant);
    }

    public static class DateOnlySerializer extends StdSerializer<Instant> {

        public DateOnlySerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(formatDate(value));
        }
    }

    public static class DateTimeSerializer extends StdSerializer<Instant> {

        public DateTimeSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(formatDateTime(value));
        }
    }
}
