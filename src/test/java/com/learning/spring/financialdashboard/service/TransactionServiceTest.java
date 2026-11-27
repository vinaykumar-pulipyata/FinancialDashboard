package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class TransactionServiceTest {

    @Autowired
    TransactionService service;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository repository;

    @Autowired
    CategoryService categoryService;

    @Autowired
    CategoryRepository categoryRepository;

    Account bank;
    Account fund;
    Account card;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        categoryRepository.deleteAll();
        accountRepository.deleteAll();
        bank = account("Bank", AccountType.BANK, "1000");
        fund = account("Emergency", AccountType.GOAL_FUND, "0");
        card = account("Card", AccountType.CREDIT_CARD, "500");
    }

    private Account account(String name, AccountType type, String opening) {
        return accountService.create(new AccountData(name, type, null, null, new BigDecimal(opening), null, null));
    }

    private TransactionData data(TransactionKind kind, Long from, Long to, String amount) {
        return new TransactionData(LocalDate.of(2026, 9, 30), kind, from, to, new BigDecimal(amount), null, null, null, "test", null);
    }

    private BigDecimal balance(Account a) {
        return accountService.listSummaries().stream()
                .filter(s -> s.id().equals(a.getId())).map(AccountSummary::balance).findFirst().orElseThrow();
    }

    private void assertBalance(String expected, Account a) {
        assertEquals(0, new BigDecimal(expected).compareTo(balance(a)), "balance of " + a.getName());
    }

    @Test
    void incomeRaisesBalanceAndExpenseLowersIt() {
        service.create(data(TransactionKind.INCOME, null, bank.getId(), "300"));
        assertBalance("1300", bank);

        service.create(data(TransactionKind.EXPENSE, bank.getId(), null, "100"));
        assertBalance("1200", bank);
    }

    @Test
    void transferMovesMoneyAndKeepsTotalUnchanged() {
        service.create(data(TransactionKind.TRANSFER, bank.getId(), fund.getId(), "250"));

        assertBalance("750", bank);
        assertBalance("250", fund);
    }

    @Test
    void creditCardSpendRaisesAmountOwedAndPaymentLowersIt() {
        service.create(data(TransactionKind.EXPENSE, card.getId(), null, "200"));
        assertBalance("700", card);

        service.create(data(TransactionKind.TRANSFER, bank.getId(), card.getId(), "300"));
        assertBalance("400", card);
        assertBalance("700", bank);
    }

    @Test
    void requiresAccountsMatchingTheKind() {
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.INCOME, null, null, "10")));
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.INCOME, bank.getId(), bank.getId(), "10")));
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.EXPENSE, null, bank.getId(), "10")));
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.TRANSFER, bank.getId(), null, "10")));
    }

    @Test
    void rejectsBadAmountSameAccountTransferAndMissingFields() {
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.INCOME, null, bank.getId(), "0")));
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.INCOME, null, bank.getId(), "-5")));
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.TRANSFER, bank.getId(), bank.getId(), "10")));
        assertThrows(ValidationException.class, () -> service.create(
                new TransactionData(null, TransactionKind.INCOME, null, bank.getId(), BigDecimal.TEN, null, null, null, null, null)));
        assertThrows(ValidationException.class, () -> service.create(
                new TransactionData(LocalDate.now(), null, null, bank.getId(), BigDecimal.TEN, null, null, null, null, null)));
    }

    @Test
    void rejectsInactiveAndUnknownAccounts() {
        accountService.deactivate(fund.getId());

        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.TRANSFER, bank.getId(), fund.getId(), "10")));
        assertThrows(ValidationException.class,
                () -> service.create(data(TransactionKind.INCOME, null, 9999L, "10")));
    }

    @Test
    void editingAndDeletingChangeBalances() {
        Transaction t = service.create(data(TransactionKind.INCOME, null, bank.getId(), "300"));

        service.update(t.getId(), data(TransactionKind.INCOME, null, bank.getId(), "100"));
        assertBalance("1100", bank);

        service.delete(t.getId());
        assertBalance("1000", bank);
    }

    @Test
    void accountWithTransactionsCannotBeDeleted() {
        service.create(data(TransactionKind.INCOME, null, bank.getId(), "10"));

        assertThrows(ValidationException.class, () -> accountService.delete(bank.getId()));
    }

    private TransactionData withFeeAndCategory(TransactionKind kind, Long from, Long to, String fee, Long categoryId) {
        return new TransactionData(LocalDate.of(2026, 9, 30), kind, from, to, new BigDecimal("100"),
                fee == null ? null : new BigDecimal(fee), categoryId, "a note", "a description", null);
    }

    @Test
    void transferFeeIsTakenFromTheSourceOnTopOfTheAmount() {
        service.create(withFeeAndCategory(TransactionKind.TRANSFER, bank.getId(), fund.getId(), "10", null));

        assertBalance("890", bank);
        assertBalance("100", fund);
    }

    @Test
    void feesAreOnlyAllowedOnTransfersAndCannotBeNegative() {
        assertThrows(ValidationException.class, () -> service.create(
                withFeeAndCategory(TransactionKind.EXPENSE, bank.getId(), null, "5", null)));
        assertThrows(ValidationException.class, () -> service.create(
                withFeeAndCategory(TransactionKind.TRANSFER, bank.getId(), fund.getId(), "-5", null)));
    }

    @Test
    void categoryMustMatchTheKindAndTransfersHaveNone() {
        Category salary = categoryService.create(new CategoryData("Salary", CategoryKind.INCOME, null));
        Category food = categoryService.create(new CategoryData("Food", CategoryKind.EXPENSE, null));

        Transaction ok = service.create(
                withFeeAndCategory(TransactionKind.INCOME, null, bank.getId(), null, salary.getId()));
        assertEquals(salary.getId(), ok.getCategoryId());
        assertEquals("a note", ok.getNote());

        assertThrows(ValidationException.class, () -> service.create(
                withFeeAndCategory(TransactionKind.INCOME, null, bank.getId(), null, food.getId())));
        assertThrows(ValidationException.class, () -> service.create(
                withFeeAndCategory(TransactionKind.TRANSFER, bank.getId(), fund.getId(), null, salary.getId())));
        assertThrows(ValidationException.class, () -> service.create(
                withFeeAndCategory(TransactionKind.INCOME, null, bank.getId(), null, 9999L)));
    }

    @Test
    void anExpenseOrTransferCannotTakeMoreThanTheAccountHolds() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(com.learning.spring.financialdashboard.exception.ValidationException.class,
                () -> service.create(data(TransactionKind.EXPENSE, bank.getId(), null, "1000.01")));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().startsWith("Insufficient balance in 'Bank'"));
        org.junit.jupiter.api.Assertions.assertThrows(com.learning.spring.financialdashboard.exception.ValidationException.class,
                () -> service.create(data(TransactionKind.TRANSFER, bank.getId(), fund.getId(), "1001")));
        service.create(data(TransactionKind.TRANSFER, bank.getId(), fund.getId(), "1000"));   // exactly the balance is fine
        org.junit.jupiter.api.Assertions.assertThrows(com.learning.spring.financialdashboard.exception.ValidationException.class,
                () -> service.create(data(TransactionKind.EXPENSE, bank.getId(), null, "1")));
    }

    @Test
    void editingATransactionGetsItsOldAmountBackBeforeTheCheck() {
        Transaction t = service.create(data(TransactionKind.EXPENSE, bank.getId(), null, "900"));
        service.update(t.getId(), data(TransactionKind.EXPENSE, bank.getId(), null, "1000"));   // 100 left + its own 900
        org.junit.jupiter.api.Assertions.assertThrows(com.learning.spring.financialdashboard.exception.ValidationException.class,
                () -> service.update(t.getId(), data(TransactionKind.EXPENSE, bank.getId(), null, "1001")));
    }

    @Test
    void spendingOnACardIsNotLimitedByItsBalance() {
        service.create(data(TransactionKind.EXPENSE, card.getId(), null, "5000"));
    }
}
