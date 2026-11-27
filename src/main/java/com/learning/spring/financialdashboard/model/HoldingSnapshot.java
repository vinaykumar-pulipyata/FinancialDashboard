package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a holding was worth on a day, recorded whenever it is added, updated or withdrawn from. One per holding per day
 * (a later change on the same day replaces it). These feed the stats graphs, which start when the first one exists.
 */
@Entity
@Table(name = "holding_snapshot")
public class HoldingSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long holdingId;

    /** The portfolio account, so a whole portfolio can be read in one go. */
    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal investedAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal currentValue;

    protected HoldingSnapshot() {
    }

    public HoldingSnapshot(Long holdingId, Long accountId, LocalDate date, BigDecimal investedAmount,
                           BigDecimal currentValue) {
        this.holdingId = holdingId;
        this.accountId = accountId;
        this.date = date;
        this.investedAmount = investedAmount;
        this.currentValue = currentValue;
    }

    public Long getId() {
        return id;
    }

    public Long getHoldingId() {
        return holdingId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public LocalDate getDate() {
        return date;
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
