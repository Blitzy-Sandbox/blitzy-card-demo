package com.carddemo.controller;

import com.carddemo.model.dto.ReportRequest;
import com.carddemo.model.dto.ReportResponse;
import com.carddemo.service.report.ReportSubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report REST controller. Re-platforms the CICS report-submission program
 * CORPT00C and its BMS screen CORPT00 (reference only, lineage commit 27d6c6f).
 * The COBOL WRITEQ TD online-to-batch bridge becomes an SQS FIFO publication
 * performed by the service; this endpoint is the stateless HTTP boundary.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportSubmissionService reportSubmissionService;

    public ReportController(ReportSubmissionService reportSubmissionService) {
        this.reportSubmissionService = reportSubmissionService;
    }

    @PostMapping("/submit")
    public ResponseEntity<ReportResponse> submit(@Valid @RequestBody ReportRequest request) {
        // The COBOL WRITEQ TD bridge is asynchronous: the service publishes a job message to
        // the SQS FIFO queue and returns immediately, the report is produced out-of-band by the
        // listener. The published contract (api-contracts.md) returns 202 Accepted to reflect
        // that the work has been accepted for asynchronous processing rather than completed.
        ReportResponse response = reportSubmissionService.submitReport(request);
        return ResponseEntity.accepted().body(response);
    }
}
