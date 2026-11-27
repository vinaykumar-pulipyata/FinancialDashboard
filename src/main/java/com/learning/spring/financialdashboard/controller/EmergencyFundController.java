package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.EmergencyFundService;
import com.learning.spring.financialdashboard.service.EmergencyFundService.Settings;
import com.learning.spring.financialdashboard.service.EmergencyFundService.View;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/emergency-fund")
public class EmergencyFundController {

    private final EmergencyFundService service;

    public EmergencyFundController(EmergencyFundService service) {
        this.service = service;
    }

    /** The Emergency Fund with its calculated figures, or {"configured": false} before it is set up. */
    @GetMapping
    public View get() {
        return service.get();
    }

    /** Sets up the Emergency Fund the first time, and changes its settings afterwards. */
    @PutMapping
    public View save(@RequestBody Settings settings) {
        return service.save(settings);
    }
}
