package com.learning.spring.financialdashboard.service;

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
import com.learning.spring.financialdashboard.startup.CategorySeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class CategoryServiceTest {

    @Autowired
    CategoryService service;

    @Autowired
    CategoryRepository repository;

    @Autowired
    CategorySeeder seeder;

    @Autowired
    TransactionService transactionService;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accountRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        repository.deleteAll();
        accountRepository.deleteAll();
    }

    private Category top(String name, CategoryKind kind) {
        return service.create(new CategoryData(name, kind, null));
    }

    @Test
    void createsCategoryAndSubCategory() {
        Category food = top("Food", CategoryKind.EXPENSE);
        Category sub = service.create(new CategoryData("Groceries", CategoryKind.EXPENSE, food.getId()));

        assertEquals(food.getId(), sub.getParentId());
        assertTrue(sub.isActive());
    }

    @Test
    void onlyTwoLevelsAndSubMustMatchParentKind() {
        Category food = top("Food", CategoryKind.EXPENSE);
        Category sub = service.create(new CategoryData("Groceries", CategoryKind.EXPENSE, food.getId()));

        assertThrows(ValidationException.class,
                () -> service.create(new CategoryData("Organic", CategoryKind.EXPENSE, sub.getId())));
        assertThrows(ValidationException.class,
                () -> service.create(new CategoryData("Bonus", CategoryKind.INCOME, food.getId())));
        assertThrows(ValidationException.class,
                () -> service.create(new CategoryData("X", CategoryKind.EXPENSE, 9999L)));
    }

    @Test
    void rejectsBlankNamesAndDuplicatesWithinTheSameParent() {
        Category food = top("Food", CategoryKind.EXPENSE);
        service.create(new CategoryData("Groceries", CategoryKind.EXPENSE, food.getId()));

        assertThrows(ValidationException.class, () -> top(" ", CategoryKind.EXPENSE));
        assertThrows(ValidationException.class, () -> top("food", CategoryKind.EXPENSE));
        assertThrows(ValidationException.class,
                () -> service.create(new CategoryData("groceries", CategoryKind.EXPENSE, food.getId())));
        // The same name is fine in the other kind, or under another parent.
        top("Food", CategoryKind.INCOME);
    }

    @Test
    void renameIsAllowedAndKindChangeBlockedWhenInUse() {
        Category gifts = top("Gifts", CategoryKind.INCOME);
        assertEquals("Presents", service.update(gifts.getId(), "Presents", null).getName());
        assertEquals(CategoryKind.EXPENSE, service.update(gifts.getId(), "Presents", CategoryKind.EXPENSE).getKind());

        Account bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, new java.math.BigDecimal("1000"), null, null));
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, bank.getId(), null,
                BigDecimal.TEN, null, gifts.getId(), null, null, null));

        assertThrows(ValidationException.class,
                () -> service.update(gifts.getId(), "Presents", CategoryKind.INCOME));
    }

    @Test
    void deleteIsBlockedWhenThereAreSubCategoriesOrTransactions() {
        Category food = top("Food", CategoryKind.EXPENSE);
        Category sub = service.create(new CategoryData("Groceries", CategoryKind.EXPENSE, food.getId()));
        assertThrows(ValidationException.class, () -> service.delete(food.getId()));

        Account bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, new java.math.BigDecimal("1000"), null, null));
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, bank.getId(), null,
                BigDecimal.TEN, null, sub.getId(), null, null, null));
        assertThrows(ValidationException.class, () -> service.delete(sub.getId()));

        assertFalse(service.setActive(sub.getId(), false).isActive());
    }

    @Test
    void unusedCategoryCanBeDeleted() {
        Category tmp = top("Temp", CategoryKind.INCOME);
        service.delete(tmp.getId());
        assertEquals(0, repository.count());
    }

    @Test
    void seederLoadsStarterCategoriesOnlyWhenEmpty() {
        seeder.seedIfEmpty();
        long seeded = repository.count();
        assertEquals(12, seeded, "a short list: 4 income and 8 expense categories");
        assertEquals(4, service.list(CategoryKind.INCOME).size());
        assertEquals(8, service.list(CategoryKind.EXPENSE).size());
        assertTrue(repository.findAll().stream().allMatch(c -> c.getParentId() == null), "no sub categories to start with");
        assertEquals(2, repository.findAll().stream()
                .filter(c -> c.getName().equals("Salary") || c.getName().equals("Housing")).count());

        service.delete(service.list(CategoryKind.INCOME).stream()
                .filter(c -> c.getName().equals("Other Income")).findFirst().orElseThrow().getId());
        seeder.seedIfEmpty();
        assertEquals(seeded - 1, repository.count());
    }
}
