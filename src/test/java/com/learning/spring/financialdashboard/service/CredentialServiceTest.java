package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class CredentialServiceTest {

    @Autowired
    CredentialService service;

    @BeforeEach
    @AfterEach
    void clean() {
        service.reset();
    }

    @Test
    void thePasswordIsSavedAsAHashAndCanBeSetOnlyOnce() {
        service.setInitial("correct horse");
        assertTrue(service.isSet());
        assertNotEquals("correct horse", service.hash().orElseThrow());
        assertThrows(ValidationException.class, () -> service.setInitial("another one 1"));
    }

    @Test
    void aShortPasswordIsRefused() {
        assertThrows(ValidationException.class, () -> service.setInitial("short"));
        assertFalse(service.isSet());
    }

    @Test
    void changingNeedsTheCurrentPassword() {
        service.setInitial("correct horse");
        assertThrows(ValidationException.class, () -> service.change("wrong one", "battery staple"));
        String before = service.hash().orElseThrow();
        service.change("correct horse", "battery staple");
        assertNotEquals(before, service.hash().orElseThrow());
    }

    @Test
    void resetRemovesThePasswordSoANewOneCanBeSet() {
        service.setInitial("correct horse");
        service.reset();
        assertFalse(service.isSet());
        service.setInitial("battery staple");
        assertTrue(service.isSet());
    }

    @Test
    void matchesTellsRightFromWrong() {
        service.setInitial("correct horse");
        assertTrue(service.matches("correct horse"));
        assertFalse(service.matches("wrong horse"));
        assertFalse(service.matches(null));
    }

    @Test
    void changingBeforeAnyPasswordExistsIsRefused() {
        assertThrows(ValidationException.class, () -> service.change("anything", "a-new-password"));
        assertFalse(service.matches("anything"));
    }

    @Test
    void aMissingOrWrongCurrentPasswordIsRefusedWhenChanging() {
        service.setInitial("correct horse");
        assertThrows(ValidationException.class, () -> service.change(null, "battery staple"));
        assertThrows(ValidationException.class, () -> service.change("wrong horse", "battery staple"));
    }

    @Test
    void thePasswordMustBeLongEnoughButNotLongerThanBcryptCanUse() {
        assertThrows(ValidationException.class, () -> service.setInitial(null));
        assertThrows(ValidationException.class, () -> service.setInitial("x".repeat(73)));
        service.setInitial("y".repeat(72));   // the longest that is allowed
        assertTrue(service.isSet());
        assertThrows(ValidationException.class, () -> service.change("y".repeat(72), "z".repeat(73)));
    }
}
