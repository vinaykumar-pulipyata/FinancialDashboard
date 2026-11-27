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
import java.time.Instant;
import java.time.LocalDate;

/** One change to a bucket's balance. The amount is signed: positive adds to the bucket, negative takes away. */
@Entity
@Table(name = "ledger_entry")
public class BucketEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bucketId;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryKind kind;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String note;

    /** The real transaction behind a FUND or PAY entry. */
    private Long transactionId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected BucketEntry() {
    }

    public BucketEntry(Long bucketId, LocalDate date, EntryKind kind, BigDecimal amount, String note, Long transactionId) {
        this.bucketId = bucketId;
        this.date = date;
        this.kind = kind;
        this.amount = amount;
        this.note = note;
        this.transactionId = transactionId;
    }

    public Long getId() {
        return id;
    }

    public Long getBucketId() {
        return bucketId;
    }

    public LocalDate getDate() {
        return date;
    }

    public EntryKind getKind() {
        return kind;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getNote() {
        return note;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
