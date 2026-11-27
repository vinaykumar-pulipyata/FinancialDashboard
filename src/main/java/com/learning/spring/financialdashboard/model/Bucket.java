package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One bill the Ledger saves for. Its balance is the sum of its entries, never stored. */
@Entity
@Table(name = "ledger_bucket")
public class Bucket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** What is added to the bucket each month when the Ledger is funded. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyAllocation = BigDecimal.ZERO;

    /** The size of one bill. Also the bucket's target: it is "funded" once its balance reaches this. Optional. */
    @Column(precision = 19, scale = 2)
    private BigDecimal paymentAmount;

    /** How many months between bills, for example 1 for monthly, 3 for quarterly, 12 for yearly. Optional. */
    private Integer frequencyMonths;

    private LocalDate nextPaymentDate;

    private LocalDate previousPaymentDate;

    private LocalDate endDate;

    /** The one bucket that receives any extra money when the Ledger is funded. */
    @Column(name = "is_buffer", nullable = false)
    private boolean buffer;

    /** Set when the bucket is closed. A closed bucket is kept as history. */
    private LocalDate closedAt;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getMonthlyAllocation() {
        return monthlyAllocation;
    }

    public void setMonthlyAllocation(BigDecimal monthlyAllocation) {
        this.monthlyAllocation = monthlyAllocation;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(BigDecimal paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public Integer getFrequencyMonths() {
        return frequencyMonths;
    }

    public void setFrequencyMonths(Integer frequencyMonths) {
        this.frequencyMonths = frequencyMonths;
    }

    public LocalDate getNextPaymentDate() {
        return nextPaymentDate;
    }

    public void setNextPaymentDate(LocalDate nextPaymentDate) {
        this.nextPaymentDate = nextPaymentDate;
    }

    public LocalDate getPreviousPaymentDate() {
        return previousPaymentDate;
    }

    public void setPreviousPaymentDate(LocalDate previousPaymentDate) {
        this.previousPaymentDate = previousPaymentDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public boolean isBuffer() {
        return buffer;
    }

    public void setBuffer(boolean buffer) {
        this.buffer = buffer;
    }

    public LocalDate getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDate closedAt) {
        this.closedAt = closedAt;
    }
}
