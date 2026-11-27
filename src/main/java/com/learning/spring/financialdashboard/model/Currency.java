package com.learning.spring.financialdashboard.model;

import com.learning.spring.financialdashboard.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The currencies an account can be held in. INR is the base: every total, the net worth and the rates are in rupees.
 * {@code decimals} is how many decimal places an amount in that currency has (VND has none). The front end has the same list
 * in {@code core/constants.js} (CURRENCIES); change both together.
 */
public enum Currency {
    INR("₹", 2),
    USD("$", 2),
    EUR("€", 2),
    GBP("£", 2),
    VND("₫", 0);

    /** Decimal places of an exchange rate (rupees per 1 unit), enough for 1 VND = ₹0.0033. */
    public static final int RATE_SCALE = 8;

    private final String symbol;
    private final int decimals;

    Currency(String symbol, int decimals) {
        this.symbol = symbol;
        this.decimals = decimals;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getDecimals() {
        return decimals;
    }

    public boolean isBase() {
        return this == INR;
    }

    /** The currency for a code typed by a caller; a missing code means rupees, an unknown one is refused. */
    public static Currency parse(String code) {
        if (code == null || code.isBlank()) {
            return INR;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Currency must be one of INR, USD, EUR, GBP or VND");
        }
    }

    /** Refuses an amount with more decimal places than this currency has (VND takes whole numbers only). */
    public void requireScale(BigDecimal amount, String what) {
        if (amount != null && amount.stripTrailingZeros().scale() > decimals) {
            throw new ValidationException(what + " in " + name() + (decimals == 0 ? " must be a whole number" : " can have at most " + decimals + " decimals"));
        }
    }

    /** {@code amount} converted to rupees at {@code rate} (rupees per 1 unit), rounded to paise. */
    public static BigDecimal toRupees(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
