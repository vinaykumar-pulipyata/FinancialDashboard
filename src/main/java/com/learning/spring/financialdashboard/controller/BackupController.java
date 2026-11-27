package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.BackupService;
import com.learning.spring.financialdashboard.service.BackupService.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backups")
public class BackupController {

    public record SettingsRequest(String copyDir) {
    }

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping
    public Status status() {
        return service.status();
    }

    /** "Back up now". A failure is part of the answer (ok = false), not an HTTP error. */
    @PostMapping
    public Status backUpNow() {
        return service.backupNow("on request");
    }

    @PutMapping("/settings")
    public Status settings(@RequestBody SettingsRequest request) {
        return service.setCopyFolder(request.copyDir());
    }
}
