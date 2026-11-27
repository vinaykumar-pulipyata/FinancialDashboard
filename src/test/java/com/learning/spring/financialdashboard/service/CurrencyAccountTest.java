package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.ExchangeRate;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.ExchangeRateRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Accounts held in a foreign currency: the rates, what is refused, and how the totals and net worth count them in rupees. */
@SpringBootTest
class CurrencyAccountTest {

    @Autowired
    AccountService accountService;

    @Autowired
    ExchangeRateService rates;

    @Autowired
    NetWorthService netWorth;

    @Autowired
    TransactionService transactionService;

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

    private static final LocalDate LONG_AGO = LocalDate.now().minusYears(2);

    @BeforeEach
    @AfterEach
    void wipe() {
        transactionRepository.deleteAll();
        categoryRepository.deleteAll();
        accountRepository.deleteAll();
        rateRepository.deleteAll();
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

    @Test
    void anAccountWithoutACurrencyIsInRupees() {
        Account bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("500"), null, null));

        assertEquals(Currency.INR, bank.getCurrency());
        assertAmount("500", summary(bank).balanceInr());
    }

    @Test
    void aForeignAccountNeedsARateFirst() {
        ValidationException e = assertThrows(ValidationException.class, () -> account("US bank", AccountType.BANK, "100", "USD"));

        assertTrue(e.getMessage().contains("exchange rate"));
    }

    @Test
    void aForeignBalanceIsCountedInRupeesAtTheLatestRate() {
        rates.save(Currency.USD, LONG_AGO, n("80"));
        rates.save(Currency.USD, LocalDate.now().minusDays(1), n("85.5"));

        Account us = account("US bank", AccountType.BANK, "100", "USD");

        assertAmount("100", summary(us).balance());
        assertAmount("8550", summary(us).balanceInr());
        assertEquals(Currency.USD, summary(us).currency());
    }

    @Test
    void aDongAmountIsAWholeNumberAndTheSmallRateKeepsItsDigits() {
        rates.save(Currency.VND, LONG_AGO, n("0.0033"));

        assertThrows(ValidationException.class, () -> account("Hanoi", AccountType.CASH, "1000.50", "VND"));
        Account hanoi = account("Hanoi", AccountType.CASH, "5000000", "VND");

        assertAmount("16500", summary(hanoi).balanceInr());
    }

    @Test
    void ratesAreCheckedBeforeTheyAreSaved() {
        assertThrows(ValidationException.class, () -> rates.save(Currency.INR, LONG_AGO, n("1")));
        assertThrows(ValidationException.class, () -> rates.save(Currency.USD, LONG_AGO, n("0")));
        assertThrows(ValidationException.class, () -> rates.save(Currency.USD, LONG_AGO, n("1.123456789")));
        assertThrows(ValidationException.class, () -> rates.save(Currency.USD, LocalDate.now().plusDays(1), n("80")));
        assertThrows(ValidationException.class, () -> Currency.parse("JPY"));
        assertEquals(Currency.INR, Currency.parse(null));
        assertEquals(Currency.EUR, Currency.parse(" eur "));
    }

    @Test
    void savingTheSameDayAgainReplacesTheRate() {
        rates.save(Currency.EUR, LONG_AGO, n("90"));
        rates.save(Currency.EUR, LONG_AGO, n("91"));

        List<ExchangeRate> all = rates.list();

        assertEquals(1, all.size());
        assertAmount("91", all.get(0).getRate());
    }

    @Test
    void theLastRateOfACurrencyInUseCannotBeDeleted() {
        ExchangeRate first = rates.save(Currency.GBP, LONG_AGO, n("100"));
        ExchangeRate second = rates.save(Currency.GBP, LONG_AGO.plusDays(1), n("101"));
        account("UK bank", AccountType.BANK, "10", "GBP");

        rates.delete(first.getId());
        assertThrows(ValidationException.class, () -> rates.delete(second.getId()));
    }

    @Test
    void onlyAccountsThatCanHoldMoneyCanBeForeign() {
        rates.save(Currency.USD, LONG_AGO, n("80"));

        assertThrows(ValidationException.class, () -> account("Goal", AccountType.GOAL_FUND, "10", "USD"));
        assertThrows(ValidationException.class, () -> account("Gems", AccountType.JEWELLERY, "10", "USD"));
        assertThrows(ValidationException.class, () -> account(null, AccountType.RECEIVABLE, "10", "USD"));
        assertThrows(ValidationException.class, () -> account("Card", AccountType.CREDIT_CARD, "10", "USD"));
        assertThrows(ValidationException.class, () -> account("Loan", AccountType.DEBT, "10", "USD"));
    }

    @Test
    void editingWithoutACurrencyKeepsTheAccountsCurrency() {
        rates.save(Currency.EUR, LONG_AGO, n("90"));
        Account eu = account("EU bank", AccountType.BANK, "100", "EUR");

        accountService.update(eu.getId(), new AccountData("EU bank", AccountType.BANK, null, null, n("120"), null, null));

        assertEquals(Currency.EUR, accountService.get(eu.getId()).getCurrency());
        assertAmount("10800", summary(eu).balanceInr());
    }

    @Test
    void theCurrencyCannotChangeOnceThereAreTransactions() {
        rates.save(Currency.USD, LONG_AGO, n("80"));
        systemCategories.ensureAll();
        Account bank = account("Bank", AccountType.BANK, "100", null);
        Account cash = account("Cash", AccountType.CASH, "0", null);
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.TRANSFER, bank.getId(), cash.getId(), n("5"), null, null, null, null, null));

        assertThrows(ValidationException.class, () -> accountService.update(bank.getId(),
                new AccountData("Bank", AccountType.BANK, null, null, n("100"), null, null, "USD")));
    }

    @Test
    void netWorthCountsAForeignAccountAtTheRateOfEachMonthEnd() {
        LocalDate now = LocalDate.now();
        rates.save(Currency.USD, now.minusMonths(3).withDayOfMonth(1), n("80"));
        rates.save(Currency.USD, now.minusMonths(1).withDayOfMonth(1), n("90"));
        account("Bank", AccountType.BANK, "1000", null);
        account("US bank", AccountType.BANK, "10", "USD");

        List<Point> points = netWorth.history(3);

        assertAmount("1800", points.get(0).netWorth());   // two months ago: 1000 + 10 dollars at 80
        assertAmount("1900", points.get(1).netWorth());   // last month: the 90 rate has started
        assertAmount("1900", points.get(2).netWorth());
    }
}
