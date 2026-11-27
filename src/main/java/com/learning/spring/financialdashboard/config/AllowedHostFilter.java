package com.learning.spring.financialdashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Only answers requests whose Host is this computer: localhost, any *.localhost name (finance.localhost), 127.0.0.1, [::1], or
 * the name set in {@code app.host}. A web page on another site can make a browser send requests to 127.0.0.1 by pointing its
 * own name at it (DNS rebinding); such a request carries the other site's name as Host, so it is refused here, before any
 * sign-in or "no password yet" rule is looked at. It is added to the security chain only, not registered as a servlet filter.
 */
final class AllowedHostFilter extends OncePerRequestFilter {

    private final String appHost;

    AllowedHostFilter(String appHost) {
        this.appHost = appHost == null ? "" : appHost.trim().toLowerCase(Locale.ROOT);
    }

    static boolean allowed(String host, String appHost) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.equals("127.0.0.1") || h.equals("[::1]")
                || (!appHost.isEmpty() && h.equals(appHost));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (allowed(request.getServerName(), appHost)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"This dashboard only answers on localhost\"}");
    }
}
