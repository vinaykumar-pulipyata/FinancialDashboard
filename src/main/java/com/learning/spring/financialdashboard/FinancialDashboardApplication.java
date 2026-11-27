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
        BackupRestorer.restoreIfRequested(args);   // --restore=FILE puts a backup back before the database is opened
        SpringApplication app = new SpringApplication(FinancialDashboardApplication.class);
        if (NativeDetector.inNativeImage()) {
            // the "native" profile (application-native.properties) tells Hibernate not to use ByteBuddy, which makes classes while the app
            // runs and so cannot work in a native image
            app.setAdditionalProfiles("native");
        }
        app.run(args);
    }

}
