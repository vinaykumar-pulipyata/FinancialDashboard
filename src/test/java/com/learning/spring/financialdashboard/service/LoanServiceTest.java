package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerData;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerView;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class LoanServiceTest {

    @Autowired
    LoanService loans;

    @Autowired
    SystemCategories systemCategories;

    @Autowired
    CategoryService categoryService;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    BorrowerRepository borrowerRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    RingFenceRepository ringFenceRepository;

    @Autowired
    com.learning.spring.financialdashboard.repository.LoanWriteOffRepository writeOffRepository;

    @Autowired
    EmergencyFundDetailRepository emergencyDetails;

    Account bank;
    Long given;
    Long collected;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        writeOffRepository.deleteAll();
        borrowerRepository.deleteAll();
        ringFenceRepository.deleteAll();
        emergencyDetails.deleteAll();
        categoryRepository.deleteAll();
        accountRepository.deleteAll();
        systemCategories.ensureAll();
        given = systemCategories.require(SystemCategories.LOANS_GIVEN).getId();
        collected = systemCategories.require(SystemCategories.LOANS_COLLECTED).getId();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("100000"), null, null));
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private Borrower person(String name, String opening) {
        return loans.create(new BorrowerData(name, opening == null ? null : n(opening)));
    }

    private void lend(Long personId, String amount) {
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, bank.getId(), null,
                n(amount), null, given, null, null, personId));
    }

    private void collect(Long personId, String amount) {
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.INCOME, null, bank.getId(),
                n(amount), null, collected, null, null, personId));
    }

    private BigDecimal balanceOf(AccountType type) {
        return accountService.listSummaries().stream().filter(s -> s.type() == type)
                .map(AccountSummary::balance).findFirst().orElseThrow();
    }

    private BorrowerView view(String name) {
        return loans.summary().borrowers().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aWriteOffsDayAndReasonCanBeCorrectedAndItCanBeTakenBack() {
        Borrower ravi = person("Ravi", null);
        lend(ravi.getId(), "5000");
        Long id = loans.writeOff(ravi.getId(), "Forgiven").getId();
        assertAmount("0", view("Ravi").owed());

        var edited = loans.updateWriteOff(id, LocalDate.now().minusDays(3), "  Lost his job  ");

        assertEquals(LocalDate.now().minusDays(3), edited.getWrittenOn());
        assertEquals("Lost his job", edited.getNote());
        assertAmount("5000", edited.getAmount());                 // the amount never changes
        assertThrows(ValidationException.class, () -> loans.updateWriteOff(id, LocalDate.now().plusDays(1), "Later"));
        assertThrows(ValidationException.class, () -> loans.updateWriteOff(id, LocalDate.now(), "  "));
        assertThrows(ValidationException.class, () -> loans.updateWriteOff(id, LocalDate.now(), "x".repeat(256)));
        assertThrows(com.learning.spring.financialdashboard.exception.NotFoundException.class, () -> loans.updateWriteOff(9999L, LocalDate.now(), "No"));

        loans.deleteWriteOff(id);

        assertAmount("5000", view("Ravi").owed());                // owed again
        assertAmount("5000", balanceOf(AccountType.RECEIVABLE));
        assertTrue(loans.writeOffList().isEmpty());
        assertThrows(com.learning.spring.financialdashboard.exception.NotFoundException.class, () -> loans.deleteWriteOff(id));
    }

    @Test
    void everyWriteOffIsListedWithItsDayAmountAndReason() {
        Borrower ravi = person("Ravi", null);
        lend(ravi.getId(), "5000");
        loans.writeOff(ravi.getId(), "Forgiven");

        var list = loans.writeOffList();

        assertEquals(1, list.size());
        assertEquals(ravi.getId(), list.get(0).getBorrowerId());
        assertAmount("5000", list.get(0).getAmount());
        assertEquals("Forgiven", list.get(0).getNote());
        assertEquals(LocalDate.now(), list.get(0).getWrittenOn());
    }

    @Test
    void whatAPersonOwesCanBeLetGoOfAndTheReceivablesTotalFollows() {
        Borrower ravi = person("Ravi", null);
        lend(ravi.getId(), "5000");
        collect(ravi.getId(), "500");
        assertAmount("4500", view("Ravi").owed());
        assertAmount("4500", balanceOf(AccountType.RECEIVABLE));

        var saved = loans.writeOff(ravi.getId(), "  He lost his job, forgiven  ");

        assertAmount("4500", saved.getAmount());
        assertEquals("He lost his job, forgiven", saved.getNote());
        assertEquals(LocalDate.now(), saved.getWrittenOn());
        assertAmount("0", view("Ravi").owed());
        assertAmount("4500", view("Ravi").writtenOff());
        assertAmount("0", balanceOf(AccountType.RECEIVABLE));
        assertAmount("95500", accountService.listSummaries().stream().filter(a -> a.id().equals(bank.getId())).findFirst().orElseThrow().balance());   // no bank account is touched

        assertThrows(ValidationException.class, () -> loans.writeOff(ravi.getId(), "again"));   // nothing is owed any more
        assertThrows(ValidationException.class, () -> loans.delete(ravi.getId()));              // the history keeps the person
    }

    @Test
    void aWriteOffNeedsANoteAndSomethingToLetGo() {
        Borrower meera = person("Meera", null);
        lend(meera.getId(), "200");
        assertThrows(ValidationException.class, () -> loans.writeOff(meera.getId(), null));
        assertThrows(ValidationException.class, () -> loans.writeOff(meera.getId(), "   "));
        assertThrows(ValidationException.class, () -> loans.writeOff(meera.getId(), "x".repeat(256)));
        assertAmount("200", view("Meera").owed());

        Borrower paid = person("Paid up", null);
        lend(paid.getId(), "100");
        collect(paid.getId(), "100");   // paid in full: nothing is left to let go
        assertThrows(ValidationException.class, () -> loans.writeOff(paid.getId(), "nothing owed"));
    }

    @Test
    void whatYouOweAPersonWhoPaidBackTooMuchCanBeLetGoOfToo() {
        Borrower sam = person("Sam", null);
        lend(sam.getId(), "100");
        collect(sam.getId(), "100.50");   // 0.50 more than they owed: you owe them
        assertAmount("-0.5", view("Sam").owed());
        assertAmount("-0.5", balanceOf(AccountType.RECEIVABLE));

        var saved = loans.writeOff(sam.getId(), "rounding");

        assertAmount("-0.5", saved.getAmount());
        assertAmount("0", view("Sam").owed());
        assertAmount("0", balanceOf(AccountType.RECEIVABLE));
    }

    @Test
    void theFixedCategoriesExistAndStayUniqueWhenEnsuredAgain() {
        systemCategories.ensureAll();
        systemCategories.ensureAll();

        Category lent = categoryRepository.findBySystemKey(SystemCategories.LOANS_GIVEN).orElseThrow();
        Category back = categoryRepository.findBySystemKey(SystemCategories.LOANS_COLLECTED).orElseThrow();
        assertEquals("Loans Given", lent.getName());
        assertEquals(CategoryKind.EXPENSE, lent.getKind());
        assertEquals("Loans Collected", back.getName());
        assertEquals(CategoryKind.INCOME, back.getKind());
        Category payments = categoryRepository.findBySystemKey(SystemCategories.LIABILITY_PAYMENT).orElseThrow();
        assertEquals("Liability Payments", payments.getName());
        assertEquals(CategoryKind.EXPENSE, payments.getKind());
        Category adjustmentOut = categoryRepository.findBySystemKey(SystemCategories.BALANCE_ADJUSTMENT_EXPENSE).orElseThrow();
        Category adjustmentIn = categoryRepository.findBySystemKey(SystemCategories.BALANCE_ADJUSTMENT_INCOME).orElseThrow();
        assertEquals("Balance Adjustment", adjustmentOut.getName());
        assertEquals(CategoryKind.EXPENSE, adjustmentOut.getKind());
        assertEquals("Balance Adjustment", adjustmentIn.getName());
        assertEquals(CategoryKind.INCOME, adjustmentIn.getKind());
        assertEquals(5, categoryRepository.count());   // the fixed ones: two loan categories, Liability Payments and the two Balance Adjustments
    }

    @Test
    void anOrdinaryCategoryWithTheSameNameIsTakenOver() {
        categoryRepository.deleteAll();
        Category mine = categoryService.create(new CategoryData("Loans Given", CategoryKind.EXPENSE, null));

        Category fixed = systemCategories.require(SystemCategories.LOANS_GIVEN);

        assertEquals(mine.getId(), fixed.getId());
        assertEquals(1, categoryRepository.count());
    }

    @Test
    void theLoanCategoriesCannotBeChanged() {
        assertThrows(ValidationException.class, () -> categoryService.update(given, "Lent", null));
        assertThrows(ValidationException.class, () -> categoryService.setActive(given, false));
        assertThrows(ValidationException.class, () -> categoryService.delete(collected));
        assertThrows(ValidationException.class,
                () -> categoryService.create(new CategoryData("Ravi", CategoryKind.EXPENSE, given)));
    }

    @Test
    void addingAPersonCreatesTheReceivablesAccountIfThereIsNone() {
        assertEquals(0, accountRepository.findAll().stream().filter(a -> a.getType() == AccountType.RECEIVABLE).count());

        person("Ravi", null);

        assertNotNull(loans.summary().receivablesAccountId());
        assertEquals(1, accountRepository.findAll().stream().filter(a -> a.getType() == AccountType.RECEIVABLE).count());
    }

    @Test
    void lendingAndCollectingMoveEachPersonAndTheReceivablesTotal() {
        Borrower ravi = person("Ravi", null);
        Borrower meera = person("Meera", null);

        lend(ravi.getId(), "5000");
        lend(meera.getId(), "2000");
        collect(ravi.getId(), "1500");

        assertAmount("3500", view("Ravi").owed());
        assertAmount("2000", view("Meera").owed());
        assertAmount("5500", balanceOf(AccountType.RECEIVABLE));      // the total shown on top
        assertAmount("94500", balanceOf(AccountType.BANK));           // 100,000 - 5,000 - 2,000 + 1,500
    }

    @Test
    void lendingDoesNotChangeNetWorth() {
        Borrower ravi = person("Ravi", null);
        BigDecimal before = balanceOf(AccountType.BANK).add(balanceOf(AccountType.RECEIVABLE));

        lend(ravi.getId(), "5000");

        assertAmount(before.toPlainString(), balanceOf(AccountType.BANK).add(balanceOf(AccountType.RECEIVABLE)));
        collect(ravi.getId(), "5000");
        assertAmount(before.toPlainString(), balanceOf(AccountType.BANK).add(balanceOf(AccountType.RECEIVABLE)));
        assertAmount("0", balanceOf(AccountType.RECEIVABLE));
    }

    @Test
    void aLoanTransactionNeedsAPersonAndOtherCategoriesTakeNone() {
        Borrower ravi = person("Ravi", null);
        Category food = categoryService.create(new CategoryData("Food", CategoryKind.EXPENSE, null));

        assertThrows(ValidationException.class, () -> lend(null, "100"));
        assertThrows(ValidationException.class, () -> lend(9999L, "100"));
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.now(),
                TransactionKind.EXPENSE, bank.getId(), null, n("100"), null, food.getId(), null, null, ravi.getId())));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void aLoanTransactionCreatesTheReceivablesAccountToo() {
        Borrower ravi = person("Ravi", null);
        accountRepository.findAll().stream().filter(a -> a.getType() == AccountType.RECEIVABLE)
                .forEach(a -> accountRepository.delete(a));

        lend(ravi.getId(), "1000");

        assertAmount("1000", balanceOf(AccountType.RECEIVABLE));
    }

    @Test
    void earlierLoansStayUnnamedUntilAssignedToPeople() {
        accountService.create(new AccountData("Receivables", AccountType.RECEIVABLE, null, null, n("25000"), null, null));
        assertAmount("25000", loans.summary().unnamed());

        person("Ravi", "10000");

        assertAmount("15000", loans.summary().unnamed());
        assertAmount("10000", view("Ravi").owed());
        assertAmount("25000", balanceOf(AccountType.RECEIVABLE));                      // the total did not change

        assertThrows(ValidationException.class, () -> person("Meera", "20000"));       // only 15,000 is left
        person("Meera", "15000");
        assertAmount("0", loans.summary().unnamed());
    }

    @Test
    void removingAPersonReturnsTheirOpeningAmountToTheUnnamedRow() {
        accountService.create(new AccountData("Receivables", AccountType.RECEIVABLE, null, null, n("25000"), null, null));
        Borrower ravi = person("Ravi", "10000");

        loans.delete(ravi.getId());

        assertAmount("25000", loans.summary().unnamed());
        assertTrue(loans.summary().borrowers().isEmpty());
    }

    @Test
    void aPersonWithLoanTransactionsCannotBeRemoved() {
        Borrower ravi = person("Ravi", null);
        lend(ravi.getId(), "100");

        assertThrows(ValidationException.class, () -> loans.delete(ravi.getId()));
        assertEquals(1, borrowerRepository.count());
    }

    @Test
    void paymentsBeyondWhatIsOwedAreAllowedAndShowNegative() {
        Borrower ravi = person("Ravi", null);
        lend(ravi.getId(), "1000");

        collect(ravi.getId(), "1500");

        assertAmount("-500", view("Ravi").owed());
        assertAmount("-500", balanceOf(AccountType.RECEIVABLE));
    }

    @Test
    void oneListOfPeopleServesBothSides() {
        Borrower ravi = person("Ravi", null);
        lend(ravi.getId(), "1000");
        collect(ravi.getId(), "200");

        loans.update(ravi.getId(), new BorrowerData("Ravi Kumar", null));

        assertEquals(1, loans.summary().borrowers().size());
        BorrowerView v = view("Ravi Kumar");
        assertAmount("1000", v.given());
        assertAmount("200", v.collected());
        assertAmount("800", v.owed());
    }

    @Test
    void namesAreUniqueAndAmountsCannotBeNegative() {
        person("Ravi", null);

        assertThrows(ValidationException.class, () -> person("ravi", null));
        assertThrows(ValidationException.class, () -> person(" ", null));
        assertThrows(ValidationException.class, () -> person("Meera", "-1"));
        assertFalse(borrowerRepository.existsByNameIgnoreCase("Meera"));
    }
}
