package com.learning.spring.financialdashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Cross-site protection without cookies or tokens. Anything that changes data (POST, PUT, PATCH, DELETE under /api/)
 * must carry {@code X-Requested-With: FinancialDashboard}. The dashboard and the demo-data script send it; a web page
 * on another site cannot, because a browser only lets a page add a custom header after a CORS check that this app never
 * allows. That matters because HTTP Basic credentials are remembered by the browser and sent with every request to this
 * address, including ones triggered from somewhere else (a hidden form that POSTs to localhost). Reads are not
 * affected: another site cannot read the answers anyway.
 *
 * <p>It sits after the sign-in checks, so an unauthenticated request still gets 401 rather than 403. It is added to the
 * security chain only, not registered as a servlet filter.
 */
final class RequireClientHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Requested-With";
    static final String VALUE = "FinancialDashboard";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || SAFE_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (VALUE.equals(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"This request must come from the dashboard: it needs the header "
                + HEADER + ": " + VALUE + "\"}");
    }
}
