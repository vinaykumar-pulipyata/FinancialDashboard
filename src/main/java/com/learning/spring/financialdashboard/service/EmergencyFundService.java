package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.EmergencyFundDetail;
import com.learning.spring.financialdashboard.model.TargetMode;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
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
import java.util.Optional;

/**
 * The Emergency Fund: one goal fund account with a target, a monthly essential expense figure (the burn rate)
 * and an optional planned contribution. Remaining, runway and the estimated date are calculated.
 */
@Service
@Transactional
public class EmergencyFundService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyFundService.class);

    private static final int AVERAGE_WINDOW_MONTHS = 3;

    private final EmergencyFundDetailRepository details;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final RingFenceRepository ringFences;
    private final LedgerDetailRepository ledgers;

    public EmergencyFundService(EmergencyFundDetailRepository details, AccountRepository accounts,
                                AccountService accountService, TransactionRepository transactions,
                                RingFenceRepository ringFences, LedgerDetailRepository ledgers) {
        this.details = details;
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.ringFences = ringFences;
        this.ledgers = ledgers;
    }

    /**
     * What the user enters. accountId picks an existing goal fund to mark as the Emergency Fund; leave it empty to
     * create a new one (with openingBalance). Both are ignored once an Emergency Fund exists.
     */
    public record Settings(Long accountId, BigDecimal openingBalance, TargetMode targetMode, Integer targetMonths,
                           BigDecimal targetAmount, BigDecimal monthlyEssentialExpenses,
                           BigDecimal plannedMonthlyContribution) {
    }

    /** Settings plus everything calculated from them and the transactions. */
    public record View(boolean configured, Long accountId, String name, BigDecimal balance, TargetMode targetMode,
                       Integer targetMonths, BigDecimal monthlyEssentialExpenses,
                       BigDecimal plannedMonthlyContribution, BigDecimal target, BigDecimal remaining,
                       BigDecimal progressPercent, BigDecimal runwayMonths, BigDecimal averageMonthlyContribution,
                       String contributionSource, LocalDate estimatedDate, boolean targetReached,
                       boolean ringFenced) {

        static View notConfigured() {
            return new View(false, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, false, false);
        }
    }

    @Transactional(readOnly = true)
    public View get() {
        Optional<EmergencyFundDetail> current = findCurrent();
        return current.isEmpty() ? View.notConfigured() : view(current.get());
    }

    public View save(Settings s) {
        validate(s);
        Optional<EmergencyFundDetail> current = findCurrent();
        EmergencyFundDetail detail;
        if (current.isPresent()) {
            detail = current.get();
        } else {
            Long accountId = s.accountId() != null ? s.accountId() : createAccount(s.openingBalance()).getId();
            if (s.accountId() != null) {
                Account chosen = accounts.findById(accountId)
                        .orElseThrow(() -> new ValidationException("Account not found"));
                if (ledgers.existsById(accountId)) {
                    throw new ValidationException("The Future Expenses Ledger cannot be the Emergency Fund");
                }
                if (chosen.getType() != AccountType.GOAL_FUND) {
                    throw new ValidationException("Only a goal fund can be the Emergency Fund");
                }
            }
            detail = new EmergencyFundDetail(accountId);
        }
        detail.setTargetMode(s.targetMode());
        detail.setTargetMonths(s.targetMode() == TargetMode.MONTHS ? s.targetMonths() : null);
        detail.setTargetAmount(s.targetMode() == TargetMode.AMOUNT ? s.targetAmount() : null);
        detail.setMonthlyEssentialExpenses(s.monthlyEssentialExpenses());
        detail.setPlannedMonthlyContribution(
                s.plannedMonthlyContribution() == null || s.plannedMonthlyContribution().signum() == 0
                        ? null : s.plannedMonthlyContribution());
        EmergencyFundDetail saved = details.save(detail);
        log.info("Emergency Fund saved: account={} target={}", saved.getAccountId(), saved.getTargetMode());
        return view(saved);
    }

    private Account createAccount(BigDecimal openingBalance) {
        return accountService.create(new AccountData("Emergency Fund", AccountType.GOAL_FUND, null, null,
                openingBalance, null, null));
    }

    /** The saved settings, ignoring (and clearing) one whose account no longer exists. */
    private Optional<EmergencyFundDetail> findCurrent() {
        for (EmergencyFundDetail detail : details.findAll()) {
            if (accounts.existsById(detail.getAccountId())) {
                return Optional.of(detail);
            }
            details.delete(detail);
        }
        return Optional.empty();
    }

    private void validate(Settings s) {
        if (s.targetMode() == null) {
            throw new ValidationException("Choose how to set the target");
        }
        if (s.targetMode() == TargetMode.MONTHS) {
            if (s.targetMonths() == null || s.targetMonths() <= 0) {
                throw new ValidationException("Target months must be greater than zero");
            }
            if (s.monthlyEssentialExpenses() == null || s.monthlyEssentialExpenses().signum() <= 0) {
                throw new ValidationException("Enter your monthly essential expenses to set a target in months");
            }
        } else if (s.targetAmount() == null || s.targetAmount().signum() <= 0) {
            throw new ValidationException("Target amount must be greater than zero");
        }
        if (s.monthlyEssentialExpenses() != null && s.monthlyEssentialExpenses().signum() < 0) {
            throw new ValidationException("Monthly essential expenses cannot be negative");
        }
        if (s.plannedMonthlyContribution() != null && s.plannedMonthlyContribution().signum() < 0) {
            throw new ValidationException("Planned monthly contribution cannot be negative");
        }
    }

    private View view(EmergencyFundDetail d) {
        Account account = accounts.findById(d.getAccountId()).orElseThrow();
        BigDecimal balance = accountService.listSummaries().stream()
                .filter(a -> a.id().equals(account.getId())).map(AccountSummary::balance)
                .findFirst().orElse(account.getOpeningBalance());
        BigDecimal burn = d.getMonthlyEssentialExpenses();
        boolean hasBurn = burn != null && burn.signum() > 0;

        BigDecimal target = d.getTargetMode() == TargetMode.MONTHS
                ? burn.multiply(BigDecimal.valueOf(d.getTargetMonths()))
                : d.getTargetAmount();
        BigDecimal remaining = target.subtract(balance).max(BigDecimal.ZERO);
        boolean reached = remaining.signum() == 0;
        BigDecimal progress = balance.signum() <= 0 ? BigDecimal.ZERO
                : balance.multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP)
                        .min(BigDecimal.valueOf(100));
        BigDecimal runway = hasBurn ? balance.max(BigDecimal.ZERO).divide(burn, 1, RoundingMode.HALF_UP) : null;

        LocalDate today = LocalDate.now();
        BigDecimal recentIn = Optional.ofNullable(
                transactions.sumIncomingSince(account.getId(), today.minusMonths(AVERAGE_WINDOW_MONTHS)))
                .orElse(BigDecimal.ZERO);
        BigDecimal average = recentIn.divide(BigDecimal.valueOf(AVERAGE_WINDOW_MONTHS), 2, RoundingMode.HALF_UP);
        BigDecimal planned = d.getPlannedMonthlyContribution();
        BigDecimal contribution;
        String source;
        if (planned != null && planned.signum() > 0) {
            contribution = planned;
            source = "PLANNED";
        } else if (average.signum() > 0) {
            contribution = average;
            source = "AVERAGE";
        } else {
            contribution = BigDecimal.ZERO;
            source = "NONE";
        }
        LocalDate estimate = null;
        if (!reached && contribution.signum() > 0) {
            int months = remaining.divide(contribution, 0, RoundingMode.CEILING).intValue();
            estimate = today.plusMonths(months);
        }
        return new View(true, account.getId(), account.getName(), balance, d.getTargetMode(), d.getTargetMonths(),
                burn, planned, target, remaining, progress, runway, average, source, estimate, reached,
                ringFences.existsById(account.getId()));
    }
}
