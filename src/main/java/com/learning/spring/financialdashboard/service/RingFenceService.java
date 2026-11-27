package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.RingFence;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Ring-fencing for goal funds (the Emergency Fund and the user's own goals). It can only be switched on once the
 * fund has reached its target, never part-way. While on, money can still go in but nothing can be spent or
 * transferred out until the fund is released. The Future Expenses Ledger is a sinking fund and is not ring-fenced.
 */
@Service
@Transactional
public class RingFenceService {

    private static final Logger log = LoggerFactory.getLogger(RingFenceService.class);

    private final RingFenceRepository fences;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final EmergencyFundService emergencyFund;
    private final LedgerDetailRepository ledgers;

    public RingFenceService(RingFenceRepository fences, AccountRepository accounts, AccountService accountService,
                            EmergencyFundService emergencyFund, LedgerDetailRepository ledgers) {
        this.fences = fences;
        this.accounts = accounts;
        this.accountService = accountService;
        this.emergencyFund = emergencyFund;
        this.ledgers = ledgers;
    }

    public void fence(Long accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
        if (account.getType() != AccountType.GOAL_FUND) {
            throw new ValidationException("Only a goal fund can be ring-fenced");
        }
        if (ledgers.existsById(accountId)) {
            throw new ValidationException("The Future Expenses Ledger is a sinking fund and cannot be ring-fenced");
        }
        if (fences.existsById(accountId)) {
            throw new ValidationException("'" + account.getName() + "' is already ring-fenced");
        }
        if (!targetReached(account)) {
            throw new ValidationException("'" + account.getName() + "' can be ring-fenced once it reaches its target");
        }
        fences.save(new RingFence(accountId));
        log.info("Ring-fence set: account={}", accountId);
    }

    public void release(Long accountId) {
        if (!fences.existsById(accountId)) {
            throw new ValidationException("That account is not ring-fenced");
        }
        fences.deleteById(accountId);
        log.info("Ring-fence released: account={}", accountId);
    }

    private boolean targetReached(Account account) {
        EmergencyFundService.View fund = emergencyFund.get();
        if (fund.configured() && account.getId().equals(fund.accountId())) {
            return fund.targetReached();
        }
        BigDecimal target = account.getTargetAmount();
        if (target == null || target.signum() <= 0) {
            return false;
        }
        BigDecimal balance = accountService.listSummaries().stream()
                .filter(s -> s.id().equals(account.getId())).map(AccountSummary::balance)
                .findFirst().orElse(account.getOpeningBalance());
        return balance.compareTo(target) >= 0;
    }
}
