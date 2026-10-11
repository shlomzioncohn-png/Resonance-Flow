package com.rnd.inbox.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.List;

/**
 * Test helper: collects everything {@link SqlQueryLoggingListener} logs while it is open, and
 * restores the logger's level and appenders on close.
 */
public final class SqlLogCapture implements AutoCloseable {

    private final Logger logger = (Logger) LoggerFactory.getLogger(SqlQueryLoggingListener.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            // Snapshots the MDC now; otherwise it is read lazily, after the request has cleared it.
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private final Level originalLevel = logger.getLevel();

    public SqlLogCapture() {
        appender.start();
        logger.addAppender(appender);
    }

    public void setLevel(Level level) {
        logger.setLevel(level);
    }

    public List<ILoggingEvent> events() {
        // Appends are synchronized on the appender; other threads (e.g. the outbox worker) may
        // be logging while a test reads.
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    public List<String> messages() {
        return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public static SqlLogEvent eventOf(ILoggingEvent event) {
        return (SqlLogEvent) keyValue(event, SqlQueryLoggingListener.EVENT_KEY);
    }

    public static long durationOf(ILoggingEvent event) {
        return (Long) keyValue(event, SqlQueryLoggingListener.DURATION_KEY);
    }

    private static Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Log event carries no " + key));
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }
}
