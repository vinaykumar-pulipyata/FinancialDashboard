package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.Bucket;
import com.learning.spring.financialdashboard.model.BucketEntry;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.CreditCardDetail;
import com.learning.spring.financialdashboard.model.DebtDetail;
import com.learning.spring.financialdashboard.model.ExchangeRate;
import com.learning.spring.financialdashboard.model.HoldingSnapshot;
import com.learning.spring.financialdashboard.model.VehicleDetail;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.DebtDetailRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import com.learning.spring.financialdashboard.service.LedgerService.BucketView;
import com.learning.spring.financialdashboard.service.PortfolioService.HoldingView;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFLineProperties;
import org.apache.poi.xddf.usermodel.XDDFShapeProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.MarkerStyle;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFPieChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the Excel extract: one sheet per area (accounts, every transaction, income and expense by category,
 * month by month, net worth, the Ledger, the Emergency Fund, Mutual Funds, Shares, loans, categories, notes, reminders and
 * the value history of each holding). The Accounts sheet also carries each account's target, credit limit and bill day,
 * EMI and vehicle details, so nothing in the account tables is missing. With native
 * Excel charts next to the tables they draw from. It is a snapshot of calculated figures; the app never reads it back.
 */
@Service
@Transactional(readOnly = true)
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    // The dashboard's chart colours, in the same order (blue, orange, green, amber, pink).
    private static final byte[][] PALETTE = {
            rgb(0x0a, 0x84, 0xff), rgb(0xff, 0x5a, 0x1f), rgb(0x00, 0xc3, 0x89), rgb(0xf4, 0x9c, 0x00), rgb(0xff, 0x4f, 0xa3)};
    private static final int NET_WORTH_MONTHS = 120;
    private static final int PIE_SLICES = 5;   // the top 5 categories, then "Other", as on the dashboard

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final CategoryService categoryService;
    private final NetWorthService netWorthService;
    private final LedgerService ledgerService;
    private final PortfolioService portfolioService;
    private final LoanService loanService;
    private final EmergencyFundService emergencyFundService;
    private final BucketRepository buckets;
    private final BucketEntryRepository bucketEntries;
    private final HoldingRepository holdings;
    private final NoteService noteService;
    private final ReminderService reminderService;
    private final CreditCardDetailRepository cardDetails;
    private final DebtDetailRepository debtDetails;
    private final VehicleDetailRepository vehicleDetails;
    private final HoldingSnapshotRepository holdingSnapshots;
    private final JewelleryService jewelleryService;
    private final BudgetService budgetService;
    private final ExchangeRateService exchangeRateService;
    private final RecurringDepositService recurringDepositService;
    private final com.learning.spring.financialdashboard.repository.LoanWriteOffRepository writeOffs;

    public ExportService(AccountService accountService, TransactionService transactionService,
                         CategoryService categoryService, NetWorthService netWorthService,
                         LedgerService ledgerService, PortfolioService portfolioService, LoanService loanService,
                         EmergencyFundService emergencyFundService, BucketRepository buckets,
                         BucketEntryRepository bucketEntries, HoldingRepository holdings, NoteService noteService,
                         ReminderService reminderService, CreditCardDetailRepository cardDetails,
                         DebtDetailRepository debtDetails, VehicleDetailRepository vehicleDetails,
                         HoldingSnapshotRepository holdingSnapshots, JewelleryService jewelleryService,
                         BudgetService budgetService, ExchangeRateService exchangeRateService,
                         RecurringDepositService recurringDepositService,
                         com.learning.spring.financialdashboard.repository.LoanWriteOffRepository writeOffs) {
        this.writeOffs = writeOffs;
        this.exchangeRateService = exchangeRateService;
        this.recurringDepositService = recurringDepositService;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.categoryService = categoryService;
        this.netWorthService = netWorthService;
        this.ledgerService = ledgerService;
        this.portfolioService = portfolioService;
        this.loanService = loanService;
        this.emergencyFundService = emergencyFundService;
        this.buckets = buckets;
        this.bucketEntries = bucketEntries;
        this.holdings = holdings;
        this.noteService = noteService;
        this.reminderService = reminderService;
        this.cardDetails = cardDetails;
        this.debtDetails = debtDetails;
        this.vehicleDetails = vehicleDetails;
        this.holdingSnapshots = holdingSnapshots;
        this.jewelleryService = jewelleryService;
        this.budgetService = budgetService;
    }

    private record Styles(CellStyle header, CellStyle money, CellStyle date, CellStyle number, CellStyle bold, CellStyle rate) {
    }

    /** What the transactions need to show names instead of ids. */
    private record Names(Map<Long, String> accounts, Map<Long, Category> categories, Map<Long, String> people,
                         Map<Long, String> holdings) {

        String account(Long id) {
            return id == null ? "" : accounts.getOrDefault(id, "(deleted account)");
        }

        /** "Parent › Sub", or just the name of a top-level category. */
        String category(Long id) {
            Category c = id == null ? null : categories.get(id);
            if (c == null) {
                return "";
            }
            Category parent = c.getParentId() == null ? null : categories.get(c.getParentId());
            return parent == null ? c.getName() : parent.getName() + " › " + c.getName();
        }

        /** The top-level category a transaction rolls up into. */
        String topCategory(Long id) {
            Category c = id == null ? null : categories.get(id);
            if (c == null) {
                return "Uncategorised";
            }
            Category parent = c.getParentId() == null ? null : categories.get(c.getParentId());
            return parent == null ? c.getName() : parent.getName();
        }
    }

    public byte[] build() throws IOException {
        long started = System.nanoTime();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = styles(wb);
            List<AccountSummary> accounts = accountService.listSummaries();
            List<Transaction> txns = transactionService.list();
            List<Category> categories = categoryService.list(null);
            LoanService.Summary loans = loanService.summary();

            Names names = new Names(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
            accounts.forEach(a -> names.accounts().put(a.id(), a.name()));
            categories.forEach(c -> names.categories().put(c.getId(), c));
            loans.borrowers().forEach(b -> names.people().put(b.id(), b.name()));
            holdings.findAll().forEach(h -> names.holdings().put(h.getId(), h.getName()));

            accountsSheet(wb, st, accounts);
            transactionsSheet(wb, st, txns, names, accounts);
            balanceAdjustmentsSheet(wb, st, txns, names);
            // the totals by category and by month are in rupees, so a transaction on a foreign-currency account is left out of them
            Set<Long> foreign = accounts.stream().filter(a -> !a.currency().isBase()).map(AccountSummary::id).collect(Collectors.toSet());
            List<Transaction> rupeeTxns = txns.stream()
                    .filter(t -> !foreign.contains(t.getFromAccountId()) && !foreign.contains(t.getToAccountId())).toList();
            categorySheet(wb, st, rupeeTxns, names);
            monthlySheet(wb, st, rupeeTxns);
            netWorthSheet(wb, st);
            ledgerSheets(wb, st);
            emergencyFundSheet(wb, st);
            for (PortfolioKind kind : PortfolioKind.values()) {
                portfolioSheet(wb, st, kind);
            }
            jewellerySheet(wb, st);
            loansSheet(wb, st, loans);
            writeOffsSheet(wb, st, names);
            billsSheet(wb, st, accounts);
            categoriesSheet(wb, st, categories, names);
            budgetsSheet(wb, st, names);
            notesSheet(wb, st);
            noteFoldersSheet(wb, st);
            remindersSheet(wb, st);
            reminderTicksSheet(wb, st);
            holdingHistorySheet(wb, st, names);
            recurringDepositsSheet(wb, st);
            exchangeRatesSheet(wb, st);

            wb.write(out);
            log.info("Excel workbook built: {} sheets, {} KB, {} ms", wb.getNumberOfSheets(), out.size() / 1024,
                    (System.nanoTime() - started) / 1_000_000);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------------------------------ sheets

    private void accountsSheet(XSSFWorkbook wb, Styles st, List<AccountSummary> accounts) {
        XSSFSheet sheet = wb.createSheet("Accounts");
        header(sheet, st, 0, 0, "Account", "Type", "Level", "Parent", "Opening balance",
                "Balance (amount owed for debts and cards)", "Active", "Ring-fenced", "Notes",
                "Target (goal funds)", "Credit limit", "Bill due day", "Monthly EMI", "Number plate", "Year bought",
                "Value last set", "Statement day", "Currency", "Balance in ₹ (counted in the totals)");
        Map<Long, String> byId = new HashMap<>();
        accounts.forEach(a -> byId.put(a.id(), a.name()));
        Map<Long, CreditCardDetail> cards = new HashMap<>();
        cardDetails.findAll().forEach(c -> cards.put(c.getAccountId(), c));
        Map<Long, DebtDetail> debts = new HashMap<>();
        debtDetails.findAll().forEach(d -> debts.put(d.getAccountId(), d));
        Map<Long, VehicleDetail> vehicles = new HashMap<>();
        vehicleDetails.findAll().forEach(v -> vehicles.put(v.getAccountId(), v));
        int r = 1;
        for (AccountSummary a : accounts) {
            Row row = sheet.createRow(r++);
            put(row, 0, a.name(), null);
            put(row, 1, a.type().name(), null);
            put(row, 2, a.level(), st.number());
            put(row, 3, a.parentId() == null ? "" : byId.getOrDefault(a.parentId(), ""), null);
            put(row, 4, a.openingBalance(), st.money());
            put(row, 5, a.balance(), st.money());
            put(row, 6, a.active() ? "Yes" : "No", null);
            put(row, 7, a.ringFenced() ? "Yes" : "No", null);
            put(row, 8, a.notes(), null);
            put(row, 17, a.currency().name(), null);
            put(row, 18, a.balanceInr(), st.money());
            put(row, 9, a.targetAmount(), st.money());
            CreditCardDetail card = cards.get(a.id());
            if (card != null) {
                put(row, 10, card.getCreditLimit(), st.money());
                put(row, 11, card.getDueDay(), null);
                put(row, 16, card.getStatementDay(), null);
            }
            DebtDetail debt = debts.get(a.id());
            if (debt != null) {
                put(row, 12, debt.getEmiAmount(), st.money());
            }
            VehicleDetail vehicle = vehicles.get(a.id());
            if (vehicle != null) {
                put(row, 13, vehicle.getNumberPlate(), null);
                put(row, 14, vehicle.getYearBought(), null);   // a year, not a quantity: no thousands separator
                put(row, 15, vehicle.getValueUpdatedOn(), st.date());
            }
        }
        widths(sheet, 28, 14, 8, 24, 18, 24, 8, 12, 40, 18, 16, 12, 14, 14, 12, 14, 14, 10, 24);
        sheet.createFreezePane(0, 1);
    }

    private void transactionsSheet(XSSFWorkbook wb, Styles st, List<Transaction> txns, Names names, List<AccountSummary> accounts) {
        Map<Long, String> currencies = new HashMap<>();
        accounts.forEach(a -> currencies.put(a.id(), a.currency().name()));
        XSSFSheet sheet = wb.createSheet("Transactions");
        header(sheet, st, 0, 0, "Date", "Kind", "From", "To", "Amount", "Fee", "Category", "Person", "Holding",
                "Note", "Description", "Tags", "Currency", "Amount received", "Received in");
        int r = 1;
        for (Transaction t : txns) {
            Row row = sheet.createRow(r++);
            put(row, 0, t.getDate(), st.date());
            put(row, 1, t.getKind().name(), null);
            put(row, 2, names.account(t.getFromAccountId()), null);
            put(row, 3, names.account(t.getToAccountId()), null);
            put(row, 4, t.getAmount(), st.money());
            put(row, 5, t.getFee(), st.money());
            put(row, 6, names.category(t.getCategoryId()), null);
            put(row, 7, t.getBorrowerId() == null ? "" : names.people().getOrDefault(t.getBorrowerId(), ""), null);
            put(row, 8, t.getHoldingId() == null ? "" : names.holdings().getOrDefault(t.getHoldingId(), ""), null);
            put(row, 9, t.getNote(), null);
            put(row, 10, t.getDescription(), null);
            put(row, 11, String.join(", ", t.getTags()), null);
            // Amount and Fee are in Currency (the account the money left, or came into for an income); a transfer between two
            // currencies also says what arrived and in which currency
            put(row, 12, currencies.get(t.getKind() == TransactionKind.INCOME ? t.getToAccountId() : t.getFromAccountId()), null);
            if (t.getToAmount() != null) {
                put(row, 13, t.getToAmount(), st.money());
                put(row, 14, currencies.get(t.getToAccountId()), null);
            }
        }
        widths(sheet, 12, 10, 24, 24, 14, 10, 28, 18, 20, 28, 36, 30, 10, 16, 12);
        sheet.createFreezePane(0, 1);
        if (r > 1) {
            sheet.setAutoFilter(new CellRangeAddress(0, r - 1, 0, 14));
        }
    }

    /** Income and expense by category (all time, transfers left out), each with a pie of the top 5 plus "Other". */
    private void categorySheet(XSSFWorkbook wb, Styles st, List<Transaction> txns, Names names) {
        XSSFSheet sheet = wb.createSheet("Income & Expenses");
        pieTable(sheet, st, txns, names, TransactionKind.INCOME, "Income", 0);
        pieTable(sheet, st, txns, names, TransactionKind.EXPENSE, "Expenses", 5);

        // The full breakdown, with sub categories, below the charts.
        int top = 30;
        Map<String, BigDecimal> detail = new TreeMap<>();
        for (Transaction t : txns) {
            if (t.getKind() == TransactionKind.TRANSFER) {
                continue;
            }
            String key = (t.getKind() == TransactionKind.INCOME ? "Income" : "Expense") + "\u0000" + names.category(t.getCategoryId());
            detail.merge(key, t.getAmount(), BigDecimal::add);
        }
        sheet.createRow(top - 1).createCell(0).setCellValue("All categories and sub categories");
        header(sheet, st, top, 0, "Kind", "Category", "Amount");
        int r = top + 1;
        for (Map.Entry<String, BigDecimal> e : detail.entrySet()) {
            String[] parts = e.getKey().split("\u0000", -1);
            Row row = sheet.createRow(r++);
            put(row, 0, parts[0], null);
            put(row, 1, parts[1].isEmpty() ? "Uncategorised" : parts[1], null);
            put(row, 2, e.getValue(), st.money());
        }
        widths(sheet, 26, 18, 14, 4, 4, 26, 18, 14);
    }

    private void pieTable(XSSFSheet sheet, Styles st, List<Transaction> txns, Names names, TransactionKind kind,
                          String title, int firstCol) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (Transaction t : txns) {
            if (t.getKind() == kind) {
                totals.merge(names.topCategory(t.getCategoryId()), t.getAmount(), BigDecimal::add);
            }
        }
        List<Map.Entry<String, BigDecimal>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Map.Entry.<String, BigDecimal>comparingByValue().reversed());
        BigDecimal all = sorted.stream().map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);

        Row titleRow = rowAt(sheet, 0);
        put(titleRow, firstCol, title + " by category (all time)", st.bold());
        header(sheet, st, 1, firstCol, "Category", "Amount", "Share %");
        int r = 2;
        BigDecimal shown = BigDecimal.ZERO;
        for (int i = 0; i < sorted.size() && i < PIE_SLICES; i++) {
            Row row = rowAt(sheet, r++);
            put(row, firstCol, sorted.get(i).getKey(), null);
            put(row, firstCol + 1, sorted.get(i).getValue(), st.money());
            put(row, firstCol + 2, share(sorted.get(i).getValue(), all), st.number());
            shown = shown.add(sorted.get(i).getValue());
        }
        if (sorted.size() > PIE_SLICES) {
            BigDecimal other = all.subtract(shown);
            Row row = rowAt(sheet, r++);
            put(row, firstCol, "Other", null);
            put(row, firstCol + 1, other, st.money());
            put(row, firstCol + 2, share(other, all), st.number());
        }
        if (r > 2) {
            pie(sheet, title + " by category", firstCol, firstCol + 1, 2, r - 1, firstCol, 9, firstCol + 4, 27);
        }
    }

    private void monthlySheet(XSSFWorkbook wb, Styles st, List<Transaction> txns) {
        XSSFSheet sheet = wb.createSheet("Monthly");
        header(sheet, st, 0, 0, "Month", "Income", "Expense", "Net");
        Map<YearMonth, BigDecimal[]> months = new TreeMap<>();
        for (Transaction t : txns) {
            if (t.getKind() == TransactionKind.TRANSFER) {
                continue;
            }
            BigDecimal[] sums = months.computeIfAbsent(YearMonth.from(t.getDate()),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            int i = t.getKind() == TransactionKind.INCOME ? 0 : 1;
            sums[i] = sums[i].add(t.getAmount());
        }
        int r = 1;
        for (Map.Entry<YearMonth, BigDecimal[]> e : months.entrySet()) {
            Row row = sheet.createRow(r++);
            put(row, 0, e.getKey().toString(), null);
            put(row, 1, e.getValue()[0], st.money());
            put(row, 2, e.getValue()[1], st.money());
            put(row, 3, e.getValue()[0].subtract(e.getValue()[1]), st.money());
        }
        widths(sheet, 12, 16, 16, 16);
        sheet.createFreezePane(0, 1);
        if (r > 1) {
            columns(sheet, "Income and expense by month", 0, 0, r - 1, new int[]{1, 2}, 5, 1, 16, 20);
        }
    }

    private void netWorthSheet(XSSFWorkbook wb, Styles st) {
        XSSFSheet sheet = wb.createSheet("Net worth");
        header(sheet, st, 0, 0, "Month", "Net worth", "Assets", "Liabilities");
        int r = 1;
        boolean started = false;
        for (NetWorthService.Point p : netWorthService.history(NET_WORTH_MONTHS)) {
            // Months before anything was recorded are all zero; leave them out.
            started |= p.assets().signum() != 0 || p.liabilities().signum() != 0;
            if (!started) {
                continue;
            }
            Row row = sheet.createRow(r++);
            put(row, 0, p.month(), null);
            put(row, 1, p.netWorth(), st.money());
            put(row, 2, p.assets(), st.money());
            put(row, 3, p.liabilities(), st.money());
        }
        widths(sheet, 12, 16, 16, 16);
        sheet.createFreezePane(0, 1);
        if (r > 1) {
            line(sheet, "Net worth, month end", 0, 1, r - 1, new int[]{1}, 5, 1, 16, 20);
        }
    }

    private void ledgerSheets(XSSFWorkbook wb, Styles st) {
        LedgerService.View view = ledgerService.get();
        XSSFSheet sheet = wb.createSheet("Ledger");
        if (!view.configured()) {
            sheet.createRow(0).createCell(0).setCellValue("The Future Expenses Ledger is not set up.");
            return;
        }
        put(rowAt(sheet, 0), 0, view.name(), st.bold());
        put(rowAt(sheet, 1), 0, "Balance", null);
        put(rowAt(sheet, 1), 1, view.balance(), st.money());
        put(rowAt(sheet, 2), 0, "Monthly plan", null);
        put(rowAt(sheet, 2), 1, view.monthlyPlan(), st.money());

        int head = 4;
        header(sheet, st, head, 0, "Bucket", "Balance", "Monthly allocation", "Payment amount", "Every (months)",
                "Next payment", "Previous payment", "End date", "Buffer", "Funded", "Short by");
        int r = head + 1;
        for (BucketView b : view.buckets()) {
            Row row = rowAt(sheet, r++);
            put(row, 0, b.name(), null);
            put(row, 1, b.balance(), st.money());
            put(row, 2, b.monthlyAllocation(), st.money());
            put(row, 3, b.paymentAmount(), st.money());
            put(row, 4, b.frequencyMonths(), st.number());
            put(row, 5, b.nextPaymentDate(), st.date());
            put(row, 6, b.previousPaymentDate(), st.date());
            put(row, 7, b.endDate(), st.date());
            put(row, 8, b.buffer() ? "Yes" : "No", null);
            put(row, 9, b.funded() ? "Yes" : "No", null);
            put(row, 10, b.shortBy(), st.money());
        }
        int lastBucket = r - 1;
        if (!view.closed().isEmpty()) {
            r++;
            put(rowAt(sheet, r++), 0, "Closed buckets", st.bold());
            header(sheet, st, r++, 0, "Bucket", "Total paid", "Closed on");
            for (LedgerService.ClosedBucketView c : view.closed()) {
                Row row = rowAt(sheet, r++);
                put(row, 0, c.name(), null);
                put(row, 1, c.totalPaid(), st.money());
                put(row, 2, c.closedAt(), st.date());
            }
        }
        widths(sheet, 28, 16, 18, 16, 14, 14, 16, 14, 8, 8, 14);
        if (lastBucket > head) {
            columns(sheet, "Money held in each bucket", 0, head, lastBucket, new int[]{1}, 12, 1, 22, 20);
        }

        // Every change to every bucket, oldest first, so a bucket's history can be followed.
        XSSFSheet entries = wb.createSheet("Ledger entries");
        header(entries, st, 0, 0, "Date", "Bucket", "Type", "Amount", "Note");
        Map<Long, String> bucketNames = new HashMap<>();
        for (Bucket b : buckets.findAll()) {
            bucketNames.put(b.getId(), b.getName());
        }
        List<BucketEntry> all = new ArrayList<>(bucketEntries.findAll());
        all.sort(Comparator.comparing(BucketEntry::getDate).thenComparing(BucketEntry::getId));
        int e = 1;
        for (BucketEntry entry : all) {
            Row row = entries.createRow(e++);
            put(row, 0, entry.getDate(), st.date());
            put(row, 1, bucketNames.getOrDefault(entry.getBucketId(), "(deleted bucket)"), null);
            put(row, 2, entry.getKind().name(), null);
            put(row, 3, entry.getAmount(), st.money());
            put(row, 4, entry.getNote(), null);
        }
        widths(entries, 12, 28, 12, 14, 40);
        entries.createFreezePane(0, 1);
        if (e > 1) {
            entries.setAutoFilter(new CellRangeAddress(0, e - 1, 0, 4));
        }
    }

    private void emergencyFundSheet(XSSFWorkbook wb, Styles st) {
        EmergencyFundService.View v = emergencyFundService.get();
        XSSFSheet sheet = wb.createSheet("Emergency Fund");
        if (!v.configured()) {
            sheet.createRow(0).createCell(0).setCellValue("The Emergency Fund is not set up.");
            return;
        }
        header(sheet, st, 0, 0, "Item", "Value");
        Object[][] rows = {
                {"Account", v.name(), null},
                {"Balance", v.balance(), st.money()},
                {"Target", v.target(), st.money()},
                {"Remaining", v.remaining(), st.money()},
                {"Progress %", v.progressPercent(), st.number()},
                {"Target set by", v.targetMode() == null ? "" : v.targetMode().name(), null},
                {"Target months", v.targetMonths(), st.number()},
                {"Monthly essential expenses", v.monthlyEssentialExpenses(), st.money()},
                {"Planned monthly contribution", v.plannedMonthlyContribution(), st.money()},
                {"Average monthly contribution", v.averageMonthlyContribution(), st.money()},
                {"Runway (months)", v.runwayMonths(), st.number()},
                {"Estimated date", v.estimatedDate(), st.date()},
                {"Target reached", v.targetReached() ? "Yes" : "No", null},
                {"Ring-fenced", v.ringFenced() ? "Yes" : "No", null}};
        int r = 1;
        for (Object[] item : rows) {
            Row row = sheet.createRow(r++);
            put(row, 0, item[0], null);
            put(row, 1, item[1], (CellStyle) item[2]);
        }
        widths(sheet, 32, 20);
        // Balance against what is still to save.
        if (v.target() != null && v.remaining() != null && v.remaining().signum() > 0 && v.balance().signum() > 0) {
            put(rowAt(sheet, 16), 0, "Saved", null);
            put(rowAt(sheet, 16), 1, v.balance(), st.money());
            put(rowAt(sheet, 17), 0, "Still to save", null);
            put(rowAt(sheet, 17), 1, v.remaining(), st.money());
            pie(sheet, "Emergency Fund: saved and still to save", 0, 1, 16, 17, 3, 1, 10, 18);
        }
    }

    private void portfolioSheet(XSSFWorkbook wb, Styles st, PortfolioKind kind) {
        PortfolioService.View v = portfolioService.get(kind);
        XSSFSheet sheet = wb.createSheet(kind.getLabel());
        if (!v.configured()) {
            sheet.createRow(0).createCell(0).setCellValue("Not set up.");
            return;
        }
        put(rowAt(sheet, 0), 0, v.label(), st.bold());
        String[] labels = {"Current value", "Invested (holdings)", "Total invested", "Gain or loss", "Gain %", "Profit from sales"};
        Object[] values = {v.currentValue(), v.investedAmount(), v.totalInvested(), v.gain(), v.gainPercent(), v.realizedGain()};
        for (int i = 0; i < labels.length; i++) {
            put(rowAt(sheet, 1 + i), 0, labels[i], null);
            put(rowAt(sheet, 1 + i), 1, values[i], i == 4 ? st.number() : st.money());
        }

        int head = 7;
        header(sheet, st, head, 0, "Holding", "Invested", "Current value", "Gain or loss", "Gain %", "Quantity");
        int r = head + 1;
        for (HoldingView h : v.holdings()) {
            Row row = sheet.createRow(r++);
            put(row, 0, h.name(), null);
            put(row, 1, h.investedAmount(), st.money());
            put(row, 2, h.currentValue(), st.money());
            put(row, 3, h.gain(), st.money());
            put(row, 4, h.gainPercent(), st.number());
            put(row, 5, h.quantity(), st.number());
        }
        int lastHolding = r - 1;

        // Value history, one row per day it was recorded, to the right of the holdings.
        List<PortfolioService.StatPoint> history = portfolioService.stats(kind).overall();
        header(sheet, st, head, 7, "Date", "Invested", "Current value");
        int h = head + 1;
        for (PortfolioService.StatPoint p : history) {
            Row row = rowAt(sheet, h++);
            put(row, 7, p.date().toString(), null);
            put(row, 8, p.investedAmount(), st.money());
            put(row, 9, p.currentValue(), st.money());
        }
        widths(sheet, 30, 16, 16, 16, 12, 12, 4, 12, 16, 16);

        int chartRow = Math.max(lastHolding, h - 1) + 3;
        if (lastHolding > head) {
            columns(sheet, kind.getLabel() + ": invested and current value", 0, head, lastHolding,
                    new int[]{1, 2}, 0, chartRow, 6, chartRow + 18);
        }
        if (h - 1 > head + 1) {
            line(sheet, kind.getLabel() + " over time", 7, head + 1, h - 1, new int[]{8, 9}, 7, chartRow, 14, chartRow + 18);
        }
    }

    private void loansSheet(XSSFWorkbook wb, Styles st, LoanService.Summary loans) {
        XSSFSheet sheet = wb.createSheet("Loans");
        header(sheet, st, 0, 0, "Person", "Owed before you started", "Lent", "Collected", "Written off", "Owed now", "Of which shared expenses");
        int r = 1;
        if (loans.unnamed() != null && loans.unnamed().signum() != 0) {
            Row row = sheet.createRow(r++);
            put(row, 0, "Earlier loans (no name)", null);
            put(row, 5, loans.unnamed(), st.money());
        }
        for (LoanService.BorrowerView b : loans.borrowers()) {
            Row row = sheet.createRow(r++);
            put(row, 0, b.name(), null);
            put(row, 1, b.openingAmount(), st.money());
            put(row, 2, b.given(), st.money());
            put(row, 3, b.collected(), st.money());
            put(row, 4, b.writtenOff(), st.money());
            put(row, 5, b.owed(), st.money());
            put(row, 6, b.shared(), st.money());
        }
        widths(sheet, 28, 22, 14, 14, 14, 14, 24);
        sheet.createFreezePane(0, 1);
    }

    private void categoriesSheet(XSSFWorkbook wb, Styles st, List<Category> categories, Names names) {
        XSSFSheet sheet = wb.createSheet("Categories");
        header(sheet, st, 0, 0, "Category", "Kind", "Parent", "Active", "Fixed");
        int r = 1;
        for (Category c : categories) {
            Row row = sheet.createRow(r++);
            put(row, 0, c.getName(), null);
            put(row, 1, c.getKind() == CategoryKind.INCOME ? "Income" : "Expense", null);
            Category parent = c.getParentId() == null ? null : names.categories().get(c.getParentId());
            put(row, 2, parent == null ? "" : parent.getName(), null);
            put(row, 3, c.isActive() ? "Yes" : "No", null);
            put(row, 4, c.getSystemKey() == null ? "No" : "Yes", null);
        }
        widths(sheet, 28, 10, 24, 8, 8);
        sheet.createFreezePane(0, 1);
    }

    /** The monthly spending limits: one on all spending and one per main category. What is used is shown on the dashboard, not here. */
    private void budgetsSheet(XSSFWorkbook wb, Styles st, Names names) {
        XSSFSheet sheet = wb.createSheet("Budgets");
        header(sheet, st, 0, 0, "Category", "Monthly limit");
        int r = 1;
        for (com.learning.spring.financialdashboard.model.Budget b : budgetService.list()) {
            Row row = sheet.createRow(r++);
            put(row, 0, b.getCategoryId() == null ? "All spending" : names.category(b.getCategoryId()), null);
            put(row, 1, b.getAmount(), st.money());
        }
        widths(sheet, 28, 16);
        sheet.createFreezePane(0, 1);
    }

    private void notesSheet(XSSFWorkbook wb, Styles st) {
        XSSFSheet sheet = wb.createSheet("Notes");
        header(sheet, st, 0, 0, "Date", "Title", "Note", "Tags", "Folder");
        java.util.Map<Long, String> folderNames = new java.util.HashMap<>();
        noteService.folders().folders().forEach(f -> folderNames.put(f.id(), f.name()));
        int r = 1;
        for (com.learning.spring.financialdashboard.model.Note n : noteService.all()) {
            Row row = sheet.createRow(r++);
            put(row, 0, n.getDate(), st.date());
            put(row, 1, n.getTitle(), null);
            put(row, 2, n.getBody(), null);
            put(row, 3, String.join(", ", n.getTags()), null);
            put(row, 4, n.getFolderId() == null ? NoteService.DAILY_NAME : folderNames.get(n.getFolderId()), null);
        }
        widths(sheet, 12, 30, 100, 30, 24);
        sheet.createFreezePane(0, 1);
    }

    /** Every piece of jewellery: kind, name, type, weight and the price paid, grouped by kind. No current value is kept. */
    private void jewellerySheet(XSSFWorkbook wb, Styles st) {
        JewelleryService.View v = jewelleryService.get();
        XSSFSheet sheet = wb.createSheet("Jewellery");
        if (!v.configured()) {
            sheet.createRow(0).createCell(0).setCellValue("Not set up.");
            return;
        }
        header(sheet, st, 0, 0, "Kind", "Name", "Type", "Weight (g)", "Bought price");
        int r = 1;
        for (JewelleryService.MetalView metal : v.metals()) {
            for (JewelleryService.ItemView item : metal.items()) {
                Row row = sheet.createRow(r++);
                put(row, 0, item.metal(), null);
                put(row, 1, item.name(), null);
                put(row, 2, item.form().charAt(0) + item.form().substring(1).toLowerCase(), null);
                put(row, 3, item.weight(), st.number());
                put(row, 4, item.boughtPrice(), st.money());
            }
        }
        Row total = sheet.createRow(r + 1);
        put(total, 0, "Total", st.bold());
        put(total, 4, v.boughtPrice(), st.money());
        widths(sheet, 14, 36, 12, 12, 16);
        sheet.createFreezePane(0, 1);
    }

    /** The transactions Reset balance and Write off made (the fixed Balance Adjustment categories), apart from the ordinary ones. */
    private void balanceAdjustmentsSheet(XSSFWorkbook wb, Styles st, List<Transaction> txns, Names names) {
        XSSFSheet sheet = wb.createSheet("Balance adjustments");
        header(sheet, st, 0, 0, "Date", "Account", "What it did", "Amount", "Why (the note)");
        int r = 1;
        for (Transaction t : txns) {
            Category c = t.getCategoryId() == null ? null : names.categories().get(t.getCategoryId());
            if (c == null || c.getSystemKey() == null || !c.getSystemKey().startsWith("BALANCE_ADJUSTMENT")) {
                continue;
            }
            boolean expense = t.getKind() == com.learning.spring.financialdashboard.model.TransactionKind.EXPENSE;
            Row row = sheet.createRow(r++);
            put(row, 0, t.getDate(), st.date());
            put(row, 1, names.account(expense ? t.getFromAccountId() : t.getToAccountId()), null);
            put(row, 2, expense ? "Took the balance down" : "Brought the balance up", null);
            put(row, 3, t.getAmount(), st.money());
            put(row, 4, t.getNote(), null);
        }
        widths(sheet, 12, 28, 24, 16, 60);
        sheet.createFreezePane(0, 1);
    }

    /** Each debt a person owed you that you let go of: the day, who, how much (negative when it was you who owed them) and why. */
    private void writeOffsSheet(XSSFWorkbook wb, Styles st, Names names) {
        XSSFSheet sheet = wb.createSheet("Write-offs");
        header(sheet, st, 0, 0, "Date", "Person", "Amount", "Why");
        int r = 1;
        List<com.learning.spring.financialdashboard.model.LoanWriteOff> all = new java.util.ArrayList<>(writeOffs.findAll());
        all.sort(java.util.Comparator.comparing(com.learning.spring.financialdashboard.model.LoanWriteOff::getWrittenOn)
                .thenComparing(com.learning.spring.financialdashboard.model.LoanWriteOff::getId));
        for (var w : all) {
            Row row = sheet.createRow(r++);
            put(row, 0, w.getWrittenOn(), st.date());
            put(row, 1, names.people().getOrDefault(w.getBorrowerId(), "(removed person)"), null);
            put(row, 2, w.getAmount(), st.money());
            put(row, 3, w.getNote(), null);
        }
        widths(sheet, 12, 28, 16, 60);
        sheet.createFreezePane(0, 1);
    }

    /** What is billed and when: each credit card (limit, statement and due day, what is owed now) and each Ledger bucket that pays a bill. */
    private void billsSheet(XSSFWorkbook wb, Styles st, List<AccountSummary> accounts) {
        XSSFSheet sheet = wb.createSheet("Bills");
        header(sheet, st, 0, 0, "From", "Name", "Owed now / bill amount", "Credit limit", "Statement day", "Due day / next payment", "Every (months)", "Ends");
        int r = 1;
        for (AccountSummary a : accounts) {
            if (a.type() != com.learning.spring.financialdashboard.model.AccountType.CREDIT_CARD) {
                continue;
            }
            var detail = cardDetails.findById(a.id()).orElse(null);
            Row row = sheet.createRow(r++);
            put(row, 0, "Credit card", null);
            put(row, 1, a.name(), null);
            put(row, 2, a.balance(), st.money());
            put(row, 3, detail == null ? null : detail.getCreditLimit(), st.money());
            put(row, 4, detail == null ? null : detail.getStatementDay(), st.number());
            put(row, 5, detail == null ? null : detail.getDueDay(), st.number());
        }
        LedgerService.View view = ledgerService.get();
        if (view.configured()) {
            for (BucketView b : view.buckets()) {
                if (b.buffer() || b.paymentAmount() == null) {
                    continue;
                }
                Row row = sheet.createRow(r++);
                put(row, 0, "Ledger bucket", null);
                put(row, 1, b.name(), null);
                put(row, 2, b.paymentAmount(), st.money());
                put(row, 5, b.nextPaymentDate(), st.date());
                put(row, 6, b.frequencyMonths(), st.number());
                put(row, 7, b.endDate(), st.date());
            }
        }
        widths(sheet, 16, 30, 22, 16, 14, 22, 14, 14);
        sheet.createFreezePane(0, 1);
    }

    /** The folders of the Notes view with how many notes each holds; the Daily Calendar Notes come first. */
    private void noteFoldersSheet(XSSFWorkbook wb, Styles st) {
        XSSFSheet sheet = wb.createSheet("Note folders");
        header(sheet, st, 0, 0, "Folder", "Notes");
        NoteService.Folders folders = noteService.folders();
        int r = 1;
        Row first = sheet.createRow(r++);
        put(first, 0, NoteService.DAILY_NAME, null);
        put(first, 1, folders.daily(), st.number());
        for (NoteService.FolderView f : folders.folders()) {
            Row row = sheet.createRow(r++);
            put(row, 0, f.name(), null);
            put(row, 1, f.count(), st.number());
        }
        widths(sheet, 30, 10);
        sheet.createFreezePane(0, 1);
    }

    /** The days of a repeating reminder that were ticked, one row per day (the Reminders sheet has the reminder itself). */
    private void reminderTicksSheet(XSSFWorkbook wb, Styles st) {
        XSSFSheet sheet = wb.createSheet("Reminder ticks");
        header(sheet, st, 0, 0, "Reminder", "Repeats", "Day it fell on", "Ticked on");
        int r = 1;
        for (com.learning.spring.financialdashboard.model.Reminder m : reminderService.all()) {
            for (var tick : m.getCompletions().entrySet()) {
                Row row = sheet.createRow(r++);
                put(row, 0, m.getTitle(), null);
                put(row, 1, switch (m.getRepeat()) {
                    case WEEKLY -> "Every week";
                    case MONTHLY -> "Every month";
                    case YEARLY -> "Every year";
                    case NONE -> null;
                }, null);
                put(row, 2, tick.getKey(), st.date());
                put(row, 3, tick.getValue(), st.date());
            }
        }
        widths(sheet, 60, 14, 16, 14);
        sheet.createFreezePane(0, 1);
    }

    private void remindersSheet(XSSFWorkbook wb, Styles st) {
        XSSFSheet sheet = wb.createSheet("Reminders");
        header(sheet, st, 0, 0, "Date", "Reminder", "Done", "Done on", "Repeats");
        int r = 1;
        for (com.learning.spring.financialdashboard.model.Reminder m : reminderService.all()) {
            Row row = sheet.createRow(r++);
            put(row, 0, m.getDate(), st.date());
            put(row, 1, m.getTitle(), null);
            put(row, 2, m.isDone() ? "Yes" : "No", null);
            put(row, 3, m.getDoneOn(), st.date());
            put(row, 4, switch (m.getRepeat()) {
                case WEEKLY -> "Every week";
                case MONTHLY -> "Every month";
                case YEARLY -> "Every year";
                case NONE -> null;
            }, null);
        }
        widths(sheet, 12, 80, 8, 12, 12);
        sheet.createFreezePane(0, 1);
    }

    /** The recorded value of every holding, day by day (what the Stats line graphs draw), one row per holding per day. */
    private void holdingHistorySheet(XSSFWorkbook wb, Styles st, Names names) {
        XSSFSheet sheet = wb.createSheet("Holding history");
        header(sheet, st, 0, 0, "Portfolio", "Holding", "Date", "Invested", "Current value", "Gain or loss");
        List<HoldingSnapshot> snapshots = new ArrayList<>(holdingSnapshots.findAll());
        snapshots.sort(Comparator.comparing(HoldingSnapshot::getAccountId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(s -> names.holdings().getOrDefault(s.getHoldingId(), ""))
                .thenComparing(HoldingSnapshot::getDate));
        int r = 1;
        for (HoldingSnapshot s : snapshots) {
            Row row = sheet.createRow(r++);
            put(row, 0, names.account(s.getAccountId()), null);
            put(row, 1, names.holdings().getOrDefault(s.getHoldingId(), "(removed holding)"), null);
            put(row, 2, s.getDate(), st.date());
            put(row, 3, s.getInvestedAmount(), st.money());
            put(row, 4, s.getCurrentValue(), st.money());
            put(row, 5, s.getCurrentValue().subtract(s.getInvestedAmount()), st.money());
        }
        widths(sheet, 22, 30, 12, 16, 16, 16);
        sheet.createFreezePane(0, 1);
        if (r > 1) {
            sheet.setAutoFilter(new CellRangeAddress(0, r - 1, 0, 5));
        }
    }

    /** Every recurring deposit, open and closed: its term, what was paid in, what it should pay and, once closed, what came back. */
    private void recurringDepositsSheet(XSSFWorkbook wb, Styles st) {
        RecurringDepositService.View view = recurringDepositService.get();
        if (!view.configured() || (view.open().isEmpty() && view.closed().isEmpty())) {
            return;
        }
        XSSFSheet sheet = wb.createSheet("Recurring Deposits");
        header(sheet, st, 0, 0, "Deposit", "Status", "Started", "Term (months)", "Matures", "Monthly amount", "Interest a year (%)", "Paid in",
                "Deposits made", "Expected at maturity", "Closed on", "Received", "Interest earned", "Already deposited");
        int r = 1;
        List<RecurringDepositService.DepositView> all = new ArrayList<>(view.open());
        all.addAll(view.closed());
        for (RecurringDepositService.DepositView d : all) {
            Row row = sheet.createRow(r++);
            put(row, 0, d.name(), null);
            put(row, 1, d.status(), null);
            put(row, 2, d.startDate(), st.date());
            put(row, 3, d.termMonths(), null);
            put(row, 4, d.maturesOn(), st.date());
            put(row, 5, d.monthlyAmount(), st.money());
            put(row, 6, d.interestRate(), st.rate());
            put(row, 7, d.deposited(), st.money());
            put(row, 8, d.instalments(), null);
            put(row, 9, d.expectedMaturity(), st.money());
            put(row, 10, d.closedOn(), st.date());
            put(row, 11, d.received(), st.money());
            put(row, 12, d.interestEarned(), st.money());
            put(row, 13, d.alreadyDeposited(), st.money());
        }
        widths(sheet, 24, 10, 12, 14, 12, 16, 18, 16, 14, 20, 12, 16, 16, 18);
        sheet.createFreezePane(0, 1);
    }

    /** The rates the foreign accounts are counted at. Left out when there are none, like the Ledger entries without a Ledger. */
    private void exchangeRatesSheet(XSSFWorkbook wb, Styles st) {
        List<ExchangeRate> rates = exchangeRateService.list();
        if (rates.isEmpty()) {
            return;
        }
        XSSFSheet sheet = wb.createSheet("Exchange rates");
        header(sheet, st, 0, 0, "Currency", "Date", "Rupees per 1 unit");
        int r = 1;
        for (ExchangeRate rate : rates) {
            Row row = sheet.createRow(r++);
            put(row, 0, rate.getCurrency().name(), null);
            put(row, 1, rate.getDate(), st.date());
            put(row, 2, rate.getRate(), st.rate());
        }
        widths(sheet, 12, 12, 20);
        sheet.createFreezePane(0, 1);
    }

    // ------------------------------------------------------------------------------------------ charts

    private void pie(XSSFSheet sheet, String title, int catCol, int valCol, int firstRow, int lastRow,
                     int c1, int r1, int c2, int r2) {
        XSSFChart chart = newChart(sheet, title, c1, r1, c2, r2);
        XDDFPieChartData data = (XDDFPieChartData) chart.createData(ChartTypes.PIE, null, null);
        data.setVaryColors(true);
        data.addSeries(strings(sheet, catCol, firstRow, lastRow), numbers(sheet, valCol, firstRow, lastRow));
        chart.plot(data);
    }

    private void line(XSSFSheet sheet, String title, int catCol, int firstRow, int lastRow, int[] valCols,
                      int c1, int r1, int c2, int r2) {
        XSSFChart chart = newChart(sheet, title, c1, r1, c2, r2);
        XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
        left.setNumberFormat("#,##0");
        XDDFLineChartData data = (XDDFLineChartData) chart.createData(ChartTypes.LINE, bottom, left);
        for (int i = 0; i < valCols.length; i++) {
            XDDFLineChartData.Series s = (XDDFLineChartData.Series) data.addSeries(
                    strings(sheet, catCol, firstRow, lastRow), numbers(sheet, valCols[i], firstRow, lastRow));
            s.setTitle(headerText(sheet, firstRow - 1, valCols[i]), new CellReference(sheet.getSheetName(), firstRow - 1, valCols[i], true, true));
            s.setSmooth(false);
            s.setMarkerStyle(MarkerStyle.NONE);
            XDDFLineProperties line = new XDDFLineProperties();
            line.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(PALETTE[i % PALETTE.length])));
            line.setWidth(2.25);
            XDDFShapeProperties props = new XDDFShapeProperties();
            props.setLineProperties(line);
            s.setShapeProperties(props);
        }
        chart.plot(data);
    }

    private void columns(XSSFSheet sheet, String title, int catCol, int headerRow, int lastRow, int[] valCols,
                         int c1, int r1, int c2, int r2) {
        int firstRow = headerRow + 1;
        XSSFChart chart = newChart(sheet, title, c1, r1, c2, r2);
        XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
        left.setNumberFormat("#,##0");
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottom, left);
        data.setBarDirection(BarDirection.COL);
        for (int i = 0; i < valCols.length; i++) {
            XDDFBarChartData.Series s = (XDDFBarChartData.Series) data.addSeries(
                    strings(sheet, catCol, firstRow, lastRow), numbers(sheet, valCols[i], firstRow, lastRow));
            s.setTitle(headerText(sheet, headerRow, valCols[i]), new CellReference(sheet.getSheetName(), headerRow, valCols[i], true, true));
            XDDFShapeProperties props = new XDDFShapeProperties();
            props.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(PALETTE[i % PALETTE.length])));
            s.setShapeProperties(props);
        }
        chart.plot(data);
    }

    private XSSFChart newChart(XSSFSheet sheet, String title, int c1, int r1, int c2, int r2) {
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, c1, r1, c2, r2);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        return chart;
    }

    private XDDFDataSource<String> strings(XSSFSheet sheet, int col, int firstRow, int lastRow) {
        return XDDFDataSourcesFactory.fromStringCellRange(sheet, new CellRangeAddress(firstRow, lastRow, col, col));
    }

    private XDDFNumericalDataSource<Double> numbers(XSSFSheet sheet, int col, int firstRow, int lastRow) {
        return XDDFDataSourcesFactory.fromNumericCellRange(sheet, new CellRangeAddress(firstRow, lastRow, col, col));
    }

    private String headerText(XSSFSheet sheet, int row, int col) {
        return sheet.getRow(row).getCell(col).getStringCellValue();
    }

    // ------------------------------------------------------------------------------------------ cells

    private static byte[] rgb(int r, int g, int b) {
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }

    private static BigDecimal share(BigDecimal part, BigDecimal all) {
        return all.signum() == 0 ? BigDecimal.ZERO
                : part.multiply(BigDecimal.valueOf(100)).divide(all, 1, java.math.RoundingMode.HALF_UP);
    }

    private static Row rowAt(XSSFSheet sheet, int index) {
        Row row = sheet.getRow(index);
        return row != null ? row : sheet.createRow(index);
    }

    private static void header(XSSFSheet sheet, Styles st, int rowIndex, int firstCol, String... titles) {
        Row row = rowAt(sheet, rowIndex);
        for (int i = 0; i < titles.length; i++) {
            Cell cell = row.createCell(firstCol + i);
            cell.setCellValue(titles[i]);
            cell.setCellStyle(st.header());
        }
    }

    private static void widths(XSSFSheet sheet, int... chars) {
        for (int i = 0; i < chars.length; i++) {
            sheet.setColumnWidth(i, Math.min(chars[i], 80) * 256);
        }
    }

    /** Writes a value as the right cell type; null leaves the cell empty. */
    private static void put(Row row, int col, Object value, CellStyle style) {
        if (value == null) {
            return;
        }
        Cell cell = row.createCell(col);
        if (value instanceof BigDecimal n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof LocalDate d) {
            cell.setCellValue(d);
        } else {
            cell.setCellValue(value.toString());
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static Styles styles(XSSFWorkbook wb) {
        Font bold = wb.createFont();
        bold.setBold(true);

        CellStyle header = wb.createCellStyle();
        header.setFont(bold);
        header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle boldStyle = wb.createCellStyle();
        boldStyle.setFont(bold);

        var format = wb.createDataFormat();
        CellStyle money = wb.createCellStyle();
        money.setDataFormat(format.getFormat("#,##0.00"));
        CellStyle date = wb.createCellStyle();
        date.setDataFormat(format.getFormat("yyyy-mm-dd"));
        CellStyle number = wb.createCellStyle();
        number.setDataFormat(format.getFormat("#,##0.##"));
        CellStyle rate = wb.createCellStyle();   // 85.50, and 0.0033 keeps its digits
        rate.setDataFormat(format.getFormat("#,##0.00######"));
        return new Styles(header, money, date, number, boldStyle, rate);
    }
}
