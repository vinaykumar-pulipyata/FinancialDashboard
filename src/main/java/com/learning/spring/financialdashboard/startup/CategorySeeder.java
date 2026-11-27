package com.learning.spring.financialdashboard.startup;

import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Loads the starter categories on first run. Does nothing once any ordinary category exists. */
@Component
@Order(1)   // before SystemCategoryInitializer
public class CategorySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CategorySeeder.class);

    // A short, flat list on purpose: more, and sub categories, are added in Menu > Edit categories.
    private static final List<String> INCOME = List.of("Salary", "Business", "Investment Income", "Other Income");
    private static final List<String> EXPENSE = List.of("Housing", "Food", "Transport", "Health", "Bills", "Shopping",
            "Entertainment", "Other Expense");

    private final CategoryRepository repository;

    public CategorySeeder(CategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    public void seedIfEmpty() {
        // The fixed loan categories do not count: they are created at every start.
        if (repository.findAll().stream().anyMatch(c -> c.getSystemKey() == null)) {
            return;
        }
        seed(CategoryKind.INCOME, INCOME);
        seed(CategoryKind.EXPENSE, EXPENSE);
        log.info("First run: the starter categories were loaded");
    }

    private void seed(CategoryKind kind, List<String> names) {
        names.forEach(name -> save(name, kind));
    }

    private void save(String name, CategoryKind kind) {
        Category category = new Category();
        category.setName(name);
        category.setKind(kind);
        repository.save(category);
    }
}
