package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.service.AccountService;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blank database built the way a new install builds it: Flyway runs the migrations, then Hibernate only validates.
 * The context starting at all is the main check: with {@code ddl-auto=validate} it fails, naming the table and column,
 * if an entity has something the migrations do not create. Own in-memory database, so the other tests are not disturbed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flywaycheck;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"})
class FlywayMigrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    AccountService accounts;

    /** The account type column is an ENUM in the migrated schema, so a newly added type needs a migration that adds it. */
    @Test
    void everyAccountTypeCanBeSavedInTheMigratedSchema() {
        for (AccountType type : AccountType.values()) {
            accounts.create(new AccountData("Check " + type, type, null, null, BigDecimal.ONE, null, null));
        }
        assertEquals(AccountType.values().length, accounts.listSummaries().stream().filter(s -> s.name().startsWith("Check ")).count());
    }

    @Test
    void aBlankDatabaseIsBuiltByTheMigrationsAndHibernateAcceptsIt() {
        assertTrue(flyway.info().pending().length == 0, "every migration has been applied");
        MigrationVersion latest = Arrays.stream(flyway.info().all()).map(MigrationInfo::getVersion).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElseThrow();
        assertEquals(latest, flyway.info().current().getVersion(), "the newest migration is the one the database is at");
    }
}
