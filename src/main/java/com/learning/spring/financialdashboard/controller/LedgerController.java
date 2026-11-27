package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.LedgerService;
import com.learning.spring.financialdashboard.service.LedgerService.BucketData;
import com.learning.spring.financialdashboard.service.LedgerService.CloseRequest;
import com.learning.spring.financialdashboard.service.LedgerService.FundRequest;
import com.learning.spring.financialdashboard.service.LedgerService.InterestRequest;
import com.learning.spring.financialdashboard.service.LedgerService.PayRequest;
import com.learning.spring.financialdashboard.service.LedgerService.TransferRequest;
import com.learning.spring.financialdashboard.service.LedgerService.View;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Future Expenses Ledger. Every call returns the Ledger as it is afterwards. */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService service;

    public LedgerController(LedgerService service) {
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

    @PostMapping("/buckets")
    public View addBucket(@RequestBody BucketData data) {
        return service.addBucket(data);
    }

    @PutMapping("/buckets/{id}")
    public View updateBucket(@PathVariable Long id, @RequestBody BucketData data) {
        return service.updateBucket(id, data);
    }

    @PostMapping("/buckets/{id}/close")
    public View close(@PathVariable Long id, @RequestBody(required = false) CloseRequest request) {
        return service.close(id, request);
    }

    @PostMapping("/fund")
    public View fund(@RequestBody FundRequest request) {
        return service.fund(request);
    }

    /** Deletes a funding transaction and what it added to the buckets. */
    @DeleteMapping("/fundings/{transactionId}")
    public View deleteFunding(@PathVariable Long transactionId) {
        return service.deleteFunding(transactionId);
    }

    @PostMapping("/pay")
    public View pay(@RequestBody PayRequest request) {
        return service.pay(request);
    }

    @PostMapping("/interest")
    public View creditInterest(@RequestBody InterestRequest request) {
        return service.creditInterest(request);
    }

    @PostMapping("/transfer")
    public View transfer(@RequestBody TransferRequest request) {
        return service.transfer(request);
    }
}
