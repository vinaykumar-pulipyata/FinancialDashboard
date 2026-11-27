package com.learning.spring.financialdashboard.startup;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A database made before Flyway (the tables exist, there is no history) is marked as version 1 and V1 is skipped, so the
 * real data is never touched; the later migrations (V2 and on) are then applied on top. The old database is made by running V1 by hand and putting a row in it; if Flyway ran V1
 * again, the tables would already exist and migrate() would fail, and a rebuilt table would lose the row.
 */
class FlywayBaselineTest {

    @Test
    void anExistingDatabaseIsBaselinedAtVersionOneAndItsDataIsKept() {
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:baselinecheck;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__baseline.sql")).execute(db);
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.update("insert into account (active, level, name, opening_balance, type) values (true, 1, 'Wallet', 100.00, 'CASH')");

        Flyway flyway = Flyway.configure().dataSource(db).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load();
        flyway.migrate();

        // Baselined at 1, so V1 was not run again; every later migration has been applied on top of it (the latest one is current).
        MigrationVersion latest = Arrays.stream(flyway.info().all()).map(MigrationInfo::getVersion).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElseThrow();
        assertEquals(latest, flyway.info().current().getVersion());
        assertEquals(0, flyway.info().pending().length, "nothing is left to apply");
        assertEquals(1, jdbc.queryForObject("select count(*) from account where name = 'Wallet'", Integer.class));
        // V2 added the statement day to credit cards: the column is there and the old rows would just have none.
        assertEquals(0, jdbc.queryForObject("select count(*) from credit_card_detail where statement_day is not null", Integer.class));
    }
}
