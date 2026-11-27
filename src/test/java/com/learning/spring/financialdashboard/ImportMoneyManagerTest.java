package com.learning.spring.financialdashboard;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.AppCredentialRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.ExchangeRateRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService;
import com.learning.spring.financialdashboard.service.SystemCategories;
import com.learning.spring.financialdashboard.startup.CategorySeeder;
import jakarta.servlet.Filter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real scripts/import-money-manager.py (`plan`, then `apply` through a pipe, src/test/resources/import_bridge.py) on a small
 * Money Manager workbook built here, against the real application, and checks what it saved. Skipped without python3 or the script.
 */
@SpringBootTest
class ImportMoneyManagerTest {

    private static final String PASSWORD = "import-test-password";
    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);

    @Autowired
    WebApplicationContext context;
    @Autowired
    Filter springSecurityFilterChain;
    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accounts;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    CategoryRepository categories;
    @Autowired
    CreditCardDetailRepository creditCards;
    @Autowired
    ExchangeRateRepository rates;
    @Autowired
    com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository holdingSnapshots;
    @Autowired
    com.learning.spring.financialdashboard.repository.HoldingRepository holdingRepository;
    @Autowired
    com.learning.spring.financialdashboard.repository.PortfolioDetailRepository portfolioDetails;
    @Autowired
    com.learning.spring.financialdashboard.repository.RecurringDepositRepository recurringDepositRepository;
    @Autowired
    com.learning.spring.financialdashboard.repository.RecurringDepositDetailRepository recurringDepositDetailRepository;
    @Autowired
    AppCredentialRepository credentials;
    @Autowired
    com.learning.spring.financialdashboard.service.LedgerService ledgerService;
    @Autowired
    com.learning.spring.financialdashboard.service.LoanService loansService;
    @Autowired
    com.learning.spring.financialdashboard.repository.BudgetRepository budgetRepository;
    @Autowired
    com.learning.spring.financialdashboard.repository.NoteRepository noteRepository;
    @Autowired
    com.learning.spring.financialdashboard.service.NoteService noteService;
    @Autowired
    com.learning.spring.financialdashboard.repository.ReminderRepository reminderRepository;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    com.learning.spring.financialdashboard.service.PortfolioService portfolioService;
    @Autowired
    com.learning.spring.financialdashboard.service.RecurringDepositService recurringDeposits;
    @Autowired
    CategorySeeder seeder;
    @Autowired
    com.learning.spring.financialdashboard.repository.BorrowerRepository borrowers;
    @Autowired
    com.learning.spring.financialdashboard.repository.BucketEntryRepository ledgerEntries;
    @Autowired
    com.learning.spring.financialdashboard.repository.BucketRepository ledgerBuckets;
    @Autowired
    com.learning.spring.financialdashboard.repository.LedgerDetailRepository ledgerDetails;
    @Autowired
    com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository emergencyFundDetails;

    MockMvc mvc;
    Path dir;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        wipe();
        categories.deleteAll();
        seeder.seedIfEmpty();   // a new database starts with the starter categories
        systemCategories.ensureAll();
        dir = Files.createTempDirectory("import-money-manager");
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        transactions.deleteAll();
        borrowers.deleteAll();
        budgetRepository.deleteAll();
        noteRepository.deleteAll();
        reminderRepository.deleteAll();
        ledgerEntries.deleteAll();
        ledgerBuckets.deleteAll();
        ledgerDetails.deleteAll();
        emergencyFundDetails.deleteAll();
        holdingSnapshots.deleteAll();
        holdingRepository.deleteAll();
        portfolioDetails.deleteAll();
        recurringDepositRepository.deleteAll();
        recurringDepositDetailRepository.deleteAll();
        creditCards.deleteAll();
        accounts.deleteAll();
        rates.deleteAll();
        credentials.deleteAll();
    }

    private static Path script() {
        return Path.of(System.getProperty("import.script", "scripts/import-money-manager.py"));
    }

    private static boolean pythonAvailable() {
        try {
            return new ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static double serial(LocalDate day) {
        return ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), day);
    }

    /** The columns of an export: Date, Account, Category, Subcategory, Note, INR, Income/Expense, Tag, Description, Amount, Currency, Account. */
    private static void row(XSSFSheet sheet, int day, String account, String category, String sub, String note, double inr, String type, double amount, String currency) {
        Row r = sheet.createRow(sheet.getLastRowNum() + 1);
        r.createCell(0).setCellValue(serial(DAY.plusDays(day)));
        r.createCell(1).setCellValue(account + " ");
        r.createCell(2).setCellValue(category);
        if (!sub.isEmpty()) {
            r.createCell(3).setCellValue(sub);
        }
        r.createCell(4).setCellValue(note);
        r.createCell(5).setCellValue(inr);
        r.createCell(6).setCellValue(type);
        r.createCell(9).setCellValue(amount);
        r.createCell(10).setCellValue(currency);
    }

    private Path workbook() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet s = wb.createSheet("Money Manager");
            String[] head = {"Date", "Account", "Category", "Subcategory", "Note", "INR", "Income/Expense", "Tag", "Description", "Amount", "Currency", "Account"};
            Row h = s.createRow(0);
            for (int i = 0; i < head.length; i++) {
                h.createCell(i).setCellValue(head[i]);
            }
            row(s, 0, "HDFC", "💰 Salary", "", "March salary", 100000, "Income", 100000, "INR");
            row(s, 1, "HDFC", "🍜 Food", "🍽️ Eating out", "Lunch", 200, "Expense", 200, "INR");
            row(s, 2, "HDFC", "🍜 Food", "🍉 Fruits", "Returned fruit", -50, "Expense", -50, "INR");          // money given back: income
            row(s, 3, "HDFC", "Cash", "", "ATM", 3000, "Transfer-Out", 3000, "INR");
            row(s, 4, "HDFC", "Dollars", "", "Buying dollars", 8000, "Transfer-Out", 100, "USD");             // 8000 rupees out, 100 dollars in
            row(s, 5, "Dollars", "Trips", "conversion", "Conversion to Dong", 4800, "Expense", 50, "USD");  // 96 rupees a dollar that day
            row(s, 5, "Dollars", "Trips", "conversion", "Dollar conversion fee", 96, "Expense", 96, "INR");  // in rupees on the dollar account: 1 dollar
            row(s, 6, "Vietnamese Dong", "🍜 Food", "🍽️ Eating out", "Pho", 360, "Expense", 100000, "VND");
            row(s, 7, "Kiwi Credit Card", "🍜 Food", "🍽️ Eating out", "Dinner", 500, "Expense", 500, "INR");
            row(s, 8, "HDFC", "Mid Cap Mutual fund", "", "SIP", 1000, "Transfer-Out", 1000, "INR");
            row(s, 9, "HDFC", "Loan", "Ravi", "Lent", 1000, "Expense", 1000, "INR");
            row(s, 10, "HDFC", "Loan", "Ravi", "Part repaid", -400, "Expense", -400, "INR");                  // a repayment, not a refund
            row(s, 11, "Amazon Wallet", "Modified Bal.", "", "Difference", 85.01, "Income", 85.01, "INR");
            row(s, 0, "HDFC", "Savings", "", "Balance amount", 1000, "Income", 1000, "INR");                      // row 15: really an opening balance
            row(s, 3, "HDFC", "Bank expenses", "RD", "", 2000, "Expense", 2000, "INR");                         // row 16: really money moved to a Recurring Deposit
            row(s, 200, "HDFC", "Food", "Eating out", "After the cut-off", 99, "Expense", 99, "INR");           // row 17: later than --until
            row(s, 4, "HDFC", "Bank expenses", "E.M.I", "Loan EMI", 100, "Expense", 100, "INR");                 // row 18: a payment into the loan
            row(s, 9, "HDFC", "Mutual funds", "", "Mid cap sold", 6000, "Income", 6000, "INR");                   // row 19: the fund sold for what it was worth
            row(s, 10, "HDFC", "Emergency Funds", "", "Starting check", 2500, "Transfer-Out", 2500, "INR");              // row 20: money into the app's Emergency Fund
            row(s, 11, "HDFC", "Monthly Expense", "", "May contribution", 3000, "Transfer-Out", 3000, "INR");           // row 21: a funding of the app's Ledger
            row(s, 12, "Dollars", "Trips", "conversion", "Conversion to Dong 2", 960, "Expense", 10, "USD");             // row 22: dollars changed into Dong, saved as a transfer with the Dong that arrived
            row(s, 13, "HDFC", "Cash", "", "Online to Ravi", 400, "Transfer-Out", 400, "INR");                                 // row 23: a transfer row that is really money lent to Ravi
            row(s, 14, "Monthly Expense", "Chit Funds", "Kallur", "Chit Fund Installment", 1000, "Expense", 1000, "INR");   // row 24: a bill paid from the Ledger
            row(s, 14, "Monthly Expense", "Bank Interest", "", "Interest Credited", 5, "Income", 5, "INR");                  // row 25: interest credited to the Ledger
            Path file = dir.resolve("mm.xlsx");
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
            return file;
        }
    }

    private int exitOf(Process p) throws InterruptedException {
        return p.waitFor();
    }

    private String plan(Path file) throws Exception {
        Files.createDirectories(dir.resolve("review"));
        Files.writeString(dir.resolve("review/row-overrides.csv"), "file_row,action,target,why\n15,OPENING,,the balance on the first day\n23,EXPENSE,Loan > Ravi,sent to Ravi online\n22,TRANSFER,Vietnamese Dong=250000,dollars changed into Dong\n16,TRANSFER,Recurring Deposit,an RD\n18,PAYMENT,Personal Loan,the EMI\n19,TRANSFER_FROM,Mid Cap Mutual fund,sold\n");
        Files.writeString(dir.resolve("review/category-renames.csv"), "kind,from_category,from_sub,to_category,to_sub,from_date,to_date\nEXPENSE,Food,Eating out,Food,Dining out,,\nEXPENSE,Trips,*,Dollar Trip,*,2026-03-06,2026-03-06\n");
        Files.writeString(dir.resolve("review/account-map.csv"), "money_manager_account,app_name,type,currency,credit_limit,opening_balance,emi_amount,"
                + "rd_start_date,rd_term_months,rd_monthly_amount,rd_interest_rate,backfill_months,backfill_amount,backfill_from,rows_in_the_file,target_amount,due_day,statement_day\n"
                + "Personal Loan,Personal Loan,DEBT,INR,,1000,100,,,,,,,,0\n"                          // the owed amount on the first day, and the EMI
                + "Mid Cap Mutual fund,Mid Cap Fund,HOLDING_MUTUAL_FUNDS,INR,,4000,,,,,,,,,0\n"       // a fund inside the Mutual Funds account, holding 4000 before the file
                + "Recurring Deposit,Recurring Deposit,HOLDING_RECURRING_DEPOSIT,INR,,500,,,12,2000,6,3,700,HDFC,0\n"
                + "Dash Card,Dash Card,CREDIT_CARD,INR,5000,0,,,,,,,,,0,,9,20\n"                      // accounts that are only in the dashboard's own database (extra-plan-rows.csv)
                + "Dash Goal,Dash Goal,GOAL_FUND,INR,,100,,,,,,,,,0,1000,,\n");
        Files.writeString(dir.resolve("review/extra-plan-rows.csv"), "file_row,date,kind,account_or_from,to_account,amount,currency,amount_received,category,sub_category,note,description,flag\n"
                + "900001," + DAY.plusDays(15) + ",TRANSFER,HDFC,Dash Goal,50,INR,,,,From the dashboard,,\n"
                + "900002," + DAY.plusDays(16) + ",EXPENSE,Dash Card,,300,INR,,Food,Eating out,Dinner on the card,,\n");   // a deposit of the card, fed 700 a month for 3 months before the file
        Files.writeString(dir.resolve("review/emergency-fund.csv"), "target_mode,target_months,target_amount,monthly_essential_expenses,planned_monthly_contribution\nAMOUNT,,5000,900,400\n");
        Files.writeString(dir.resolve("review/ledger-buckets.csv"), "name,pays,monthly_allocation,payment_amount,frequency_months,next_payment_date,previous_payment_date,end_date,balance\n"
                + "Kallur Chit,Kallur,100,1000,6,2027-03-15,2026-09-15,,500\nWifi,Wifi,50,600,6,2027-03-01,2026-09-01,,300\nBuffer,,0,,,,,,1205\n");   // a bill's bucket, one that is not paid in the file, and the Buffer
        Files.writeString(dir.resolve("review/borrowers.csv"), "name,opening_amount\nOld Friend,500\n");   // someone who already owed you before the file
        Files.writeString(dir.resolve("review/portfolio-holdings.csv"), "kind,name,quantity,invested,current_value\nSHARES,TESTCO,10,1000,1500\n");   // a holding that only the dashboard has
        Files.writeString(dir.resolve("review/budgets.csv"), "category,sub_category,amount\nFood,Dining out,2000\n,,50000\n");   // a limit on a category and one on all spending
        Files.writeString(dir.resolve("review/portfolio-values.csv"), "kind,name,current_value,date\nSHARES,TESTCO,1600," + DAY.plusDays(20) + "\n");
        Files.writeString(dir.resolve("review/notes.csv"), "date,title,body,tags\n2026-03-02,\"A note\",\"Two\nlines\",\"first|second\"\n");
        Files.writeString(dir.resolve("review/reminders.csv"), "date,title,repeat,done,done_on,ticked_days\n2026-03-03,\"Pay rent\",MONTHLY,false,,\"2026-03-03=2026-03-03\"\n"
                + "2026-03-04,\"Call the CA\",NONE,true,2026-03-05,\"\"\n");
        Process p = new ProcessBuilder("python3", "-I", script().toString(), "plan", file.toString(), "--until", DAY.plusDays(100).toString(),
                "--out", dir.resolve("review").toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, exitOf(p), output);
        return output;
    }

    private MockHttpServletResponse perform(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .header("X-Requested-With", "FinancialDashboard")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private record Run(int exitCode, List<String> output, int requests) {
    }

    /** Starts `apply` and answers each of its API calls with the real application until it finishes. */
    private Run apply() throws Exception {
        Path bridge;
        try {
            bridge = Path.of(getClass().getResource("/import_bridge.py").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        ProcessBuilder builder = new ProcessBuilder("python3", "-I", bridge.toString(), script().toString(), dir.resolve("review").toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("FINANCE_PASSWORD", PASSWORD);
        builder.environment().put("CLEAR_STARTER", "1");   // start from only the categories of the file
        Process process = builder.start();
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
                String[] parts = line.substring("@@REQ ".length()).split(" ", 3);
                MockHttpServletResponse response = perform(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
                out.write(response.getStatus() + " " + response.getContentAsString(StandardCharsets.UTF_8).replace("\n", " "));
                out.newLine();
                out.flush();
            }
        }
        return new Run(process.waitFor(), output, requests);
    }

    private static void assertAmount(String expected, BigDecimal actual, String what) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    @Test
    void aWorkbookIsPlannedThenSavedIntoAnEmptyDatabaseAndNeverIntoOneThatHasData() throws Exception {
        assumeTrue(Files.exists(script()), "scripts/import-money-manager.py not found from " + Path.of("").toAbsolutePath());
        assumeTrue(pythonAvailable(), "python3 is not available");
        Path file = workbook();

        String planned = plan(file);
        assertTrue(planned.contains("27 rows planned"), planned);   // 13, and the RD; the opening balance and the late row are not transactions
        assertTrue(Files.readString(dir.resolve("review/category-map.csv")).contains("Loan Repaid"), "a negative Loan row is a repayment");

        Run run = apply();
        String output = String.join("\n", run.output());
        assertEquals(0, run.exitCode(), output);
        assertTrue(output.contains("27 transactions saved") && output.contains("0 failed"), output);

        // ---- accounts: foreign ones in their currency, the card with its limit, every opening balance back at 0
        Map<String, AccountSummary> byName = accountService.listSummaries().stream().collect(Collectors.toMap(AccountSummary::name, Function.identity()));
        assertEquals(15, byName.size(), byName.keySet().toString());   // only the accounts the rows use, the Recurring Deposit the override names, and Receivables, which the first borrower makes
        assertEquals("USD", byName.get("Dollars").currency().name());
        assertEquals("VND", byName.get("Vietnamese Dong").currency().name());
        assertTrue(byName.values().stream().filter(a -> !a.name().equals("HDFC") && !a.name().equals("Personal Loan") && !a.name().equals("Recurring Deposits") && !a.name().equals("Mutual Funds") && !a.name().equals("Dash Goal") && !a.name().equals("Shares") && !a.name().equals("Receivables")).allMatch(a -> a.openingBalance().signum() == 0), "the temporary cushion is gone");
        assertAmount("3100", byName.get("HDFC").openingBalance(), "HDFC's opening balance: 1000 from the OPENING override and the 2100 that went into the deposit before the file");
        assertAmount("4600", byName.get("Recurring Deposits").balance(), "the Recurring Deposits account: 500 already in it, 3 back-filled deposits of 700 and the 2000 deposited");
        assertTrue(!byName.containsKey("Recurring Deposit"), "the deposit is on the card, not an account of its own");
        var rd = recurringDeposits.get().open().get(0);
        assertEquals("Recurring Deposit", rd.name());
        assertEquals(12, rd.termMonths());
        assertEquals(LocalDate.of(2025, 12, 4), rd.startDate(), "it started with the first back-filled deposit");
        assertAmount("4600", rd.deposited(), "the deposit");
        assertEquals(4, rd.instalments(), "the 3 back-filled deposits and the one in the file");
        assertAmount("900", byName.get("Personal Loan").balance(), "the loan: 1000 owed on the first day less the 100 EMI");
        assertAmount("1000", byName.get("Personal Loan").openingBalance(), "the amount owed on the first day");
        assertTrue(creditCards.findById(byName.get("Kiwi Credit Card").id()).isPresent());

        // ---- what the rows add up to, in each account's own currency
        assertAmount("86200", byName.get("HDFC").balance(), "HDFC");   // 1000 opening + 100000 + 50 refund + 400 repaid - 200 - 3000 - 8000 - 1000 SIP - 1000 loan - 2000 RD - 100 EMI + 6000 fund sold - 2500 into the Emergency Fund - 3000 into the Ledger - 400 lent to Ravi online - 50 into the dashboard-only goal fund
        assertAmount("3000", byName.get("Cash").balance(), "Cash");
        assertAmount("39", byName.get("Dollars").balance(), "Dollars");   // 100 arrived - 50 - the 96 rupee fee as 1 dollar - 10 changed into Dong
        assertAmount("150000", byName.get("Vietnamese Dong").balance(), "Vietnamese Dong");   // -100000 for the pho + the 250000 Dong that arrived for the 10 dollars
        assertAmount("500", byName.get("Kiwi Credit Card").balance(), "Kiwi Credit Card");
        assertAmount("0", byName.get("Mutual Funds").balance(), "Mid Cap Fund held 4000, took 1000 more, was worth 6000 when it sold and paid it all out");
        assertTrue(!byName.containsKey("Mid Cap Mutual fund") && !byName.containsKey("Mid Cap Fund"), "the fund is a holding, not an account");
        assertAmount("85.01", byName.get("Amazon Wallet").balance(), "Amazon Wallet");

        // ---- the fund's value follows what was put in until it is sold, so its graph does not show a loss the day money goes in
        var points = portfolioService.stats(com.learning.spring.financialdashboard.model.PortfolioKind.MUTUAL_FUNDS).holdings().get(0).points();
        var afterInvest = points.stream().filter(pt -> pt.date().equals(LocalDate.of(2026, 3, 9))).findFirst().orElseThrow();
        assertAmount("5000", afterInvest.investedAmount(), "4000 it held and the 1000 put in on 9 March");
        assertAmount("5000", afterInvest.currentValue(), "worth what it cost that day");

        // ---- the transfer between two currencies carries what arrived; refunds and repayments became income
        Account hdfc = accounts.findById(byName.get("HDFC").id()).orElseThrow();
        Transaction buying = transactions.findAll().stream().filter(t -> "Buying dollars".equals(t.getNote())).findFirst().orElseThrow();
        assertAmount("8000", buying.getAmount(), "rupees out");
        assertAmount("100", buying.getToAmount(), "dollars in");
        assertEquals(hdfc.getId(), buying.getFromAccountId());
        Map<Long, Category> category = categories.findAll().stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        Transaction online = transactions.findAll().stream().filter(t -> "Online to Ravi".equals(t.getNote())).findFirst().orElseThrow();
        assertEquals("EXPENSE", online.getKind().name(), "the transfer row became money spent");
        assertEquals("LOANS_GIVEN", category.get(online.getCategoryId()).getSystemKey());
        assertEquals(borrowers.findById(online.getBorrowerId()).orElseThrow().getName(), "Ravi", "lent to Ravi");
        Transaction conversion = transactions.findAll().stream().filter(t -> "Conversion to Dong".equals(t.getNote())).findFirst().orElseThrow();
        assertEquals("Dollar Trip", category.get(category.get(conversion.getCategoryId()).getParentId()).getName(), "a rename with dates applies to the rows of those days");
        assertTrue(categories.findAll().stream().noneMatch(c -> "Trips".equals(c.getName())), "every Trips row of that day went to the trip's own category");
        Transaction fruit = transactions.findAll().stream().filter(t -> "Returned fruit".equals(t.getNote())).findFirst().orElseThrow();
        assertEquals("INCOME", fruit.getKind().name());
        assertEquals("Food", category.get(fruit.getCategoryId()).getName());   // the original category is the sub category of Refund
        assertEquals("Refund", category.get(category.get(fruit.getCategoryId()).getParentId()).getName(), "filed under Refund, not as a Food expense");
        Transaction repaid = transactions.findAll().stream().filter(t -> "Part repaid".equals(t.getNote())).findFirst().orElseThrow();
        assertEquals("LOANS_COLLECTED", category.get(repaid.getCategoryId()).getSystemKey(), "a repayment is Loans Collected, not a category of its own");
        Transaction lent = transactions.findAll().stream().filter(t -> "Lent".equals(t.getNote())).findFirst().orElseThrow();
        assertEquals("LOANS_GIVEN", category.get(lent.getCategoryId()).getSystemKey());
        assertEquals("Ravi", borrowers.findById(lent.getBorrowerId()).orElseThrow().getName(), "the person is a borrower");
        assertEquals(lent.getBorrowerId(), repaid.getBorrowerId(), "the same person for the loan and its repayment");
        assertTrue(categories.findAll().stream().noneMatch(c -> "Ravi".equals(c.getName()) || "Loan".equals(c.getName())), "no category named after the person");
        assertAmount("150", byName.get("Dash Goal").balance(), "an account only the dashboard has: its opening and the extra transfer");
        assertAmount("1000", byName.get("Dash Goal").targetAmount(), "its target");
        assertAmount("300", byName.get("Dash Card").balance(), "the card's spending, from an extra row");
        var dashCard = creditCards.findById(byName.get("Dash Card").id()).orElseThrow();
        assertEquals(9, dashCard.getDueDay());
        assertEquals(20, dashCard.getStatementDay());
        assertTrue(transactions.findAll().stream().anyMatch(t -> "Dinner on the card".equals(t.getNote())
                && category.get(t.getCategoryId()).getName().equals("Dining out")), "the rename applies to an extra row too");
        assertAmount("1000", byName.get("Shares").balance(), "a holding from portfolio-holdings.csv counts at its invested amount, whatever portfolio-values.csv values it at");
        var budgetList = budgetRepository.findAll();
        assertEquals(2, budgetList.size());
        assertTrue(budgetList.stream().anyMatch(b -> b.getCategoryId() == null && b.getAmount().compareTo(new BigDecimal("50000")) == 0), "a limit on all spending");
        assertTrue(budgetList.stream().anyMatch(b -> b.getCategoryId() != null && b.getAmount().compareTo(new BigDecimal("2000")) == 0), "a limit on a category");
        assertAmount("1500", byName.get("Receivables").balance(), "Old Friend's 500 from before the file, Ravi's 1000 and 400 lent online, less the 400 paid back");
        assertTrue(loansService.summary().borrowers().stream().anyMatch(b -> b.name().equals("Old Friend") && b.owed().compareTo(new BigDecimal("500")) == 0));
        var stored = noteRepository.findAll();
        assertEquals(1, stored.size());
        assertTrue(stored.get(0).isSealed(), "a diary note is stored sealed");
        assertFalse(stored.get(0).getBody().contains("lines"), "the stored value is not the text");
        com.learning.spring.financialdashboard.service.DiaryVault.bind(PASSWORD);   // what a request with the login does
        try {
            var savedNotes = noteService.page(null, null, null, 0, 10).notes();
            assertEquals(1, savedNotes.size());
            assertEquals("Two\nlines", savedNotes.get(0).getBody());
            assertEquals(java.util.Set.of("first", "second"), savedNotes.get(0).getTags());
        } finally {
            com.learning.spring.financialdashboard.service.DiaryVault.unbind();
        }
        var savedReminders = reminderRepository.findAll();
        assertEquals(2, savedReminders.size());
        var call = savedReminders.stream().filter(r -> r.getTitle().equals("Call the CA")).findFirst().orElseThrow();
        assertTrue(call.isDone());
        assertEquals(java.time.LocalDate.of(2026, 3, 5), call.getDoneOn());
        var rent = savedReminders.stream().filter(r -> r.getTitle().equals("Pay rent")).findFirst().orElseThrow();
        assertEquals("MONTHLY", rent.getRepeat().name());
        assertEquals(java.time.LocalDate.of(2026, 3, 3), rent.getCompletions().get(java.time.LocalDate.of(2026, 3, 3)));
        MockHttpServletResponse ledger = perform("GET", "/api/ledger", "");
        assertTrue(ledger.getContentAsString().contains("\"configured\":true"), ledger.getContentAsString());
        assertAmount("2005", byName.get("Future Expenses Ledger").balance(), "the Ledger holds what was funded, less the installment paid, plus the interest");
        Transaction installment = transactions.findAll().stream().filter(t -> "Chit Fund Installment".equals(t.getNote())).findFirst().orElseThrow();
        assertEquals(DAY.plusDays(14), installment.getDate(), "paid on its own day");
        assertEquals("Kallur", categories.findById(installment.getCategoryId()).orElseThrow().getName(), "under the row's category");
        assertTrue(transactions.findAll().stream().anyMatch(t -> "Funded the Future Expenses Ledger".equals(t.getNote()) && t.getDate().equals(DAY.plusDays(11))), "the funding is on its own day");
        var ledgerView = ledgerService.get();
        assertEquals(java.util.List.of("Buffer", "Kallur Chit", "Wifi"), ledgerView.buckets().stream().map(b -> b.name()).sorted().toList(), "the real buckets, and the bill's bucket carries the real name");
        assertAmount("500", ledgerView.buckets().stream().filter(b -> b.name().equals("Kallur Chit")).findFirst().orElseThrow().balance(), "what the bucket holds");
        assertAmount("300", ledgerView.buckets().stream().filter(b -> b.name().equals("Wifi")).findFirst().orElseThrow().balance(), "a bucket nothing was paid from");
        assertAmount("1205", ledgerView.buckets().stream().filter(b -> b.buffer()).findFirst().orElseThrow().balance(), "the rest is the Buffer");
        assertEquals(java.time.LocalDate.of(2027, 3, 15), ledgerView.buckets().stream().filter(b -> b.name().equals("Kallur Chit")).findFirst().orElseThrow().nextPaymentDate(), "its dates are the real ones");
        MockHttpServletResponse emergency = perform("GET", "/api/emergency-fund", "");
        assertTrue(emergency.getContentAsString().contains("\"targetMode\":\"AMOUNT\"") && emergency.getContentAsString().contains("\"target\":5000") && emergency.getContentAsString().contains("\"configured\":true") && emergency.getContentAsString().contains("\"name\":\"Emergency Fund\""), "the account is the app's Emergency Fund: " + emergency.getContentAsString());
        assertAmount("2500", byName.get("Emergency Fund").balance(), "what was put into it");
        Transaction correction = transactions.findAll().stream().filter(t -> "Difference".equals(t.getNote())).findFirst().orElseThrow();
        assertEquals("Balance correction", category.get(correction.getCategoryId()).getName());
        assertTrue(categories.findAll().stream().anyMatch(c -> c.getName().equals("Dining out")) && categories.findAll().stream().noneMatch(c -> c.getName().equals("Eating out")),
                "the rename is applied to the rows the category map put there");
        assertTrue(categories.findAll().stream().noneMatch(c -> c.getName().equals("Business") || c.getName().equals("Cashback")),
                "the app's starter categories were removed first, so only the file's categories are left");
        assertTrue(categories.findAll().stream().anyMatch(c -> "LIABILITY_PAYMENT".equals(c.getSystemKey())), "the fixed categories stay");
        assertTrue(transactions.findAll().stream().allMatch(t -> t.getTags() != null), "the server tagged them as it does for any transaction");

        // ---- a second run on a database that now has data is refused, and adds nothing
        long before = transactions.count();
        Run again = apply();
        assertNotEquals(0, again.exitCode(), String.join("\n", again.output()));
        assertTrue(String.join("\n", again.output()).contains("REFUSED") || again.exitCode() != 0);
        assertEquals(before, transactions.count());
    }
}
