package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.service.LedgerService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Gives an older Future Expenses Ledger its Buffer and moves any money that belonged to no bucket into it. */
@Component
@Order(3)
public class LedgerInitializer implements ApplicationRunner {

    private final LedgerService ledgerService;

    public LedgerInitializer(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ledgerService.tidyUp();
    }
}
