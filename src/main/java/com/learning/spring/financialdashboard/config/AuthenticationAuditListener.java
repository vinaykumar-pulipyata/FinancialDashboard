package com.learning.spring.financialdashboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** A refused sign-in is worth a warning in the log: who tried, from where, and why. Passwords are never logged. */
@Component
public class AuthenticationAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationAuditListener.class);

    private final LoginThrottle throttle;

    public AuthenticationAuditListener(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        throttle.recordSuccess();
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        throttle.recordFailure();
        Object details = event.getAuthentication().getDetails();
        String from = details instanceof WebAuthenticationDetails web ? web.getRemoteAddress() : "unknown address";
        log.warn("Sign-in refused for user '{}' from {}: {}", clean(event.getAuthentication().getName()), from,
                event.getException().getClass().getSimpleName());
    }

    /** The user name is typed by whoever calls the API, so strip anything that could fake a log line. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String printable = text.replaceAll("\\p{Cntrl}", "?");
        return printable.length() > 40 ? printable.substring(0, 40) + "..." : printable;
    }
}
