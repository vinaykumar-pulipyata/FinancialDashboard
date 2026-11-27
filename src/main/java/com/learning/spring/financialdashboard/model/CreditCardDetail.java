package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Extra details for an account of type CREDIT_CARD: the credit limit, the day the bill is due and the day the statement is generated. */
@Entity
@Table(name = "credit_card_detail")
public class CreditCardDetail {

    @Id
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal creditLimit;

    /** The day of the month the card bill is due (1 to 31; a short month uses its last day), or empty. */
    private Integer dueDay;

    /** The day of the month the statement (bill) is generated (1 to 31; a short month uses its last day), or empty. It is not the due day. */
    private Integer statementDay;

    protected CreditCardDetail() {
    }

    public CreditCardDetail(Long accountId, BigDecimal creditLimit) {
        this.accountId = accountId;
        this.creditLimit = creditLimit;
    }

    public CreditCardDetail(Long accountId, BigDecimal creditLimit, Integer dueDay) {
        this(accountId, creditLimit);
        this.dueDay = dueDay;
    }

    public CreditCardDetail(Long accountId, BigDecimal creditLimit, Integer dueDay, Integer statementDay) {
        this(accountId, creditLimit, dueDay);
        this.statementDay = statementDay;
    }

    public Long getAccountId() {
        return accountId;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public Integer getDueDay() {
        return dueDay;
    }

    public void setDueDay(Integer dueDay) {
        this.dueDay = dueDay;
    }

    public Integer getStatementDay() {
        return statementDay;
    }

    public void setStatementDay(Integer statementDay) {
        this.statementDay = statementDay;
    }
}
