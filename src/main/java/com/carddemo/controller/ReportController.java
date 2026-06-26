package com.carddemo.controller;

import com.carddemo.dto.ReportDto;
import com.carddemo.service.ReportService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless REST surface for transaction-report submission, rooted at
 * {@code /api/reports}.
 *
 * <p>This controller is the HTTP entry point for the CICS pseudo-conversational
 * report-submission program {@code CORPT00C} (transaction {@code CR00}, mapset
 * {@code app/bms/CORPT00.bms}). The single endpoint
 * {@code POST /api/reports/submit} delegates to {@link ReportService}, which
 * resolves the requested reporting window and publishes a report-request message
 * to the SQS FIFO queue {@code carddemo-report-jobs.fifo}. Because the report is
 * produced later by a Spring Batch consumer, submission is asynchronous and the
 * endpoint responds with {@code 202 Accepted} and an empty body.</p>
 *
 * <p>The controller is a thin HTTP adapter: it holds no business logic and
 * performs no data access. The request body is checked declaratively with
 * {@link Valid}; the date-window, mutually-exclusive-flag, and messaging
 * failures raised by the service are translated to HTTP status codes by the
 * centralized {@code GlobalExceptionHandler}. All routes require an
 * authenticated caller, enforced by the application security configuration.</p>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    /**
     * Creates the controller with its single collaborating service.
     *
     * @param reportService the service that validates the reporting window and
     *                       publishes the report-request message; never
     *                       {@code null}
     */
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Submits a transaction-report request for asynchronous processing
     * ({@code CORPT00C} / {@code CR00}).
     *
     * <p>Delegates to {@link ReportService#submitReport(ReportDto.SubmitRequest)},
     * which selects the report type, computes or validates the reporting date
     * range, enforces the confirmation gate, and &mdash; on confirmation &mdash;
     * publishes a single message to the FIFO report queue. The submission is
     * accepted for later processing rather than completed inline.</p>
     *
     * @param request the validated submission request carrying the report-type
     *                flags, custom date segments, and confirmation flag
     * @return {@code 202 Accepted} with an empty body once the request has been
     *         handed off to the service
     */
    @PostMapping("/submit")
    public ResponseEntity<Void> submitReport(@Valid @RequestBody ReportDto.SubmitRequest request) {
        reportService.submitReport(request);
        return ResponseEntity.accepted().build();
    }
}
