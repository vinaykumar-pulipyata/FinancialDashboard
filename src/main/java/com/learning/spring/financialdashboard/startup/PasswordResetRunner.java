package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.service.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Forgot password: start with --reset-password to remove the saved password. The next visit asks for a new one. */
@Component
@Order(0)
public class PasswordResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRunner.class);

    private final CredentialService credentials;

    public PasswordResetRunner(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("reset-password")) {
            credentials.reset();
            log.warn("The saved password was removed. Open the dashboard to set a new one. Restart without --reset-password.");
        }
    }
}
