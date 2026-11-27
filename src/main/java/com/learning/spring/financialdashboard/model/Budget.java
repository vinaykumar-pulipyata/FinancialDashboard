package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A monthly spending limit: for one category (a main category's limit counts its sub categories too, a sub category's only its
 * own spending), or, with no category, for all spending. The same limit applies to every month; how much of it is used is worked out in the browser from the transactions.
 */
@Entity
@Table(name = "budget")
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Empty for the limit on all spending. */
    private Long categoryId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected Budget() {
    }

    public Budget(Long categoryId, BigDecimal amount) {
        this.categoryId = categoryId;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
