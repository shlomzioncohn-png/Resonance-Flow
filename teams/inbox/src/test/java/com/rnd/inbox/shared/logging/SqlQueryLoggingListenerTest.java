package com.rnd.inbox.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.rnd.inbox.shared.config.SqlLoggingProperties;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlQueryLoggingListenerTest {

    private static final long THRESHOLD_MS = 100;

    private SqlLoggingProperties properties;
    private SqlQueryLoggingListener listener;
    private SqlLogCapture capture;

    @BeforeEach
    void setUp() {
        properties = new SqlLoggingProperties();
        properties.setSlowQueryThresholdMs(THRESHOLD_MS);
        listener = new SqlQueryLoggingListener(properties);
        capture = new SqlLogCapture();
        capture.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        capture.close();
        MDC.clear();
    }

    // ---- event and level selection ----

    @Test
    void afterQuery_fasterThanThreshold_logsQueryAtDebug() {
        listener.afterQuery(execution(12, true), List.of(query("select 1")));

        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.QUERY);
            assertThat(SqlLogCapture.durationOf(event)).isEqualTo(12);
            assertThat(event.getFormattedMessage()).isEqualTo("select 1");
        });
    }

    @Test
    void afterQuery_slowerThanThreshold_logsSlowQueryAtWarn() {
        listener.afterQuery(execution(THRESHOLD_MS + 1, true), List.of(query("select 1")));

        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.SLOW_QUERY);
            assertThat(SqlLogCapture.durationOf(event)).isEqualTo(101);
            assertThat(event.getFormattedMessage()).isEqualTo("select 1");
        });
    }

    @Test
    void afterQuery_exactlyAtThreshold_isNotSlow() {
        listener.afterQuery(execution(THRESHOLD_MS, true), List.of(query("select 1")));

        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.QUERY);
        });
    }

    @Test
    void afterQuery_zeroElapsedTime_logsQueryWithZeroDuration() {
        listener.afterQuery(execution(0, true), List.of(query("select 1")));

        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.QUERY);
            assertThat(SqlLogCapture.durationOf(event)).isZero();
        });
    }

    // ---- logger level acts as the on/off and slow-only switch ----

    @Test
    void afterQuery_loggerAtWarnAndFastQuery_logsNothing() {
        capture.setLevel(Level.WARN);

        listener.afterQuery(execution(12, true), List.of(query("select 1")));

        assertThat(capture.events()).isEmpty();
    }

    @Test
    void afterQuery_loggerAtWarnAndSlowQuery_stillLogs() {
        capture.setLevel(Level.WARN);

        listener.afterQuery(execution(500, true), List.of(query("select 1")));

        assertThat(capture.messages()).containsExactly("select 1");
    }

    @Test
    void afterQuery_loggerAtWarnAndFastFailedQuery_stillLogs() {
        capture.setLevel(Level.WARN);

        listener.afterQuery(execution(3, false), List.of(query("select 1")));

        assertThat(capture.messages()).containsExactly("select 1");
    }

    @Test
    void afterQuery_loggerOff_logsNothingEvenForSlowQuery() {
        capture.setLevel(Level.OFF);

        listener.afterQuery(execution(5_000, true), List.of(query("select 1")));

        assertThat(capture.events()).isEmpty();
    }

    // ---- outcome ----

    @Test
    void afterQuery_failedStatement_logsQueryFailedAtWarn() {
        listener.afterQuery(execution(3, false), List.of(query("select * from missing_table")));

        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.QUERY_FAILED);
            assertThat(event.getFormattedMessage()).isEqualTo("select * from missing_table");
        });
    }

    @Test
    void afterQuery_failedAndSlowStatement_isReportedAsFailedNotSlow() {
        listener.afterQuery(execution(900, false), List.of(query("select 1")));

        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(SqlLogCapture.eventOf(event)).isEqualTo(SqlLogEvent.QUERY_FAILED);
            assertThat(SqlLogCapture.durationOf(event)).isEqualTo(900);
        });
    }

    // ---- parameters ----

    @Test
    void afterQuery_withParameters_inlinesThemIntoTheSql() throws Exception {
        QueryInfo query = query("select * from tasks where status = ? and priority > ?",
                List.of(setObject(1, "OPEN"), setObject(2, 3)));

        listener.afterQuery(execution(5, true), List.of(query));

        assertThat(capture.messages())
                .containsExactly("select * from tasks where status = 'OPEN' and priority > 3");
    }

    @Test
    void afterQuery_includeParametersDisabled_keepsThePlaceholders() throws Exception {
        properties.setIncludeParameters(false);
        QueryInfo query = query("select * from users where email = ?",
                List.of(setObject(1, "someone@example.com")));

        listener.afterQuery(execution(5, true), List.of(query));

        assertThat(capture.messages()).containsExactly("select * from users where email = ?");
    }

    @Test
    void afterQuery_includeParametersDisabledAndBatch_logsTheStatementOnce() throws Exception {
        properties.setIncludeParameters(false);
        QueryInfo query = query("insert into tasks (title) values (?)",
                List.of(setObject(1, "first")), List.of(setObject(1, "second")));

        listener.afterQuery(execution(5, true), List.of(query));

        assertThat(capture.messages()).containsExactly("insert into tasks (title) values (?)");
    }

    @Test
    void afterQuery_statementWithoutParameters_logsTheSqlAsIs() {
        listener.afterQuery(execution(5, true), List.of(query("select count(*) from tasks")));

        assertThat(capture.messages()).containsExactly("select count(*) from tasks");
    }

    @Test
    void afterQuery_sqlContainingFormatPlaceholder_isLoggedVerbatim() {
        listener.afterQuery(execution(5, true), List.of(query("select '{}' as braces")));

        assertThat(capture.messages()).containsExactly("select '{}' as braces");
    }

    @Test
    void afterQuery_batchWithSeveralParameterSets_logsOneLinePerSet() throws Exception {
        QueryInfo query = query("insert into tasks (title) values (?)",
                List.of(setObject(1, "first")), List.of(setObject(1, "second")));

        listener.afterQuery(execution(8, true), List.of(query));

        assertThat(capture.messages()).containsExactly(
                "insert into tasks (title) values ('first')",
                "insert into tasks (title) values ('second')");
    }

    @Test
    void afterQuery_severalQueries_logsOneLinePerQueryInOrder() {
        listener.afterQuery(execution(8, true), List.of(query("select 1"), query("select 2")));

        assertThat(capture.messages()).containsExactly("select 1", "select 2");
    }

    @Test
    void afterQuery_emptyQueryList_logsNothing() {
        listener.afterQuery(execution(8, true), List.of());

        assertThat(capture.events()).isEmpty();
    }

    // ---- threshold comes from the properties object at call time ----

    @Test
    void afterQuery_thresholdRaised_sameDurationDropsBackToDebug() {
        properties.setSlowQueryThresholdMs(1_000);

        listener.afterQuery(execution(500, true), List.of(query("select 1")));

        assertThat(capture.events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG);
    }

    @Test
    void afterQuery_thresholdZero_anyMeasurableDurationIsSlow() {
        properties.setSlowQueryThresholdMs(0);

        listener.afterQuery(execution(0, true), List.of(query("select 1")));
        listener.afterQuery(execution(1, true), List.of(query("select 2")));

        assertThat(capture.events()).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG, Level.WARN);
    }

    // ---- the outbox worker's own statements are left out ----

    @Test
    void afterQuery_backgroundOutboxPoll_isNotLogged() {
        listener.afterQuery(execution(2, true),
                List.of(query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY created_at LIMIT 20")));

        assertThat(capture.events()).isEmpty();
    }

    @Test
    void afterQuery_backgroundOutboxUpdateAndDelete_areNotLogged() {
        listener.afterQuery(execution(2, true), List.of(
                query("update outbox set status=? where event_id=?"),
                query("DELETE FROM outbox WHERE status = 'published' AND created_at < ?"),
                query("select oe1_0.event_id\nfrom outbox oe1_0 where oe1_0.event_id=?")));

        assertThat(capture.events()).isEmpty();
    }

    @Test
    void afterQuery_backgroundOutboxStatementThatIsSlowOrFailed_isStillNotLogged() {
        listener.afterQuery(execution(5_000, true), List.of(query("select * from outbox")));
        listener.afterQuery(execution(3, false), List.of(query("select * from outbox")));

        assertThat(capture.events()).isEmpty();
    }

    @Test
    void afterQuery_backgroundOutboxInsert_isLogged() {
        listener.afterQuery(execution(2, true), List.of(query("insert into outbox (event_id) values (?)")));

        assertThat(capture.messages()).containsExactly("insert into outbox (event_id) values (?)");
    }

    @Test
    void afterQuery_outboxStatementWhileServingARequest_isLogged() {
        MDC.put(SqlLogRequestInterceptor.REQUEST_ID_KEY, "req-a123");

        listener.afterQuery(execution(2, true), List.of(query("select * from outbox")));

        assertThat(capture.messages()).containsExactly("select * from outbox");
    }

    @Test
    void afterQuery_valueThatMentionsOutbox_doesNotHideTheLine() throws Exception {
        QueryInfo query = query("select * from tasks where title = ?", List.of(setObject(1, "fix the outbox")));

        listener.afterQuery(execution(2, true), List.of(query));

        assertThat(capture.messages()).containsExactly("select * from tasks where title = 'fix the outbox'");
    }

    @Test
    void afterQuery_tableWhoseNameOnlyContainsOutbox_isLogged() {
        listener.afterQuery(execution(2, true), List.of(query("select * from outbox_archive")));

        assertThat(capture.messages()).containsExactly("select * from outbox_archive");
    }

    @Test
    void afterQuery_outboxStatementNextToAnother_onlyTheOtherIsLogged() {
        listener.afterQuery(execution(2, true), List.of(query("select * from outbox"), query("select 1")));

        assertThat(capture.messages()).containsExactly("select 1");
    }

    // ---- beforeQuery ----

    @Test
    void beforeQuery_logsNothing() {
        listener.beforeQuery(execution(0, true), List.of(query("select 1")));

        assertThat(capture.events()).isEmpty();
    }

    // ---- helpers ----

    private static ExecutionInfo execution(long elapsedMs, boolean success) {
        ExecutionInfo execution = new ExecutionInfo();
        execution.setElapsedTime(elapsedMs);
        execution.setSuccess(success);
        return execution;
    }

    @SafeVarargs
    private static QueryInfo query(String sql, List<ParameterSetOperation>... parameterSets) {
        QueryInfo query = new QueryInfo(sql);
        query.setParametersList(new ArrayList<>(List.of(parameterSets)));
        return query;
    }

    private static ParameterSetOperation setObject(int index, Object value) throws NoSuchMethodException {
        Method method = PreparedStatement.class.getMethod("setObject", int.class, Object.class);
        return new ParameterSetOperation(method, new Object[]{index, value});
    }
}
