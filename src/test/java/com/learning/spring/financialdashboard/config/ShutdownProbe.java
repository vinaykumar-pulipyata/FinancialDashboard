package com.learning.spring.financialdashboard.config;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Run in a separate JVM by H2ShutdownConfigTest. Like the backup that is made when the app closes, it uses the database
 * from a shutdown hook while the JVM is exiting, and says whether that worked.
 */
public final class ShutdownProbe {

    public static void main(String[] args) throws Exception {
        Connection connection = DriverManager.getConnection(args[0], "sa", "");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                connection.createStatement().executeQuery("SELECT 1");
                System.out.println("OK");
            } catch (Exception e) {
                System.out.println("FAILED " + e.getMessage());
            }
        }));
        System.exit(0);
    }
}
