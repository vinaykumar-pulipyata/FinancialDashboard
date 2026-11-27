package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export, empty the database, import the file: the ordinary parts come back the way they were. */
@SpringBootTest
class ImportServiceTest {

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

    private static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    private Map<String, BigDecimal> balances() {
        return accountService.listSummaries().stream().collect(Collectors.toMap(a -> a.name(), a -> a.balance()));
    }

    private void txn(LocalDate day, TransactionKind kind, Long from, Long to, String amount, Long category, String note) {
        transactionService.create(new TransactionData(day, kind, from, to, n(amount), null, category, note, null, null, List.of("t")));
    }

    /** A small but varied set of data. */
    private void seed() {
        systemCategories.ensureAll();
        var food = categoryService.create(new CategoryService.CategoryData("Imp Food", CategoryKind.EXPENSE, null));
        var dining = categoryService.create(new CategoryService.CategoryData("Imp Dining", CategoryKind.EXPENSE, food.getId()));
        var pay = categoryService.create(new CategoryService.CategoryData("Imp Pay", CategoryKind.INCOME, null));
        var old = categoryService.create(new CategoryService.CategoryData("Imp Old", CategoryKind.EXPENSE, null));
        categoryService.setActive(old.getId(), false);
        setupService.createAll(List.of(
                new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("5000"), null, null),
                new SetupService.SetupAccount("Imp Cash", AccountType.CASH, null, null, n("300"), null, null),
                new SetupService.SetupAccount("Imp Card", AccountType.CREDIT_CARD, null, null, n("0"), null, n("50000"), null, null, 12, null, 3),
                new SetupService.SetupAccount("Imp Loan", AccountType.DEBT, null, null, n("20000"), null, null, null, null, null, n("1500")),
                new SetupService.SetupAccount("Imp Car", AccountType.VEHICLE, null, null, n("100000"), null, null, "KA01AB1234", 2019),
                new SetupService.SetupAccount("Imp Goal", AccountType.GOAL_FUND, null, null, n("0"), n("9000"), null)));
        Map<String, Long> id = accounts.findAll().stream().collect(Collectors.toMap(a -> a.getName(), a -> a.getId()));
        var ravi = loanService.create(new LoanService.BorrowerData("Imp Ravi", BigDecimal.ZERO));
        txn(LocalDate.of(2026, 1, 5), TransactionKind.INCOME, null, id.get("Imp Bank"), "40000", pay.getId(), "Salary");
        txn(LocalDate.of(2026, 1, 6), TransactionKind.EXPENSE, id.get("Imp Bank"), null, "1200.50", dining.getId(), "Dinner");
        txn(LocalDate.of(2026, 1, 6), TransactionKind.EXPENSE, id.get("Imp Cash"), null, "80", food.getId(), "Fruit");
        txn(LocalDate.of(2026, 1, 7), TransactionKind.TRANSFER, id.get("Imp Bank"), id.get("Imp Cash"), "500", null, "ATM");
        txn(LocalDate.of(2026, 1, 8), TransactionKind.EXPENSE, id.get("Imp Card"), null, "2500", dining.getId(), "Card dinner");
        transactionService.create(new TransactionData(LocalDate.of(2026, 1, 9), TransactionKind.EXPENSE, id.get("Imp Bank"), id.get("Imp Card"), n("1000"), null,
                systemCategories.require(SystemCategories.LIABILITY_PAYMENT).getId(), "Card bill", null, null, List.of()));
        transactionService.create(new TransactionData(LocalDate.of(2026, 1, 10), TransactionKind.EXPENSE, id.get("Imp Bank"), null, n("700"), null,
                systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), "Lent", null, ravi.getId(), List.of()));
        txn(LocalDate.of(2026, 1, 11), TransactionKind.TRANSFER, id.get("Imp Bank"), id.get("Imp Goal"), "1000", null, "Saving");
        budgetService.save(food.getId(), n("3000"));
        budgetService.save(null, n("20000"));
        var folder = noteService.createFolder("Imp Recipes");
        noteService.create(new NoteService.NoteData(LocalDate.of(2026, 1, 6), "Dal", "Soak it.", List.of("dal"), folder.getId()));
        noteService.create(new NoteService.NoteData(LocalDate.of(2026, 1, 6), "Diary", "A day.", List.of("day")));
        reminderService.setDone(reminderService.create(new ReminderService.ReminderData(LocalDate.of(2026, 1, 20), "Pay rent")).getId(), true, LocalDate.of(2026, 1, 19));
        var weekly = reminderService.create(new ReminderService.ReminderData(LocalDate.of(2026, 1, 1), "Water plants", Repeat.WEEKLY));
        reminderService.setOccurrenceDone(weekly.getId(), LocalDate.of(2026, 1, 8), true, LocalDate.of(2026, 1, 8));
    }

    @Test
    void anExportedFileLoadsBackIntoAnEmptyDatabase() throws Exception {
        seed();
        Map<String, BigDecimal> before = balances();
        int transactionCount = (int) transactions.count();
        byte[] file = export.build();
        wipe();
        assertEquals(0, accounts.count());

        // Checking first changes nothing.
        ImportService.Report check = importer.run(new ByteArrayInputStream(file), false);
        assertEquals(List.of(), check.steps().stream().flatMap(s -> s.messages().stream()).toList());
        assertEquals(0, accounts.count());
        assertEquals(0, transactions.count());

        ImportService.Report done = importer.run(new ByteArrayInputStream(file), true);
        assertTrue(done.applied());
        assertEquals(false, importer.progress().running(), "the run is over");
        assertEquals(100, importer.progress().percent());
        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        assertEquals(transactionCount, transactions.count());
        Map<String, BigDecimal> after = balances();
        for (String name : List.of("Imp Bank", "Imp Cash", "Imp Card", "Imp Loan", "Imp Car", "Imp Goal")) {
            assertEquals(0, before.get(name).compareTo(after.get(name)), name + " balance: " + before.get(name) + " vs " + after.get(name));
        }
        assertEquals(1, cards.findAll().stream().filter(c -> c.getCreditLimit().compareTo(n("50000")) == 0 && c.getDueDay() == 12 && c.getStatementDay() == 3).count());
        assertEquals(1, debts.count());
        assertEquals("KA01AB1234", vehicles.findAll().get(0).getNumberPlate());
        assertEquals(1, borrowers.count());
        assertEquals(2, budgets.count());
        assertEquals(2, noteRepository.count());
        assertEquals(List.of("Imp Recipes"), noteFolders.findAll().stream().map(f -> f.getName()).toList());
        assertEquals(2, reminders.count());
        assertTrue(categories.findAll().stream().anyMatch(c -> c.getName().equals("Imp Old") && !c.isActive()), "an inactive category stays inactive");
        assertTrue(transactionService.list().stream().anyMatch(t -> "Card dinner".equals(t.getNote()) && t.getTags().contains("t")), "notes and tags come back");
    }

    @Test
    void importRefusesADatabaseThatAlreadyHasData() throws Exception {
        seed();
        byte[] file = export.build();
        assertThrows(ValidationException.class, () -> importer.run(new ByteArrayInputStream(file), true));
        assertThrows(ValidationException.class, () -> importer.run(new ByteArrayInputStream("not a workbook".getBytes()), false));
    }

    @Test
    void theEndpointTakesTheFileAsTheBodyAndChecksUnlessToldToApply() throws Exception {
        seed();
        byte[] file = export.build();
        wipe();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.learning.spring.financialdashboard.controller.ImportController(importer))
                .setControllerAdvice(new com.learning.spring.financialdashboard.exception.ApiExceptionHandler()).build();
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/import")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM).content(file);

        var checked = mvc.perform(post).andReturn().getResponse();
        assertEquals(200, checked.getStatus());
        assertTrue(checked.getContentAsString().contains("\"applied\":false"));
        assertEquals(0, accounts.count(), "a check saves nothing");

        var applied = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/import?apply=true")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM).content(file)).andReturn().getResponse();
        assertTrue(applied.getContentAsString().contains("\"applied\":true"));
        assertTrue(accounts.count() > 0);

        var refused = mvc.perform(post).andReturn().getResponse();   // now the database has data
        assertEquals(400, refused.getStatus());
        assertTrue(refused.getContentAsString().contains("error"));
    }

    @Test
    void mutualFundsAndSharesComeBackWithTheirHoldingsMoneyAndHistory() throws Exception {
        systemCategories.ensureAll();
        var income = categoryService.create(new CategoryService.CategoryData("Investment Income", CategoryKind.INCOME, null));
        categoryService.create(new CategoryService.CategoryData("Dividends", CategoryKind.INCOME, income.getId()));
        setupService.createAll(List.of(new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("100000"), null, null)));
        Long bank = accounts.findAll().get(0).getId();
        var mf = com.learning.spring.financialdashboard.model.PortfolioKind.MUTUAL_FUNDS;
        var sh = com.learning.spring.financialdashboard.model.PortfolioKind.SHARES;
        portfolioService.setUp(mf);
        portfolioService.setUp(sh);
        portfolioService.invest(mf, new PortfolioService.InvestRequest(bank, n("10000"), null, "Index fund", LocalDate.of(2026, 2, 1)));
        portfolioService.invest(mf, new PortfolioService.InvestRequest(bank, n("5000"), null, "Small cap", LocalDate.of(2026, 2, 3)));
        Long index = portfolioService.get(mf).holdings().stream().filter(h -> h.name().equals("Index fund")).findFirst().orElseThrow().id();
        portfolioService.updateValue(mf, index, new PortfolioService.ValueRequest(n("11500"), null, LocalDate.of(2026, 3, 1), n("12.5")));
        portfolioService.invest(mf, new PortfolioService.InvestRequest(bank, n("2000"), index, null, LocalDate.of(2026, 3, 2)));
        portfolioService.withdraw(mf, index, new PortfolioService.WithdrawRequest(bank, n("4000"), LocalDate.of(2026, 3, 10), n("3000")));
        portfolioService.addHolding(sh, new PortfolioService.HoldingData("Infosys", n("4000"), n("4500")));
        Long infosys = portfolioService.get(sh).holdings().get(0).id();
        portfolioService.dividend(sh, infosys, new PortfolioService.DividendRequest(bank, n("120"), LocalDate.of(2026, 3, 15)));
        var mfBefore = portfolioService.get(mf);
        var shBefore = portfolioService.get(sh);
        Map<String, BigDecimal> before = balances();
        byte[] file = export.build();
        wipe();

        ImportService.Report done = importer.run(new ByteArrayInputStream(file), true);

        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        var mfAfter = portfolioService.get(mf);
        var shAfter = portfolioService.get(sh);
        for (String what : List.of("investedAmount", "totalInvested", "currentValue", "realizedGain")) {
            var read = mfBefore.getClass().getMethod(what);
            assertEquals(0, ((BigDecimal) read.invoke(mfBefore)).compareTo((BigDecimal) read.invoke(mfAfter)), "Mutual Funds " + what);
            assertEquals(0, ((BigDecimal) read.invoke(shBefore)).compareTo((BigDecimal) read.invoke(shAfter)), "Shares " + what);
        }
        assertEquals(2, mfAfter.holdings().size());
        assertEquals(0, mfBefore.holdings().stream().filter(h -> h.name().equals("Index fund")).findFirst().orElseThrow().quantity()
                .compareTo(mfAfter.holdings().stream().filter(h -> h.name().equals("Index fund")).findFirst().orElseThrow().quantity()), "the quantity comes back");
        Map<String, BigDecimal> after = balances();
        for (String name : before.keySet()) {
            assertEquals(0, before.get(name).compareTo(after.get(name)), name + ": " + before.get(name) + " vs " + after.get(name));
        }
        assertTrue(portfolioService.stats(mf).overall().size() > 2, "the value history is back too");
        assertEquals(1, transactionService.list().stream().filter(t -> t.getHoldingId() != null && t.getKind() == TransactionKind.INCOME).count(), "the dividend");
    }

    @Test
    void theEmergencyFundComesBackWithItsMoneyAndItsCardSettings() throws Exception {
        setupService.createAll(List.of(new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("50000"), null, null)));
        Long bank = accounts.findAll().get(0).getId();
        var fund = emergencyFundService.save(new EmergencyFundService.Settings(null, n("2000"), com.learning.spring.financialdashboard.model.TargetMode.MONTHS, 6, null,
                n("1500"), n("500")));
        txn(LocalDate.of(2026, 2, 1), TransactionKind.TRANSFER, bank, fund.accountId(), "3000", null, "Saved");
        txn(LocalDate.of(2026, 3, 1), TransactionKind.TRANSFER, fund.accountId(), bank, "800", null, "Used");
        Map<String, BigDecimal> before = balances();
        byte[] file = export.build();
        wipe();

        ImportService.Report done = importer.run(new ByteArrayInputStream(file), true);

        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        var after = emergencyFundService.get();
        assertTrue(after.configured());
        assertEquals("Emergency Fund", after.name());
        assertEquals(com.learning.spring.financialdashboard.model.TargetMode.MONTHS, after.targetMode());
        assertEquals(6, after.targetMonths());
        assertEquals(0, n("9000").compareTo(after.target()));
        assertEquals(0, n("500").compareTo(after.plannedMonthlyContribution()));
        Map<String, BigDecimal> now = balances();
        for (String name : before.keySet()) {
            assertEquals(0, before.get(name).compareTo(now.get(name)), name + ": " + before.get(name) + " vs " + now.get(name));
        }
    }

    @Test
    void theLedgerComesBackWithItsBucketsHistoryAndBalance() throws Exception {
        systemCategories.ensureAll();
        var income = categoryService.create(new CategoryService.CategoryData("Investment Income", CategoryKind.INCOME, null));
        var interest = categoryService.create(new CategoryService.CategoryData("Interest", CategoryKind.INCOME, income.getId()));
        var bills = categoryService.create(new CategoryService.CategoryData("Imp Bills", CategoryKind.EXPENSE, null));
        setupService.createAll(List.of(new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("100000"), null, null)));
        Long bank = accounts.findAll().get(0).getId();
        LocalDate today = LocalDate.now();
        ledgerService.setUp();
        ledgerService.addBucket(new LedgerService.BucketData("Rent", n("1000"), n("1000"), 1, today.plusDays(10), today.minusDays(20), null, n("300")));
        ledgerService.addBucket(new LedgerService.BucketData("Insurance", n("500"), n("6000"), 12, today.plusMonths(6), null, today.plusYears(2), null));
        ledgerService.addBucket(new LedgerService.BucketData("Old plan", n("200"), null, null, null, null, null, n("100")));
        var view = ledgerService.get();
        Map<String, Long> id = view.buckets().stream().collect(Collectors.toMap(b -> b.name(), b -> b.id()));
        ledgerService.fund(new LedgerService.FundRequest(bank, n("1700"), null, today.minusDays(60)));        // the plan: 1000 + 500 + 200
        ledgerService.fund(new LedgerService.FundRequest(bank, n("2000"), null, today.minusDays(30)));        // the plan and 300 extra to the buffer
        ledgerService.fund(new LedgerService.FundRequest(bank, n("900"), Map.of(id.get("Rent"), n("600"), id.get("Insurance"), n("300")), today.minusDays(5)));
        ledgerService.pay(new LedgerService.PayRequest(id.get("Rent"), n("1000"), today.minusDays(25), bills.getId(), "March rent"));
        ledgerService.creditInterest(new LedgerService.InterestRequest(n("42.50"), today.minusDays(3), interest.getId()));
        ledgerService.transfer(new LedgerService.TransferRequest(id.get("Insurance"), id.get("Rent"), n("100")));
        ledgerService.close(id.get("Old plan"), new LedgerService.CloseRequest(null));
        var before = ledgerService.get();
        Map<String, BigDecimal> bankBefore = balances();
        int entriesBefore = (int) bucketEntries.count();
        byte[] file = export.build();
        wipe();

        ImportService.Report done = importer.run(new ByteArrayInputStream(file), true);

        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        var after = ledgerService.get();
        assertEquals(0, before.balance().compareTo(after.balance()), "the Ledger's balance");
        assertEquals(before.buckets().stream().collect(Collectors.toMap(b -> b.name(), b -> b.balance().setScale(2))).toString(),
                after.buckets().stream().collect(Collectors.toMap(b -> b.name(), b -> b.balance().setScale(2))).toString(), "every open bucket's balance");
        assertEquals(List.of("Old plan"), after.closed().stream().map(LedgerService.ClosedBucketView::name).toList());
        var rent = after.buckets().stream().filter(b -> b.name().equals("Rent")).findFirst().orElseThrow();
        var rentBefore = before.buckets().stream().filter(b -> b.name().equals("Rent")).findFirst().orElseThrow();
        assertEquals(rentBefore.previousPaymentDate(), rent.previousPaymentDate(), "the bucket's dates are as the file has them");
        assertEquals(rentBefore.nextPaymentDate(), rent.nextPaymentDate());
        assertEquals(entriesBefore, bucketEntries.count(), "the whole history");
        Map<String, BigDecimal> bankAfter = balances();
        for (String name : bankBefore.keySet()) {
            assertEquals(0, bankBefore.get(name).compareTo(bankAfter.get(name)), name + ": " + bankBefore.get(name) + " vs " + bankAfter.get(name));
        }
        assertTrue(transactionService.list().stream().anyMatch(t -> "March rent".equals(t.getNote())), "the bill's note");
    }

    @Test
    void recurringDepositsComeBackOpenAndClosedWithTheirDepositsAndInterest() throws Exception {
        systemCategories.ensureAll();
        var income = categoryService.create(new CategoryService.CategoryData("Investment Income", CategoryKind.INCOME, null));
        categoryService.create(new CategoryService.CategoryData("Bank Interest", CategoryKind.INCOME, null));
        setupService.createAll(List.of(new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("100000"), null, null)));
        Long bank = accounts.findAll().get(0).getId();
        recurringDepositService.setUp();
        recurringDepositService.open(new RecurringDepositService.OpenData("Home RD", LocalDate.of(2025, 1, 5), 24, n("5000"), n("6.5"), n("10000")));
        recurringDepositService.open(new RecurringDepositService.OpenData("Old RD", LocalDate.of(2024, 1, 5), 12, n("1000"), n("7"), null));
        var view = recurringDepositService.get();
        Long home = view.open().stream().filter(d -> d.name().equals("Home RD")).findFirst().orElseThrow().id();
        Long old = view.open().stream().filter(d -> d.name().equals("Old RD")).findFirst().orElseThrow().id();
        for (int m = 0; m < 3; m++) {
            recurringDepositService.deposit(home, new RecurringDepositService.DepositRequest(bank, n("5000"), LocalDate.of(2025, 2 + m, 5)));
            recurringDepositService.deposit(old, new RecurringDepositService.DepositRequest(bank, n("1000"), LocalDate.of(2024, 2 + m, 5)));
        }
        recurringDepositService.close(old, new RecurringDepositService.CloseRequest(bank, n("3200.50"), LocalDate.of(2024, 6, 1)));
        var before = recurringDepositService.get();
        Map<String, BigDecimal> balancesBefore = balances();
        int transactionCount = (int) transactions.count();
        byte[] file = export.build();
        wipe();

        ImportService.Report done = importer.run(new ByteArrayInputStream(file), true);

        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        var after = recurringDepositService.get();
        assertEquals(0, before.deposited().compareTo(after.deposited()));
        assertEquals(1, after.open().size());
        assertEquals(0, before.open().get(0).deposited().compareTo(after.open().get(0).deposited()), "10,000 already in it plus three deposits");
        assertEquals(3, after.open().get(0).instalments());
        assertEquals(1, after.closed().size());
        assertEquals(0, n("3200.50").compareTo(after.closed().get(0).received()));
        assertEquals(LocalDate.of(2024, 6, 1), after.closed().get(0).closedOn());
        assertEquals(transactionCount, transactions.count(), "the deposits, the closing transfer and the interest, each once");
        Map<String, BigDecimal> balancesAfter = balances();
        for (String name : balancesBefore.keySet()) {
            assertEquals(0, balancesBefore.get(name).compareTo(balancesAfter.get(name)), name + ": " + balancesBefore.get(name) + " vs " + balancesAfter.get(name));
        }
    }

    @Test
    void writeOffsComeBackWithTheirDayAmountAndReason() throws Exception {
        systemCategories.ensureAll();
        setupService.createAll(List.of(new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("50000"), null, null)));
        Long bank = accounts.findAll().get(0).getId();
        var ravi = loanService.create(new LoanService.BorrowerData("Imp Ravi", BigDecimal.ZERO));
        var sam = loanService.create(new LoanService.BorrowerData("Imp Sam", BigDecimal.ZERO));
        Long given = systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), collected = systemCategories.require(SystemCategories.LOANS_COLLECTED).getId();
        transactionService.create(new TransactionData(LocalDate.of(2026, 1, 5), TransactionKind.EXPENSE, bank, null, n("5000"), null, given, "Lent", null, ravi.getId(), List.of()));
        transactionService.create(new TransactionData(LocalDate.of(2026, 1, 6), TransactionKind.EXPENSE, bank, null, n("100"), null, given, "Lent", null, sam.getId(), List.of()));
        transactionService.create(new TransactionData(LocalDate.of(2026, 2, 6), TransactionKind.INCOME, null, bank, n("100.50"), null, collected, "Back", null, sam.getId(), List.of()));
        loanService.restoreWriteOff(ravi.getId(), n("4000"), LocalDate.of(2026, 3, 1), "He lost his job");
        loanService.restoreWriteOff(sam.getId(), n("-0.50"), LocalDate.of(2026, 3, 2), "rounding");
        var before = loanService.summary();
        Map<String, BigDecimal> balancesBefore = balances();
        byte[] file = export.build();
        wipe();

        ImportService.Report done = importer.run(new ByteArrayInputStream(file), true);

        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        var after = loanService.summary();
        for (String name : List.of("Imp Ravi", "Imp Sam")) {
            var was = before.borrowers().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
            var is = after.borrowers().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
            assertEquals(0, was.writtenOff().compareTo(is.writtenOff()), name + " written off");
            assertEquals(0, was.owed().compareTo(is.owed()), name + " owed");
        }
        assertEquals(List.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2)), writeOffRepository.findAll().stream().map(w -> w.getWrittenOn()).sorted().toList());
        Map<String, BigDecimal> balancesAfter = balances();
        for (String name : balancesBefore.keySet()) {
            assertEquals(0, balancesBefore.get(name).compareTo(balancesAfter.get(name)), name + ": " + balancesBefore.get(name) + " vs " + balancesAfter.get(name));
        }
    }

    @Test
    void jewelleryComesBackWithItsItemsTransfersAndBalance() throws Exception {
        systemCategories.ensureAll();
        categoryService.create(new CategoryService.CategoryData("Investment Income", CategoryKind.INCOME, null));
        categoryService.create(new CategoryService.CategoryData("Other Expense", CategoryKind.EXPENSE, null));
        setupService.createAll(List.of(new SetupService.SetupAccount("Imp Bank", AccountType.BANK, null, null, n("500000"), null, null)));
        Long bank = accounts.findAll().get(0).getId();
        jewelleryService.setUp();
        jewelleryService.add(new JewelleryService.ItemData("Gold", "Mother's chain", "ORNAMENT", n("20"), n("100000"), null));      // already owned
        jewelleryService.add(new JewelleryService.ItemData("Gold", "Coin", "COIN", n("10"), n("50000"), bank));                       // bought
        jewelleryService.add(new JewelleryService.ItemData("Silver", "Plates", "OTHER", n("500"), n("30000"), bank));                  // bought, then sold whole
        var view = jewelleryService.get();
        Long chain = view.metals().stream().flatMap(m -> m.items().stream()).filter(i -> i.name().equals("Mother's chain")).findFirst().orElseThrow().id();
        Long plates = view.metals().stream().flatMap(m -> m.items().stream()).filter(i -> i.name().equals("Plates")).findFirst().orElseThrow().id();
        jewelleryService.sell(chain, new JewelleryService.SaleRequest(bank, n("35000"), n("5")));       // a part, at a profit
        jewelleryService.sell(plates, new JewelleryService.SaleRequest(bank, n("25000"), null));        // the whole, at a loss
        var before = jewelleryService.get();
        Map<String, BigDecimal> balancesBefore = balances();
        int transactionCount = (int) transactions.count();
        byte[] file = export.build();
        wipe();

        ImportService.Report done = importer.run(new ByteArrayInputStream(file), true);

        assertEquals(List.of(), done.steps().stream().flatMap(s -> s.messages().stream()).toList());
        var after = jewelleryService.get();
        assertEquals(0, before.boughtPrice().compareTo(after.boughtPrice()), "what the items cost");
        assertEquals(before.metals().stream().flatMap(m -> m.items().stream()).map(i -> i.name() + i.weight().stripTrailingZeros()).sorted().toList(),
                after.metals().stream().flatMap(m -> m.items().stream()).map(i -> i.name() + i.weight().stripTrailingZeros()).sorted().toList());
        assertTrue(after.metals().stream().flatMap(m -> m.items().stream()).anyMatch(i -> i.name().equals("Coin") && i.paidByAccount()), "the coin is linked to its payment");
        assertEquals(transactionCount, transactions.count(), "transfers and the profit and loss, once each");
        Map<String, BigDecimal> balancesAfter = balances();
        for (String name : balancesBefore.keySet()) {
            assertEquals(0, balancesBefore.get(name).compareTo(balancesAfter.get(name)), name + ": " + balancesBefore.get(name) + " vs " + balancesAfter.get(name));
        }
    }
}
