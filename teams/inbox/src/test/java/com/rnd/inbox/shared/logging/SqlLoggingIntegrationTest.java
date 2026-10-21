package com.rnd.inbox.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.zaxxer.hikari.HikariDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

// Parameters are off by default; most assertions here find a line by the value inlined into it.
@AutoConfigureMockMvc
@TestPropertySource(properties = "inbox.sql-logging.include-parameters=true")
class SqlLoggingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private MockMvc mockMvc;

    private SqlLogCapture capture;

    @BeforeEach
    void setUp() {
        capture = new SqlLogCapture();
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    @Test
    void dataSource_inTheRealApplicationContext_wrapsTheConnectionPool() {
        assertThat(dataSource).isInstanceOf(ProxyDataSource.class);
        assertThat(((ProxyDataSource) dataSource).getDataSource()).isInstanceOf(HikariDataSource.class);
    }

    @Test
    void repositoryQuery_isLoggedWithItsParameterInlined() {
        UUID unknownId = UUID.randomUUID();

        tenantRepository.findById(unknownId);

        assertThat(capture.messages())
                .filteredOn(message -> message.contains(unknownId.toString()))
                .singleElement().asString()
                .contains("'" + unknownId + "'")
                .doesNotContain("?");
    }

    @Test
    void derivedRepositoryQuery_isLoggedWithItsStringParameterInlined() {
        String slug = "no-such-tenant-" + UUID.randomUUID();

        tenantRepository.findBySlug(slug);

        assertThat(capture.messages())
                .filteredOn(message -> message.contains(slug))
                .singleElement().asString()
                .contains("'" + slug + "'");
    }

    @Test
    void loggedQuery_whenRunAgainExactlyAsLogged_returnsTheSameRow() {
        // The whole point of the log: copy a line into a SQL console and run it. The text value
        // is deliberately hostile - quotes, an injection-shaped tail, a backslash, a question
        // mark and a newline - and must come back unchanged as one column.
        String marker = UUID.randomUUID().toString();
        String hostileText = "O'Brien'; DROP TABLE tasks; -- \\ why? \n" + marker;
        UUID id = UUID.randomUUID();
        OffsetDateTime moment = OffsetDateTime.parse("2026-10-04T10:15:30+03:00");
        String sql = "select cast(? as text) as txt, cast(? as integer) as num, cast(? as uuid) as id, "
                + "cast(? as boolean) as flag, cast(? as text) as missing, cast(? as timestamptz) as at";

        Map<String, Object> original = jdbcTemplate.queryForMap(sql, hostileText, 42, id, true, null, moment);

        String loggedSql = loggedSqlContaining(marker);
        Map<String, Object> replayed = jdbcTemplate.queryForMap(loggedSql);

        assertThat(loggedSql.lines()).hasSize(1);
        assertThat(replayed).isEqualTo(original);
        assertThat(replayed.get("txt")).isEqualTo(hostileText);
        assertThat(replayed.get("missing")).isNull();
    }

    @Test
    void failingQuery_isLoggedAsFailed() {
        String missingTable = "missing_" + UUID.randomUUID().toString().replace("-", "");

        assertThatThrownBy(() -> jdbcTemplate.queryForList("select * from " + missingTable))
                .isInstanceOf(RuntimeException.class);

        assertThat(capture.events())
                .filteredOn(event -> event.getFormattedMessage().contains(missingTable))
                .singleElement()
                .satisfies(event -> assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.QUERY_FAILED));
    }

    @Test
    void fastQuery_isLoggedAtDebug() {
        String marker = UUID.randomUUID().toString();

        jdbcTemplate.queryForObject("select cast(? as text)", String.class, marker);

        assertThat(capture.events())
                .filteredOn(event -> event.getFormattedMessage().contains(marker))
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG);
    }

    @Test
    void sqlLogger_isConfiguredToWriteOnlyToItsOwnFile() {
        Logger logger = (Logger) LoggerFactory.getLogger(SqlQueryLoggingListener.class);

        Appender<ILoggingEvent> appender = logger.getAppender("SQL_FILE");

        assertThat(logger.isAdditive()).isFalse();
        assertThat(logger.getEffectiveLevel()).isEqualTo(Level.DEBUG);
        assertThat(appender).isInstanceOf(RollingFileAppender.class);
        assertThat(((RollingFileAppender<ILoggingEvent>) appender).getFile()).endsWith("inbox-sql.log");
    }

    @Test
    void sqlLogFile_isWrittenAsJsonLines() {
        Logger logger = (Logger) LoggerFactory.getLogger(SqlQueryLoggingListener.class);
        RollingFileAppender<ILoggingEvent> appender =
                (RollingFileAppender<ILoggingEvent>) logger.getAppender("SQL_FILE");

        assertThat(appender.getEncoder()).isInstanceOf(SqlLogJsonEncoder.class);
    }

    @Test
    void queryIssuedWhileServingARequest_carriesItsIdAndOperation() throws Exception {
        // An unknown actor id is looked up in users and rejected - one read, nothing written.
        UUID unknownActor = UUID.randomUUID();

        MvcResult result = mockMvc.perform(get("/api/v1/tenants?size=1")
                        .header("X-Actor-Id", unknownActor.toString()))
                .andReturn();

        String requestId = result.getResponse().getHeader(SqlLogRequestInterceptor.REQUEST_ID_HEADER);
        assertThat(requestId).matches("req-[0-9a-f]{8}");
        assertThat(capture.events())
                .filteredOn(event -> event.getFormattedMessage().contains(unknownActor.toString()))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry(SqlLogRequestInterceptor.REQUEST_ID_KEY, requestId)
                        .containsEntry(SqlLogRequestInterceptor.OPERATION_KEY, "LIST_TENANTS"));
    }

    @Test
    void requestWithItsOwnRequestId_isLoggedAndAnsweredWithThatId() throws Exception {
        UUID unknownActor = UUID.randomUUID();
        String suppliedId = "client-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(get("/api/v1/tenants?size=1")
                        .header("X-Actor-Id", unknownActor.toString())
                        .header(SqlLogRequestInterceptor.REQUEST_ID_HEADER, suppliedId))
                .andReturn();

        assertThat(result.getResponse().getHeader(SqlLogRequestInterceptor.REQUEST_ID_HEADER)).isEqualTo(suppliedId);
        assertThat(capture.events())
                .filteredOn(event -> event.getFormattedMessage().contains(unknownActor.toString()))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry(SqlLogRequestInterceptor.REQUEST_ID_KEY, suppliedId));
    }

    @Test
    void queryIssuedOutsideARequest_carriesNoRequestFields() {
        UUID unknownId = UUID.randomUUID();

        tenantRepository.findById(unknownId);

        assertThat(capture.events())
                .filteredOn(event -> event.getFormattedMessage().contains(unknownId.toString()))
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap())
                        .doesNotContainKeys(SqlLogRequestInterceptor.REQUEST_ID_KEY,
                                SqlLogRequestInterceptor.OPERATION_KEY));
    }

    @Test
    void backgroundOutboxPoll_isNotLogged() {
        jdbcTemplate.queryForList("select event_id from outbox where status = 'pending' limit 1");

        assertThat(capture.messages()).noneMatch(message -> message.contains("from outbox"));
    }

    @Test
    void mainLog_stillGoesToConsoleAndFile() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        assertThat(root.getAppender("CONSOLE")).isNotNull();
        assertThat(root.getAppender("FILE")).isNotNull();
        assertThat(root.getLevel()).isEqualTo(Level.INFO);
    }

    private String loggedSqlContaining(String marker) {
        return capture.messages().stream()
                .filter(candidate -> candidate.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No logged SQL contains " + marker));
    }
}
