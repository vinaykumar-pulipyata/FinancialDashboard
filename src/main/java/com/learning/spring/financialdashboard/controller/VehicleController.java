package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.VehicleDetail;
import com.learning.spring.financialdashboard.service.VehicleService;
import com.learning.spring.financialdashboard.service.VehicleService.VehicleData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Number plate and year bought of the vehicle accounts. */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService service;

    public VehicleController(VehicleService service) {
        this.service = service;
    }

    @GetMapping
    public List<VehicleDetail> list() {
        return service.list();
    }

    @PutMapping("/{accountId}")
    public VehicleDetail save(@PathVariable Long accountId, @RequestBody VehicleData data) {
        return service.save(accountId, data);
    }
}
