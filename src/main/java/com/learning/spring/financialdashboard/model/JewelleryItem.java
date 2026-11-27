package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** One piece of gold, silver or another metal you own: what it is, how much it weighs and what you paid. No current value is kept. */
@Entity
@Table(name = "jewellery_item")
public class JewelleryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The Jewellery account this item belongs to. */
    @Column(nullable = false)
    private Long accountId;

    /** Gold, Silver, or a custom type typed by the user. */
    @Column(nullable = false, length = 40)
    private String metal;

    /** ORNAMENT, COIN, BAR, LOOSE or OTHER (checked by the service). */
    @Column(nullable = false, length = 12)
    private String form;

    @Column(nullable = false, length = 100)
    private String name;

    /** In grams. */
    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal weight;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal boughtPrice;

    /** The transfer that paid for this piece, or null when it was already owned (or has been partly sold). */
    @Column
    private Long purchaseTransactionId;

    public Long getPurchaseTransactionId() { return purchaseTransactionId; }
    public void setPurchaseTransactionId(Long purchaseTransactionId) { this.purchaseTransactionId = purchaseTransactionId; }
    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getMetal() { return metal; }
    public void setMetal(String metal) { this.metal = metal; }
    public String getForm() { return form; }
    public void setForm(String form) { this.form = form; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public BigDecimal getBoughtPrice() { return boughtPrice; }
    public void setBoughtPrice(BigDecimal boughtPrice) { this.boughtPrice = boughtPrice; }
}
