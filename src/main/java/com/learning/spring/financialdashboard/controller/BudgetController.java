package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Budget;
import com.learning.spring.financialdashboard.service.BudgetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Monthly spending limits, one per expense category (main or sub) and one for all spending (no categoryId). */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    /** categoryId is left out for the limit on all spending. */
    public record BudgetRequest(Long categoryId, BigDecimal amount) {
    }

    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    @GetMapping
    public List<Budget> list() {
        return service.list();
    }

    /** Sets (or replaces) the limit for the category in the body. */
    @PutMapping
    public Budget save(@RequestBody BudgetRequest request) {
        return service.save(request.categoryId(), request.amount());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
