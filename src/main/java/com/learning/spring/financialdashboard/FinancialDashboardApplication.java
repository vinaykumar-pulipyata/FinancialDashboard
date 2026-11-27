package com.learning.spring.financialdashboard;

import com.learning.spring.financialdashboard.service.BackupRestorer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinancialDashboardApplication {

    public static void main(String[] args) {
        BackupRestorer.restoreIfRequested(args);   // --restore=FILE puts a backup back before the database is opened
        SpringApplication.run(FinancialDashboardApplication.class, args);
    }

}
