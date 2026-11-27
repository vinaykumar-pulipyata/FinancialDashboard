package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.SplitSettlementRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerData;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerView;
import com.learning.spring.financialdashboard.service.SplitService.BalanceRequest;
import com.learning.spring.financialdashboard.service.SplitService.RepayRequest;
import com.learning.spring.financialdashboard.service.SplitService.SettleRequest;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expenses split with people: what they owe, how it shows under Receivables, and how a payment settles specific expenses. */
@SpringBootTest
class SplitServiceTest {

    @Autowired LoanService loans;
    @Autowired SplitService splits;
    @Autowired SystemCategories systemCategories;
    @Autowired AccountService accountService;
    @Autowired TransactionService transactionService;
    @Autowired BorrowerRepository borrowerRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired SplitSettlementRepository settlementRepository;

    Account bank;
    Borrower teja;
    Borrower ravi;
    Long collected;

    @BeforeEach
    void setUp() {
        wipe();
        systemCategories.ensureAll();
        collected = systemCategories.require(SystemCategories.LOANS_COLLECTED).getId();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("100000"), null, null));
        teja = loans.create(new BorrowerData("Teja", null));
        ravi = loans.create(new BorrowerData("Ravi", null));
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        settlementRepository.deleteAll();
        transactionRepository.deleteAll();
        borrowerRepository.deleteAll();
        categoryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private Transaction expense(String date, String amount, Map<Long, BigDecimal> shares) {
        return transactionService.create(new TransactionData(LocalDate.parse(date), TransactionKind.EXPENSE, bank.getId(), null,
                n(amount), null, null, "Dinner", null, null, null, null, shares));
    }

    private BorrowerView view(String name) {
        return loans.summary().borrowers().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    private BigDecimal receivables() {
        return accountService.listSummaries().stream().filter(s -> s.type() == AccountType.RECEIVABLE)
                .map(AccountSummary::balance).findFirst().orElseThrow();
    }

    private BigDecimal bankBalance() {
        return accountService.listSummaries().stream().filter(s -> s.id().equals(bank.getId())).map(AccountSummary::balance).findFirst().orElseThrow();
    }

    @Test
    void aSplitExpenseKeepsItsFullAmountAndTheSharesAreOwedUnderReceivables() {
        Transaction t = expense("2026-11-01", "1000", Map.of(teja.getId(), n("250"), ravi.getId(), n("250")));

        assertAmount("1000", t.getAmount());
        assertAmount("250", t.getSplits().get(teja.getId()));
        assertAmount("99000", bankBalance());                  // the whole bill left the account
        assertAmount("250", view("Teja").owed());
        assertAmount("250", view("Teja").shared());
        assertAmount("500", receivables());                    // both shares are owed to you
    }

    @Test
    void anExpenseThatIsNotSplitIsAllYours() {
        Transaction t = expense("2026-11-01", "1000", null);
        assertTrue(t.getSplits().isEmpty());
        assertAmount("0", view("Teja").owed());
    }

    @Test
    void sharesMustBePeopleOnTheListPositiveAndNotMoreThanTheExpense() {
        assertThrows(ValidationException.class, () -> expense("2026-11-01", "1000", Map.of(9999L, n("100"))));
        assertThrows(ValidationException.class, () -> expense("2026-11-01", "1000", Map.of(teja.getId(), n("0"))));
        assertThrows(ValidationException.class, () -> expense("2026-11-01", "1000", Map.of(teja.getId(), n("600"), ravi.getId(), n("500"))));
        expense("2026-11-01", "1000", Map.of(teja.getId(), n("1000")));   // everything for someone else is allowed
    }

    @Test
    void onlyAnOrdinaryExpenseCanBeSplit() {
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"),
                TransactionKind.INCOME, null, bank.getId(), n("100"), null, null, null, null, null, null, null, Map.of(teja.getId(), n("10")))));
    }

    @Test
    void aPaymentSettlesTheOldestSharedExpensesFirstAndKeepsTheLinks() {
        Transaction first = expense("2026-11-01", "1000", Map.of(teja.getId(), n("500")));
        Transaction second = expense("2026-11-05", "600", Map.of(teja.getId(), n("300")));

        Transaction payment = splits.settle(teja.getId(), new SettleRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("600"), null, null));

        assertEquals(TransactionKind.INCOME, payment.getKind());
        assertEquals(teja.getId(), payment.getBorrowerId());
        assertAmount("200", view("Teja").owed());                       // 800 shared less 600 paid
        assertAmount("200", receivables());
        var all = splits.list();
        assertEquals(2, all.size());
        assertAmount("500", all.stream().filter(a -> a.expenseTransactionId().equals(first.getId())).findFirst().orElseThrow().amount());
        assertAmount("100", all.stream().filter(a -> a.expenseTransactionId().equals(second.getId())).findFirst().orElseThrow().amount());
        assertTrue(all.stream().allMatch(a -> a.incomeTransactionId().equals(payment.getId())));
    }

    @Test
    void aPaymentCanBeAimedAtChosenExpensesAndCannotExceedWhatIsOpen() {
        Transaction first = expense("2026-11-01", "1000", Map.of(teja.getId(), n("500")));
        Transaction second = expense("2026-11-05", "600", Map.of(teja.getId(), n("300")));

        assertThrows(ValidationException.class, () ->
                splits.settle(teja.getId(), new SettleRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("400"), List.of(second.getId()), null)));
        splits.settle(teja.getId(), new SettleRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("300"), List.of(second.getId()), null));
        assertEquals(1, splits.list().size());
        assertEquals(second.getId(), splits.list().get(0).expenseTransactionId());
        assertThrows(ValidationException.class, () ->
                splits.settle(teja.getId(), new SettleRequest(LocalDate.parse("2026-11-11"), bank.getId(), n("501"), null, null)));
        assertAmount("500", view("Teja").owed());
        assertTrue(first.getId() != null);
    }

    @Test
    void aSettledExpenseCannotBeChangedOrDeletedUntilThePaymentIsDeleted() {
        Transaction t = expense("2026-11-01", "1000", Map.of(teja.getId(), n("500")));
        Transaction payment = splits.settle(teja.getId(), new SettleRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("200"), null, null));

        assertThrows(ValidationException.class, () -> transactionService.delete(t.getId()));
        assertThrows(ValidationException.class, () -> transactionService.update(t.getId(), new TransactionData(t.getDate(),
                TransactionKind.EXPENSE, bank.getId(), null, n("1000"), null, null, "Dinner", null, null, null, null, Map.of(teja.getId(), n("400")))));
        assertThrows(ValidationException.class, () -> transactionService.update(payment.getId(), new TransactionData(payment.getDate(),
                TransactionKind.INCOME, null, bank.getId(), n("100"), null, collected, null, null, teja.getId(), null, null, null)));
        // the same amount and shares, with a new note, is fine
        transactionService.update(t.getId(), new TransactionData(t.getDate(), TransactionKind.EXPENSE, bank.getId(), null, n("1000"), null, null,
                "Dinner at Mandi", null, null, null, null, Map.of(teja.getId(), n("500"))));

        transactionService.delete(payment.getId());                      // the payment goes: the share is open again
        assertTrue(splits.list().isEmpty());
        assertAmount("500", view("Teja").owed());
        transactionService.delete(t.getId());
        assertAmount("0", receivables());
    }

    @Test
    void aPersonWithSharedExpensesCannotBeRemoved() {
        expense("2026-11-01", "1000", Map.of(teja.getId(), n("500")));
        assertThrows(ValidationException.class, () -> loans.delete(teja.getId()));
        assertFalse(borrowerRepository.findById(teja.getId()).isEmpty());
    }

    // ----------------------------------------------------------------------------- expenses somebody else paid for

    private Transaction paidBy(Borrower person, String date, String amount, String myShare) {
        return transactionService.create(new TransactionData(LocalDate.parse(date), TransactionKind.EXPENSE, null, null, n(amount), null, null,
                "Dinner", null, null, null, null, null, person.getId(), n(myShare)));
    }

    @Test
    void anExpenseSomebodyElsePaidMovesNoMoneyAndIsOwedToThemSeparately() {
        Transaction t = paidBy(ravi, "2026-11-01", "1200", "400");

        assertAmount("1200", t.getAmount());
        assertAmount("400", t.getOwnShare());
        assertEquals(ravi.getId(), t.getPaidByBorrowerId());
        assertAmount("100000", bankBalance());                 // nothing left your account
        assertAmount("400", view("Ravi").youOwe());
        assertAmount("0", view("Ravi").owed());                // what they owe you is untouched
        assertAmount("-400", receivables());                   // Receivables is the balance: you owe him, so it is negative
    }

    @Test
    void paidByNeedsAPersonAYourShareWithinTheBillAndNoAccountOrSplit() {
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"), TransactionKind.EXPENSE,
                null, null, n("100"), null, null, null, null, null, null, null, null, ravi.getId(), null)));
        assertThrows(ValidationException.class, () -> paidBy(ravi, "2026-11-01", "100", "101"));
        assertThrows(ValidationException.class, () -> paidBy(ravi, "2026-11-01", "100", "0"));
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"), TransactionKind.EXPENSE,
                bank.getId(), null, n("100"), null, null, null, null, null, null, null, null, ravi.getId(), n("50"))));
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"), TransactionKind.EXPENSE,
                null, null, n("100"), null, null, null, null, null, null, null, Map.of(teja.getId(), n("10")), ravi.getId(), n("50"))));
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"), TransactionKind.EXPENSE,
                null, null, n("100"), null, null, null, null, null, null, null, null, 9999L, n("50"))));
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"), TransactionKind.EXPENSE,
                bank.getId(), null, n("100"), null, null, null, null, null, null, null, null, null, n("50"))));   // a share without a payer
    }

    @Test
    void payingThemBackClearsTheOldestExpensesFirstAndIsNotLending() {
        Transaction first = paidBy(ravi, "2026-11-01", "1200", "400");
        Transaction second = paidBy(ravi, "2026-11-05", "600", "300");

        Transaction payment = splits.repay(ravi.getId(), new RepayRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("500"), null, null));

        assertEquals(TransactionKind.EXPENSE, payment.getKind());
        assertAmount("99500", bankBalance());                  // the money left your bank
        assertAmount("200", view("Ravi").youOwe());            // 700 owed less 500 paid
        assertAmount("0", view("Ravi").owed());                // paying back is not lending to them
        assertAmount("0", view("Ravi").given());
        assertAmount("-200", receivables());                   // 700 owed less 500 paid
        var all = splits.list();
        assertAmount("400", all.stream().filter(a -> a.expenseTransactionId().equals(first.getId())).findFirst().orElseThrow().amount());
        assertAmount("100", all.stream().filter(a -> a.expenseTransactionId().equals(second.getId())).findFirst().orElseThrow().amount());

        assertThrows(ValidationException.class, () ->
                splits.repay(ravi.getId(), new RepayRequest(LocalDate.parse("2026-11-11"), bank.getId(), n("201"), null, null)));
        splits.repay(ravi.getId(), new RepayRequest(LocalDate.parse("2026-11-11"), bank.getId(), n("200"), null, null));
        assertAmount("0", view("Ravi").youOwe());
    }

    @Test
    void aRepaidExpenseIsLockedUntilThePaymentIsDeleted() {
        Transaction t = paidBy(ravi, "2026-11-01", "1200", "400");
        Transaction payment = splits.repay(ravi.getId(), new RepayRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("100"), null, null));

        assertThrows(ValidationException.class, () -> transactionService.delete(t.getId()));
        assertThrows(ValidationException.class, () -> transactionService.update(t.getId(), new TransactionData(t.getDate(), TransactionKind.EXPENSE,
                null, null, n("1200"), null, null, "Dinner", null, null, null, null, null, ravi.getId(), n("300"))));
        assertThrows(ValidationException.class, () -> loans.delete(ravi.getId()));
        transactionService.delete(payment.getId());
        assertAmount("400", view("Ravi").youOwe());
        assertAmount("100000", bankBalance());
        transactionService.delete(t.getId());
        assertAmount("0", view("Ravi").youOwe());
    }

    @Test
    void whatYouOweAndWhatTheyOweYouAreBothInTheReceivablesBalance() {
        expense("2026-11-01", "1000", Map.of(ravi.getId(), n("300")));      // Ravi owes you 300
        paidBy(ravi, "2026-11-02", "800", "500");                           // you owe Ravi 500
        assertAmount("300", view("Ravi").owed());
        assertAmount("500", view("Ravi").youOwe());
        assertAmount("-200", receivables());                  // 300 owed to you less 500 you owe
    }

    // ----------------------------------------------------------------------------- settling the balance

    @Test
    void settlingTheBalanceSetsOffWhatYouOweEachOtherThenTheyPayTheRest() {
        expense("2026-11-01", "1000", Map.of(teja.getId(), n("500")));      // Teja owes you 500
        paidBy(teja, "2026-11-02", "400", "200");                           // you owe Teja 200

        var result = splits.settleBalance(teja.getId(), new BalanceRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("100"), null));

        assertAmount("200", result.offset());
        assertEquals(TransactionKind.INCOME, result.payment().getKind());
        assertAmount("99100", bankBalance());                   // 1,000 for the expense, 100 came back
        assertAmount("0", view("Teja").youOwe());               // the bill was cancelled against his share
        assertAmount("200", view("Teja").owed());               // 500 - 200 set off - 100 paid
        assertAmount("200", receivables());

        splits.settleBalance(teja.getId(), new BalanceRequest(LocalDate.parse("2026-11-11"), bank.getId(), n("200"), null));
        assertAmount("0", view("Teja").owed());
        assertAmount("0", receivables());
    }

    @Test
    void whenYouOweMoreYouPayTheDifferenceAndItIsNotLending() {
        expense("2026-11-01", "400", Map.of(teja.getId(), n("200")));       // Teja owes you 200
        paidBy(teja, "2026-11-02", "1000", "500");                          // you owe Teja 500

        var result = splits.settleBalance(teja.getId(), new BalanceRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("300"), null));

        assertAmount("200", result.offset());
        assertEquals(TransactionKind.EXPENSE, result.payment().getKind());
        assertAmount("99300", bankBalance());                   // 400 for the expense, then 300 paid to Teja
        assertAmount("0", view("Teja").youOwe());               // 500 - 200 set off - 300 paid
        assertAmount("0", view("Teja").owed());                 // paying him back is not lending
        assertAmount("0", view("Teja").given());
        assertAmount("0", receivables());
    }

    @Test
    void theAmountCannotBeMoreThanTheBalanceAndAnOffsetAloneCanSettleEverything() {
        expense("2026-11-01", "800", Map.of(teja.getId(), n("300")));
        paidBy(teja, "2026-11-02", "600", "300");

        assertThrows(ValidationException.class, () ->
                splits.settleBalance(teja.getId(), new BalanceRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("1"), null)));   // nothing is left to pay
        var result = splits.settleBalance(teja.getId(), new BalanceRequest(null, null, null, null));
        assertAmount("300", result.offset());
        assertEquals(null, result.payment());
        assertAmount("0", view("Teja").owed());
        assertAmount("0", view("Teja").youOwe());
        assertAmount("0", receivables());
        assertThrows(ValidationException.class, () -> splits.settleBalance(teja.getId(), new BalanceRequest(null, null, null, null)));
    }

    @Test
    void aPersonWhoOwesYouALoanPaysYouEvenWhenYouOweHimABill() {
        transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"), TransactionKind.EXPENSE, bank.getId(), null, n("10000"), null,
                systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), "Loan", null, teja.getId(), null));
        paidBy(teja, "2026-11-02", "600", "300");               // you owe Teja 300, he owes you 10,000 of loan

        assertAmount("10000", view("Teja").owed());
        assertAmount("9700", receivables());                    // the balance: 10,000 - 300

        var result = splits.settleBalance(teja.getId(), new BalanceRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("9700"), null));

        assertAmount("300", result.offset());                   // the bill is set off against the loan
        assertEquals(TransactionKind.INCOME, result.payment().getKind());   // and he pays you, you do not pay him
        assertAmount("0", view("Teja").owed());
        assertAmount("0", view("Teja").youOwe());
        assertAmount("0", receivables());
        assertThrows(ValidationException.class, () -> splits.settleBalance(teja.getId(), new BalanceRequest(null, null, null, null)));
    }

    @Test
    void thePaymentCannotBeMoreThanTheBalanceIncludingLoans() {
        transactionService.create(new TransactionData(LocalDate.parse("2026-11-01"), TransactionKind.EXPENSE, bank.getId(), null, n("1000"), null,
                systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), "Loan", null, teja.getId(), null));
        assertThrows(ValidationException.class, () ->
                splits.settleBalance(teja.getId(), new BalanceRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("1001"), null)));
        splits.settleBalance(teja.getId(), new BalanceRequest(LocalDate.parse("2026-11-10"), bank.getId(), n("1000"), null));
        assertAmount("0", view("Teja").owed());
    }
}
