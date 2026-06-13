package com.cardemo.controller;

import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the AWS CardDemo CICS BMS 3270 <strong>Transaction Reports</strong> screen. It
 * exposes a single route, <strong>{@code POST /api/reports/submit}</strong>, and is a thin adapter over
 * {@link ReportSubmissionService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L651: <em>{@code controller/ReportController.java} CREATE &larr;
 * {@code app/bms/CORPT00.bms} &mdash; "POST /api/reports/submit"</em>) and of AAP&nbsp;&sect;0.3.4
 * (BMS&nbsp;&rarr;&nbsp;REST contract translation). It preserves feature <strong>F-013</strong>
 * (Transaction Report submission) without expansion (Minimal Change Clause, AAP&nbsp;&sect;0.7.1):
 * exactly one endpoint, no business logic, no data access, no messaging.</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/CORPT00.bms}</strong> &mdash; the Transaction Reports mapset
 *       ({@code CORPT00} / map {@code CORPT0A}), driven by CICS program {@code CORPT00C}, transaction
 *       <strong>{@code CR00}</strong>. The 3270 screen let the operator choose a {@code Monthly},
 *       {@code Yearly} or {@code Custom} transaction report (the custom case supplying a start/end date
 *       as separate month/day/year parts) and confirm submission with a single {@code (Y/N)} keystroke
 *       ({@code CONFIRM PIC X(1)}).</li>
 * </ul>
 * <p>Its symbolic map ({@code app/cpy-bms/CORPT00.CPY}) was migrated into {@link ReportRequest} (the
 * {@code COPY CSSETATY} field contract) and the result shape into the nested record
 * {@link ReportSubmissionService.ReportSubmissionResult}. This controller never re-declares those
 * structures and never copies COBOL/BMS text &mdash; only the screen <em>behavior</em> is reproduced,
 * by delegation to {@link ReportSubmissionService}.</p>
 *
 * <h2>Key insight &mdash; the system's sole online&rarr;batch bridge (AAP &sect;0.6.3)</h2>
 * <p>{@code CORPT00C} is the <strong>single coupling point between the online and batch worlds</strong>
 * in the entire CardDemo estate. On the mainframe, a {@code 'Y'} confirmation caused the program to
 * build a JCL deck and write it to the CICS Transient Data Queue {@code JOBS} ({@code WRITEQ TD}), which
 * triggered JES batch submission of the downstream report job ({@code TRANREPT} &rarr; {@code CBTRN03C}).
 * In the migrated system that hand-off becomes a <strong>single AWS SQS publish</strong> to the FIFO
 * queue {@code carddemo-report-jobs.fifo}, which triggers the Spring Batch report job. This is the
 * <em>only</em> permitted infrastructure-bridge migration in the whole estate (AAP &sect;0.1.2
 * transformation table "CICS TDQ {@code WRITEQ} &rarr; SQS"; &sect;0.6.3; blueprint L631). The publish
 * itself is owned entirely by {@link ReportSubmissionService}; this controller performs none of it.</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code RECEIVE MAP('CORPT0A')} / {@code SEND MAP('CORPT0A')} &rarr; request/response
 *       DTO.</strong> The 3270 field harvest and screen paint collapse into Jackson (de)serialization
 *       of {@link ReportRequest} and {@link ReportSubmissionService.ReportSubmissionResult}. Screen
 *       chrome ({@code TRNNAME}, {@code TITLE01}/{@code TITLE02}, {@code CURDATE}, {@code CURTIME},
 *       {@code PGMNAME}), the {@code ERRMSG} line and BMS control bytes have no REST analogue and are
 *       not modeled as request fields (AAP&nbsp;&sect;0.4.2).</li>
 *   <li><strong>{@code RETURN TRANSID('CR00') COMMAREA} &rarr; stateless REST.</strong> The CICS
 *       pseudo-conversational hand-off (the {@code COCOM01Y} COMMAREA carrying state across turns) is
 *       replaced by stateless HTTP; this controller holds no conversational state
 *       (AAP&nbsp;&sect;0.1.2). The COBOL multi-turn confirm screen collapses into the single
 *       {@code confirm} field on the request: {@code Y}=submit, {@code N}=cancel &mdash; interpreted by
 *       the service, never here.</li>
 *   <li><strong>{@code WRITEQ TD 'JOBS'} (&rarr; JES) &rarr; SQS publish (&rarr; Spring Batch).</strong>
 *       See the bridge note above; the publish to {@code carddemo-report-jobs.fifo} is performed by
 *       {@link ReportSubmissionService}, not this adapter.</li>
 *   <li><strong>AID keys &rarr; distinct client-driven REST calls.</strong> The screen's {@code ENTER}
 *       (submit) and {@code PF3} (back) are client-navigation concerns: the client issues
 *       {@code POST /api/reports/submit} (or navigates away). They are not endpoints on this controller
 *       (AAP&nbsp;&sect;0.1.2).</li>
 * </ul>
 *
 * <h2>Validation parity &mdash; delegated to the service (AAP &sect;0.7.2)</h2>
 * <p>{@link ReportSubmissionService} is the authoritative source for the validation <em>order</em> and
 * the verbatim COBOL message text ({@code CORPT00C} emits specific ordered messages such as
 * "Select a report type to print report...", "Start Date - Not a valid date..." and
 * "&hellip; is not a valid value to confirm..."). The request body is therefore bound as a plain
 * {@code @RequestBody} <strong>without</strong> {@code @Valid}. If {@code @Valid} were applied here a
 * malformed field would be short-circuited at the boundary with a generic
 * {@code MethodArgumentNotValidException} (HTTP&nbsp;400) <em>instead</em> of the verbatim COBOL message
 * that only the service produces. Omitting {@code @Valid} ensures every request reaches the service so
 * message parity holds exactly (consistent with the other CardDemo mutation controllers, e.g.
 * {@code BillingController}/{@code AccountController}). The {@link ReportRequest} Jakarta constraints
 * remain the documented field contract (the {@code COPY CSSETATY} translation), but the service is the
 * runtime source of truth.</p>
 *
 * <h2>No transactional or data-layer expectation</h2>
 * <p>{@link ReportSubmissionService} is intentionally <strong>not</strong> {@code @Transactional}: it
 * performs no monetary arithmetic and no VSAM/JPA reads or writes &mdash; it is pure messaging
 * (AAP&nbsp;&sect;0.6.3). This controller therefore carries no transactional expectation; its only
 * decision is the {@code 202}-vs-{@code 200} status selection described below.</p>
 *
 * <h2>Error handling &mdash; centralized advice, exceptions propagate</h2>
 * <p>This controller defines <strong>no</strong> {@code @ExceptionHandler} /
 * {@code @RestControllerAdvice} and catches no exception. The service throws and this controller lets
 * propagate the typed exceptions translated by the centralized {@code @RestControllerAdvice} in
 * {@code config/WebConfig}:</p>
 * <ul>
 *   <li>{@code com.cardemo.exception.ValidationException} &rarr; HTTP&nbsp;<strong>400 Bad
 *       Request</strong> (blank/invalid report type, bad custom date range, or blank/invalid confirm
 *       value &mdash; the verbatim COBOL edit messages).</li>
 *   <li>A failed SQS publish surfaces as an unchecked {@code RuntimeException} &rarr;
 *       HTTP&nbsp;<strong>500 Internal Server Error</strong> (via the central {@code CardDemoException}
 *       / fallback mapping in {@code config/WebConfig}).</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>The {@code /api/reports/*} endpoint requires authentication; access is governed by the Spring
 * Security configuration in {@code config/SecurityConfig}. No security infrastructure and no
 * method-security annotations are introduced in this thin adapter.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit SHA
 * {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are never copied into
 * this repository (AAP&nbsp;&sect;0.7.2).</p>
 *
 * @see ReportSubmissionService
 * @see ReportSubmissionService.ReportSubmissionResult
 * @see ReportRequest
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    /**
     * Service owning the report-submission logic ({@code CORPT00C}): the {@code EVALUATE TRUE}
     * report-type branching (Monthly / Yearly / Custom), the custom-range date validation
     * ({@code CSUTLDTC} &rarr; {@code java.time}), the confirm / cancel handling, and the single SQS
     * publish to {@code carddemo-report-jobs.fifo} that replaces the legacy TDQ {@code WRITEQ}.
     */
    private final ReportSubmissionService reportSubmissionService;

    /**
     * Constructs the controller with the report-submission service injected by Spring (constructor
     * injection; the field is {@code final}; no field {@code @Autowired}).
     *
     * @param reportSubmissionService the report-submission service ({@code CORPT00C} translation)
     */
    public ReportController(final ReportSubmissionService reportSubmissionService) {
        this.reportSubmissionService = reportSubmissionService;
    }

    /**
     * Submits a transaction report request, reproducing the server-side core of {@code CORPT00C}.
     *
     * <p><strong>Endpoint:</strong> {@code POST /api/reports/submit}.</p>
     *
     * <p>The request body carries the report-type selection (Monthly / Yearly / Custom), the optional
     * custom start/end date parts, and the single-character {@code confirm} flag. All interpretation is
     * performed by {@link ReportSubmissionService#submitReport(ReportRequest)}: a {@code 'Y'}/{@code 'y'}
     * confirmation publishes the report job to the SQS FIFO queue {@code carddemo-report-jobs.fifo} (the
     * downstream Spring Batch trigger) and yields a {@code submitted == true} result; a
     * {@code 'N'}/{@code 'n'} confirmation cancels with no publish and yields {@code submitted == false};
     * a blank/other value, an unselected report type, or an invalid custom date range raises a
     * {@code ValidationException}. The body is bound as {@code @RequestBody} <strong>without</strong>
     * {@code @Valid} so the service owns the ordered verbatim COBOL edit messages and parity is preserved
     * (AAP&nbsp;&sect;0.7.2).</p>
     *
     * <p><strong>Status mapping</strong> (a thin presentation choice driven solely by
     * {@link ReportSubmissionService.ReportSubmissionResult#submitted() submitted()}, not business
     * logic):</p>
     * <ul>
     *   <li>{@code submitted() == true} &rarr; HTTP&nbsp;<strong>202 Accepted</strong> &mdash; the report
     *       job has been published to SQS for <em>asynchronous</em> Spring Batch processing; 202 is the
     *       canonical "accepted for async processing" status and mirrors the legacy TDQ&nbsp;&rarr;&nbsp;JES
     *       asynchronous trigger.</li>
     *   <li>{@code submitted() == false} &rarr; HTTP&nbsp;<strong>200 OK</strong> &mdash; the operator
     *       cancelled; nothing was queued (a no-op).</li>
     * </ul>
     *
     * @param request the report-criteria request (report-type flags, optional custom date parts, and
     *                confirm flag) bound from the JSON body
     * @return {@code 202 Accepted} with the {@link ReportSubmissionService.ReportSubmissionResult} when
     *         the report job was published; {@code 200 OK} with the result when the submission was
     *         cancelled
     * @throws com.cardemo.exception.ValidationException if no report type is selected, the custom date
     *         range is invalid, or the confirm flag is blank/invalid (rendered as HTTP&nbsp;400 by
     *         {@code config/WebConfig}); propagated, not caught
     * @throws RuntimeException if the SQS publish fails (rendered as HTTP&nbsp;500 by
     *         {@code config/WebConfig}); propagated, not caught
     */
    // COBOL substitution: CORPT00C RECEIVE MAP('CORPT0A') field harvest -> @RequestBody binding;
    // SEND MAP('CORPT0A') -> ReportSubmissionResult JSON; RETURN TRANSID('CR00') COMMAREA -> stateless
    // POST (no conversational state, AAP §0.1.2). The single confirm field collapses the COBOL
    // multi-turn confirm screen: Y=submit, N=cancel -- all service-owned.
    // SOLE online->batch bridge: CORPT00C WRITEQ TD 'JOBS' (-> JES) becomes an SQS publish to
    // carddemo-report-jobs.fifo (-> Spring Batch), performed inside ReportSubmissionService (AAP §0.6.3).
    // Bound WITHOUT @Valid so a malformed field reaches the service and surfaces the verbatim COBOL edit
    // message rather than a generic MethodArgumentNotValidException (parity, AAP §0.7.2; consistent with
    // BillingController/AccountController). Status: result.submitted() true -> 202 (accepted for async
    // batch) vs false -> 200 (cancelled / no-op) -- a presentation choice, not business logic.
    @PostMapping("/submit")
    public ResponseEntity<ReportSubmissionService.ReportSubmissionResult> submit(
            @RequestBody final ReportRequest request) {
        final ReportSubmissionService.ReportSubmissionResult result =
                reportSubmissionService.submitReport(request);
        return result.submitted()
                ? ResponseEntity.accepted().body(result)   // 202: report job queued for async Spring Batch
                : ResponseEntity.ok(result);               // 200: cancelled / no-op (nothing queued)
    }
}
