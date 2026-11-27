package com.learning.spring.financialdashboard.model;

import java.math.BigDecimal;

/**
 * An account together with its calculated balance. For liabilities the balance is the amount owed. The balance is in the account's
 * own currency; {@code balanceInr} is the same amount in rupees at today's rate (equal to the balance for a rupee account).
 */
public record AccountSummary(Long id, String name, AccountType type, int level, Long parentId,
                             BigDecimal openingBalance, BigDecimal targetAmount, boolean active,
                             String notes, BigDecimal balance, boolean ringFenced, Currency currency, BigDecimal balanceInr) {

    public static AccountSummary of(Account a, BigDecimal balance, boolean ringFenced, BigDecimal balanceInr) {
        return new AccountSummary(a.getId(), a.getName(), a.getType(), a.getLevel(), a.getParentId(),
                a.getOpeningBalance(), a.getTargetAmount(), a.isActive(), a.getNotes(), balance, ringFenced, a.getCurrency(), balanceInr);
    }

    /** For a rupee account, whose balance in rupees is the balance itself. */
    public static AccountSummary of(Account a, BigDecimal balance, boolean ringFenced) {
        return of(a, balance, ringFenced, balance);
    }
}
