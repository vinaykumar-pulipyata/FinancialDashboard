package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.service.PortfolioService;
import com.learning.spring.financialdashboard.service.PortfolioService.DividendRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.HoldingData;
import com.learning.spring.financialdashboard.service.PortfolioService.InvestRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.RenameRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.Stats;
import com.learning.spring.financialdashboard.service.PortfolioService.ValueRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.View;
import com.learning.spring.financialdashboard.service.PortfolioService.WithdrawRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Mutual Funds and Shares panels, addressed by kind (MUTUAL_FUNDS or SHARES). Every call returns the panel as it is afterwards. */
@RestController
@RequestMapping("/api/portfolios/{kind}")
public class PortfolioController {

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping
    public View get(@PathVariable PortfolioKind kind) {
        return service.get(kind);
    }

    /** The line graphs: overall and per holding, each starting when its first value was recorded. */
    @GetMapping("/stats")
    public Stats stats(@PathVariable PortfolioKind kind) {
        return service.stats(kind);
    }

    @PostMapping("/setup")
    public View setUp(@PathVariable PortfolioKind kind) {
        return service.setUp(kind);
    }

    @PostMapping("/holdings")
    public View add(@PathVariable PortfolioKind kind, @RequestBody HoldingData data) {
        return service.addHolding(kind, data);
    }

    @PutMapping("/holdings/{id}")
    public View rename(@PathVariable PortfolioKind kind, @PathVariable Long id, @RequestBody RenameRequest request) {
        return service.rename(kind, id, request);
    }

    @PostMapping("/holdings/{id}/value")
    public View updateValue(@PathVariable PortfolioKind kind, @PathVariable Long id, @RequestBody ValueRequest request) {
        return service.updateValue(kind, id, request);
    }

    /** Takes money from a bank or cash account and adds it to Total invested. */
    @PostMapping("/invest")
    public View invest(@PathVariable PortfolioKind kind, @RequestBody InvestRequest request) {
        return service.invest(kind, request);
    }

    /** Deletes an Invest transfer and takes its amount back out of Total invested and the fund. */
    @DeleteMapping("/investments/{transactionId}")
    public View deleteInvestment(@PathVariable PortfolioKind kind, @PathVariable Long transactionId,
                                 @RequestParam(required = false) Long fundId) {
        return service.deleteInvestment(kind, transactionId, fundId);
    }

    @PostMapping("/holdings/{id}/withdraw")
    public View withdraw(@PathVariable PortfolioKind kind, @PathVariable Long id, @RequestBody WithdrawRequest request) {
        return service.withdraw(kind, id, request);
    }

    @PostMapping("/holdings/{id}/dividend")
    public View dividend(@PathVariable PortfolioKind kind, @PathVariable Long id, @RequestBody DividendRequest request) {
        return service.dividend(kind, id, request);
    }

    @DeleteMapping("/holdings/{id}")
    public View delete(@PathVariable PortfolioKind kind, @PathVariable Long id) {
        return service.deleteHolding(kind, id);
    }
}
