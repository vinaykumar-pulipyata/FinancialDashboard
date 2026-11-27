package com.learning.spring.financialdashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One line for every API call: method, path, status and how long it took. It runs ahead of Spring Security so refused
 * sign-ins are seen too. Only the path is logged, never the query string (a search text) or the body (amounts, notes,
 * passwords). Reads (and the page's own log lines) are logged at DEBUG, changes at INFO, refused sign-ins at WARN and server errors at ERROR.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");   // pages, scripts and styles are not worth a line each
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            log.error("{} {} failed after {} ms", request.getMethod(), request.getRequestURI(), millis(started), e);
            throw e;
        }
        int status = response.getStatus();
        String method = request.getMethod();
        String path = request.getRequestURI();
        long ms = millis(started);
        if (status >= 500) {
            log.error("{} {} -> {} ({} ms)", method, path, status, ms);
        } else if (status == 401 || status == 403) {
            log.warn("{} {} -> {} ({} ms)", method, path, status, ms);
        } else if (!"GET".equals(method) && !path.equals("/api/client-log")) {   // the page's own log lines are the line, not news
            log.info("{} {} -> {} ({} ms)", method, path, status, ms);
        } else {
            log.debug("{} {} -> {} ({} ms)", method, path, status, ms);
        }
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
