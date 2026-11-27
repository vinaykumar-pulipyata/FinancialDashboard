package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Reminder;
import com.learning.spring.financialdashboard.service.ReminderService;
import com.learning.spring.financialdashboard.service.ReminderService.ReminderData;
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
@RequestMapping("/api/reminders")
public class ReminderController {

    /** The body of PUT /{id}/done. */
    public record DoneRequest(boolean done, java.time.LocalDate doneOn) {
    }

    private final ReminderService service;

    public ReminderController(ReminderService service) {
        this.service = service;
    }

    @GetMapping
    public List<Reminder> list() {
        return service.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Reminder create(@RequestBody ReminderData data) {
        return service.create(data);
    }

    @PutMapping("/{id}")
    public Reminder update(@PathVariable Long id, @RequestBody ReminderData data) {
        return service.update(id, data);
    }

    @PutMapping("/{id}/done")
    public Reminder done(@PathVariable Long id, @RequestBody DoneRequest request) {
        return service.setDone(id, request.done(), request.doneOn());
    }

    /** Ticks or unticks one occurrence of a repeating reminder; the body is the same as for /{id}/done. */
    @PutMapping("/{id}/occurrences/{date}/done")
    public Reminder occurrenceDone(@PathVariable Long id, @PathVariable java.time.LocalDate date, @RequestBody DoneRequest request) {
        return service.setOccurrenceDone(id, date, request.done(), request.doneOn());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
