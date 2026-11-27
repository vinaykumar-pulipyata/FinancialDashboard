package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.service.SetupService;
import com.learning.spring.financialdashboard.service.SetupService.SetupAccount;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService service;

    public SetupController(SetupService service) {
        this.service = service;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Account> createAll(@RequestBody List<SetupAccount> accounts) {
        return service.createAll(accounts);
    }
}
