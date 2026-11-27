package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.RecurringDeposit;
import com.learning.spring.financialdashboard.model.RecurringDeposit.Status;
import com.learning.spring.financialdashboard.model.RecurringDepositDetail;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositDetailRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Recurring deposits: one INVESTMENT account, "Recurring Deposits" (a row in recurring_deposit_detail marks it, and ordinary
 * transactions on it are refused, like Mutual Funds and Jewellery), with one entry per deposit. An entry has a term, and
 * optionally an instalment and a yearly rate, and is opened, deposited into and closed, never deleted, so its history stays.
 *
 * <p>A deposit is a transfer from a bank or cash account into the account and closing is a transfer of everything deposited back out,
 * plus income for the interest; each carries the entry's id. Money already in a deposit when it is added here is added to the
 * account's opening balance. An open deposit counts at what was put into it (the account's balance), not at the interest it has
 * earned so far: that arrives as income when it is closed.
 */
@Service
@Transactional
public class RecurringDepositService {

    private static final Logger log = LoggerFactory.getLogger(RecurringDepositService.class);

    public static final String ACCOUNT_NAME = "Recurring Deposits";
    public static final int MAX_TERM_MONTHS = 240;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    /** monthlyAmount, interestRate (per year, in percent) and alreadyDeposited may be left out. */
    public record OpenData(String name, LocalDate startDate, Integer termMonths, BigDecimal monthlyAmount, BigDecimal interestRate,
                           BigDecimal alreadyDeposited) {
    }

    /** What can be changed on an open deposit. Left-out values stay as they are. */
    public record EditData(String name, LocalDate startDate, Integer termMonths, BigDecimal monthlyAmount, BigDecimal interestRate,
                           BigDecimal alreadyDeposited) {
    }

    public record DepositRequest(Long fromAccountId, BigDecimal amount, LocalDate date) {
    }

    /** amountReceived is everything that came back, the deposits and the interest. */
    public record CloseRequest(Long toAccountId, BigDecimal amountReceived, LocalDate date) {
    }

    public record DepositView(Long id, String name, LocalDate startDate, int termMonths, LocalDate maturesOn, BigDecimal monthlyAmount,
                              BigDecimal interestRate, String status, LocalDate closedOn, BigDecimal deposited, int instalments,
                              BigDecimal plannedTotal, BigDecimal expectedMaturity, BigDecimal received, BigDecimal interestEarned,
                              int monthsElapsed, int progressPercent, BigDecimal alreadyDeposited) {
    }

    /** deposited is what the open deposits hold, which is also the balance of the account. */
    public record View(boolean configured, Long accountId, String name, BigDecimal deposited, List<DepositView> open, List<DepositView> closed) {

        static View notConfigured() {
            return new View(false, null, null, BigDecimal.ZERO, List.of(), List.of());
        }
    }

    private final RecurringDepositDetailRepository details;
    private final RecurringDepositRepository deposits;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final TransactionTagService tagService;
    private final BalanceGuard balanceGuard;
    private final RingFenceRepository ringFences;

    public RecurringDepositService(RecurringDepositDetailRepository details, RecurringDepositRepository deposits, AccountRepository accounts,
                                   AccountService accountService, TransactionRepository transactions, CategoryRepository categories,
                                   TransactionTagService tagService, BalanceGuard balanceGuard, RingFenceRepository ringFences) {
        this.details = details;
        this.deposits = deposits;
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.categories = categories;
        this.tagService = tagService;
        this.balanceGuard = balanceGuard;
        this.ringFences = ringFences;
    }

    // ------------------------------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public View get() {
        return current().map(this::view).orElseGet(View::notConfigured);
    }

    private Optional<RecurringDepositDetail> current() {
        return details.findAll().stream().filter(d -> accounts.existsById(d.getAccountId())).findFirst();   // a row whose account is gone is ignored
    }

    // ------------------------------------------------------------------------------------------ changing

    /** Creates the account if it is not there yet. */
    public View setUp() {
        Optional<RecurringDepositDetail> existing = current();
        if (existing.isPresent()) {
            return view(existing.get());
        }
        if (accounts.findAll().stream().anyMatch(a -> a.getName().equalsIgnoreCase(ACCOUNT_NAME))) {
            throw new ValidationException("An account called '" + ACCOUNT_NAME + "' already exists. Rename or remove it first");
        }
        Account account = accountService.create(new AccountData(ACCOUNT_NAME, AccountType.INVESTMENT, null, null, BigDecimal.ZERO, null, null));
        log.info("Recurring Deposits set up: account={}", account.getId());
        return view(details.save(new RecurringDepositDetail(account.getId())));
    }

    /** Adds a deposit. What was already in it is added to the account's opening balance, so net worth counts it from the start. */
    public View open(OpenData data) {
        RecurringDepositDetail detail = requireConfigured();
        String name = cleanName(data.name());
        requireNameFree(detail, name, null);
        if (data.startDate() == null) {
            throw new ValidationException("Enter the day the deposit started");
        }
        int term = requireTerm(data.termMonths());
        BigDecimal monthly = requireMoney(data.monthlyAmount(), "The monthly amount");
        BigDecimal rate = requireRate(data.interestRate());
        BigDecimal already = data.alreadyDeposited() == null ? BigDecimal.ZERO : requireMoney(data.alreadyDeposited(), "The amount already deposited");

        RecurringDeposit deposit = new RecurringDeposit(detail.getAccountId(), name, data.startDate(), term);
        deposit.setMonthlyAmount(monthly);
        deposit.setInterestRate(rate);
        deposit.setAlreadyDeposited(already == null ? BigDecimal.ZERO : already);
        deposits.save(deposit);
        shiftOpening(detail.getAccountId(), deposit.getAlreadyDeposited());
        log.info("Recurring deposit opened: id={} termMonths={}", deposit.getId(), term);
        return view(detail);
    }

    /** Changes an open deposit's name, term, instalment, rate or what was already in it. */
    public View update(Long id, EditData data) {
        RecurringDepositDetail detail = requireConfigured();
        RecurringDeposit deposit = requireOpen(detail, id);
        if (data.name() != null) {
            String name = cleanName(data.name());
            requireNameFree(detail, name, id);
            deposit.setName(name);
        }
        if (data.startDate() != null) {
            deposit.setStartDate(data.startDate());
        }
        if (data.termMonths() != null) {
            deposit.setTermMonths(requireTerm(data.termMonths()));
        }
        if (data.monthlyAmount() != null) {
            deposit.setMonthlyAmount(requireMoney(data.monthlyAmount(), "The monthly amount"));
        }
        if (data.interestRate() != null) {
            deposit.setInterestRate(requireRate(data.interestRate()));
        }
        if (data.alreadyDeposited() != null) {
            BigDecimal already = requireMoney(data.alreadyDeposited(), "The amount already deposited");
            shiftOpening(detail.getAccountId(), already.subtract(deposit.getAlreadyDeposited()));
            deposit.setAlreadyDeposited(already);
        }
        deposits.save(deposit);
        log.info("Recurring deposit changed: id={}", id);
        return view(detail);
    }

    /** Puts money into an open deposit: a transfer from a bank or cash account, on the day given (today when left out). */
    public View deposit(Long id, DepositRequest request) {
        RecurringDepositDetail detail = requireConfigured();
        RecurringDeposit deposit = requireOpen(detail, id);
        BigDecimal amount = requireMoney(request.amount(), "The amount");
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the amount to deposit");
        }
        LocalDate day = dayOf(request.date());
        Account from = requireBankOrCash(request.fromAccountId(), "the money comes from");
        if (ringFences.existsById(from.getId())) {
            throw new ValidationException("'" + from.getName() + "' is ring-fenced. Release it first to spend from it");
        }
        balanceGuard.requireFunds(from.getId(), amount);
        Transaction t = transfer(from.getId(), detail.getAccountId(), amount, "Deposit: " + deposit.getName(), day, id);
        transactions.save(t);
        log.info("Recurring deposit {} received a deposit from account {}", id, from.getId());
        return view(detail);
    }

    /**
     * Closes a deposit at maturity or early. Everything deposited goes back to the bank or cash account as a transfer and what came
     * back above that is interest, saved as income (Bank Interest, else Investment Income). The deposit stays, marked closed.
     */
    public View close(Long id, CloseRequest request) {
        RecurringDepositDetail detail = requireConfigured();
        RecurringDeposit deposit = requireOpen(detail, id);
        BigDecimal received = requireMoney(request.amountReceived(), "The amount received");
        if (received == null || received.signum() <= 0) {
            throw new ValidationException("Enter the amount that came back");
        }
        LocalDate day = dayOf(request.date());
        if (day.isBefore(deposit.getStartDate())) {
            throw new ValidationException("A deposit cannot be closed before it started");
        }
        Account to = requireBankOrCash(request.toAccountId(), "the money goes to");
        BigDecimal principal = depositedOf(deposit);
        if (received.compareTo(principal) < 0) {
            throw new ValidationException("The amount received cannot be less than the " + plain(principal) + " that was deposited");
        }
        if (principal.signum() > 0) {
            transactions.save(transfer(detail.getAccountId(), to.getId(), principal, "Closed: " + deposit.getName(), day, id));
        }
        BigDecimal interest = received.subtract(principal);
        if (interest.signum() > 0) {
            Transaction t = new Transaction();
            t.setKind(TransactionKind.INCOME);
            t.setToAccountId(to.getId());
            t.setAmount(interest);
            t.setDate(day);
            t.setNote("Interest: " + deposit.getName());
            t.setCategoryId(interestCategoryId());
            t.setRecurringDepositId(id);
            tagService.tag(t);
            transactions.save(t);
        }
        deposit.setStatus(Status.CLOSED);
        deposit.setClosedOn(day);
        deposit.setReceivedAmount(received);
        deposits.save(deposit);
        log.info("Recurring deposit closed: id={} into account {}", id, to.getId());
        return view(detail);
    }

    /** Removes a deposit made by mistake: the money goes back to the account it came from. Only while the deposit is open. */
    public View deleteDeposit(Long transactionId) {
        RecurringDepositDetail detail = requireConfigured();
        Transaction t = transactions.findById(transactionId == null ? -1L : transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction " + transactionId + " not found"));
        if (t.getRecurringDepositId() == null || !detail.getAccountId().equals(t.getToAccountId())) {
            throw new ValidationException("That is not a deposit into a recurring deposit");
        }
        requireOpen(detail, t.getRecurringDepositId());
        transactions.delete(t);
        log.info("Recurring deposit deposit removed: transaction={}", transactionId);
        return view(detail);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private RecurringDepositDetail requireConfigured() {
        return current().orElseThrow(() -> new ValidationException("Set up Recurring Deposits first"));
    }

    private RecurringDeposit requireOpen(RecurringDepositDetail detail, Long id) {
        RecurringDeposit deposit = deposits.findById(id == null ? -1L : id)
                .filter(d -> d.getAccountId().equals(detail.getAccountId()))
                .orElseThrow(() -> new NotFoundException("Recurring deposit " + id + " not found"));
        if (deposit.getStatus() == Status.CLOSED) {
            throw new ValidationException("'" + deposit.getName() + "' is closed");
        }
        return deposit;
    }

    private String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Enter a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100) {
            throw new ValidationException("The name is too long");
        }
        return trimmed;
    }

    private void requireNameFree(RecurringDepositDetail detail, String name, Long exceptId) {
        boolean taken = deposits.findAllByAccountIdOrderByIdAsc(detail.getAccountId()).stream()
                .anyMatch(d -> !d.getId().equals(exceptId) && d.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new ValidationException("There is already a deposit called '" + name + "'");
        }
    }

    private static int requireTerm(Integer months) {
        if (months == null || months < 1 || months > MAX_TERM_MONTHS) {
            throw new ValidationException("The term must be from 1 to " + MAX_TERM_MONTHS + " months");
        }
        return months;
    }

    /** A money amount with at most two decimals and not absurd; null stays null (optional). */
    private static BigDecimal requireMoney(BigDecimal amount, String what) {
        if (amount == null) {
            return null;
        }
        if (amount.signum() < 0) {
            throw new ValidationException(what + " cannot be negative");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new ValidationException(what + " can have at most two decimals");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException(what + " is too large");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal requireRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        if (rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new ValidationException("The interest rate must be from 0 to 100 percent a year");
        }
        if (rate.stripTrailingZeros().scale() > 3) {
            throw new ValidationException("The interest rate can have at most three decimals");
        }
        return rate.setScale(3, RoundingMode.UNNECESSARY);
    }

    private static LocalDate dayOf(LocalDate requested) {
        LocalDate day = requested == null ? LocalDate.now() : requested;
        if (day.isAfter(LocalDate.now())) {
            throw new ValidationException("The date cannot be in the future");
        }
        return day;
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

    private void shiftOpening(Long accountId, BigDecimal delta) {
        if (delta == null || delta.signum() == 0) {
            return;
        }
        Account account = accounts.findById(accountId).orElseThrow();
        account.setOpeningBalance(account.getOpeningBalance().add(delta));
        accounts.save(account);
    }

    private Transaction transfer(Long from, Long to, BigDecimal amount, String note, LocalDate day, Long depositId) {
        Transaction t = new Transaction();
        t.setKind(TransactionKind.TRANSFER);
        t.setFromAccountId(from);
        t.setToAccountId(to);
        t.setAmount(amount);
        t.setDate(day);
        t.setNote(note.length() > 255 ? note.substring(0, 255) : note);
        t.setRecurringDepositId(depositId);
        tagService.tag(t);
        return t;
    }

    private Long interestCategoryId() {
        List<Category> all = categories.findAll();
        for (String name : List.of("Bank Interest", "Investment Income")) {
            Optional<Long> id = all.stream().filter(c -> c.isActive() && c.getParentId() == null && c.getKind() == CategoryKind.INCOME
                    && c.getName().equalsIgnoreCase(name)).findFirst().map(Category::getId);
            if (id.isPresent()) {
                return id.get();
            }
        }
        log.warn("Recurring deposit closed: no Bank Interest or Investment Income category, the interest is saved uncategorised");
        return null;
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** What went into a deposit: what was already there and every transfer into the account that carries its id. */
    private BigDecimal depositedOf(RecurringDeposit deposit) {
        BigDecimal total = deposit.getAlreadyDeposited();
        for (Transaction t : transactions.findAllByRecurringDepositId(deposit.getId())) {
            if (deposit.getAccountId().equals(t.getToAccountId())) {
                total = total.add(t.getAmount());
            }
        }
        return total;
    }

    // ------------------------------------------------------------------------------------------ the view

    private View view(RecurringDepositDetail detail) {
        Account account = accounts.findById(detail.getAccountId()).orElseThrow();
        LocalDate today = LocalDate.now();
        List<DepositView> all = deposits.findAllByAccountIdOrderByIdAsc(detail.getAccountId()).stream()
                .map(d -> depositView(d, today)).toList();
        List<DepositView> open = all.stream().filter(d -> d.status().equals(Status.OPEN.name())).toList();
        List<DepositView> closed = all.stream().filter(d -> d.status().equals(Status.CLOSED.name()))
                .sorted((a, b) -> b.closedOn().compareTo(a.closedOn())).toList();
        BigDecimal held = open.stream().map(DepositView::deposited).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new View(true, account.getId(), account.getName(), held, open, closed);
    }

    private DepositView depositView(RecurringDeposit d, LocalDate today) {
        List<Transaction> made = transactions.findAllByRecurringDepositId(d.getId()).stream()
                .filter(t -> d.getAccountId().equals(t.getToAccountId()) && t.getKind() == TransactionKind.TRANSFER).toList();
        BigDecimal deposited = d.getAlreadyDeposited().add(made.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        LocalDate matures = d.getStartDate().plusMonths(d.getTermMonths());
        boolean closed = d.getStatus() == Status.CLOSED;
        LocalDate asOf = closed ? d.getClosedOn() : today;
        long total = Math.max(1, ChronoUnit.DAYS.between(d.getStartDate(), matures));
        long elapsed = Math.min(Math.max(ChronoUnit.DAYS.between(d.getStartDate(), asOf), 0), total);
        int months = (int) Math.min(Math.max(ChronoUnit.MONTHS.between(d.getStartDate(), asOf), 0), d.getTermMonths());
        BigDecimal planned = d.getMonthlyAmount() == null ? null : d.getMonthlyAmount().multiply(BigDecimal.valueOf(d.getTermMonths()));
        BigDecimal interest = closed ? d.getReceivedAmount().subtract(deposited) : null;
        return new DepositView(d.getId(), d.getName(), d.getStartDate(), d.getTermMonths(), matures, d.getMonthlyAmount(), d.getInterestRate(),
                d.getStatus().name(), d.getClosedOn(), deposited, made.size(), planned, expectedMaturity(d), closed ? d.getReceivedAmount() : null,
                interest, months, (int) Math.round(elapsed * 100.0 / total), d.getAlreadyDeposited());
    }

    /**
     * What a deposit of the monthly amount for the whole term would pay at the yearly rate, with interest added every quarter (the way
     * banks work out a recurring deposit): each instalment grows for the months from its day to the maturity. Null without a rate and an amount.
     */
    static BigDecimal expectedMaturity(RecurringDeposit d) {
        if (d.getMonthlyAmount() == null || d.getInterestRate() == null) {
            return null;
        }
        double quarterly = 1 + d.getInterestRate().doubleValue() / 400.0;
        double total = 0;
        for (int month = 1; month <= d.getTermMonths(); month++) {
            total += d.getMonthlyAmount().doubleValue() * Math.pow(quarterly, 4.0 * (d.getTermMonths() - month + 1) / 12.0);
        }
        return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    }
}
