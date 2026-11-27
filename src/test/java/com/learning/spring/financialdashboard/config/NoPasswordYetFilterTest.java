package com.learning.spring.financialdashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hiding the login from Spring Security while no password exists, and nothing else. */
class NoPasswordYetFilterTest {

    @Test
    void theLoginIsHiddenInEveryWayItCanBeAskedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic YWRtaW46");
        request.addHeader("authorization", "Basic c2Vjb25k");
        request.addHeader("Accept", "application/json");

        NoPasswordYetFilter.WithoutLogin hidden = new NoPasswordYetFilter.WithoutLogin(request);

        assertNull(hidden.getHeader("Authorization"));
        assertNull(hidden.getHeader("AUTHORIZATION"));
        assertFalse(hidden.getHeaders("Authorization").hasMoreElements());
        assertTrue(Collections.list(hidden.getHeaderNames()).stream().noneMatch(n -> n.equalsIgnoreCase("authorization")));
    }

    @Test
    void everyOtherHeaderIsLeftAlone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic YWRtaW46");
        request.addHeader("Accept", "application/json");

        NoPasswordYetFilter.WithoutLogin hidden = new NoPasswordYetFilter.WithoutLogin(request);

        assertEquals("application/json", hidden.getHeader("Accept"));
        assertEquals("application/json", Collections.list(hidden.getHeaders("Accept")).get(0));
        assertEquals(java.util.List.of("Accept"), Collections.list(hidden.getHeaderNames()));
        assertNull(hidden.getHeader(null));
    }
}
