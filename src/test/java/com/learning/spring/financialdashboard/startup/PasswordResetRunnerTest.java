package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.service.CredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PasswordResetRunnerTest {

    @Autowired
    PasswordResetRunner runner;
    @Autowired
    CredentialService credentials;

    @BeforeEach
    @AfterEach
    void clean() {
        credentials.reset();
    }

    @Test
    void startingWithResetPasswordRemovesTheSavedPassword() {
        credentials.setInitial("a-good-password");

        runner.run(new DefaultApplicationArguments("--reset-password"));

        assertFalse(credentials.isSet());
    }

    @Test
    void startingNormallyLeavesThePasswordAlone() {
        credentials.setInitial("a-good-password");

        runner.run(new DefaultApplicationArguments());
        runner.run(new DefaultApplicationArguments("--something-else"));

        assertTrue(credentials.isSet());
    }
}
