package com.learning.spring.financialdashboard.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which backups are kept and which can go, and how the file names read. */
class BackupRetentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private static LocalDateTime at(int year, int month, int day, int hour) {
        return LocalDateTime.of(year, month, day, hour, 0, 0);
    }

    @Test
    void fileNamesRoundTrip() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 12, 18, 5, 9);
        String name = BackupRetention.fileName(time);

        assertEquals("backup-2026-10-12_180509.sql.zip", name);
        assertEquals(time, BackupRetention.parse(name).orElseThrow());
    }

    @Test
    void namesThatAreNotOursAreIgnored() {
        assertTrue(BackupRetention.parse(null).isEmpty());
        assertTrue(BackupRetention.parse("report-2026-10.xlsx").isEmpty());
        assertTrue(BackupRetention.parse("backup-notadate.sql.zip").isEmpty());
        assertTrue(BackupRetention.parse("backup-2026-10-12_180509.zip").isEmpty());
        assertTrue(BackupRetention.parse("my-backup-2026-10-12_180509.sql.zip").isEmpty());
    }

    @Test
    void nothingToDeleteWhenThereAreNoBackups() {
        assertTrue(BackupRetention.toDelete(List.of(), TODAY).isEmpty());
    }

    @Test
    void theNewestBackupIsNeverDeleted() {
        LocalDateTime old = at(2019, 5, 20, 10);
        assertTrue(BackupRetention.toDelete(List.of(old), TODAY).isEmpty());
    }

    @Test
    void ofSeveralBackupsOnOneRecentDayOnlyTheLastIsKept() {
        LocalDateTime morning = at(2026, 10, 10, 8);
        LocalDateTime noon = at(2026, 10, 10, 12);
        LocalDateTime night = at(2026, 10, 10, 22);
        LocalDateTime first = at(2026, 10, 1, 9);   // the first of the month is protected on its own

        Set<LocalDateTime> gone = BackupRetention.toDelete(List.of(first, morning, noon, night), TODAY);

        assertEquals(Set.of(morning, noon), gone);
    }

    @Test
    void olderThanThirtyDaysOnlyTheFirstBackupOfEachMonthSurvives() {
        LocalDateTime marchFirst = at(2026, 3, 5, 9);
        LocalDateTime marchLater = at(2026, 3, 20, 10);
        LocalDateTime marchSameDay = at(2026, 3, 5, 18);
        LocalDateTime aprilFirst = at(2026, 4, 2, 10);
        LocalDateTime recent = at(2026, 10, 12, 7);

        Set<LocalDateTime> gone = BackupRetention.toDelete(List.of(marchFirst, marchLater, marchSameDay, aprilFirst, recent), TODAY);

        assertEquals(Set.of(marchLater, marchSameDay), gone);
    }

    @Test
    void theThirtyDayWindowIncludesTodayAndTwentyNineDaysBack() {
        LocalDateTime inside = at(2026, 9, 13, 10);       // 29 days before today: still in the window
        LocalDateTime insideLater = at(2026, 9, 13, 20);
        LocalDateTime outside = at(2026, 9, 12, 10);      // 30 days before: now only monthly copies count
        LocalDateTime outsideLater = at(2026, 9, 12, 20);
        LocalDateTime septFirst = at(2026, 9, 1, 6);
        LocalDateTime recent = at(2026, 10, 12, 7);

        Set<LocalDateTime> gone = BackupRetention.toDelete(
                List.of(septFirst, outside, outsideLater, inside, insideLater, recent), TODAY);

        assertEquals(Set.of(outside, outsideLater, inside), gone);
    }
}
