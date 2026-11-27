package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reset balance brings an active account to zero with one Balance Adjustment transaction that carries a note saying what happened to
 * the money; a deactivated account allows nothing until it is activated again.
 */
@SpringBootTest
class BalanceResetTest {

    @Autowired
    AccountService accounts;
    @Autowired
    TransactionService transactions;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    SystemCategories systemCategories;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private Account account(String name, AccountType type, String opening) {
        return accounts.create(new AccountData(name, type, null, null, new BigDecimal(opening), null, null));
    }

    private BigDecimal balance(Account a) {
        return accounts.listSummaries().stream().filter(s -> s.id().equals(a.getId())).map(AccountSummary::balance).findFirst().orElseThrow();
    }

    private TransactionData income(Account to, String amount) {
        return new TransactionData(LocalDate.of(2026, 9, 1), TransactionKind.INCOME, null, to.getId(), new BigDecimal(amount), null, null, "pay", null, null);
    }

    private TransactionData spend(Account from, String amount) {
        return new TransactionData(LocalDate.of(2026, 9, 2), TransactionKind.EXPENSE, from.getId(), null, new BigDecimal(amount), null, null, "shop", null, null);
    }

    @Test
    void anAccountWithMoneyInItLosesItAsABalanceAdjustmentExpenseWithTheNote() {
        Account bank = account("Old bank", AccountType.BANK, "1000");
        transactions.create(income(bank, "500"));

        Transaction t = accounts.resetBalance(bank.getId(), "  Withdrew it all in cash and closed the account  ");

        assertEquals(0, BigDecimal.ZERO.compareTo(balance(bank)));
        assertEquals(TransactionKind.EXPENSE, t.getKind());
        assertEquals(bank.getId(), t.getFromAccountId());
        assertNull(t.getToAccountId());
        assertEquals(0, new BigDecimal("1500").compareTo(t.getAmount()));
        assertEquals("Withdrew it all in cash and closed the account", t.getNote());
        assertEquals(LocalDate.now(), t.getDate());
        assertTrue(systemCategories.isBalanceAdjustment(t.getCategoryId()));
        assertEquals("Balance Adjustment", categoryRepository.findById(t.getCategoryId()).orElseThrow().getName());
    }

    @Test
    void anOverdrawnAccountGainsItAsAnIncomeAndAnAlreadyZeroBalanceIsRefused() {
        Account wallet = account("Wallet", AccountType.WALLET, "100");
        transactions.create(spend(wallet, "40"));
        accounts.update(wallet.getId(), new AccountData("Wallet", AccountType.WALLET, null, null, BigDecimal.ZERO, null, null));   // the opening was wrong
        assertEquals(0, new BigDecimal("-40").compareTo(balance(wallet)));

        Transaction t = accounts.resetBalance(wallet.getId(), "Cash was found in a coat");

        assertEquals(TransactionKind.INCOME, t.getKind());
        assertEquals(wallet.getId(), t.getToAccountId());
        assertEquals(0, new BigDecimal("40").compareTo(t.getAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(wallet)));
        assertTrue(systemCategories.isBalanceAdjustment(t.getCategoryId()));

        assertThrows(ValidationException.class, () -> accounts.resetBalance(wallet.getId(), "again"));   // it is already zero
    }

    @Test
    void aLoanOrCardOwingMoneyGetsAnIncomeAndAnOverpaidOneAnExpense() {
        Account loan = account("Friend loan", AccountType.DEBT, "700");
        Transaction t = accounts.resetBalance(loan.getId(), "He waived it");
        assertEquals(TransactionKind.INCOME, t.getKind());   // money into a liability lowers what is owed
        assertEquals(loan.getId(), t.getToAccountId());
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(loan)));

        Account card = account("Card", AccountType.CREDIT_CARD, "0");
        transactions.create(new TransactionData(LocalDate.of(2026, 9, 3), TransactionKind.INCOME, null, card.getId(), new BigDecimal("25"), null, null, "refund", null, null));
        assertEquals(0, new BigDecimal("-25").compareTo(balance(card)));   // overpaid: the bank owes the card holder
        Transaction back = accounts.resetBalance(card.getId(), "Refund paid out by the bank");
        assertEquals(TransactionKind.EXPENSE, back.getKind());
        assertEquals(card.getId(), back.getFromAccountId());
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(card)));
    }

    @Test
    void theNoteIsRequiredAndOnlyAnActiveOrdinaryAccountCanBeReset() {
        Account bank = account("Bank", AccountType.BANK, "100");
        assertThrows(ValidationException.class, () -> accounts.resetBalance(bank.getId(), null));
        assertThrows(ValidationException.class, () -> accounts.resetBalance(bank.getId(), "   "));
        assertThrows(ValidationException.class, () -> accounts.resetBalance(bank.getId(), "x".repeat(256)));
        assertEquals(0, new BigDecimal("100").compareTo(balance(bank)), "nothing changed");

        accounts.deactivate(bank.getId());
        assertThrows(ValidationException.class, () -> accounts.resetBalance(bank.getId(), "closing"));   // only an active account
        accounts.activate(bank.getId());
        accounts.resetBalance(bank.getId(), "closing");

        Account jewellery = accounts.create(new AccountData("Jewellery", AccountType.JEWELLERY, null, null, new BigDecimal("50"), null, null));
        assertThrows(ValidationException.class, () -> accounts.resetBalance(jewellery.getId(), "x"));   // its own buttons keep its balance
    }

    @Test
    void aDeactivatedAccountAllowsNothingUntilItIsActivatedAgain() {
        Account bank = account("Bank", AccountType.BANK, "100");
        Account other = account("Other", AccountType.BANK, "0");
        Transaction saved = transactions.create(income(bank, "10"));

        accounts.deactivate(bank.getId());

        assertThrows(ValidationException.class, () -> transactions.create(income(bank, "5")));
        assertThrows(ValidationException.class, () -> transactions.create(new TransactionData(LocalDate.of(2026, 9, 5), TransactionKind.TRANSFER,
                other.getId(), bank.getId(), new BigDecimal("1"), null, null, "t", null, null)));
        assertThrows(ValidationException.class, () -> transactions.update(saved.getId(), income(bank, "11")));
        assertThrows(ValidationException.class, () -> transactions.delete(saved.getId()));
        assertThrows(ValidationException.class, () -> accounts.update(bank.getId(), new AccountData("Renamed", AccountType.BANK, null, null, new BigDecimal("100"), null, null)));

        accounts.activate(bank.getId());
        assertEquals(0, new BigDecimal("11").compareTo(transactions.update(saved.getId(), income(bank, "11")).getAmount()));
        assertEquals("Renamed", accounts.update(bank.getId(), new AccountData("Renamed", AccountType.BANK, null, null, new BigDecimal("100"), null, null)).getName());
    }

    @Test
    void anOrdinaryTransactionCannotUseTheBalanceAdjustmentCategory() {
        Account bank = account("Bank", AccountType.BANK, "100");
        Long adjustment = systemCategories.require(SystemCategories.BALANCE_ADJUSTMENT_EXPENSE).getId();

        assertThrows(ValidationException.class, () -> transactions.create(new TransactionData(LocalDate.of(2026, 9, 5), TransactionKind.EXPENSE,
                bank.getId(), null, new BigDecimal("5"), null, adjustment, "x", null, null)));
    }
}
