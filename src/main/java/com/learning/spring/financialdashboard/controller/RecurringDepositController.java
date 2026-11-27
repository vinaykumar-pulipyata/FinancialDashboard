package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.RecurringDepositService;
import com.learning.spring.financialdashboard.service.RecurringDepositService.CloseRequest;
import com.learning.spring.financialdashboard.service.RecurringDepositService.DepositRequest;
import com.learning.spring.financialdashboard.service.RecurringDepositService.EditData;
import com.learning.spring.financialdashboard.service.RecurringDepositService.OpenData;
import com.learning.spring.financialdashboard.service.RecurringDepositService.View;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Recurring Deposits account: open a deposit, put money into it and close it. Deposits are closed, never deleted. */
@RestController
@RequestMapping("/api/recurring-deposits")
public class RecurringDepositController {

    private final RecurringDepositService service;

    public RecurringDepositController(RecurringDepositService service) {
        this.service = service;
    }

    @GetMapping
    public View get() {
        return service.get();
    }

    @PostMapping("/setup")
    public View setUp() {
        return service.setUp();
    }

    @PostMapping("/deposits")
    public View open(@RequestBody OpenData data) {
        return service.open(data);
    }

    @PutMapping("/deposits/{id}")
    public View update(@PathVariable Long id, @RequestBody EditData data) {
        return service.update(id, data);
    }

    @PostMapping("/deposits/{id}/deposit")
    public View deposit(@PathVariable Long id, @RequestBody DepositRequest request) {
        return service.deposit(id, request);
    }

    @PostMapping("/deposits/{id}/close")
    public View close(@PathVariable Long id, @RequestBody CloseRequest request) {
        return service.close(id, request);
    }

    /** Removes a deposit made by mistake (the transfer), while its recurring deposit is still open. */
    @DeleteMapping("/transfers/{transactionId}")
    public View deleteDeposit(@PathVariable Long transactionId) {
        return service.deleteDeposit(transactionId);
    }
}
