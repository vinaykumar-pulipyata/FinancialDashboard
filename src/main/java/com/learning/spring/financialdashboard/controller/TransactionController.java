package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.service.TransactionService;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
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
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @GetMapping
    public List<Transaction> list() {
        return service.list();
    }

    /** The tags a transaction with this note, description and category would get; nothing is saved. */
    public record TagRequest(Long categoryId, String note, String description) {
    }

    @PostMapping("/suggest-tags")
    public List<String> suggestTags(@RequestBody TagRequest request) {
        return service.suggestTags(request.note(), request.description(), request.categoryId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction create(@RequestBody TransactionData data) {
        return service.create(data);
    }

    @PutMapping("/{id}")
    public Transaction update(@PathVariable Long id, @RequestBody TransactionData data) {
        return service.update(id, data);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
