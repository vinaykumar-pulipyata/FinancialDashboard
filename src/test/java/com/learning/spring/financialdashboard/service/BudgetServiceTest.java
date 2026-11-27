package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Budget;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.repository.BudgetRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Monthly spending limits: one for all spending, one per main expense category, replaced when set again. */
@SpringBootTest
class BudgetServiceTest {

    @Autowired
    BudgetService service;
    @Autowired
    BudgetRepository budgets;
    @Autowired
    CategoryService categoryService;
    @Autowired
    CategoryRepository categories;
    @Autowired
    TransactionRepository transactions;

    @BeforeEach
    @AfterEach
    void clean() {
        budgets.deleteAll();
        transactions.deleteAll();
        categories.deleteAll();
    }

    private Category category(String name, CategoryKind kind, Long parentId) {
        return categoryService.create(new CategoryData(name, kind, parentId));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    void aLimitOnAllSpendingAndOneOnACategoryAreKeptSeparately() {
        Category food = category("Food", CategoryKind.EXPENSE, null);
        service.save(null, money("50000"));
        service.save(food.getId(), money("12000.50"));

        assertEquals(2, service.list().size());
        Budget overall = budgets.findByCategoryIdIsNull().orElseThrow();
        assertNull(overall.getCategoryId());
        assertEquals(0, money("50000").compareTo(overall.getAmount()));
        assertEquals(0, money("12000.50").compareTo(budgets.findByCategoryId(food.getId()).orElseThrow().getAmount()));
    }

    @Test
    void settingTheSameLimitAgainReplacesItInsteadOfAddingAnother() {
        Category food = category("Food", CategoryKind.EXPENSE, null);
        Budget first = service.save(food.getId(), money("10000"));
        Budget second = service.save(food.getId(), money("15000"));
        service.save(null, money("40000"));
        service.save(null, money("45000"));

        assertEquals(first.getId(), second.getId());
        assertEquals(2, service.list().size());
        assertEquals(0, money("15000").compareTo(budgets.findByCategoryId(food.getId()).orElseThrow().getAmount()));
        assertEquals(0, money("45000").compareTo(budgets.findByCategoryIdIsNull().orElseThrow().getAmount()));
    }

    @Test
    void anAmountMustBePositiveWithAtMostTwoDecimalsAndNotAbsurd() {
        assertThrows(ValidationException.class, () -> service.save(null, null));
        assertThrows(ValidationException.class, () -> service.save(null, BigDecimal.ZERO));
        assertThrows(ValidationException.class, () -> service.save(null, money("-5")));
        assertThrows(ValidationException.class, () -> service.save(null, money("10.123")));
        assertThrows(ValidationException.class, () -> service.save(null, money("1000000000000")));
        service.save(null, money("10.500"));   // trailing zeros are fine
        assertEquals(1, service.list().size());
    }

    @Test
    void onlyAnExpenseCategoryThatIsNotFixedCanHaveOne_mainOrSub() {
        Category food = category("Food", CategoryKind.EXPENSE, null);
        Category groceries = category("Groceries", CategoryKind.EXPENSE, food.getId());
        Category salary = category("Salary", CategoryKind.INCOME, null);

        service.save(food.getId(), money("15000"));
        service.save(groceries.getId(), money("6000"));   // a sub category has its own, next to its parent's
        assertEquals(2, service.list().size());
        assertThrows(ValidationException.class, () -> service.save(salary.getId(), money("100")), "income has nothing to limit");
        assertThrows(ValidationException.class, () -> service.save(999999L, money("100")), "no such category");

        Category fixed = new Category();
        fixed.setName("Loans Given");
        fixed.setKind(CategoryKind.EXPENSE);
        fixed.setSystemKey("LOANS_GIVEN");
        Category savedFixed = categories.save(fixed);
        assertThrows(ValidationException.class, () -> service.save(savedFixed.getId(), money("100")), "a fixed category");
        assertEquals(2, service.list().size(), "only the two valid ones were saved");
    }

    @Test
    void deletingALimitRemovesIt_andAnUnknownOneIsNotFound() {
        Budget saved = service.save(null, money("1000"));
        service.delete(saved.getId());
        assertTrue(service.list().isEmpty());
        assertThrows(NotFoundException.class, () -> service.delete(saved.getId()));
    }

    @Test
    void deletingACategoryTakesItsLimitWithIt_andChangingItsKindDoes() {
        Category food = category("Food", CategoryKind.EXPENSE, null);
        Category travel = category("Travel", CategoryKind.EXPENSE, null);
        service.save(food.getId(), money("1000"));
        service.save(travel.getId(), money("2000"));
        service.save(null, money("9000"));

        categoryService.delete(food.getId());
        assertTrue(budgets.findByCategoryId(food.getId()).isEmpty());

        categoryService.update(travel.getId(), "Travel", CategoryKind.INCOME);
        assertTrue(budgets.findByCategoryId(travel.getId()).isEmpty(), "a limit is for an expense category only");
        assertEquals(1, service.list().size(), "the limit on all spending stays");
    }
}
