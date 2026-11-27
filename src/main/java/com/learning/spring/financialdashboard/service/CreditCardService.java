package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.CreditCardDetail;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CreditCardService {

    private static final Logger log = LoggerFactory.getLogger(CreditCardService.class);

    private final CreditCardDetailRepository details;
    private final AccountRepository accounts;

    public CreditCardService(CreditCardDetailRepository details, AccountRepository accounts) {
        this.details = details;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<CreditCardDetail> list() {
        return details.findAll();
    }

    /** Sets or changes a card's credit limit. The limit must be greater than zero. The due day and statement day are left as they were. */
    public CreditCardDetail updateLimit(Long accountId, BigDecimal limit) {
        Optional<CreditCardDetail> was = details.findById(accountId);
        return update(accountId, limit, was.map(CreditCardDetail::getDueDay).orElse(null), was.map(CreditCardDetail::getStatementDay).orElse(null));
    }

    /** Sets a card's credit limit and its bill due day; the statement day is left as it was. */
    public CreditCardDetail update(Long accountId, BigDecimal limit, Integer dueDay) {
        return update(accountId, limit, dueDay, details.findById(accountId).map(CreditCardDetail::getStatementDay).orElse(null));
    }

    /**
     * Sets a card's credit limit (greater than zero), the day of the month its bill is due and the day of the month its statement
     * is generated (each 1 to 31, or none). They are two different days: the statement closes the cycle and the bill is due some
     * days after it.
     */
    public CreditCardDetail update(Long accountId, BigDecimal limit, Integer dueDay, Integer statementDay) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
        if (account.getType() != AccountType.CREDIT_CARD) {
            throw new ValidationException("Account '" + account.getName() + "' is not a credit card");
        }
        if (limit == null || limit.signum() <= 0) {
            throw new ValidationException("Credit limit must be greater than zero");
        }
        checkDueDay(dueDay);
        checkStatementDay(statementDay);
        CreditCardDetail detail = details.findById(accountId)
                .orElseGet(() -> new CreditCardDetail(accountId, limit));
        detail.setCreditLimit(limit);
        detail.setDueDay(dueDay);
        detail.setStatementDay(statementDay);
        log.info("Credit card set: account={} dueDay={} statementDay={}", accountId, dueDay, statementDay);
        return details.save(detail);
    }

    /** A statement day is a day of the month: 1 to 31, or none. */
    public static void checkStatementDay(Integer statementDay) {
        if (statementDay != null && (statementDay < 1 || statementDay > 31)) {
            throw new ValidationException("The statement day must be a day of the month, from 1 to 31");
        }
    }

    /** A due day is a day of the month: 1 to 31, or none. */
    public static void checkDueDay(Integer dueDay) {
        if (dueDay != null && (dueDay < 1 || dueDay > 31)) {
            throw new ValidationException("The due day must be a day of the month, from 1 to 31");
        }
    }
}
