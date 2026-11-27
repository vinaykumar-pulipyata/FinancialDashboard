package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row here marks an account as the Future Expenses Ledger (at most one). The Ledger holds real money in one
 * account; its buckets are slices of that money tracked in {@link Bucket} and {@link BucketEntry}.
 */
@Entity
@Table(name = "ledger_detail")
public class LedgerDetail {

    @Id
    private Long accountId;

    protected LedgerDetail() {
    }

    public LedgerDetail(Long accountId) {
        this.accountId = accountId;
    }

    public Long getAccountId() {
        return accountId;
    }
}
