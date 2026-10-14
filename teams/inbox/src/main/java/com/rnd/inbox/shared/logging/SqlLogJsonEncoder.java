package com.rnd.inbox.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.slf4j.event.KeyValuePair;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Renders a SQL log event as one JSON object per line, with the fields in a fixed order:
 * timestamp, level, event, duration_ms, request_id, operation, sql. request_id and operation
 * are left out for statements that no HTTP request issued.
 */
public class SqlLogJsonEncoder extends EncoderBase<ILoggingEvent> {

    private static final JsonFactory JSON = new JsonFactory();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        StringWriter line = new StringWriter();
        try (JsonGenerator json = JSON.createGenerator(line)) {
            Map<String, String> mdc = event.getMDCPropertyMap();
            json.writeStartObject();
            json.writeStringField("timestamp", TIMESTAMP.format(event.getInstant()));
            json.writeStringField("level", event.getLevel().toString());
            writeIfPresent(json, SqlQueryLoggingListener.EVENT_KEY,
                    keyValue(event, SqlQueryLoggingListener.EVENT_KEY));
            if (keyValue(event, SqlQueryLoggingListener.DURATION_KEY) instanceof Number duration) {
                json.writeNumberField(SqlQueryLoggingListener.DURATION_KEY, duration.longValue());
            }
            writeIfPresent(json, SqlLogRequestInterceptor.REQUEST_ID_KEY,
                    mdc.get(SqlLogRequestInterceptor.REQUEST_ID_KEY));
            writeIfPresent(json, SqlLogRequestInterceptor.OPERATION_KEY,
                    mdc.get(SqlLogRequestInterceptor.OPERATION_KEY));
            json.writeStringField("sql", event.getFormattedMessage());
            json.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        line.append(CoreConstants.LINE_SEPARATOR);
        return line.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    private static void writeIfPresent(JsonGenerator json, String field, Object value) throws IOException {
        if (value != null) {
            json.writeStringField(field, value.toString());
        }
    }

    private static Object keyValue(ILoggingEvent event, String key) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return null;
        }
        return pairs.stream().filter(pair -> key.equals(pair.key)).map(pair -> pair.value).findFirst().orElse(null);
    }
}
