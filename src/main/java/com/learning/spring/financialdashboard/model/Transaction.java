package com.learning.spring.financialdashboard.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

@Entity
@Table(name = "financial_transaction")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionKind kind;

    /** Empty for income. */
    private Long fromAccountId;

    /** Empty for expense. */
    private Long toAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /**
     * Only for a transfer between accounts in different currencies: what arrived, in the to-account's currency ({@code amount} is in the
     * from-account's). Empty when both accounts are in the same currency, where the same amount arrived.
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal toAmount;

    /** Optional charge on a transfer. Taken from the source account on top of the amount. */
    @Column(precision = 19, scale = 2)
    private BigDecimal fee;

    /** A category or sub category. Empty for transfers. */
    private Long categoryId;

    /** Who a Loans Given or Loans Collected transaction is with. Empty for every other transaction. */
    private Long borrowerId;

    /**
     * For an expense somebody else paid for: who paid it. Then fromAccountId is empty (no account of the user's moved), amount is the whole bill
     * and ownShare is what the user owes of it. Empty for every other transaction.
     */
    private Long paidByBorrowerId;

    /** The user's share of an expense somebody else paid; set only with paidByBorrowerId. */
    @Column(precision = 19, scale = 2)
    private BigDecimal ownShare;

    /** For a deposit into, or the payout of, a recurring deposit: which one. Null for everything else. */
    private Long recurringDepositId;

    /** For a dividend: the holding (a share or mutual fund) that paid it. Null for everything else. */
    private Long holdingId;

    @Column(length = 255)
    private String note;

    @Column(length = 1000)
    private String description;

    /** Short lower-case labels, kept in the transaction_tag table. TransactionTagService works them out and cleans them before they get here. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "transaction_tag", joinColumns = @JoinColumn(name = "transaction_id"))
    @Column(name = "tag", length = 30, nullable = false)
    @Fetch(FetchMode.SUBSELECT)
    private Set<String> tags = new HashSet<>();

    /**
     * For an expense shared with other people: what each of them owes of it, by person (borrower id). The expense keeps its full amount (it is
     * what left the account); the user's own share is the amount less these. Empty for every other transaction.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "transaction_split", joinColumns = @JoinColumn(name = "transaction_id"))
    @MapKeyColumn(name = "borrower_id")
    @Column(name = "share", nullable = false, precision = 19, scale = 2)
    @Fetch(FetchMode.SUBSELECT)
    private Map<Long, BigDecimal> splits = new LinkedHashMap<>();

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    /** The tags in alphabetical order. */
    public Set<String> getTags() {
        return new TreeSet<>(tags);
    }

    public void setTags(Collection<String> newTags) {
        tags.clear();
        tags.addAll(newTags);
    }

    public Long getPaidByBorrowerId() {
        return paidByBorrowerId;
    }

    public void setPaidByBorrowerId(Long paidByBorrowerId) {
        this.paidByBorrowerId = paidByBorrowerId;
    }

    public BigDecimal getOwnShare() {
        return ownShare;
    }

    public void setOwnShare(BigDecimal ownShare) {
        this.ownShare = ownShare;
    }

    /** What each other person owes of this expense, by person id (empty when it is not split). */
    public Map<Long, BigDecimal> getSplits() {
        return new LinkedHashMap<>(splits);
    }

    public void setSplits(Map<Long, BigDecimal> shares) {
        splits.clear();
        if (shares != null) {
            splits.putAll(shares);
        }
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public TransactionKind getKind() {
        return kind;
    }

    public void setKind(TransactionKind kind) {
        this.kind = kind;
    }

    public Long getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(Long fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(Long toAccountId) {
        this.toAccountId = toAccountId;
    }

    public BigDecimal getToAmount() {
        return toAmount;
    }

    public void setToAmount(BigDecimal toAmount) {
        this.toAmount = toAmount;
    }

    /** What the to-account received, in its own currency: the amount that arrived, which is {@code amount} unless the currencies differ. */
    public BigDecimal receivedAmount() {
        return toAmount != null ? toAmount : amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getRecurringDepositId() {
        return recurringDepositId;
    }

    public void setRecurringDepositId(Long recurringDepositId) {
        this.recurringDepositId = recurringDepositId;
    }

    public Long getHoldingId() {
        return holdingId;
    }

    public void setHoldingId(Long holdingId) {
        this.holdingId = holdingId;
    }

    public Long getBorrowerId() {
        return borrowerId;
    }

    public void setBorrowerId(Long borrowerId) {
        this.borrowerId = borrowerId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
