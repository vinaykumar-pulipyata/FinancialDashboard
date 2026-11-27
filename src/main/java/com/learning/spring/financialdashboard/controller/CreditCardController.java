package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.CreditCardDetail;
import com.learning.spring.financialdashboard.service.CreditCardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/credit-cards")
public class CreditCardController {

    /** dueDay is the day of the month the bill is due and statementDay the day it is generated; leave either out for none. */
    public record LimitRequest(BigDecimal creditLimit, Integer dueDay, Integer statementDay) {
    }

    private final CreditCardService service;

    public CreditCardController(CreditCardService service) {
        this.service = service;
    }

    @GetMapping
    public List<CreditCardDetail> list() {
        return service.list();
    }

    @PutMapping("/{accountId}")
    public CreditCardDetail updateLimit(@PathVariable Long accountId, @RequestBody LimitRequest request) {
        return service.update(accountId, request.creditLimit(), request.dueDay(), request.statementDay());
    }
}
