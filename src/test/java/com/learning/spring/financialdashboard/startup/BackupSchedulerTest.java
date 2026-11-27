package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.model.AppSetting;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import com.learning.spring.financialdashboard.service.BackupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The automatic backups switched on, in a context of their own (its own in-memory database and backup folder, so the
 * other tests are not disturbed): a backup is made as soon as the app has started, and again when it is told to close.
 */
@SpringBootTest(properties = {
        "app.backup.automatic=true",
        "spring.datasource.url=jdbc:h2:mem:schedulertest;DB_CLOSE_DELAY=-1",
        "app.backup.dir=${java.io.tmpdir}/financial-dashboard-scheduler-test"})
class BackupSchedulerTest {

    @Autowired
    BackupScheduler scheduler;
    @Autowired
    BackupService backups;
    @Autowired
    AppSettingRepository settings;

    @BeforeEach
    @AfterEach
    void clean() {
        java.io.File[] left = new java.io.File(System.getProperty("java.io.tmpdir"), "financial-dashboard-scheduler-test").listFiles();
        if (left != null) {
            for (java.io.File f : left) {
                f.delete();
            }
        }
    }

    @Test
    void aBackupIsMadeWhenTheAppHasStarted() {
        // The start-up runner already ran once when the context was built, whichever test runs first.
        assertNotNull(backups.status().lastSuccess());
        assertTrue(backups.status().ok());

        scheduler.run(new DefaultApplicationArguments());   // and it does so again whenever it is run
        assertFalse(backups.dueToday());
        assertTrue(backups.status().lastMessage().contains("app started"));
    }

    @Test
    void theHourlyCheckOnlyBacksUpWhenNoGoodBackupIsFromTheLast24Hours() {
        scheduler.run(new DefaultApplicationArguments());          // a good backup was just made
        scheduler.hourlyCheck();                                   // so there is nothing new to do
        assertTrue(backups.status().lastMessage().contains("app started"));

        settings.save(new AppSetting("backup.lastSuccessAt", LocalDateTime.now().minusHours(30).toString()));
        scheduler.hourlyCheck();
        assertTrue(backups.status().lastMessage().contains("daily"));
    }

    @Test
    void closingTheAppAlsoMakesABackupAndRunningTheRunnerAgainDoesToo() {
        scheduler.onClose();
        assertTrue(backups.status().lastMessage().contains("app closed"));

        scheduler.run(new DefaultApplicationArguments());
        assertEquals(true, backups.status().lastMessage().contains("app started"));
    }
}
