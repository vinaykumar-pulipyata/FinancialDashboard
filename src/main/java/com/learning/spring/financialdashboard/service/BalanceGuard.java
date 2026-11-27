package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Refuses to take more money out of an account than it holds. Used by every place that spends from an account:
 * expenses and transfers, Invest, buying Jewellery and funding the Ledger. A card or loan (money owed), the
 * Receivables account and accounts whose balance is not worked out from transactions are not checked here.
 */
@Component
public class BalanceGuard {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public BalanceGuard(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /** The account must hold at least needed. */
    public void requireFunds(Long accountId, BigDecimal needed) {
        requireFunds(accountId, needed, BigDecimal.ZERO);
    }

    /**
     * alreadyOut is what an edited transaction already takes out of this account: it is given back before the check,
     * because the edit replaces it.
     */
    @Transactional(readOnly = true)
    public void requireFunds(Long accountId, BigDecimal needed, BigDecimal alreadyOut) {
        if (accountId == null || needed == null) {
            return;
        }
        Account account = accounts.findById(accountId).orElse(null);
        if (account == null || account.getType().isLiability() || account.getType() == AccountType.RECEIVABLE) {
            return;
        }
        BigDecimal in = transactions.sumIncomingTo(accountId);
        BigDecimal out = transactions.sumOutgoingFrom(accountId);
        BigDecimal balance = account.getOpeningBalance()
                .add(in == null ? BigDecimal.ZERO : in)
                .subtract(out == null ? BigDecimal.ZERO : out)
                .add(alreadyOut == null ? BigDecimal.ZERO : alreadyOut);
        if (balance.compareTo(needed) < 0) {
            throw new ValidationException("Insufficient balance in '" + account.getName() + "': it has "
                    + balance.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " and "
                    + needed.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " is needed");
        }
    }
}
