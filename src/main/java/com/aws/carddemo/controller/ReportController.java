/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.controller;

import com.aws.carddemo.service.ReportJobHandle;
import com.aws.carddemo.service.ReportSubmissionRequest;
import com.aws.carddemo.service.ReportSubmissionResult;
import com.aws.carddemo.service.ReportSubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the CardDemo transaction-report submission endpoint
 * — the Java migration of the CICS BMS mapset {@code app/bms/CORPT00.bms}
 * (Transaction Reports Screen, TRANID {@code CR00}) plus its handling program
 * {@code app/cbl/CORPT00C.cbl} (649 lines, JCL JOB name {@code TRNRPT00}).
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>{@code POST /api/reports/submit} — body is a JSON-serialised
 * {@link ReportSubmissionRequest}; response is a JSON-serialised
 * {@link ReportSubmissionJsonResponse}. HTTP-status mapping:
 * <ul>
 *   <li>{@code 202 ACCEPTED} — submission succeeded; the underlying Spring
 *       Batch {@code TRNRPT00} job has been dispatched asynchronously and the
 *       response body carries the {@code jobId} for downstream status polling.
 *       The HTTP 202 status code per RFC 7231 §6.3.3 is the canonical choice
 *       for an asynchronous accept-and-acknowledge handoff that mirrors the
 *       COBOL {@code DFHRESP(NORMAL)} response from {@code EXEC CICS WRITEQ TD
 *       QUEUE('JOBS')} at lines 517-523 of CORPT00C.cbl.</li>
 *   <li>{@code 400 BAD_REQUEST} — service-layer validation rejected the
 *       submission. The response body's {@code message} field carries the
 *       COBOL-equivalent reject message verbatim per AAP §0.10.4 (Immutable
 *       Boundaries): {@code "Please select a report type..."},
 *       {@code "Start Date can NOT be empty"},
 *       {@code "End Date can NOT be empty"},
 *       {@code "Start Date - Not a valid date..."},
 *       {@code "End Date - Not a valid date..."},
 *       {@code "End Date should be greater than Start Date"},
 *       {@code "Confirmation cancelled by user"}.</li>
 *   <li>{@code 401 UNAUTHORIZED} — request reached the controller without
 *       an authenticated principal; Spring Security's filter chain rejects
 *       it before the controller is invoked.</li>
 *   <li>{@code 403 FORBIDDEN} — state-changing request submitted without a
 *       valid CSRF token; Spring Security's {@code CsrfFilter} rejects it
 *       before the controller is invoked.</li>
 *   <li>{@code 415 UNSUPPORTED_MEDIA_TYPE} — request lacked
 *       {@code Content-Type: application/json}; enforced by the
 *       {@code consumes = MediaType.APPLICATION_JSON_VALUE} attribute on
 *       the {@code @PostMapping} below.</li>
 *   <li>{@code 500 INTERNAL_SERVER_ERROR} — unexpected service-layer
 *       failure (the Java equivalent of the COBOL
 *       {@code 'Unable to Write TDQ (JOBS)...'} response at lines 531-532
 *       of CORPT00C.cbl). The {@link #handleServiceFailure(RuntimeException)}
 *       handler returns a sanitised error message; the underlying exception
 *       is never echoed in the HTTP response (AAP §0.10.5 — applied
 *       transitively to error details).</li>
 * </ul>
 *
 * <h2>COBOL Provenance — CORPT00C.cbl</h2>
 *
 * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph (lines 208-456) and
 * {@code SUBMIT-JOB-TO-INTRDR} paragraph (lines 462-510) encode the
 * report-submission workflow:
 *
 * <ol>
 *   <li>Resolve the report mode ({@code MONTHLY} / {@code YEARLY} /
 *       {@code CUSTOM}) and the associated date window (current month,
 *       current year, or user-supplied range with strict-ISO date parsing
 *       and start-≤-end ordering).</li>
 *   <li>Confirm the operator's {@code 'Y'} response to the confirmation
 *       prompt (CORPT00C.cbl lines 478-483).</li>
 *   <li>Assemble the JCL JOB stream (CORPT00C.cbl lines 84-127) and write
 *       it to the {@code JOBS} extra-partition TDQ via
 *       {@code EXEC CICS WRITEQ TD} (lines 517-523).</li>
 *   <li>Surface the success or reject message via the {@code SEND-TRNRPT-SCREEN}
 *       paragraph (lines 556-580).</li>
 * </ol>
 *
 * <p>The controller delegates the validation cascade, date computation,
 * dispatch composition, and reject-message production to
 * {@link ReportSubmissionService}; its responsibilities are restricted to:
 * <ol>
 *   <li>Auto-injecting the {@code "Y"} confirmation token so the REST POST
 *       itself acts as the confirmation gate. In the original CICS workflow
 *       the user was prompted with a separate confirmation screen
 *       ({@code CONFIRMI OF CORPT0AI}, line 464 of CORPT00C.cbl). The REST
 *       semantics collapse that two-step interaction into a single
 *       HTTP POST: clients that want to "back out" of the submission
 *       simply do not issue the POST. See <em>Java Migration:
 *       REST Confirmation Semantics</em> below.</li>
 *   <li>Mapping the service's {@link ReportSubmissionResult} outcome to the
 *       appropriate HTTP status code (202 / 400 / 500).</li>
 *   <li>Composing the wire-format {@link ReportSubmissionJsonResponse}
 *       carrying the JCL JOB name {@code TRNRPT00} verbatim per AAP §0.10.4
 *       (Immutable Boundaries) and the dispatcher-assigned job-id token
 *       from {@link ReportJobHandle#getIdentifier()}.</li>
 * </ol>
 *
 * <h2>Java Migration: REST Confirmation Semantics</h2>
 *
 * <p>The COBOL workflow required two operator interactions to dispatch a
 * report: (1) populate the report-mode and date fields, hit Enter; (2) see
 * the confirmation prompt and type {@code "Y"}. The Java REST migration
 * collapses this two-step interaction into a single HTTP POST: the act of
 * POSTing IS the confirmation, so the controller auto-injects {@code "Y"}
 * into {@link ReportSubmissionRequest#setConfirmation(String)} before
 * delegating to {@link ReportSubmissionService}. This means the JSON
 * request body has no {@code confirmation} field — clients send only the
 * mode and (for {@code CUSTOM}) the date window. Clients that want to
 * cancel a submission simply do not issue the POST.
 *
 * <p>This is a documented Java-migration adaptation (AAP §0.10.2: "All
 * deviations from literal COBOL logic must be documented with the original
 * COBOL paragraph name and reason for divergence"). The COBOL paragraph
 * {@code SUBMIT-JOB-TO-INTRDR} (lines 462-510 of CORPT00C.cbl) is the
 * origin; the divergence reason is that REST does not have a stateful
 * two-screen confirmation prompt analogous to CICS.
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>CSRF</b> — {@code POST /api/reports/submit} is a state-changing
 *       request (it queues a downstream batch job). Spring Security's
 *       {@code CsrfFilter} requires a valid CSRF token on every such
 *       request; missing tokens produce HTTP 403 before the controller is
 *       invoked.</li>
 *   <li><b>Authentication</b> — the endpoint requires an authenticated
 *       principal. Anonymous callers receive HTTP 401 from the Spring
 *       Security filter chain before the controller is invoked.</li>
 *   <li><b>PCI containment</b> — the request body carries only the report
 *       mode and date window; the response carries the success/reject
 *       message, the JCL JOB name {@code TRNRPT00}, the dispatcher-assigned
 *       job-id token, and the echoed report mode. No financial data, no
 *       account numbers, no card numbers ever pass through this endpoint.
 *       The endpoint is therefore safe to log at the {@code INFO} or
 *       {@code DEBUG} level without disclosing PII (AAP §0.10.5).</li>
 *   <li><b>JCL JOB name immutability</b> — the {@code "TRNRPT00"} literal
 *       (line 84 of CORPT00C.cbl: {@code //TRNRPT00 JOB 'TRAN
 *       REPORT',CLASS=A,MSGCLASS=0,...}) is propagated verbatim into the
 *       response body's {@code jobName} field. Downstream Spring Batch
 *       consumers use this exact name to look up the corresponding
 *       {@code Job} bean. The constant {@link #JOB_NAME} mirrors
 *       {@code ReportSubmissionService.JOB_NAME} (which is package-private
 *       and so cannot be referenced from this sibling package; the matching
 *       test {@code ReportControllerTest} asserts both literals against the
 *       same value so drift surfaces loudly per AAP §0.10.10 style
 *       consistency).</li>
 * </ul>
 *
 * @see ReportSubmissionService
 * @see ReportSubmissionRequest
 * @see ReportSubmissionResult
 * @see ReportJobHandle
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    // ------------------------------------------------------------------------
    // Service-layer constant mirror
    // ------------------------------------------------------------------------
    //
    // ReportSubmissionService.JOB_NAME is package-private (no modifier on the
    // `static final String` declaration in that class), so it cannot be
    // referenced directly from a class in a sibling package. The controller
    // duplicates the literal here so the success-response composition can
    // include it verbatim. There is a matching test
    // (com.aws.carddemo.controller.ReportControllerTest) that asserts the
    // mapped jobName against this same literal — so if the service ever
    // renames the JCL JOB name, the controller's mirror and its test will
    // fail together, surfacing the drift loudly (AAP §0.10.10).
    //
    // Source: com.aws.carddemo.service.ReportSubmissionService.JOB_NAME
    //         = "TRNRPT00" (verbatim from line 84 of app/cbl/CORPT00C.cbl)
    // ------------------------------------------------------------------------

    /**
     * JCL JOB name dispatched on every successful submission — verbatim from
     * line 84 of {@code app/cbl/CORPT00C.cbl} ({@code //TRNRPT00 JOB 'TRAN
     * REPORT',CLASS=A,MSGCLASS=0,...}). Load-bearing constant per AAP §0.10.4
     * (Immutable Boundaries).
     */
    static final String JOB_NAME = "TRNRPT00";

    /**
     * Confirmation token auto-injected into every inbound request before
     * delegation to the service. The REST POST itself is the confirmation, so
     * the controller treats every reachable POST as an implicit {@code "Y"}
     * response to the COBOL confirmation prompt ({@code CONFIRMI OF
     * CORPT0AI}, line 464 of CORPT00C.cbl).
     */
    static final String DEFAULT_CONFIRMATION = "Y";

    /**
     * Generic HTTP 500 message when the service layer throws an unexpected
     * exception. The underlying exception detail is logged internally
     * (production would inject a {@code Logger} here) but NEVER returned in
     * the HTTP response — preventing accidental disclosure of database error
     * strings, stack traces, SQL fragments, or internal class names (AAP
     * §0.10.5). Mirrors the COBOL {@code 'Unable to Write TDQ (JOBS)...'}
     * response (line 531-532 of CORPT00C.cbl).
     */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    // ------------------------------------------------------------------------
    // Collaborators (constructor-injected)
    // ------------------------------------------------------------------------

    /**
     * The single mock boundary for {@code ReportControllerTest} per AAP
     * §0.10.1 (Require Test Coverage rule): the service that owns the
     * validation cascade, date computation, dispatch composition, and
     * reject-message production. Constructor-injected so the test slice can
     * supply a {@code @MockBean} stand-in.
     */
    private final ReportSubmissionService reportSubmissionService;

    /**
     * Constructs the controller with a constructor-injected
     * {@link ReportSubmissionService} collaborator.
     *
     * @param reportSubmissionService the service that owns the report
     *                                submission workflow (validation cascade,
     *                                date computation, dispatch composition,
     *                                reject-message production); must not be
     *                                {@code null}
     */
    public ReportController(ReportSubmissionService reportSubmissionService) {
        this.reportSubmissionService = reportSubmissionService;
    }

    // ========================================================================
    // POST /api/reports/submit — submit a transaction-report job
    // (Java replacement for CORPT00C.cbl TRANID CR00 + JCL JOB TRNRPT00)
    // ========================================================================

    /**
     * Submits a transaction-report job. Accepts a JSON-serialised
     * {@link ReportSubmissionRequest} carrying the report mode (MONTHLY /
     * YEARLY / CUSTOM) and, for CUSTOM mode, the start/end-date window.
     * Auto-injects the {@code "Y"} confirmation token before delegating to
     * {@link ReportSubmissionService#submit(ReportSubmissionRequest)} so the
     * service's confirmation gate (CORPT00C.cbl lines 478-483) passes
     * without requiring the client to send a separate confirmation field.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Auto-inject {@code "Y"} into {@code request.confirmation}
     *       (REST-confirmation semantics — see class Javadoc).</li>
     *   <li>Delegate to {@link ReportSubmissionService#submit(ReportSubmissionRequest)}.
     *       The service performs:
     *       <ul>
     *         <li>Report-mode resolution (MONTHLY / YEARLY / CUSTOM).</li>
     *         <li>Date window computation (current month / current year /
     *             strict-ISO-parsed user input).</li>
     *         <li>Confirmation-gate enforcement (passes because we set
     *             {@code "Y"} above).</li>
     *         <li>Dispatch to the asynchronous batch executor via
     *             {@code ReportJobDispatcher}.</li>
     *       </ul></li>
     *   <li>Map the service result to the appropriate HTTP status:
     *       <ul>
     *         <li>{@code result.isSuccess() == true} → HTTP 202 Accepted
     *             with a {@link ReportSubmissionJsonResponse} carrying the
     *             dispatcher-assigned {@code jobId}, the verbatim JCL JOB
     *             name ({@code "TRNRPT00"}), and the echoed report mode.</li>
     *         <li>{@code result.isSuccess() == false} → HTTP 400 Bad Request
     *             with the reject message verbatim (per AAP §0.10.4).</li>
     *       </ul></li>
     * </ol>
     *
     * @param request the JSON-serialised report-submission request; if
     *                Spring's JSON message converter cannot deserialise the
     *                body, Spring's exception resolver returns HTTP 400
     *                before this method is invoked
     * @return a {@link ResponseEntity} carrying the
     *         {@link ReportSubmissionJsonResponse} body and the appropriate
     *         HTTP status code per the workflow above
     */
    @PostMapping(path = "/submit",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportSubmissionJsonResponse> submitReport(
            @RequestBody ReportSubmissionRequest request) {

        // Step 1 — REST-confirmation semantics. The POST itself is the
        // confirmation; auto-inject "Y" so the service's confirmation gate
        // (CORPT00C.cbl lines 478-483) passes unconditionally. Clients
        // that want to "cancel" the submission simply do not issue the
        // POST. Spring's @RequestBody on a valid request never produces a
        // null object — invalid JSON triggers
        // HttpMessageNotReadableException before this method is invoked,
        // and an empty body triggers HttpMessageNotReadableException
        // ("Required request body is missing"). The controller therefore
        // does not need to null-guard the {@code request} parameter.
        request.setConfirmation(DEFAULT_CONFIRMATION);

        // Step 2 — Delegate to the service. The service does ALL the
        // business logic (mode resolution, date computation, validation,
        // dispatch) so the controller never duplicates COBOL semantics in
        // test-visible code (AAP §0.10.1 Require Test Coverage rule).
        ReportSubmissionResult result = reportSubmissionService.submit(request);

        // Echo the request's reportMode in the response so clients can
        // confirm which mode the service actually processed (especially
        // useful when the request body had a case-mismatched value that
        // the service uppercased internally).
        String reportMode = request.getReportMode();

        if (result.isSuccess()) {
            // Step 3a — Happy path. The
            // ReportSubmissionResult.success(message, handle) factory
            // enforces non-null handle, and ReportJobHandle.of(...)
            // enforces non-null non-blank identifier — so the controller
            // can safely call getIdentifier() without an extra null
            // guard. HTTP 202 Accepted is the canonical REST status for
            // an asynchronous accept-and-acknowledge handoff (RFC 7231
            // §6.3.3) — the request has been accepted for processing but
            // has not yet completed. The response carries the jobId for
            // downstream status polling.
            String jobId = result.getJobHandle().getIdentifier();
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new ReportSubmissionJsonResponse(
                            true,
                            result.getMessage(),
                            jobId,
                            JOB_NAME,
                            reportMode));
        }

        // Step 3b — Validation reject. The service returned a failure
        // outcome with one of the seven COBOL-equivalent reject messages
        // (or the Java-migration-added MSG_END_BEFORE_START /
        // MSG_CANCELLED). HTTP 400 is appropriate because the input was
        // malformed or semantically invalid; the reject message is
        // propagated verbatim per AAP §0.10.4 (Immutable Boundaries) so
        // downstream consumers can pattern-match on the load-bearing
        // phrases preserved from the COBOL baseline.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ReportSubmissionJsonResponse(
                        false,
                        result.getMessage(),
                        null,
                        null,
                        reportMode));
    }

    // ========================================================================
    // @ExceptionHandler — unexpected service failures
    // ========================================================================

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service
     * layer (for example a dispatcher infrastructure failure when the
     * downstream batch executor is unreachable — the Java equivalent of the
     * COBOL {@code 'Unable to Write TDQ (JOBS)...'} response at lines
     * 531-532 of CORPT00C.cbl). Returns HTTP 500 with a sanitised body
     * ({@link #MSG_INTERNAL_ERROR}) — the underlying exception detail must
     * never leak into the HTTP response (AAP §0.10.5 applied to error
     * paths).
     *
     * <p>{@link AccessDeniedException} (and its Spring Security 6.1+
     * subtype {@code AuthorizationDeniedException}) is RE-THROWN so Spring
     * Security's {@code ExceptionTranslationFilter} can map it to HTTP 403.
     * This mirrors the pattern used by {@link TransactionController} and
     * {@link UserAdminController} so the controller-layer exception
     * handlers behave consistently across endpoints (AAP §0.10.10).
     *
     * @param ex the exception caught from the service layer; the controller
     *           does NOT include any field of this exception in the
     *           response body. Production deployments would log this
     *           exception via an injected {@code Logger} for diagnosis.
     * @return HTTP 500 with a generic, sanitised error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ReportSubmissionJsonResponse> handleServiceFailure(RuntimeException ex) {
        // Re-throw Spring Security authorization failures so the
        // ExceptionTranslationFilter can map them to HTTP 403. Catching
        // them here would mask the security infrastructure's intent.
        // Production code would replace the placeholder below with a
        // structured Logger invocation that records {ex} for operations
        // review (intentionally NOT echoed in the HTTP response per AAP
        // §0.10.5 applied to error paths — neither the exception message
        // nor its stack trace ever leaks into the response body).
        if (ex instanceof AccessDeniedException) {
            throw (AccessDeniedException) ex;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ReportSubmissionJsonResponse(
                        false, MSG_INTERNAL_ERROR, null, null, null));
    }

    // ========================================================================
    // Response DTO — inner record governing the wire-format contract
    // ========================================================================

    /**
     * Wire-format response for {@code POST /api/reports/submit}. Encodes
     * either a success outcome (with the dispatcher-assigned {@code jobId},
     * the verbatim JCL JOB name {@code "TRNRPT00"}, and the echoed
     * {@code reportMode}) or a failure outcome (with the reject message and
     * {@code null} job fields).
     *
     * <h2>Field Contract</h2>
     *
     * <ul>
     *   <li>{@code success} — {@code true} on the happy path (HTTP 202),
     *       {@code false} on any reject (HTTP 400 / 500).</li>
     *   <li>{@code message} — the COBOL-equivalent (or Java-migration-added)
     *       success or reject message from
     *       {@link ReportSubmissionResult#getMessage()} on success / 400
     *       reject; the sanitised {@link #MSG_INTERNAL_ERROR} literal on
     *       500. Never {@code null} on a controller-generated response.</li>
     *   <li>{@code jobId} — the dispatcher-assigned opaque identifier from
     *       {@link ReportJobHandle#getIdentifier()} on success; {@code null}
     *       on any reject path.</li>
     *   <li>{@code jobName} — the verbatim JCL JOB name {@code "TRNRPT00"}
     *       on success; {@code null} on any reject path. Load-bearing per
     *       AAP §0.10.4 — downstream Spring Batch consumers locate the
     *       corresponding {@code Job} bean by this name.</li>
     *   <li>{@code reportMode} — the request's {@code reportMode} echoed
     *       back so clients can confirm which mode the service actually
     *       processed; {@code null} only when the request body itself was
     *       {@code null} (deserialisation edge case).</li>
     * </ul>
     *
     * @param success    outcome flag (true on HTTP 202, false on 400/500)
     * @param message    the success/reject/error message; never {@code null}
     *                   on a controller-generated response
     * @param jobId      the dispatcher-assigned identifier; non-{@code null}
     *                   only on success
     * @param jobName    the verbatim JCL JOB name {@code "TRNRPT00"};
     *                   non-{@code null} only on success
     * @param reportMode the echoed request {@code reportMode}; may be
     *                   {@code null} when the request body was {@code null}
     */
    public static record ReportSubmissionJsonResponse(
            boolean success,
            String message,
            String jobId,
            String jobName,
            String reportMode) {
    }
}
