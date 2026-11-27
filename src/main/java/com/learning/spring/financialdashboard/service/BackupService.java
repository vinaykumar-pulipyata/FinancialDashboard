package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.AppSetting;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Backups of the whole database. A backup is a zipped file of SQL commands (H2's SCRIPT) that rebuilds everything:
 * every table and every row. Each one is checked straight away: it is loaded into a throw-away database and every
 * table's row count and content fingerprint is compared with the live data, taken in the same snapshot. A copy that
 * does not match is deleted and reported as a failure. Good copies are also placed in a second folder when one is set.
 * Old backups are thinned by {@link BackupRetention}. On the first backup of each month an Excel report is saved too.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final int STALE_AFTER_DAYS = 2;
    private static final int LISTED_FILES = 12;

    private static final String LAST_ATTEMPT_OK = "backup.lastOk";
    private static final String LAST_SUCCESS = "backup.lastSuccessAt";
    private static final String LAST_MESSAGE = "backup.lastMessage";
    private static final String COPY_DIR = "backup.copyDir";
    private static final String COPY_OK = "backup.copyOk";
    private static final String COPY_MESSAGE = "backup.copyMessage";

    public enum Level { NONE, WARNING, ERROR }

    public record BackupFile(String name, long sizeBytes, LocalDateTime made) {
    }

    /** What the screen shows. {@code level} and {@code banner} say whether something needs attention. */
    public record Status(boolean ok, LocalDateTime lastSuccess, String lastMessage, String directory, String copyDir,
                         Level level, String banner, List<BackupFile> files) {
    }

    private final DataSource dataSource;
    private final AppSettingRepository settings;
    private final ExportService export;
    private final Path dir;

    public BackupService(DataSource dataSource, AppSettingRepository settings, ExportService export,
                         @Value("${app.backup.dir:./backups}") String dir) {
        this.dataSource = dataSource;
        this.settings = settings;
        this.export = export;
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------------------------------ making a backup

    /** Makes, checks and files one backup. Never throws: a failure is recorded and shown on the dashboard. */
    public synchronized Status backupNow(String reason) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Path file = dir.resolve(BackupRetention.fileName(now));
        Path part = dir.resolve(file.getFileName() + ".part");   // written and checked under this name first
        try {
            Files.createDirectories(dir);
            Map<String, String> live = writeScript(part);
            List<String> differences = differences(live, fingerprintOfBackup(part));
            if (!differences.isEmpty()) {
                throw new IllegalStateException("the copy did not match your data (" + String.join("; ", differences) + ")");
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);   // only a checked copy ever gets a real name
            long rows = live.values().stream().mapToLong(v -> Long.parseLong(v.substring(0, v.indexOf(':')))).sum();
            put(LAST_ATTEMPT_OK, "true");
            put(LAST_SUCCESS, now.toString());
            put(LAST_MESSAGE, "Backed up " + live.size() + " tables and " + rows + " rows (" + reason + ")");
            log.info("Backup written: {} ({}, {} rows)", file, reason, rows);
        } catch (Exception e) {
            log.error("Backup failed", e);
            deleteQuietly(part);
            put(LAST_ATTEMPT_OK, "false");
            put(LAST_MESSAGE, describe(e));
            return status();
        }
        try {
            monthlyReport(now);
        } catch (Exception e) {
            log.warn("The monthly Excel report could not be saved", e);
        }
        thin(dir);
        copyToSecondFolder();
        return status();
    }

    /** Writes the SCRIPT file and fingerprints the live tables inside the same snapshot, so they describe the same data. */
    private Map<String, String> writeScript(Path file) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            try {
                c.setAutoCommit(false);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try (Statement st = c.createStatement()) {
                    // NOSETTINGS: only the tables and rows are saved, not session settings such as how long a database stays open.
                    st.execute("SCRIPT NOSETTINGS TO '" + quote(file) + "' COMPRESSION ZIP");
                }
                return fingerprint(c);
            } finally {
                c.rollback();
                c.setTransactionIsolation(isolation);
                c.setAutoCommit(autoCommit);
            }
        }
    }

    /** Loads a backup into a temporary in-memory database and fingerprints what it contains. */
    Map<String, String> fingerprintOfBackup(Path file) throws SQLException {
        String name = "backupcheck" + System.nanoTime();
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("RUNSCRIPT FROM '" + quote(file) + "' COMPRESSION ZIP");
            return fingerprint(c);
        }
    }

    /** For every table: "rowCount:sha256 of all its rows in key order". */
    static Map<String, String> fingerprint(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                     + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        Map<String, String> result = new TreeMap<>();
        for (String table : tables) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY 1")) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                ResultSetMetaData meta = rs.getMetaData();
                long rows = 0;
                while (rs.next()) {
                    rows++;
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        digest.update(String.valueOf(rs.getString(i)).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0x1f);
                    }
                    digest.update((byte) 0x1e);
                }
                result.put(table, rows + ":" + String.format("%064x", new BigInteger(1, digest.digest())));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    /** The tables that differ between the live data and the copy; empty when they are identical. */
    static List<String> differences(Map<String, String> live, Map<String, String> copy) {
        List<String> problems = new ArrayList<>();
        live.forEach((table, print) -> {
            String other = copy.get(table);
            if (other == null) {
                problems.add(table + " is missing");
            } else if (!other.equals(print)) {
                problems.add(table + " has " + other.substring(0, other.indexOf(':')) + " rows, expected "
                        + print.substring(0, print.indexOf(':')) + " or its contents differ");
            }
        });
        copy.keySet().stream().filter(t -> !live.containsKey(t)).forEach(t -> problems.add(t + " should not be there"));
        return problems;
    }

    private void monthlyReport(LocalDateTime now) throws IOException {
        Path report = dir.resolve("report-" + YearMonth.from(now) + ".xlsx");
        if (!Files.exists(report)) {
            Files.write(report, export.build());
        }
    }

    // ------------------------------------------------------------------------------------------ keeping and copying

    /** Deletes the backups the retention rule no longer needs in one folder. Only files with our own names are touched. */
    private void thin(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            Map<LocalDateTime, Path> byTime = new TreeMap<>();
            files.forEach(p -> BackupRetention.parse(p.getFileName().toString()).ifPresent(t -> byTime.put(t, p)));
            for (LocalDateTime t : BackupRetention.toDelete(byTime.keySet(), LocalDateTime.now().toLocalDate())) {
                Files.deleteIfExists(byTime.get(t));
            }
        } catch (IOException e) {
            log.warn("Could not thin the old backups in {}", folder, e);
        }
    }

    /** Puts every backup and report that the second folder does not have yet in it, then thins it the same way. */
    private void copyToSecondFolder() {
        Optional<String> target = get(COPY_DIR);
        if (target.isEmpty()) {
            return;
        }
        try {
            Path second = Paths.get(target.get());
            Files.createDirectories(second);
            copyMissing(dir, second);
            thin(second);
            put(COPY_OK, "true");
            put(COPY_MESSAGE, "");
        } catch (Exception e) {
            log.error("Copying to the second folder failed", e);
            put(COPY_OK, "false");
            put(COPY_MESSAGE, describe(e));
        }
    }

    private void copyMissing(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.list(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                boolean ours = BackupRetention.parse(name).isPresent() || (name.startsWith("report-") && name.endsWith(".xlsx"));
                Path copy = to.resolve(name);
                if (ours && !(Files.exists(copy) && Files.size(copy) == Files.size(p))) {
                    Files.copy(p, copy, StandardCopyOption.REPLACE_EXISTING);
                    if (Files.size(copy) != Files.size(p)) {
                        throw new IOException("the copy of " + name + " is not the same size");
                    }
                }
            }
        }
    }

    /** Sets (or, with a blank path, clears) the second folder, checks it can be written to, and copies the backups there. */
    public synchronized Status setCopyFolder(String path) {
        if (path == null || path.isBlank()) {
            settings.deleteById(COPY_DIR);
            settings.deleteById(COPY_OK);
            settings.deleteById(COPY_MESSAGE);
            return status();
        }
        String text = path.trim();
        if (text.equals("~") || text.startsWith("~/")) {
            text = System.getProperty("user.home") + text.substring(1);
        }
        Path folder = Paths.get(text);
        if (!folder.isAbsolute()) {
            throw new ValidationException("Give the full path of the folder, for example /Users/you/Dropbox/finance-backups");
        }
        folder = folder.normalize();
        if (folder.equals(dir)) {
            throw new ValidationException("Choose a different folder from the main backups folder");
        }
        try {
            Files.createDirectories(folder);
            Path probe = Files.createTempFile(folder, "write-test", ".tmp");
            Files.delete(probe);
        } catch (IOException | RuntimeException e) {
            throw new ValidationException("Cannot write to that folder: " + describe(e));
        }
        put(COPY_DIR, folder.toString());
        copyToSecondFolder();
        return status();
    }

    // ------------------------------------------------------------------------------------------ status

    public Status status() {
        Optional<LocalDateTime> lastSuccess = get(LAST_SUCCESS).map(LocalDateTime::parse);
        boolean lastOk = !"false".equals(get(LAST_ATTEMPT_OK).orElse("true"));
        Optional<String> copyDir = get(COPY_DIR);
        boolean copyOk = !"false".equals(get(COPY_OK).orElse("true"));

        Level level = Level.NONE;
        String banner = null;
        if (!lastOk) {
            level = Level.ERROR;
            banner = "The last backup failed: " + get(LAST_MESSAGE).orElse("unknown reason") + ". Your data is not safe until this is fixed.";
        } else if (lastSuccess.isEmpty()) {
            level = Level.ERROR;
            banner = "No backup has been made yet.";
        } else if (Duration.between(lastSuccess.get(), LocalDateTime.now()).toDays() >= STALE_AFTER_DAYS) {
            level = Level.ERROR;
            banner = "The last good backup was " + Duration.between(lastSuccess.get(), LocalDateTime.now()).toDays()
                    + " days ago. Open the app every day, or press Back up now.";
        } else if (copyDir.isPresent() && !copyOk) {
            level = Level.ERROR;
            banner = "The copy in your second folder failed: " + get(COPY_MESSAGE).orElse("unknown reason");
        } else if (copyDir.isEmpty()) {
            level = Level.WARNING;
            banner = "Your backups are only on this computer. Choose a second folder (iCloud, Dropbox or a USB drive) so they survive a broken disk.";
        }
        return new Status(lastOk, lastSuccess.orElse(null), get(LAST_MESSAGE).orElse(null), dir.toString(),
                copyDir.orElse(null), level, banner, listFiles());
    }

    private List<BackupFile> listFiles() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> BackupRetention.parse(p.getFileName().toString()).isPresent())
                    .map(p -> {
                        try {
                            return new BackupFile(p.getFileName().toString(), Files.size(p),
                                    BackupRetention.parse(p.getFileName().toString()).orElseThrow());
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .sorted(Comparator.comparing(BackupFile::made).reversed())
                    .limit(LISTED_FILES)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** True when there is no good backup from the last 24 hours. */
    public boolean dueToday() {
        return get(LAST_SUCCESS).map(LocalDateTime::parse)
                .map(t -> Duration.between(t, LocalDateTime.now()).toHours() >= 24).orElse(true);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Optional<String> get(String key) {
        return settings.findById(key).map(AppSetting::getValue).filter(v -> !v.isEmpty());
    }

    private void put(String key, String value) {
        AppSetting setting = settings.findById(key).orElseGet(() -> new AppSetting(key, value));
        setting.setValue(value.length() > 1000 ? value.substring(0, 1000) : value);
        settings.save(setting);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}", file, e);
        }
    }

    private static String quote(Path file) {
        return file.toAbsolutePath().toString().replace("'", "''");
    }

    private static String describe(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.replace('\n', ' ');
    }
}
