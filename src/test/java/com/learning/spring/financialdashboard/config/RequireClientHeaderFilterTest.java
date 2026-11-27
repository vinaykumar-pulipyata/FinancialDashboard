package com.learning.spring.financialdashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Which requests the cross-site header rule applies to, and what it answers. */
class RequireClientHeaderFilterTest {

    private final RequireClientHeaderFilter filter = new RequireClientHeaderFilter();

    private MockHttpServletResponse call(String method, String path, String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (header != null) {
            request.addHeader("X-Requested-With", header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        response.setForwardedUrl(chain.getRequest() == null ? null : "passed");   // marks that the chain was reached
        return response;
    }

    private static boolean passed(MockHttpServletResponse response) {
        return "passed".equals(response.getForwardedUrl());
    }

    @Test
    void everyChangingMethodNeedsTheHeader() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletResponse refused = call(method, "/api/accounts", null);
            assertEquals(403, refused.getStatus(), method);
            assertTrue(refused.getContentAsString(StandardCharsets.UTF_8).contains("X-Requested-With: FinancialDashboard"));
            assertEquals("application/json", refused.getContentType().split(";")[0]);
            assertNull(refused.getForwardedUrl());
            assertTrue(passed(call(method, "/api/accounts", "FinancialDashboard")), method);
        }
    }

    @Test
    void aWrongOrEmptyValueIsRefused() throws Exception {
        assertEquals(403, call("POST", "/api/notes", "XMLHttpRequest").getStatus());
        assertEquals(403, call("POST", "/api/notes", "financialdashboard").getStatus());   // exact match
        assertEquals(403, call("POST", "/api/notes", "").getStatus());
    }

    @Test
    void readsAndPagesAreLeftAlone() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS", "TRACE"}) {
            assertTrue(passed(call(method, "/api/accounts", null)), method);
        }
        assertTrue(passed(call("POST", "/Dashboard.html", null)));   // only /api/ is guarded
        assertNotNull(RequireClientHeaderFilter.VALUE);
    }

    @Test
    void theDashboardAndTheDemoScriptSendExactlyTheHeaderTheServerDemands() throws Exception {
        String expected = "\"" + RequireClientHeaderFilter.HEADER + "\": \"" + RequireClientHeaderFilter.VALUE + "\"";
        try (var util = getClass().getResourceAsStream("/static/js/core/util.js")) {
            assertNotNull(util, "the dashboard's util.js is on the classpath");
            assertTrue(new String(util.readAllBytes(), StandardCharsets.UTF_8).contains(expected), "api() in util.js must send " + expected);
        }
        Path script = Path.of(System.getProperty("demo.script", "scripts/load-demo-data.py"));
        assumeTrue(Files.exists(script), "the demo-data script is not found from " + Path.of("").toAbsolutePath());
        assertTrue(Files.readString(script).contains(expected), "the demo-data script must send " + expected);
    }
}
