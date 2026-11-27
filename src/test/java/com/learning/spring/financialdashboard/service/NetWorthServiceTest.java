package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerData;
import com.learning.spring.financialdashboard.service.NetWorthService.Point;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class NetWorthServiceTest {

    @Autowired
    NetWorthService service;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    LoanService loanService;

    @Autowired
    SystemCategories systemCategories;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    BorrowerRepository borrowerRepository;

    @Autowired
    CategoryRepository categoryRepository;

    Account bank;
    Account card;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        borrowerRepository.deleteAll();
        categoryRepository.deleteAll();
        accountRepository.deleteAll();
        systemCategories.ensureAll();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("1000"), null, null));
        card = accountService.create(new AccountData("Card", AccountType.CREDIT_CARD, null, null, n("300"), null, null));
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private LocalDate inMonth(int monthsAgo) {
        return YearMonth.now().minusMonths(monthsAgo).atDay(15);
    }

    private void income(LocalDate date, String amount) {
        transactionService.create(new TransactionData(date, TransactionKind.INCOME, null, bank.getId(), n(amount),
                null, null, null, null, null));
    }

    private void expense(LocalDate date, String amount) {
        transactionService.create(new TransactionData(date, TransactionKind.EXPENSE, bank.getId(), null, n(amount),
                null, null, null, null, null));
    }

    @Test
    void listsTheLastMonthsOldestFirstEndingWithThisMonth() {
        List<Point> points = service.history(3);

        assertEquals(3, points.size());
        assertEquals(YearMonth.now().minusMonths(2).toString(), points.get(0).month());
        assertEquals(YearMonth.now().toString(), points.get(2).month());
        assertEquals(YearMonth.now().getYear(), Integer.parseInt(points.get(2).label().substring(4)));
    }

    @Test
    void theMonthsAreClampedToBetweenOneAndSixty() {
        assertEquals(1, service.history(0).size());
        assertEquals(60, service.history(500).size());
    }

    @Test
    void netWorthAtEachMonthEndFollowsTheTransactions() {
        income(inMonth(2), "500");      // two months ago
        expense(inMonth(1), "200");     // last month

        List<Point> points = service.history(3);

        assertAmount("1200", points.get(0).netWorth());     // 1,000 + 500 - 300 owed on the card
        assertAmount("1000", points.get(1).netWorth());     // 200 spent
        assertAmount("1000", points.get(2).netWorth());
        assertAmount("1500", points.get(0).assets());       // the card is a liability, so it is not in assets
        assertAmount("300", points.get(0).liabilities());
    }

    @Test
    void aLiabilityFlipsItsSign() {
        // Spending on the card raises what is owed and lowers net worth.
        transactionService.create(new TransactionData(inMonth(1), TransactionKind.EXPENSE, card.getId(), null,
                n("100"), null, null, null, null, null));

        List<Point> points = service.history(2);

        assertAmount("400", points.get(0).liabilities());       // 300 owed to start with + 100 spent
        assertAmount("600", points.get(0).netWorth());          // 1,000 - 400
    }

    @Test
    void aTransactionDatedInTheFutureIsInTheLatestPointLikeTheBalancesButNotInEarlierMonths() {
        income(LocalDate.now().plusDays(10), "999");

        List<Point> points = service.history(2);
        assertAmount("700", points.get(0).netWorth());                    // 1,000 - 300
        assertAmount("1699", points.get(1).netWorth());                   // + the 999 booked ahead, as the balances count it
    }

    @Test
    void moneyLentIsYoursButNotWithYouSoItIsLeftOutUntilItIsCollected() {
        Borrower ravi = loanService.create(new BorrowerData("Ravi", null));
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.INCOME, null, bank.getId(),
                n("5000"), null, null, null, null, null));   // so the bank holds enough to lend
        BigDecimal before = service.history(1).get(0).netWorth();

        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, bank.getId(), null,
                n("5000"), null, systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), null, null, ravi.getId()));

        assertAmount(before.subtract(n("5000")).toPlainString(), service.history(1).get(0).netWorth());   // the bank lost it, Receivables is not counted

        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.INCOME, null, bank.getId(),
                n("5000"), null, systemCategories.require(SystemCategories.LOANS_COLLECTED).getId(), null, null, ravi.getId()));

        assertAmount(before.toPlainString(), service.history(1).get(0).netWorth());                       // back with you
    }

    @Test
    void theReceivablesOpeningAmountIsNotInNetWorthEither() {
        BigDecimal before = service.history(1).get(0).netWorth();

        accountService.create(new AccountData(null, AccountType.RECEIVABLE, null, null, n("25000"), null, null));

        assertAmount(before.toPlainString(), service.history(1).get(0).netWorth());
        Point latest = service.history(1).get(0);
        assertAmount(before.toPlainString(), latest.assets().subtract(latest.liabilities()));   // the assets leave it out too
    }

    @Test
    void anInactiveAccountIsLeftOut() {
        accountService.deactivate(card.getId());

        assertAmount("1000", service.history(1).get(0).netWorth());
    }
}
