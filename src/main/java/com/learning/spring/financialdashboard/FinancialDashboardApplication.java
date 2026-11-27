package com.learning.spring.financialdashboard;

import com.learning.spring.financialdashboard.service.BackupRestorer;
import org.springframework.boot.SpringApplication;
import com.learning.spring.financialdashboard.config.NativeImageHints;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(NativeImageHints.class)   // only used when the GraalVM native image is built
public class FinancialDashboardApplication {

    public static void main(String[] args) {
        BackupRestorer.restoreIfRequested(args);   // --restore=FILE puts a backup back before the database is opened
        SpringApplication.run(FinancialDashboardApplication.class, args);
    }

}
