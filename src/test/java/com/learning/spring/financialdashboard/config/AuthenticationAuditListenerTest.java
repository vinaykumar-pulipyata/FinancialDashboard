package com.learning.spring.financialdashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationAuditListenerTest {

    @Test
    void userNamesAreCleanedBeforeTheyReachTheLog() {
        assertEquals("", AuthenticationAuditListener.clean(null));
        assertEquals("admin", AuthenticationAuditListener.clean("admin"));
        assertEquals("a?b?c", AuthenticationAuditListener.clean("a\nb\rc"));
        String cleaned = AuthenticationAuditListener.clean("x".repeat(100));
        assertEquals(43, cleaned.length());
        assertTrue(cleaned.endsWith("..."));
    }

    @Test
    void aFailureWithoutRequestDetailsIsStillLogged() {
        try (LogCapture log = new LogCapture()) {
            new AuthenticationAuditListener(new LoginThrottle()).onFailure(new AuthenticationFailureBadCredentialsEvent(
                    new UsernamePasswordAuthenticationToken("admin", "secret-password"), new BadCredentialsException("bad")));

            assertTrue(log.has("WARN Sign-in refused for user 'admin' from unknown address: BadCredentialsException"));
            assertFalse(log.has("secret-password"));
        }
    }
}
