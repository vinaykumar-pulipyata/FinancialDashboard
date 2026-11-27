package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Marks an account of type DEBT as a fixed loan: it is paid in a set EMI every month. A loan with no row here is a
 * custom one, paid in any amounts whenever the user likes.
 */
@Entity
@Table(name = "debt_detail")
public class DebtDetail {

    @Id
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal emiAmount;

    protected DebtDetail() {
    }

    public DebtDetail(Long accountId, BigDecimal emiAmount) {
        this.accountId = accountId;
        this.emiAmount = emiAmount;
    }

    public Long getAccountId() {
        return accountId;
    }

    public BigDecimal getEmiAmount() {
        return emiAmount;
    }

    public void setEmiAmount(BigDecimal emiAmount) {
        this.emiAmount = emiAmount;
    }
}
