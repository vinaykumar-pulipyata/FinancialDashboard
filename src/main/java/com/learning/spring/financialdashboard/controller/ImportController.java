package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.service.ImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Menu > Data > Import from Excel: the file is the request body. {@code apply=false} (the default) only checks it. */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    /** How far the run in progress has got: what it is doing and a percentage. The page polls this while it waits for the answer. */
    @GetMapping("/progress")
    public ImportService.Progress progress() {
        return service.progress();
    }

    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ImportService.Report importWorkbook(@RequestParam(defaultValue = "false") boolean apply, @RequestBody byte[] file) throws IOException {
        return service.run(new ByteArrayInputStream(file), apply);
    }
}
