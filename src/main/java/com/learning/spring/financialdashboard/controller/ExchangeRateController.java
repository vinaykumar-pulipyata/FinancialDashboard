package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.ExchangeRate;
import com.learning.spring.financialdashboard.service.ExchangeRateService;
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
import java.time.LocalDate;
import java.util.List;

/** Rupees per 1 unit of a foreign currency, by day. */
@RestController
@RequestMapping("/api/exchange-rates")
public class ExchangeRateController {

    public record RateRequest(String currency, LocalDate date, BigDecimal rate) {
    }

    private final ExchangeRateService service;

    public ExchangeRateController(ExchangeRateService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExchangeRate> list() {
        return service.list();
    }

    /** Sets (or replaces) the rate of a currency on a day. */
    @PutMapping
    public ExchangeRate save(@RequestBody RateRequest request) {
        return service.save(Currency.parse(request.currency()), request.date(), request.rate());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
