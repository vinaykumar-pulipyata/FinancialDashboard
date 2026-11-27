package com.learning.spring.financialdashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** While {@link LoginThrottle} is locked, refuses (429) every request that tries a password: one with an Authorization header, or the screen lock / password calls. */
final class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginThrottle throttle;

    LoginThrottleFilter(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    private static boolean triesAPassword(HttpServletRequest request) {
        String path = request.getRequestURI();
        return request.getHeader("Authorization") != null || path.equals("/api/auth/verify") || path.equals("/api/auth/password");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long wait = throttle.secondsLeft();
        if (wait == 0 || !triesAPassword(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(wait));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"Too many wrong passwords. Try again in " + wait + " seconds\"}");
    }
}
