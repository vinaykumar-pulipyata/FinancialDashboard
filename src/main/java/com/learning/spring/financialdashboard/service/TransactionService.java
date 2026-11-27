package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.SplitSettlementRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository repository;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final RingFenceRepository ringFences;
    private final BorrowerRepository borrowers;
    private final SystemCategories systemCategories;
    private final LoanService loanService;
    private final LedgerDetailRepository ledgers;
    private final PortfolioDetailRepository portfolios;
    private final RecurringDepositDetailRepository recurringDeposits;
    private final TransactionTagService tagService;
    private final SplitSettlementRepository splitSettlements;

    public TransactionService(TransactionRepository repository, AccountRepository accounts,
                              CategoryRepository categories, RingFenceRepository ringFences,
                              BorrowerRepository borrowers, SystemCategories systemCategories,
                              LoanService loanService, LedgerDetailRepository ledgers,
                              PortfolioDetailRepository portfolios, RecurringDepositDetailRepository recurringDeposits,
                              TransactionTagService tagService, BalanceGuard balanceGuard,
                              SplitSettlementRepository splitSettlements) {
        this.splitSettlements = splitSettlements;
        this.balanceGuard = balanceGuard;
        this.repository = repository;
        this.accounts = accounts;
        this.categories = categories;
        this.ringFences = ringFences;
        this.borrowers = borrowers;
        this.systemCategories = systemCategories;
        this.loanService = loanService;
        this.ledgers = ledgers;
        this.portfolios = portfolios;
        this.recurringDeposits = recurringDeposits;
        this.tagService = tagService;
    }

    /**
     * borrowerId is only for Loans Given and Loans Collected: who the loan is with. Null for everything else. splits is only for an ordinary
     * expense shared with other people: what each of them owes of it, by person id (the user's own share is the rest); null or empty = not split.
     * paidByBorrowerId is only for an expense somebody else paid: the person who paid (the expense then has no from-account), with ownShare, what the
     * user owes of the bill; it cannot also be split.
     */
    public record TransactionData(LocalDate date, TransactionKind kind, Long fromAccountId,
                                  Long toAccountId, BigDecimal amount, BigDecimal fee, Long categoryId,
                                  String note, String description, Long borrowerId, List<String> tags, BigDecimal toAmount,
                                  Map<Long, BigDecimal> splits, Long paidByBorrowerId, BigDecimal ownShare) {

        /** Paid by the user (not by somebody else). */
        public TransactionData(LocalDate date, TransactionKind kind, Long fromAccountId, Long toAccountId, BigDecimal amount,
                               BigDecimal fee, Long categoryId, String note, String description, Long borrowerId, List<String> tags,
                               BigDecimal toAmount, Map<Long, BigDecimal> splits) {
            this(date, kind, fromAccountId, toAccountId, amount, fee, categoryId, note, description, borrowerId, tags, toAmount, splits, null, null);
        }

        /** Not split with anyone. */
        public TransactionData(LocalDate date, TransactionKind kind, Long fromAccountId, Long toAccountId, BigDecimal amount,
                               BigDecimal fee, Long categoryId, String note, String description, Long borrowerId, List<String> tags,
                               BigDecimal toAmount) {
            this(date, kind, fromAccountId, toAccountId, amount, fee, categoryId, note, description, borrowerId, tags, toAmount, null, null, null);
        }

        /** In one currency, or with no received amount: the amount that arrived is the amount that left. */
        public TransactionData(LocalDate date, TransactionKind kind, Long fromAccountId, Long toAccountId, BigDecimal amount,
                               BigDecimal fee, Long categoryId, String note, String description, Long borrowerId, List<String> tags) {
            this(date, kind, fromAccountId, toAccountId, amount, fee, categoryId, note, description, borrowerId, tags, null);
        }

        /** Without tags: they are worked out from the note, description and category. */
        public TransactionData(LocalDate date, TransactionKind kind, Long fromAccountId, Long toAccountId, BigDecimal amount,
                               BigDecimal fee, Long categoryId, String note, String description, Long borrowerId) {
            this(date, kind, fromAccountId, toAccountId, amount, fee, categoryId, note, description, borrowerId, null);
        }
    }

    private final BalanceGuard balanceGuard;

    public Transaction create(TransactionData data) {
        Transaction t = new Transaction();
        apply(t, data);
        t.setTags(tagsFor(data));
        Transaction saved = repository.save(t);
        log.info("Transaction recorded: id={} kind={} date={}", saved.getId(), saved.getKind(), saved.getDate());
        return saved;
    }

    public Transaction update(Long id, TransactionData data) {
        Transaction t = get(id);
        requireNotLedger(t.getFromAccountId(), t.getToAccountId());
        requireActive(t.getFromAccountId());
        requireActive(t.getToAccountId());
        if (splitSettlements.existsByIncomeTransactionId(id)) {
            throw new ValidationException("This payment settled shared expenses. Delete it and settle again to change it");
        }
        apply(t, data);
        t.setTags(tagsFor(data));
        Transaction saved = repository.save(t);
        log.info("Transaction updated: id={} kind={} date={}", saved.getId(), saved.getKind(), saved.getDate());
        return saved;
    }

    /** The tags to save: the list that came with the request, cleaned, or, when it left them out, the ones worked out from the text. */
    private java.util.Collection<String> tagsFor(TransactionData data) {
        return data.tags() != null ? tagService.clean(data.tags()) : tagService.suggest(data.note(), data.description(), data.categoryId());
    }

    /** What tags a transaction like this would get (the form shows them while you type). Saves nothing. */
    @Transactional(readOnly = true)
    public List<String> suggestTags(String note, String description, Long categoryId) {
        return tagService.suggest(note, description, categoryId);
    }

    @Transactional(readOnly = true)
    public Transaction get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transaction " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Transaction> list() {
        return repository.findAllByOrderByDateDescIdDesc();
    }

    public void delete(Long id) {
        Transaction t = get(id);
        requireNotLedger(t.getFromAccountId(), t.getToAccountId());
        requireActive(t.getFromAccountId());
        requireActive(t.getToAccountId());
        if (splitSettlements.existsByExpenseTransactionId(id)) {
            throw new ValidationException("Part of this shared expense has been settled. Delete the payment that settled it first");
        }
        splitSettlements.deleteByIncomeTransactionId(id);   // a payment that settled shares: those shares are open again
        repository.delete(t);
        log.info("Transaction deleted: id={} kind={} date={}", id, t.getKind(), t.getDate());
    }

    private void apply(Transaction t, TransactionData d) {
        if (d.kind() == null) {
            throw new ValidationException("Transaction kind is required");
        }
        if (d.date() == null) {
            throw new ValidationException("Date is required");
        }
        if (d.amount() == null || d.amount().signum() <= 0) {
            throw new ValidationException("Amount must be greater than zero");
        }
        boolean payment = systemCategories.isLiabilityPayment(d.categoryId());
        switch (d.kind()) {
            case INCOME -> {
                require(d.toAccountId() != null && d.fromAccountId() == null,
                        "Income needs a destination account only");
            }
            case EXPENSE -> {
                if (payment) {   // a payment towards a liability is an expense that also names the loan or card it pays
                    require(d.fromAccountId() != null && d.toAccountId() != null,
                            "A payment needs the account it is paid from and the loan or card it pays");
                } else if (d.paidByBorrowerId() != null) {   // somebody else paid: no account of the user's is involved
                    require(d.fromAccountId() == null && d.toAccountId() == null,
                            "An expense somebody else paid for has no account");
                } else {
                    require(d.fromAccountId() != null && d.toAccountId() == null,
                            "Expense needs a source account only");
                }
            }
            case TRANSFER -> {
                require(d.fromAccountId() != null && d.toAccountId() != null,
                        "Transfer needs both a source and a destination account");
                require(!d.fromAccountId().equals(d.toAccountId()),
                        "Cannot transfer to the same account");
            }
        }
        requireNotLedger(d.fromAccountId(), d.toAccountId());
        BigDecimal received = checkCurrencies(d);
        checkActive(t, d.fromAccountId());
        checkActive(t, d.toAccountId());
        checkRingFence(t, d);
        checkFee(d);
        checkFunds(t, d);
        checkCategory(t, d);
        checkPayment(d, payment);
        boolean loan = checkLoan(d);
        Map<Long, BigDecimal> shares = checkSplits(t, d, payment, loan);
        checkPaidBy(t, d, payment, loan, shares);

        t.setSplits(shares);
        t.setPaidByBorrowerId(d.paidByBorrowerId());
        t.setOwnShare(d.paidByBorrowerId() == null ? null : d.ownShare());
        t.setDate(d.date());
        t.setKind(d.kind());
        t.setFromAccountId(d.fromAccountId());
        t.setToAccountId(d.toAccountId());
        t.setAmount(d.amount());
        t.setToAmount(received);
        t.setFee(d.fee() == null || d.fee().signum() == 0 ? null : d.fee());
        t.setCategoryId(d.categoryId());
        t.setBorrowerId(loan ? d.borrowerId() : null);
        t.setNote(blankToNull(d.note()));
        t.setDescription(blankToNull(d.description()));
    }

    /**
     * The shares other people owe of an expense, cleaned (empty when it is not split). Only an ordinary expense paid from a rupee account can be
     * split; every person must be on the Receivables list, each share is more than nothing, and together they cannot be more than the amount (the
     * rest is the user's own). Once part of a split has been settled, the amount and the shares can no longer change.
     */
    private Map<Long, BigDecimal> checkSplits(Transaction t, TransactionData d, boolean payment, boolean loan) {
        Map<Long, BigDecimal> shares = new LinkedHashMap<>();
        if (d.splits() != null) {
            d.splits().forEach((person, share) -> {
                if (person != null && share != null) {
                    shares.put(person, share);
                }
            });
        }
        if (t.getId() != null && splitSettlements.existsByExpenseTransactionId(t.getId())) {
            boolean same = d.kind() == TransactionKind.EXPENSE && d.amount().compareTo(t.getAmount()) == 0
                    && sameShares(shares, t.getSplits());
            if (!same) {
                throw new ValidationException("Part of this shared expense has been settled, so its amount and shares cannot change. "
                        + "Delete the payment that settled it first");
            }
        }
        if (shares.isEmpty()) {
            return shares;
        }
        require(d.kind() == TransactionKind.EXPENSE && !payment && !loan,
                "Only an ordinary expense can be split with people");
        Currency from = currencyOf(d.fromAccountId());
        require(from == null || from.isBase(), "An expense can be split only when it is paid from a rupee account");
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
            require(borrowers.existsById(e.getKey()), "Choose the people to split with from the list");
            require(e.getValue().signum() > 0, "Each person's share must be more than zero");
            require(e.getValue().scale() <= 2 || e.getValue().stripTrailingZeros().scale() <= 2, "A share can have at most 2 decimals");
            total = total.add(e.getValue());
        }
        require(total.compareTo(d.amount()) <= 0, "The shares of the other people cannot add up to more than the expense");
        loanService.ensureReceivablesAccount();   // what they owe shows under Receivables
        return shares;
    }

    /**
     * An expense somebody else paid for: only an ordinary expense, paid by a person on the Receivables list, with the user's own share more than
     * nothing and not more than the bill, and not split as well. Once part of what is owed has been paid back, who paid and the amounts are fixed.
     */
    private void checkPaidBy(Transaction t, TransactionData d, boolean payment, boolean loan, Map<Long, BigDecimal> shares) {
        if (t.getId() != null && t.getPaidByBorrowerId() != null && splitSettlements.existsByExpenseTransactionId(t.getId())) {
            boolean same = d.kind() == TransactionKind.EXPENSE && t.getPaidByBorrowerId().equals(d.paidByBorrowerId())
                    && d.amount().compareTo(t.getAmount()) == 0 && d.ownShare() != null && d.ownShare().compareTo(t.getOwnShare()) == 0;
            if (!same) {
                throw new ValidationException("Part of this has been paid back, so who paid and the amounts cannot change. "
                        + "Delete the payment that paid it back first");
            }
        }
        if (d.paidByBorrowerId() == null) {
            require(d.ownShare() == null, "Your share is only for an expense somebody else paid");
            return;
        }
        require(d.kind() == TransactionKind.EXPENSE && !payment && !loan, "Only an ordinary expense can be paid by somebody else");
        require(shares.isEmpty(), "An expense somebody else paid for cannot also be split");
        require(borrowers.existsById(d.paidByBorrowerId()), "Choose who paid from the list of people");
        require(d.fee() == null || d.fee().signum() == 0, "Fees apply to transfers only");
        require(d.ownShare() != null && d.ownShare().signum() > 0, "Enter your share of the bill");
        require(d.ownShare().stripTrailingZeros().scale() <= 2, "Your share can have at most 2 decimals");
        require(d.ownShare().compareTo(d.amount()) <= 0, "Your share cannot be more than the bill");
        loanService.ensureReceivablesAccount();   // what you owe shows under Receivables
    }

    private boolean sameShares(Map<Long, BigDecimal> a, Map<Long, BigDecimal> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        return a.entrySet().stream().allMatch(e -> e.getValue().compareTo(b.get(e.getKey())) == 0);
    }

    /**
     * Checks the amounts against the currencies of the accounts and returns what the to-account received when it is in a different
     * currency than the from-account (null otherwise). {@code amount} and {@code fee} are in the from-account's currency, or the
     * account's for an income; a transfer between two currencies must say what arrived. Loans, and payments towards a loan or card,
     * are in rupees (Receivables, loans and cards are rupee accounts), so a foreign account cannot take part in them.
     */
    private BigDecimal checkCurrencies(TransactionData d) {
        Currency from = currencyOf(d.fromAccountId());
        Currency to = currencyOf(d.toAccountId());
        boolean foreign = (from != null && !from.isBase()) || (to != null && !to.isBase());
        Currency own = d.kind() == TransactionKind.INCOME ? to : from;   // the currency of amount (and of the fee)
        if (own != null) {
            own.requireScale(d.amount(), "The amount");
            own.requireScale(d.fee(), "The fee");
        }
        if (foreign && (systemCategories.isLoanCategory(d.categoryId()) || systemCategories.isLiabilityPayment(d.categoryId()))) {
            throw new ValidationException("Loans, and payments towards a loan or card, are in rupees: use a rupee account");
        }
        if (d.kind() != TransactionKind.TRANSFER) {
            require(d.toAmount() == null, "Only a transfer has an amount received");
            return null;
        }
        if (from == to) {
            require(d.toAmount() == null || d.toAmount().compareTo(d.amount()) == 0,
                    "Both accounts are in " + from + ", so the amount that arrived is the amount that left");
            return null;
        }
        require(d.toAmount() != null && d.toAmount().signum() > 0,
                "Enter the amount that arrived in the " + to + " account");
        to.requireScale(d.toAmount(), "The amount received");
        return d.toAmount();
    }

    private Currency currencyOf(Long accountId) {
        return accountId == null ? null : accounts.findById(accountId).map(Account::getCurrency).orElse(Currency.INR);
    }

    private boolean isJewellery(Long accountId) {
        return accountId != null && accounts.findById(accountId).map(a -> a.getType() == AccountType.JEWELLERY).orElse(false);
    }

    /**
     * The Future Expenses Ledger is used through its own buttons (Fund Ledger, Pay, Transfer), so its buckets always
     * add up to its balance. These transactions therefore cannot be made, edited or deleted here.
     */
    private void requireNotLedger(Long fromAccountId, Long toAccountId) {
        if ((fromAccountId != null && ledgers.existsById(fromAccountId))
                || (toAccountId != null && ledgers.existsById(toAccountId))) {
            throw new ValidationException("The Future Expenses Ledger is used through its own buttons: "
                    + "Fund Ledger, Pay and Transfer");
        }
        if (isJewellery(fromAccountId) || isJewellery(toAccountId)) {
            throw new ValidationException("Jewellery is used through its own buttons: Add item, Sell and Remove");
        }
        if ((fromAccountId != null && recurringDeposits.existsById(fromAccountId))
                || (toAccountId != null && recurringDeposits.existsById(toAccountId))) {
            throw new ValidationException("Recurring Deposits are used through their own buttons: Open, Deposit and Close");
        }
        if ((fromAccountId != null && portfolios.existsById(fromAccountId))
                || (toAccountId != null && portfolios.existsById(toAccountId))) {
            throw new ValidationException("Mutual Funds and Shares are used through their own buttons: "
                    + "Invest, Withdraw and Update value");
        }
    }

    /**
     * A ring-fenced fund takes money in but nothing out. A new outflow, or an edit that moves money out of it or
     * makes an old outflow larger, is refused. Editing a note or a date on an old outflow is still fine.
     */
    private void checkRingFence(Transaction t, TransactionData d) {
        Long from = d.fromAccountId();
        if (from == null || !ringFences.existsById(from)) {
            return;
        }
        boolean existing = t.getId() != null && from.equals(t.getFromAccountId());
        boolean larger = !existing || outflow(d.amount(), d.fee()).compareTo(outflow(t.getAmount(), t.getFee())) > 0;
        if (larger) {
            String name = accounts.findById(from).map(Account::getName).orElse("This fund");
            throw new ValidationException("'" + name + "' is ring-fenced. Release it first to spend from it");
        }
    }

    /** An expense or transfer cannot take more out of an account than it holds (an edit gets its old amount back first). */
    private void checkFunds(Transaction t, TransactionData d) {
        if (d.kind() == TransactionKind.INCOME || d.fromAccountId() == null) {
            return;
        }
        boolean sameSource = t.getId() != null && d.fromAccountId().equals(t.getFromAccountId());
        BigDecimal replaced = sameSource ? outflow(t.getAmount(), t.getFee()) : BigDecimal.ZERO;
        BigDecimal needed = outflow(d.amount(), d.fee());
        if (sameSource && needed.compareTo(replaced) <= 0) {
            return;   // a note or date changed, or the amount lowered: never refused
        }
        balanceGuard.requireFunds(d.fromAccountId(), needed, replaced);
    }

    private BigDecimal outflow(BigDecimal amount, BigDecimal fee) {
        return amount.add(fee == null ? BigDecimal.ZERO : fee);
    }

    /**
     * Loans Given and Loans Collected must say who the loan is with, and that person must exist. No other category
     * takes a person. The first loan creates the Receivables account if there is none, so the money shows up there.
     */
    private boolean checkLoan(TransactionData d) {
        boolean loan = systemCategories.isLoanCategory(d.categoryId());
        if (!loan) {
            if (d.borrowerId() != null) {
                throw new ValidationException("A person can only be chosen for Loans Given or Loans Collected");
            }
            return false;
        }
        if (d.borrowerId() == null || !borrowers.existsById(d.borrowerId())) {
            throw new ValidationException("Choose who the loan is with");
        }
        loanService.ensureReceivablesAccount();
        return true;
    }

    /**
     * A Liability Payments expense is paid from an ordinary account into a loan or credit card, which lowers the amount
     * owed through the usual rule (money into a liability). It takes no other kind of destination.
     */
    private void checkPayment(TransactionData d, boolean payment) {
        if (!payment) {
            return;
        }
        Account to = accounts.findById(d.toAccountId())
                .orElseThrow(() -> new ValidationException("Account " + d.toAccountId() + " not found"));
        if (!to.getType().isLiability()) {
            throw new ValidationException("A payment must go to a loan or a credit card");
        }
        Account from = accounts.findById(d.fromAccountId())
                .orElseThrow(() -> new ValidationException("Account " + d.fromAccountId() + " not found"));
        if (from.getType().isLiability()) {
            throw new ValidationException("Pay from a bank or cash account, not from another loan or card");
        }
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private void checkFee(TransactionData d) {
        if (d.fee() == null) {
            return;
        }
        if (d.fee().signum() < 0) {
            throw new ValidationException("Fees cannot be negative");
        }
        if (d.fee().signum() > 0 && d.kind() != TransactionKind.TRANSFER) {
            throw new ValidationException("Fees apply to transfers only");
        }
    }

    /**
     * An account or category that has been switched off since the transaction was saved may stay on it, so its note or date
     * can still be corrected; only a newly chosen one has to be active.
     */
    private void checkCategory(Transaction t, TransactionData d) {
        if (d.categoryId() == null) {
            return;
        }
        if (d.kind() == TransactionKind.TRANSFER) {
            throw new ValidationException("Transfers have no category");
        }
        Category category = categories.findById(d.categoryId())
                .orElseThrow(() -> new ValidationException("Category not found"));
        CategoryKind expected = d.kind() == TransactionKind.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        if (category.getKind() != expected) {
            throw new ValidationException("Choose a " + expected.name().toLowerCase() + " category");
        }
        boolean unchanged = t.getId() != null && d.categoryId().equals(t.getCategoryId());
        if (systemCategories.isBalanceAdjustment(d.categoryId()) && !unchanged) {
            throw new ValidationException("Balance Adjustment is written by the Reset balance button of an account");
        }
        if (!category.isActive() && !unchanged) {
            throw new ValidationException("Category '" + category.getName() + "' is inactive");
        }
    }

    private void require(boolean ok, String message) {
        if (!ok) {
            throw new ValidationException(message);
        }
    }

    /** A deactivated account allows nothing: no new transaction, and none already on it can be changed or deleted until it is activated. */
    private void checkActive(Transaction t, Long accountId) {
        requireActive(accountId);
    }

    private void requireActive(Long accountId) {
        if (accountId == null) {
            return;
        }
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ValidationException("Account " + accountId + " not found"));
        if (!account.isActive()) {
            throw new ValidationException("Account '" + account.getName() + "' is inactive. Activate it first");
        }
    }
}
