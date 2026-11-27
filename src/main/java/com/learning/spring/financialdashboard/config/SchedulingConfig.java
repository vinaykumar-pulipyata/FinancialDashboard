package com.learning.spring.financialdashboard.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the hourly backup check. Tests switch it off with app.backup.automatic=false. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.backup.automatic", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
