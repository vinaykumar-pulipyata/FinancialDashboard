package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** The extra details of a vehicle account: its number plate and the year it was bought. Both are optional. */
@Entity
@Table(name = "vehicle_detail")
public class VehicleDetail {

    @Id
    private Long accountId;

    @Column(length = 20)
    private String numberPlate;

    private Integer yearBought;

    /** The day the vehicle's value (the account's amount) was last set. Empty for vehicles from before this was kept. */
    private LocalDate valueUpdatedOn;

    protected VehicleDetail() {
    }

    public VehicleDetail(Long accountId, String numberPlate, Integer yearBought) {
        this.accountId = accountId;
        this.numberPlate = numberPlate;
        this.yearBought = yearBought;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getNumberPlate() {
        return numberPlate;
    }

    public void setNumberPlate(String numberPlate) {
        this.numberPlate = numberPlate;
    }

    public LocalDate getValueUpdatedOn() {
        return valueUpdatedOn;
    }

    public void setValueUpdatedOn(LocalDate valueUpdatedOn) {
        this.valueUpdatedOn = valueUpdatedOn;
    }

    public Integer getYearBought() {
        return yearBought;
    }

    public void setYearBought(Integer yearBought) {
        this.yearBought = yearBought;
    }
}
