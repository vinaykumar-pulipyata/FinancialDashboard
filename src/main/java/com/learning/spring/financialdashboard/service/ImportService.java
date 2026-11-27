package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.model.Reminder;
import com.learning.spring.financialdashboard.model.Repeat;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.SetupService.SetupAccount;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads the workbook the Excel export writes into a NEW, EMPTY database (menu > Data > Import). It works in the order the data depends on
 * itself: exchange rates, categories, accounts, people (Loans), transactions, budgets, note folders and notes, reminders and their ticks.
 * Sheets that are only a report of other sheets (Net worth, Monthly, Bills ...) are read for nothing, and the areas that have their own cards
 * and rules (the Ledger, Emergency Fund, Mutual Funds, Shares, Recurring Deposits, Jewellery) are not loaded yet: the report says so, and a
 * transaction that touches them is skipped and counted.
 *
 * <p>Check-only mode runs the whole import inside a transaction that is rolled back, so it finds exactly the problems the real run would
 * (and changes nothing); the real run goes row by row, so one bad row is reported and the rest still load. It never runs on a database that
 * has an account or a transaction. Nothing is logged but counts.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** While the transactions load, every ordinary account holds this much more, so the order of rows on one day can never make a balance negative; it is taken off again at the end. */
    private static final BigDecimal CUSHION = new BigDecimal("10000000000");
    private static final String SEP = " › ";
    /** Names of the accounts the app makes for its own cards. */
    private static final Set<String> OWN_ACCOUNTS = Set.of("future expenses ledger", "mutual funds", "shares", "recurring deposits", "jewellery", "receivables");

    /** What one step did: how many rows it loaded and skipped, and what to tell the person. */
    public record Step(String name, int imported, int skipped, List<String> messages) {
    }

    public record Report(boolean applied, List<Step> steps, List<String> notLoaded) {

        public int problems() {
            return steps.stream().mapToInt(s -> s.messages().size()).sum();
        }
    }

    /** How far a run has got, for the page to show while it waits: what it is doing and a percentage. */
    public record Progress(boolean running, String doing, int percent) {
    }

    private static final List<String> STAGES = List.of("Exchange rates", "Categories", "Accounts", "People and loans", "Transactions", "The Ledger",
            "Recurring Deposits", "Jewellery", "Write-offs", "Mutual Funds and Shares", "The Emergency Fund", "Account settings", "Ring-fencing",
            "Budgets", "Notes", "Reminders");
    private volatile Progress progress = new Progress(false, "", 0);

    /** What the run in progress is doing (or the last one did), for GET /api/import/progress. */
    public Progress progress() {
        return progress;
    }

    private void stage(int index) {
        progress = new Progress(true, STAGES.get(index), index * 100 / STAGES.size());
    }

    /** Inside a long stage: row i of n. */
    private void within(int index, int i, int n) {
        int span = 100 / STAGES.size();
        progress = new Progress(true, STAGES.get(index) + " " + i + " of " + n, index * 100 / STAGES.size() + (n == 0 ? 0 : span * i / n));
    }

    private final AccountService accountService;
    private final SetupService setupService;
    private final CategoryService categoryService;
    private final LoanService loanService;
    private final TransactionService transactionService;
    private final BudgetService budgetService;
    private final NoteService noteService;
    private final ReminderService reminderService;
    private final ExchangeRateService exchangeRateService;
    private final PortfolioService portfolioService;
    private final EmergencyFundService emergencyFundService;
    private final LedgerService ledgerService;
    private final RecurringDepositService recurringDepositService;
    private final JewelleryService jewelleryService;
    private final RingFenceService ringFenceService;
    private final DiaryVault vault;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final PlatformTransactionManager transactionManager;

    public ImportService(AccountService accountService, SetupService setupService, CategoryService categoryService, LoanService loanService,
                         TransactionService transactionService, BudgetService budgetService, NoteService noteService, ReminderService reminderService,
                         ExchangeRateService exchangeRateService, PortfolioService portfolioService, EmergencyFundService emergencyFundService, LedgerService ledgerService, RecurringDepositService recurringDepositService, JewelleryService jewelleryService, RingFenceService ringFenceService, DiaryVault vault, AccountRepository accounts, TransactionRepository transactions,
                         PlatformTransactionManager transactionManager) {
        this.accountService = accountService;
        this.setupService = setupService;
        this.categoryService = categoryService;
        this.loanService = loanService;
        this.transactionService = transactionService;
        this.budgetService = budgetService;
        this.noteService = noteService;
        this.reminderService = reminderService;
        this.exchangeRateService = exchangeRateService;
        this.portfolioService = portfolioService;
        this.emergencyFundService = emergencyFundService;
        this.ledgerService = ledgerService;
        this.recurringDepositService = recurringDepositService;
        this.jewelleryService = jewelleryService;
        this.ringFenceService = ringFenceService;
        this.vault = vault;
        this.accounts = accounts;
        this.transactions = transactions;
        this.transactionManager = transactionManager;
    }

    /** Reads the workbook; apply = false only checks it (and changes nothing). */
    public Report run(InputStream file, boolean apply) throws IOException {
        if (accounts.count() > 0 || transactions.count() > 0) {
            throw new ValidationException("Import only works on an empty database: this one already has accounts or transactions. "
                    + "Start the app on a new, empty database (delete the data folder with the app stopped) and import there");
        }
        progress = new Progress(true, "Reading the file", 0);
        try (Workbook wb = new XSSFWorkbook(file)) {
            if (wb.getSheet("Accounts") == null || wb.getSheet("Transactions") == null) {
                throw new ValidationException("This is not an Excel file made by Export: it has no Accounts and Transactions sheets");
            }
            Report report;
            if (apply) {
                report = load(wb, true);
            } else {
                // The whole run, then everything is rolled back, so only the findings are kept.
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                report = template.execute(status -> {
                    Report found = load(wb, false);
                    status.setRollbackOnly();
                    return found;
                });
                vault.forget();   // a diary key made inside the rolled-back run must not stay in memory
            }
            log.info("Import {}: {} steps, {} problems", apply ? "done" : "checked", report.steps().size(), report.problems());
            return report;
        } finally {
            progress = new Progress(false, "", 100);
        }
    }

    // ------------------------------------------------------------------------------------------ the steps

    private Report load(Workbook wb, boolean applied) {
        List<Step> steps = new ArrayList<>();
        stage(0);
        exchangeRates(wb, steps);
        stage(1);
        Map<String, Category> categories = categories(wb, steps);
        stage(2);
        Map<String, Long> accountIds = accounts(wb, steps);
        stage(3);
        Map<String, Long> people = loans(wb, steps);
        Map<PortfolioKind, Plan> plans = portfolioPlans(wb);
        LedgerPlan ledgerPlan = ledgerPlan(wb);
        List<Row> rdRows = new ArrayList<>(), jewelleryRows = new ArrayList<>();
        stage(4);
        transactions(wb, steps, categories, accountIds, people, plans, ledgerPlan, rdRows, jewelleryRows);
        stage(5);
        ledger(wb, steps, ledgerPlan, categories, accountIds);
        stage(6);
        recurringDeposits(wb, steps, accountIds, rdRows);
        stage(7);
        jewellery(wb, steps, accountIds, jewelleryRows);
        stage(8);
        writeOffs(wb, steps, people);
        stage(9);
        for (Plan plan : plans.values()) {
            portfolio(wb, steps, plan, accountIds);
        }
        stage(10);
        emergencyFund(wb, steps, accountIds);
        stage(11);
        finishAccounts(wb, steps, accountIds);
        stage(12);
        ringFence(wb, steps, accountIds);
        stage(13);
        budgets(wb, steps, categories);
        stage(14);
        notes(wb, steps);
        stage(15);
        reminders(wb, steps);
        List<String> notLoaded = new ArrayList<>();   // what the file has that is left out: nothing is, now that every sheet with data is loaded
        return new Report(applied, steps, notLoaded);
    }

    private void exchangeRates(Workbook wb, List<Step> steps) {
        Sheet sheet = wb.getSheet("Exchange rates");
        if (sheet == null) {
            return;
        }
        Counter step = new Counter("Exchange rates");
        for (Row row : rows(sheet)) {
            try {
                exchangeRateService.save(Currency.valueOf(text(row, 0)), date(row, 1), amount(row, 2, Currency.RATE_SCALE));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        steps.add(step.done());
    }

    /** Creates the categories that are not there yet (the starter and fixed ones are found by name). Returns "kind|Name" or "kind|Parent › Sub" -> category. */
    private Map<String, Category> categories(Workbook wb, List<Step> steps) {
        Counter step = new Counter("Categories");
        Sheet sheet = wb.getSheet("Categories");
        if (sheet != null) {
            List<Row> rows = rows(sheet);
            for (int pass = 0; pass < 2; pass++) {   // main categories first, then their sub categories
                for (Row row : rows) {
                    String parent = text(row, 2);
                    if ((pass == 0) != parent.isEmpty() || "Yes".equalsIgnoreCase(text(row, 4))) {
                        continue;
                    }
                    try {
                        CategoryKind kind = "Income".equalsIgnoreCase(text(row, 1)) ? CategoryKind.INCOME : CategoryKind.EXPENSE;
                        Category parentCategory = parent.isEmpty() ? null : find(categoryService.list(kind), parent, null);
                        if (!parent.isEmpty() && parentCategory == null) {
                            throw new ValidationException("The category " + parent + " is missing");
                        }
                        Long parentId = parentCategory == null ? null : parentCategory.getId();
                        if (find(categoryService.list(kind), text(row, 0), parentId) == null) {
                            categoryService.create(new CategoryService.CategoryData(text(row, 0), kind, parentId));
                            step.ok();
                        } else {
                            step.skip();   // already there
                        }
                    } catch (RuntimeException e) {
                        step.fail(row, e);
                    }
                }
            }
        }
        steps.add(step.done());
        return categoryMap();
    }

    private static Category find(List<Category> all, String name, Long parentId) {
        return all.stream().filter(c -> c.getName().equalsIgnoreCase(name) && java.util.Objects.equals(c.getParentId(), parentId)).findFirst().orElse(null);
    }

    private Map<String, Category> categoryMap() {
        List<Category> all = categoryService.list(null);
        Map<Long, Category> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.getId(), c));
        Map<String, Category> map = new HashMap<>();
        for (Category c : all) {
            Category parent = c.getParentId() == null ? null : byId.get(c.getParentId());
            map.put(c.getKind() + "|" + (parent == null ? c.getName() : parent.getName() + SEP + c.getName()), c);
        }
        return map;
    }

    /** Creates the ordinary accounts; the app's own ones (Ledger, funds, Receivables ...) are left to their cards. Returns name -> id. */
    private Map<String, Long> accounts(Workbook wb, List<Step> steps) {
        Counter step = new Counter("Accounts");
        Map<String, Long> ids = new HashMap<>();
        Sheet sheet = wb.getSheet("Accounts");
        List<SetupAccount> items = new ArrayList<>();
        Set<String> skippedParents = new HashSet<>();
        for (Row row : rows(sheet)) {
            String name = text(row, 0);
            AccountType type;
            try {
                type = AccountType.valueOf(text(row, 1));
            } catch (IllegalArgumentException e) {
                step.fail(row, new ValidationException("Unknown account type " + text(row, 1)));
                continue;
            }
            if (type == AccountType.RECEIVABLE || type == AccountType.JEWELLERY || OWN_ACCOUNTS.contains(name.toLowerCase(Locale.ROOT))) {
                step.skip();
                skippedParents.add(name);
                continue;
            }
            BigDecimal opening = amount(row, 4, 2);
            if (opening == null) {
                opening = BigDecimal.ZERO;
            }
            boolean liability = type == AccountType.DEBT || type == AccountType.CREDIT_CARD;
            String currency = text(row, 17).isEmpty() ? null : text(row, 17);
            String parent = text(row, 3);
            items.add(new SetupAccount(name, type, integer(row, 2), parent.isEmpty() || skippedParents.contains(parent) ? null : parent,
                    liability ? opening : opening.add(CUSHION), amount(row, 9, 2), amount(row, 10, 2), text(row, 13).isEmpty() ? null : text(row, 13),
                    integer(row, 14), integer(row, 11), amount(row, 12, 2), integer(row, 16), currency));
        }
        if (!items.isEmpty()) {
            try {
                for (Account a : setupService.createAll(items)) {
                    ids.put(a.getName(), a.getId());
                    step.ok();
                }
            } catch (RuntimeException e) {
                step.messages.add("The accounts could not be created: " + e.getMessage());
                step.skipped += items.size();
            }
        }
        steps.add(step.done());
        return ids;
    }

    /** The Receivables total and the people. Returns name -> borrower id. */
    private Map<String, Long> loans(Workbook wb, List<Step> steps) {
        Counter step = new Counter("Loans");
        Map<String, Long> people = new HashMap<>();
        Sheet sheet = wb.getSheet("Loans");
        Sheet accountSheet = wb.getSheet("Accounts");
        if (sheet == null) {
            return people;
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (Row row : rows(accountSheet)) {
            if ("RECEIVABLE".equals(text(row, 1))) {
                total = amount(row, 4, 2) == null ? BigDecimal.ZERO : amount(row, 4, 2);
                any = true;
            }
        }
        if (any) {
            try {
                Account receivables = loanService.ensureReceivablesAccount();
                accountService.update(receivables.getId(), new AccountData(receivables.getName(), AccountType.RECEIVABLE, receivables.getLevel(), null, total, null, receivables.getNotes()));
            } catch (RuntimeException e) {
                step.messages.add("The Receivables account could not be set up: " + e.getMessage());
            }
        }
        for (Row row : rows(sheet)) {
            String name = text(row, 0);
            if (name.isEmpty() || name.startsWith("Earlier loans")) {
                continue;
            }
            try {
                Borrower b = loanService.create(new LoanService.BorrowerData(name, amount(row, 1, 2) == null ? BigDecimal.ZERO : amount(row, 1, 2)));
                people.put(b.getName(), b.getId());
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        steps.add(step.done());
        return people;
    }

    private void transactions(Workbook wb, List<Step> steps, Map<String, Category> categories, Map<String, Long> accountIds, Map<String, Long> people,
                              Map<PortfolioKind, Plan> plans, LedgerPlan ledgerPlan, List<Row> rdRows, List<Row> jewelleryRows) {
        Counter step = new Counter("Transactions");
        Sheet sheet = wb.getSheet("Transactions");
        // the export lists newest first; load oldest first (and, on one day, in the order they were made)
        List<Row> rows = new ArrayList<>(rows(sheet));
        java.util.Collections.reverse(rows);
        rows.sort(Comparator.comparing(r -> dateOrMax(r, 0)));
        int skippedOwn = 0;
        Sheet jewellerySheet = wb.getSheet("Jewellery");
        boolean jewelleryOn = jewellerySheet != null && jewellerySheet.getRow(0) != null && "Kind".equals(text(jewellerySheet.getRow(0), 0));
        Set<String> rdNames = null;
        Sheet rdSheet = wb.getSheet("Recurring Deposits");
        if (rdSheet != null) {
            rdNames = new HashSet<>();
            for (Row row : rows(rdSheet)) {
                rdNames.add(text(row, 0));
            }
        }
        int done = 0;
        for (Row row : rows) {
            if (done++ % 20 == 0) {
                within(4, done, rows.size());
            }
            try {
                String from = text(row, 2), to = text(row, 3), category = text(row, 6);
                TransactionKind kind = TransactionKind.valueOf(text(row, 1));
                if (jewelleryOn && (JewelleryService.ACCOUNT_NAME.equalsIgnoreCase(from) || JewelleryService.ACCOUNT_NAME.equalsIgnoreCase(to))) {
                    jewelleryRows.add(row);   // loaded with its items
                    continue;
                }
                if (rdNames != null && (RecurringDepositService.ACCOUNT_NAME.equalsIgnoreCase(from) || RecurringDepositService.ACCOUNT_NAME.equalsIgnoreCase(to)
                        || (kind == TransactionKind.INCOME && rdNames.contains(text(row, 9).startsWith("Interest: ") ? text(row, 9).substring(10) : "")))) {
                    rdRows.add(row);   // loaded with its deposit
                    continue;
                }
                if (ledgerPlan != null && (ledgerPlan.name.equalsIgnoreCase(from) || ledgerPlan.name.equalsIgnoreCase(to))) {
                    ledgerPlan.rows.add(row);   // loaded with its buckets
                    continue;
                }
                Plan plan = planFor(plans, from, to, text(row, 8));
                if (plan != null) {
                    plan.rows.add(row);   // loaded with its portfolio, in date order with the value updates
                    continue;
                }
                if (OWN_ACCOUNTS.contains(from.toLowerCase(Locale.ROOT)) || OWN_ACCOUNTS.contains(to.toLowerCase(Locale.ROOT)) || !text(row, 8).isEmpty()) {
                    skippedOwn++;
                    continue;
                }
                if (category.startsWith("Balance Adjustment")) {
                    Long account = id(accountIds, kind == TransactionKind.INCOME ? to : from, "account");
                    accountService.restoreAdjustment(account, kind, amount(row, 4, 2), date(row, 0), blankToNull(text(row, 9)));
                    step.ok();
                    continue;
                }
                Long fromId = from.isEmpty() ? null : id(accountIds, from, "account");
                Long toId = to.isEmpty() ? null : id(accountIds, to, "account");
                Long categoryId = null;
                if (!category.isEmpty()) {
                    Category c = categories.get((kind == TransactionKind.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE) + "|" + category);
                    if (c == null) {
                        throw new ValidationException("The category " + category + " is missing");
                    }
                    categoryId = c.getId();
                }
                Long borrower = text(row, 7).isEmpty() ? null : id(people, text(row, 7), "person");
                List<String> tags = text(row, 11).isEmpty() ? List.of() : java.util.Arrays.stream(text(row, 11).split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
                transactionService.create(new TransactionData(date(row, 0), kind, fromId, toId, amount(row, 4, 2), amount(row, 5, 2), categoryId,
                        blankToNull(text(row, 9)), blankToNull(text(row, 10)), borrower, tags, amount(row, 13, 2)));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        if (skippedOwn > 0) {
            step.messages.add(skippedOwn + " transactions touch the Ledger, Emergency Fund, Mutual Funds, Shares, Recurring Deposits, Jewellery or Receivables account and were not loaded");
            step.skipped += skippedOwn;
        }
        steps.add(step.done());
    }

    // ------------------------------------------------------------------------------------------ Mutual Funds and Shares

    /** What one portfolio sheet holds: its holdings as they end, the day-by-day values, and the transactions that touch it. */
    private static final class Plan {
        final PortfolioKind kind;
        final Sheet card;
        final Map<String, BigDecimal[]> finals = new java.util.LinkedHashMap<>();   // holding -> invested, current value, quantity
        final Map<String, List<Object[]>> snaps = new HashMap<>();                  // holding -> date, invested, value (oldest first)
        final List<Row> rows = new ArrayList<>();

        Plan(PortfolioKind kind, Sheet card) {
            this.kind = kind;
            this.card = card;
        }

        boolean knows(String holding) {
            return finals.containsKey(holding) || snaps.containsKey(holding);
        }
    }

    private Map<PortfolioKind, Plan> portfolioPlans(Workbook wb) {
        Map<PortfolioKind, Plan> plans = new java.util.LinkedHashMap<>();
        for (PortfolioKind kind : PortfolioKind.values()) {
            Sheet card = wb.getSheet(kind.getLabel());
            if (card == null || card.getRow(0) == null || !kind.getLabel().equals(text(card.getRow(0), 0))) {
                continue;   // not set up, or nothing there
            }
            Plan plan = new Plan(kind, card);
            for (int i = 8; i <= card.getLastRowNum(); i++) {
                Row row = card.getRow(i);
                if (row == null || text(row, 0).isEmpty()) {
                    break;
                }
                plan.finals.put(text(row, 0), new BigDecimal[]{amount(row, 1, 2), amount(row, 2, 2), amount(row, 5, 4)});
            }
            Sheet history = wb.getSheet("Holding history");
            for (Row row : rows(history)) {
                if (kind.getLabel().equals(text(row, 0))) {
                    plan.snaps.computeIfAbsent(text(row, 1), k -> new ArrayList<>()).add(new Object[]{date(row, 2), amount(row, 3, 2), amount(row, 4, 2)});
                }
            }
            plan.snaps.values().forEach(list -> list.sort(Comparator.comparing(o -> (LocalDate) o[0])));
            plans.put(kind, plan);
        }
        return plans;
    }

    /** The portfolio a transaction belongs to (it is into or out of its account, or a dividend of one of its holdings), or null. */
    private static Plan planFor(Map<PortfolioKind, Plan> plans, String from, String to, String holding) {
        for (Plan plan : plans.values()) {
            String label = plan.kind.getLabel();
            if (label.equalsIgnoreCase(from) || label.equalsIgnoreCase(to) || (!holding.isEmpty() && plan.knows(holding))) {
                return plan;
            }
        }
        return null;
    }

    /**
     * Replays one portfolio: holdings are made on the day of their first event (an Invest that starts a fund, or the first recorded value
     * for one already held), then every Invest, Withdraw, dividend and value update in date order (on one day the money first, then the
     * values), and at last each holding is set to what the card says it ends as. Money moves through the same calls the buttons use.
     */
    private void portfolio(Workbook wb, List<Step> steps, Plan plan, Map<String, Long> accountIds) {
        Counter step = new Counter(plan.kind.getLabel());
        PortfolioKind kind = plan.kind;
        try {
            portfolioService.setUp(kind);
        } catch (RuntimeException e) {
            step.messages.add("Could not set it up: " + e.getMessage());
            steps.add(step.done());
            return;
        }
        // events: {date, 0 money / 1 value, sequence, payload}
        List<Object[]> events = new ArrayList<>();
        int seq = 0;
        List<Row> money = new ArrayList<>(plan.rows);
        java.util.Collections.reverse(money);   // the sheet is newest first: oldest first
        for (Row row : money) {
            events.add(new Object[]{dateOrMax(row, 0), 0, seq++, row});
        }
        for (Map.Entry<String, List<Object[]>> e : plan.snaps.entrySet()) {
            for (Object[] snap : e.getValue()) {
                events.add(new Object[]{snap[0], 1, seq++, new Object[]{e.getKey(), snap[1], snap[2]}});
            }
        }
        events.sort(Comparator.comparing((Object[] ev) -> (LocalDate) ev[0]).thenComparing(ev -> (Integer) ev[1]).thenComparing(ev -> (Integer) ev[2]));
        Map<String, Long> holdingIds = new HashMap<>();
        for (Object[] ev : events) {
            try {
                if ((Integer) ev[1] == 0) {
                    portfolioMoney(plan, (Row) ev[3], accountIds, holdingIds, step);
                } else {
                    Object[] snap = (Object[]) ev[3];
                    String name = (String) snap[0];
                    Long id = holdingIds.get(name);
                    if (id == null) {
                        portfolioService.addHolding(kind, new PortfolioService.HoldingData(name, (BigDecimal) snap[1], (BigDecimal) snap[2]));
                        holdingIds.put(name, holdingId(kind, name));
                    } else {
                        BigDecimal have = investedOf(kind, id);
                        portfolioService.updateValue(kind, id, new PortfolioService.ValueRequest((BigDecimal) snap[2],
                                have.compareTo((BigDecimal) snap[1]) == 0 ? null : (BigDecimal) snap[1], (LocalDate) ev[0]));
                    }
                    step.ok();
                }
            } catch (RuntimeException e) {
                if (step.messages.size() < 25) {
                    step.messages.add("On " + ev[0] + ": " + e.getMessage());
                }
                step.skipped++;
            }
        }
        // each holding ends as the card says (a holding with no history at all is made now)
        for (Map.Entry<String, BigDecimal[]> e : plan.finals.entrySet()) {
            try {
                BigDecimal[] end = e.getValue();
                BigDecimal invested = end[0] == null ? BigDecimal.ZERO : end[0], value = end[1] == null ? invested : end[1];
                Long id = holdingIds.get(e.getKey());
                if (id == null) {
                    portfolioService.addHolding(kind, new PortfolioService.HoldingData(e.getKey(), invested, value, end[2]));
                    holdingIds.put(e.getKey(), holdingId(kind, e.getKey()));
                } else {
                    var held = portfolioService.get(kind).holdings().stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow();
                    boolean same = held.currentValue().compareTo(value) == 0 && held.investedAmount().compareTo(invested) == 0
                            && (end[2] == null || (held.quantity() != null && held.quantity().compareTo(end[2]) == 0));
                    if (!same) {   // only when the replay did not already end here, so no extra point is added to the value history
                        List<Object[]> own = plan.snaps.getOrDefault(e.getKey(), List.of());   // on the day of its last recorded value, which this replaces
                        portfolioService.updateValue(kind, id, new PortfolioService.ValueRequest(value,
                                held.investedAmount().compareTo(invested) == 0 ? null : invested, own.isEmpty() ? null : (LocalDate) own.get(own.size() - 1)[0], end[2]));
                    }
                }
                step.ok();
            } catch (RuntimeException ex) {
                step.messages.add("Holding: " + ex.getMessage());
                step.skipped++;
            }
        }
        checkTotals(plan, step);
        steps.add(step.done());
    }

    private void portfolioMoney(Plan plan, Row row, Map<String, Long> accountIds, Map<String, Long> holdingIds, Counter step) {
        PortfolioKind kind = plan.kind;
        String label = kind.getLabel(), from = text(row, 2), to = text(row, 3), holding = text(row, 8);
        if (holding.isEmpty() && label.equalsIgnoreCase(from) && text(row, 9).startsWith("Withdrawn from ")) {
            holding = text(row, 9).substring("Withdrawn from ".length());   // a withdrawal names its holding in its note
        }
        BigDecimal amount = amount(row, 4, 2);
        LocalDate day = date(row, 0);
        Long holdingId = holding.isEmpty() ? null : holdingIds.get(holding);
        if (TransactionKind.INCOME.name().equals(text(row, 1))) {   // a dividend
            if (holdingId == null) {
                holdingId = makeHolding(plan, holding, holdingIds);
            }
            portfolioService.dividend(kind, holdingId, new PortfolioService.DividendRequest(id(accountIds, to, "account"), amount, day));
        } else if (label.equalsIgnoreCase(to)) {                      // invested
            Long bank = id(accountIds, from, "account");
            if (!holding.isEmpty() && holdingId == null) {             // the transfer that started the fund
                portfolioService.invest(kind, new PortfolioService.InvestRequest(bank, amount, null, holding, day));
                holdingIds.put(holding, holdingId(kind, holding));
            } else {
                portfolioService.invest(kind, new PortfolioService.InvestRequest(bank, amount, holdingId, null, day));
            }
        } else {                                                       // withdrawn
            if (holdingId == null && plan.knows(holding)) {
                holdingId = makeHolding(plan, holding, holdingIds);
            }
            if (holdingId == null) {
                throw new ValidationException("A withdrawal from a holding that is not in the file's list");
            }
            BigDecimal before = investedOf(kind, holdingId);
            BigDecimal cost = null;
            for (Object[] snap : plan.snaps.getOrDefault(holding, List.of())) {
                if (day.equals(snap[0])) {
                    cost = before.subtract((BigDecimal) snap[1]).max(BigDecimal.ZERO);   // what it cost, from the value kept that day
                }
            }
            portfolioService.withdraw(kind, holdingId, new PortfolioService.WithdrawRequest(id(accountIds, to, "account"), amount, day, cost));
        }
        step.ok();
    }

    /** A holding the file only mentions in a transaction: made with nothing in it, so the payment has something to belong to. */
    private Long makeHolding(Plan plan, String name, Map<String, Long> holdingIds) {
        if (name.isEmpty()) {
            throw new ValidationException("A dividend with no holding named");
        }
        List<Object[]> first = plan.snaps.getOrDefault(name, List.of());   // held before the first recorded value: that value is what it had
        portfolioService.addHolding(plan.kind, new PortfolioService.HoldingData(name, first.isEmpty() ? BigDecimal.ZERO : (BigDecimal) first.get(0)[1],
                first.isEmpty() ? BigDecimal.ZERO : (BigDecimal) first.get(0)[2]));
        Long id = holdingId(plan.kind, name);
        holdingIds.put(name, id);
        return id;
    }

    private Long holdingId(PortfolioKind kind, String name) {
        return portfolioService.get(kind).holdings().stream().filter(h -> h.name().equals(name)).findFirst().orElseThrow().id();
    }

    private BigDecimal investedOf(PortfolioKind kind, Long id) {
        return portfolioService.get(kind).holdings().stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow().investedAmount();
    }

    /** Tells the person when the totals of what was loaded are not those on the card in the file. */
    private void checkTotals(Plan plan, Counter step) {
        PortfolioService.View v = portfolioService.get(plan.kind);
        String[] labels = {"Current value", "Invested (holdings)", "Total invested", "Gain or loss", "Gain %", "Profit from sales"};
        BigDecimal[] loaded = {v.currentValue(), v.investedAmount(), v.totalInvested(), null, null, v.realizedGain()};
        for (int i = 0; i < labels.length; i++) {
            Row row = plan.card.getRow(1 + i);
            BigDecimal expected = row == null || loaded[i] == null ? null : amount(row, 1, 2);
            if (expected != null && expected.compareTo(loaded[i].setScale(2, RoundingMode.HALF_UP)) != 0) {
                step.messages.add(labels[i] + " is " + loaded[i].setScale(2, RoundingMode.HALF_UP) + " here but " + expected + " in the file");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ Jewellery

    /**
     * The account is set up, its opening balance is what the file says (the items already owned, before any sale of them), every transfer
     * into or out of it is saved on its own day, and each item is added as it stands now, linked to the transfer "Bought name" that paid for
     * it when there is one. The profit or loss of a sale is an ordinary transaction on the bank account and loads with the others.
     */
    private void jewellery(Workbook wb, List<Step> steps, Map<String, Long> accountIds, List<Row> jewelleryRows) {
        Sheet sheet = wb.getSheet("Jewellery");
        if (sheet == null || sheet.getRow(0) == null || !"Kind".equals(text(sheet.getRow(0), 0))) {
            return;   // not set up
        }
        Counter step = new Counter("Jewellery");
        try {
            jewelleryService.setUp();
            for (Row row : rows(wb.getSheet("Accounts"))) {
                if ("JEWELLERY".equals(text(row, 1)) && amount(row, 4, 2) != null) {
                    jewelleryService.restoreOpening(amount(row, 4, 2));
                }
            }
        } catch (RuntimeException e) {
            step.messages.add("Could not set it up: " + e.getMessage());
            steps.add(step.done());
            return;
        }
        Long jewelleryAccount = jewelleryService.get().accountId();
        Map<String, Long> bought = new HashMap<>();   // item name -> the transfer that paid for it
        List<Row> money = new ArrayList<>(jewelleryRows);
        java.util.Collections.reverse(money);
        money.sort(Comparator.comparing(r -> dateOrMax(r, 0)));
        for (Row row : money) {
            try {
                Long from = JewelleryService.ACCOUNT_NAME.equalsIgnoreCase(text(row, 2)) ? jewelleryAccount : id(accountIds, text(row, 2), "account");
                Long to = JewelleryService.ACCOUNT_NAME.equalsIgnoreCase(text(row, 3)) ? jewelleryAccount : id(accountIds, text(row, 3), "account");
                Long id = jewelleryService.restoreTransfer(from, to, amount(row, 4, 2), date(row, 0), blankToNull(text(row, 9)));
                if (text(row, 9).startsWith("Bought ")) {
                    bought.putIfAbsent(text(row, 9).substring("Bought ".length()), id);
                }
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        for (Row row : rows(sheet)) {
            if (text(row, 0).equals("Total")) {
                continue;
            }
            try {
                jewelleryService.restoreItem(text(row, 0), text(row, 1), text(row, 2), amount(row, 3, 3), amount(row, 4, 2), bought.get(text(row, 1)));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        steps.add(step.done());
    }

    // ------------------------------------------------------------------------------------------ Write-offs

    /** Each debt that was let go of, on its day with its reason (a negative amount is one where it was you who owed them). */
    private void writeOffs(Workbook wb, List<Step> steps, Map<String, Long> people) {
        Sheet sheet = wb.getSheet("Write-offs");
        if (sheet == null) {
            return;
        }
        Counter step = new Counter("Write-offs");
        for (Row row : rows(sheet)) {
            try {
                loanService.restoreWriteOff(id(people, text(row, 1), "person"), amount(row, 2, 2), date(row, 0), text(row, 3));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        steps.add(step.done());
    }

    // ------------------------------------------------------------------------------------------ Recurring Deposits

    /**
     * Each deposit is opened with its terms (and what was already in it), every deposit made into it is replayed as a dated transfer from the
     * bank or cash account the file names, and a closed one is closed at last with what came back, on its day, into the account the closing
     * transfer (or the interest) went to. The closing transfer and the interest income are made by the close, so they are not loaded twice.
     */
    private void recurringDeposits(Workbook wb, List<Step> steps, Map<String, Long> accountIds, List<Row> rdRows) {
        Sheet sheet = wb.getSheet("Recurring Deposits");
        if (sheet == null) {
            return;
        }
        Counter step = new Counter("Recurring Deposits");
        try {
            recurringDepositService.setUp();
        } catch (RuntimeException e) {
            step.messages.add("Could not set it up: " + e.getMessage());
            steps.add(step.done());
            return;
        }
        Map<String, Long> ids = new HashMap<>();
        List<Row> deposits = rows(sheet);
        for (Row row : deposits) {
            try {
                BigDecimal already = amount(row, 13, 2);
                recurringDepositService.open(new RecurringDepositService.OpenData(text(row, 0), date(row, 2), integer(row, 3), amount(row, 5, 2),
                        amount(row, 6, 2), already == null ? BigDecimal.ZERO : already));
                ids.put(text(row, 0), recurringDepositService.get().open().stream().filter(d -> d.name().equals(text(row, 0))).findFirst().orElseThrow().id());
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        List<Row> money = new ArrayList<>(rdRows);
        java.util.Collections.reverse(money);   // newest first in the sheet
        money.sort(Comparator.comparing(r -> dateOrMax(r, 0)));
        Map<String, Long> closingBank = new HashMap<>();   // deposit -> the account its money came back to
        for (Row row : money) {
            String note = text(row, 9);
            try {
                if (text(row, 3).equalsIgnoreCase(RecurringDepositService.ACCOUNT_NAME) && note.startsWith("Deposit: ")) {
                    Long id = ids.get(note.substring("Deposit: ".length()));
                    if (id == null) {
                        throw new ValidationException("A deposit into a recurring deposit that is not in the file's list");
                    }
                    recurringDepositService.deposit(id, new RecurringDepositService.DepositRequest(id(accountIds, text(row, 2), "account"), amount(row, 4, 2), date(row, 0)));
                    step.ok();
                } else if (note.startsWith("Closed: ") && text(row, 2).equalsIgnoreCase(RecurringDepositService.ACCOUNT_NAME)) {
                    closingBank.put(note.substring("Closed: ".length()), id(accountIds, text(row, 3), "account"));
                } else if (note.startsWith("Interest: ")) {
                    closingBank.putIfAbsent(note.substring("Interest: ".length()), id(accountIds, text(row, 3), "account"));
                } else {
                    throw new ValidationException("A transaction of the Recurring Deposits account that no deposit explains");
                }
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        for (Row row : deposits) {
            if (!"CLOSED".equals(text(row, 1))) {
                continue;
            }
            try {
                Long id = ids.get(text(row, 0));
                Long to = closingBank.get(text(row, 0));
                if (id == null || to == null) {
                    throw new ValidationException("A closed deposit with no closing transfer to a bank account");
                }
                recurringDepositService.close(id, new RecurringDepositService.CloseRequest(to, amount(row, 11, 2), date(row, 10)));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        steps.add(step.done());
    }

    // ------------------------------------------------------------------------------------------ the Ledger

    /** The Ledger sheet read: its name, its buckets (open and closed) and every entry, plus the transactions that touch its account. */
    private static final class LedgerPlan {
        String name;
        final List<Row> open = new ArrayList<>();
        final List<Row> closed = new ArrayList<>();
        final List<Row> entries = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
    }

    private LedgerPlan ledgerPlan(Workbook wb) {
        Sheet sheet = wb.getSheet("Ledger");
        if (sheet == null || sheet.getRow(0) == null || text(sheet.getRow(0), 0).isEmpty() || text(sheet.getRow(0), 0).startsWith("The Future Expenses Ledger is not")) {
            return null;
        }
        LedgerPlan plan = new LedgerPlan();
        plan.name = text(sheet.getRow(0), 0);
        boolean closed = false;
        for (int i = 5; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || text(row, 0).isEmpty()) {
                continue;
            }
            if (text(row, 0).equals("Closed buckets")) {
                closed = true;
                i++;   // its header
                continue;
            }
            (closed ? plan.closed : plan.open).add(row);
        }
        plan.entries.addAll(rows(wb.getSheet("Ledger entries")));
        return plan;
    }

    /**
     * Rebuilds the Ledger from its sheets: the account, every bucket exactly as it ends (closed ones too), then each entry in order. Fundings,
     * bill payments and interest are matched to the account's transactions by day and amount and saved with them; moves, closes, openings and
     * corrections are entries on their own. So the buckets, their history and the Ledger's balance come back as they were.
     */
    private void ledger(Workbook wb, List<Step> steps, LedgerPlan plan, Map<String, Category> categories, Map<String, Long> accountIds) {
        if (plan == null) {
            return;
        }
        Counter step = new Counter("Ledger");
        try {
            ledgerService.setUp();
        } catch (RuntimeException e) {
            step.messages.add("Could not set it up: " + e.getMessage());
            steps.add(step.done());
            return;
        }
        Map<String, Long> bucketIds = new HashMap<>();
        for (Row row : plan.open) {
            try {
                boolean buffer = "Yes".equalsIgnoreCase(text(row, 8));
                bucketIds.put(text(row, 0), ledgerService.restoreBucket(text(row, 0), buffer, amount(row, 2, 2), amount(row, 3, 2), integer(row, 4),
                        dateOrNull(row, 5), dateOrNull(row, 6), dateOrNull(row, 7), null));
                if (buffer) {
                    bucketIds.put("Buffer", bucketIds.get(text(row, 0)));
                }
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        for (Row row : plan.closed) {
            try {
                bucketIds.putIfAbsent(text(row, 0), ledgerService.restoreBucket(text(row, 0), false, BigDecimal.ZERO, null, null, null, null, null, dateOrNull(row, 2)));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        // the account's transactions, oldest first, each to be used once
        List<Row> pool = new ArrayList<>(plan.rows);
        java.util.Collections.reverse(pool);
        pool.sort(Comparator.comparing(r -> dateOrMax(r, 0)));
        Set<Row> used = new HashSet<>();
        Set<Row> usedEntries = new HashSet<>();
        for (Row entry : plan.entries) {
            if (usedEntries.contains(entry)) {
                continue;
            }
            try {
                LocalDate day = date(entry, 0);
                String bucket = text(entry, 1);
                Long bucketId = bucketIds.get(bucket);
                if (bucketId == null) {
                    throw new ValidationException("The bucket is not in the file's list");
                }
                com.learning.spring.financialdashboard.model.EntryKind kind = com.learning.spring.financialdashboard.model.EntryKind.valueOf(text(entry, 2));
                BigDecimal amount = amount(entry, 3, 2);
                switch (kind) {
                    case FUND -> {
                        Row transfer = takeRow(pool, used, day, null, "TRANSFER", plan.name, false);
                        if (transfer == null) {
                            throw new ValidationException("A funding with no transfer in the Transactions sheet");
                        }
                        Map<Long, BigDecimal> credits = new java.util.LinkedHashMap<>();
                        BigDecimal total = amount(transfer, 4, 2), sum = BigDecimal.ZERO;
                        for (Row candidate : plan.entries) {
                            if (sum.compareTo(total) >= 0) {
                                break;
                            }
                            if (!usedEntries.contains(candidate) && "FUND".equals(text(candidate, 2)) && day.equals(dateOrNull(candidate, 0))) {
                                BigDecimal share = amount(candidate, 3, 2);
                                credits.merge(bucketIds.get(text(candidate, 1)), share, BigDecimal::add);
                                sum = sum.add(share);
                                usedEntries.add(candidate);
                            }
                        }
                        if (sum.compareTo(total) != 0) {
                            throw new ValidationException("The funding entries add up to " + sum + ", the transfer is " + total);
                        }
                        ledgerService.restoreFunding(id(accountIds, text(transfer, 2), "account"), total, day, blankToNull(text(transfer, 9)), credits);
                    }
                    case PAY -> {
                        Row paid = takeRow(pool, used, day, amount.abs(), "EXPENSE", plan.name, true);
                        if (paid == null) {
                            throw new ValidationException("A payment with no expense in the Transactions sheet");
                        }
                        Category c = text(paid, 6).isEmpty() ? null : categories.get(CategoryKind.EXPENSE + "|" + text(paid, 6));
                        ledgerService.restorePayment(bucketId, amount.abs(), day, c == null ? null : c.getId(), blankToNull(text(paid, 9)));
                    }
                    case INTEREST -> {
                        Row credited = takeRow(pool, used, day, amount, "INCOME", plan.name, false);
                        if (credited == null) {
                            throw new ValidationException("Interest with no income in the Transactions sheet");
                        }
                        Category c = text(credited, 6).isEmpty() ? null : categories.get(CategoryKind.INCOME + "|" + text(credited, 6));
                        ledgerService.restoreInterest(amount, day, c == null ? null : c.getId(), blankToNull(text(credited, 9)));
                    }
                    default -> ledgerService.restoreEntry(bucketId, day, kind, amount, blankToNull(text(entry, 4)));
                }
                usedEntries.add(entry);
                step.ok();
            } catch (RuntimeException e) {
                step.fail(entry, e);
            }
        }
        long left = pool.stream().filter(r -> !used.contains(r)).count();
        if (left > 0) {
            step.messages.add(left + " transactions of the Ledger account match no entry and were not loaded");
            step.skipped += (int) left;
        }
        steps.add(step.done());
    }

    /** The first unused transaction of the Ledger account on a day (with this amount, when given), of a kind; byFrom: the money leaves the Ledger. */
    private static Row takeRow(List<Row> pool, Set<Row> used, LocalDate day, BigDecimal amount, String kind, String ledgerName, boolean byFrom) {
        for (Row row : pool) {
            if (used.contains(row) || !kind.equals(text(row, 1)) || !day.equals(dateOrNull(row, 0))) {
                continue;
            }
            if (amount != null && amount.compareTo(amount(row, 4, 2)) != 0) {
                continue;
            }
            if (kind.equals("TRANSFER") ? !ledgerName.equalsIgnoreCase(text(row, 3)) : byFrom ? !ledgerName.equalsIgnoreCase(text(row, 2)) : !ledgerName.equalsIgnoreCase(text(row, 3))) {
                continue;
            }
            used.add(row);
            return row;
        }
        return null;
    }

    /** The Emergency Fund: its account came with the other accounts (a goal fund), so only its card settings are left to save. */
    private void emergencyFund(Workbook wb, List<Step> steps, Map<String, Long> accountIds) {
        Sheet sheet = wb.getSheet("Emergency Fund");
        if (sheet == null || sheet.getRow(0) == null || !"Item".equals(text(sheet.getRow(0), 0))) {
            return;   // not set up
        }
        Counter step = new Counter("Emergency Fund");
        Map<String, Row> items = new HashMap<>();
        for (Row row : rows(sheet)) {
            items.put(text(row, 0), row);
        }
        try {
            Long accountId = id(accountIds, text(items.get("Account"), 1), "account");
            com.learning.spring.financialdashboard.model.TargetMode mode = com.learning.spring.financialdashboard.model.TargetMode.valueOf(text(items.get("Target set by"), 1));
            BigDecimal target = amount(items.get("Target"), 1, 2);
            emergencyFundService.save(new EmergencyFundService.Settings(accountId, null, mode,
                    integer(items.get("Target months"), 1), mode == com.learning.spring.financialdashboard.model.TargetMode.AMOUNT ? target : null,
                    items.containsKey("Monthly essential expenses") ? amount(items.get("Monthly essential expenses"), 1, 2) : null,
                    items.containsKey("Planned monthly contribution") ? amount(items.get("Planned monthly contribution"), 1, 2) : null));
            step.ok();
        } catch (RuntimeException e) {
            step.messages.add("The Emergency Fund settings were not saved: " + e.getMessage());
            step.skipped++;
        }
        steps.add(step.done());
    }

    /** Takes the cushion off, then switches off what the sheets say is inactive. */
    private void finishAccounts(Workbook wb, List<Step> steps, Map<String, Long> accountIds) {
        Counter step = new Counter("Account settings");
        for (Row row : rows(wb.getSheet("Accounts"))) {
            Long id = accountIds.get(text(row, 0));
            if (id == null) {
                continue;
            }
            try {
                AccountType type = AccountType.valueOf(text(row, 1));
                BigDecimal opening = amount(row, 4, 2) == null ? BigDecimal.ZERO : amount(row, 4, 2);
                Account a = accountService.get(id);
                accountService.update(id, new AccountData(a.getName(), type, a.getLevel(), a.getParentId(), opening, a.getTargetAmount(),
                        blankToNull(text(row, 8)), a.getCurrency().name()));
                if ("No".equalsIgnoreCase(text(row, 6))) {
                    accountService.deactivate(id);
                }
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        Sheet categories = wb.getSheet("Categories");
        if (categories != null) {
            Map<String, Category> map = categoryMap();
            for (Row row : rows(categories)) {
                if ("No".equalsIgnoreCase(text(row, 3)) && !"Yes".equalsIgnoreCase(text(row, 4))) {
                    CategoryKind kind = "Income".equalsIgnoreCase(text(row, 1)) ? CategoryKind.INCOME : CategoryKind.EXPENSE;
                    Category c = map.get(kind + "|" + (text(row, 2).isEmpty() ? text(row, 0) : text(row, 2) + SEP + text(row, 0)));
                    if (c != null) {
                        try {
                            categoryService.setActive(c.getId(), false);
                        } catch (RuntimeException e) {
                            step.fail(row, e);
                        }
                    }
                }
            }
        }
        steps.add(step.done());
    }

    /** Ring-fences the goal funds the file says are ring-fenced, once everything is back (the fund must have reached its target, as it had). */
    private void ringFence(Workbook wb, List<Step> steps, Map<String, Long> accountIds) {
        Counter step = new Counter("Ring-fencing");
        for (Row row : rows(wb.getSheet("Accounts"))) {
            Long id = accountIds.get(text(row, 0));
            if (id == null || !"Yes".equalsIgnoreCase(text(row, 7))) {
                continue;
            }
            try {
                ringFenceService.fence(id);
                step.ok();
            } catch (RuntimeException e) {
                step.messages.add(text(row, 0).isEmpty() ? e.getMessage() : "Could not ring-fence an account: " + e.getMessage());
                step.skipped++;
            }
        }
        if (step.imported + step.skipped > 0) {
            steps.add(step.done());
        }
    }

    private void budgets(Workbook wb, List<Step> steps, Map<String, Category> categories) {
        Sheet sheet = wb.getSheet("Budgets");
        if (sheet == null) {
            return;
        }
        Counter step = new Counter("Budgets");
        Map<String, Category> expense = categoryMap();
        for (Row row : rows(sheet)) {
            try {
                String name = text(row, 0);
                Long categoryId = null;
                if (!name.equals("All spending")) {
                    Category c = expense.get(CategoryKind.EXPENSE + "|" + name);
                    if (c == null) {
                        throw new ValidationException("The category " + name + " is missing");
                    }
                    categoryId = c.getId();
                }
                budgetService.save(categoryId, amount(row, 1, 2));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        steps.add(step.done());
    }

    private void notes(Workbook wb, List<Step> steps) {
        Sheet sheet = wb.getSheet("Notes");
        if (sheet == null) {
            return;
        }
        Counter step = new Counter("Notes");
        Map<String, Long> folderIds = new HashMap<>();
        Sheet folderSheet = wb.getSheet("Note folders");
        if (folderSheet != null) {
            for (Row row : rows(folderSheet)) {
                String name = text(row, 0);
                if (name.isEmpty() || name.equals(NoteService.DAILY_NAME)) {
                    continue;
                }
                try {
                    folderIds.put(name, noteService.createFolder(name).getId());
                } catch (RuntimeException e) {
                    step.fail(row, e);
                }
            }
        }
        for (Row row : rows(sheet)) {
            try {
                String folder = text(row, 4);
                Long folderId = folder.isEmpty() || folder.equals(NoteService.DAILY_NAME) ? null : folderIds.get(folder);
                if (!folder.isEmpty() && !folder.equals(NoteService.DAILY_NAME) && folderId == null) {
                    throw new ValidationException("The folder " + folder + " is missing");
                }
                List<String> tags = text(row, 3).isEmpty() ? List.of() : java.util.Arrays.stream(text(row, 3).split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
                noteService.create(new NoteService.NoteData(date(row, 0), blankToNull(text(row, 1)), text(row, 2), tags, folderId));
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        steps.add(step.done());
    }

    private void reminders(Workbook wb, List<Step> steps) {
        Sheet sheet = wb.getSheet("Reminders");
        if (sheet == null) {
            return;
        }
        Counter step = new Counter("Reminders");
        Map<String, Long> byTitle = new HashMap<>();
        for (Row row : rows(sheet)) {
            try {
                Repeat repeat = switch (text(row, 4)) {
                    case "Every week" -> Repeat.WEEKLY;
                    case "Every month" -> Repeat.MONTHLY;
                    case "Every year" -> Repeat.YEARLY;
                    default -> Repeat.NONE;
                };
                Reminder r = reminderService.create(new ReminderService.ReminderData(date(row, 0), text(row, 1), repeat));
                byTitle.putIfAbsent(text(row, 1) + "|" + repeat, r.getId());
                if (repeat == Repeat.NONE && "Yes".equalsIgnoreCase(text(row, 2))) {
                    reminderService.setDone(r.getId(), true, dateOrNull(row, 3));
                }
                step.ok();
            } catch (RuntimeException e) {
                step.fail(row, e);
            }
        }
        Sheet ticks = wb.getSheet("Reminder ticks");
        if (ticks != null) {
            for (Row row : rows(ticks)) {
                try {
                    Repeat repeat = switch (text(row, 1)) {
                        case "Every week" -> Repeat.WEEKLY;
                        case "Every month" -> Repeat.MONTHLY;
                        default -> Repeat.YEARLY;
                    };
                    Long id = byTitle.get(text(row, 0) + "|" + repeat);
                    if (id == null) {
                        throw new ValidationException("The repeating reminder is missing");
                    }
                    reminderService.setOccurrenceDone(id, date(row, 2), true, dateOrNull(row, 3));
                    step.ok();
                } catch (RuntimeException e) {
                    step.fail(row, e);
                }
            }
        }
        steps.add(step.done());
    }

    // ------------------------------------------------------------------------------------------ reading cells

    /** Counts a step's rows; a row that fails is described by its sheet row number and the reason, never its content. */
    private static final class Counter extends Object {
        final String name;
        int imported, skipped;
        final List<String> messages = new ArrayList<>();

        Counter(String name) {
            this.name = name;
        }

        void ok() {
            imported++;
        }

        void skip() {
            skipped++;
        }

        void fail(Row row, RuntimeException e) {
            skipped++;
            if (messages.size() < 25) {
                messages.add("Row " + (row.getRowNum() + 1) + ": " + e.getMessage());
            }
        }

        Step done() {
            return new Step(name, imported, skipped, messages);
        }
    }

    private static List<Row> rows(Sheet sheet) {
        List<Row> rows = new ArrayList<>();
        if (sheet == null) {
            return rows;
        }
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !text(row, 0).isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static String text(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static BigDecimal amount(Row row, int col, int scale) {
        Cell cell = row.getCell(col);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(scale, RoundingMode.HALF_UP);
        }
        String s = text(row, col).replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s).setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new ValidationException("\"" + s + "\" is not a number");
        }
    }

    private static Integer integer(Row row, int col) {
        BigDecimal n = amount(row, col, 0);
        return n == null ? null : n.intValueExact();
    }

    private static LocalDate date(Row row, int col) {
        LocalDate d = dateOrNull(row, col);
        if (d == null) {
            throw new ValidationException("The date is missing or not a date");
        }
        return d;
    }

    private static LocalDate dateOrNull(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        try {
            return LocalDate.parse(text(row, col));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate dateOrMax(Row row, int col) {
        LocalDate d = dateOrNull(row, col);
        return d == null ? LocalDate.MAX : d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Long id(Map<String, Long> ids, String name, String what) {
        Long id = ids.get(name);
        if (id == null) {
            throw new ValidationException("The " + what + " " + name + " is not in the file's list");
        }
        return id;
    }
}
