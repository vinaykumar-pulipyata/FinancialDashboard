package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.service.AccountService;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccountSummary> list() {
        return service.listSummaries();
    }

    @GetMapping("/{id}")
    public Account get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account create(@RequestBody AccountData data) {
        return service.create(data);
    }

    @PutMapping("/{id}")
    public Account update(@PathVariable Long id, @RequestBody AccountData data) {
        return service.update(id, data);
    }

    @PostMapping("/{id}/deactivate")
    public Account deactivate(@PathVariable Long id) {
        return service.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    public Account activate(@PathVariable Long id) {
        return service.activate(id);
    }

    /** What happened to the money, as the user's own words: a required note. */
    public record ResetBalanceRequest(String note) {
    }

    /** Brings the balance of an active account to zero with a Balance Adjustment transaction that carries the note. */
    @PostMapping("/{id}/reset-balance")
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction resetBalance(@PathVariable Long id, @RequestBody ResetBalanceRequest request) {
        return service.resetBalance(id, request.note());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
