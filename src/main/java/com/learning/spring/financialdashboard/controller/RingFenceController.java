package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.RingFenceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ring-fences")
public class RingFenceController {

    private final RingFenceService service;

    public RingFenceController(RingFenceService service) {
        this.service = service;
    }

    /** Ring-fences a goal fund that has reached its target. */
    @PostMapping("/{accountId}")
    @ResponseStatus(HttpStatus.CREATED)
    public void fence(@PathVariable Long accountId) {
        service.fence(accountId);
    }

    /** Releases a ring-fenced goal fund. */
    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable Long accountId) {
        service.release(accountId);
    }
}
