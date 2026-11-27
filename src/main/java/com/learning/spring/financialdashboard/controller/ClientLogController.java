package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.ClientLogService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lets the page put its own errors and steps in the server's log (see ClientLogService for what is allowed). */
@RestController
@RequestMapping("/api/client-log")
public class ClientLogController {

    /** level is ERROR, WARN, INFO or DEBUG (anything else is INFO); event is a short code like refresh.failed. */
    public record LogLine(String level, String event, String detail) {
    }

    private final ClientLogService service;

    public ClientLogController(ClientLogService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void log(@RequestBody LogLine line) {
        service.record(line.level(), line.event(), line.detail());
    }
}
