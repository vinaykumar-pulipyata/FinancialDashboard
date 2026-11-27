package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A row here means the goal fund is ring-fenced: money can go in, but not out until it is released. */
@Entity
@Table(name = "ring_fence")
public class RingFence {

    @Id
    private Long accountId;

    @Column(nullable = false)
    private Instant fencedAt = Instant.now();

    protected RingFence() {
    }

    public RingFence(Long accountId) {
        this.accountId = accountId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Instant getFencedAt() {
        return fencedAt;
    }
}
