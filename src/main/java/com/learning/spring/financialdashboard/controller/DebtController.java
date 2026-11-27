package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.DebtDetail;
import com.learning.spring.financialdashboard.service.DebtService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** The fixed loans, with their monthly EMI. A loan that is not listed is a custom one. */
@RestController
@RequestMapping("/api/debts")
public class DebtController {

    /** emiAmount is the monthly EMI of a fixed loan; leave it out for a custom loan. */
    public record PlanRequest(BigDecimal emiAmount) {
    }

    private final DebtService service;

    public DebtController(DebtService service) {
        this.service = service;
    }

    @GetMapping
    public List<DebtDetail> list() {
        return service.list();
    }

    @PutMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable Long accountId, @RequestBody PlanRequest request) {
        service.update(accountId, request.emiAmount());
    }
}
