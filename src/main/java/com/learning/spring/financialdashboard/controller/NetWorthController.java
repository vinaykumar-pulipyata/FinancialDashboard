package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.NetWorthService;
import com.learning.spring.financialdashboard.service.NetWorthService.Point;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/net-worth")
public class NetWorthController {

    private final NetWorthService service;

    public NetWorthController(NetWorthService service) {
        this.service = service;
    }

    /** Month-end net worth for the last N months (default 12), oldest first. */
    @GetMapping
    public List<Point> history(@RequestParam(defaultValue = "12") int months) {
        return service.history(months);
    }
}
