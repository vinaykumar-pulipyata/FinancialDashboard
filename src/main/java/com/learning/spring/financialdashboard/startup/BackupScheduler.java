package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.service.BackupService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When backups are made on their own: once when the app starts, once when it is closed, and, if the app stays open
 * for days, whenever an hour passes with no good backup from the last 24 hours.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "app.backup.automatic", havingValue = "true", matchIfMissing = true)
public class BackupScheduler implements ApplicationRunner {

    private final BackupService backups;

    public BackupScheduler(BackupService backups) {
        this.backups = backups;
    }

    @Override
    public void run(ApplicationArguments args) {
        backups.backupNow("app started");
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    public void hourlyCheck() {
        if (backups.dueToday()) {
            backups.backupNow("daily");
        }
    }

    @PreDestroy
    public void onClose() {
        backups.backupNow("app closed");
    }
}
