package com.learning.spring.financialdashboard.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.learning.spring.financialdashboard.config.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the page may put in the server's log: one clean line per call, at the level it asked for, and not too many. */
class ClientLogServiceTest {

    private final ClientLogService service = new ClientLogService();
    private long now = 1_000;

    @BeforeEach
    void setUp() {
        service.clock = () -> now;
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("client").setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("client").setLevel(null);
    }

    @Test
    void aLineIsWrittenAtTheLevelAskedForAndUnknownLevelsAreInfo() {
        try (LogCapture log = new LogCapture()) {
            service.record("ERROR", "script.error", "TypeError at fill");
            service.record("warn", "category.page.sync.failed", "Error");
            service.record("DEBUG", "txn.form.opened", "kind=EXPENSE");
            service.record("INFO", "category.added", "id=12");
            service.record("LOUD", "whatever", "x");
            service.record(null, "no-level", null);

            List<String> lines = log.lines();
            assertEquals(List.of(
                    "ERROR Browser script.error TypeError at fill",
                    "WARN Browser category.page.sync.failed Error",
                    "DEBUG Browser txn.form.opened kind=EXPENSE",
                    "INFO Browser category.added id=12",
                    "INFO Browser whatever x",
                    "INFO Browser no-level "), lines);
        }
    }

    @Test
    void anEventThatIsNotAShortCodeIsReplacedSoTextCannotSmuggleInThroughIt() {
        try (LogCapture log = new LogCapture()) {
            service.record("INFO", "Pets and 5000 rupees!", "d");
            service.record("INFO", null, "d");
            service.record("INFO", "x".repeat(61), "d");

            assertEquals(3, log.lines().stream().filter(l -> l.startsWith("INFO Browser invalid-event")).count());
        }
    }

    @Test
    void aDetailIsOneLineAndCutShortSoItCannotForgeAnEntryOrFillTheLog() {
        try (LogCapture log = new LogCapture()) {
            service.record("INFO", "a.b", "first\nINFO Browser forged\r\nline\t3");
            service.record("INFO", "a.c", "z".repeat(5000));

            List<String> lines = log.lines();
            assertEquals("INFO Browser a.b first INFO Browser forged  line 3", lines.get(0));
            assertFalse(lines.get(0).contains("\n"));
            assertTrue(lines.get(1).length() < 540, "length " + lines.get(1).length());
            assertTrue(lines.get(1).endsWith("..."));
        }
    }

    @Test
    void afterTheLimitInAMinuteLinesAreDroppedWithOneWarningAndTheNextMinuteStartsAgain() {
        try (LogCapture log = new LogCapture()) {
            for (int i = 0; i < ClientLogService.MAX_PER_MINUTE; i++) {
                assertTrue(service.record("INFO", "loop", "i=" + i));
            }
            assertFalse(service.record("INFO", "loop", "gone"));
            assertFalse(service.record("INFO", "loop", "gone"));
            assertEquals(1, log.lines().stream().filter(l -> l.contains("arriving too fast")).count());
            assertFalse(log.has("loop gone"));

            now += 60_000;
            assertTrue(service.record("INFO", "loop", "again"));
            assertTrue(log.has("again"));
        }
    }
}
