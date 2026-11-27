package com.learning.spring.financialdashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The database must stay open while Spring is still stopping. */
class H2ShutdownConfigTest {

    @Test
    void aFileDatabaseGetsTheOptionOnceAndNothingElseIsTouched() {
        assertEquals("jdbc:h2:file:./data/financialdb;DB_CLOSE_ON_EXIT=FALSE", H2ShutdownConfig.withOwnShutdown("jdbc:h2:file:./data/financialdb"));
        assertEquals("jdbc:h2:file:./data/demo;MODE=REGULAR;DB_CLOSE_ON_EXIT=FALSE", H2ShutdownConfig.withOwnShutdown("jdbc:h2:file:./data/demo;MODE=REGULAR"));
        assertEquals("JDBC:H2:FILE:./x;DB_CLOSE_ON_EXIT=FALSE", H2ShutdownConfig.withOwnShutdown("JDBC:H2:FILE:./x"));
        // already decided by whoever wrote the URL
        assertEquals("jdbc:h2:file:./x;DB_CLOSE_ON_EXIT=TRUE", H2ShutdownConfig.withOwnShutdown("jdbc:h2:file:./x;DB_CLOSE_ON_EXIT=TRUE"));
        assertEquals("jdbc:h2:file:./x;db_close_on_exit=false", H2ShutdownConfig.withOwnShutdown("jdbc:h2:file:./x;db_close_on_exit=false"));
        // not a file database
        assertEquals("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", H2ShutdownConfig.withOwnShutdown("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"));
        assertEquals("jdbc:postgresql://localhost/x", H2ShutdownConfig.withOwnShutdown("jdbc:postgresql://localhost/x"));
        assertNull(H2ShutdownConfig.withOwnShutdown(null));
    }

    @Test
    void theSpringPropertiesOfTheDataSourceAreUpdatedBeforeTheDataSourceIsBuilt() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl("jdbc:h2:file:./data/demo");

        var processor = H2ShutdownConfig.h2StaysOpenUntilSpringStops();
        Object returned = processor.postProcessAfterInitialization(properties, "dataSourceProperties");

        assertEquals(properties, returned);
        assertEquals("jdbc:h2:file:./data/demo;DB_CLOSE_ON_EXIT=FALSE", properties.getUrl());
        assertEquals("other", processor.postProcessAfterInitialization("other", "x"), "other beans pass through");
    }

    @Test
    void withTheOptionADatabaseCanStillBeUsedFromAShutdownHookEveryTime() throws Exception {
        Path folder = Files.createTempDirectory("fd-h2-exit");
        try {
            String url = H2ShutdownConfig.withOwnShutdown("jdbc:h2:file:" + folder.resolve("db"));
            List<String> classpath = List.of(location(ShutdownProbe.class), location(Class.forName("org.h2.Driver")));
            for (int i = 0; i < 8; i++) {
                Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", String.join(File.pathSeparator, classpath), ShutdownProbe.class.getName(), url)
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes()).trim();
                process.waitFor();
                assertEquals("OK", output, "run " + i);
            }
        } finally {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
