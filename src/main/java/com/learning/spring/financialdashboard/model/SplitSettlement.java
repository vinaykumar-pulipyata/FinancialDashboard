package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Part of a payment from a person that cleared (some of) their share of one split expense. The payment itself is an ordinary Loans Collected
 * income; this row says which expense it went towards, so a settlement is never a lump of money with no explanation.
 */
@Entity
@Table(name = "split_settlement")
public class SplitSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long borrowerId;

    @Column(nullable = false)
    private Long expenseTransactionId;

    @Column(nullable = false)
    private Long incomeTransactionId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected SplitSettlement() {
    }

    public SplitSettlement(Long borrowerId, Long expenseTransactionId, Long incomeTransactionId, BigDecimal amount) {
        this.borrowerId = borrowerId;
        this.expenseTransactionId = expenseTransactionId;
        this.incomeTransactionId = incomeTransactionId;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Long getBorrowerId() {
        return borrowerId;
    }

    public Long getExpenseTransactionId() {
        return expenseTransactionId;
    }

    public Long getIncomeTransactionId() {
        return incomeTransactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
