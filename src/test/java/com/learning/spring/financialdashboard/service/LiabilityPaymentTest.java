package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.DebtDetailRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.NetWorthService.Point;
import com.learning.spring.financialdashboard.service.SetupService.SetupAccount;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A payment towards a loan or credit card is an expense in the fixed Liability Payments category with the liability as
 * its destination. The existing balance rules then lower the amount owed, so nothing else needed to change.
 */
@SpringBootTest
class LiabilityPaymentTest {

    @Autowired
    TransactionService transactionService;
    @Autowired
    AccountService accountService;
    @Autowired
    DebtService debtService;
    @Autowired
    SetupService setupService;
    @Autowired
    NetWorthService netWorth;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;
    @Autowired
    BorrowerRepository borrowers;
    @Autowired
    DebtDetailRepository debts;
    @Autowired
    CreditCardDetailRepository cards;

    Long payments;
    Account bank;
    Account loan;

    @AfterEach
    void clean() {
        transactions.deleteAll();
        borrowers.deleteAll();
        debts.deleteAll();
        cards.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
    }

    @BeforeEach
    void setUp() {
        clean();
        payments = systemCategories.require(SystemCategories.LIABILITY_PAYMENT).getId();
        bank = account("Bank", AccountType.BANK, "100000");
        loan = account("Car loan", AccountType.DEBT, "500000");
    }

    private Account account(String name, AccountType type, String opening) {
        return accountService.create(new AccountData(name, type, null, null, new BigDecimal(opening), null, null));
    }

    private Transaction pay(Long from, Long to, String amount) {
        return transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, from, to,
                new BigDecimal(amount), null, payments, null, null, null));
    }

    private BigDecimal balance(Long id) {
        return accountService.listSummaries().stream().filter(a -> a.id().equals(id)).map(AccountSummary::balance).findFirst().orElseThrow();
    }

    @Test
    void aPaymentIsAnExpenseThatLowersWhatIsOwedAndTakesMoneyFromTheBank() {
        Transaction t = pay(bank.getId(), loan.getId(), "23072");

        assertEquals(TransactionKind.EXPENSE, t.getKind());
        assertEquals(payments, t.getCategoryId());
        assertEquals(0, new BigDecimal("76928").compareTo(balance(bank.getId())));
        assertEquals(0, new BigDecimal("476928").compareTo(balance(loan.getId())));
    }

    @Test
    void aCreditCardCanBePaidTheSameWay() {
        Account card = account("Visa", AccountType.CREDIT_CARD, "8000");

        pay(bank.getId(), card.getId(), "3000");

        assertEquals(0, new BigDecimal("5000").compareTo(balance(card.getId())));
    }

    @Test
    void netWorthDropsByTheMoneyPaidOnlyOnceItIsPaidOutAndOwedFallsWithIt() {
        pay(bank.getId(), loan.getId(), "20000");

        List<Point> history = netWorth.history(1);
        Point latest = history.get(history.size() - 1);
        assertEquals(0, new BigDecimal("80000").compareTo(latest.assets()));
        assertEquals(0, new BigDecimal("480000").compareTo(latest.liabilities()));
        assertEquals(0, new BigDecimal("-400000").compareTo(latest.netWorth()), "paying a debt out of the bank does not change net worth");
    }

    @Test
    void editingOrDeletingAPaymentMovesTheAmountOwedBack() {
        Transaction t = pay(bank.getId(), loan.getId(), "20000");

        transactionService.update(t.getId(), new TransactionData(t.getDate(), TransactionKind.EXPENSE, bank.getId(), loan.getId(),
                new BigDecimal("5000"), null, payments, null, null, null));
        assertEquals(0, new BigDecimal("495000").compareTo(balance(loan.getId())));

        transactionService.delete(t.getId());
        assertEquals(0, new BigDecimal("500000").compareTo(balance(loan.getId())));
        assertEquals(0, new BigDecimal("100000").compareTo(balance(bank.getId())));
    }

    @Test
    void aPaymentNeedsBothAccounts() {
        assertThrows(ValidationException.class, () -> pay(bank.getId(), null, "100"));
        assertThrows(ValidationException.class, () -> pay(null, loan.getId(), "100"));
    }

    @Test
    void aPaymentMustGoToALoanOrCardAndComeFromAnOrdinaryAccount() {
        Account cash = account("Cash", AccountType.CASH, "10");
        Account card = account("Visa", AccountType.CREDIT_CARD, "100");

        assertThrows(ValidationException.class, () -> pay(bank.getId(), cash.getId(), "5"));
        assertThrows(ValidationException.class, () -> pay(card.getId(), loan.getId(), "5"));
        assertThrows(ValidationException.class, () -> pay(bank.getId(), 9999L, "5"));
    }

    @Test
    void thePaymentCategoryIsNotForIncomeAndOtherExpensesStillTakeNoDestination() {
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.now(),
                TransactionKind.INCOME, null, bank.getId(), BigDecimal.TEN, null, payments, null, null, null)));
        Long food = categories.save(category("Food")).getId();
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.now(),
                TransactionKind.EXPENSE, bank.getId(), loan.getId(), BigDecimal.TEN, null, food, null, null, null)));
    }

    private Category category(String name) {
        Category c = new Category();
        c.setName(name);
        c.setKind(CategoryKind.EXPENSE);
        c.setActive(true);
        return c;
    }

    @Test
    void thePaymentCategoryIsFixedAndNeedsNoPerson() {
        var category = categories.findBySystemKey(SystemCategories.LIABILITY_PAYMENT).orElseThrow();
        assertEquals("Liability Payments", category.getName());
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.now(),
                TransactionKind.EXPENSE, bank.getId(), loan.getId(), BigDecimal.TEN, null, payments, null, null, 1L)),
                "no person can be named on a payment");
        assertFalse(systemCategories.isLoanCategory(payments));
        assertTrue(systemCategories.isLiabilityPayment(payments));
    }

    @Test
    void aLoanWithPaymentsCannotBeDeletedButAnEmptyOneTakesItsPlanWithIt() {
        Account fixed = account("Bike loan", AccountType.DEBT, "1000");
        debtService.update(fixed.getId(), new BigDecimal("100"));
        accountService.delete(fixed.getId());
        assertEquals(0, debts.count());

        pay(bank.getId(), loan.getId(), "10");
        assertThrows(ValidationException.class, () -> accountService.delete(loan.getId()));
    }

    @Test
    void aLoanIsFixedWithAnEmiOrCustomWithout() {
        debtService.update(loan.getId(), new BigDecimal("23072"));
        assertEquals(0, new BigDecimal("23072").compareTo(debts.findById(loan.getId()).orElseThrow().getEmiAmount()));

        debtService.update(loan.getId(), new BigDecimal("20000"));
        assertEquals(1, debts.count());
        assertEquals(0, new BigDecimal("20000").compareTo(debts.findById(loan.getId()).orElseThrow().getEmiAmount()));

        debtService.update(loan.getId(), null);
        assertEquals(0, debts.count());
        debtService.update(loan.getId(), null);   // already custom: nothing to do
    }

    @Test
    void onlyALoanCanHaveAnEmiAndItMustBePositive() {
        Account card = account("Visa", AccountType.CREDIT_CARD, "0");
        assertThrows(ValidationException.class, () -> debtService.update(bank.getId(), BigDecimal.TEN));
        assertThrows(ValidationException.class, () -> debtService.update(card.getId(), BigDecimal.TEN));
        assertThrows(ValidationException.class, () -> debtService.update(loan.getId(), BigDecimal.ZERO));
        assertThrows(ValidationException.class, () -> debtService.update(loan.getId(), new BigDecimal("-5")));
        assertThrows(NotFoundException.class, () -> debtService.update(9999L, BigDecimal.TEN));
    }

    @Test
    void setUpCreatesAFixedLoanWithItsEmiAndACustomOneWithout() {
        List<Account> made = setupService.createAll(List.of(
                new SetupAccount("Home loan", AccountType.DEBT, null, null, new BigDecimal("2000000"), null, null, null, null, null, new BigDecimal("18000")),
                new SetupAccount("Friend", AccountType.DEBT, null, null, new BigDecimal("5000"), null, null, null, null, null, null)));

        assertEquals(0, new BigDecimal("18000").compareTo(debts.findById(made.get(0).getId()).orElseThrow().getEmiAmount()));
        assertFalse(debts.existsById(made.get(1).getId()));
    }

    @Test
    void setUpRefusesAFixedLoanWithAZeroEmi() {
        assertThrows(ValidationException.class, () -> setupService.createAll(List.of(
                new SetupAccount("Home loan", AccountType.DEBT, null, null, BigDecimal.TEN, null, null, null, null, null, BigDecimal.ZERO))));
    }
}
