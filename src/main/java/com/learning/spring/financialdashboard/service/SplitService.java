package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.SplitSettlement;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.SplitSettlementRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settling the expenses a person shares with you. What they owe is on the expense itself (its splits) and shows under Receivables; a payment
 * from them is an ordinary Loans Collected income (so balances and Receivables follow without a special case) plus one {@link SplitSettlement}
 * row per expense it went towards, so it is always clear which expenses a payment cleared.
 */
@Service
@Transactional
public class SplitService {

    private static final Logger log = LoggerFactory.getLogger(SplitService.class);

    private final SplitSettlementRepository settlements;
    private final TransactionRepository transactions;
    private final BorrowerRepository borrowers;
    private final TransactionService transactionService;
    private final SystemCategories systemCategories;
    private final LoanService loanService;

    public SplitService(SplitSettlementRepository settlements, TransactionRepository transactions, BorrowerRepository borrowers,
                        TransactionService transactionService, SystemCategories systemCategories, LoanService loanService) {
        this.loanService = loanService;
        this.settlements = settlements;
        this.transactions = transactions;
        this.borrowers = borrowers;
        this.transactionService = transactionService;
        this.systemCategories = systemCategories;
    }

    /**
     * A payment from a person. expenseIds (optional) chooses which of their open shared expenses it clears; without it the oldest are cleared
     * first. note is optional. The amount cannot be more than what is open: other money from them is a Loans Collected transaction.
     */
    public record SettleRequest(LocalDate date, Long toAccountId, BigDecimal amount, List<Long> expenseIds, String note) {
    }

    /**
     * You paying back a person for expenses they paid for you: the day, the account the money leaves, the amount, optionally which of those expenses
     * it clears (the oldest first without it) and a note. It cannot be more than what is open.
     */
    public record RepayRequest(LocalDate date, Long fromAccountId, BigDecimal amount, List<Long> expenseIds, String note) {
    }

    /**
     * Settling a person's whole balance in one go: the day, the account the money moves through, the amount and a note. What each owes the other is
     * set off first (no money moves for that part); the amount is what is left to pay, in the direction of the balance (they pay you when they owe
     * more, you pay them when you owe more). The amount may be left out when the offset alone settles everything.
     */
    public record BalanceRequest(LocalDate date, Long accountId, BigDecimal amount, String note) {
    }

    /** What settling a balance did: the payment it saved (null when only an offset was needed) and how much was set off. */
    public record BalanceResult(Transaction payment, BigDecimal offset) {
    }

    /** One expense a payment went towards: the payment, the expense and how much of it. */
    public record Allocation(Long id, Long borrowerId, Long expenseTransactionId, Long incomeTransactionId, BigDecimal amount) {
    }

    @Transactional(readOnly = true)
    public List<Allocation> list() {
        return settlements.findAll().stream()
                .map(s -> new Allocation(s.getId(), s.getBorrowerId(), s.getExpenseTransactionId(), s.getIncomeTransactionId(), s.getAmount()))
                .toList();
    }

    public Transaction settle(Long borrowerId, SettleRequest request) {
        Borrower person = borrowers.findById(borrowerId)
                .orElseThrow(() -> new NotFoundException("Person " + borrowerId + " not found"));
        if (request == null || request.amount() == null || request.amount().signum() <= 0) {
            throw new ValidationException("Enter the amount " + person.getName() + " paid");
        }
        if (request.date() == null || request.date().isAfter(LocalDate.now())) {
            throw new ValidationException("The payment needs a day that is not in the future");
        }
        if (request.toAccountId() == null) {
            throw new ValidationException("Choose the account the money went into");
        }
        List<Open> open = openShares(borrowerId, request.expenseIds());
        BigDecimal available = open.stream().map(Open::left).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (request.amount().compareTo(available) > 0) {
            throw new ValidationException(person.getName() + " has " + available.stripTrailingZeros().toPlainString()
                    + " of shared expenses open. For any other money from them, record Loans Collected");
        }
        Transaction income = collect(borrowerId, request.date(), request.toAccountId(), request.amount(), request.note(), open);
        log.info("Shared expenses settled: person={} payment={} date={}", borrowerId, income.getId(), request.date());
        return income;
    }

    /** A payment from a person: a Loans Collected income, with the settlement rows for the part that clears their open shares (oldest first). */
    private Transaction collect(Long borrowerId, LocalDate date, Long accountId, BigDecimal amount, String note, List<Open> open) {
        Long collected = systemCategories.find(SystemCategories.LOANS_COLLECTED)
                .orElseThrow(() -> new ValidationException("The Loans Collected category is missing")).getId();
        Transaction income = transactionService.create(new TransactionData(date, TransactionKind.INCOME, null, accountId, amount, null, collected,
                note == null || note.isBlank() ? "Settled shared expenses" : note.trim(), null, borrowerId, null));
        BigDecimal remaining = amount;
        for (Open o : open) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal part = remaining.min(o.left());
            settlements.save(new SplitSettlement(borrowerId, o.expense().getId(), income.getId(), part));
            remaining = remaining.subtract(part);
        }
        return income;
    }

    /**
     * Pays back a person for expenses they paid for you. The payment is an ordinary Loans Given expense from your account (not spending, like any
     * loan), and the split_settlement rows it saves say which of their expenses it cleared; Loans Given that clear such expenses are not counted as
     * lending (see LoanService.summary), so it lowers what you owe them and does not raise what they owe you.
     */
    public Transaction repay(Long borrowerId, RepayRequest request) {
        Borrower person = borrowers.findById(borrowerId)
                .orElseThrow(() -> new NotFoundException("Person " + borrowerId + " not found"));
        if (request == null || request.amount() == null || request.amount().signum() <= 0) {
            throw new ValidationException("Enter the amount you paid " + person.getName());
        }
        if (request.date() == null || request.date().isAfter(LocalDate.now())) {
            throw new ValidationException("The payment needs a day that is not in the future");
        }
        if (request.fromAccountId() == null) {
            throw new ValidationException("Choose the account the money came from");
        }
        List<Open> open = openPaidForYou(borrowerId, request.expenseIds());
        BigDecimal owed = open.stream().map(Open::left).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (request.amount().compareTo(owed) > 0) {
            throw new ValidationException("You owe " + person.getName() + " " + owed.stripTrailingZeros().toPlainString()
                    + " for expenses they paid. For any other money to them, record Loans Given");
        }
        Long given = systemCategories.find(SystemCategories.LOANS_GIVEN)
                .orElseThrow(() -> new ValidationException("The Loans Given category is missing")).getId();
        Transaction payment = transactionService.create(new TransactionData(request.date(), TransactionKind.EXPENSE, request.fromAccountId(), null,
                request.amount(), null, given,
                request.note() == null || request.note().isBlank() ? "Paid back shared expenses" : request.note().trim(),
                null, borrowerId, null));
        BigDecimal remaining = request.amount();
        for (Open o : open) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal part = remaining.min(o.left());
            settlements.save(new SplitSettlement(borrowerId, o.expense().getId(), payment.getId(), part));
            remaining = remaining.subtract(part);
        }
        log.info("Paid back for shared expenses: person={} payment={} date={}", borrowerId, payment.getId(), request.date());
        return payment;
    }

    /**
     * Settles the balance with a person: first what you owe each other is set off against each other, oldest first (two settlement rows with no
     * payment, which lower both sides without moving money); then the amount, up to what is left on the side that owes, is paid through
     * settle (they pay you) or repay (you pay them), clearing the oldest items first.
     */
    public BalanceResult settleBalance(Long borrowerId, BalanceRequest request) {
        Borrower person = borrowers.findById(borrowerId)
                .orElseThrow(() -> new NotFoundException("Person " + borrowerId + " not found"));
        if (request == null) {
            throw new ValidationException("Enter the amount");
        }
        BigDecimal amount = request.amount() == null ? BigDecimal.ZERO : request.amount();
        if (amount.signum() < 0) {
            throw new ValidationException("The amount cannot be negative");
        }
        // What they owe you is their open shares plus the plain loans still open (their whole balance less those shares); what you owe them is the open bills.
        BigDecimal owedTotal = loanService.summary().borrowers().stream().filter(v -> v.id().equals(borrowerId))
                .map(LoanService.BorrowerView::owed).findFirst().orElse(BigDecimal.ZERO);
        List<Open> shares = openShares(borrowerId, null);
        List<Open> bills = openPaidForYou(borrowerId, null);
        BigDecimal sharesOpen = total(shares);
        BigDecimal billsOpen = total(bills);
        BigDecimal loansOpen = owedTotal.subtract(sharesOpen).max(BigDecimal.ZERO);
        BigDecimal againstShares = sharesOpen.min(billsOpen);
        BigDecimal againstLoans = billsOpen.subtract(againstShares).min(loansOpen);
        BigDecimal toOffset = againstShares.add(againstLoans);
        BigDecimal theyStillOwe = sharesOpen.subtract(againstShares).add(loansOpen.subtract(againstLoans));
        BigDecimal youStillOwe = billsOpen.subtract(toOffset);
        BigDecimal rest = theyStillOwe.max(youStillOwe);
        if (amount.signum() > 0 && amount.compareTo(rest) > 0) {
            throw new ValidationException("After setting off what you owe each other, " + (theyStillOwe.signum() > 0
                    ? person.getName() + " owes you " : "you owe " + person.getName() + " ") + rest.stripTrailingZeros().toPlainString()
                    + ". Enter at most that");
        }
        if (amount.signum() == 0 && toOffset.signum() == 0) {
            throw new ValidationException("Enter the amount");
        }
        if (amount.signum() > 0 && (request.date() == null || request.date().isAfter(LocalDate.now()))) {
            throw new ValidationException("The payment needs a day that is not in the future");
        }
        if (toOffset.signum() > 0) {
            offset(borrowerId, shares, bills, loansOpen);
        }
        Transaction payment = null;
        if (amount.signum() > 0) {
            if (request.accountId() == null) {
                throw new ValidationException("Choose the account the money moves through");
            }
            payment = theyStillOwe.signum() > 0
                    ? collect(borrowerId, request.date(), request.accountId(), amount, request.note(), openShares(borrowerId, null))
                    : repay(borrowerId, new RepayRequest(request.date(), request.accountId(), amount, null, request.note()));
        }
        log.info("Balance settled: person={} offset={} paid={}", borrowerId, toOffset.signum() > 0, payment != null);
        return new BalanceResult(payment, toOffset);
    }

    /**
     * Cancels what you owe each other, oldest items first. A share and a bill cancel with two settlement rows (income_transaction_id 0: no payment);
     * what is left of the bills then cancels against the plain loans with one row on the bill alone (the loan has no row to hold it: the person's
     * balance falls by the same amount through LoanService.summary).
     */
    private void offset(Long borrowerId, List<Open> shares, List<Open> bills, BigDecimal loansOpen) {
        int s = 0;
        BigDecimal sLeft = shares.isEmpty() ? BigDecimal.ZERO : shares.get(0).left();
        BigDecimal loansLeft = loansOpen;
        for (Open bill : bills) {
            BigDecimal bLeft = bill.left();
            while (bLeft.signum() > 0 && s < shares.size()) {
                BigDecimal part = sLeft.min(bLeft);
                settlements.save(new SplitSettlement(borrowerId, shares.get(s).expense().getId(), 0L, part));
                settlements.save(new SplitSettlement(borrowerId, bill.expense().getId(), 0L, part));
                sLeft = sLeft.subtract(part);
                bLeft = bLeft.subtract(part);
                if (sLeft.signum() == 0 && ++s < shares.size()) {
                    sLeft = shares.get(s).left();
                }
            }
            if (bLeft.signum() > 0 && loansLeft.signum() > 0) {
                BigDecimal part = loansLeft.min(bLeft);
                settlements.save(new SplitSettlement(borrowerId, bill.expense().getId(), 0L, part));
                loansLeft = loansLeft.subtract(part);
            }
        }
    }

    private BigDecimal total(List<Open> rows) {
        return rows.stream().map(Open::left).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The expenses a person paid for you that you still owe on, oldest first; only the chosen ones when expenseIds is given. */
    private List<Open> openPaidForYou(Long borrowerId, List<Long> expenseIds) {
        List<Open> result = new ArrayList<>();
        for (Transaction t : transactions.findAllById(settlements.paidForYouExpenseIds(borrowerId))) {
            if (expenseIds != null && !expenseIds.isEmpty() && !expenseIds.contains(t.getId())) {
                continue;
            }
            BigDecimal cleared = settlements.findAllByExpenseTransactionId(t.getId()).stream()
                    .map(SplitSettlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal left = t.getOwnShare().subtract(cleared);
            if (left.signum() > 0) {
                result.add(new Open(t, left));
            }
        }
        result.sort(Comparator.comparing((Open o) -> o.expense().getDate()).thenComparing(o -> o.expense().getId()));
        return result;
    }

    /** The shared expenses a person still owes on, oldest first; only the chosen ones when expenseIds is given. */
    private List<Open> openShares(Long borrowerId, List<Long> expenseIds) {
        Map<Long, BigDecimal> settled = new HashMap<>();
        for (SplitSettlement s : settlements.findAllByBorrowerId(borrowerId)) {
            settled.merge(s.getExpenseTransactionId(), s.getAmount(), BigDecimal::add);
        }
        List<Open> result = new ArrayList<>();
        for (Transaction t : transactions.findAllById(settlements.splitExpenseIds(borrowerId))) {
            if (expenseIds != null && !expenseIds.isEmpty() && !expenseIds.contains(t.getId())) {
                continue;
            }
            BigDecimal left = t.getSplits().get(borrowerId).subtract(settled.getOrDefault(t.getId(), BigDecimal.ZERO));
            if (left.signum() > 0) {
                result.add(new Open(t, left));
            }
        }
        result.sort(Comparator.comparing((Open o) -> o.expense().getDate()).thenComparing(o -> o.expense().getId()));
        return result;
    }

    private record Open(Transaction expense, BigDecimal left) {
    }
}
