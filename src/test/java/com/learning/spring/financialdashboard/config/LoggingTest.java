package com.learning.spring.financialdashboard.config;

import ch.qos.logback.classic.Level;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.NoteRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.CredentialService;
import com.learning.spring.financialdashboard.startup.CategorySeeder;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What gets logged, and, as important, what never does. */
@SpringBootTest
class LoggingTest {

    private static final String PASSWORD = "pw-SECRET-123";

    @Autowired
    WebApplicationContext context;
    @Autowired
    Filter springSecurityFilterChain;
    @Autowired
    RequestLoggingFilter requestLogging;
    @Autowired
    CredentialService credentials;
    @Autowired
    CategorySeeder seeder;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;
    @Autowired
    NoteRepository notes;
    @Autowired
    com.learning.spring.financialdashboard.repository.ReminderRepository reminders;
    @Autowired
    AppSettingRepository settings;

    MockMvc mvc;
    String auth;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestLogging, springSecurityFilterChain).build();
        wipe();
        auth = null;
    }

    @AfterEach
    void tearDown() {
        wipe();
        LogCapture.setLevel(RequestLoggingFilter.class, null);
    }

    private void wipe() {
        credentials.reset();
        transactions.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
        notes.deleteAll();
        reminders.deleteAll();
        settings.deleteAll();
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String json) throws Exception {
        if (auth != null) {
            request.header("Authorization", auth);
        }
        request.header("X-Requested-With", "FinancialDashboard");
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private long id(MockHttpServletResponse r) throws Exception {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(r.getContentAsString(StandardCharsets.UTF_8));
        assertTrue(m.find());
        return Long.parseLong(m.group(1));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void changesAreLoggedByIdAndKindAndNeverWithAmountsTextOrPasswords() throws Exception {
        try (LogCapture log = new LogCapture()) {
            send(MockMvcRequestBuilders.post("/api/auth/password"), "{\"password\":\"" + PASSWORD + "\"}");
            auth = basic("admin", PASSWORD);
            long bank = id(send(MockMvcRequestBuilders.post("/api/accounts"), "{\"name\":\"Secret bank\",\"type\":\"BANK\",\"openingBalance\":98765.43}"));
            send(MockMvcRequestBuilders.post("/api/transactions"), "{\"date\":\"" + LocalDate.now() + "\",\"kind\":\"INCOME\",\"toAccountId\":" + bank
                    + ",\"amount\":12345.67,\"note\":\"SECRET-NOTE-TEXT\",\"description\":\"SECRET-DESC\"}");
            send(MockMvcRequestBuilders.post("/api/notes"), "{\"date\":\"" + LocalDate.now() + "\",\"title\":\"SECRET-TITLE\",\"body\":\"SECRET-DIARY-TEXT\",\"tags\":[\"secret-tag\"]}");
            send(MockMvcRequestBuilders.get("/api/notes?q=SECRET-SEARCH"), null);
            send(MockMvcRequestBuilders.post("/api/reminders"), "{\"date\":\"" + LocalDate.now() + "\",\"title\":\"SECRET-REMINDER\"}");

            assertTrue(log.has("INFO The dashboard password was set"));
            assertTrue(log.has("INFO Account created: id=" + bank + " type=BANK"));
            assertTrue(log.has("INFO Transaction recorded: id="));
            assertTrue(log.has("INFO Note added: id="));
            assertTrue(log.has("INFO Reminder added: id="));
            assertTrue(log.has("INFO POST /api/transactions -> 201"));

            for (String secret : new String[]{"SECRET", "12345.67", "98765.43", "pw-"}) {
                assertFalse(log.has(secret), "the log must not contain '" + secret + "': " + log.lines());
            }
        }
    }

    @Test
    void readsAreLoggedOnlyWhenDebugIsOn() throws Exception {
        try (LogCapture log = new LogCapture()) {
            send(MockMvcRequestBuilders.get("/api/accounts"), null);
            assertFalse(log.has("GET /api/accounts"));

            LogCapture.setLevel(RequestLoggingFilter.class, Level.DEBUG);
            send(MockMvcRequestBuilders.get("/api/accounts"), null);
            assertTrue(log.has("DEBUG GET /api/accounts -> 200"));
        }
    }

    @Test
    void aRefusedSignInIsAWarningWithTheUserAndNeverThePassword() throws Exception {
        credentials.setInitial(PASSWORD);
        try (LogCapture log = new LogCapture()) {
            auth = basic("admin", "wrong-password-xyz");
            assertEquals(401, send(MockMvcRequestBuilders.get("/api/accounts"), null).getStatus());
            auth = basic("bad\u0007name", "wrong-password-xyz");
            send(MockMvcRequestBuilders.get("/api/accounts"), null);

            assertTrue(log.has("WARN Sign-in refused for user 'admin'"));
            assertTrue(log.has("WARN Sign-in refused for user 'bad?name'"));   // control characters cannot fake a log line
            assertTrue(log.has("WARN GET /api/accounts -> 401"));
            assertFalse(log.has("wrong-password-xyz"));
            assertFalse(log.has(PASSWORD));
        }
    }

    @Test
    void refusedAndMissingRequestsAreLoggedWithTheReason() throws Exception {
        try (LogCapture log = new LogCapture()) {
            send(MockMvcRequestBuilders.post("/api/accounts"), "{\"name\":\"\",\"type\":\"BANK\"}");
            send(MockMvcRequestBuilders.get("/api/accounts/987654"), null);

            assertTrue(log.has("INFO POST /api/accounts refused: Name is required"));
            assertTrue(log.has("INFO GET /api/accounts/987654 not found: Account 987654 not found"));
            assertTrue(log.has("INFO POST /api/accounts -> 400"));
        }
    }

    @Test
    void theStarterCategoriesAreLoggedOnTheFirstRunOnly() {
        try (LogCapture log = new LogCapture()) {
            seeder.seedIfEmpty();
            assertTrue(log.has("INFO First run: the starter categories were loaded"));
        }
        try (LogCapture log = new LogCapture()) {
            seeder.seedIfEmpty();   // categories exist now
            assertFalse(log.has("starter categories"));
        }
    }
}
