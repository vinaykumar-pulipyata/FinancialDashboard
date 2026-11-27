package com.learning.spring.financialdashboard;

import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.NoteRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import com.learning.spring.financialdashboard.service.CredentialService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole REST API through the real servlet pipeline (Spring Security, the controllers, JSON and the error handler),
 * without opening a port. Each test drives one area from request to response.
 */
@SpringBootTest
class ApiTest {

    private static final String PASSWORD = "test-password-1";
    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    @Autowired
    com.learning.spring.financialdashboard.service.SystemCategories systemCategories;

    @Autowired
    WebApplicationContext context;
    @Autowired
    Filter springSecurityFilterChain;
    @Autowired
    CredentialService credentials;
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
    com.learning.spring.financialdashboard.repository.SplitSettlementRepository splitSettlements;
    @Autowired
    com.learning.spring.financialdashboard.repository.DebtDetailRepository debtDetails;
    @Autowired
    AccountRepository accounts;
    @Autowired
    AppSettingRepository appSettings;
    @Autowired
    NoteRepository noteRepository;
    @Autowired
    com.learning.spring.financialdashboard.repository.ReminderRepository reminderRepository;
    @Autowired
    com.learning.spring.financialdashboard.repository.BudgetRepository budgetRepository;
    @Autowired
    com.learning.spring.financialdashboard.repository.ExchangeRateRepository exchangeRateRepository;
    @Value("${app.backup.dir}")
    String backupDir;

    MockMvc mvc;
    String auth;   // the Authorization header to send, or null for none
    boolean clientHeader = true;   // whether to send X-Requested-With, as the dashboard does

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        wipe();
        auth = null;
        clientHeader = true;
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        credentials.reset();
        splitSettlements.deleteAll();
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
        debtDetails.deleteAll();
        borrowers.deleteAll();
        accounts.deleteAll();
        appSettings.deleteAll();
        noteRepository.deleteAll();
        reminderRepository.deleteAll();
        budgetRepository.deleteAll();
        exchangeRateRepository.deleteAll();
        java.io.File[] left = new java.io.File(backupDir).listFiles();
        if (left != null) {
            for (java.io.File f : left) {
                f.delete();
            }
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String json) throws Exception {
        if (auth != null) {
            request.header("Authorization", auth);
        }
        if (clientHeader) {
            request.header("X-Requested-With", "FinancialDashboard");
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private String body(MockHttpServletResponse r) throws Exception {
        return r.getContentAsString(StandardCharsets.UTF_8);
    }

    private String ok(MockHttpServletResponse r) throws Exception {
        assertTrue(r.getStatus() >= 200 && r.getStatus() < 300, "unexpected status " + r.getStatus() + ", body: " + body(r));
        return body(r);
    }

    private String get(String path) throws Exception {
        return ok(send(MockMvcRequestBuilders.get(path), null));
    }

    private String post(String path, String json) throws Exception {
        return ok(send(MockMvcRequestBuilders.post(path), json));
    }

    private String put(String path, String json) throws Exception {
        return ok(send(MockMvcRequestBuilders.put(path), json));
    }

    private String delete(String path) throws Exception {
        return ok(send(MockMvcRequestBuilders.delete(path), null));
    }

    private int status(MockHttpServletRequestBuilder request, String json) throws Exception {
        return send(request, json).getStatus();
    }

    private long id(String json) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        assertTrue(m.find(), "no id in " + json);
        return Long.parseLong(m.group(1));
    }

    private long account(String name, String type, String opening) throws Exception {
        return id(post("/api/accounts", "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":" + opening + "}"));
    }

    private long category(String name, String kind, Long parentId) throws Exception {
        return id(post("/api/categories", "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\""
                + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}"));
    }

    private String today() {
        return LocalDate.now().toString();
    }

    // ------------------------------------------------------------------------------------------ security

    @Test
    void theAppIsOpenUntilAPasswordIsSet() throws Exception {
        assertTrue(get("/api/auth/status").contains("\"passwordSet\":false"));
        get("/api/accounts");   // no sign-in needed yet
    }

    @Test
    void whileNoPasswordIsSetEvenARequestThatSendsALoginIsAccepted() throws Exception {
        // A script or tool that always sends credentials must still work on a fresh database.
        auth = "Basic " + Base64.getEncoder().encodeToString("admin:".getBytes(StandardCharsets.UTF_8));
        get("/api/accounts");
        auth = "Basic " + Base64.getEncoder().encodeToString("someone:whatever".getBytes(StandardCharsets.UTF_8));
        get("/api/accounts");
    }

    @Test
    void aRequestForAnotherHostIsRefusedEvenBeforeAPasswordExists() throws Exception {
        // DNS rebinding: a web page on another site reaches 127.0.0.1 but its name arrives as the Host.
        assertEquals(403, status(MockMvcRequestBuilders.get("/api/auth/status").with(r -> { r.setServerName("evil.example"); return r; }), null));
        assertEquals(403, status(MockMvcRequestBuilders.post("/api/auth/password").with(r -> { r.setServerName("evil.example"); return r; }), "{\"password\":\"" + PASSWORD + "\"}"));
        assertTrue(get("/api/auth/status").contains("\"passwordSet\":false"), "nothing was set");
        assertEquals(200, status(MockMvcRequestBuilders.get("/api/auth/status").with(r -> { r.setServerName("finance.localhost"); return r; }), null));
    }

    @Test
    void oncePasswordIsSetEveryRequestNeedsTheLogin() throws Exception {
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");

        assertEquals(401, status(MockMvcRequestBuilders.get("/api/accounts"), null));
        auth = "Basic " + Base64.getEncoder().encodeToString("admin:wrong-password".getBytes(StandardCharsets.UTF_8));
        assertEquals(401, status(MockMvcRequestBuilders.get("/api/accounts"), null));
        auth = "Basic " + Base64.getEncoder().encodeToString((PASSWORD + ":x").getBytes(StandardCharsets.UTF_8));
        assertEquals(401, status(MockMvcRequestBuilders.get("/api/accounts"), null));   // wrong user name
        auth = BASIC;
        get("/api/accounts");
        assertTrue(get("/api/auth/status").contains("\"passwordSet\":true"));
    }

    @Test
    void thePasswordCanBeVerifiedChangedAndRefusedWhenWeak() throws Exception {
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");
        auth = BASIC;

        post("/api/auth/verify", "{\"password\":\"" + PASSWORD + "\"}");
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/auth/verify"), "{\"password\":\"nope\"}"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/auth/password"), "{\"password\":\"another-one-1\"}"));   // already set
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/auth/password"),
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}"));
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/auth/password"),
                "{\"currentPassword\":\"wrong\",\"newPassword\":\"a-new-password\"}"));

        put("/api/auth/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a-new-password\"}");
        assertEquals(401, status(MockMvcRequestBuilders.get("/api/accounts"), null));   // the old login no longer works
        auth = "Basic " + Base64.getEncoder().encodeToString("admin:a-new-password".getBytes(StandardCharsets.UTF_8));
        get("/api/accounts");
    }

    @Test
    void errorsComeBackAsAJsonMessage() throws Exception {
        MockHttpServletResponse missing = send(MockMvcRequestBuilders.get("/api/accounts/987654"), null);
        assertEquals(404, missing.getStatus());
        assertTrue(body(missing).contains("\"error\""));

        MockHttpServletResponse invalid = send(MockMvcRequestBuilders.post("/api/accounts"), "{\"name\":\"\",\"type\":\"BANK\"}");
        assertEquals(400, invalid.getStatus());
        assertTrue(body(invalid).contains("\"error\""));
    }

    @Test
    void theHomePageForwardsToTheDashboard() throws Exception {
        assertEquals("/Dashboard.html", send(MockMvcRequestBuilders.get("/"), null).getForwardedUrl());
    }

    // ------------------------------------------------------------------------------------------ accounts and transactions

    @Test
    void accountsCanBeCreatedReadUpdatedDeactivatedAndDeleted() throws Exception {
        long bank = account("Savings", "BANK", "1000");

        assertTrue(get("/api/accounts").contains("Savings"));
        assertTrue(get("/api/accounts/" + bank).contains("\"name\":\"Savings\""));
        put("/api/accounts/" + bank, "{\"name\":\"Main savings\",\"type\":\"BANK\",\"openingBalance\":2000}");
        assertTrue(get("/api/accounts/" + bank).contains("Main savings"));

        assertTrue(post("/api/accounts/" + bank + "/deactivate", null).contains("\"active\":false"));
        assertTrue(post("/api/accounts/" + bank + "/activate", null).contains("\"active\":true"));
        delete("/api/accounts/" + bank);
        assertEquals(404, status(MockMvcRequestBuilders.get("/api/accounts/" + bank), null));
    }

    @Test
    void anAccountsBalanceCanBeResetWithANoteAndADeactivatedOneAllowsNothing() throws Exception {
        long bank = account("Old bank", "BANK", "1000");

        assertEquals(400, status(MockMvcRequestBuilders.post("/api/accounts/" + bank + "/reset-balance")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"note\":\"  \"}"), null), "a note is required");
        String saved = post("/api/accounts/" + bank + "/reset-balance", "{\"note\":\"Withdrawn in cash\"}");
        assertTrue(saved.contains("\"kind\":\"EXPENSE\"") && saved.contains("Withdrawn in cash") && saved.contains("\"amount\":1000"), saved);
        assertTrue(get("/api/accounts").contains("\"balance\":0"), "the balance is zero");
        assertTrue(get("/api/categories").contains("BALANCE_ADJUSTMENT_EXPENSE"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/accounts/" + bank + "/reset-balance")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"note\":\"again\"}"), null), "already zero");

        post("/api/accounts/" + bank + "/deactivate", null);
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/accounts/" + bank)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\",\"type\":\"BANK\",\"openingBalance\":1000}"), null), "a deactivated account cannot be edited");
    }

    @Test
    void transactionsCanBeRecordedListedEditedAndDeleted() throws Exception {
        long bank = account("Bank", "BANK", "1000");
        long cash = account("Wallet", "CASH", "0");
        long salary = category("Api salary", "INCOME", null);

        long income = id(post("/api/transactions", "{\"date\":\"" + today() + "\",\"kind\":\"INCOME\",\"toAccountId\":" + bank
                + ",\"amount\":500,\"categoryId\":" + salary + ",\"note\":\"pay\"}"));
        long transfer = id(post("/api/transactions", "{\"date\":\"" + today() + "\",\"kind\":\"TRANSFER\",\"fromAccountId\":" + bank
                + ",\"toAccountId\":" + cash + ",\"amount\":100,\"fee\":5}"));

        String list = get("/api/transactions");
        assertTrue(list.contains("\"id\":" + income) && list.contains("\"id\":" + transfer));
        put("/api/transactions/" + income, "{\"date\":\"" + today() + "\",\"kind\":\"INCOME\",\"toAccountId\":" + bank
                + ",\"amount\":600,\"categoryId\":" + salary + "}");
        assertTrue(get("/api/accounts").contains("1495"));   // 1000 + 600 - 100 - 5
        delete("/api/transactions/" + transfer);
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/transactions"),
                "{\"date\":\"" + today() + "\",\"kind\":\"EXPENSE\",\"fromAccountId\":" + bank + ",\"amount\":0}"));
    }

    @Test
    void categoriesCanBeCreatedFilteredRenamedDeactivatedAndDeleted() throws Exception {
        long parent = category("Api food", "EXPENSE", null);
        long child = category("Api snacks", "EXPENSE", parent);
        category("Api bonus", "INCOME", null);

        assertTrue(get("/api/categories?kind=EXPENSE").contains("Api food"));
        assertTrue(!get("/api/categories?kind=EXPENSE").contains("Api bonus"));
        assertTrue(get("/api/categories").contains("Api bonus"));
        put("/api/categories/" + child, "{\"name\":\"Api treats\",\"kind\":\"EXPENSE\"}");
        assertTrue(post("/api/categories/" + child + "/deactivate", null).contains("\"active\":false"));
        assertTrue(post("/api/categories/" + child + "/activate", null).contains("\"active\":true"));
        delete("/api/categories/" + child);
        delete("/api/categories/" + parent);
    }

    @Test
    void theBatchSetupCreatesCardsVehiclesAndTheirDetails() throws Exception {
        String created = post("/api/setup/accounts", "[{\"name\":\"Card\",\"type\":\"CREDIT_CARD\",\"openingBalance\":0,\"creditLimit\":50000,\"dueDay\":15},"
                + "{\"name\":\"Car\",\"type\":\"VEHICLE\",\"openingBalance\":300000,\"numberPlate\":\"KA01AB1234\",\"yearBought\":2020}]");
        long card = id(created);

        assertTrue(get("/api/credit-cards").contains("\"dueDay\":15"), "the due day given at set-up is kept");
        assertTrue(get("/api/credit-cards").contains("50000"));
        assertTrue(put("/api/credit-cards/" + card, "{\"creditLimit\":75000}").contains("75000"));
        String withDay = put("/api/credit-cards/" + card, "{\"creditLimit\":75000,\"dueDay\":21}");
        assertTrue(withDay.contains("\"dueDay\":21"), withDay);
        assertTrue(get("/api/credit-cards").contains("\"dueDay\":21"));
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/credit-cards/" + card), "{\"creditLimit\":75000,\"dueDay\":32}"));
        assertTrue(put("/api/credit-cards/" + card, "{\"creditLimit\":75000}").contains("\"dueDay\":null"), "leaving it out clears it");
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/credit-cards/" + card), "{\"creditLimit\":0}"));

        String listed = get("/api/vehicles");
        assertTrue(listed.contains("KA01AB1234"));
        long car = id(listed.replace("accountId", "id"));
        assertTrue(put("/api/vehicles/" + car, "{\"numberPlate\":\"KA02CD5678\",\"yearBought\":2021}").contains("KA02CD5678"));
    }

    @Test
    void transactionsCarryTagsThatAreWorkedOutOrTypedAndCanBeSuggestedFirst() throws Exception {
        long bank = account("Api tag bank", "BANK", "1000");
        long food = category("Api tag food", "EXPENSE", null);
        long dining = category("Api tag dining", "EXPENSE", food);
        String base = "\"date\":\"" + today() + "\",\"kind\":\"EXPENSE\",\"fromAccountId\":" + bank + ",\"amount\":120,\"categoryId\":" + dining;

        String suggested = post("/api/transactions/suggest-tags", "{\"categoryId\":" + dining + ",\"note\":\"Dinner at Barbeque Nation\"}");
        assertTrue(suggested.contains("barbeque-nation") && suggested.contains("dinner"), suggested);

        String auto = post("/api/transactions", "{" + base + ",\"note\":\"Dinner at Barbeque Nation\"}");
        assertTrue(auto.contains("\"tags\":[") && auto.contains("barbeque-nation"), auto);
        String typed = post("/api/transactions", "{" + base + ",\"note\":\"Dinner\",\"tags\":[\"Family\",\"#trip\"]}");
        assertTrue(typed.contains("\"tags\":[\"family\",\"trip\"]"), typed);
        assertTrue(get("/api/transactions").contains("\"family\""), "the list carries the tags");
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/transactions"), "{" + base + ",\"tags\":[\"no good!\"]}"));
    }

    @Test
    void notesAreTaggedFromTheirTextUnlessATagListComesWithThem() throws Exception {
        String suggested = post("/api/notes/suggest-tags", "{\"title\":\"Car service\",\"body\":\"Took it to the workshop\"}");
        assertTrue(suggested.contains("car") && suggested.contains("service"), suggested);
        String auto = post("/api/notes", "{\"date\":\"" + today() + "\",\"title\":\"Car service\",\"body\":\"Took it to the workshop\"}");
        assertTrue(auto.contains("\"tags\":[") && auto.contains("service"), auto);
        String typed = post("/api/notes", "{\"date\":\"" + today() + "\",\"body\":\"Text\",\"tags\":[\"Home\"]}");
        assertTrue(typed.contains("\"tags\":[\"home\"]"), typed);
    }

    @Test
    void budgetsAreSetReplacedListedAndDeleted() throws Exception {
        long food = category("Api budget food", "EXPENSE", null);
        long snacks = category("Api budget snacks", "EXPENSE", food);

        String overall = put("/api/budgets", "{\"amount\":50000}");
        long overallId = id(overall);
        put("/api/budgets", "{\"categoryId\":" + food + ",\"amount\":12000.50}");
        put("/api/budgets", "{\"categoryId\":" + food + ",\"amount\":15000}");
        String list = get("/api/budgets");
        assertTrue(list.contains("\"categoryId\":" + food) && list.contains("15000") && !list.contains("12000.5"), list);
        assertEquals(2, list.split("\"amount\"", -1).length - 1, "one limit on everything and one on Food: " + list);   // before the sub category's

        put("/api/budgets", "{\"categoryId\":" + snacks + ",\"amount\":3000}");   // a sub category can have its own, next to its parent's
        assertTrue(get("/api/budgets").contains("\"categoryId\":" + snacks));
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/budgets"), "{\"amount\":0}"));
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/budgets"), "{\"categoryId\":999999,\"amount\":10}"));

        assertEquals(204, status(MockMvcRequestBuilders.delete("/api/budgets/" + overallId), null));
        assertEquals(404, status(MockMvcRequestBuilders.delete("/api/budgets/" + overallId), null));
        clientHeader = false;
        assertEquals(403, status(MockMvcRequestBuilders.put("/api/budgets"), "{\"amount\":10}"), "a change needs the client header like every other");
    }

    @Test
    void aLoanIsFixedOrCustomAndPaidThroughLiabilityPaymentsExpenses() throws Exception {
        long bank = account("Bank", "BANK", "100000");
        String made = post("/api/setup/accounts", "[{\"name\":\"Car loan\",\"type\":\"DEBT\",\"openingBalance\":500000,\"emiAmount\":23072},"
                + "{\"name\":\"Friend\",\"type\":\"DEBT\",\"openingBalance\":5000}]");
        long car = id(made);
        String upToFriend = made.substring(0, made.indexOf("\"Friend\""));
        long friend = Long.parseLong(upToFriend.substring(upToFriend.lastIndexOf("\"id\":") + 5).replaceAll("^(\\d+).*$", "$1"));

        String fixed = get("/api/debts");
        assertTrue(fixed.contains("\"accountId\":" + car) && fixed.contains("23072"), fixed);
        assertTrue(!fixed.contains("\"accountId\":" + friend), "a custom loan has no plan");
        assertEquals(204, status(MockMvcRequestBuilders.put("/api/debts/" + friend), "{\"emiAmount\":1000}"));
        assertEquals(204, status(MockMvcRequestBuilders.put("/api/debts/" + friend), "{}"));
        assertTrue(!get("/api/debts").contains("\"accountId\":" + friend), "leaving the EMI out makes it custom again");
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/debts/" + car), "{\"emiAmount\":0}"));
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/debts/" + bank), "{\"emiAmount\":10}"));
        assertEquals(404, status(MockMvcRequestBuilders.put("/api/debts/999999"), "{\"emiAmount\":10}"));

        systemCategories.ensureAll();   // other test classes wipe the categories, and this one reads the fixed one from the list
        String categories = get("/api/categories");
        String fromKey = categories.substring(categories.lastIndexOf('{', categories.indexOf("LIABILITY_PAYMENT")));
        long payments = id(fromKey);
        long paid = id(post("/api/transactions", "{\"date\":\"" + today() + "\",\"kind\":\"EXPENSE\",\"fromAccountId\":" + bank
                + ",\"toAccountId\":" + car + ",\"amount\":23072,\"categoryId\":" + payments + "}"));
        String accountsNow = get("/api/accounts");
        assertTrue(accountsNow.contains("476928") && accountsNow.contains("76928"), accountsNow);   // owed 500000 - 23072; bank 100000 - 23072
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/transactions"), "{\"date\":\"" + today() + "\",\"kind\":\"EXPENSE\",\"fromAccountId\":"
                + bank + ",\"amount\":10,\"categoryId\":" + payments + "}"));   // a payment must say which loan or card
        delete("/api/transactions/" + paid);
        assertTrue(get("/api/accounts").contains("500000"));
    }

    @Test
    void thePageCanPutItsOwnErrorsAndStepsInTheLog() throws Exception {
        try (com.learning.spring.financialdashboard.config.LogCapture log = new com.learning.spring.financialdashboard.config.LogCapture()) {
            assertEquals(204, status(MockMvcRequestBuilders.post("/api/client-log"),
                    "{\"level\":\"WARN\",\"event\":\"category.page.sync.failed\",\"detail\":\"Error\"}"));
            assertEquals(204, status(MockMvcRequestBuilders.post("/api/client-log"), "{\"level\":\"INFO\"}"), "a missing event is replaced, not refused");
            assertTrue(log.has("WARN Browser category.page.sync.failed Error"), log.lines().toString());
            assertTrue(log.has("INFO Browser invalid-event"));
        }
    }

    @Test
    void theNetWorthHistoryIsOneMonthPerPoint() throws Exception {
        account("Bank", "BANK", "1000");
        String history = get("/api/net-worth?months=3");
        assertEquals(3, history.split("\"month\"").length - 1);
        assertTrue(get("/api/net-worth").contains("netWorth"));
    }

    // ------------------------------------------------------------------------------------------ goal funds, ledger, loans

    @Test
    void theEmergencyFundCanBeSetUpAndRingFenced() throws Exception {
        assertTrue(get("/api/emergency-fund").contains("\"configured\":false"));
        put("/api/emergency-fund", "{\"openingBalance\":1000,\"targetMode\":\"AMOUNT\",\"targetAmount\":1000,\"monthlyEssentialExpenses\":500}");
        String view = get("/api/emergency-fund");
        assertTrue(view.contains("\"configured\":true") && view.contains("\"targetReached\":true"));

        long fund = id(view.replace("accountId", "id"));
        post("/api/ring-fences/" + fund, null);
        assertTrue(get("/api/emergency-fund").contains("\"ringFenced\":true"));
        delete("/api/ring-fences/" + fund);
        assertTrue(get("/api/emergency-fund").contains("\"ringFenced\":false"));
    }

    @Test
    void theLedgerFlowFromSetupToClosingABucket() throws Exception {
        long bank = account("Bank", "BANK", "100000");
        assertTrue(get("/api/ledger").contains("\"configured\":false"));
        post("/api/ledger/setup", null);

        String next = LocalDate.now().plusDays(20).toString();
        String withInsurance = post("/api/ledger/buckets", "{\"name\":\"Insurance\",\"monthlyAllocation\":2000,\"paymentAmount\":6000,"
                + "\"frequencyMonths\":3,\"nextPaymentDate\":\"" + next + "\"}");
        post("/api/ledger/buckets", "{\"name\":\"Wifi\",\"monthlyAllocation\":500,\"paymentAmount\":500,\"frequencyMonths\":1,\"nextPaymentDate\":\"" + next + "\"}");
        long insurance = bucketId(withInsurance, "Insurance");
        put("/api/ledger/buckets/" + insurance, "{\"name\":\"Car insurance\",\"monthlyAllocation\":2000,\"paymentAmount\":6000,"
                + "\"frequencyMonths\":3,\"nextPaymentDate\":\"" + next + "\"}");

        String funded = post("/api/ledger/fund", "{\"fromAccountId\":" + bank + ",\"amount\":2500}");
        assertTrue(funded.contains("Car insurance"));
        post("/api/ledger/interest", "{\"amount\":100}");
        post("/api/ledger/pay", "{\"bucketId\":" + insurance + ",\"amount\":500}");
        long wifi = bucketId(get("/api/ledger"), "Wifi");
        post("/api/ledger/transfer", "{\"fromBucketId\":" + insurance + ",\"toBucketId\":" + wifi + ",\"amount\":100}");

        // Funding again, then taking that funding back out.
        post("/api/ledger/fund", "{\"fromAccountId\":" + bank + ",\"amount\":2500}");
        long funding = id(get("/api/transactions"));
        delete("/api/ledger/fundings/" + funding);

        post("/api/ledger/buckets/" + wifi + "/close", "{}");
        assertTrue(get("/api/ledger").contains("\"closed\":[{"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/ledger/pay"), "{\"bucketId\":" + insurance + ",\"amount\":999999}"));
    }

    private long bucketId(String ledgerJson, String name) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"name\"\\s*:\\s*\"" + Pattern.quote(name) + "\"").matcher(ledgerJson);
        assertTrue(m.find(), "no bucket " + name + " in " + ledgerJson);
        return Long.parseLong(m.group(1));
    }

    @Test
    void peopleAndLoansCanBeAddedEditedAndRemoved() throws Exception {
        long person = id(post("/api/borrowers", "{\"name\":\"Ravi\",\"openingAmount\":0}"));
        assertTrue(get("/api/loans").contains("Ravi"));
        put("/api/borrowers/" + person, "{\"name\":\"Ravi K\",\"openingAmount\":0}");
        assertTrue(get("/api/loans").contains("Ravi K"));
        delete("/api/borrowers/" + person);
        assertTrue(!get("/api/loans").contains("Ravi K"));
        assertEquals("[]", get("/api/loans/write-offs"));
        assertEquals(404, status(MockMvcRequestBuilders.delete("/api/loans/write-offs/999"), null));
        assertEquals(404, status(MockMvcRequestBuilders.put("/api/loans/write-offs/999"), "{\"date\":\"2026-01-01\",\"note\":\"x\"}"));
    }

    @Test
    void anExpenseCanBeSplitWithPeopleAndSettledThroughTheApi() throws Exception {
        long bank = account("Bank", "BANK", "10000");
        long teja = id(post("/api/borrowers", "{\"name\":\"Teja\",\"openingAmount\":0}"));
        String saved = post("/api/transactions", "{\"date\":\"2026-11-01\",\"kind\":\"EXPENSE\",\"fromAccountId\":" + bank
                + ",\"amount\":590,\"note\":\"Movie\",\"splits\":{\"" + teja + "\":295}}");
        long expense = id(saved);
        assertTrue(saved.contains("\"splits\":{\"" + teja + "\":295"), saved);
        assertTrue(get("/api/loans").contains("\"shared\":295"));
        assertTrue(get("/api/transactions").contains("\"splits\":{\"" + teja + "\":295"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/transactions"), "{\"date\":\"2026-11-01\",\"kind\":\"EXPENSE\",\"fromAccountId\":" + bank
                + ",\"amount\":100,\"splits\":{\"" + teja + "\":101}}"));

        assertEquals("[]", get("/api/splits"));
        post("/api/borrowers/" + teja + "/settle-splits", "{\"date\":\"2026-11-10\",\"toAccountId\":" + bank + ",\"amount\":200}");
        String cleared = get("/api/splits");
        assertTrue(cleared.contains("\"expenseTransactionId\":" + expense) && cleared.contains("\"amount\":200"), cleared);
        assertEquals(400, status(MockMvcRequestBuilders.delete("/api/transactions/" + expense), null));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/borrowers/" + teja + "/settle-splits"),
                "{\"date\":\"2026-11-11\",\"toAccountId\":" + bank + ",\"amount\":500}"));
    }

    @Test
    void aBillSomebodyElsePaidIsOwedToThemAndPaidBackThroughTheApi() throws Exception {
        long bank = account("Bank", "BANK", "10000");
        long ravi = id(post("/api/borrowers", "{\"name\":\"Ravi\",\"openingAmount\":0}"));
        String saved = post("/api/transactions", "{\"date\":\"2026-11-01\",\"kind\":\"EXPENSE\",\"amount\":1200,\"note\":\"Hotel\","
                + "\"paidByBorrowerId\":" + ravi + ",\"ownShare\":400}");
        long bill = id(saved);
        assertTrue(saved.contains("\"ownShare\":400") && saved.contains("\"paidByBorrowerId\":" + ravi), saved);
        assertTrue(get("/api/loans").contains("\"youOwe\":400"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/transactions"), "{\"date\":\"2026-11-01\",\"kind\":\"EXPENSE\",\"amount\":100,"
                + "\"paidByBorrowerId\":" + ravi + ",\"ownShare\":500}"));
        post("/api/borrowers/" + ravi + "/repay-splits", "{\"date\":\"2026-11-10\",\"fromAccountId\":" + bank + ",\"amount\":150}");
        assertTrue(get("/api/loans").contains("\"youOwe\":250"));
        assertTrue(get("/api/splits").contains("\"expenseTransactionId\":" + bill));
        assertEquals(400, status(MockMvcRequestBuilders.delete("/api/transactions/" + bill), null));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/borrowers/" + ravi + "/repay-splits"),
                "{\"date\":\"2026-11-11\",\"fromAccountId\":" + bank + ",\"amount\":251}"));
        // settling the whole balance: you owe 250 and he owes you 100, so 100 is set off and you pay him 150
        post("/api/transactions", "{\"date\":\"2026-11-12\",\"kind\":\"EXPENSE\",\"fromAccountId\":" + bank + ",\"amount\":200,"
                + "\"splits\":{\"" + ravi + "\":100}}");
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/borrowers/" + ravi + "/settle-balance"),
                "{\"date\":\"2026-11-13\",\"accountId\":" + bank + ",\"amount\":151}"));
        String settled = post("/api/borrowers/" + ravi + "/settle-balance", "{\"date\":\"2026-11-13\",\"accountId\":" + bank + ",\"amount\":150}");
        assertTrue(settled.contains("\"offset\":100"), settled);
        String loans = get("/api/loans");
        assertTrue(loans.contains("\"youOwe\":0") && loans.contains("\"owed\":0"), loans);
    }

    // ------------------------------------------------------------------------------------------ investments

    @Test
    void mutualFundsAndSharesCanBeSetUpTradedAndRemoved() throws Exception {
        long bank = account("Bank", "BANK", "100000");
        for (String kind : new String[]{"MUTUAL_FUNDS", "SHARES"}) {
            String base = "/api/portfolios/" + kind;
            assertTrue(get(base).contains("\"configured\":"));
            post(base + "/setup", null);   // may already exist (created at start-up), that is fine
        }
        String base = "/api/portfolios/SHARES";
        String added = post(base + "/holdings", "{\"name\":\"Acme\",\"investedAmount\":1000,\"currentValue\":1200}");
        long holding = holdingId(added);

        put(base + "/holdings/" + holding, "{\"name\":\"Acme Ltd\"}");
        post(base + "/holdings/" + holding + "/value", "{\"currentValue\":1500,\"investedAmount\":1000,\"date\":\"" + today() + "\"}");
        post(base + "/invest", "{\"fromAccountId\":" + bank + ",\"amount\":500}");
        post(base + "/holdings/" + holding + "/dividend", "{\"toAccountId\":" + bank + ",\"amount\":50,\"date\":\"" + today() + "\"}");
        post(base + "/holdings/" + holding + "/withdraw", "{\"toAccountId\":" + bank + ",\"amount\":200}");
        assertTrue(get(base + "/stats").contains("overall"));
        assertEquals(400, status(MockMvcRequestBuilders.post(base + "/holdings/" + holding + "/withdraw"),
                "{\"toAccountId\":" + bank + ",\"amount\":0}"));
        assertEquals(400, status(MockMvcRequestBuilders.delete(base + "/holdings/" + holding), null));   // still worth something
        String empty = post(base + "/holdings", "{\"name\":\"Empty\",\"investedAmount\":0,\"currentValue\":0}");
        Matcher emptyId = Pattern.compile("\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"name\"\\s*:\\s*\"Empty\"").matcher(empty);
        assertTrue(emptyId.find(), empty);
        delete(base + "/holdings/" + emptyId.group(1));   // worth nothing, so it can go
    }

    private long holdingId(String viewJson) {
        Matcher m = Pattern.compile("\"holdings\"\\s*:\\s*\\[\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)").matcher(viewJson);
        assertTrue(m.find(), "no holding in " + viewJson);
        return Long.parseLong(m.group(1));
    }

    // ------------------------------------------------------------------------------------------ export

    @Test
    void theExportIsAnExcelDownload() throws Exception {
        account("Bank", "BANK", "1000");
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");
        auth = BASIC;
        String ask = "{\"password\":\"" + PASSWORD + "\"}";
        assertEquals(405, status(MockMvcRequestBuilders.get("/api/export"), null), "it cannot be fetched without the password");
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/export"), "{\"password\":\"not-it\"}"), "a wrong password gets nothing");
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/export"), "{}"), "no password gets nothing");
        MockHttpServletResponse response = send(MockMvcRequestBuilders.post("/api/export"), ask);

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Content-Disposition").contains("financial-dashboard-" + today() + ".xlsx"));
        assertTrue(response.getContentType().contains("spreadsheetml"));
        byte[] bytes = response.getContentAsByteArray();
        assertEquals('P', bytes[0]);   // an .xlsx file is a zip
        assertEquals('K', bytes[1]);
    }

    // ------------------------------------------------------------------------------------------ backups

    @Test
    void backupsCanBeMadeCheckedAndSentToASecondFolder() throws Exception {
        account("Bank", "BANK", "1000");

        String before = get("/api/backups");
        assertTrue(before.contains("\"level\":\"ERROR\"") && before.contains("No backup has been made yet."), before);

        String made = post("/api/backups", null);
        assertTrue(made.contains("\"ok\":true") && made.contains("backup-") && made.contains("\"level\":\"WARNING\""), made);

        java.nio.file.Path second = java.nio.file.Files.createTempDirectory("fd-api-second");
        try {
            String withCopy = put("/api/backups/settings", "{\"copyDir\":\"" + second.toString().replace("\\", "\\\\") + "\"}");
            assertTrue(withCopy.contains("\"level\":\"NONE\"") && withCopy.contains("\"banner\":null"), withCopy);
            try (var files = java.nio.file.Files.list(second)) {
                assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("backup-")));
            }
            assertTrue(put("/api/backups/settings", "{\"copyDir\":\"\"}").contains("\"copyDir\":null"));
        } finally {
            try (var files = java.nio.file.Files.list(second)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files::iterator) {
                    java.nio.file.Files.deleteIfExists(p);
                }
            }
            java.nio.file.Files.deleteIfExists(second);
        }
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/backups/settings"), "{\"copyDir\":\"not/absolute\"}"));
    }

    @Test
    void backupsNeedTheLoginLikeEverythingElse() throws Exception {
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");
        assertEquals(401, status(MockMvcRequestBuilders.get("/api/backups"), null));
        assertEquals(401, status(MockMvcRequestBuilders.post("/api/backups"), null));
        auth = BASIC;
        get("/api/backups");
    }

    // ------------------------------------------------------------------------------------------ notes

    @Test
    void theDiaryCanBeWrittenSearchedEditedAndDeleted() throws Exception {
        String day = today();
        long first = id(post("/api/notes", "{\"date\":\"" + day + "\",\"title\":\"Rent\",\"body\":\"Paid the landlord\"}"));
        post("/api/notes", "{\"date\":\"" + day + "\",\"body\":\"Called the bank\"}");
        post("/api/notes", "{\"date\":\"2020-01-05\",\"title\":\"Old\",\"body\":\"An old note\"}");

        assertTrue(get("/api/notes").contains("Paid the landlord") && get("/api/notes").contains("An old note"));
        assertTrue(get("/api/notes?date=" + day).contains("Called the bank") && !get("/api/notes?date=" + day).contains("An old note"));
        assertTrue(get("/api/notes?q=LANDLORD").contains("Rent") && !get("/api/notes?q=LANDLORD").contains("Called the bank"));
        String days = get("/api/notes/days");
        assertTrue(days.contains("\"date\":\"2020-01-05\",\"count\":1") && days.contains("\"date\":\"" + day + "\",\"count\":2"), days);

        assertTrue(put("/api/notes/" + first, "{\"date\":\"" + day + "\",\"title\":\"Rent paid\",\"body\":\"Done\"}").contains("Rent paid"));
        delete("/api/notes/" + first);
        assertTrue(!get("/api/notes").contains("Rent paid"));
    }

    @Test
    void invalidAndMissingNotesAreRefusedWithAMessage() throws Exception {
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/notes"), "{\"date\":\"" + today() + "\",\"body\":\"   \"}"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/notes"), "{\"body\":\"No day\"}"));
        assertEquals(404, status(MockMvcRequestBuilders.put("/api/notes/987654"), "{\"date\":\"" + today() + "\",\"body\":\"x\"}"));
        assertEquals(404, status(MockMvcRequestBuilders.delete("/api/notes/987654"), null));
    }

    // ------------------------------------------------------------------------------------------ reminders

    @Test
    void remindersCanBeAddedMovedTickedAndDeleted() throws Exception {
        String day = today();
        long first = id(post("/api/reminders", "{\"date\":\"" + day + "\",\"title\":\"Renew insurance\"}"));
        post("/api/reminders", "{\"date\":\"2020-01-05\",\"title\":\"Old reminder\"}");

        String all = get("/api/reminders");
        assertTrue(all.contains("Renew insurance") && all.contains("Old reminder") && all.contains("\"done\":false"), all);
        assertTrue(all.indexOf("Old reminder") < all.indexOf("Renew insurance"), "oldest date first");

        assertTrue(put("/api/reminders/" + first + "/done", "{\"done\":true}").contains("\"done\":true"));
        assertTrue(get("/api/reminders").contains("\"doneOn\":\"" + day + "\""));
        assertTrue(put("/api/reminders/" + first + "/done", "{\"done\":false}").contains("\"doneOn\":null"));
        assertTrue(put("/api/reminders/" + first, "{\"date\":\"2030-02-03\",\"title\":\"Renewed\"}").contains("2030-02-03"));
        delete("/api/reminders/" + first);
        assertTrue(!get("/api/reminders").contains("Renewed"));
    }

    @Test
    void aRepeatingReminderIsSavedListedAndCannotBeTicked() throws Exception {
        long id = id(post("/api/reminders", "{\"date\":\"1990-03-14\",\"title\":\"Asha birthday\",\"repeat\":\"YEARLY\"}"));
        post("/api/reminders", "{\"date\":\"2026-01-31\",\"title\":\"Rent\",\"repeat\":\"MONTHLY\"}");
        String all = get("/api/reminders");
        assertTrue(all.contains("\"repeat\":\"YEARLY\"") && all.contains("\"repeat\":\"MONTHLY\""), all);
        assertTrue(post("/api/reminders", "{\"date\":\"" + today() + "\",\"title\":\"Ordinary\"}").contains("\"repeat\":\"NONE\""));
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/reminders/" + id + "/done"), "{\"done\":true}"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/reminders"), "{\"date\":\"" + today() + "\",\"title\":\"x\",\"repeat\":\"DAILY\"}"), "an unknown setting is refused");
    }

    @Test
    void aDayOfARepeatingReminderIsTickedThroughItsOwnEndpoint() throws Exception {
        long id = id(post("/api/reminders", "{\"date\":\"2026-01-05\",\"title\":\"Plan\",\"repeat\":\"WEEKLY\"}"));
        String path = "/api/reminders/" + id + "/occurrences/2026-01-12/done";
        assertTrue(put(path, "{\"done\":true,\"doneOn\":\"2026-01-13\"}").contains("\"2026-01-12\":\"2026-01-13\""));
        assertTrue(get("/api/reminders").contains("\"2026-01-12\":\"2026-01-13\""), "it comes back in the list");
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/reminders/" + id + "/occurrences/2026-01-13/done"), "{\"done\":true}"), "not a day it falls on");
        assertTrue(put(path, "{\"done\":false}").contains("\"completions\":{}"));
    }

    @Test
    void invalidAndMissingRemindersAreRefusedWithAMessage() throws Exception {
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/reminders"), "{\"date\":\"" + today() + "\",\"title\":\"  \"}"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/reminders"), "{\"title\":\"No day\"}"));
        assertEquals(404, status(MockMvcRequestBuilders.put("/api/reminders/987654"), "{\"date\":\"" + today() + "\",\"title\":\"x\"}"));
        assertEquals(404, status(MockMvcRequestBuilders.put("/api/reminders/987654/done"), "{\"done\":true}"));
        assertEquals(404, status(MockMvcRequestBuilders.delete("/api/reminders/987654"), null));
    }

    @Test
    void remindersNeedTheLogin() throws Exception {
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");
        assertEquals(401, status(MockMvcRequestBuilders.get("/api/reminders"), null));
        auth = BASIC;
        get("/api/reminders");
    }

    @Test
    void theDiaryNeedsTheLogin() throws Exception {
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");
        assertEquals(401, status(MockMvcRequestBuilders.get("/api/notes"), null));
        auth = BASIC;
        get("/api/notes");
    }

    @Test
    void notesCanCarryTagsThatAreCleanedFilteredAndCounted() throws Exception {
        String day = today();
        post("/api/notes", "{\"date\":\"" + day + "\",\"body\":\"Paid the premium\",\"tags\":[\"#Insurance\",\"Car loan\"]}");
        post("/api/notes", "{\"date\":\"" + day + "\",\"body\":\"A renewal reminder\",\"tags\":[\"insurance\"]}");
        post("/api/notes", "{\"date\":\"" + day + "\",\"body\":\"No tags here\"}");

        String insurance = get("/api/notes?tag=insurance");
        assertTrue(insurance.contains("Paid the premium") && insurance.contains("renewal") && !insurance.contains("No tags here"), insurance);
        assertTrue(insurance.contains("\"tags\":[\"car-loan\",\"insurance\"]"), insurance);
        assertTrue(get("/api/notes?tag=insurance&q=renewal").contains("renewal") && !get("/api/notes?tag=insurance&q=renewal").contains("Paid"));
        assertTrue(get("/api/notes/tags").contains("{\"tag\":\"insurance\",\"count\":2}"), get("/api/notes/tags"));
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/notes"), "{\"date\":\"" + day + "\",\"body\":\"x\",\"tags\":[\"no good!\"]}"));
    }

    @Test
    void notesComeInPagesWithTheTotals() throws Exception {
        for (int i = 1; i <= 25; i++) {
            post("/api/notes", "{\"date\":\"" + LocalDate.now().minusDays(i) + "\",\"body\":\"paged note " + i + "\"}");
        }

        String second = get("/api/notes?page=1&size=10");
        assertTrue(second.contains("\"total\":25") && second.contains("\"pages\":3") && second.contains("\"page\":1") && second.contains("\"size\":10"), second);
        assertEquals(10, second.split("\"body\"").length - 1);
        assertTrue(second.contains("paged note 11") && second.contains("paged note 20") && !second.contains("paged note 10\""), second);

        String last = get("/api/notes?page=2&size=10");
        assertEquals(5, last.split("\"body\"").length - 1);
        assertEquals(10, get("/api/notes").split("\"body\"").length - 1);          // the default page size
        assertEquals(0, get("/api/notes?page=9&size=10").split("\"body\"").length - 1);
        assertTrue(get("/api/notes?q=paged&page=1&size=10").contains("\"total\":25"));
        assertTrue(get("/api/notes?date=" + LocalDate.now().minusDays(3)).contains("paged note 3"));
    }

    // ------------------------------------------------------------------------------------------ cross-site protection

    @Test
    void aChangeWithoutTheDashboardHeaderIsRefusedButReadsAreNot() throws Exception {
        long bank = account("Bank", "BANK", "1000");
        clientHeader = false;

        get("/api/accounts");                                                                      // reads need nothing
        MockHttpServletResponse refused = send(MockMvcRequestBuilders.post("/api/accounts/" + bank + "/deactivate"), null);
        assertEquals(403, refused.getStatus());
        assertTrue(body(refused).contains("X-Requested-With"), body(refused));
        assertEquals(403, status(MockMvcRequestBuilders.put("/api/accounts/" + bank), "{\"name\":\"x\",\"type\":\"BANK\"}"));
        assertEquals(403, status(MockMvcRequestBuilders.delete("/api/accounts/" + bank), null));
        assertEquals(403, status(MockMvcRequestBuilders.post("/api/notes"), "{\"date\":\"" + today() + "\",\"body\":\"x\"}"));

        clientHeader = true;
        assertTrue(get("/api/accounts/" + bank).contains("\"active\":true"));                   // nothing changed
        post("/api/accounts/" + bank + "/deactivate", null);                                      // with the header it works
        assertTrue(get("/api/accounts/" + bank).contains("\"active\":false"));
    }

    @Test
    void theHeaderMustHaveTheRightValue() throws Exception {
        clientHeader = false;
        MockHttpServletRequestBuilder wrong = MockMvcRequestBuilders.post("/api/ledger/setup").header("X-Requested-With", "XMLHttpRequest");
        assertEquals(403, status(wrong, null));
    }

    @Test
    void theProtectionAlsoCoversTheFirstPasswordAndSignedInUsers() throws Exception {
        clientHeader = false;
        assertEquals(403, status(MockMvcRequestBuilders.post("/api/auth/password"), "{\"password\":\"" + PASSWORD + "\"}"));
        clientHeader = true;
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");

        auth = BASIC;
        clientHeader = false;
        assertEquals(403, status(MockMvcRequestBuilders.put("/api/auth/password"),
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another-pass-1\"}"));   // a signed-in user, still refused
        assertEquals(403, status(MockMvcRequestBuilders.post("/api/backups"), null));
    }

    @Test
    void anUnauthenticatedChangeIsStill401NotAskedForTheHeader() throws Exception {
        post("/api/auth/password", "{\"password\":\"" + PASSWORD + "\"}");
        clientHeader = false;
        auth = null;
        assertEquals(401, status(MockMvcRequestBuilders.post("/api/accounts"), "{\"name\":\"x\",\"type\":\"BANK\"}"));
    }

    // ------------------------------------------------------------------------------------------ currencies

    @Test
    void aForeignAccountAndATransferBetweenCurrenciesWorkThroughTheApi() throws Exception {
        long bank = account("Bank", "BANK", "10000");
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/accounts"),
                "{\"name\":\"US\",\"type\":\"BANK\",\"openingBalance\":10,\"currency\":\"USD\"}"), "no rate yet");
        put("/api/exchange-rates", "{\"currency\":\"USD\",\"date\":\"2026-01-01\",\"rate\":80}");
        assertEquals(400, status(MockMvcRequestBuilders.put("/api/exchange-rates"), "{\"currency\":\"JPY\",\"date\":\"2026-01-01\",\"rate\":1}"));
        long us = id(post("/api/accounts", "{\"name\":\"US\",\"type\":\"BANK\",\"openingBalance\":10,\"currency\":\"USD\"}"));

        String accountList = get("/api/accounts");
        assertTrue(accountList.contains("\"currency\":\"USD\""), accountList);
        assertTrue(accountList.contains("\"balanceInr\":800"), accountList);

        String t = post("/api/transactions", "{\"date\":\"" + today() + "\",\"kind\":\"TRANSFER\",\"fromAccountId\":" + bank
                + ",\"toAccountId\":" + us + ",\"amount\":800,\"toAmount\":10}");
        assertTrue(t.contains("\"toAmount\":10"), t);
        assertTrue(get("/api/accounts").contains("\"balance\":20"), "ten dollars arrived on top of ten");
        assertEquals(400, status(MockMvcRequestBuilders.post("/api/transactions"), "{\"date\":\"" + today()
                + "\",\"kind\":\"TRANSFER\",\"fromAccountId\":" + bank + ",\"toAccountId\":" + us + ",\"amount\":800}"), "what arrived is needed");
        assertTrue(get("/api/exchange-rates").contains("\"rate\":80"));
    }
}
