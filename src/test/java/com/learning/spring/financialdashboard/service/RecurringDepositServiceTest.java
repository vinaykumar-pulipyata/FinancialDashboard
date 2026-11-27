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
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositDetailRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.RecurringDepositService.CloseRequest;
import com.learning.spring.financialdashboard.service.RecurringDepositService.DepositRequest;
import com.learning.spring.financialdashboard.service.RecurringDepositService.DepositView;
import com.learning.spring.financialdashboard.service.RecurringDepositService.EditData;
import com.learning.spring.financialdashboard.service.RecurringDepositService.OpenData;
import com.learning.spring.financialdashboard.service.RecurringDepositService.View;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Recurring Deposits account: opening a deposit, putting money in, the progress it shows, closing it and what is refused. */
@SpringBootTest
class RecurringDepositServiceTest {

    @Autowired
    RecurringDepositService service;
    @Autowired
    ExportService export;
    @Autowired
    AccountService accountService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    RecurringDepositRepository depositRepository;
    @Autowired
    RecurringDepositDetailRepository detailRepository;

    Account bank;
    Category interest;

    @BeforeEach
    void setUp() {
        wipe();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("100000"), null, null));
        interest = categoryService.create(new CategoryData("Bank Interest", CategoryKind.INCOME, null));
    }

    @AfterEach
    void wipe() {
        transactionRepository.deleteAll();
        depositRepository.deleteAll();
        detailRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountService.listSummaries().stream().filter(a -> a.id().equals(accountId)).findFirst().map(AccountSummary::balance).orElseThrow();
    }

    private DepositView open(String name, LocalDate start, int term, String monthly, String rate, String already) {
        service.setUp();
        View v = service.open(new OpenData(name, start, term, monthly == null ? null : n(monthly), rate == null ? null : n(rate), already == null ? null : n(already)));
        return v.open().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theAccountIsSetUpOnceAndStartsEmpty() {
        assertTrue(!service.get().configured());

        View first = service.setUp();
        View again = service.setUp();

        assertTrue(first.configured());
        assertEquals(first.accountId(), again.accountId());
        assertEquals("Recurring Deposits", first.name());
        assertEquals(1, accountRepository.findAll().stream().filter(a -> a.getName().equals("Recurring Deposits")).count());
        assertEquals(AccountType.INVESTMENT, accountService.get(first.accountId()).getType());
    }

    @Test
    void anAccountWithThatNameBlocksTheSetUp() {
        accountService.create(new AccountData("Recurring Deposits", AccountType.BANK, null, null, BigDecimal.ZERO, null, null));

        assertThrows(ValidationException.class, () -> service.setUp());
    }

    @Test
    void aDepositShowsItsTermProgressAndWhatItShouldPay() {
        LocalDate start = LocalDate.now().minusMonths(6);

        DepositView d = open("Bank RD", start, 12, "1000", "6", null);

        assertEquals("OPEN", d.status());
        assertEquals(start.plusMonths(12), d.maturesOn());
        assertEquals(6, d.monthsElapsed());
        assertTrue(d.progressPercent() >= 49 && d.progressPercent() <= 51, "about half way: " + d.progressPercent());
        assertAmount("12000", d.plannedTotal());
        assertAmount("0", d.deposited());
        assertTrue(d.expectedMaturity().compareTo(n("12000")) > 0 && d.expectedMaturity().compareTo(n("12500")) < 0,
                "twelve instalments of 1000 at 6% a year: " + d.expectedMaturity());
    }

    @Test
    void aFinishedTermShowsAFullBarAndANotYetStartedOneShowsNone() {
        DepositView old = open("Old", LocalDate.now().minusMonths(30), 24, null, null, null);
        DepositView later = open("Later", LocalDate.now().plusDays(10), 12, null, null, null);

        assertEquals(100, old.progressPercent());
        assertEquals(24, old.monthsElapsed());
        assertEquals(0, later.progressPercent());
        assertEquals(0, later.monthsElapsed());
        assertEquals(null, old.expectedMaturity(), "no rate and no instalment, so nothing to expect");
    }

    @Test
    void whatWasAlreadyInItIsPartOfTheOpeningBalance() {
        DepositView d = open("Started before", LocalDate.now().minusMonths(10), 24, "5000", null, "50000");
        Long account = service.get().accountId();

        assertAmount("50000", d.deposited());
        assertAmount("50000", accountService.get(account).getOpeningBalance());
        assertAmount("50000", balanceOf(account));
    }

    @Test
    void aDepositIsATransferOnItsDayAndRaisesTheAccount() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(2), 12, "1000", null, null);
        Long account = service.get().accountId();
        LocalDate first = LocalDate.now().minusDays(40);

        service.deposit(d.id(), new DepositRequest(bank.getId(), n("1000"), first));
        service.deposit(d.id(), new DepositRequest(bank.getId(), n("1000"), null));

        DepositView after = service.get().open().get(0);
        assertAmount("2000", after.deposited());
        assertEquals(2, after.instalments());
        assertAmount("2000", balanceOf(account));
        assertAmount("98000", balanceOf(bank.getId()));
        List<Transaction> made = transactionRepository.findAllByRecurringDepositId(d.id());
        assertEquals(2, made.size());
        assertTrue(made.stream().anyMatch(t -> t.getDate().equals(first)), "the transfer carries the day given");
        assertTrue(made.stream().allMatch(t -> t.getKind() == TransactionKind.TRANSFER && account.equals(t.getToAccountId())));
    }

    @Test
    void closingPaysBackEverythingDepositedAndFilesTheRestAsInterest() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(12), 12, "1000", null, "5000");
        Long account = service.get().accountId();
        service.deposit(d.id(), new DepositRequest(bank.getId(), n("1000"), LocalDate.now().minusDays(20)));

        View view = service.close(d.id(), new CloseRequest(bank.getId(), n("6500"), null));

        DepositView closed = view.closed().get(0);
        assertEquals("CLOSED", closed.status());
        assertEquals(LocalDate.now(), closed.closedOn());
        assertAmount("6000", closed.deposited());
        assertAmount("6500", closed.received());
        assertAmount("500", closed.interestEarned());
        assertTrue(view.open().isEmpty());
        assertAmount("0", balanceOf(account));
        assertAmount("105500", balanceOf(bank.getId()));   // 100000 - the 1000 deposited + the 6500 that came back
        List<Transaction> made = transactionRepository.findAllByRecurringDepositId(d.id());
        Transaction income = made.stream().filter(t -> t.getKind() == TransactionKind.INCOME).findFirst().orElseThrow();
        assertAmount("500", income.getAmount());
        assertEquals(interest.getId(), income.getCategoryId(), "filed under Bank Interest");
        assertEquals(1, depositRepository.findAllByAccountIdOrderByIdAsc(account).size(), "closed, never deleted");
    }

    @Test
    void aClosedDepositTakesNoMoreMoneyAndCannotBeClosedAgain() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(3), 12, null, null, null);
        service.deposit(d.id(), new DepositRequest(bank.getId(), n("1000"), null));
        service.close(d.id(), new CloseRequest(bank.getId(), n("1000"), null));

        assertThrows(ValidationException.class, () -> service.deposit(d.id(), new DepositRequest(bank.getId(), n("1"), null)));
        assertThrows(ValidationException.class, () -> service.close(d.id(), new CloseRequest(bank.getId(), n("1000"), null)));
        assertThrows(ValidationException.class, () -> service.update(d.id(), new EditData("Renamed", null, null, null, null, null)));
    }

    @Test
    void whatCameBackCannotBeLessThanWhatWasDeposited() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(3), 12, null, null, null);
        service.deposit(d.id(), new DepositRequest(bank.getId(), n("1000"), null));

        assertThrows(ValidationException.class, () -> service.close(d.id(), new CloseRequest(bank.getId(), n("999.99"), null)));
        assertEquals(1, service.get().open().size(), "still open");
    }

    @Test
    void theTermsAreChecked() {
        service.setUp();
        LocalDate start = LocalDate.now();

        assertThrows(ValidationException.class, () -> service.open(new OpenData(" ", start, 12, null, null, null)));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("A", null, 12, null, null, null)));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("A", start, 0, null, null, null)));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("A", start, 241, null, null, null)));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("A", start, 12, n("-1"), null, null)));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("A", start, 12, n("10.123"), null, null)));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("A", start, 12, null, n("101"), null)));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("A", start, 12, null, n("5.1234"), null)));
        service.open(new OpenData("A", start, 12, null, null, null));
        assertThrows(ValidationException.class, () -> service.open(new OpenData("a", start, 12, null, null, null)), "the name is used already");
    }

    @Test
    void aDepositNeedsAnAmountADayThatHasComeAndMoneyToSpend() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(1), 12, null, null, null);

        assertThrows(ValidationException.class, () -> service.deposit(d.id(), new DepositRequest(bank.getId(), n("0"), null)));
        assertThrows(ValidationException.class, () -> service.deposit(d.id(), new DepositRequest(bank.getId(), n("10"), LocalDate.now().plusDays(1))));
        assertThrows(ValidationException.class, () -> service.deposit(d.id(), new DepositRequest(bank.getId(), n("100001"), null)), "more than the bank holds");
        assertThrows(ValidationException.class, () -> service.deposit(d.id(), new DepositRequest(service.get().accountId(), n("10"), null)), "not from the deposits account itself");
        assertThrows(NotFoundException.class, () -> service.deposit(9999L, new DepositRequest(bank.getId(), n("10"), null)));
    }

    @Test
    void aMistakenDepositCanBeRemovedWhileTheDepositIsOpen() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(1), 12, null, null, null);
        service.deposit(d.id(), new DepositRequest(bank.getId(), n("1000"), null));
        Transaction made = transactionRepository.findAllByRecurringDepositId(d.id()).get(0);

        service.deleteDeposit(made.getId());

        assertAmount("0", service.get().open().get(0).deposited());
        assertAmount("100000", balanceOf(bank.getId()));
        Account cash = accountService.create(new AccountData("Cash", AccountType.CASH, null, null, BigDecimal.ZERO, null, null));
        Transaction other = transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.TRANSFER, bank.getId(), cash.getId(),
                n("10"), null, null, null, null, null));
        assertThrows(ValidationException.class, () -> service.deleteDeposit(other.getId()), "only a deposit into a recurring deposit can be removed here");
    }

    @Test
    void theAccountIsUsedOnlyThroughItsOwnButtons() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(1), 12, null, null, null);
        Long account = service.get().accountId();
        Category food = categoryService.create(new CategoryData("Food", CategoryKind.EXPENSE, null));
        Account cash = accountService.create(new AccountData("Cash", AccountType.CASH, null, null, n("100"), null, null));

        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.TRANSFER,
                bank.getId(), account, n("10"), null, null, null, null, null)));
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.INCOME,
                null, account, n("10"), null, food.getId(), null, null, null)));
        assertThrows(ValidationException.class, () -> accountService.update(account, new AccountData("Recurring Deposits", AccountType.INVESTMENT, null, null, n("5"), null, null)),
                "its opening balance is not edited by hand");
        assertNotNull(cash);
        assertNotNull(d);
    }

    @Test
    void anAccountWithNoTransactionsCanBeDeletedWithItsDeposits() {
        open("Bank RD", LocalDate.now(), 12, null, null, null);
        Long account = service.get().accountId();

        accountService.delete(account);

        assertTrue(depositRepository.findAll().isEmpty());
        assertTrue(!detailRepository.existsById(account));
    }

    @Test
    void editingKeepsWhatWasLeftOutAndMovesTheOpeningBalanceWithWhatWasAlreadyDeposited() {
        DepositView d = open("Bank RD", LocalDate.now().minusMonths(1), 12, "1000", "6", "2000");
        Long account = service.get().accountId();

        View view = service.update(d.id(), new EditData("Renamed", null, 24, null, null, n("3500")));

        DepositView after = view.open().get(0);
        assertEquals("Renamed", after.name());
        assertEquals(24, after.termMonths());
        assertAmount("1000", after.monthlyAmount());
        assertAmount("6", after.interestRate());
        assertAmount("3500", after.deposited());
        assertAmount("3500", accountService.get(account).getOpeningBalance());
    }

    @Test
    void theExportListsEveryDepositOnItsOwnSheetOnlyWhenTheCardIsInUse() throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook none = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(export.build()))) {
            assertTrue(none.getSheet("Recurring Deposits") == null, "no sheet before the card is used");
        }
        DepositView open = open("Bank RD", LocalDate.now().minusMonths(2), 12, "1000", "6", null);
        service.deposit(open.id(), new DepositRequest(bank.getId(), n("1000"), null));
        DepositView done = open("Old RD", LocalDate.now().minusMonths(12), 12, null, null, "5000");
        service.close(done.id(), new CloseRequest(bank.getId(), n("5400"), null));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(export.build()))) {
            var sheet = wb.getSheet("Recurring Deposits");
            assertEquals(3, sheet.getLastRowNum() + 1, "a header, the open deposit and the closed one");
            var first = sheet.getRow(1);
            assertEquals("Bank RD", first.getCell(0).getStringCellValue());
            assertEquals("OPEN", first.getCell(1).getStringCellValue());
            assertEquals(1000.0, first.getCell(7).getNumericCellValue());
            var second = sheet.getRow(2);
            assertEquals("CLOSED", second.getCell(1).getStringCellValue());
            assertEquals(5400.0, second.getCell(11).getNumericCellValue());
            assertEquals(400.0, second.getCell(12).getNumericCellValue());
        }
    }
}
