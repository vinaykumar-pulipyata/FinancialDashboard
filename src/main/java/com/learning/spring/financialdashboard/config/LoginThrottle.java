package com.learning.spring.financialdashboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Slows down password guessing. There is one user, so one counter: after {@value #MAX_FAILURES} wrong passwords in a row
 * (wrong Basic sign-in or wrong screen-lock password) every sign-in attempt is refused for {@value #LOCK_SECONDS} seconds.
 * A right password clears the count. BCrypt already makes each guess slow; this stops a script from trying thousands.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 10;
    static final long LOCK_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final Clock clock;
    private int failures;
    private Instant lockedUntil = Instant.EPOCH;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public synchronized void recordFailure() {
        if (secondsLeft() > 0) {
            return;
        }
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            lockedUntil = clock.instant().plus(Duration.ofSeconds(LOCK_SECONDS));
            log.warn("Too many wrong passwords in a row: sign-in is refused for {} seconds", LOCK_SECONDS);
        }
    }

    public synchronized void recordSuccess() {
        failures = 0;
    }

    /** 0 when sign-in is allowed, else how many whole seconds are left (at least 1). */
    public synchronized long secondsLeft() {
        long left = Duration.between(clock.instant(), lockedUntil).toMillis();
        return left <= 0 ? 0 : Math.max(1, (left + 999) / 1000);
    }
}
