package com.carddemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.service.ReportService;

import jakarta.validation.Valid;

/**
 * Transaction-report request REST controller &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * replacement for the legacy AWS CardDemo report program {@code CORPT00C} (CICS transaction
 * {@code CR00}, &quot;Transaction Reports&quot;; frozen COBOL reference at source commit SHA
 * {@code 27d6c6f}, never copied into this repository).
 *
 * <h2>Legacy behaviour being migrated</h2>
 * <p>On the mainframe the {@code CORPT00} BMS map (3270 screen) let an operator pick a report
 * period &mdash; <strong>Monthly</strong>, <strong>Yearly</strong>, or <strong>Custom</strong>
 * (a caller-supplied start/end date range) &mdash; and confirm the selection. {@code CORPT00C}
 * then built a JCL job stream and submitted it to JES by writing to a CICS <em>extra-partition
 * Transient Data Queue</em> (the TDQ&nbsp;&rarr;&nbsp;JES bridge, paragraph
 * {@code SUBMIT-JOB-TO-INTRDR}). Per the migration architecture that order-preserving,
 * fire-and-forget bridge becomes an <strong>SQS FIFO</strong>-triggered Spring Batch launch: the
 * request is delegated to {@link ReportService}, which publishes exactly one message to
 * {@code carddemo-report-jobs.fifo} that triggers the {@code TransactionReportJob}
 * (AAP&nbsp;&sect;0.5.1, &sect;0.7.7, &sect;0.8.5).</p>
 *
 * <h2>Asynchronous, job-accepted contract (HTTP 202)</h2>
 * <p>Report generation is asynchronous: the batch job produces the report out-of-band, so this
 * endpoint does <strong>not</strong> block waiting for a rendered report. On acceptance it returns
 * HTTP&nbsp;{@code 202 Accepted} with a {@link ReportResponse} acknowledgement carrying an opaque
 * {@code jobId} the caller can use to poll for completion &mdash; the correct semantics for
 * &quot;job accepted, not yet complete&quot; (never {@code 200}/{@code 201}).</p>
 *
 * <h2>Thin controller by design</h2>
 * <p>This class performs no business logic: it binds and validates the HTTP request, delegates to
 * {@link ReportService}, and returns the produced acknowledgement. The report-window derivation for
 * MONTHLY/YEARLY, the CUSTOM cross-field rules (both dates mandatory and {@code startDate <= endDate},
 * the Java replacement for the twin {@code CALL 'CSUTLDTC'} date checks), the confirmation gate (the
 * pseudo-conversational {@code COMMAREA} confirm flag, now {@link ReportRequest#confirm()}), and the
 * SQS FIFO publish all live in the service. This controller deliberately does <strong>not</strong>
 * catch exceptions: the {@code @Pattern} report-type edit fails {@code @Valid} and is rendered as
 * HTTP&nbsp;{@code 400}, and a {@code ValidationException} / {@code DateValidationException} raised by
 * the service (missing or invalid CUSTOM dates, start after end, unknown type) is likewise mapped to
 * HTTP&nbsp;{@code 400} &mdash; all by the central {@link GlobalExceptionHandler}
 * {@code @RestControllerAdvice}.</p>
 *
 * <h2>Security</h2>
 * <p>The endpoint requires an authenticated caller but is reachable by <em>any</em> role: reports
 * are offered to regular users from the main menu, so there is no admin gate (no
 * {@code @PreAuthorize}). Authentication is enforced by the security filter chain in the
 * {@code config} package, not here.</p>
 *
 * <h2>Observability</h2>
 * <p>The controller logs the request at INFO with only the non-sensitive {@code reportType} and the
 * returned {@code jobId} (never any queue name, credential, or PII). The per-request MDC
 * {@code correlationId} established by the correlation-id filter is attached to every log line and is
 * propagated by {@link ReportService} onto the SQS trigger message, stitching this REST request to
 * the downstream batch job trace. No monetary fields are involved, so no {@code BigDecimal} (and no
 * {@code float}/{@code double}) appears; the reporting window uses {@code java.time.LocalDate} at the
 * DTO level.</p>
 *
 * <p>The type is a stateless, thread-safe Spring singleton using constructor injection; its single
 * service collaborator is {@code final}. Design rationale is recorded in {@code docs/decision-log.md}
 * and the COBOL-paragraph mapping in {@code docs/traceability-matrix.md} (Explainability rule), not
 * in code comments.</p>
 *
 * @see ReportService
 * @see ReportRequest
 * @see ReportResponse
 * @see GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportController {

    /**
     * Structured logger; emits only the non-sensitive {@code reportType} and returned {@code jobId}
     * &mdash; never a queue name, credential, or PII. Every line carries the MDC {@code correlationId}
     * supplied by the correlation-id filter (Observability rule).
     */
    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    /**
     * The report-launch service that resolves the reporting window, applies the confirmation gate,
     * and publishes the SQS FIFO trigger message. Constructor injection replaces the legacy static
     * COBOL linkage and keeps the controller's single dependency explicit and {@code final}.
     */
    private final ReportService reportService;

    /**
     * Creates the report controller with its report-launch collaborator.
     *
     * <p>Constructor injection is used exclusively (never field injection) so the dependency is
     * explicit and {@code final}, and so the controller can be instantiated directly &mdash; with a
     * mocked {@link ReportService} &mdash; in a standalone MockMvc unit test with no Spring
     * {@code ApplicationContext} required.</p>
     *
     * @param reportService the transaction-report launch service (never {@code null})
     */
    public ReportController(final ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Accepts a transaction-report request and enqueues it for asynchronous batch generation &mdash;
     * the REST translation of the {@code CORPT00C} enter-key path (paragraphs {@code PROCESS-ENTER-KEY}
     * and {@code SUBMIT-JOB-TO-INTRDR}).
     *
     * <p>The controller records a non-sensitive audit line (report type only) and delegates the entire
     * flow &mdash; report-window derivation, the CUSTOM date cross-field validation
     * ({@code startDate}/{@code endDate} required and {@code startDate <= endDate}), the confirmation
     * gate, and the SQS FIFO publish &mdash; to {@link ReportService#generateReport(ReportRequest)}.
     * It then returns HTTP&nbsp;{@code 202 Accepted} with the service's acknowledgement, because the
     * report is produced out-of-band by the SQS-triggered batch job (the request thread never blocks
     * on report generation).</p>
     *
     * <p>No exception is caught here (AAP requirement): a bad {@code reportType} fails the DTO
     * {@code @Pattern} under {@code @Valid} and becomes HTTP&nbsp;{@code 400}; a
     * {@code ValidationException} or {@code DateValidationException} raised by the service (missing or
     * invalid CUSTOM dates, start after end, unknown type) also becomes HTTP&nbsp;{@code 400}; and an
     * SQS/AWS enqueue failure becomes HTTP&nbsp;{@code 500} &mdash; all mapped by the central
     * {@link GlobalExceptionHandler}.</p>
     *
     * @param request the validated report request carrying the report type and, for CUSTOM, the
     *                start/end window and the confirm flag; validated by {@code @Valid} and never
     *                {@code null} (a missing or unreadable body is rejected upstream by Spring MVC)
     * @return an HTTP&nbsp;{@code 202 Accepted} {@link ResponseEntity} whose body is the
     *         {@link ReportResponse} job-accepted acknowledgement (an opaque {@code jobId} and status)
     */
    @PostMapping
    public ResponseEntity<ReportResponse> requestReport(@Valid @RequestBody final ReportRequest request) {
        // Audit the request by report type only (no secrets, no PII). The MDC correlationId set by the
        // correlation-id filter is attached automatically and is propagated by ReportService onto the
        // SQS FIFO trigger message, stitching this REST request to the downstream batch job trace.
        log.info("Report request received (CR00): reportType={}", request.reportType());

        // Delegate the entire CORPT00C flow (window derivation, CUSTOM date validation, confirmation
        // gate, and SQS FIFO publish) to the service. Any failure propagates to GlobalExceptionHandler.
        final ReportResponse response = reportService.generateReport(request);

        // Record the returned, non-sensitive jobId (and status) so the async acknowledgement is
        // traceable end-to-end. jobId is null only for a PENDING_CONFIRMATION acknowledgement.
        log.info("Report request acknowledged (CR00): reportType={}, status={}, jobId={}",
                request.reportType(), response.status(), response.jobId());

        // HTTP 202 Accepted: the report is produced asynchronously by the SQS-triggered batch job.
        return ResponseEntity.accepted().body(response);
    }
}
