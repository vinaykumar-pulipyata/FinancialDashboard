package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.repository.BudgetRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository repository;
    private final TransactionRepository transactions;
    private final BudgetRepository budgets;

    public CategoryService(CategoryRepository repository, TransactionRepository transactions, BudgetRepository budgets) {
        this.repository = repository;
        this.transactions = transactions;
        this.budgets = budgets;
    }

    public record CategoryData(String name, CategoryKind kind, Long parentId) {
    }

    public Category create(CategoryData data) {
        String name = cleanName(data.name());
        if (data.kind() == null) {
            throw new ValidationException("Kind is required");
        }
        if (data.parentId() != null) {
            Category parent = repository.findById(data.parentId())
                    .orElseThrow(() -> new ValidationException("Parent category not found"));
            if (parent.getSystemKey() != null) {
                throw new ValidationException("People are added under Receivables, not as sub categories of '"
                        + parent.getName() + "'");
            }
            if (parent.getParentId() != null) {
                throw new ValidationException("Categories can only be two levels deep");
            }
            if (parent.getKind() != data.kind()) {
                throw new ValidationException("A sub category must have the same kind as its category");
            }
        }
        checkUnique(name, data.kind(), data.parentId(), null);

        Category category = new Category();
        category.setName(name);
        category.setKind(data.kind());
        category.setParentId(data.parentId());
        Category saved = repository.save(category);
        log.info("Category created: id={} kind={} sub={}", saved.getId(), saved.getKind(), saved.getParentId() != null);
        return saved;
    }

    /** Renames, and optionally changes kind. A kind change is blocked once the category is in use. */
    public Category update(Long id, String name, CategoryKind kind) {
        Category category = get(id);
        requireNotFixed(category);
        String cleaned = cleanName(name);
        if (kind != null && kind != category.getKind()) {
            if (category.getParentId() != null) {
                throw new ValidationException("A sub category always has the same kind as its category");
            }
            if (repository.existsByParentId(id) || transactions.existsByCategoryId(id)) {
                throw new ValidationException("Cannot change the kind of a category that is in use");
            }
            category.setKind(kind);
            budgets.deleteByCategoryId(id);   // a monthly limit is for an expense category only
        }
        checkUnique(cleaned, category.getKind(), category.getParentId(), category);
        category.setName(cleaned);
        log.info("Category updated: id={} kind={}", id, category.getKind());
        return repository.save(category);
    }

    @Transactional(readOnly = true)
    public Category get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Category> list(CategoryKind kind) {
        return kind == null ? repository.findAllByOrderByNameAsc() : repository.findAllByKindOrderByNameAsc(kind);
    }

    public Category setActive(Long id, boolean active) {
        Category category = get(id);
        requireNotFixed(category);
        category.setActive(active);
        log.info("Category {}: id={}", active ? "activated" : "deactivated", id);
        return repository.save(category);
    }

    public void delete(Long id) {
        Category category = get(id);
        requireNotFixed(category);
        if (repository.existsByParentId(id)) {
            throw new ValidationException("Category has sub categories. Delete or move them first");
        }
        if (transactions.existsByCategoryId(id)) {
            throw new ValidationException("Category has transactions. Deactivate it or reassign them first");
        }
        budgets.deleteByCategoryId(id);   // its monthly limit goes with it
        repository.delete(category);
        log.info("Category deleted: id={}", id);
    }

    private void requireNotFixed(Category category) {
        if (category.getSystemKey() != null) {
            throw new ValidationException("'" + category.getName() + "' is a fixed category and cannot be changed");
        }
    }

    private String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Name is required");
        }
        return name.trim();
    }

    private void checkUnique(String name, CategoryKind kind, Long parentId, Category self) {
        boolean sameAsSelf = self != null && name.equalsIgnoreCase(self.getName())
                && Objects.equals(parentId, self.getParentId()) && kind == self.getKind();
        if (sameAsSelf) {
            return;
        }
        boolean exists = parentId == null
                ? repository.existsByNameIgnoreCaseAndKindAndParentIdIsNull(name, kind)
                : repository.existsByNameIgnoreCaseAndParentId(name, parentId);
        if (exists) {
            throw new ValidationException("A category named '" + name + "' already exists here");
        }
    }
}
