package com.learning.spring.financialdashboard.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupBanner {

    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

    private final Environment environment;

    public StartupBanner(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printUrl() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        // *.localhost names always point at this computer in current browsers, so no hosts-file entry is needed.
        String host = environment.getProperty("app.host", "finance.localhost");
        // The address with no port needs the optional proxy (Caddyfile, started by scripts/financial-dashboard.sh); the app
        // cannot tell whether it is running, so the message says when it works.
        log.info("Financial Dashboard is ready: http://{}:{}/ (http://localhost:{}/ works too; http://{}/ when the Caddy proxy is running)",
            host, port, port, host);
    }
}
