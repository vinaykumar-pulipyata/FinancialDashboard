package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.DebtDetail;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.DebtDetailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * How a loan (an account of type DEBT) is paid. A fixed loan has a monthly EMI; a custom loan has none and is paid in
 * any amounts. The payments themselves are Liability Payments expenses made through the transaction service.
 */
@Service
@Transactional
public class DebtService {

    private static final Logger log = LoggerFactory.getLogger(DebtService.class);

    private final DebtDetailRepository details;
    private final AccountRepository accounts;

    public DebtService(DebtDetailRepository details, AccountRepository accounts) {
        this.details = details;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<DebtDetail> list() {
        return details.findAll();
    }

    /** Makes a loan fixed with this monthly EMI (greater than zero), or custom when the EMI is left out. */
    public void update(Long accountId, BigDecimal emiAmount) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
        if (account.getType() != AccountType.DEBT) {
            throw new ValidationException("Account '" + account.getName() + "' is not a loan");
        }
        if (emiAmount == null) {
            details.findById(accountId).ifPresent(details::delete);
            log.info("Loan set to custom: account={}", accountId);
            return;
        }
        if (emiAmount.signum() <= 0) {
            throw new ValidationException("The monthly EMI must be greater than zero");
        }
        DebtDetail detail = details.findById(accountId).orElseGet(() -> new DebtDetail(accountId, emiAmount));
        detail.setEmiAmount(emiAmount);
        details.save(detail);
        log.info("Loan set to fixed: account={}", accountId);
    }
}
