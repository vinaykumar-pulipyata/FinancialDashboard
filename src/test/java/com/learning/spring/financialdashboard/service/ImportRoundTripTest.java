package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Repeat;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.BudgetRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.DebtDetailRepository;
import com.learning.spring.financialdashboard.repository.ExchangeRateRepository;
import com.learning.spring.financialdashboard.repository.NoteFolderRepository;
import com.learning.spring.financialdashboard.repository.NoteRepository;
import com.learning.spring.financialdashboard.repository.ReminderRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Export, empty the database, import the file: the ordinary parts come back the way they were. */
@SpringBootTest
class ImportRoundTripTest {

    @Autowired ExportService export;
    @Autowired ImportService importer;
    @Autowired AccountService accountService;
    @Autowired SetupService setupService;
    @Autowired CategoryService categoryService;
    @Autowired TransactionService transactionService;
    @Autowired LoanService loanService;
    @Autowired BudgetService budgetService;
    @Autowired NoteService noteService;
    @Autowired ReminderService reminderService;
    @Autowired SystemCategories systemCategories;
    @Autowired ExchangeRateService exchangeRateService;
    @Autowired PortfolioService portfolioService;
    @Autowired RingFenceService ringFenceService;
    @Autowired com.learning.spring.financialdashboard.repository.RingFenceRepository ringFenceRepository;
    @Autowired JewelleryService jewelleryService;
    @Autowired com.learning.spring.financialdashboard.repository.JewelleryItemRepository jewelleryItems;
    @Autowired com.learning.spring.financialdashboard.repository.LoanWriteOffRepository writeOffRepository;
    @Autowired RecurringDepositService recurringDepositService;
    @Autowired com.learning.spring.financialdashboard.repository.RecurringDepositRepository rdRepository;
    @Autowired com.learning.spring.financialdashboard.repository.RecurringDepositDetailRepository rdDetails;
    @Autowired LedgerService ledgerService;
    @Autowired com.learning.spring.financialdashboard.repository.BucketEntryRepository bucketEntries;
    @Autowired com.learning.spring.financialdashboard.repository.BucketRepository bucketRepository;
    @Autowired com.learning.spring.financialdashboard.repository.LedgerDetailRepository ledgerDetails;
    @Autowired EmergencyFundService emergencyFundService;
    @Autowired com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository emergencyFunds;
    @Autowired com.learning.spring.financialdashboard.repository.HoldingRepository holdings;
    @Autowired com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository snapshots;
    @Autowired com.learning.spring.financialdashboard.repository.PortfolioDetailRepository portfolioDetails;
    @Autowired TransactionRepository transactions;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired BorrowerRepository borrowers;
    @Autowired BudgetRepository budgets;
    @Autowired NoteRepository noteRepository;
    @Autowired NoteFolderRepository noteFolders;
    @Autowired ReminderRepository reminders;
    @Autowired CreditCardDetailRepository cards;
    @Autowired DebtDetailRepository debts;
    @Autowired VehicleDetailRepository vehicles;
    @Autowired ExchangeRateRepository rates;

    final List<Long> myCategories = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void wipe() {
        DiaryVault.unbind();
        transactions.deleteAll();
        ringFenceRepository.deleteAll();
        jewelleryItems.deleteAll();
        writeOffRepository.deleteAll();
        rdRepository.deleteAll();
        rdDetails.deleteAll();
        bucketEntries.deleteAll();
        bucketRepository.deleteAll();
        ledgerDetails.deleteAll();
        emergencyFunds.deleteAll();
        snapshots.deleteAll();
        holdings.deleteAll();
        portfolioDetails.deleteAll();
        budgets.deleteAll();
        cards.deleteAll();
        debts.deleteAll();
        vehicles.deleteAll();
        borrowers.deleteAll();
        accounts.deleteAll();
        noteRepository.deleteAll();
        noteFolders.deleteAll();
        reminders.deleteAll();
        rates.deleteAll();
        categories.findAll().stream().filter(c -> c.getSystemKey() == null && c.getParentId() != null).forEach(categories::delete);
        categories.findAll().stream().filter(c -> c.getSystemKey() == null).forEach(categories::delete);
    }


    private Map<String, BigDecimal> balances() {
        return accountService.listSummaries().stream().collect(Collectors.toMap(a -> a.name(), a -> a.balance()));
    }

    private static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    private void txn(LocalDate day, TransactionKind kind, Long from, Long to, String amount, Long category, String note) {
        transactionService.create(new TransactionData(day, kind, from, to, n(amount), null, category, note, null, null, List.of("t")));
    }

    /** One sheet as rows of text (the header row first), so two workbooks can be compared. */
    private static List<List<String>> sheet(org.apache.poi.ss.usermodel.Workbook wb, String name) {
        var sheet = wb.getSheet(name);
        List<List<String>> rows = new ArrayList<>();
        if (sheet == null) {
            return rows;
        }
        var formatter = new org.apache.poi.ss.usermodel.DataFormatter();
        for (var row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                cells.add(row.getCell(c) == null ? "" : formatter.formatCellValue(row.getCell(c)).trim());
            }
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                cells.remove(cells.size() - 1);
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private static List<String> differences(String name, org.apache.poi.ss.usermodel.Workbook a, org.apache.poi.ss.usermodel.Workbook b, boolean sorted) {
        List<String> first = sheet(a, name).stream().map(Object::toString).collect(Collectors.toList());
        List<String> second = sheet(b, name).stream().map(Object::toString).collect(Collectors.toList());
        if (sorted) {
            java.util.Collections.sort(first);
            java.util.Collections.sort(second);
        }
        List<String> out = new ArrayList<>();
        List<String> onlyFirst = new ArrayList<>(first), onlySecond = new ArrayList<>(second);
        second.forEach(onlyFirst::remove);
        first.forEach(onlySecond::remove);
        onlyFirst.forEach(r -> out.add(name + " only before: " + r));
        onlySecond.forEach(r -> out.add(name + " only after:  " + r));
        return out;
    }

    private void seedEverything() {
        systemCategories.ensureAll();
        var food = categoryService.create(new CategoryService.CategoryData("Imp Food", CategoryKind.EXPENSE, null));
        var dining = categoryService.create(new CategoryService.CategoryData("Imp Dining", CategoryKind.EXPENSE, food.getId()));
        var pay = categoryService.create(new CategoryService.CategoryData("Imp Pay", CategoryKind.INCOME, null));
        var invIncome = categoryService.create(new CategoryService.CategoryData("Investment Income", CategoryKind.INCOME, null));
        categoryService.create(new CategoryService.CategoryData("Dividends", CategoryKind.INCOME, invIncome.getId()));
        categoryService.create(new CategoryService.CategoryData("Interest", CategoryKind.INCOME, invIncome.getId()));
        categoryService.create(new CategoryService.CategoryData("Bank Interest", CategoryKind.INCOME, null));
        categoryService.create(new CategoryService.CategoryData("Other Expense", CategoryKind.EXPENSE, null));
        var bills = categoryService.create(new CategoryService.CategoryData("Imp Bills", CategoryKind.EXPENSE, null));
        var old = categoryService.create(new CategoryService.CategoryData("Imp Old", CategoryKind.EXPENSE, null));
        categoryService.setActive(old.getId(), false);
        exchangeRateService.save(com.learning.spring.financialdashboard.model.Currency.USD, LocalDate.of(2026, 1, 1), n("83.25"));
        setupService.createAll(List.of(
                new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("500000"), null, null),
                new SetupService.SetupAccount("Imp Cash", AccountType.CASH, null, null, n("300"), null, null),
                new SetupService.SetupAccount("Imp Card", AccountType.CREDIT_CARD, null, null, n("0"), null, n("50000"), null, null, 12, null, 3),
                new SetupService.SetupAccount("Imp Loan", AccountType.DEBT, null, null, n("20000"), null, null, null, null, null, n("1500")),
                new SetupService.SetupAccount("Imp Car", AccountType.VEHICLE, null, null, n("100000"), null, null, "KA01AB1234", 2019),
                new SetupService.SetupAccount("Imp Goal", AccountType.GOAL_FUND, null, null, n("0"), n("9000"), null),
                new SetupService.SetupAccount("Imp Dollars", AccountType.BANK, null, null, n("100"), null, null, null, null, null, null, null, "USD")));
        Map<String, Long> id = accounts.findAll().stream().collect(Collectors.toMap(a -> a.getName(), a -> a.getId()));
        Long bank = id.get("Imp Bank");
        var ravi = loanService.create(new LoanService.BorrowerData("Imp Ravi", BigDecimal.ZERO));
        txn(LocalDate.of(2026, 1, 5), TransactionKind.INCOME, null, bank, "40000", pay.getId(), "Salary");
        txn(LocalDate.of(2026, 1, 6), TransactionKind.EXPENSE, bank, null, "1200.50", dining.getId(), "Dinner");
        txn(LocalDate.of(2026, 1, 7), TransactionKind.TRANSFER, bank, id.get("Imp Cash"), "500", null, "ATM");
        txn(LocalDate.of(2026, 1, 8), TransactionKind.EXPENSE, id.get("Imp Card"), null, "2500", dining.getId(), "Card dinner");
        transactionService.create(new TransactionData(LocalDate.of(2026, 1, 9), TransactionKind.EXPENSE, bank, id.get("Imp Card"), n("1000"), null,
                systemCategories.require(SystemCategories.LIABILITY_PAYMENT).getId(), "Card bill", null, null, List.of()));
        transactionService.create(new TransactionData(LocalDate.of(2026, 1, 10), TransactionKind.EXPENSE, bank, null, n("700"), null,
                systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), "Lent", null, ravi.getId(), List.of()));
        transactionService.create(new TransactionData(LocalDate.of(2026, 1, 11), TransactionKind.TRANSFER, bank, id.get("Imp Dollars"), n("8325"), null, null,
                "To dollars", null, null, List.of(), n("100")));
        txn(LocalDate.of(2026, 1, 12), TransactionKind.TRANSFER, bank, id.get("Imp Goal"), "1000", null, "Saving");
        txn(LocalDate.of(2026, 1, 13), TransactionKind.TRANSFER, bank, id.get("Imp Goal"), "8000", null, "Goal reached");
        ringFenceService.fence(id.get("Imp Goal"));
        loanService.restoreWriteOff(ravi.getId(), n("200"), LocalDate.of(2026, 3, 1), "Let it go");
        accountService.restoreAdjustment(id.get("Imp Cash"), TransactionKind.EXPENSE, n("120"), LocalDate.of(2026, 3, 2), "Lost the wallet");
        accountService.restoreAdjustment(id.get("Imp Loan"), TransactionKind.INCOME, n("500"), LocalDate.of(2026, 3, 3), "The bank waived it");
        budgetService.save(food.getId(), n("3000"));
        budgetService.save(null, n("20000"));
        var folder = noteService.createFolder("Imp Recipes");
        noteService.create(new NoteService.NoteData(LocalDate.of(2026, 1, 6), "Dal", "Soak it.", List.of("dal"), folder.getId()));
        noteService.create(new NoteService.NoteData(LocalDate.of(2026, 1, 6), "Diary", "A day.", List.of("day")));
        reminderService.setDone(reminderService.create(new ReminderService.ReminderData(LocalDate.of(2026, 1, 20), "Pay rent")).getId(), true, LocalDate.of(2026, 1, 19));
        var weekly = reminderService.create(new ReminderService.ReminderData(LocalDate.of(2026, 1, 1), "Water plants", Repeat.WEEKLY));
        reminderService.setOccurrenceDone(weekly.getId(), LocalDate.of(2026, 1, 8), true, LocalDate.of(2026, 1, 8));

        // the Emergency Fund
        var fund = emergencyFundService.save(new EmergencyFundService.Settings(null, n("2000"), com.learning.spring.financialdashboard.model.TargetMode.MONTHS, 6, null, n("1500"), n("500")));
        txn(LocalDate.of(2026, 2, 1), TransactionKind.TRANSFER, bank, fund.accountId(), "3000", null, "To the fund");

        // the Ledger
        LocalDate today = LocalDate.now();
        ledgerService.setUp();
        ledgerService.addBucket(new LedgerService.BucketData("Rent", n("1000"), n("1000"), 1, today.plusDays(10), today.minusDays(20), null, n("300")));
        ledgerService.addBucket(new LedgerService.BucketData("Insurance", n("500"), n("6000"), 12, today.plusMonths(6), null, today.plusYears(2), null));
        ledgerService.addBucket(new LedgerService.BucketData("Old plan", n("200"), null, null, null, null, null, n("100")));
        Map<String, Long> bucket = ledgerService.get().buckets().stream().collect(Collectors.toMap(b -> b.name(), b -> b.id()));
        ledgerService.fund(new LedgerService.FundRequest(bank, n("1700"), null, today.minusDays(60)));
        ledgerService.fund(new LedgerService.FundRequest(bank, n("900"), Map.of(bucket.get("Rent"), n("600"), bucket.get("Insurance"), n("300")), today.minusDays(5)));
        ledgerService.pay(new LedgerService.PayRequest(bucket.get("Rent"), n("1000"), today.minusDays(25), bills.getId(), "March rent"));
        ledgerService.creditInterest(new LedgerService.InterestRequest(n("42.50"), today.minusDays(3), null));
        ledgerService.transfer(new LedgerService.TransferRequest(bucket.get("Insurance"), bucket.get("Rent"), n("100")));
        ledgerService.close(bucket.get("Old plan"), new LedgerService.CloseRequest(null));

        // Mutual Funds and Shares
        var mf = com.learning.spring.financialdashboard.model.PortfolioKind.MUTUAL_FUNDS;
        var sh = com.learning.spring.financialdashboard.model.PortfolioKind.SHARES;
        portfolioService.setUp(mf);
        portfolioService.setUp(sh);
        portfolioService.invest(mf, new PortfolioService.InvestRequest(bank, n("10000"), null, "Index fund", LocalDate.of(2026, 2, 1)));
        Long index = portfolioService.get(mf).holdings().get(0).id();
        portfolioService.updateValue(mf, index, new PortfolioService.ValueRequest(n("11500"), null, LocalDate.of(2026, 3, 1), n("12.5")));
        portfolioService.withdraw(mf, index, new PortfolioService.WithdrawRequest(bank, n("4000"), LocalDate.of(2026, 3, 10), n("3000")));
        portfolioService.addHolding(sh, new PortfolioService.HoldingData("Infosys", n("4000"), n("4500")));
        portfolioService.dividend(sh, portfolioService.get(sh).holdings().get(0).id(), new PortfolioService.DividendRequest(bank, n("120"), LocalDate.of(2026, 3, 15)));

        // Recurring Deposits
        recurringDepositService.setUp();
        recurringDepositService.open(new RecurringDepositService.OpenData("Home RD", LocalDate.of(2025, 1, 5), 24, n("5000"), n("6.5"), n("10000")));
        recurringDepositService.open(new RecurringDepositService.OpenData("Old RD", LocalDate.of(2024, 1, 5), 12, n("1000"), n("7"), null));
        var rd = recurringDepositService.get();
        Long home = rd.open().stream().filter(d -> d.name().equals("Home RD")).findFirst().orElseThrow().id();
        Long oldRd = rd.open().stream().filter(d -> d.name().equals("Old RD")).findFirst().orElseThrow().id();
        for (int m = 0; m < 3; m++) {
            recurringDepositService.deposit(home, new RecurringDepositService.DepositRequest(bank, n("5000"), LocalDate.of(2025, 2 + m, 5)));
            recurringDepositService.deposit(oldRd, new RecurringDepositService.DepositRequest(bank, n("1000"), LocalDate.of(2024, 2 + m, 5)));
        }
        recurringDepositService.close(oldRd, new RecurringDepositService.CloseRequest(bank, n("3200.50"), LocalDate.of(2024, 6, 1)));

        // Jewellery
        jewelleryService.setUp();
        jewelleryService.add(new JewelleryService.ItemData("Gold", "Mother's chain", "ORNAMENT", n("20"), n("100000"), null));
        jewelleryService.add(new JewelleryService.ItemData("Gold", "Coin", "COIN", n("10"), n("50000"), bank));
        jewelleryService.add(new JewelleryService.ItemData("Silver", "Plates", "OTHER", n("500"), n("30000"), bank));
        var jv = jewelleryService.get();
        Long chain = jv.metals().stream().flatMap(m -> m.items().stream()).filter(i -> i.name().equals("Mother's chain")).findFirst().orElseThrow().id();
        Long plates = jv.metals().stream().flatMap(m -> m.items().stream()).filter(i -> i.name().equals("Plates")).findFirst().orElseThrow().id();
        jewelleryService.sell(chain, new JewelleryService.SaleRequest(bank, n("35000"), n("5")));
        jewelleryService.sell(plates, new JewelleryService.SaleRequest(bank, n("25000"), null));
    }

    @Test
    void everythingExportedComesBackAndExportsTheSame() throws Exception {
        seedEverything();
        Map<String, BigDecimal> before = balances();
        byte[] first = export.build();
        wipe();
        assertEquals(0, accounts.count());

        ImportService.Report check = importer.run(new ByteArrayInputStream(first), false);
        assertEquals(List.of(), check.steps().stream().flatMap(s -> s.messages().stream()).toList(), "the check finds no problem");
        assertEquals(0, accounts.count(), "the check saves nothing");

        ImportService.Report done = importer.run(new ByteArrayInputStream(first), true);
        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        assertEquals(List.of(), done.notLoaded());
        assertEquals(1, ringFenceRepository.count(), "the goal fund is ring-fenced again");

        Map<String, BigDecimal> after = balances();
        assertEquals(before.keySet(), after.keySet());
        for (String name : before.keySet()) {
            assertEquals(0, before.get(name).compareTo(after.get(name)), name + ": " + before.get(name) + " vs " + after.get(name));
        }

        byte[] second = export.build();
        try (var one = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(first));
             var two = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(second))) {
            List<String> problems = new ArrayList<>();
            for (String name : List.of("Accounts", "Transactions", "Loans", "Write-offs", "Categories", "Budgets", "Notes", "Note folders", "Reminders",
                    "Reminder ticks", "Ledger", "Ledger entries", "Emergency Fund", "Mutual Funds", "Shares", "Jewellery", "Recurring Deposits",
                    "Exchange rates", "Bills", "Balance adjustments")) {
                problems.addAll(differences(name, one, two, true));
            }
            assertEquals(List.of(), problems, "the second export says what the first said");
        }
    }
}
