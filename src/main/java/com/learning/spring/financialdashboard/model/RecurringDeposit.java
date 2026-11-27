package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One recurring deposit: its term, the instalment and rate (both optional), what was already in it before the app knew it, and
 * whether it is open or closed. It is closed, never deleted, so the history stays. What was deposited since is read from the
 * transfers that carry its id.
 */
@Entity
@Table(name = "recurring_deposit")
public class RecurringDeposit {

    public enum Status {
        OPEN, CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private int termMonths;

    @Column(precision = 19, scale = 2)
    private BigDecimal monthlyAmount;

    /** Per year, in percent. */
    @Column(precision = 6, scale = 3)
    private BigDecimal interestRate;

    /** What was in it already when it was added here: part of the account's opening balance. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal alreadyDeposited = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    private LocalDate closedOn;

    /** What came back when it was closed: the deposits and the interest. */
    @Column(precision = 19, scale = 2)
    private BigDecimal receivedAmount;

    protected RecurringDeposit() {
    }

    public RecurringDeposit(Long accountId, String name, LocalDate startDate, int termMonths) {
        this.accountId = accountId;
        this.name = name;
        this.startDate = startDate;
        this.termMonths = termMonths;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public void setTermMonths(int termMonths) {
        this.termMonths = termMonths;
    }

    public BigDecimal getMonthlyAmount() {
        return monthlyAmount;
    }

    public void setMonthlyAmount(BigDecimal monthlyAmount) {
        this.monthlyAmount = monthlyAmount;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public BigDecimal getAlreadyDeposited() {
        return alreadyDeposited;
    }

    public void setAlreadyDeposited(BigDecimal alreadyDeposited) {
        this.alreadyDeposited = alreadyDeposited;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDate getClosedOn() {
        return closedOn;
    }

    public void setClosedOn(LocalDate closedOn) {
        this.closedOn = closedOn;
    }

    public BigDecimal getReceivedAmount() {
        return receivedAmount;
    }

    public void setReceivedAmount(BigDecimal receivedAmount) {
        this.receivedAmount = receivedAmount;
    }
}
