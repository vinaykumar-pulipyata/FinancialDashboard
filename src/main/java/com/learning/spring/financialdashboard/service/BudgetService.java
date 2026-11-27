package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Budget;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.repository.BudgetRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Monthly spending limits: one on all spending and one per expense category, main or sub. A main category's limit counts its sub
 * categories too; a sub category's counts only its own spending, so both can be set. Only the limits are kept here; what has been
 * used this month is worked out in the browser from the transactions, the same way Stats counts spending.
 */
@Service
@Transactional
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    /** Far more than any monthly limit; keeps the amount inside NUMERIC(19, 2). */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999");

    private final BudgetRepository budgets;
    private final CategoryRepository categories;

    public BudgetService(BudgetRepository budgets, CategoryRepository categories) {
        this.budgets = budgets;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<Budget> list() {
        return budgets.findAllByOrderByIdAsc();
    }

    /** Sets the limit for a category (or for all spending when categoryId is empty), replacing the one it had. */
    public Budget save(Long categoryId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("A budget must be more than zero");
        }
        if (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("A budget can have at most two decimals");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException("That budget is too large");
        }
        if (categoryId != null) {
            Category category = categories.findById(categoryId)
                    .orElseThrow(() -> new ValidationException("Category not found"));
            if (category.getKind() != CategoryKind.EXPENSE) {
                throw new ValidationException("A budget is for an expense category");
            }
            if (category.getSystemKey() != null) {
                throw new ValidationException("A fixed category cannot have a budget");
            }
        }
        Budget budget = (categoryId == null ? budgets.findByCategoryIdIsNull() : budgets.findByCategoryId(categoryId))
                .orElseGet(() -> new Budget(categoryId, amount));
        budget.setAmount(amount.setScale(2, RoundingMode.UNNECESSARY));
        Budget saved = budgets.save(budget);
        log.info("Budget saved: id={} category={}", saved.getId(), categoryId);
        return saved;
    }

    public void delete(Long id) {
        Budget budget = budgets.findById(id).orElseThrow(() -> new NotFoundException("Budget " + id + " not found"));
        budgets.delete(budget);
        log.info("Budget deleted: id={}", id);
    }
}
