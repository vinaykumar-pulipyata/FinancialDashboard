package com.learning.spring.financialdashboard.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Host check and the password-guessing lock, without a web server. */
class LoginProtectionTest {

    /** A clock the test can move forward. */
    private static final class Moving extends Clock {
        Instant now = Instant.parse("2026-10-28T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void onlyThisComputersNamesAreAllowedAsHost() {
        for (String ok : new String[] {"localhost", "LOCALHOST", "finance.localhost", "a.b.localhost", "127.0.0.1", "[::1]"}) {
            assertTrue(AllowedHostFilter.allowed(ok, ""), ok);
        }
        for (String bad : new String[] {"evil.example", "localhost.evil.example", "127.0.0.1.evil.example", "notlocalhost", "192.168.1.5", ""}) {
            assertFalse(AllowedHostFilter.allowed(bad, ""), bad);
        }
        assertFalse(AllowedHostFilter.allowed(null, ""));
        assertTrue(AllowedHostFilter.allowed("money.home", "money.home"), "the name set in app.host");
        assertFalse(AllowedHostFilter.allowed("money.home", ""));
    }

    @Test
    void tenWrongPasswordsInARowLockSignInForThirtySecondsAndARightOneClearsTheCount() {
        Moving clock = new Moving();
        LoginThrottle throttle = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure();
        }
        assertEquals(0, throttle.secondsLeft(), "nine wrong passwords do not lock");
        throttle.recordSuccess();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure();
        }
        assertEquals(0, throttle.secondsLeft(), "a right password in between started the count again");

        throttle.recordFailure();
        assertEquals(LoginThrottle.LOCK_SECONDS, throttle.secondsLeft());
        clock.now = clock.now.plus(Duration.ofSeconds(10));
        assertEquals(LoginThrottle.LOCK_SECONDS - 10, throttle.secondsLeft());
        clock.now = clock.now.plus(Duration.ofSeconds(21));
        assertEquals(0, throttle.secondsLeft(), "the lock ends by itself");
    }
}
