package com.learning.spring.financialdashboard.config;

import ch.qos.logback.classic.Level;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The access-log filter on its own: which requests it sees and at which level it reports them. */
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @AfterEach
    void reset() {
        LogCapture.setLevel(RequestLoggingFilter.class, null);
    }

    private void call(String method, String path, int status, LogCapture log) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                ((MockHttpServletResponse) res).setStatus(status);
            }
        });
    }

    @Test
    void onlyApiCallsAreLogged() throws Exception {
        try (LogCapture log = new LogCapture()) {
            call("GET", "/Dashboard.html", 200, log);
            call("GET", "/js/app.js", 200, log);
            call("POST", "/api/notes", 201, log);

            assertEquals(1, log.lines().stream().filter(l -> l.contains("->")).count());
        }
    }

    @Test
    void theLevelFollowsTheStatusAndTheKindOfRequest() throws Exception {
        LogCapture.setLevel(RequestLoggingFilter.class, Level.DEBUG);
        try (LogCapture log = new LogCapture()) {
            call("GET", "/api/a", 200, log);
            call("POST", "/api/b", 201, log);
            call("DELETE", "/api/c", 400, log);
            call("GET", "/api/d", 401, log);
            call("PUT", "/api/e", 403, log);
            call("GET", "/api/f", 500, log);

            assertTrue(log.has("DEBUG GET /api/a -> 200"));
            assertTrue(log.has("INFO POST /api/b -> 201"));
            assertTrue(log.has("INFO DELETE /api/c -> 400"));
            assertTrue(log.has("WARN GET /api/d -> 401"));
            assertTrue(log.has("WARN PUT /api/e -> 403"));
            assertTrue(log.has("ERROR GET /api/f -> 500"));
        }
    }

    @Test
    void aCrashInsideARequestIsLoggedWithItsStackTraceAndPassedOn() {
        try (LogCapture log = new LogCapture()) {
            MockFilterChain explode = new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                    throw new IllegalStateException("boom");
                }
            };

            assertThrows(IllegalStateException.class,
                    () -> filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(), explode));

            assertTrue(log.has("ERROR GET /api/x failed after"));
            assertTrue(log.has("IllegalStateException: boom"));
            assertFalse(log.has("->"));
        }
    }

    @Test
    void theQueryStringIsNeverPartOfTheLine() throws Exception {
        try (LogCapture log = new LogCapture()) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notes");
            request.setQueryString("q=SECRET");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertTrue(log.has("POST /api/notes"));
            assertFalse(log.has("SECRET"));
        }
    }
}
