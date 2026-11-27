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
 * Money a person owed you that you have let go of. It lowers what they owe and the Receivables total, with the day and the reason kept.
 * No account is involved, so it is not a transaction and does not touch income, expenses or net worth.
 */
@Entity
@Table(name = "loan_write_off")
public class LoanWriteOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long borrowerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate writtenOn;

    @Column(nullable = false, length = 255)
    private String note;

    protected LoanWriteOff() {
    }

    public LoanWriteOff(Long borrowerId, BigDecimal amount, LocalDate writtenOn, String note) {
        this.borrowerId = borrowerId;
        this.amount = amount;
        this.writtenOn = writtenOn;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public Long getBorrowerId() {
        return borrowerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getWrittenOn() {
        return writtenOn;
    }

    public String getNote() {
        return note;
    }

    public void setWrittenOn(LocalDate writtenOn) {
        this.writtenOn = writtenOn;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
