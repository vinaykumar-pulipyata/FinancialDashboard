package com.learning.spring.financialdashboard;

import com.learning.spring.financialdashboard.service.BackupRestorer;
import org.springframework.boot.SpringApplication;
import com.learning.spring.financialdashboard.config.NativeImageHints;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.NativeDetector;

@SpringBootApplication
@ImportRuntimeHints(NativeImageHints.class)   // only used when the GraalVM native image is built
public class FinancialDashboardApplication {

    public static void main(String[] args) {
        if (NativeDetector.inNativeImage()) {
            // Hibernate's default byte-code provider (ByteBuddy) makes classes while the app runs, which a native image cannot do.
            System.setProperty("hibernate.bytecode.provider", "none");
        }
        BackupRestorer.restoreIfRequested(args);   // --restore=FILE puts a backup back before the database is opened
        SpringApplication.run(FinancialDashboardApplication.class, args);
    }

}
