package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Borrower;
import com.learning.spring.financialdashboard.model.LoanWriteOff;
import com.learning.spring.financialdashboard.service.LoanService;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerData;
import com.learning.spring.financialdashboard.service.LoanService.Summary;
import com.learning.spring.financialdashboard.service.LoanService.WriteOffRequest;
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

@RestController
@RequestMapping("/api")
public class LoanController {

    private final LoanService service;

    public LoanController(LoanService service) {
        this.service = service;
    }

    /** What each person owes, and the part of the Receivables opening total that has no name yet. */
    @GetMapping("/loans")
    public Summary summary() {
        return service.summary();
    }

    /** Every write-off with its day, amount and reason, for the people's transaction lists. */
    @GetMapping("/loans/write-offs")
    public java.util.List<LoanWriteOff> writeOffs() {
        return service.writeOffList();
    }

    /** The day and reason of a write-off. */
    public record WriteOffEdit(java.time.LocalDate date, String note) {
    }

    @PutMapping("/loans/write-offs/{id}")
    public LoanWriteOff updateWriteOff(@PathVariable Long id, @RequestBody WriteOffEdit edit) {
        return service.updateWriteOff(id, edit.date(), edit.note());
    }

    /** Takes a write-off back: the person owes (or is owed) that amount again. */
    @DeleteMapping("/loans/write-offs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWriteOff(@PathVariable Long id) {
        service.deleteWriteOff(id);
    }

    @PostMapping("/borrowers")
    @ResponseStatus(HttpStatus.CREATED)
    public Borrower create(@RequestBody BorrowerData data) {
        return service.create(data);
    }

    @PutMapping("/borrowers/{id}")
    public Borrower update(@PathVariable Long id, @RequestBody BorrowerData data) {
        return service.update(id, data);
    }

    /** Lets go of what a person still owes you: the whole amount, with a required note saying why. */
    @PostMapping("/borrowers/{id}/write-off")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanWriteOff writeOff(@PathVariable Long id, @RequestBody WriteOffRequest request) {
        return service.writeOff(id, request.note());
    }

    @DeleteMapping("/borrowers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
