package com.rnd.inbox.shared.logging;

import com.rnd.inbox.shared.config.SqlLoggingProperties;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes one log line per executed SQL statement: DEBUG normally, WARN once the statement
 * runs longer than the slow-query threshold or fails.
 */
public class SqlQueryLoggingListener implements QueryExecutionListener {

    public static final String EVENT_KEY = "event";
    public static final String DURATION_KEY = "duration_ms";

    private static final Logger log = LoggerFactory.getLogger(SqlQueryLoggingListener.class);

    // The outbox worker polls every few seconds and would bury every other line. Inserts stay
    // visible: they are part of creating a task, not of the worker's own housekeeping.
    private static final Pattern OUTBOX_HOUSEKEEPING = Pattern.compile(
            "^\\s*(select|update|delete)\\b.*\\boutbox\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final SqlLoggingProperties properties;

    public SqlQueryLoggingListener(SqlLoggingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // Nothing to log until the statement has run and its duration is known.
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        long elapsedMs = execInfo.getElapsedTime();
        SqlLogEvent event = eventOf(execInfo.isSuccess(), elapsedMs);
        Level level = event == SqlLogEvent.QUERY ? Level.DEBUG : Level.WARN;
        if (!log.isEnabledForLevel(level)) {
            return;
        }

        for (QueryInfo queryInfo : queryInfoList) {
            if (isBackgroundOutboxHousekeeping(queryInfo.getQuery())) {
                continue;
            }
            List<List<ParameterSetOperation>> parameterSets = queryInfo.getParametersList();
            if (parameterSets.isEmpty() || !properties.isIncludeParameters()) {
                write(level, event, elapsedMs, queryInfo.getQuery());
                continue;
            }
            for (List<ParameterSetOperation> parameters : parameterSets) {
                write(level, event, elapsedMs, SqlParameterInliner.inline(queryInfo.getQuery(), parameters));
            }
        }
    }

    private SqlLogEvent eventOf(boolean success, long elapsedMs) {
        if (!success) {
            return SqlLogEvent.QUERY_FAILED;
        }
        return elapsedMs > properties.getSlowQueryThresholdMs() ? SqlLogEvent.SLOW_QUERY : SqlLogEvent.QUERY;
    }

    // Matched against the statement before its values are inlined, so a value can never hide a line.
    private boolean isBackgroundOutboxHousekeeping(String sql) {
        boolean servingRequest = MDC.get(SqlLogRequestInterceptor.REQUEST_ID_KEY) != null;
        return !servingRequest && OUTBOX_HOUSEKEEPING.matcher(sql).find();
    }

    private void write(Level level, SqlLogEvent event, long elapsedMs, String sql) {
        // The SQL is the message itself, never a format string: it may contain "{}".
        log.atLevel(level)
                .addKeyValue(EVENT_KEY, event)
                .addKeyValue(DURATION_KEY, elapsedMs)
                .log(sql);
    }
}
