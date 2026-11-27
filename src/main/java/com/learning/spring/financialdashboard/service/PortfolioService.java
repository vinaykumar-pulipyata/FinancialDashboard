package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Holding;
import com.learning.spring.financialdashboard.model.HoldingSnapshot;
import com.learning.spring.financialdashboard.model.PortfolioDetail;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The Mutual Funds (and Shares) panel: one account at Level 3 whose holdings are the individual funds. Each holding has
 * an invested amount and a current value that is typed in by hand; gain or loss is calculated.
 *
 * <p>The account's balance is the sum of the current values, so a gain raises net worth at once. Money reaches a
 * holding only through {@link #invest} and leaves it only through {@link #withdraw}; both are recorded as ordinary
 * transfers. Changing the value with {@link #updateValue} moves no money, so it records no transaction.
 */
@Service
@Transactional
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    /** quantity (units or shares held) is optional. */
    public record HoldingData(String name, BigDecimal investedAmount, BigDecimal currentValue, BigDecimal quantity) {

        public HoldingData(String name, BigDecimal investedAmount, BigDecimal currentValue) {
            this(name, investedAmount, currentValue, null);
        }
    }

    public record RenameRequest(String name) {
    }

    /**
     * investedAmount is optional: when given it corrects what was invested. date is optional too (today when left out):
     * the day the stats graph should record this value for; it cannot be in the future.
     */
    public record ValueRequest(BigDecimal currentValue, BigDecimal investedAmount, LocalDate date, BigDecimal quantity) {

        public ValueRequest(BigDecimal currentValue, BigDecimal investedAmount, LocalDate date) {
            this(currentValue, investedAmount, date, null);
        }
    }

    /** What a holding, or the whole portfolio added up, was worth on a day. */
    public record StatPoint(LocalDate date, BigDecimal investedAmount, BigDecimal currentValue) {
    }

    public record StatSeries(Long id, String name, List<StatPoint> points) {
    }

    /** overall adds up the holdings; each holding's line starts at its first recorded value. */
    public record Stats(List<StatPoint> overall, List<StatSeries> holdings) {
    }

    /**
     * Money from a bank or cash account, added to Total invested. holdingId names the fund it goes into (its invested
     * amount rises); newName instead creates a new fund with that name. With neither, only Total invested changes.
     */
    public record InvestRequest(Long fromAccountId, BigDecimal amount, Long holdingId, String newName, LocalDate date) {

        /** Invested today. */
        public InvestRequest(Long fromAccountId, BigDecimal amount, Long holdingId, String newName) {
            this(fromAccountId, amount, holdingId, newName, null);
        }

        public InvestRequest(Long fromAccountId, BigDecimal amount) {
            this(fromAccountId, amount, null, null);
        }
    }

    /**
     * amount is what comes out: the current value of what is sold, which is what the bank or cash account receives. investedAmount is the
     * cost of that part; the difference is the profit (or loss). When it is left out, the invested amount comes down by the same share as
     * the value does (and amount cannot be more than the holding is worth).
     */
    public record WithdrawRequest(Long toAccountId, BigDecimal amount, LocalDate date, BigDecimal investedAmount) {

        /** Withdrawn on a day, with the invested amount worked out as a share of the value. */
        public WithdrawRequest(Long toAccountId, BigDecimal amount, LocalDate date) {
            this(toAccountId, amount, date, null);
        }

        /** Withdrawn today. */
        public WithdrawRequest(Long toAccountId, BigDecimal amount) {
            this(toAccountId, amount, null, null);
        }
    }

    /** A dividend paid by one holding into a bank or cash account. date is today when left out. */
    public record DividendRequest(Long toAccountId, BigDecimal amount, LocalDate date) {
    }

    public record HoldingView(Long id, String name, BigDecimal investedAmount, BigDecimal currentValue,
                              BigDecimal gain, BigDecimal gainPercent, BigDecimal quantity) {
    }

    /**
     * totalInvested is what was put in and has not been taken out at cost (a withdrawal lowers it by the cost of what it took out);
     * investedAmount is the sum of what the holdings say is still in; realizedGain is what the sales made so far.
     */
    public record View(boolean configured, PortfolioKind kind, String label, Long accountId, String name,
                       BigDecimal investedAmount, BigDecimal totalInvested, BigDecimal currentValue, BigDecimal gain,
                       BigDecimal gainPercent, List<HoldingView> holdings, BigDecimal realizedGain) {

        static View notConfigured(PortfolioKind kind) {
            return new View(false, kind, kind.getLabel(), null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, null, List.of(), BigDecimal.ZERO);
        }
    }

    private final BalanceGuard balanceGuard;
    private final TransactionTagService tagService;
    private final PortfolioDetailRepository portfolios;
    private final HoldingRepository holdings;
    private final HoldingSnapshotRepository snapshots;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final RingFenceRepository ringFences;

    public PortfolioService(PortfolioDetailRepository portfolios, HoldingRepository holdings,
                            HoldingSnapshotRepository snapshots, CategoryRepository categories,
                            AccountRepository accounts, AccountService accountService,
                            TransactionRepository transactions, RingFenceRepository ringFences,
                         TransactionTagService tagService, BalanceGuard balanceGuard) {
        this.balanceGuard = balanceGuard;
        this.tagService = tagService;
        this.portfolios = portfolios;
        this.holdings = holdings;
        this.snapshots = snapshots;
        this.categories = categories;
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.ringFences = ringFences;
    }

    // ------------------------------------------------------------------------------------------ reading

    /** The portfolio of this kind, unless its account is gone (a detail row left behind is ignored, as for the other detail tables). */
    private Optional<PortfolioDetail> current(PortfolioKind kind) {
        return portfolios.findByKind(kind).filter(p -> accounts.existsById(p.getAccountId()));
    }

    @Transactional(readOnly = true)
    public View get(PortfolioKind kind) {
        return current(kind).map(this::view).orElseGet(() -> View.notConfigured(kind));
    }

    // ------------------------------------------------------------------------------------------ changing

    /** Creates the account for this panel if it is not there yet. */
    public View setUp(PortfolioKind kind) {
        Optional<PortfolioDetail> current = current(kind);
        if (current.isPresent()) {
            return view(current.get());
        }
        if (accounts.findAll().stream().anyMatch(a -> a.getName().equalsIgnoreCase(kind.getLabel()))) {
            throw new ValidationException("An account called '" + kind.getLabel() + "' already exists. Rename or remove it first");
        }
        Account account = accountService.create(new AccountData(kind.getLabel(), AccountType.INVESTMENT, null, null,
                BigDecimal.ZERO, null, null));
        log.info("{} set up: account={}", kind, account.getId());
        return view(portfolios.save(new PortfolioDetail(account.getId(), kind)));
    }

    /**
     * Adds a fund you already hold. Nothing is paid from an account, so the invested amount is added to the account's
     * opening balance: the net worth history then counts it from the start.
     */
    public View addHolding(PortfolioKind kind, HoldingData data) {
        PortfolioDetail portfolio = requireConfigured(kind);
        String name = cleanName(data.name());
        requireNameFree(portfolio, name, null);
        BigDecimal invested = data.investedAmount() == null ? BigDecimal.ZERO : data.investedAmount();
        BigDecimal value = data.currentValue() == null ? invested : data.currentValue();
        if (invested.signum() < 0 || value.signum() < 0 || (data.quantity() != null && data.quantity().signum() < 0)) {
            throw new ValidationException("Amounts cannot be negative");
        }
        BigDecimal totalBefore = totalInvested(portfolio);      // before this holding is in the list
        Holding holding = new Holding();
        holding.setAccountId(portfolio.getAccountId());
        holding.setName(name);
        holding.setInvestedAmount(invested);
        holding.setCurrentValue(value);
        holding.setQuantity(data.quantity());
        holdings.save(holding);
        record(holding, LocalDate.now());
        portfolio.setTotalInvested(totalBefore.add(invested));
        portfolios.save(portfolio);

        Account account = accounts.findById(portfolio.getAccountId()).orElseThrow();
        account.setOpeningBalance(account.getOpeningBalance().add(invested));
        accounts.save(account);
        log.info("{} holding added: id={}", kind, holding.getId());
        return view(portfolio);
    }

    public View rename(PortfolioKind kind, Long holdingId, RenameRequest request) {
        PortfolioDetail portfolio = requireConfigured(kind);
        Holding holding = requireHolding(portfolio, holdingId);
        String name = cleanName(request.name());
        requireNameFree(portfolio, name, holdingId);
        holding.setName(name);
        holdings.save(holding);
        log.info("{} holding renamed: id={}", kind, holdingId);
        return view(portfolio);
    }

    /**
     * The current value as your app or statement shows it, and optionally a correction to what was invested. No money
     * moves, so no transaction is recorded. Total invested is not touched. A correction to the invested amount moves
     * the account's opening balance by the same amount, so the net worth history counts it from the start.
     */
    public View updateValue(PortfolioKind kind, Long holdingId, ValueRequest request) {
        PortfolioDetail portfolio = requireConfigured(kind);
        Holding holding = requireHolding(portfolio, holdingId);
        if (request.currentValue() == null || request.currentValue().signum() < 0) {
            throw new ValidationException("Enter the current value");
        }
        LocalDate day = request.date() == null ? LocalDate.now() : request.date();
        if (day.isAfter(LocalDate.now())) {
            throw new ValidationException("The date cannot be in the future");
        }
        BigDecimal invested = request.investedAmount();
        if (invested != null) {
            if (invested.signum() < 0) {
                throw new ValidationException("Amounts cannot be negative");
            }
            BigDecimal change = invested.subtract(holding.getInvestedAmount());
            if (change.signum() != 0) {
                holding.setInvestedAmount(invested);
                Account account = accounts.findById(portfolio.getAccountId()).orElseThrow();
                account.setOpeningBalance(account.getOpeningBalance().add(change));
                accounts.save(account);
            }
        }
        if (request.quantity() != null) {
            if (request.quantity().signum() < 0) {
                throw new ValidationException("Amounts cannot be negative");
            }
            holding.setQuantity(request.quantity());
        }
        holding.setCurrentValue(request.currentValue());
        holdings.save(holding);
        record(holding, day);
        log.info("{} holding value updated: id={} date={}", kind, holdingId, day);
        return view(portfolio);
    }

    /**
     * Records money put in: it leaves a bank or cash account (an ordinary transfer into the portfolio account) and the
     * amount is added to Total invested. No holding changes: the funds are kept up to date by hand with
     * {@link #updateValue}, so net worth only rises when they are.
     */
    public View invest(PortfolioKind kind, InvestRequest request) {
        PortfolioDetail portfolio = requireConfigured(kind);
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the amount to invest");
        }
        LocalDate day = dayOf(request.date());
        Account from = requireBankOrCash(request.fromAccountId(), "the money comes from");
        if (ringFences.existsById(from.getId())) {
            throw new ValidationException("'" + from.getName() + "' is ring-fenced. Release it first to spend from it");
        }
        balanceGuard.requireFunds(from.getId(), amount);
        Holding target = null;
        String newName = null;
        if (request.holdingId() != null) {
            target = requireHolding(portfolio, request.holdingId());
        } else if (request.newName() != null) {
            newName = cleanName(request.newName());
            requireNameFree(portfolio, newName, null);
        }
        BigDecimal totalBefore = totalInvested(portfolio);      // before a new holding is in the list
        if (target != null) {           // only the invested amount rises; the value is updated by hand
            target.setInvestedAmount(target.getInvestedAmount().add(amount));
            holdings.save(target);
            record(target, day);
        } else if (newName != null) {   // a new fund starts worth what was put in
            target = new Holding();
            target.setAccountId(portfolio.getAccountId());
            target.setName(newName);
            target.setInvestedAmount(amount);
            target.setCurrentValue(amount);
            holdings.save(target);
            record(target, day);
        }
        Transaction transfer = saveTransfer(from.getId(), portfolio.getAccountId(), amount,
                "Invested in " + portfolio.getKind().getLabel(), day);
        if (target != null) {           // lets Delete take the amount back out of the same fund
            transfer.setHoldingId(target.getId());
            transactions.save(transfer);
        }
        portfolio.setTotalInvested(totalBefore.add(amount));
        portfolios.save(portfolio);
        log.info("Invested in {} from account {}", kind, from.getId());
        return view(portfolio);
    }

    /**
     * Deletes an Invest transfer: the money goes back to the account it came from (balances are calculated, so
     * deleting the transfer is enough), Total invested comes down and so does the invested amount of the fund it went
     * into. A fund that is left with nothing invested is left worth nothing too. Refused when the fund has since
     * had money withdrawn, or Total invested no longer covers it.
     */
    public View deleteInvestment(PortfolioKind kind, Long transactionId) {
        return deleteInvestment(kind, transactionId, null);
    }

    /** fundId is only used for an older investment that never recorded which fund it went into. */
    public View deleteInvestment(PortfolioKind kind, Long transactionId, Long fundId) {
        PortfolioDetail portfolio = requireConfigured(kind);
        Transaction t = transactions.findById(transactionId == null ? -1L : transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction " + transactionId + " not found"));
        if (t.getKind() != TransactionKind.TRANSFER || !portfolio.getAccountId().equals(t.getToAccountId())) {
            throw new ValidationException("Only an investment in " + kind.getLabel() + " can be deleted here");
        }
        BigDecimal amount = t.getAmount();
        BigDecimal total = totalInvested(portfolio);
        if (total.compareTo(amount) < 0) {
            throw new ValidationException("Total invested is lower than this investment, so it cannot be taken back");
        }
        Long linked = t.getHoldingId() != null ? t.getHoldingId() : fundId;
        if (linked != null) {
            Holding holding = holdings.findById(linked).orElse(null);
            if (holding != null && portfolio.getAccountId().equals(holding.getAccountId())) {
                if (holding.getInvestedAmount().compareTo(amount) < 0) {
                    throw new ValidationException("'" + holding.getName()
                            + "' has less invested than this, probably because money was withdrawn from it");
                }
                holding.setInvestedAmount(holding.getInvestedAmount().subtract(amount));
                if (holding.getInvestedAmount().signum() == 0) {
                    holding.setCurrentValue(BigDecimal.ZERO);
                    if (holding.getQuantity() != null) {
                        holding.setQuantity(BigDecimal.ZERO);
                    }
                }
                holdings.save(holding);
                record(holding, LocalDate.now());
            }
        }
        portfolio.setTotalInvested(total.subtract(amount));
        portfolios.save(portfolio);
        transactions.delete(t);
        log.info("Investment deleted from {}: transaction {}", kind, transactionId);
        return view(portfolio);
    }

    /**
     * Money leaves the holding for a bank or cash account. The amount comes off the current value, and the invested
     * amount comes down by the same share, so the gain percentage stays the same.
     */
    public View withdraw(PortfolioKind kind, Long holdingId, WithdrawRequest request) {
        PortfolioDetail portfolio = requireConfigured(kind);
        Holding holding = requireHolding(portfolio, holdingId);
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the amount to withdraw");
        }
        BigDecimal cost = request.investedAmount();   // what the part sold cost, when the person says; else the same share as the value
        BigDecimal invested = holding.getInvestedAmount();
        if (cost != null) {
            if (cost.signum() < 0) {
                throw new ValidationException("The invested amount cannot be negative");
            }
            if (cost.compareTo(invested) > 0) {
                throw new ValidationException("'" + holding.getName() + "' has only " + plain(invested) + " invested");
            }
        }
        BigDecimal value = holding.getCurrentValue();
        if (cost == null && amount.compareTo(value) > 0) {
            throw new ValidationException("'" + holding.getName() + "' is only worth " + plain(value));
        }
        if (cost != null && amount.compareTo(value) > 0) {
            value = amount;   // it sold for more than the value on file, which was out of date: it was worth what it fetched
        }
        LocalDate day = dayOf(request.date());
        Account to = requireBankOrCash(request.toAccountId(), "the money goes to");
        saveTransfer(portfolio.getAccountId(), to.getId(), amount, "Withdrawn from " + holding.getName(), day);
        BigDecimal reduction = cost != null ? cost
                : amount.compareTo(value) == 0 ? invested : invested.multiply(amount).divide(value, 2, RoundingMode.HALF_UP);
        holding.setInvestedAmount(invested.subtract(reduction).max(BigDecimal.ZERO));
        if (holding.getQuantity() != null) {      // the quantity comes down by the same share
            holding.setQuantity(amount.compareTo(value) == 0 ? BigDecimal.ZERO
                    : holding.getQuantity().multiply(value.subtract(amount)).divide(value, 4, RoundingMode.HALF_UP));
        }
        holding.setCurrentValue(value.subtract(amount));
        holdings.save(holding);
        record(holding, day);
        // what was sold leaves Total invested at its cost, and what it paid above (or below) that cost is booked as the sale's profit (or loss)
        portfolio.setTotalInvested(totalInvested(portfolio).subtract(reduction).max(BigDecimal.ZERO));
        BigDecimal booked = portfolio.getRealizedGain() == null ? BigDecimal.ZERO : portfolio.getRealizedGain();
        portfolio.setRealizedGain(booked.add(amount.subtract(reduction)));
        portfolios.save(portfolio);
        log.info("Withdrawn from {} holding {} to account {}", kind, holdingId, to.getId());
        return view(portfolio);
    }

    /**
     * Records a dividend: income into a bank or cash account, filed under Investment Income > Dividends (or Investment Income
     * when it has no Dividends under it), and linked to the holding that paid it. It is income, not capital, so the holding's
     * invested amount and value do not change.
     */
    public View dividend(PortfolioKind kind, Long holdingId, DividendRequest request) {
        PortfolioDetail portfolio = requireConfigured(kind);
        Holding holding = requireHolding(portfolio, holdingId);
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the dividend amount");
        }
        LocalDate day = request.date() == null ? LocalDate.now() : request.date();
        if (day.isAfter(LocalDate.now())) {
            throw new ValidationException("The date cannot be in the future");
        }
        Account to = requireBankOrCash(request.toAccountId(), "the dividend was paid into");
        Transaction t = new Transaction();
        t.setKind(TransactionKind.INCOME);
        t.setToAccountId(to.getId());
        t.setAmount(amount);
        t.setDate(day);
        t.setNote("Dividend: " + holding.getName());
        t.setCategoryId(dividendCategoryId());
        t.setHoldingId(holding.getId());
        tagService.tag(t);
        transactions.save(t);
        log.info("Dividend recorded: {} holding {} into account {}", kind, holdingId, to.getId());
        return view(portfolio);
    }

    /** Investment Income > Dividends if the user has it, else Investment Income itself (the starter list has no sub categories). */
    private Long dividendCategoryId() {
        List<Category> all = categories.findAll();
        return all.stream()
                .filter(c -> c.getParentId() == null && c.getKind() == CategoryKind.INCOME
                        && c.getName().equalsIgnoreCase("Investment Income"))
                .findFirst()
                .map(parent -> all.stream()
                        .filter(c -> parent.getId().equals(c.getParentId()) && c.getName().equalsIgnoreCase("Dividends"))
                        .findFirst().orElse(parent))
                .map(Category::getId).orElse(null);
    }

    /** Stops tracking a holding. It has to be worth nothing first, so no value disappears from net worth. */
    public View deleteHolding(PortfolioKind kind, Long holdingId) {
        PortfolioDetail portfolio = requireConfigured(kind);
        Holding holding = requireHolding(portfolio, holdingId);
        if (holding.getCurrentValue().signum() != 0) {
            throw new ValidationException("Withdraw everything from '" + holding.getName() + "' before removing it");
        }
        holding.setInvestedAmount(BigDecimal.ZERO);
        record(holding, LocalDate.now());          // the overall line stops counting it from today
        holdings.delete(holding);
        log.info("{} holding removed: id={}", kind, holdingId);
        return view(portfolio);
    }

    // ------------------------------------------------------------------------------------------ stats

    /** Stats graphs for a panel. A line starts on the day its first value was recorded; nothing is invented before it. */
    @Transactional(readOnly = true)
    public Stats stats(PortfolioKind kind) {
        Optional<PortfolioDetail> portfolio = current(kind);
        if (portfolio.isEmpty()) {
            return new Stats(List.of(), List.of());
        }
        Long accountId = portfolio.get().getAccountId();
        Map<Long, List<HoldingSnapshot>> byHolding = new HashMap<>();
        TreeSet<LocalDate> days = new TreeSet<>();
        for (HoldingSnapshot s : snapshots.findAllByAccountIdOrderByDateAscIdAsc(accountId)) {
            byHolding.computeIfAbsent(s.getHoldingId(), id -> new ArrayList<>()).add(s);
            days.add(s.getDate());
        }
        List<StatPoint> overall = new ArrayList<>();
        for (LocalDate day : days) {
            BigDecimal invested = BigDecimal.ZERO;
            BigDecimal value = BigDecimal.ZERO;
            for (List<HoldingSnapshot> list : byHolding.values()) {
                HoldingSnapshot latest = latestOnOrBefore(list, day);
                if (latest != null) {
                    invested = invested.add(latest.getInvestedAmount());
                    value = value.add(latest.getCurrentValue());
                }
            }
            overall.add(new StatPoint(day, invested, value));
        }
        List<StatSeries> series = holdings.findAllByAccountIdOrderByIdAsc(accountId).stream()
                .map(h -> new StatSeries(h.getId(), h.getName(), byHolding.getOrDefault(h.getId(), List.of()).stream()
                        .map(s -> new StatPoint(s.getDate(), s.getInvestedAmount(), s.getCurrentValue())).toList()))
                .toList();
        return new Stats(overall, series);
    }

    private HoldingSnapshot latestOnOrBefore(List<HoldingSnapshot> ascending, LocalDate day) {
        HoldingSnapshot found = null;
        for (HoldingSnapshot s : ascending) {
            if (s.getDate().isAfter(day)) {
                break;
            }
            found = s;
        }
        return found;
    }

    /** Start-up: a holding with no recorded value yet gets one for today, so its graph has a starting point. */
    public void recordMissingSnapshots() {
        for (Holding h : holdings.findAll()) {
            if (!snapshots.existsByHoldingId(h.getId())) {
                log.info("Holding {} had no recorded value: today's was recorded", h.getId());
                record(h, LocalDate.now());
            }
        }
    }

    /** Remembers the holding's figures for a day, replacing an earlier record of the same day. */
    private void record(Holding holding, LocalDate day) {
        HoldingSnapshot snapshot = snapshots.findByHoldingIdAndDate(holding.getId(), day)
                .orElseGet(() -> new HoldingSnapshot(holding.getId(), holding.getAccountId(), day,
                        holding.getInvestedAmount(), holding.getCurrentValue()));
        snapshot.setInvestedAmount(holding.getInvestedAmount());
        snapshot.setCurrentValue(holding.getCurrentValue());
        snapshots.save(snapshot);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private PortfolioDetail requireConfigured(PortfolioKind kind) {
        return current(kind)
                .orElseThrow(() -> new ValidationException("Set up " + kind.getLabel() + " first"));
    }

    private Holding requireHolding(PortfolioDetail portfolio, Long holdingId) {
        Holding holding = holdings.findById(holdingId == null ? -1L : holdingId)
                .orElseThrow(() -> new NotFoundException("Holding " + holdingId + " not found"));
        if (!holding.getAccountId().equals(portfolio.getAccountId())) {
            throw new NotFoundException("Holding " + holdingId + " not found");
        }
        return holding;
    }

    private Account requireBankOrCash(Long accountId, String role) {
        Account account = accounts.findById(accountId == null ? -1L : accountId)
                .orElseThrow(() -> new ValidationException("Choose the account " + role));
        if (!account.isActive() || (account.getType() != AccountType.BANK && account.getType() != AccountType.CASH)) {
            throw new ValidationException("Choose a bank or cash account");
        }
        if (!account.getCurrency().isBase()) {
            throw new ValidationException("Choose a bank or cash account in rupees");
        }
        return account;
    }

    private String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Enter a name");
        }
        return name.trim();
    }

    private void requireNameFree(PortfolioDetail portfolio, String name, Long exceptId) {
        boolean taken = holdings.findAllByAccountIdOrderByIdAsc(portfolio.getAccountId()).stream()
                .anyMatch(h -> !h.getId().equals(exceptId) && h.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new ValidationException("There is already a holding called '" + name + "'");
        }
    }

    /** The day a request is for: today when it names none, never a day that has not come yet. */
    private static LocalDate dayOf(LocalDate requested) {
        LocalDate day = requested == null ? LocalDate.now() : requested;
        if (day.isAfter(LocalDate.now())) {
            throw new ValidationException("The date cannot be in the future");
        }
        return day;
    }

    private Transaction saveTransfer(Long from, Long to, BigDecimal amount, String note, LocalDate day) {
        Transaction t = new Transaction();
        t.setKind(TransactionKind.TRANSFER);
        t.setFromAccountId(from);
        t.setToAccountId(to);
        t.setAmount(amount);
        t.setDate(day);
        t.setNote(note);
        tagService.tag(t);
        return transactions.save(t);
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private View view(PortfolioDetail portfolio) {
        Account account = accounts.findById(portfolio.getAccountId()).orElseThrow();
        List<HoldingView> rows = holdings.findAllByAccountIdOrderByIdAsc(portfolio.getAccountId()).stream()
                .map(h -> holdingView(h.getId(), h.getName(), h.getInvestedAmount(), h.getCurrentValue(), h.getQuantity())).toList();
        BigDecimal invested = rows.stream().map(HoldingView::investedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal value = rows.stream().map(HoldingView::currentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        HoldingView total = holdingView(null, account.getName(), invested, value, null);
        return new View(true, portfolio.getKind(), portfolio.getKind().getLabel(), account.getId(), account.getName(),
                invested, totalInvested(portfolio), value, total.gain(), total.gainPercent(), rows,
                portfolio.getRealizedGain() == null ? BigDecimal.ZERO : portfolio.getRealizedGain());
    }

    /** The recorded total, or the sum of the holdings' invested amounts until one has been recorded. */
    private BigDecimal totalInvested(PortfolioDetail portfolio) {
        if (portfolio.getTotalInvested() != null) {
            return portfolio.getTotalInvested();
        }
        return holdings.findAllByAccountIdOrderByIdAsc(portfolio.getAccountId()).stream()
                .map(Holding::getInvestedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private HoldingView holdingView(Long id, String name, BigDecimal invested, BigDecimal value, BigDecimal quantity) {
        BigDecimal gain = value.subtract(invested);
        BigDecimal percent = invested.signum() > 0
                ? gain.multiply(BigDecimal.valueOf(100)).divide(invested, 1, RoundingMode.HALF_UP) : null;
        return new HoldingView(id, name, invested, value, gain, percent, quantity);
    }
}
