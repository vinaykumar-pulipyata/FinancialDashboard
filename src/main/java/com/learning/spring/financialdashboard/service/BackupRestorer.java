package com.learning.spring.financialdashboard.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Puts a backup back. It runs before the app opens its database (see FinancialDashboardApplication.main): the backup is
 * first loaded into a brand new database file next to the real one; only when that worked is the current database
 * moved aside (never deleted) and the new one put in its place. A failed restore leaves everything as it was.
 */
public final class BackupRestorer {

    static final String DEFAULT_URL = "jdbc:h2:file:./data/financialdb";
    private static final String FILE_PREFIX = "jdbc:h2:file:";

    private BackupRestorer() {
    }

    /** Looks for --restore=FILE in the start-up arguments. Returns true when a restore was done. */
    public static boolean restoreIfRequested(String[] args) {
        String file = null;
        String url = DEFAULT_URL;
        for (String arg : args) {
            if (arg.startsWith("--restore=")) {
                file = arg.substring("--restore=".length());
            } else if (arg.startsWith("--spring.datasource.url=")) {
                url = arg.substring("--spring.datasource.url=".length());
            }
        }
        if (file == null) {
            return false;
        }
        try {
            Path moved = restore(Path.of(file), url);
            System.out.println("Restored " + file + (moved == null ? "" : ". The data it replaced was kept as " + moved));
            return true;
        } catch (Exception e) {
            // Do not start on the wrong data: stop, and leave the current database untouched.
            throw new IllegalStateException("Could not restore " + file + ": " + e.getMessage()
                    + ". Nothing was changed.", e);
        }
    }

    /** Restores a backup into the database at the given URL. Returns where the replaced database was moved, if any. */
    public static Path restore(Path backup, String url) throws SQLException, IOException {
        if (!Files.isRegularFile(backup)) {
            throw new IOException("The backup file " + backup + " does not exist");
        }
        if (!url.startsWith(FILE_PREFIX)) {
            throw new IOException("Only a file database can be restored (" + url + ")");
        }
        String base = url.substring(FILE_PREFIX.length());
        int options = base.indexOf(';');
        if (options >= 0) {
            base = base.substring(0, options);
        }
        Path live = Path.of(base + ".mv.db").toAbsolutePath();
        Path building = Path.of(base + "-restoring.mv.db").toAbsolutePath();
        Files.createDirectories(live.getParent());
        Files.deleteIfExists(building);

        try (Connection c = DriverManager.getConnection(FILE_PREFIX + base + "-restoring", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("RUNSCRIPT FROM '" + backup.toAbsolutePath().toString().replace("'", "''") + "' COMPRESSION ZIP");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    throw new IOException("the backup has no tables in it");
                }
            }
            st.execute("SHUTDOWN");   // close the new database for certain, so its file is complete and unlocked
        } catch (SQLException | IOException e) {
            Files.deleteIfExists(building);
            throw e;
        }

        Path moved = null;
        if (Files.exists(live)) {
            moved = live.resolveSibling(live.getFileName() + ".before-restore-"
                    + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()));
            Files.move(live, moved);
        }
        Files.move(building, live);
        return moved;
    }
}
