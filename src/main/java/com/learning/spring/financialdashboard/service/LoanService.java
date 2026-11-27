package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.LoanWriteOff;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.LoanWriteOffRepository;
import com.learning.spring.financialdashboard.repository.SplitSettlementRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The people behind Receivables. One list of borrowers serves both Loans Given and Loans Collected. What each person
 * owes is their opening amount + loans given - loans collected. The part of the Receivables opening total that is
 * not assigned to anyone stays as "earlier loans (no name)".
 */
@Service
@Transactional
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final BorrowerRepository borrowers;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final SystemCategories systemCategories;
    private final TransactionRepository transactions;
    private final LoanWriteOffRepository writeOffs;
    private final SplitSettlementRepository splits;

    public LoanService(BorrowerRepository borrowers, AccountRepository accounts, AccountService accountService,
                       SystemCategories systemCategories, TransactionRepository transactions, LoanWriteOffRepository writeOffs,
                       SplitSettlementRepository splits) {
        this.splits = splits;
        this.borrowers = borrowers;
        this.accounts = accounts;
        this.accountService = accountService;
        this.systemCategories = systemCategories;
        this.transactions = transactions;
        this.writeOffs = writeOffs;
    }

    public record BorrowerData(String name, BigDecimal openingAmount) {
    }

    /**
     * owed is negative when someone has paid back more than they owed (the user owes them); writtenOff is what you let go of; shared is their
     * share of expenses split with them. owed = opening + given + shared - collected - writtenOff. youOwe is kept apart: your share of expenses they
     * paid for you, less what you have paid back (a payment back is a Loans Given that clears those expenses, so it is not counted in given).
     */
    public record BorrowerView(Long id, String name, BigDecimal openingAmount, BigDecimal given, BigDecimal collected,
                               BigDecimal writtenOff, BigDecimal shared, BigDecimal youOwe, BigDecimal owed) {
    }

    public record WriteOffRequest(String note) {
    }

    public record Summary(Long receivablesAccountId, BigDecimal unnamed, List<BorrowerView> borrowers) {
    }

    public Borrower create(BorrowerData data) {
        String name = cleanName(data.name());
        BigDecimal opening = amount(data.openingAmount());
        checkUnique(name, null);
        Account receivables = ensureReceivablesAccount();
        checkOpening(opening, receivables, null);
        Borrower borrower = new Borrower();
        borrower.setName(name);
        borrower.setOpeningAmount(opening);
        Borrower saved = borrowers.save(borrower);
        log.info("Person added: id={}", saved.getId());
        return saved;
    }

    public Borrower update(Long id, BorrowerData data) {
        Borrower borrower = get(id);
        String name = cleanName(data.name());
        BigDecimal opening = amount(data.openingAmount());
        checkUnique(name, id);
        checkOpening(opening, ensureReceivablesAccount(), id);
        borrower.setName(name);
        borrower.setOpeningAmount(opening);
        log.info("Person updated: id={}", id);
        return borrowers.save(borrower);
    }

    public void delete(Long id) {
        Borrower borrower = get(id);
        if (transactions.existsByBorrowerId(id) || writeOffs.existsByBorrowerId(id) || splits.countSharesOf(id) > 0
                || splits.countPaidBy(id) > 0) {
            throw new ValidationException(borrower.getName() + " has loan transactions, so they cannot be removed");
        }
        borrowers.delete(borrower);   // their opening amount goes back into "earlier loans (no name)"
        log.info("Person removed: id={}", id);
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Optional<Account> receivables = findReceivablesAccount();
        List<Borrower> all = borrowers.findAllByOrderByNameAsc();
        Map<Long, BigDecimal> given = sums(SystemCategories.LOANS_GIVEN);
        Map<Long, BigDecimal> collected = sums(SystemCategories.LOANS_COLLECTED);
        Map<Long, BigDecimal> shared = new HashMap<>();
        for (Object[] row : splits.sumSharesByBorrower()) {
            shared.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
        }
        Map<Long, BigDecimal> paidForYou = new HashMap<>();
        for (Object[] row : splits.sumPaidForYouByBorrower()) {
            paidForYou.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
        }
        Map<Long, BigDecimal> repaid = new HashMap<>();
        for (Object[] row : splits.sumRepaidByBorrower()) {
            repaid.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
        }
        Map<Long, BigDecimal> offset = new HashMap<>();
        for (Object[] row : splits.sumOffsetByBorrower()) {
            offset.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
        }
        Map<Long, BigDecimal> letGo = new HashMap<>();
        for (Object[] row : writeOffs.sumByBorrower()) {
            letGo.put((Long) row[0], (BigDecimal) row[1]);
        }

        BigDecimal assigned = all.stream().map(Borrower::getOpeningAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unnamed = receivables.map(a -> a.getOpeningBalance().subtract(assigned).max(BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
        List<BorrowerView> views = all.stream().map(b -> {
            BigDecimal paidBack = repaid.getOrDefault(b.getId(), BigDecimal.ZERO);
            BigDecimal g = given.getOrDefault(b.getId(), BigDecimal.ZERO).subtract(paidBack);   // paying back what you owe is not lending
            BigDecimal c = collected.getOrDefault(b.getId(), BigDecimal.ZERO);
            BigDecimal w = letGo.getOrDefault(b.getId(), BigDecimal.ZERO);
            BigDecimal setOff = offset.getOrDefault(b.getId(), BigDecimal.ZERO);   // shares and bills cancelled against each other
            BigDecimal s = shared.getOrDefault(b.getId(), BigDecimal.ZERO).subtract(setOff);
            BigDecimal owe = paidForYou.getOrDefault(b.getId(), BigDecimal.ZERO).subtract(paidBack).subtract(setOff);
            return new BorrowerView(b.getId(), b.getName(), b.getOpeningAmount(), g, c, w, s, owe,
                    b.getOpeningAmount().add(g).add(s).subtract(c).subtract(w));
        }).sorted((x, y) -> y.owed().compareTo(x.owed())).toList();
        return new Summary(receivables.map(Account::getId).orElse(null), unnamed, views);
    }

    /**
     * Lets go of everything between you and a person: saves a write-off for the whole balance, dated today, with the reason (required, at most
     * 255 characters). The amount is negative when you owed them (they paid back more than they owed); the person's history stays.
     */
    public LoanWriteOff writeOff(Long id, String note) {
        Borrower borrower = get(id);
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty()) {
            throw new ValidationException("Say why you are letting it go");
        }
        if (reason.length() > 255) {
            throw new ValidationException("The note can have at most 255 characters");
        }
        BigDecimal owed = summary().borrowers().stream().filter(b -> b.id().equals(id)).map(BorrowerView::owed).findFirst().orElse(BigDecimal.ZERO);
        if (owed.signum() == 0) {
            throw new ValidationException(borrower.getName() + " has nothing to write off");
        }
        LoanWriteOff saved = writeOffs.save(new LoanWriteOff(id, owed, LocalDate.now(), reason));
        log.info("Loan written off: person={} date={}", id, saved.getWrittenOn());
        return saved;
    }

    /** A write-off as it was made on an earlier day, from an exported file (Import from Excel): any non-zero amount, never in the future. */
    public LoanWriteOff restoreWriteOff(Long borrowerId, BigDecimal amount, LocalDate date, String note) {
        get(borrowerId);
        if (amount == null || amount.signum() == 0) {
            throw new ValidationException("A write-off has to be for an amount");
        }
        if (date == null || date.isAfter(LocalDate.now())) {
            throw new ValidationException("The write-off needs a day that is not in the future");
        }
        if (note == null || note.isBlank() || note.length() > 255) {
            throw new ValidationException("A write-off needs a reason of at most 255 characters");
        }
        LoanWriteOff saved = writeOffs.save(new LoanWriteOff(borrowerId, amount, date, note.trim()));
        log.info("Loan write-off restored: person={} date={}", borrowerId, saved.getWrittenOn());
        return saved;
    }

    /** The day and the reason of a write-off, corrected. The amount is not changed: it was the whole balance when it was made (delete it and write off again). */
    public LoanWriteOff updateWriteOff(Long id, LocalDate date, String note) {
        LoanWriteOff writeOff = writeOffs.findById(id).orElseThrow(() -> new NotFoundException("Write-off " + id + " not found"));
        if (date == null || date.isAfter(LocalDate.now())) {
            throw new ValidationException("The write-off needs a day that is not in the future");
        }
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty()) {
            throw new ValidationException("Say why you let it go");
        }
        if (reason.length() > 255) {
            throw new ValidationException("The note can have at most 255 characters");
        }
        writeOff.setWrittenOn(date);
        writeOff.setNote(reason);
        log.info("Loan write-off updated: id={} date={}", id, date);
        return writeOffs.save(writeOff);
    }

    /** Takes a write-off back: what the person owed (or you owed them) is owed again, and the Receivables total follows. */
    public void deleteWriteOff(Long id) {
        LoanWriteOff writeOff = writeOffs.findById(id).orElseThrow(() -> new NotFoundException("Write-off " + id + " not found"));
        writeOffs.delete(writeOff);
        log.info("Loan write-off removed: id={}", id);
    }

    /** Every write-off, oldest first: when you let go of what a person owed (or what you owed them), how much and why. */
    @Transactional(readOnly = true)
    public List<LoanWriteOff> writeOffList() {
        return writeOffs.findAll().stream()
                .sorted(java.util.Comparator.comparing(LoanWriteOff::getWrittenOn).thenComparing(LoanWriteOff::getId)).toList();
    }

    /** The single Receivables account, created when the first person or loan is added without one. */
    public Account ensureReceivablesAccount() {
        if (findReceivablesAccount().isEmpty() && accounts.existsByType(AccountType.RECEIVABLE)) {
            throw new ValidationException("The Receivables account is switched off. Switch it on to record loans");
        }
        return findReceivablesAccount().orElseGet(() -> accountService.create(
                new AccountData("Receivables", AccountType.RECEIVABLE, null, null, BigDecimal.ZERO, null, null)));
    }

    private Optional<Account> findReceivablesAccount() {
        return accounts.findAllByOrderByLevelAscNameAsc().stream()
                .filter(a -> a.getType() == AccountType.RECEIVABLE && a.isActive()).findFirst();
    }

    private Borrower get(Long id) {
        return borrowers.findById(id).orElseThrow(() -> new NotFoundException("Person " + id + " not found"));
    }

    private Map<Long, BigDecimal> sums(String key) {
        Map<Long, BigDecimal> result = new HashMap<>();
        systemCategories.find(key).ifPresent(category -> {
            for (Object[] row : transactions.sumByBorrower(category.getId())) {
                result.put((Long) row[0], (BigDecimal) row[1]);
            }
        });
        return result;
    }

    private String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Enter the person's name");
        }
        return name.trim();
    }

    private BigDecimal amount(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new ValidationException("The amount cannot be negative");
        }
        return value;
    }

    private void checkUnique(String name, Long exceptId) {
        boolean exists = exceptId == null ? borrowers.existsByNameIgnoreCase(name)
                : borrowers.existsByNameIgnoreCaseAndIdNot(name, exceptId);
        if (exists) {
            throw new ValidationException("'" + name + "' is already in the list");
        }
    }

    /** A person's opening amount comes out of the earlier-loans total, so it cannot be more than what is left. */
    private void checkOpening(BigDecimal opening, Account receivables, Long exceptId) {
        BigDecimal assignedToOthers = borrowers.findAll().stream()
                .filter(b -> !b.getId().equals(exceptId)).map(Borrower::getOpeningAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal left = receivables.getOpeningBalance().subtract(assignedToOthers).max(BigDecimal.ZERO);
        if (opening.compareTo(left) > 0) {
            throw new ValidationException("Only " + left.stripTrailingZeros().toPlainString()
                    + " of your earlier loans is left to assign to people");
        }
    }
}
