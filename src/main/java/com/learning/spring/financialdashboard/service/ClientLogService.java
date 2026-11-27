package com.learning.spring.financialdashboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Writes what the page reports about itself (script errors, and the steps of a flow such as adding a category) to the
 * log, under the logger name "client", so a problem that happens in the browser can be read in the same file as the
 * server's. The page sends only codes, ids and counts, never amounts, notes, category names or search text, and this
 * class does not trust that: the event must be a short code, the detail is cut to one line of at most 500 characters,
 * and at most 120 lines a minute are kept so a looping page cannot fill the log.
 */
@Service
public class ClientLogService {

    private static final Logger log = LoggerFactory.getLogger("client");
    private static final Pattern EVENT = Pattern.compile("[a-z0-9._-]{1,60}");
    static final int MAX_DETAIL = 500;
    static final int MAX_PER_MINUTE = 120;
    private static final long MINUTE = 60_000;

    LongSupplier clock = System::currentTimeMillis;   // a test can replace it
    private long windowStart = -1;
    private int inWindow;

    /** Returns false when the line was dropped because too many arrived in the last minute. */
    public synchronized boolean record(String level, String event, String detail) {
        long now = clock.getAsLong();
        if (windowStart < 0 || now - windowStart >= MINUTE) {
            windowStart = now;
            inWindow = 0;
        }
        inWindow++;
        if (inWindow > MAX_PER_MINUTE) {
            if (inWindow == MAX_PER_MINUTE + 1) {
                log.warn("Browser log lines are arriving too fast; the rest of this minute is dropped");
            }
            return false;
        }
        String code = event != null && EVENT.matcher(event).matches() ? event : "invalid-event";
        String text = clean(detail);
        String upper = level == null ? "" : level.toUpperCase();
        switch (upper) {
            case "ERROR" -> log.error("Browser {} {}", code, text);
            case "WARN" -> log.warn("Browser {} {}", code, text);
            case "DEBUG" -> log.debug("Browser {} {}", code, text);
            default -> log.info("Browser {} {}", code, text);
        }
        return true;
    }

    /** One line: control characters (a new line could forge a log entry) become spaces, and the length is capped. */
    static String clean(String detail) {
        if (detail == null) {
            return "";
        }
        String line = detail.replaceAll("\\p{Cntrl}", " ").trim();
        return line.length() > MAX_DETAIL ? line.substring(0, MAX_DETAIL) + "..." : line;
    }
}
