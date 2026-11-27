package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.JewelleryService;
import com.learning.spring.financialdashboard.service.JewelleryService.ItemData;
import com.learning.spring.financialdashboard.service.JewelleryService.SaleRequest;
import com.learning.spring.financialdashboard.service.JewelleryService.View;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Jewellery panel: gold, silver and other items. Every call returns the panel as it is afterwards. */
@RestController
@RequestMapping("/api/jewellery")
public class JewelleryController {

    private final JewelleryService service;

    public JewelleryController(JewelleryService service) {
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

    @PostMapping("/items")
    public View add(@RequestBody ItemData data) {
        return service.add(data);
    }

    @PutMapping("/items/{id}")
    public View update(@PathVariable Long id, @RequestBody ItemData data) {
        return service.update(id, data);
    }

    @PostMapping("/items/{id}/sell")
    public View sell(@PathVariable Long id, @RequestBody SaleRequest request) {
        return service.sell(id, request);
    }

    @DeleteMapping("/items/{id}")
    public View remove(@PathVariable Long id) {
        return service.remove(id);
    }
}
