package com.learning.spring.financialdashboard.config;

import com.learning.spring.financialdashboard.service.CredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * While no password has been set the whole app is open, so the dashboard can ask for one. A client that sends a login
 * anyway (the demo-data script, a tool that always sends credentials) must not be turned away because that login
 * cannot be checked yet: this filter hides the Authorization header from Spring Security until a password exists.
 * It is added to the security chain only, not registered as a servlet filter.
 */
final class NoPasswordYetFilter extends OncePerRequestFilter {

    private final CredentialService credentials;

    NoPasswordYetFilter(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(credentials.isSet() ? request : new WithoutLogin(request), response);
    }

    /** The same request, minus any Authorization header. */
    static final class WithoutLogin extends HttpServletRequestWrapper {

        WithoutLogin(HttpServletRequest request) {
            super(request);
        }

        private static boolean isLogin(String name) {
            return name != null && name.toLowerCase(Locale.ROOT).equals("authorization");
        }

        @Override
        public String getHeader(String name) {
            return isLogin(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isLogin(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n -> !isLogin(n)).toList());
        }
    }
}
