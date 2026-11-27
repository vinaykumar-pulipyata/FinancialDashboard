package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.service.SystemCategories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Makes sure Loans Given, Loans Collected and Liability Payments exist, also for a database created before they were added. */
@Component
@Order(2)   // after CategorySeeder, so the starter categories are still seeded on a first run
public class SystemCategoryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemCategoryInitializer.class);

    private final SystemCategories systemCategories;

    public SystemCategoryInitializer(SystemCategories systemCategories) {
        this.systemCategories = systemCategories;
    }

    @Override
    public void run(ApplicationArguments args) {
        systemCategories.ensureAll();
        log.debug("Fixed categories (Loans Given, Loans Collected, Liability Payments) are in place");
    }
}
