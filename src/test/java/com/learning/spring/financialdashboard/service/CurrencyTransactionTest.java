package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.ExchangeRateRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Money moving in and out of accounts held in a foreign currency, and between two currencies. */
@SpringBootTest
class CurrencyTransactionTest {

    @Autowired
    AccountService accountService;
    @Autowired
    ExchangeRateService rates;
    @Autowired
    NetWorthService netWorth;
    @Autowired
    TransactionService transactionService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    ExchangeRateRepository rateRepository;

    Account bank;      // rupees, 10,000
    Account us;        // dollars, 100
    Account hanoi;     // dong, 1,000,000
    Category salary;
    Category food;

    @AfterEach
    void wipe() {
        transactionRepository.deleteAll();
        categoryRepository.deleteAll();
        accountRepository.deleteAll();
        rateRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        wipe();
        systemCategories.ensureAll();
        rates.save(Currency.USD, LocalDate.now().minusYears(1), n("80"));
        rates.save(Currency.VND, LocalDate.now().minusYears(1), n("0.0033"));
        bank = account("Bank", AccountType.BANK, "10000", null);
        us = account("US bank", AccountType.BANK, "100", "USD");
        hanoi = account("Hanoi cash", AccountType.CASH, "1000000", "VND");
        salary = categoryService.create(new CategoryData("Pay", CategoryKind.INCOME, null));
        food = categoryService.create(new CategoryData("Food", CategoryKind.EXPENSE, null));
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private Account account(String name, AccountType type, String balance, String currency) {
        return accountService.create(new AccountData(name, type, null, null, n(balance), null, null, currency));
    }

    private AccountSummary summary(Account a) {
        return accountService.listSummaries().stream().filter(s -> s.id().equals(a.getId())).findFirst().orElseThrow();
    }

    private Transaction income(Account to, Category category, String amount) {
        return transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.INCOME, null, to.getId(), n(amount), null,
                category.getId(), null, null, null));
    }

    private Transaction expense(Account from, Category category, String amount) {
        return transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, from.getId(), null, n(amount), null,
                category.getId(), null, null, null));
    }

    private Transaction transfer(Account from, Account to, String amount, String fee, String received) {
        return transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.TRANSFER, from.getId(), to.getId(), n(amount),
                fee == null ? null : n(fee), null, null, null, null, null, received == null ? null : n(received)));
    }

    @Test
    void incomeAndExpenseOnAForeignAccountAreInItsOwnCurrency() {
        income(us, salary, "50");
        expense(us, food, "30.5");

        assertAmount("119.5", summary(us).balance());
        assertAmount("9560", summary(us).balanceInr());   // 119.5 dollars at 80
    }

    @Test
    void youCannotSpendMoreThanAForeignAccountHolds() {
        ValidationException e = assertThrows(ValidationException.class, () -> expense(us, food, "100.01"));

        assertTrue(e.getMessage().contains("Insufficient"));
        expense(us, food, "100");
        assertAmount("0", summary(us).balance());
    }

    @Test
    void aTransferBetweenCurrenciesMovesWhatLeftOutAndWhatArrivedIn() {
        Transaction t = transfer(bank, us, "800", "10", "10");

        assertAmount("9190", summary(bank).balance());   // 800 and a 10 rupee fee
        assertAmount("110", summary(us).balance());      // ten dollars arrived
        assertAmount("10", t.getToAmount());
    }

    @Test
    void aTransferBetweenCurrenciesMustSayWhatArrived() {
        assertThrows(ValidationException.class, () -> transfer(bank, us, "800", null, null));
        assertThrows(ValidationException.class, () -> transfer(bank, us, "800", null, "0"));
    }

    @Test
    void aTransferInOneCurrencyHasNoReceivedAmount() {
        Account savings = account("Savings", AccountType.BANK, "0", null);

        assertThrows(ValidationException.class, () -> transfer(bank, savings, "100", null, "90"));
        Transaction same = transfer(bank, savings, "100", null, "100");

        assertNull(same.getToAmount(), "the same amount arrived, so nothing extra is kept");
        assertAmount("100", summary(savings).balance());
    }

    @Test
    void onlyATransferHasAReceivedAmount() {
        TransactionData data = new TransactionData(LocalDate.now(), TransactionKind.INCOME, null, us.getId(), n("5"), null,
                salary.getId(), null, null, null, null, n("5"));

        assertThrows(ValidationException.class, () -> transactionService.create(data));
    }

    @Test
    void aDongAmountIsAWholeNumberAndSoIsAFeeInDong() {
        assertThrows(ValidationException.class, () -> expense(hanoi, food, "100.5"));
        assertThrows(ValidationException.class, () -> transfer(hanoi, bank, "1000", "0.5", "3"));
        assertThrows(ValidationException.class, () -> transfer(bank, hanoi, "100", null, "30000.5"));
        transfer(bank, hanoi, "100", null, "30000");

        assertAmount("1030000", summary(hanoi).balance());
    }

    @Test
    void loansAndPaymentsTowardsALoanAreInRupeesOnly() {
        Account loan = account("Car loan", AccountType.DEBT, "5000", null);
        Category payments = systemCategories.find(SystemCategories.LIABILITY_PAYMENT).orElseThrow();

        TransactionData pay = new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, us.getId(), loan.getId(), n("5"), null,
                payments.getId(), null, null, null);

        ValidationException e = assertThrows(ValidationException.class, () -> transactionService.create(pay));
        assertTrue(e.getMessage().contains("rupees"));
    }

    @Test
    void netWorthCountsWhatArrivedInTheForeignAccountAtTheRate() {
        transfer(bank, us, "800", null, "10");   // 800 rupees became ten dollars

        List<Point> points = netWorth.history(1);

        assertAmount("21300", points.get(0).netWorth());   // 9,200 rupees + 110 dollars at 80 (8,800) + 1,000,000 dong at 0.0033 (3,300)
    }
}
