package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every rule that refuses a transaction, plus how blank and zero values are stored. */
@SpringBootTest
class TransactionValidationTest {

    @Autowired
    TransactionService service;
    @Autowired
    AccountService accountService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    RingFenceService ringFenceService;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;
    @Autowired
    RingFenceRepository ringFences;

    Account bank;
    Account wallet;

    private void wipe() {
        transactions.deleteAll();
        ringFences.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    @BeforeEach
    void setUp() {
        wipe();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, new BigDecimal("1000"), null, null));
        wallet = accountService.create(new AccountData("Wallet", AccountType.CASH, null, null, BigDecimal.ZERO, null, null));
    }

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    private TransactionData data(LocalDate date, TransactionKind kind, Long from, Long to, String amount, String fee, Long category) {
        return new TransactionData(date, kind, from, to, amount == null ? null : new BigDecimal(amount),
                fee == null ? null : new BigDecimal(fee), category, null, null, null);
    }

    private void refused(TransactionData d) {
        assertThrows(ValidationException.class, () -> service.create(d));
    }

    @Test
    void theBasicFieldsAreRequired() {
        refused(data(DAY, null, null, bank.getId(), "10", null, null));
        refused(data(null, TransactionKind.INCOME, null, bank.getId(), "10", null, null));
        refused(data(DAY, TransactionKind.INCOME, null, bank.getId(), null, null, null));
        refused(data(DAY, TransactionKind.INCOME, null, bank.getId(), "0", null, null));
        refused(data(DAY, TransactionKind.INCOME, null, bank.getId(), "-5", null, null));
    }

    @Test
    void eachKindNeedsTheRightAccounts() {
        refused(data(DAY, TransactionKind.INCOME, bank.getId(), bank.getId(), "10", null, null));
        refused(data(DAY, TransactionKind.INCOME, null, null, "10", null, null));
        refused(data(DAY, TransactionKind.EXPENSE, null, null, "10", null, null));
        refused(data(DAY, TransactionKind.EXPENSE, bank.getId(), wallet.getId(), "10", null, null));
        refused(data(DAY, TransactionKind.TRANSFER, bank.getId(), null, "10", null, null));
        refused(data(DAY, TransactionKind.TRANSFER, null, bank.getId(), "10", null, null));
        refused(data(DAY, TransactionKind.TRANSFER, bank.getId(), bank.getId(), "10", null, null));
    }

    @Test
    void feesAreForTransfersOnlyAndNeverNegative() {
        refused(data(DAY, TransactionKind.TRANSFER, bank.getId(), wallet.getId(), "10", "-1", null));
        refused(data(DAY, TransactionKind.EXPENSE, bank.getId(), null, "10", "2", null));
        refused(data(DAY, TransactionKind.INCOME, null, bank.getId(), "10", "2", null));
        // A zero fee is allowed on anything, and is stored as no fee.
        Transaction t = service.create(data(DAY, TransactionKind.EXPENSE, bank.getId(), null, "10", "0", null));
        assertNull(t.getFee());
    }

    @Test
    void categoriesMustExistMatchTheKindAndBeActive() {
        Category income = categoryService.create(new CategoryData("Pay", CategoryKind.INCOME, null));
        Category expense = categoryService.create(new CategoryData("Food", CategoryKind.EXPENSE, null));
        refused(data(DAY, TransactionKind.TRANSFER, bank.getId(), wallet.getId(), "10", null, income.getId()));
        refused(data(DAY, TransactionKind.EXPENSE, bank.getId(), null, "10", null, 987654L));
        refused(data(DAY, TransactionKind.EXPENSE, bank.getId(), null, "10", null, income.getId()));
        refused(data(DAY, TransactionKind.INCOME, null, bank.getId(), "10", null, expense.getId()));
        categoryService.setActive(expense.getId(), false);
        refused(data(DAY, TransactionKind.EXPENSE, bank.getId(), null, "10", null, expense.getId()));
    }

    @Test
    void accountsMustExistAndBeActive() {
        accountService.deactivate(wallet.getId());
        refused(data(DAY, TransactionKind.INCOME, null, wallet.getId(), "10", null, null));
        refused(data(DAY, TransactionKind.INCOME, null, 987654L, "10", null, null));
    }

    @Test
    void textIsTrimmedAndBlankBecomesNothing() {
        Transaction t = service.create(new TransactionData(DAY, TransactionKind.EXPENSE, bank.getId(), null,
                new BigDecimal("10"), null, null, "   ", "  lunch  ", null));
        assertNull(t.getNote());
        assertEquals("lunch", t.getDescription());
    }

    @Test
    void aMissingTransactionIsNotFoundOnGetUpdateAndDelete() {
        assertThrows(NotFoundException.class, () -> service.get(987654L));
        assertThrows(NotFoundException.class, () -> service.update(987654L, data(DAY, TransactionKind.INCOME, null, bank.getId(), "1", null, null)));
        assertThrows(NotFoundException.class, () -> service.delete(987654L));
    }

    @Test
    void aRingFencedFundTakesMoneyInButEditsToOldSpendingCannotGrow() {
        Account fund = accountService.create(new AccountData("Holiday", AccountType.GOAL_FUND, null, null,
                new BigDecimal("500"), new BigDecimal("100"), null));
        Transaction spend = service.create(data(DAY, TransactionKind.EXPENSE, fund.getId(), null, "20", null, null));
        ringFenceService.fence(fund.getId());

        refused(data(DAY, TransactionKind.EXPENSE, fund.getId(), null, "5", null, null));            // new outflow
        service.create(data(DAY, TransactionKind.INCOME, null, fund.getId(), "5", null, null));      // money in is fine
        service.update(spend.getId(), data(DAY.plusDays(1), TransactionKind.EXPENSE, fund.getId(), null, "20", null, null));   // only the date
        service.update(spend.getId(), data(DAY, TransactionKind.EXPENSE, fund.getId(), null, "15", null, null));              // smaller
        assertThrows(ValidationException.class,
                () -> service.update(spend.getId(), data(DAY, TransactionKind.EXPENSE, fund.getId(), null, "30", null, null))); // larger
    }

    @Test
    void aNoteCanBeCorrectedAfterItsCategoryWasSwitchedOffButNothingCanBeDoneOnADeactivatedAccount() {
        Category food = categoryService.create(new CategoryData("Food", CategoryKind.EXPENSE, null));
        Category fuel = categoryService.create(new CategoryData("Fuel", CategoryKind.EXPENSE, null));
        service.create(data(DAY, TransactionKind.INCOME, null, wallet.getId(), "100", null, null));   // so the wallet can pay
        Transaction saved = service.create(data(DAY, TransactionKind.EXPENSE, wallet.getId(), null, "10", null, food.getId()));
        categoryService.setActive(food.getId(), false);

        Transaction edited = service.update(saved.getId(), new TransactionData(DAY, TransactionKind.EXPENSE, wallet.getId(), null,
                new BigDecimal("12"), null, food.getId(), "lunch", null, null));
        assertEquals("lunch", edited.getNote());
        assertEquals(0, new BigDecimal("12").compareTo(edited.getAmount()));

        // Only the category already on the transaction is excused: choosing a switched-off one now is still refused.
        categoryService.setActive(fuel.getId(), false);
        assertThrows(ValidationException.class, () -> service.update(saved.getId(), new TransactionData(DAY, TransactionKind.EXPENSE,
                wallet.getId(), null, new BigDecimal("12"), null, fuel.getId(), null, null, null)));

        // A deactivated account allows nothing: its transactions cannot be changed or deleted until it is activated again.
        accountService.deactivate(wallet.getId());
        assertThrows(ValidationException.class, () -> service.update(saved.getId(), new TransactionData(DAY, TransactionKind.EXPENSE,
                wallet.getId(), null, new BigDecimal("12"), null, food.getId(), "again", null, null)));
        assertThrows(ValidationException.class, () -> service.delete(saved.getId()));
        accountService.activate(wallet.getId());
        assertEquals("again", service.update(saved.getId(), new TransactionData(DAY, TransactionKind.EXPENSE, wallet.getId(), null,
                new BigDecimal("12"), null, food.getId(), "again", null, null)).getNote());
        accountService.deactivate(bank.getId());
        assertThrows(ValidationException.class, () -> service.update(saved.getId(), new TransactionData(DAY, TransactionKind.EXPENSE,
                bank.getId(), null, new BigDecimal("12"), null, food.getId(), null, null, null)));
    }
}
