package com.learning.spring.financialdashboard.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Keeps the database open until Spring has finished with it. By default H2 closes a file database by itself when the JVM
 * starts to exit, at the same moment Spring is still stopping: the backup made when the app closes, and any request still
 * being answered, then fail with "Database is already closed". Adding {@code ;DB_CLOSE_ON_EXIT=FALSE} to the URL leaves
 * the closing to Spring, which does it last. It is added here, so it also applies to a URL given on the command line
 * (for example the demo database), not only to the one in application.properties.
 */
@Configuration
public class H2ShutdownConfig {

    private static final String FILE = "jdbc:h2:file:";
    private static final String OPTION = "DB_CLOSE_ON_EXIT";

    @Bean
    static BeanPostProcessor h2StaysOpenUntilSpringStops() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSourceProperties properties) {
                    properties.setUrl(withOwnShutdown(properties.getUrl()));
                }
                return bean;
            }
        };
    }

    /** The URL with the option added, when it is a file database that does not already say what to do at exit. */
    static String withOwnShutdown(String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith(FILE) || url.toUpperCase(Locale.ROOT).contains(OPTION)) {
            return url;
        }
        return url + ";" + OPTION + "=FALSE";
    }
}
