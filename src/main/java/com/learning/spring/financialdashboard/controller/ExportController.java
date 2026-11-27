package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.config.LoginThrottle;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.service.CredentialService;
import com.learning.spring.financialdashboard.service.ExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;

/**
 * The whole dashboard as an Excel workbook. It is a POST that carries the password again, so a person who only has the open dashboard in front
 * of them cannot download (and read) everything: the password is checked here, with the same lock-out after wrong tries as signing in.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public record ExportRequest(String password) {
    }

    private final ExportService service;
    private final CredentialService credentials;
    private final LoginThrottle throttle;

    public ExportController(ExportService service, CredentialService credentials, LoginThrottle throttle) {
        this.service = service;
        this.credentials = credentials;
        this.throttle = throttle;
    }

    /** Downloaded as financial-dashboard-YYYY-MM-DD.xlsx. While no password has been set yet there is none to ask for. */
    @PostMapping
    public ResponseEntity<byte[]> download(@RequestBody(required = false) ExportRequest request) throws IOException {
        if (credentials.isSet() && !credentials.matches(request == null ? null : request.password())) {
            throttle.recordFailure();
            log.warn("Export refused: wrong password");
            throw new ValidationException("That password is not right");
        }
        throttle.recordSuccess();
        byte[] workbook = service.build();
        String name = "financial-dashboard-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(XLSX)
                .body(workbook);
    }
}
