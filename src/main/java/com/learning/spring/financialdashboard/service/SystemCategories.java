package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The fixed categories: Loans Given (an expense) and Loans Collected (an income) behind Receivables, and Liability
 * Payments (an expense) for money paid towards a loan or credit card. They cannot be renamed, deactivated or deleted,
 * and they have no sub categories: the people under the loan ones are the borrowers, and the liability a payment
 * pays is the transaction's destination account.
 */
@Component
public class SystemCategories {

    public static final String LOANS_GIVEN = "LOANS_GIVEN";
    public static final String LOANS_COLLECTED = "LOANS_COLLECTED";
    public static final String LIABILITY_PAYMENT = "LIABILITY_PAYMENT";
    /** What "Reset balance" writes: an expense when money leaves the account's balance, an income when it comes in. */
    public static final String BALANCE_ADJUSTMENT_EXPENSE = "BALANCE_ADJUSTMENT_EXPENSE";
    public static final String BALANCE_ADJUSTMENT_INCOME = "BALANCE_ADJUSTMENT_INCOME";

    private final CategoryRepository categories;

    public SystemCategories(CategoryRepository categories) {
        this.categories = categories;
    }

    /** The category if it exists. Safe to call from a read-only transaction. */
    public Optional<Category> find(String key) {
        return categories.findBySystemKey(key);
    }

    /** The category, created (or taken over from an ordinary category with the same name) if missing. */
    @Transactional
    public Category require(String key) {
        return categories.findBySystemKey(key).orElseGet(() -> create(key));
    }

    @Transactional
    public void ensureAll() {
        require(LOANS_GIVEN);
        require(LOANS_COLLECTED);
        require(LIABILITY_PAYMENT);
        require(BALANCE_ADJUSTMENT_EXPENSE);
        require(BALANCE_ADJUSTMENT_INCOME);
    }

    /** Balance Adjustment (either kind): made only by the Reset balance button of an account. */
    public boolean isBalanceAdjustment(Long categoryId) {
        return hasKey(categoryId, BALANCE_ADJUSTMENT_EXPENSE) || hasKey(categoryId, BALANCE_ADJUSTMENT_INCOME);
    }

    /** Loans Given or Loans Collected: the ones that must say who the loan is with. */
    public boolean isLoanCategory(Long categoryId) {
        return hasKey(categoryId, LOANS_GIVEN) || hasKey(categoryId, LOANS_COLLECTED);
    }

    /** Liability Payments: an expense paid from an account into a loan or credit card. */
    public boolean isLiabilityPayment(Long categoryId) {
        return hasKey(categoryId, LIABILITY_PAYMENT);
    }

    private boolean hasKey(Long categoryId, String key) {
        return categoryId != null && categories.findById(categoryId).map(c -> key.equals(c.getSystemKey())).orElse(false);
    }

    private Category create(String key) {
        CategoryKind kind = LOANS_COLLECTED.equals(key) || BALANCE_ADJUSTMENT_INCOME.equals(key) ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        String name = switch (key) {
            case LOANS_GIVEN -> "Loans Given";
            case LOANS_COLLECTED -> "Loans Collected";
            case BALANCE_ADJUSTMENT_EXPENSE, BALANCE_ADJUSTMENT_INCOME -> "Balance Adjustment";
            default -> "Liability Payments";
        };
        Category category = categories.findAll().stream()
                .filter(c -> c.getParentId() == null && c.getKind() == kind && c.getName().equalsIgnoreCase(name))
                .findFirst().orElseGet(Category::new);
        category.setName(name);
        category.setKind(kind);
        category.setParentId(null);
        category.setActive(true);
        category.setSystemKey(key);
        return categories.save(category);
    }
}
