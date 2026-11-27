package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Marks an INVESTMENT account as the Recurring Deposits account (at most one): it is used only through the card's own buttons. */
@Entity
@Table(name = "recurring_deposit_detail")
public class RecurringDepositDetail {

    @Id
    private Long accountId;

    protected RecurringDepositDetail() {
    }

    public RecurringDepositDetail(Long accountId) {
        this.accountId = accountId;
    }

    public Long getAccountId() {
        return accountId;
    }
}
