package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.config.LoginThrottle;
import com.learning.spring.financialdashboard.service.CredentialService;
import com.learning.spring.financialdashboard.service.DiaryVault;
import com.learning.spring.financialdashboard.exception.ValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SetRequest(String password) {
    }

    public record ChangeRequest(String currentPassword, String newPassword) {
    }

    private final CredentialService service;
    private final LoginThrottle throttle;
    private final DiaryVault vault;

    public AuthController(CredentialService service, LoginThrottle throttle, DiaryVault vault) {
        this.service = service;
        this.throttle = throttle;
        this.vault = vault;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("passwordSet", service.isSet(), "username", CredentialService.USERNAME);
    }

    /** First run only: open while no password exists. */
    @PostMapping("/password")
    public void set(@RequestBody SetRequest request) {
        service.setInitial(request.password());
        vault.passwordSet(request.password());   // the diary's key, wrapped by this password
    }

    @PutMapping("/password")
    public void change(@RequestBody ChangeRequest request) {
        service.change(request.currentPassword(), request.newPassword());
        vault.passwordChanged(request.currentPassword(), request.newPassword());   // the notes stay readable
    }

    /** Screen lock: checks the password without changing anything. */
    @PostMapping("/verify")
    public void verify(@RequestBody SetRequest request) {
        if (!service.matches(request.password())) {
            throttle.recordFailure();
            throw new ValidationException("That password is not right");
        }
        throttle.recordSuccess();
    }
}
