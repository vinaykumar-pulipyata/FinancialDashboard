package com.learning.spring.financialdashboard.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Collects everything logged while it is open, so a test can read it. Close it (try-with-resources) when done. */
public final class LogCapture implements AutoCloseable {

    private final Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
    }

    /** Everything logged so far, one string per event: "LEVEL message" followed by any stack trace. */
    public List<String> lines() {
        return List.copyOf(appender.list).stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()
                + (e.getThrowableProxy() == null ? "" : "\n" + ThrowableProxyUtil.asString(e.getThrowableProxy()))).toList();
    }

    public boolean has(String text) {
        return lines().stream().anyMatch(l -> l.contains(text));
    }

    public static void setLevel(Class<?> logger, Level level) {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(logger).setLevel(level);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
