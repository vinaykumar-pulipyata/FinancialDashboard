package com.learning.spring.financialdashboard.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Putting a backup back: it must work, keep what it replaces, and change nothing when it cannot work. */
class BackupRestorerTest {

    Path folder;
    String url;
    Path backup;

    @BeforeEach
    void setUp() throws Exception {
        folder = Files.createTempDirectory("fd-restorer");
        url = "jdbc:h2:file:" + folder.resolve("financialdb");
        backup = folder.resolve("good.sql.zip");
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE ENTRY(ID BIGINT PRIMARY KEY, NOTE VARCHAR(50))");
            st.execute("INSERT INTO ENTRY VALUES (1, 'salary'), (2, 'rent'), (3, 'food')");
            st.execute("SCRIPT TO '" + backup + "' COMPRESSION ZIP");
            st.execute("DELETE FROM ENTRY");   // the "loss"
            st.execute("INSERT INTO ENTRY VALUES (99, 'newer entry')");
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private List<String> notes() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT NOTE FROM ENTRY ORDER BY ID")) {
            java.util.ArrayList<String> notes = new java.util.ArrayList<>();
            while (rs.next()) {
                notes.add(rs.getString(1));
            }
            return notes;
        }
    }

    @Test
    void restoringBringsTheBackedUpRowsBackAndKeepsTheReplacedDatabase() throws Exception {
        Path moved = BackupRestorer.restore(backup, url);

        assertEquals(List.of("salary", "rent", "food"), notes());
        assertNotNull(moved);
        assertTrue(Files.exists(moved));
        assertTrue(moved.getFileName().toString().contains(".before-restore-"));
        // The replaced database is still a working database, with the newer entry in it.
        String movedUrl = "jdbc:h2:file:" + folder.resolve("kept");
        Files.move(moved, folder.resolve("kept.mv.db"));
        try (Connection c = DriverManager.getConnection(movedUrl, "sa", ""); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT NOTE FROM ENTRY")) {
            assertTrue(rs.next());
            assertEquals("newer entry", rs.getString(1));
        }
    }

    @Test
    void restoringIntoAPlaceWithNoDatabaseJustCreatesIt() throws Exception {
        Path other = folder.resolve("elsewhere");
        String otherUrl = "jdbc:h2:file:" + other.resolve("db");

        Path moved = BackupRestorer.restore(backup, otherUrl);

        assertNull(moved);
        assertTrue(Files.exists(other.resolve("db.mv.db")));
    }

    @Test
    void aMissingBackupChangesNothing() throws Exception {
        assertThrows(IOException.class, () -> BackupRestorer.restore(folder.resolve("nope.sql.zip"), url));
        assertEquals(List.of("newer entry"), notes());
    }

    @Test
    void aDamagedBackupChangesNothingAndLeavesNothingBehind() throws Exception {
        Path damaged = folder.resolve("damaged.sql.zip");
        Files.writeString(damaged, "this is not a zip file at all");

        assertThrows(Exception.class, () -> BackupRestorer.restore(damaged, url));

        assertEquals(List.of("newer entry"), notes());
        assertFalse(Files.exists(folder.resolve("financialdb-restoring.mv.db")));
    }

    @Test
    void aBackupWithNoTablesIsRefused() throws Exception {
        Path empty = folder.resolve("empty.sql.zip");
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:emptyone", "sa", ""); Statement st = c.createStatement()) {
            st.execute("SCRIPT TO '" + empty + "' COMPRESSION ZIP");
        }

        assertThrows(IOException.class, () -> BackupRestorer.restore(empty, url));
        assertEquals(List.of("newer entry"), notes());
    }

    @Test
    void onlyAFileDatabaseCanBeRestored() {
        assertThrows(IOException.class, () -> BackupRestorer.restore(backup, "jdbc:h2:mem:something"));
    }

    @Test
    void theStartUpFlagRestoresAndWithoutItNothingHappens() throws Exception {
        assertFalse(BackupRestorer.restoreIfRequested(new String[]{"--spring.profiles.active=x"}));
        assertEquals(List.of("newer entry"), notes());

        assertTrue(BackupRestorer.restoreIfRequested(new String[]{"--restore=" + backup, "--spring.datasource.url=" + url + ";MODE=REGULAR"}));
        assertEquals(List.of("salary", "rent", "food"), notes());
    }

    @Test
    void aFailedStartUpRestoreStopsTheApp() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BackupRestorer.restoreIfRequested(new String[]{"--restore=" + folder.resolve("missing.sql.zip"), "--spring.datasource.url=" + url}));
        assertTrue(e.getMessage().contains("Nothing was changed"));
    }
}
