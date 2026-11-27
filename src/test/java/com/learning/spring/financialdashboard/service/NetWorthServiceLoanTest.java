package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerData;
import com.learning.spring.financialdashboard.service.NetWorthService.Point;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Net worth when money is lent and collected, and when a transfer costs a fee. */
@SpringBootTest
class NetWorthServiceLoanTest {

    @Autowired
    NetWorthService service;
    @Autowired
    AccountService accountService;
    @Autowired
    LoanService loanService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    BorrowerRepository borrowers;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;

    @BeforeEach
    @AfterEach
    void clean() {
        transactions.deleteAll();
        borrowers.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
    }

    @Test
    void lendingCollectingAndFeesAllShowUpInTheLatestMonth() {
        systemCategories.ensureAll();
        Account bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, new BigDecimal("1000"), null, null));
        Account cash = accountService.create(new AccountData("Cash", AccountType.CASH, null, null, BigDecimal.ZERO, null, null));
        accountService.create(new AccountData(null, AccountType.RECEIVABLE, null, null, new BigDecimal("500"), null, null));
        Long person = loanService.create(new BorrowerData("Ravi", null)).getId();
        LocalDate today = LocalDate.now();

        transactionService.create(new TransactionData(today, TransactionKind.EXPENSE, bank.getId(), null, new BigDecimal("200"), null,
                systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), null, null, person));
        transactionService.create(new TransactionData(today, TransactionKind.INCOME, null, bank.getId(), new BigDecimal("50"), null,
                systemCategories.require(SystemCategories.LOANS_COLLECTED).getId(), null, null, person));
        transactionService.create(new TransactionData(today, TransactionKind.TRANSFER, bank.getId(), cash.getId(), new BigDecimal("100"),
                new BigDecimal("5"), null, null, null, null));

        List<Point> history = service.history(2);
        Point latest = history.get(history.size() - 1);

        // bank 1000 - 200 + 50 - 105 = 745 and cash 100; the receivables (500 + 200 - 50 = 650) are not counted
        assertEquals(0, new BigDecimal("845").compareTo(latest.netWorth()));
        assertEquals(0, new BigDecimal("845").compareTo(latest.assets()));
        assertEquals(0, BigDecimal.ZERO.compareTo(latest.liabilities()));
    }
}
