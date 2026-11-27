package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Bucket;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.BucketEntry;
import com.learning.spring.financialdashboard.model.EntryKind;
import com.learning.spring.financialdashboard.model.LedgerDetail;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Future Expenses Ledger: one real account that holds the money saved for bills you know are coming. Its buckets
 * are slices of that money, each with a monthly allocation and (optionally) a bill size, frequency and dates.
 *
 * <p>Money reaches the Ledger only through {@link #fund}, leaves it only through {@link #pay}, and moves between
 * buckets through {@link #transfer}. A bucket's balance is the sum of its entries, so nothing is ever deleted and the
 * buckets always add up to the Ledger's balance: money that belongs to no particular bill lives in the Buffer.
 */
@Service
@Transactional
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public static final String LEDGER_NAME = "Future Expenses Ledger";

    private final TransactionTagService tagService;
    private final LedgerDetailRepository ledgers;
    private final BucketRepository buckets;
    private final BucketEntryRepository entries;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final RingFenceRepository ringFences;
    private final CategoryRepository categories;
    private final PortfolioDetailRepository portfolios;
    private final RecurringDepositDetailRepository recurringDeposits;

    private final BalanceGuard balanceGuard;

    public LedgerService(LedgerDetailRepository ledgers, BucketRepository buckets, BucketEntryRepository entries,
                         AccountRepository accounts, AccountService accountService,
                         TransactionRepository transactions, RingFenceRepository ringFences,
                         CategoryRepository categories, PortfolioDetailRepository portfolios,
                         RecurringDepositDetailRepository recurringDeposits, TransactionTagService tagService, BalanceGuard balanceGuard) {
        this.balanceGuard = balanceGuard;
        this.tagService = tagService;
        this.ledgers = ledgers;
        this.buckets = buckets;
        this.entries = entries;
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.ringFences = ringFences;
        this.recurringDeposits = recurringDeposits;
        this.categories = categories;
        this.portfolios = portfolios;
    }

    /**
     * heldNow is the money the bucket holds. When adding, it is money the user already has there (optional). When
     * editing, a different value corrects the balance. Either way the Ledger's total changes by the same amount.
     */
    public record BucketData(String name, BigDecimal monthlyAllocation, BigDecimal paymentAmount, Integer frequencyMonths,
                             LocalDate nextPaymentDate, LocalDate previousPaymentDate, LocalDate endDate,
                             BigDecimal heldNow) {
    }

    /**
     * allocations is only used when amount is less than the monthly plan: how much goes to each bucket. date is the day of the
     * funding (never in the future); left out it is today. The Money Manager import saves history through it.
     */
    public record FundRequest(Long fromAccountId, BigDecimal amount, Map<Long, BigDecimal> allocations, LocalDate date) {
        public FundRequest(Long fromAccountId, BigDecimal amount, Map<Long, BigDecimal> allocations) {
            this(fromAccountId, amount, allocations, null);
        }
    }

    /**
     * date, categoryId and note are optional: the day of the payment (never in the future, today when left out), the category it
     * is saved under (none when left out) and its note ("Paid: <bucket>" when left out). The Money Manager import uses them.
     */
    public record PayRequest(Long bucketId, BigDecimal amount, LocalDate date, Long categoryId, String note) {
        public PayRequest(Long bucketId, BigDecimal amount) {
            this(bucketId, amount, null, null, null);
        }
    }

    /** Interest the bank credited to the Ledger. It always goes to the buffer. */
    public record InterestRequest(BigDecimal amount, LocalDate date, Long categoryId) {
        public InterestRequest(BigDecimal amount) {
            this(amount, null, null);
        }
    }

    public record TransferRequest(Long fromBucketId, Long toBucketId, BigDecimal amount) {
    }

    /** destinationBucketId is where what is left goes. Null means the Buffer. */
    public record CloseRequest(Long destinationBucketId) {
    }

    public record BucketView(Long id, String name, BigDecimal monthlyAllocation, BigDecimal paymentAmount,
                             Integer frequencyMonths, LocalDate nextPaymentDate, LocalDate previousPaymentDate,
                             LocalDate endDate, boolean buffer, BigDecimal balance, boolean funded,
                             BigDecimal shortBy, boolean endDatePassed) {
    }

    public record ClosedBucketView(Long id, String name, LocalDate closedAt, BigDecimal totalPaid) {
    }

    public record View(boolean configured, Long accountId, String name, BigDecimal balance,
                       BigDecimal monthlyPlan, List<BucketView> buckets, List<ClosedBucketView> closed) {

        static View notConfigured() {
            return new View(false, null, null, null, null, List.of(), List.of());
        }
    }

    // ------------------------------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public View get() {
        Optional<LedgerDetail> current = findCurrent();
        return current.isEmpty() ? View.notConfigured() : view(current.get());
    }

    // ------------------------------------------------------------------------------------------ set up

    /** Creates the Ledger account. Does nothing if it already exists. */
    public View setUp() {
        Optional<LedgerDetail> current = findCurrent();
        if (current.isPresent()) {
            log.info("Future Expenses Ledger set up");
            return view(current.get());
        }
        if (accounts.findAll().stream().anyMatch(a -> a.getName().equalsIgnoreCase(LEDGER_NAME))) {
            throw new ValidationException("An account called '" + LEDGER_NAME + "' already exists. Rename or remove it first");
        }
        Account account = accountService.create(new AccountData(LEDGER_NAME, AccountType.GOAL_FUND, null, null,
                BigDecimal.ZERO, null, null));
        LedgerDetail created = ledgers.save(new LedgerDetail(account.getId()));
        ensureBuffer();
        return view(created);
    }

    /**
     * Run at start-up for a Ledger from before the Buffer took in all loose money: it makes sure the Ledger has its
     * Buffer and moves any money that belonged to no bucket into it. Safe to run again.
     */
    public void tidyUp() {
        Optional<LedgerDetail> current = findCurrent();
        if (current.isEmpty()) {
            return;
        }
        Bucket buffer = ensureBuffer();
        BigDecimal inBuckets = balances().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loose = ledgerBalance(current.get()).subtract(inBuckets);
        if (loose.signum() > 0) {
            entries.save(new BucketEntry(buffer.getId(), LocalDate.now(), EntryKind.MOVE_IN, loose,
                    "Money that belonged to no bucket moved into the Buffer", null));
            log.info("Ledger tidied: money that belonged to no bucket was moved into the Buffer");
        }
    }

    /**
     * The Buffer: the one bucket for extra money, interest and anything that belongs to no particular bill. Every Ledger
     * has one. If it is missing (an older Ledger), an open bucket called Buffer is taken over or a new one is created.
     */
    private Bucket ensureBuffer() {
        List<Bucket> open = openBuckets();
        Optional<Bucket> existing = open.stream().filter(Bucket::isBuffer).findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Bucket buffer = open.stream().filter(b -> b.getName().equalsIgnoreCase("Buffer")).findFirst().orElseGet(Bucket::new);
        if (buffer.getId() == null) {
            buffer.setMonthlyAllocation(BigDecimal.ZERO);
        }
        buffer.setName("Buffer");
        buffer.setBuffer(true);
        buffer.setPaymentAmount(null);
        buffer.setFrequencyMonths(null);
        buffer.setNextPaymentDate(null);
        buffer.setPreviousPaymentDate(null);
        buffer.setEndDate(null);
        return buckets.save(buffer);
    }

    // ------------------------------------------------------------------------------------------ buckets

    public View addBucket(BucketData data) {
        LedgerDetail ledger = requireLedger();
        Bucket bucket = new Bucket();
        apply(bucket, data);
        buckets.save(bucket);
        if (data.heldNow() != null && data.heldNow().signum() > 0) {
            recordHeld(ledger, bucket, data.heldNow(), EntryKind.OPENING, "Money already in the bucket");
        }
        log.info("Ledger bucket added: id={}", bucket.getId());
        return view(ledger);
    }

    /**
     * Changes a bucket. A different "money held" value corrects the balance, and the Ledger's total changes by the same amount so the buckets still add up to it.
     */
    public View updateBucket(Long id, BucketData data) {
        LedgerDetail ledger = requireLedger();
        Bucket bucket = openBucket(id);
        apply(bucket, data);
        if (data.heldNow() != null) {
            BigDecimal change = data.heldNow().subtract(balanceOf(bucket.getId()));
            if (change.signum() != 0) {
                recordHeld(ledger, bucket, change, EntryKind.ADJUST, "Balance corrected");
            }
        }
        log.info("Ledger bucket updated: id={}", id);
        return view(ledger);
    }

    /** Money the bucket already held, or a correction: an entry for the bucket and the same change to the Ledger. */
    private void recordHeld(LedgerDetail ledger, Bucket bucket, BigDecimal change, EntryKind kind, String note) {
        entries.save(new BucketEntry(bucket.getId(), LocalDate.now(), kind, change, note, null));
        Account account = accounts.findById(ledger.getAccountId()).orElseThrow();
        account.setOpeningBalance(account.getOpeningBalance().add(change));
    }

    private void apply(Bucket bucket, BucketData d) {
        String name = d.name() == null ? "" : d.name().trim();
        if (name.isEmpty()) {
            throw new ValidationException("Enter a name for the bucket");
        }
        boolean duplicate = openBuckets().stream()
                .anyMatch(b -> !Objects.equals(b.getId(), bucket.getId()) && b.getName().equalsIgnoreCase(name));
        if (duplicate) {
            throw new ValidationException("There is already a bucket called '" + name + "'");
        }
        BigDecimal monthly = d.monthlyAllocation() == null ? BigDecimal.ZERO : d.monthlyAllocation();
        if (monthly.signum() < 0) {
            throw new ValidationException("The monthly allocation cannot be negative");
        }
        if (d.paymentAmount() != null && d.paymentAmount().signum() <= 0) {
            throw new ValidationException("The payment amount must be greater than zero");
        }
        if (d.heldNow() != null && d.heldNow().signum() < 0) {
            throw new ValidationException("The money held cannot be negative");
        }
        if (bucket.isBuffer() && (d.paymentAmount() != null || d.frequencyMonths() != null || d.nextPaymentDate() != null
                || d.previousPaymentDate() != null || d.endDate() != null)) {
            // A buffer with an end date could block funding and could not be closed, so it is a plain cushion.
            throw new ValidationException("The buffer has no bill amount, payment dates or end date");
        }
        if (d.previousPaymentDate() != null && d.previousPaymentDate().isAfter(LocalDate.now())) {
            throw new ValidationException("The previous payment cannot be in the future");
        }
        if (d.frequencyMonths() != null && (d.frequencyMonths() < 1 || d.frequencyMonths() > 120)) {
            throw new ValidationException("Repeats every must be a number of months from 1 to 120");
        }
        if ((d.frequencyMonths() == null) != (d.nextPaymentDate() == null)) {
            throw new ValidationException("Enter both how many months between bills and the next payment date, or neither");
        }
        LocalDate today = LocalDate.now();
        boolean endChanged = !Objects.equals(d.endDate(), bucket.getEndDate());
        if (d.endDate() != null && endChanged && d.endDate().isBefore(today)) {
            throw new ValidationException("The end date cannot be in the past");
        }

        bucket.setName(name);
        bucket.setMonthlyAllocation(monthly);
        bucket.setPaymentAmount(d.paymentAmount());
        bucket.setFrequencyMonths(d.frequencyMonths());
        bucket.setNextPaymentDate(d.nextPaymentDate());
        bucket.setPreviousPaymentDate(d.previousPaymentDate());
        bucket.setEndDate(d.endDate());
    }

    // ------------------------------------------------------------------------------------------ funding

    /**
     * Moves money from an account into the Ledger and shares it out. Exactly the monthly plan: every bucket gets its
     * allocation. Less: the request says how much each bucket gets. More: the extra goes to the Buffer.
     */
    public View fund(FundRequest request) {
        LedgerDetail ledger = requireLedger();
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the amount to add to the Ledger");
        }
        Account from = accounts.findById(request.fromAccountId() == null ? -1L : request.fromAccountId())
                .orElseThrow(() -> new ValidationException("Choose the account the money comes from"));
        if (from.getId().equals(ledger.getAccountId())) {
            throw new ValidationException("The money has to come from another account");
        }
        if (!from.isActive() || from.getType() == AccountType.RECEIVABLE) {
            throw new ValidationException("Choose an account the money can come from");
        }
        if (!from.getCurrency().isBase()) {
            throw new ValidationException("Choose an account in rupees");
        }
        if (ringFences.existsById(from.getId())) {
            throw new ValidationException("'" + from.getName() + "' is ring-fenced. Release it first to spend from it");
        }
        if (from.getType() == AccountType.JEWELLERY || portfolios.existsById(from.getId()) || recurringDeposits.existsById(from.getId())) {
            throw new ValidationException("Money cannot come from Jewellery, Mutual Funds, Shares or Recurring Deposits: sell, withdraw or close it into a bank or cash account first");
        }

        balanceGuard.requireFunds(from.getId(), amount);
        Bucket buffer = ensureBuffer();
        List<Bucket> open = openBuckets();
        List<String> ended = open.stream().filter(this::endDatePassed).map(Bucket::getName).toList();
        if (!ended.isEmpty()) {
            throw new ValidationException("Close, or change the end date of, " + String.join(", ", ended) + " first");
        }
        BigDecimal plan = open.stream().map(Bucket::getMonthlyAllocation).reduce(BigDecimal.ZERO, BigDecimal::add);
        int comparison = amount.compareTo(plan);
        LocalDate day = request.date() == null ? LocalDate.now() : request.date();
        if (day.isAfter(LocalDate.now())) {
            throw new ValidationException("The date cannot be in the future");
        }

        Map<Long, BigDecimal> credit = new LinkedHashMap<>();
        if (comparison < 0) {
            credit.putAll(checkedAllocations(request.allocations(), open, amount));
        } else {
            open.forEach(b -> credit.put(b.getId(), b.getMonthlyAllocation()));
            BigDecimal extra = amount.subtract(plan);
            if (extra.signum() > 0) {
                credit.merge(buffer.getId(), extra, BigDecimal::add);
            }
        }

        Transaction moved = saveTransaction(TransactionKind.TRANSFER, from.getId(), ledger.getAccountId(), amount,
                day, "Funded the " + LEDGER_NAME);
        credit.forEach((bucketId, share) -> {
            if (share.signum() > 0) {
                entries.save(new BucketEntry(bucketId, day, EntryKind.FUND, share, "Added when the Ledger was funded",
                        moved.getId()));
            }
        });
        log.info("Ledger funded from account {}", from.getId());
        return view(ledger);
    }

    private Map<Long, BigDecimal> checkedAllocations(Map<Long, BigDecimal> given, List<Bucket> open, BigDecimal amount) {
        if (given == null || given.isEmpty()) {
            throw new ValidationException("This is less than your monthly plan, so say how much goes to each bucket");
        }
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : given.entrySet()) {
            if (open.stream().noneMatch(b -> b.getId().equals(e.getKey()))) {
                throw new ValidationException("One of the buckets is not open");
            }
            BigDecimal share = e.getValue() == null ? BigDecimal.ZERO : e.getValue();
            if (share.signum() < 0) {
                throw new ValidationException("A bucket cannot receive a negative amount");
            }
            result.put(e.getKey(), share);
            total = total.add(share);
        }
        if (total.compareTo(amount) != 0) {
            throw new ValidationException("The amounts for the buckets add up to " + plain(total) + ", not " + plain(amount));
        }
        return result;
    }

    /**
     * Deletes a funding: the transfer into the Ledger and what it added to each bucket. It is refused when that would
     * leave a bucket below zero (it has already paid out of the money). Only a funding can be deleted this way;
     * payments and interest cannot.
     */
    public View deleteFunding(Long transactionId) {
        LedgerDetail ledger = requireLedger();
        Transaction funding = transactions.findById(transactionId == null ? -1L : transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction " + transactionId + " not found"));
        if (funding.getKind() != TransactionKind.TRANSFER || !ledger.getAccountId().equals(funding.getToAccountId())) {
            throw new ValidationException("Only a funding of the " + LEDGER_NAME + " can be deleted here");
        }
        List<BucketEntry> added = entries.findAllByTransactionId(funding.getId());
        Map<Long, BigDecimal> balances = balances();
        BigDecimal allocated = BigDecimal.ZERO;
        for (BucketEntry entry : added) {
            allocated = allocated.add(entry.getAmount());
        }
        for (Bucket bucket : buckets.findAll()) {
            BigDecimal taken = added.stream().filter(e -> e.getBucketId().equals(bucket.getId()))
                    .map(BucketEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (taken.signum() > 0 && balances.getOrDefault(bucket.getId(), BigDecimal.ZERO).compareTo(taken) < 0) {
                throw new ValidationException("'" + bucket.getName() + "' has already used money from this funding. "
                        + "Transfer money into it first, then delete the funding");
            }
        }
        // An older funding may have put part of its money in no bucket. That part now sits in the Buffer.
        BigDecimal unlinked = funding.getAmount().subtract(allocated);
        Bucket buffer = unlinked.signum() > 0 ? ensureBuffer() : null;
        if (buffer != null) {
            Long bufferId = buffer.getId();
            BigDecimal takenFromBuffer = added.stream().filter(e -> e.getBucketId().equals(bufferId))
                    .map(BucketEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (balances.getOrDefault(buffer.getId(), BigDecimal.ZERO).subtract(takenFromBuffer).compareTo(unlinked) < 0) {
                throw new ValidationException("The Buffer no longer holds the part of this funding that was never given to a bucket. "
                        + "Move money into the Buffer first, then delete the funding");
            }
        }
        entries.deleteAll(added);
        if (buffer != null) {
            entries.save(new BucketEntry(buffer.getId(), LocalDate.now(), EntryKind.ADJUST, unlinked.negate(),
                    "Part of a funding that was deleted", null));
        }
        transactions.delete(funding);
        log.info("Ledger funding deleted: transaction={}", transactionId);
        return view(ledger);
    }

    // ------------------------------------------------------------------------------------------ paying

    /** Pays a bill from a bucket. Refused when the bucket holds less than the amount: move money into it first. */
    public View pay(PayRequest request) {
        LedgerDetail ledger = requireLedger();
        Bucket bucket = openBucket(request.bucketId());
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the amount to pay");
        }
        BigDecimal held = balanceOf(bucket.getId());
        if (held.compareTo(amount) < 0) {
            throw new ValidationException("'" + bucket.getName() + "' only has " + plain(held) + ". Transfer "
                    + plain(amount.subtract(held)) + " into it first");
        }
        LocalDate today = request.date() == null ? LocalDate.now() : request.date();
        if (today.isAfter(LocalDate.now())) {
            throw new ValidationException("The date cannot be in the future");
        }
        Transaction paid = saveTransaction(TransactionKind.EXPENSE, ledger.getAccountId(), null, amount, today,
                request.note() == null || request.note().isBlank() ? "Paid: " + bucket.getName() : request.note(), request.categoryId());
        entries.save(new BucketEntry(bucket.getId(), today, EntryKind.PAY, amount.negate(), "Paid", paid.getId()));
        bucket.setPreviousPaymentDate(today);
        if (bucket.getNextPaymentDate() != null && bucket.getFrequencyMonths() != null) {
            bucket.setNextPaymentDate(bucket.getNextPaymentDate().plusMonths(bucket.getFrequencyMonths()));
        }
        log.info("Ledger bill paid from bucket {}", bucket.getId());
        return view(ledger);
    }

    // ------------------------------------------------------------------------------------------ interest

    /**
     * Records interest the bank credited to the Ledger: it raises the Ledger's balance (as income, under
     * Investment Income > Interest, or Investment Income when there is no Interest under it) and goes to the buffer.
     */
    public View creditInterest(InterestRequest request) {
        LedgerDetail ledger = requireLedger();
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the interest the bank credited");
        }
        Bucket buffer = ensureBuffer();
        LocalDate today = request.date() == null ? LocalDate.now() : request.date();
        if (today.isAfter(LocalDate.now())) {
            throw new ValidationException("The date cannot be in the future");
        }
        Transaction credited = saveTransaction(TransactionKind.INCOME, null, ledger.getAccountId(), amount, today,
                "Interest credited to the " + LEDGER_NAME, request.categoryId() != null ? request.categoryId() : interestCategoryId());
        entries.save(new BucketEntry(buffer.getId(), today, EntryKind.INTEREST, amount, "Interest credited", credited.getId()));
        log.info("Interest credited to the Ledger");
        return view(ledger);
    }

    /** Investment Income > Interest if the user has it, else Investment Income itself (the starter list has no sub categories). */
    private Long interestCategoryId() {
        List<Category> all = categories.findAll();
        return all.stream()
                .filter(c -> c.getParentId() == null && c.getKind() == CategoryKind.INCOME
                        && c.getName().equalsIgnoreCase("Investment Income"))
                .findFirst()
                .map(parent -> all.stream()
                        .filter(c -> parent.getId().equals(c.getParentId()) && c.getName().equalsIgnoreCase("Interest"))
                        .findFirst().orElse(parent))
                .map(Category::getId).orElse(null);
    }

    // ------------------------------------------------------------------------------------------ moving money

    /** Moves money from one bucket to another, limited to what the first one holds. */
    public View transfer(TransferRequest request) {
        LedgerDetail ledger = requireLedger();
        Long fromId = request.fromBucketId();
        Long toId = request.toBucketId();
        BigDecimal amount = request.amount();
        if (fromId == null || toId == null) {
            throw new ValidationException("Choose the bucket the money moves from and the one it moves to");
        }
        if (fromId.equals(toId)) {
            throw new ValidationException("Choose two different buckets");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the amount to move");
        }
        Bucket from = openBucket(fromId);
        Bucket to = openBucket(toId);
        BigDecimal available = balanceOf(from.getId());
        if (available.compareTo(amount) < 0) {
            throw new ValidationException("'" + from.getName() + "' only has " + plain(available));
        }
        LocalDate today = LocalDate.now();
        entries.save(new BucketEntry(from.getId(), today, EntryKind.MOVE_OUT, amount.negate(), "Moved to " + to.getName(), null));
        entries.save(new BucketEntry(to.getId(), today, EntryKind.MOVE_IN, amount, "Moved from " + from.getName(), null));
        log.info("Ledger money moved: bucket {} to bucket {}", fromId, toId);
        return view(ledger);
    }

    // ------------------------------------------------------------------------------------------ closing

    /**
     * Closes a bucket. What is left, and its monthly allocation, go to the chosen bucket, or to the Buffer when none is
     * chosen. History is kept.
     */
    public View close(Long bucketId, CloseRequest request) {
        LedgerDetail ledger = requireLedger();
        Bucket bucket = openBucket(bucketId);
        if (bucket.isBuffer()) {
            throw new ValidationException("The buffer cannot be closed");
        }
        Long destinationId = request == null ? null : request.destinationBucketId();
        if (destinationId != null && destinationId.equals(bucketId)) {
            throw new ValidationException("Choose a different place for what is left");
        }
        Bucket destination = destinationId == null ? ensureBuffer() : openBucket(destinationId);
        BigDecimal left = balanceOf(bucket.getId());
        LocalDate today = LocalDate.now();
        if (left.signum() > 0) {
            entries.save(new BucketEntry(bucket.getId(), today, EntryKind.CLOSE_OUT, left.negate(),
                    "Closed. Moved to " + destination.getName(), null));
            entries.save(new BucketEntry(destination.getId(), today, EntryKind.CLOSE_IN, left,
                    "Left over from " + bucket.getName(), null));
        }
        destination.setMonthlyAllocation(destination.getMonthlyAllocation().add(bucket.getMonthlyAllocation()));
        bucket.setClosedAt(today);
        log.info("Ledger bucket closed: id={}", bucketId);
        return view(ledger);
    }

    // ------------------------------------------------------------------------------------------ restoring (Import from Excel)

    /**
     * Puts a bucket back exactly as it was in an exported file, with no checks of today's date (an ended or closed bucket is fine). The
     * Buffer is the one the Ledger already has. Returns the bucket's id.
     */
    public Long restoreBucket(String name, boolean buffer, BigDecimal monthly, BigDecimal payment, Integer every, LocalDate next,
                              LocalDate previous, LocalDate end, LocalDate closedAt) {
        requireLedger();
        Bucket bucket = buffer ? ensureBuffer() : new Bucket();
        if (!buffer) {
            bucket.setName(name);
            bucket.setPaymentAmount(payment);
            bucket.setFrequencyMonths(every);
            bucket.setNextPaymentDate(next);
            bucket.setPreviousPaymentDate(previous);
            bucket.setEndDate(end);
            bucket.setClosedAt(closedAt);
        }
        bucket.setMonthlyAllocation(monthly == null ? BigDecimal.ZERO : monthly);
        return buckets.save(bucket).getId();
    }

    /** A funding as it was: the transfer into the Ledger and what each bucket got of it. */
    public void restoreFunding(Long fromAccountId, BigDecimal amount, LocalDate date, String note, Map<Long, BigDecimal> credits) {
        LedgerDetail ledger = requireLedger();
        Transaction moved = saveTransaction(TransactionKind.TRANSFER, fromAccountId, ledger.getAccountId(), amount, date,
                note == null || note.isBlank() ? "Funded the " + LEDGER_NAME : note);
        credits.forEach((bucketId, share) -> entries.save(new BucketEntry(bucketId, date, EntryKind.FUND, share, "Added when the Ledger was funded", moved.getId())));
    }

    /** A bill as it was paid: the expense and the bucket's PAY entry. The bucket's dates are left as the file has them. */
    public void restorePayment(Long bucketId, BigDecimal amount, LocalDate date, Long categoryId, String note) {
        LedgerDetail ledger = requireLedger();
        Transaction paid = saveTransaction(TransactionKind.EXPENSE, ledger.getAccountId(), null, amount, date,
                note == null || note.isBlank() ? "Paid" : note, categoryId);
        entries.save(new BucketEntry(bucketId, date, EntryKind.PAY, amount.negate(), "Paid", paid.getId()));
    }

    /** Interest as it was credited: the income and the Buffer's INTEREST entry. */
    public void restoreInterest(BigDecimal amount, LocalDate date, Long categoryId, String note) {
        LedgerDetail ledger = requireLedger();
        Transaction credited = saveTransaction(TransactionKind.INCOME, null, ledger.getAccountId(), amount, date,
                note == null || note.isBlank() ? "Interest credited to the " + LEDGER_NAME : note, categoryId);
        entries.save(new BucketEntry(ensureBuffer().getId(), date, EntryKind.INTEREST, amount, "Interest credited", credited.getId()));
    }

    /** An entry that moved no money in or out of the Ledger (a move, a close) or only its opening (what a bucket held, a correction). */
    public void restoreEntry(Long bucketId, LocalDate date, EntryKind kind, BigDecimal amount, String note) {
        LedgerDetail ledger = requireLedger();
        entries.save(new BucketEntry(bucketId, date, kind, amount, note, null));
        if (kind == EntryKind.OPENING || kind == EntryKind.ADJUST) {   // these change what the Ledger held to begin with
            Account account = accounts.findById(ledger.getAccountId()).orElseThrow();
            account.setOpeningBalance(account.getOpeningBalance().add(amount));
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** The saved Ledger, ignoring (and clearing) one whose account no longer exists. */
    private Optional<LedgerDetail> findCurrent() {
        for (LedgerDetail detail : ledgers.findAll()) {
            if (accounts.existsById(detail.getAccountId())) {
                return Optional.of(detail);
            }
            ledgers.delete(detail);
        }
        return Optional.empty();
    }

    private LedgerDetail requireLedger() {
        return findCurrent().orElseThrow(() -> new ValidationException("Set up the " + LEDGER_NAME + " first"));
    }

    private List<Bucket> openBuckets() {
        return buckets.findAll().stream().filter(b -> b.getClosedAt() == null).toList();
    }

    private Bucket openBucket(Long id) {
        Bucket bucket = buckets.findById(id == null ? -1L : id)
                .orElseThrow(() -> new NotFoundException("Bucket " + id + " not found"));
        if (bucket.getClosedAt() != null) {
            throw new ValidationException("'" + bucket.getName() + "' is closed");
        }
        return bucket;
    }

    private boolean endDatePassed(Bucket bucket) {
        return bucket.getEndDate() != null && bucket.getEndDate().isBefore(LocalDate.now());
    }

    private BigDecimal balanceOf(Long bucketId) {
        return balances().getOrDefault(bucketId, BigDecimal.ZERO);
    }

    private Map<Long, BigDecimal> balances() {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Object[] row : entries.balances()) {
            result.put((Long) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    private BigDecimal ledgerBalance(LedgerDetail ledger) {
        return accountService.listSummaries().stream().filter(a -> a.id().equals(ledger.getAccountId()))
                .map(AccountSummary::balance).findFirst().orElse(BigDecimal.ZERO);
    }

    private Transaction saveTransaction(TransactionKind kind, Long from, Long to, BigDecimal amount, LocalDate date,
                                        String note) {
        return saveTransaction(kind, from, to, amount, date, note, null);
    }

    private Transaction saveTransaction(TransactionKind kind, Long from, Long to, BigDecimal amount, LocalDate date,
                                        String note, Long categoryId) {
        Transaction t = new Transaction();
        t.setCategoryId(categoryId);
        t.setKind(kind);
        t.setFromAccountId(from);
        t.setToAccountId(to);
        t.setAmount(amount);
        t.setDate(date);
        t.setNote(note);
        tagService.tag(t);
        return transactions.save(t);
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private View view(LedgerDetail ledger) {
        Account account = accounts.findById(ledger.getAccountId()).orElseThrow();
        Map<Long, BigDecimal> balances = balances();
        Map<Long, BigDecimal> paid = new HashMap<>();
        for (Object[] row : entries.totalsByKind(EntryKind.PAY)) {
            paid.put((Long) row[0], ((BigDecimal) row[1]).negate());
        }
        List<Bucket> all = buckets.findAll();

        // Soonest payment first; buckets with no payment date come last, by name.
        Comparator<Bucket> soonestFirst = Comparator.comparing(Bucket::getNextPaymentDate,
                Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()));
        Comparator<Bucket> order = soonestFirst.thenComparing(b -> b.getName().toLowerCase());
        Comparator<Bucket> newestClosedFirst = Comparator.comparing(Bucket::getClosedAt, Comparator.<LocalDate>reverseOrder());

        List<BucketView> open = all.stream().filter(b -> b.getClosedAt() == null)
                .sorted(order)
                .map(b -> {
                    BigDecimal balance = balances.getOrDefault(b.getId(), BigDecimal.ZERO);
                    BigDecimal target = b.getPaymentAmount();
                    boolean funded = target != null && balance.compareTo(target) >= 0;
                    BigDecimal shortBy = target != null && !funded ? target.subtract(balance) : BigDecimal.ZERO;
                    return new BucketView(b.getId(), b.getName(), b.getMonthlyAllocation(), target, b.getFrequencyMonths(),
                            b.getNextPaymentDate(), b.getPreviousPaymentDate(), b.getEndDate(), b.isBuffer(), balance,
                            funded, shortBy, endDatePassed(b));
                }).toList();
        List<ClosedBucketView> closed = all.stream().filter(b -> b.getClosedAt() != null)
                .sorted(newestClosedFirst)
                .map(b -> new ClosedBucketView(b.getId(), b.getName(), b.getClosedAt(),
                        paid.getOrDefault(b.getId(), BigDecimal.ZERO))).toList();
        BigDecimal plan = open.stream().map(BucketView::monthlyAllocation).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balance = ledgerBalance(ledger);
        return new View(true, account.getId(), account.getName(), balance, plan, open, closed);
    }
}
