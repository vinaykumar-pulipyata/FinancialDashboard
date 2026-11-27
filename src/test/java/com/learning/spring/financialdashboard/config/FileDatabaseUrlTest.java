package com.learning.spring.financialdashboard.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real application context on a FILE database (its own, in the temp folder, so the shared test database is not
 * touched): whatever URL it is given, the connection pool must end up with DB_CLOSE_ON_EXIT=FALSE.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/financial-dashboard-file-url-test/db",
        "app.backup.automatic=false",
        "app.backup.dir=${java.io.tmpdir}/financial-dashboard-file-url-test/backups"})
class FileDatabaseUrlTest {

    @Autowired
    DataSource dataSource;

    @AfterAll
    static void tidyUp() {
        File folder = new File(System.getProperty("java.io.tmpdir"), "financial-dashboard-file-url-test");
        deleteTree(folder);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    @Test
    void theApplicationOpensItsFileDatabaseWithTheOptionThatKeepsItOpenUntilSpringStops() {
        String url = ((HikariDataSource) dataSource).getJdbcUrl();
        assertTrue(url.startsWith("jdbc:h2:file:"), url);
        assertTrue(url.endsWith(";DB_CLOSE_ON_EXIT=FALSE"), url);
        assertEquals(1, url.split("DB_CLOSE_ON_EXIT").length - 1, "added once");
    }
}
