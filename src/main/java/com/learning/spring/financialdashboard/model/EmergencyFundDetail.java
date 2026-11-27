package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Settings for the account that is the user's Emergency Fund. A row here is what marks an account as the
 * Emergency Fund; there is at most one. Balance, remaining, runway and the estimate are calculated, not stored.
 */
@Entity
@Table(name = "emergency_fund_detail")
public class EmergencyFundDetail {

    @Id
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetMode targetMode;

    /** Used when targetMode is MONTHS. */
    private Integer targetMonths;

    /** Used when targetMode is AMOUNT. */
    @Column(precision = 19, scale = 2)
    private BigDecimal targetAmount;

    /** The burn rate: what must be paid each month even with no income. */
    @Column(precision = 19, scale = 2)
    private BigDecimal monthlyEssentialExpenses;

    /** Optional. When empty, the estimate uses the average of the last three months. */
    @Column(precision = 19, scale = 2)
    private BigDecimal plannedMonthlyContribution;

    protected EmergencyFundDetail() {
    }

    public EmergencyFundDetail(Long accountId) {
        this.accountId = accountId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public TargetMode getTargetMode() {
        return targetMode;
    }

    public void setTargetMode(TargetMode targetMode) {
        this.targetMode = targetMode;
    }

    public Integer getTargetMonths() {
        return targetMonths;
    }

    public void setTargetMonths(Integer targetMonths) {
        this.targetMonths = targetMonths;
    }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(BigDecimal targetAmount) {
        this.targetAmount = targetAmount;
    }

    public BigDecimal getMonthlyEssentialExpenses() {
        return monthlyEssentialExpenses;
    }

    public void setMonthlyEssentialExpenses(BigDecimal monthlyEssentialExpenses) {
        this.monthlyEssentialExpenses = monthlyEssentialExpenses;
    }

    public BigDecimal getPlannedMonthlyContribution() {
        return plannedMonthlyContribution;
    }

    public void setPlannedMonthlyContribution(BigDecimal plannedMonthlyContribution) {
        this.plannedMonthlyContribution = plannedMonthlyContribution;
    }
}
