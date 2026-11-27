package com.learning.spring.financialdashboard;

import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Note;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.NoteRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import com.learning.spring.financialdashboard.service.AccountService;
import com.learning.spring.financialdashboard.service.BackupService;
import com.learning.spring.financialdashboard.service.ExportService;
import com.learning.spring.financialdashboard.service.JewelleryService;
import com.learning.spring.financialdashboard.service.LedgerService;
import com.learning.spring.financialdashboard.service.LoanService;
import com.learning.spring.financialdashboard.service.NetWorthService;
import com.learning.spring.financialdashboard.service.PortfolioService;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.service.SystemCategories;
import com.learning.spring.financialdashboard.startup.CategorySeeder;
import jakarta.servlet.Filter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real scripts/load-demo-data.py (four years of history) against the real application, through a pipe instead
 * of a network (src/test/resources/demo_bridge.py), then checks what it produced and that the rest of the app copes with
 * that much data: net worth history, the Excel export, a checked backup and the busiest API calls. Skipped when
 * python3 or the script is not available.
 */
@SpringBootTest
class DemoDataTest {

    private static final int YEARS = Integer.getInteger("demo.years", 4);   // the script's default is 5; 4 keeps the test quick

    @Autowired
    WebApplicationContext context;
    @Autowired
    Filter springSecurityFilterChain;
    @Autowired
    AccountService accountService;
    @Autowired
    NetWorthService netWorth;
    @Autowired
    LedgerService ledger;
    @Autowired
    LoanService loans;

    @Autowired
    com.learning.spring.financialdashboard.service.SplitService settlements;
    @Autowired
    PortfolioService portfolios;
    @Autowired
    JewelleryService jewellery;
    @Autowired
    ExportService export;
    @Autowired
    com.learning.spring.financialdashboard.service.BudgetService budgetService;
    @Autowired
    BackupService backups;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    CategorySeeder seeder;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    NoteRepository notes;
    @Autowired
    com.learning.spring.financialdashboard.repository.ReminderRepository reminders;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;
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
    PortfolioDetailRepository portfolioDetails;
    @Autowired
    VehicleDetailRepository vehicles;
    @Autowired
    CreditCardDetailRepository creditCards;
    @Autowired
    BorrowerRepository borrowers;

    @Autowired
    com.learning.spring.financialdashboard.repository.SplitSettlementRepository splitSettlements;
    @Autowired
    com.learning.spring.financialdashboard.repository.DebtDetailRepository debtDetails;
    @Autowired
    AppSettingRepository settings;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        wipe();
        categories.deleteAll();
        seeder.seedIfEmpty();            // what the app does on its first start
        systemCategories.ensureAll();
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        transactions.deleteAll();
        bucketEntries.deleteAll();
        buckets.deleteAll();
        ledgers.deleteAll();
        ringFences.deleteAll();
        emergencyFunds.deleteAll();
        snapshots.deleteAll();
        holdings.deleteAll();
        portfolioDetails.deleteAll();
        vehicles.deleteAll();
        creditCards.deleteAll();
        debtDetails.deleteAll();
        splitSettlements.deleteAll();
        borrowers.deleteAll();
        notes.deleteAll();
        reminders.deleteAll();
        accounts.deleteAll();
        settings.deleteAll();
    }

    private record Run(int exitCode, List<String> output, int requests) {
    }

    private static Path script() {
        return Path.of(System.getProperty("demo.script", "scripts/load-demo-data.py"));
    }

    private static boolean pythonAvailable() {
        try {
            return new ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private MockHttpServletResponse perform(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .header("X-Requested-With", "FinancialDashboard");   // the real script sends it
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    /** Starts the script and answers each of its API calls with the real application until it finishes. */
    private Run runScript() throws Exception {
        Path bridge;
        try {
            bridge = Path.of(getClass().getResource("/demo_bridge.py").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        Process process = new ProcessBuilder("python3", "-I", bridge.toString(), script().toString(), String.valueOf(YEARS))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        List<String> output = new ArrayList<>();
        int requests = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith("@@REQ ")) {
                    output.add(line);
                    continue;
                }
                requests++;
                String[] parts = line.substring("@@REQ ".length()).split(" ", 3);   // METHOD, path, body (may be empty)
                MockHttpServletResponse response = perform(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
                out.write(response.getStatus() + " " + response.getContentAsString(StandardCharsets.UTF_8).replace("\n", " "));
                out.newLine();
                out.flush();
            }
        }
        return new Run(process.waitFor(), output, requests);
    }

    @Test
    void fourYearsOfDemoDataLoadCleanlyAndTheWholeAppStillWorks() throws Exception {
        assumeTrue(Files.exists(script()), "scripts/load-demo-data.py not found from " + Path.of("").toAbsolutePath());
        assumeTrue(pythonAvailable(), "python3 is not available");

        long loadStart = System.nanoTime();
        Run run = runScript();
        long loadMs = (System.nanoTime() - loadStart) / 1_000_000;
        String output = String.join("\n", run.output());

        // ---- the script itself: every step ran, nothing was refused
        assertEquals(0, run.exitCode(), output);
        assertFalse(output.contains("Skipped:"), output);
        assertEquals(6, run.output().stream().filter(l -> l.contains("Loaded:")).count(), output);

        // ---- accounts and four years of transactions
        List<Transaction> all = transactions.findAll();
        LocalDate today = LocalDate.now();
        LocalDate first = all.stream().map(Transaction::getDate).min(LocalDate::compareTo).orElseThrow();
        assertTrue(all.size() > 3000, "transactions: " + all.size());
        assertTrue(ChronoUnit.MONTHS.between(first, today) >= 12 * YEARS - 2, "history starts " + first);
        // 2 to 3 everyday transactions on EVERY day of the history, not just on some days
        java.util.Map<LocalDate, Long> perDay = all.stream().collect(Collectors.groupingBy(Transaction::getDate, Collectors.counting()));
        List<LocalDate> thinDays = first.datesUntil(today.plusDays(1)).filter(d -> perDay.getOrDefault(d, 0L) < 2).toList();
        assertTrue(thinDays.isEmpty(), "days with fewer than 2 transactions: " + thinDays.stream().limit(10).toList());
        assertTrue(perDay.values().stream().allMatch(c -> c <= 20), "no day should be a pile-up: " + perDay.values().stream().max(Long::compare));
        assertTrue(accounts.count() >= 15, "accounts: " + accounts.count());

        List<AccountSummary> summaries = accountService.listSummaries();
        assertTrue(summaries.stream().filter(s -> s.type() == AccountType.BANK || s.type() == AccountType.CASH || s.type() == AccountType.WALLET)
                .allMatch(s -> s.balance().signum() >= 0), "no bank, cash or wallet account should be overdrawn: " + summaries);
        Long ledgerAccount = ledger.get().accountId();   // a Ledger payment is the Ledger's own, filed by its bucket, not by category
        long uncategorised = all.stream().filter(t -> t.getKind() == TransactionKind.EXPENSE && t.getCategoryId() == null
                && !ledgerAccount.equals(t.getFromAccountId())).count();
        assertEquals(0, uncategorised, "every ordinary expense lands in one of the short list of starter categories (or a fixed one)");
        AccountSummary carLoan = summaries.stream().filter(s -> s.name().equals("Car Loan")).findFirst().orElseThrow();
        assertTrue(carLoan.balance().signum() > 0);
        assertTrue(debtDetails.existsById(carLoan.id()), "the car loan is a fixed loan with a monthly EMI");
        Long payments = systemCategories.require(SystemCategories.LIABILITY_PAYMENT).getId();
        assertTrue(all.stream().anyMatch(t -> payments.equals(t.getCategoryId()) && carLoan.id().equals(t.getToAccountId())
                && t.getKind() == TransactionKind.EXPENSE), "the EMIs are Liability Payments expenses, as the Pay button records them");

        // ---- goals, loans and the Ledger
        assertTrue(summaries.stream().filter(s -> s.name().equals("Vacation Fund")).findFirst().orElseThrow().ringFenced());
        assertTrue(creditCards.findAll().stream().anyMatch(c -> c.getDueDay() != null), "the card has a bill day, so Coming up has something to show");
        assertTrue(creditCards.findAll().stream().anyMatch(c -> c.getStatementDay() != null), "and a statement day, which is a different day");
        // ---- what the Spending Review and Budgets views are meant to find
        assertTrue(all.stream().anyMatch(t -> "Laptop".equals(t.getNote()) && t.getAmount().compareTo(new BigDecimal("52000")) == 0), "a large one-off this month");
        assertTrue(all.stream().anyMatch(t -> "Dental work".equals(t.getNote())), "a large Health bill last month");
        assertTrue(all.stream().anyMatch(t -> "Health check-up".equals(t.getNote())), "an early Health bill last month, so Health falls this month");
        assertTrue(all.stream().filter(t -> "Stationery and supplies".equals(t.getNote()) && t.getAmount().compareTo(new BigDecimal("500")) < 0).count() >= Math.min(5, today.getDayOfMonth()),
                "six small Household expenses this month, for the frequent small expenses insight");
        assertTrue(all.stream().anyMatch(t -> "Laptop".equals(t.getNote()) && t.getTags().contains("laptop") && t.getTags().contains("big-purchase")), "hand-written tags are kept");
        assertTrue(all.stream().filter(t -> t.getKind() == TransactionKind.EXPENSE && t.getNote() != null).allMatch(t -> !t.getTags().isEmpty()),
                "every expense with a note was tagged by the app");
        var limits = budgetService.list();
        assertTrue(limits.stream().anyMatch(b -> b.getCategoryId() == null), "a limit on all spending");
        assertTrue(limits.stream().anyMatch(b -> b.getCategoryId() != null && categories.findById(b.getCategoryId()).orElseThrow().getParentId() != null),
                "a limit on a sub category");
        assertTrue(limits.size() >= 1 && limits.size() <= 7, "budgets: " + limits.size());
        assertTrue(ledger.get().configured());
        assertEquals(5, ledger.get().buckets().size());   // four bills and the Buffer
        LoanService.Summary loanSummary = loans.summary();
        assertEquals(3, loanSummary.borrowers().size());   // Ravi, Meera and Teja (the friend the shared expenses are split with)
        assertEquals(0, new BigDecimal("30000").compareTo(loanSummary.borrowers().stream()
                .filter(b -> b.name().equals("Ravi")).findFirst().orElseThrow().owed()));   // 30,000 + 20,000 lent, 20,000 back
        assertEquals(0, new BigDecimal("10000").compareTo(loanSummary.borrowers().stream()
                .filter(b -> b.name().equals("Meera")).findFirst().orElseThrow().owed()));  // 15,000 lent, 5,000 back
        assertEquals(0, new BigDecimal("990").compareTo(loanSummary.borrowers().stream()
                .filter(b -> b.name().equals("Teja")).findFirst().orElseThrow().owed()));   // shares 3,000 + 1,200 + 590, paid back 3,000 + 800
        assertEquals(3, settlements.list().stream().map(s -> s.incomeTransactionId()).distinct().count(), "two payments cleared shared expenses and one paid back a bill");
        assertEquals(0, new BigDecimal("850").compareTo(loanSummary.borrowers().stream()
                .filter(b -> b.name().equals("Teja")).findFirst().orElseThrow().youOwe()));   // owes Teja 900 + 350, paid back 400

        // ---- diary: notes across the years, every one tagged
        List<Note> diary = notes.findAll();
        Set<String> tagWords = diary.stream().flatMap(n -> n.getTags().stream()).collect(Collectors.toSet());
        java.util.Map<LocalDate, Long> notesPerDay = diary.stream().collect(Collectors.groupingBy(Note::getDate, Collectors.counting()));
        LocalDate firstNote = diary.stream().map(Note::getDate).min(LocalDate::compareTo).orElseThrow();
        assertEquals(ChronoUnit.DAYS.between(firstNote, today) + 1, notesPerDay.size(), "every day has a note");
        assertTrue(notesPerDay.values().stream().allMatch(c -> c == 1 || c == 2), "one or two notes on each day");
        assertTrue(diary.stream().anyMatch(n -> n.getDate().equals(today) && n.getBody().contains("\n\n")), "a long multi-paragraph note today, for the reading view");
        assertTrue(notesPerDay.values().stream().filter(c -> c == 2).count() >= 200, "many days have two");
        assertTrue(diary.size() > 365 * (YEARS - 1) * 1.2, "notes: " + diary.size());
        assertTrue(diary.stream().allMatch(n -> !n.getTags().isEmpty()));
        assertTrue(tagWords.size() >= 10, "tags: " + tagWords);
        assertTrue(ChronoUnit.MONTHS.between(diary.stream().map(Note::getDate).min(LocalDate::compareTo).orElseThrow(), today) >= 12 * YEARS - 2);

        // ---- reminders: done ones through the years, a few open ones around today
        List<com.learning.spring.financialdashboard.model.Reminder> mine = reminders.findAll();
        long doneCount = mine.stream().filter(com.learning.spring.financialdashboard.model.Reminder::isDone).count();
        List<com.learning.spring.financialdashboard.model.Reminder> open = mine.stream().filter(r -> !r.isDone() && r.getRepeat() == com.learning.spring.financialdashboard.model.Repeat.NONE).toList();
        List<com.learning.spring.financialdashboard.model.Reminder> repeating = mine.stream().filter(r -> r.getRepeat() != com.learning.spring.financialdashboard.model.Repeat.NONE).toList();
        List<com.learning.spring.financialdashboard.model.Reminder> yearlies = repeating.stream().filter(r -> r.getRepeat() == com.learning.spring.financialdashboard.model.Repeat.YEARLY).toList();
        assertEquals(4, repeating.stream().filter(r -> r.getRepeat() == com.learning.spring.financialdashboard.model.Repeat.MONTHLY).count(), "monthly reminders");
        assertEquals(2, repeating.stream().filter(r -> r.getRepeat() == com.learning.spring.financialdashboard.model.Repeat.WEEKLY).count(), "weekly reminders");
        assertTrue(repeating.stream().noneMatch(com.learning.spring.financialdashboard.model.Reminder::isDone));
        assertEquals(6, yearlies.size(), "reminders that repeat every year");
                assertTrue(yearlies.stream().allMatch(r -> r.getDate().getYear() < today.getYear()), "dated with a first year in the past");
        assertTrue(yearlies.stream().anyMatch(r -> r.getDate().getMonth() == today.plusDays(4).getMonth() && r.getDate().getDayOfMonth() == today.plusDays(4).getDayOfMonth()), "a birthday in four days");
        assertTrue(doneCount >= 2 * (12 * YEARS - 3), "done reminders: " + doneCount);
        assertEquals(18, open.size(), "open reminders: 8 for the bar and 10 random ones later");
        assertEquals(2, open.stream().filter(r -> r.getDate().isBefore(today)).count(), "two are late");
        assertTrue(open.stream().anyMatch(r -> r.getDate().equals(today)), "one is due today");
        assertTrue(open.stream().filter(r -> !r.getDate().isBefore(today) && !r.getDate().isAfter(today.plusDays(7))).count() >= 3, "some are in the Coming up window");
        assertTrue(mine.stream().filter(com.learning.spring.financialdashboard.model.Reminder::isDone).allMatch(r -> r.getDoneOn() != null));

        // ---- investments with a monthly value history
        for (PortfolioKind kind : PortfolioKind.values()) {
            assertEquals(4, portfolios.get(kind).holdings().size());
            assertTrue(portfolios.stats(kind).overall().size() >= 12 * YEARS - 2, kind + " history points");
        }

        // ---- jewellery: gold, silver, platinum and diamond pieces, one bought from the bank, part of the loose gold sold
        JewelleryService.View jewelleryView = jewellery.get();
        assertTrue(jewelleryView.configured());
        assertEquals(List.of("Gold", "Silver", "Diamond", "Platinum"), jewelleryView.metals().stream().map(JewelleryService.MetalView::metal).toList());
        assertEquals(13, jewelleryView.metals().stream().mapToInt(m -> m.items().size()).sum(), "twelve pieces and the new chain");
        assertTrue(jewelleryView.boughtPrice().signum() > 0);

        // ---- the rest of the app against this much data
        long t = System.nanoTime();
        List<NetWorthService.Point> history = netWorth.history(60);
        long netWorthMs = (System.nanoTime() - t) / 1_000_000;
        assertEquals(60, history.size());
        assertTrue(history.get(history.size() - 1).netWorth().signum() > 0);

        t = System.nanoTime();
        byte[] workbook = export.build();
        long exportMs = (System.nanoTime() - t) / 1_000_000;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            assertEquals(all.size(), wb.getSheet("Transactions").getLastRowNum());
            assertEquals(diary.size(), wb.getSheet("Notes").getLastRowNum());
            assertEquals(1, wb.getSheet("Net worth").getDrawingPatriarch().getCharts().size());
            assertEquals(2, wb.getSheet("Income & Expenses").getDrawingPatriarch().getCharts().size());
        }

        t = System.nanoTime();
        BackupService.Status backup = backups.backupNow("demo data test");
        long backupMs = (System.nanoTime() - t) / 1_000_000;
        assertTrue(backup.ok(), backup.banner());

        t = System.nanoTime();
        MockHttpServletResponse list = perform("GET", "/api/transactions", "");
        long listMs = (System.nanoTime() - t) / 1_000_000;
        assertEquals(200, list.getStatus());

        System.out.printf("DemoDataTest: %d requests loaded in %d ms; %d transactions (%d KB as JSON), %d notes; "
                        + "net worth %d ms, Excel export %d ms (%d KB), backup %d ms, transactions list %d ms%n",
                run.requests(), loadMs, all.size(), list.getContentAsByteArray().length / 1024, diary.size(), netWorthMs, exportMs,
                workbook.length / 1024, backupMs, listMs);
        assertTrue(netWorthMs < 20_000 && exportMs < 30_000 && backupMs < 30_000 && listMs < 10_000,
                "something got slow with four years of data");
    }
}
