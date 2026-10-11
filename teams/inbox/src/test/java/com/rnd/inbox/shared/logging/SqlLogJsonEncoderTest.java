package com.rnd.inbox.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.CoreConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLogJsonEncoderTest {

    private static final Instant MOMENT = Instant.parse("2026-10-04T10:30:15Z");

    private final SqlLogJsonEncoder encoder = new SqlLogJsonEncoder();
    private final LoggerContext loggerContext = new LoggerContext();

    @Test
    void encode_statementIssuedByARequest_writesEveryFieldInTheAgreedOrder() {
        LoggingEvent event = event(Level.WARN, SqlLogEvent.SLOW_QUERY, 348,
                "SELECT * FROM tasks WHERE owner_id = 125 AND status = 'APPROVED' ORDER BY deadline ASC LIMIT 20",
                Map.of("request_id", "req-a123", "operation", "GET_TASKS"));

        assertThat(encode(event)).isEqualTo("{\"timestamp\":\"2026-10-04T10:30:15.000Z\",\"level\":\"WARN\","
                + "\"event\":\"SLOW_QUERY\",\"duration_ms\":348,\"request_id\":\"req-a123\","
                + "\"operation\":\"GET_TASKS\",\"sql\":\"SELECT * FROM tasks WHERE owner_id = 125 AND "
                + "status = 'APPROVED' ORDER BY deadline ASC LIMIT 20\"}" + CoreConstants.LINE_SEPARATOR);
    }

    @Test
    void encode_statementIssuedOutsideARequest_leavesRequestFieldsOut() throws Exception {
        LoggingEvent event = event(Level.DEBUG, SqlLogEvent.QUERY, 7, "select 1");

        assertThat(parse(encode(event)).keySet())
                .containsExactly("timestamp", "level", "event", "duration_ms", "sql");
    }

    @Test
    void encode_durationIsWrittenAsANumber() throws Exception {
        LoggingEvent event = event(Level.DEBUG, SqlLogEvent.QUERY, 7, "select 1");

        assertThat(parse(encode(event)).get("duration_ms")).isEqualTo(7);
    }

    @Test
    void encode_timestampIsConvertedToUtcWithMilliseconds() throws Exception {
        LoggingEvent event = event(Level.DEBUG, SqlLogEvent.QUERY, 7, "select 1");
        event.setInstant(Instant.parse("2026-10-04T13:30:15.932+03:00"));

        assertThat(parse(encode(event)).get("timestamp")).isEqualTo("2026-10-04T10:30:15.932Z");
    }

    @Test
    void encode_sqlWithQuotesBackslashesAndNewlines_staysOnOneLineAndRoundTrips() throws Exception {
        String sql = "select \"t\".\"id\" from t where a = E'x\\\\y'\n  and b = 'line\r\nbreak\ttab'";
        LoggingEvent event = event(Level.DEBUG, SqlLogEvent.QUERY, 7, sql);

        String line = encode(event);

        assertThat(line.strip().lines()).hasSize(1);
        assertThat(parse(line).get("sql")).isEqualTo(sql);
    }

    @Test
    void encode_forgedJsonInsideTheSql_cannotAddOrOverrideFields() throws Exception {
        String sql = "select 1\", \"level\": \"ERROR\", \"injected\": \"yes";
        LoggingEvent event = event(Level.DEBUG, SqlLogEvent.QUERY, 7, sql);

        Map<String, Object> parsed = parse(encode(event));

        assertThat(parsed).doesNotContainKey("injected");
        assertThat(parsed.get("level")).isEqualTo("DEBUG");
        assertThat(parsed.get("sql")).isEqualTo(sql);
    }

    @Test
    void encode_nonAsciiText_isKeptReadable() {
        LoggingEvent event = event(Level.DEBUG, SqlLogEvent.QUERY, 7, "select 'משימה חדשה'");

        assertThat(encode(event)).contains("select 'משימה חדשה'");
    }

    @Test
    void encode_eventWithoutSqlLogFields_stillProducesValidJson() throws Exception {
        LoggingEvent event = new LoggingEvent("fqcn", loggerContext.getLogger("test"), Level.INFO, "plain", null, null);
        event.setInstant(MOMENT);
        event.setMDCPropertyMap(Map.of());

        assertThat(parse(encode(event)).keySet()).containsExactly("timestamp", "level", "sql");
    }

    @Test
    void headerAndFooter_areEmpty() {
        assertThat(encoder.headerBytes()).isNull();
        assertThat(encoder.footerBytes()).isNull();
    }

    // ---- helpers ----

    private LoggingEvent event(Level level, SqlLogEvent sqlLogEvent, long durationMs, String sql) {
        return event(level, sqlLogEvent, durationMs, sql, Map.of());
    }

    private LoggingEvent event(Level level, SqlLogEvent sqlLogEvent, long durationMs, String sql,
                               Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent("fqcn", loggerContext.getLogger("test"), level, sql, null, null);
        event.setInstant(MOMENT);
        event.setMDCPropertyMap(mdc);
        event.addKeyValuePair(new KeyValuePair(SqlQueryLoggingListener.EVENT_KEY, sqlLogEvent));
        event.addKeyValuePair(new KeyValuePair(SqlQueryLoggingListener.DURATION_KEY, durationMs));
        return event;
    }

    private String encode(LoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String line) throws Exception {
        return new ObjectMapper().readValue(line, LinkedHashMap.class);
    }
}
