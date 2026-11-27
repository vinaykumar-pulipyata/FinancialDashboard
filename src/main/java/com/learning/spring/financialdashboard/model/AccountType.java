package com.learning.spring.financialdashboard.model;

public enum AccountType {
    CASH(1, false),
    BANK(1, false),
    WALLET(1, false),
    GOAL_FUND(2, false),
    INVESTMENT(3, false),
    GOLD(3, false),            // older Gold accounts keep working; new gold and silver go in the Jewellery account
    JEWELLERY(3, false),
    PROPERTY(4, false),
    VEHICLE(4, false),
    RECEIVABLE(5, false),
    DEBT(6, true),
    CREDIT_CARD(6, true),
    OTHER(7, false);

    private final int defaultLevel;
    private final boolean liability;

    AccountType(int defaultLevel, boolean liability) {
        this.defaultLevel = defaultLevel;
        this.liability = liability;
    }

    public int getDefaultLevel() {
        return defaultLevel;
    }

    public boolean isLiability() {
        return liability;
    }
}
