package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** One mutual fund (or share) inside a portfolio account. Gain or loss is calculated, never stored. */
@Entity
@Table(name = "holding")
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The portfolio account this holding belongs to. */
    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private String name;

    /** What has been put in and not taken out. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal investedAmount = BigDecimal.ZERO;

    /** What it is worth now, typed in by hand. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal currentValue = BigDecimal.ZERO;

    /** How many units or shares are held; optional (null when not given). */
    @Column(precision = 19, scale = 4)
    private BigDecimal quantity;

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getInvestedAmount() {
        return investedAmount;
    }

    public void setInvestedAmount(BigDecimal investedAmount) {
        this.investedAmount = investedAmount;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(BigDecimal currentValue) {
        this.currentValue = currentValue;
    }
}
