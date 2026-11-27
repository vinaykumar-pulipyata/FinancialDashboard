package com.learning.spring.financialdashboard.config;

import com.learning.spring.financialdashboard.service.DiaryVault;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Hands the request's HTTP Basic password to the diary (see {@link DiaryVault}) for the length of the request, so the Daily
 * Calendar Notes can be opened and sealed with a key made from it. It sits in the security chain after the authorization check,
 * so only a request whose login was accepted gets here, and it clears the password when the request ends. Never logged.
 */
final class DiaryKeyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String password = passwordOf(request.getHeader("Authorization"));
        if (password != null) {
            DiaryVault.bind(password);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            DiaryVault.unbind();
        }
    }

    /** The password part of "Basic base64(user:password)", or null. */
    static String passwordOf(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String pair = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = pair.indexOf(':');
            return colon < 0 ? null : pair.substring(colon + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
