package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.AppCredential;
import com.learning.spring.financialdashboard.repository.AppCredentialRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The single dashboard password. It is saved as a BCrypt hash and never in plain text. */
@Service
@Transactional
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    public static final String USERNAME = "admin";
    private static final int MIN_LENGTH = 8;
    private static final int MAX_BYTES = 72;   // BCrypt ignores (or rejects) anything longer

    private final AppCredentialRepository credentials;
    private final PasswordEncoder encoder;

    public CredentialService(AppCredentialRepository credentials, PasswordEncoder encoder) {
        this.credentials = credentials;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public boolean isSet() {
        return credentials.existsById(AppCredential.ID);
    }

    @Transactional(readOnly = true)
    public Optional<String> hash() {
        return credentials.findById(AppCredential.ID).map(AppCredential::getPasswordHash);
    }

    /** First-run only: sets the password when none exists. */
    public void setInitial(String password) {
        if (isSet()) {
            throw new ValidationException("A password is already set");
        }
        credentials.save(new AppCredential(encoder.encode(checked(password))));
        log.info("The dashboard password was set");
    }

    /** Used by the screen lock: true when the password is right. */
    @Transactional(readOnly = true)
    public boolean matches(String password) {
        return password != null && hash().map(h -> encoder.matches(password, h)).orElse(false);
    }

    public void change(String current, String password) {
        AppCredential credential = credentials.findById(AppCredential.ID)
                .orElseThrow(() -> new ValidationException("No password is set yet"));
        if (current == null || !encoder.matches(current, credential.getPasswordHash())) {
            throw new ValidationException("The current password is not right");
        }
        credential.setPasswordHash(encoder.encode(checked(password)));
        log.info("The dashboard password was changed");
    }

    /** Forgot password: removes the saved password so the next visit asks for a new one. Data is untouched. */
    public void reset() {
        credentials.deleteAll();
        log.warn("The dashboard password was removed (reset)");
    }

    private static String checked(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new ValidationException("The password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ValidationException("The password is too long (at most " + MAX_BYTES + " bytes)");
        }
        return password;
    }
}
