package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.service.PortfolioService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Gives holdings from before the stats existed a first recorded value (today's), so their graph has a starting point. */
@Component
@Order(4)
public class PortfolioInitializer implements ApplicationRunner {

    private final PortfolioService portfolioService;

    public PortfolioInitializer(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @Override
    public void run(ApplicationArguments args) {
        portfolioService.recordMissingSnapshots();
    }
}
