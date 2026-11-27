package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The category rules: kinds, two levels, names, fixed categories and changing a kind. */
@SpringBootTest
class CategoryServiceValidationTest {

    @Autowired
    CategoryService service;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    AccountService accountService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    CategoryRepository categories;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    AccountRepository accounts;

    @BeforeEach
    @AfterEach
    void clean() {
        transactions.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
    }

    private Category top(String name, CategoryKind kind) {
        return service.create(new CategoryData(name, kind, null));
    }

    @Test
    void aKindIsRequiredAndNamesAreTrimmedAndNotBlank() {
        assertThrows(ValidationException.class, () -> service.create(new CategoryData("X", null, null)));
        assertThrows(ValidationException.class, () -> top("   ", CategoryKind.INCOME));
        assertThrows(ValidationException.class, () -> top(null, CategoryKind.INCOME));
        assertEquals("Food", top("  Food  ", CategoryKind.EXPENSE).getName());
    }

    @Test
    void subCategoriesNeedARealParentOfTheSameKindAndOnlyTwoLevelsExist() {
        Category food = top("Food", CategoryKind.EXPENSE);
        Category snacks = service.create(new CategoryData("Snacks", CategoryKind.EXPENSE, food.getId()));

        assertThrows(ValidationException.class, () -> service.create(new CategoryData("X", CategoryKind.EXPENSE, 987654L)));
        assertThrows(ValidationException.class, () -> service.create(new CategoryData("X", CategoryKind.INCOME, food.getId())));
        assertThrows(ValidationException.class, () -> service.create(new CategoryData("X", CategoryKind.EXPENSE, snacks.getId())));
    }

    @Test
    void fixedLoanCategoriesCannotHaveSubCategoriesOrBeChanged() {
        systemCategories.ensureAll();
        Category given = systemCategories.require(SystemCategories.LOANS_GIVEN);
        assertThrows(ValidationException.class, () -> service.create(new CategoryData("Ravi", CategoryKind.EXPENSE, given.getId())));
        assertThrows(ValidationException.class, () -> service.update(given.getId(), "Renamed", null));
        assertThrows(ValidationException.class, () -> service.setActive(given.getId(), false));
        assertThrows(ValidationException.class, () -> service.delete(given.getId()));
    }

    @Test
    void namesAreUniqueWithinAKindOrParentButARenameToItselfIsFine() {
        Category food = top("Food", CategoryKind.EXPENSE);
        top("Salary", CategoryKind.INCOME);
        assertThrows(ValidationException.class, () -> top("food", CategoryKind.EXPENSE));
        top("Food", CategoryKind.INCOME);   // another kind may share a name
        service.create(new CategoryData("Snacks", CategoryKind.EXPENSE, food.getId()));
        assertThrows(ValidationException.class, () -> service.create(new CategoryData("SNACKS", CategoryKind.EXPENSE, food.getId())));
        assertEquals("FOOD", service.update(food.getId(), "FOOD", null).getName());
    }

    @Test
    void aKindChangeIsAllowedOnlyForAnUnusedTopLevelCategory() {
        Category free = top("Free", CategoryKind.EXPENSE);
        Category parent = top("Parent", CategoryKind.EXPENSE);
        Category child = service.create(new CategoryData("Child", CategoryKind.EXPENSE, parent.getId()));
        Category used = top("Used", CategoryKind.EXPENSE);
        Account bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, new BigDecimal("100"), null, null));
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, bank.getId(), null, BigDecimal.TEN,
                null, used.getId(), null, null, null));

        assertThrows(ValidationException.class, () -> service.update(child.getId(), "Child", CategoryKind.INCOME));
        assertThrows(ValidationException.class, () -> service.update(parent.getId(), "Parent", CategoryKind.INCOME));
        assertThrows(ValidationException.class, () -> service.update(used.getId(), "Used", CategoryKind.INCOME));
        assertEquals(CategoryKind.INCOME, service.update(free.getId(), "Free", CategoryKind.INCOME).getKind());
        assertEquals(CategoryKind.EXPENSE, service.update(free.getId(), "Free", CategoryKind.EXPENSE).getKind());
    }

    @Test
    void aMissingCategoryIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.get(987654L));
        assertThrows(NotFoundException.class, () -> service.update(987654L, "X", null));
    }
}
