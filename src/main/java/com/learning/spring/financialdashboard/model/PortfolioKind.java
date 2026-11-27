package com.learning.spring.financialdashboard.model;

/** The kinds of investment panel under Level 3. Each one is a single account holding a list of holdings. */
public enum PortfolioKind {
    MUTUAL_FUNDS("Mutual Funds"),
    SHARES("Shares");

    private final String label;

    PortfolioKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
