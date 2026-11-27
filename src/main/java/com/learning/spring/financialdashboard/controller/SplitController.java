package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.service.SplitService;
import com.learning.spring.financialdashboard.service.SplitService.Allocation;
import com.learning.spring.financialdashboard.service.SplitService.BalanceRequest;
import com.learning.spring.financialdashboard.service.SplitService.BalanceResult;
import com.learning.spring.financialdashboard.service.SplitService.RepayRequest;
import com.learning.spring.financialdashboard.service.SplitService.SettleRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SplitController {

    private final SplitService service;

    public SplitController(SplitService service) {
        this.service = service;
    }

    /** Which payments cleared which shared expenses. What each person owes is on the expenses (their splits) and in /api/loans. */
    @GetMapping("/splits")
    public List<Allocation> list() {
        return service.list();
    }

    /** A person paid towards the expenses shared with them; returns the Loans Collected income it saved. */
    @PostMapping("/borrowers/{id}/settle-splits")
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction settle(@PathVariable Long id, @RequestBody SettleRequest request) {
        return service.settle(id, request);
    }

    /** You paid back a person for expenses they paid for you; returns the Loans Given expense it saved. */
    @PostMapping("/borrowers/{id}/repay-splits")
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction repay(@PathVariable Long id, @RequestBody RepayRequest request) {
        return service.repay(id, request);
    }

    /** Settles the whole balance with a person: what you owe each other is set off, then the rest is paid in the direction of the balance. */
    @PostMapping("/borrowers/{id}/settle-balance")
    @ResponseStatus(HttpStatus.CREATED)
    public BalanceResult settleBalance(@PathVariable Long id, @RequestBody BalanceRequest request) {
        return service.settleBalance(id, request);
    }
}
