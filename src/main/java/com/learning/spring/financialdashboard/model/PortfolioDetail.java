package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row here marks an account as the Mutual Funds (or Shares) account, at most one per kind. The account's balance is
 * not worked out from transactions: it is the sum of the current values of its {@link Holding}s.
 */
@Entity
@Table(name = "portfolio_detail")
public class PortfolioDetail {

    @Id
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private PortfolioKind kind;

    /**
     * What is recorded as invested and not yet taken out at cost: it goes up with each Invest and when a holding is added, and
     * comes down by the cost of what a withdrawal takes out. Empty until then, when the invested amounts of the holdings stand in for it.
     */
    @Column(precision = 19, scale = 2)
    private java.math.BigDecimal totalInvested;

    /** What selling has made so far: each withdrawal adds what it paid minus the cost of what it took out (a loss is negative). Empty before the first sale. */
    @Column(precision = 19, scale = 2)
    private java.math.BigDecimal realizedGain;

    protected PortfolioDetail() {
    }

    public PortfolioDetail(Long accountId, PortfolioKind kind) {
        this.accountId = accountId;
        this.kind = kind;
    }

    public Long getAccountId() {
        return accountId;
    }

    public PortfolioKind getKind() {
        return kind;
    }

    public java.math.BigDecimal getTotalInvested() {
        return totalInvested;
    }

    public void setTotalInvested(java.math.BigDecimal totalInvested) {
        this.totalInvested = totalInvested;
    }

    public java.math.BigDecimal getRealizedGain() {
        return realizedGain;
    }

    public void setRealizedGain(java.math.BigDecimal realizedGain) {
        this.realizedGain = realizedGain;
    }
}
