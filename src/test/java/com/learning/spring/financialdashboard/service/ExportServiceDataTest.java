package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.model.TargetMode;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.EmergencyFundService.Settings;
import com.learning.spring.financialdashboard.service.LedgerService.BucketData;
import com.learning.spring.financialdashboard.service.LedgerService.CloseRequest;
import com.learning.spring.financialdashboard.service.LedgerService.FundRequest;
import com.learning.spring.financialdashboard.service.LedgerService.PayRequest;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerData;
import com.learning.spring.financialdashboard.service.PortfolioService.HoldingData;
import com.learning.spring.financialdashboard.service.PortfolioService.ValueRequest;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import com.learning.spring.financialdashboard.service.VehicleService.VehicleData;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Excel export against a fully populated database, and against an empty one. */
@SpringBootTest
class ExportServiceDataTest {

    @Autowired
    ExportService export;
    @Autowired
    AccountService accountService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    CreditCardService creditCardService;
    @Autowired
    VehicleService vehicleService;
    @Autowired
    EmergencyFundService emergencyFundService;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    PortfolioService portfolioService;
    @Autowired
    LoanService loanService;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    NoteService noteService;
    @Autowired
    ReminderService reminderService;
    @Autowired
    com.learning.spring.financialdashboard.repository.NoteRepository noteRepository;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    BucketEntryRepository bucketEntries;
    @Autowired
    BucketRepository buckets;
    @Autowired
    LedgerDetailRepository ledgers;
    @Autowired
    RingFenceRepository ringFences;
    @Autowired
    EmergencyFundDetailRepository emergencyFunds;
    @Autowired
    HoldingSnapshotRepository snapshots;
    @Autowired
    HoldingRepository holdings;
    @Autowired
    PortfolioDetailRepository portfolios;
    @Autowired
    VehicleDetailRepository vehicles;
    @Autowired
    CreditCardDetailRepository creditCards;
    @Autowired
    BorrowerRepository borrowers;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;

    final List<Long> myCategories = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void wipe() {
        transactions.deleteAll();
        bucketEntries.deleteAll();
        buckets.deleteAll();
        ledgers.deleteAll();
        ringFences.deleteAll();
        emergencyFunds.deleteAll();
        snapshots.deleteAll();
        holdings.deleteAll();
        portfolios.deleteAll();
        vehicles.deleteAll();
        creditCards.deleteAll();
        borrowers.deleteAll();
        accounts.deleteAll();
        noteRepository.deleteAll();
        myCategories.forEach(id -> categories.findById(id).ifPresent(categories::delete));
        myCategories.clear();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private Account account(String name, AccountType type, String opening) {
        return accountService.create(new AccountData(name, type, null, null, n(opening), null, null));
    }

    private Category category(String name, CategoryKind kind, Long parentId) {
        Category c = categoryService.create(new CategoryData(name, kind, parentId));
        myCategories.add(c.getId());
        return c;
    }

    private void txn(int daysAgo, TransactionKind kind, Account from, Account to, String amount, String fee, Category category, Long borrowerId) {
        transactionService.create(new TransactionData(LocalDate.now().minusDays(daysAgo), kind,
                from == null ? null : from.getId(), to == null ? null : to.getId(), n(amount), fee == null ? null : n(fee),
                category == null ? null : category.getId(), "note " + amount, "description", borrowerId));
    }

    private XSSFWorkbook open() throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(export.build()));
    }

    private int charts(XSSFWorkbook wb, String sheet) {
        XSSFSheet s = wb.getSheet(sheet);
        return s.getDrawingPatriarch() == null ? 0 : s.getDrawingPatriarch().getCharts().size();
    }

    private org.apache.poi.ss.usermodel.Row rowNamed(Sheet sheet, String name) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row.getCell(0) != null && name.equals(row.getCell(0).toString())) {
                return row;
            }
        }
        throw new AssertionError("no row named " + name);
    }

    private String text(Sheet sheet, int row, int col) {
        return sheet.getRow(row).getCell(col).toString();
    }

    @Test
    void aFullyPopulatedDatabaseFillsEverySheetAndEveryChart() throws IOException {
        Account bank = account("Main bank", AccountType.BANK, "100000");
        Account cash = account("Wallet", AccountType.CASH, "500");
        Account card = account("Travel card", AccountType.CREDIT_CARD, "0");
        Account car = account("Car", AccountType.VEHICLE, "300000");
        creditCardService.update(card.getId(), n("50000"), 25, 5);   // bill due on the 25th, statement on the 5th
        vehicleService.save(car.getId(), new VehicleData("KA01AB1234", 2020));

        // More than five income and expense categories, so the pies fold the rest into "Other".
        Category salary = category("Export salary", CategoryKind.INCOME, null);
        List<Category> income = new ArrayList<>(List.of(salary));
        for (String name : List.of("Export interest", "Export rent", "Export gifts", "Export refunds", "Export bonus", "Export misc income")) {
            income.add(category(name, CategoryKind.INCOME, null));
        }
        Category food = category("Export food", CategoryKind.EXPENSE, null);
        Category snacks = category("Export snacks", CategoryKind.EXPENSE, food.getId());
        List<Category> expense = new ArrayList<>(List.of(food, snacks));
        for (String name : List.of("Export travel", "Export bills", "Export fuel", "Export health", "Export fun", "Export clothes")) {
            expense.add(category(name, CategoryKind.EXPENSE, null));
        }
        int day = 80;
        for (Category c : income) {
            txn(day -= 7, TransactionKind.INCOME, null, bank, "1000", null, c, null);
        }
        day = 75;
        for (Category c : expense) {
            txn(day -= 6, TransactionKind.EXPENSE, bank, null, "200", null, c, null);
        }
        txn(20, TransactionKind.EXPENSE, cash, null, "50", null, null, null);               // uncategorised
        txn(15, TransactionKind.TRANSFER, bank, cash, "1000", "10", null, null);            // with a fee
        txn(12, TransactionKind.EXPENSE, card, null, "300", null, food, null);              // on the card

        // Money lent to someone and partly collected.
        systemCategories.ensureAll();
        account("Receivables", AccountType.RECEIVABLE, "500");   // 500 lent before you started; 100 of it is Ravi's, the rest has no name
        Borrower ravi = loanService.create(new BorrowerData("Ravi", n("100")));
        txn(10, TransactionKind.EXPENSE, bank, null, "2000", null, systemCategories.require(SystemCategories.LOANS_GIVEN), ravi.getId());
        txn(5, TransactionKind.INCOME, null, bank, "700", null, systemCategories.require(SystemCategories.LOANS_COLLECTED), ravi.getId());

        // An Emergency Fund that has not reached its target, so the saved / still-to-save pie is drawn.
        emergencyFundService.save(new Settings(null, n("5000"), TargetMode.MONTHS, 6, null, n("3000"), n("1000")));

        // A Ledger with an open bucket, a closed one and some payments.
        ledgerService.setUp();
        LocalDate next = LocalDate.now().plusDays(20);
        ledgerService.addBucket(new BucketData("Insurance", n("2000"), n("6000"), 3, next, null, null, null));
        ledgerService.addBucket(new BucketData("Wifi", n("500"), n("500"), 1, next, null, LocalDate.now().plusYears(1), null));
        ledgerService.fund(new FundRequest(bank.getId(), n("2500"), null));
        Long insurance = ledgerService.get().buckets().stream().filter(b -> b.name().equals("Insurance")).findFirst().orElseThrow().id();
        Long wifi = ledgerService.get().buckets().stream().filter(b -> b.name().equals("Wifi")).findFirst().orElseThrow().id();
        ledgerService.pay(new PayRequest(insurance, n("300")));
        ledgerService.close(wifi, new CloseRequest(null));

        // Mutual Funds and Shares with a value history of several days.
        for (PortfolioKind kind : PortfolioKind.values()) {
            if (!portfolioService.get(kind).configured()) {
                portfolioService.setUp(kind);
            }
            portfolioService.addHolding(kind, new HoldingData(kind.getLabel() + " one", n("10000"), n("11000")));
            portfolioService.addHolding(kind, new HoldingData(kind.getLabel() + " two", n("5000"), n("4500")));
            Long first = portfolioService.get(kind).holdings().get(0).id();
            portfolioService.updateValue(kind, first, new ValueRequest(n("11500"), n("10000"), LocalDate.now().minusDays(30)));
            portfolioService.updateValue(kind, first, new ValueRequest(n("12000"), n("10000"), LocalDate.now().minusDays(10)));
        }

        noteService.create(new NoteService.NoteData(LocalDate.now(), "Export diary", "A note that must reach the workbook.", List.of("export", "diary")));
        noteService.create(new NoteService.NoteData(LocalDate.now().minusDays(1), null, "An untitled earlier note."));

        var open = reminderService.create(new ReminderService.ReminderData(LocalDate.now(), "Renew insurance"));
        reminderService.create(new ReminderService.ReminderData(LocalDate.of(1990, 3, 14), "Asha birthday", com.learning.spring.financialdashboard.model.Repeat.YEARLY));
        var finished = reminderService.create(new ReminderService.ReminderData(LocalDate.now().minusDays(2), "Call the CA"));
        reminderService.setDone(finished.getId(), true);

        try (XSSFWorkbook wb = open()) {
            assertEquals(List.of("Accounts", "Transactions", "Balance adjustments", "Income & Expenses", "Monthly", "Net worth", "Ledger", "Ledger entries",
                    "Emergency Fund", "Mutual Funds", "Shares", "Jewellery", "Loans", "Write-offs", "Bills", "Categories", "Budgets", "Notes",
                    "Note folders", "Reminders", "Reminder ticks", "Holding history"),
                    java.util.stream.IntStream.range(0, wb.getNumberOfSheets()).mapToObj(wb::getSheetName).toList());

            assertEquals("Not set up.", wb.getSheet("Jewellery").getRow(0).getCell(0).getStringCellValue());
            assertTrue(wb.getSheet("Accounts").getLastRowNum() >= 4);

            // The account details that live in their own tables are on the Accounts sheet too.
            Sheet accountsSheet = wb.getSheet("Accounts");
            assertEquals(List.of("Target (goal funds)", "Credit limit", "Bill due day", "Monthly EMI", "Number plate", "Year bought", "Value last set", "Statement day"),
                    java.util.stream.IntStream.range(9, 17).mapToObj(c -> text(accountsSheet, 0, c)).toList());
            org.apache.poi.ss.usermodel.Row cardRow = rowNamed(accountsSheet, "Travel card");
            assertEquals(50000.0, cardRow.getCell(10).getNumericCellValue());
            assertEquals(25.0, cardRow.getCell(11).getNumericCellValue(), "the day the bill is due");
            assertEquals(5.0, cardRow.getCell(16).getNumericCellValue(), "the day the statement is generated: a different day");
            org.apache.poi.ss.usermodel.Row carRow = rowNamed(accountsSheet, "Car");
            assertEquals("KA01AB1234", carRow.getCell(13).getStringCellValue());
            assertEquals(2020.0, carRow.getCell(14).getNumericCellValue());
            assertTrue(rowNamed(accountsSheet, "Wallet").getCell(10) == null, "a plain account has no card details");

            // Every recorded value of every holding, one row per holding per day (2 portfolios x 4 recorded values).
            Sheet history = wb.getSheet("Holding history");
            assertEquals("Holding", text(history, 0, 1));
            assertTrue(history.getLastRowNum() >= 8, "at least the 8 values recorded above");
            assertTrue(text(history, 1, 1).endsWith(" one"), "the holding's name, not an id");
            assertEquals(income.size() + expense.size() + 7, wb.getSheet("Transactions").getLastRowNum());   // 5 more by hand plus the Ledger funding and its payment; header not counted

            assertEquals(2, charts(wb, "Income & Expenses"));
            assertEquals(1, charts(wb, "Monthly"));
            assertEquals(1, charts(wb, "Net worth"));
            assertEquals(1, charts(wb, "Ledger"));
            assertEquals(1, charts(wb, "Emergency Fund"));
            assertEquals(2, charts(wb, "Mutual Funds"));
            assertEquals(2, charts(wb, "Shares"));

            // The top five plus Other on each pie, and the closed Ledger bucket listed.
            Sheet pies = wb.getSheet("Income & Expenses");
            assertEquals("Other", text(pies, 7, 0));
            assertEquals("Other", text(pies, 7, 5));
            Sheet ledger = wb.getSheet("Ledger");
            boolean closedListed = false;
            for (int r = 0; r <= ledger.getLastRowNum(); r++) {
                if (ledger.getRow(r) != null && ledger.getRow(r).getCell(0) != null && "Closed buckets".equals(ledger.getRow(r).getCell(0).toString())) {
                    closedListed = true;
                }
            }
            assertTrue(closedListed);
            assertTrue(wb.getSheet("Ledger entries").getLastRowNum() >= 3);
            assertEquals("Earlier loans (no name)", text(wb.getSheet("Loans"), 1, 0));
            assertTrue(wb.getSheet("Loans").getLastRowNum() >= 2);
            assertEquals("Mutual Funds", text(wb.getSheet("Mutual Funds"), 0, 0));
            Sheet notes = wb.getSheet("Notes");
            assertEquals(2, notes.getLastRowNum());
            assertEquals("An untitled earlier note.", text(notes, 1, 2));   // oldest first
            assertEquals("Export diary", text(notes, 2, 1));
            assertEquals("diary, export", text(notes, 2, 3));   // tags, alphabetical
            assertEquals("Daily Calendar Notes", text(wb.getSheet("Note folders"), 1, 0));
            assertEquals("Date", text(wb.getSheet("Write-offs"), 0, 0));
            assertEquals("From", text(wb.getSheet("Bills"), 0, 0));
            assertEquals("Reminder", text(wb.getSheet("Reminder ticks"), 0, 0));
            assertEquals("What it did", text(wb.getSheet("Balance adjustments"), 0, 2));
            Sheet reminders = wb.getSheet("Reminders");
            assertEquals(3, reminders.getLastRowNum());
            assertEquals("Asha birthday", text(reminders, 1, 1));   // oldest date first
            assertEquals("Every year", text(reminders, 1, 4));
            assertEquals("Call the CA", text(reminders, 2, 1));
            assertEquals("Yes", text(reminders, 2, 2));
            assertEquals("Renew insurance", text(reminders, 3, 1));
            assertEquals("No", text(reminders, 3, 2));
        }
    }

    @Test
    void anEmptyDatabaseStillExportsEverySheetAndSaysWhatIsNotSetUp() throws IOException {
        try (XSSFWorkbook wb = open()) {
            assertEquals(21, wb.getNumberOfSheets());   // no "Ledger entries" sheet without a Ledger
            assertEquals("The Future Expenses Ledger is not set up.", text(wb.getSheet("Ledger"), 0, 0));
            assertEquals("The Emergency Fund is not set up.", text(wb.getSheet("Emergency Fund"), 0, 0));
            assertEquals(0, wb.getSheet("Transactions").getLastRowNum());
            assertEquals(0, charts(wb, "Income & Expenses"));
            assertEquals(0, charts(wb, "Monthly"));
            assertNotNull(wb.getSheet("Categories"));
        }
    }
}
