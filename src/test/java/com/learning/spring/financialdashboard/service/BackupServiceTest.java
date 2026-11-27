package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.AppSetting;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.BackupService.Level;
import com.learning.spring.financialdashboard.service.BackupService.Status;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The backup: what it writes, how it checks itself, how it thins old copies, the second folder and the warnings. */
@SpringBootTest
class BackupServiceTest {

    @Autowired
    BackupService service;
    @Autowired
    AccountService accountService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    ExportService export;
    @Autowired
    AppSettingRepository settings;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    NoteService noteService;
    @Autowired
    com.learning.spring.financialdashboard.repository.NoteRepository notes;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;
    @Autowired
    DataSource dataSource;
    @Value("${app.backup.dir}")
    String backupDir;

    Path second;

    @BeforeEach
    void setUp() throws IOException {
        wipe();
        second = Files.createTempDirectory("fd-second-copy");
    }

    @AfterEach
    void tearDown() throws IOException {
        wipe();
        deleteTree(second);
    }

    private void wipe() {
        try {
            deleteTree(Path.of(backupDir));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        settings.deleteAll();
        transactions.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
        notes.deleteAll();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void someData() {
        Account bank = accountService.create(new AccountData("Backup bank", AccountType.BANK, null, null, new BigDecimal("1000"), null, null));
        Category pay = categoryService.create(new CategoryData("Backup pay", CategoryKind.INCOME, null));
        noteService.create(new NoteService.NoteData(LocalDate.now(), "Diary", "A diary note that must be in the backup.", java.util.List.of("kept", "safe")));
        for (int i = 1; i <= 5; i++) {
            transactionService.create(new TransactionData(LocalDate.now().minusDays(i), TransactionKind.INCOME, null, bank.getId(),
                    new BigDecimal(100 * i), null, pay.getId(), "note " + i, null, null));
        }
    }

    private List<String> backupFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> BackupRetention.parse(n).isPresent()).sorted().toList();
        }
    }

    private void touch(Path folder, LocalDateTime time) throws IOException {
        Files.writeString(folder.resolve(BackupRetention.fileName(time)), "old");
    }

    // ------------------------------------------------------------------------------------------ the backup itself

    @Test
    void aBackupCopiesEveryTableAndRowAndIsCheckedAgainstTheLiveData() throws Exception {
        someData();

        Status status = service.backupNow("test");

        assertTrue(status.ok());
        assertEquals(Level.WARNING, status.level());   // no second folder yet
        assertNotNull(status.lastSuccess());
        assertTrue(status.lastMessage().contains("test"));
        assertEquals(1, status.files().size());
        assertTrue(status.files().get(0).sizeBytes() > 0);

        // The file really holds the same data as the live database, table by table.
        Path file = Path.of(backupDir).resolve(status.files().get(0).name());
        Map<String, String> inFile = service.fingerprintOfBackup(file);
        try (Connection c = dataSource.getConnection()) {
            Map<String, String> live = new java.util.TreeMap<>(BackupService.fingerprint(c));
            live.remove("APP_SETTING");   // the result of the backup is written here after the copy was made
            Map<String, String> copy = new java.util.TreeMap<>(inFile);
            copy.remove("APP_SETTING");
            assertEquals(live, copy);
        }
        assertTrue(inFile.get("FINANCIAL_TRANSACTION").startsWith("5:"), inFile.toString());
        assertTrue(inFile.get("NOTE").startsWith("1:"), inFile.toString());   // the diary is backed up too
        assertTrue(inFile.get("NOTE_TAG").startsWith("2:"), inFile.toString());   // and its tags
    }

    @Test
    void theFirstBackupOfTheMonthAlsoSavesTheExcelReportOnlyOnce() throws Exception {
        service.backupNow("one");
        Path report = Path.of(backupDir).resolve("report-" + YearMonth.now() + ".xlsx");
        assertTrue(Files.size(report) > 0);

        Files.writeString(report, "marker");
        service.backupNow("two");
        assertEquals("marker", Files.readString(report));   // not written again this month
    }

    @Test
    void aBackupCanBeRestoredIntoAFreshDatabaseWithEveryTransaction() throws Exception {
        someData();
        Status status = service.backupNow("test");
        Path file = Path.of(backupDir).resolve(status.files().get(0).name());
        Path where = Files.createTempDirectory("fd-restore");
        try {
            String url = "jdbc:h2:file:" + where.resolve("db");
            BackupRestorer.restore(file, url);

            try (Connection restored = DriverManager.getConnection(url, "sa", "");
                 Connection live = dataSource.getConnection()) {
                Map<String, String> expected = new java.util.TreeMap<>(BackupService.fingerprint(live));
                Map<String, String> actual = new java.util.TreeMap<>(BackupService.fingerprint(restored));
                expected.remove("APP_SETTING");   // the backup's own result is written after the copy was made
                actual.remove("APP_SETTING");
                assertEquals(expected, actual);
            }
        } finally {
            deleteTree(where);
        }
    }

    @Test
    void aCopyThatDoesNotMatchIsDeletedAndReportedAsAFailure() throws Exception {
        someData();
        BackupService tampering = new BackupService(dataSource, settings, export, backupDir) {
            @Override
            Map<String, String> fingerprintOfBackup(Path file) throws SQLException {
                Map<String, String> real = new java.util.TreeMap<>(super.fingerprintOfBackup(file));
                real.put("FINANCIAL_TRANSACTION", "4:abc");   // as if one transaction had been lost
                return real;
            }
        };

        Status status = tampering.backupNow("test");

        assertFalse(status.ok());
        assertEquals(Level.ERROR, status.level());
        assertTrue(status.banner().contains("did not match"), status.banner());
        assertTrue(backupFiles(Path.of(backupDir)).isEmpty());   // the bad copy is not kept
    }

    @Test
    void aBackupThatCannotBeWrittenIsReportedNotThrown() {
        BackupService broken = new BackupService(dataSource, settings, export, "/dev/null/backups");

        Status status = broken.backupNow("test");

        assertFalse(status.ok());
        assertEquals(Level.ERROR, status.level());
        assertTrue(status.banner().startsWith("The last backup failed"));
    }

    @Test
    void aFailedCheckNeverTouchesAnEarlierGoodBackupMadeInTheSameSecond() throws Exception {
        someData();
        service.backupNow("good");
        String good = backupFiles(Path.of(backupDir)).get(0);
        long size = Files.size(Path.of(backupDir).resolve(good));
        BackupService tampering = new BackupService(dataSource, settings, export, backupDir) {
            @Override
            Map<String, String> fingerprintOfBackup(Path file) {
                return Map.of();   // always "does not match"
            }
        };

        tampering.backupNow("bad");

        assertEquals(List.of(good), backupFiles(Path.of(backupDir)));   // still there, maybe replaced only by a checked one
        assertTrue(Files.size(Path.of(backupDir).resolve(good)) > 0 && size > 0);
        try (Stream<Path> left = Files.list(Path.of(backupDir))) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".part")));   // no half-written leftovers
        }
    }

    @Test
    void differencesNameTheTablesThatDisagree() {
        Map<String, String> live = Map.of("A", "2:x", "B", "1:y", "C", "0:z");
        Map<String, String> same = Map.of("A", "2:x", "B", "1:y", "C", "0:z");
        assertTrue(BackupService.differences(live, same).isEmpty());

        List<String> problems = BackupService.differences(live, Map.of("A", "1:x", "C", "0:z", "D", "0:q"));
        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("A has 1 rows, expected 2")));
        assertTrue(problems.contains("B is missing"));
        assertTrue(problems.contains("D should not be there"));
    }

    // ------------------------------------------------------------------------------------------ keeping and copying

    @Test
    void oldBackupsAreThinnedButTheFirstOfEachMonthIsKept() throws Exception {
        Path dir = Files.createDirectories(Path.of(backupDir));
        touch(dir, LocalDateTime.of(2020, 3, 5, 9, 0));
        touch(dir, LocalDateTime.of(2020, 3, 5, 18, 0));
        touch(dir, LocalDateTime.of(2020, 3, 20, 10, 0));
        touch(dir, LocalDateTime.of(2020, 4, 2, 10, 0));
        Files.writeString(dir.resolve("notes.txt"), "not a backup");

        service.backupNow("test");

        List<String> left = backupFiles(dir);
        assertTrue(left.contains(BackupRetention.fileName(LocalDateTime.of(2020, 3, 5, 9, 0))));
        assertTrue(left.contains(BackupRetention.fileName(LocalDateTime.of(2020, 4, 2, 10, 0))));
        assertFalse(left.contains(BackupRetention.fileName(LocalDateTime.of(2020, 3, 5, 18, 0))));
        assertFalse(left.contains(BackupRetention.fileName(LocalDateTime.of(2020, 3, 20, 10, 0))));
        assertEquals(3, left.size());                       // two old months and today's
        assertTrue(Files.exists(dir.resolve("notes.txt")));  // files that are not ours are never touched
    }

    @Test
    void theSecondFolderGetsEveryBackupAndReport() throws Exception {
        service.backupNow("before the second folder");

        Status status = service.setCopyFolder(second.toString());
        assertEquals(second.toString(), status.copyDir());
        assertEquals(Level.NONE, status.level());
        assertEquals(1, backupFiles(second).size());          // what already existed was copied over
        assertTrue(Files.exists(second.resolve("report-" + YearMonth.now() + ".xlsx")));

        touch(Path.of(backupDir), LocalDateTime.of(2020, 1, 15, 10, 0));   // an old monthly copy, always kept
        Thread.sleep(1100);   // backup names have a one-second resolution
        service.backupNow("after");
        assertEquals(3, backupFiles(second).size());          // the old one, the first real one, and the newest
    }

    @Test
    void theSecondFolderIsCheckedBeforeItIsAccepted() throws Exception {
        Path aFile = Files.createFile(second.resolve("plain-file"));

        assertThrows(ValidationException.class, () -> service.setCopyFolder("relative/folder"));
        assertThrows(ValidationException.class, () -> service.setCopyFolder(backupDir));
        assertThrows(ValidationException.class, () -> service.setCopyFolder(aFile.resolve("inside").toString()));
        assertNull(service.status().copyDir());
    }

    @Test
    void theSecondFolderMayStartWithATildeAndCanBeCleared() {
        String folder = "~/.financial-dashboard-test-" + System.nanoTime();
        try {
            Status set = service.setCopyFolder(folder);
            assertEquals(System.getProperty("user.home") + folder.substring(1), set.copyDir());
        } finally {
            Status cleared = service.setCopyFolder("  ");
            assertNull(cleared.copyDir());
            try {
                deleteTree(Path.of(System.getProperty("user.home") + folder.substring(1)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ warnings

    private void set(String key, String value) {
        settings.save(new AppSetting(key, value));
    }

    @Test
    void theWarningsFollowTheState() {
        assertEquals(Level.ERROR, service.status().level());
        assertEquals("No backup has been made yet.", service.status().banner());
        assertTrue(service.dueToday());

        service.backupNow("test");
        assertEquals(Level.WARNING, service.status().level());   // good, but no second folder
        assertFalse(service.dueToday());

        service.setCopyFolder(second.toString());
        assertEquals(Level.NONE, service.status().level());
        assertNull(service.status().banner());

        set("backup.copyOk", "false");
        set("backup.copyMessage", "disk full");
        assertEquals(Level.ERROR, service.status().level());
        assertTrue(service.status().banner().contains("disk full"));
        set("backup.copyOk", "true");

        set("backup.lastSuccessAt", LocalDateTime.now().minusDays(3).toString());
        assertEquals(Level.ERROR, service.status().level());
        assertTrue(service.status().banner().contains("3 days ago"), service.status().banner());
        assertTrue(service.dueToday());

        set("backup.lastOk", "false");
        set("backup.lastMessage", "no space left");
        assertFalse(service.status().ok());
        assertTrue(service.status().banner().contains("no space left"));
    }
}
