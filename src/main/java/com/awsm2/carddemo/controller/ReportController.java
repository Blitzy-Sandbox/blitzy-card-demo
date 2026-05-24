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
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.service.ReportSubmissionService;
import com.awsm2.carddemo.service.ReportSubmissionService.ReportSubmissionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report-submission REST controller &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/CORPT00C.cbl} (CICS transaction id {@code 'CR00'}),
 * paired with BMS mapset {@code app/bms/CORPT00.bms} (map {@code CORPT0A})
 * and its generated symbolic-map copybook {@code app/cpy-bms/CORPT00.CPY}
 * ({@code CORPT0AI} / {@code CORPT0AO}).
 *
 * <h2>The sole online-to-batch bridge (AAP &sect;0.1.1)</h2>
 *
 * <p>Per the Agent Action Plan, {@code CORPT00C.cbl} is the <i>only</i>
 * online-to-batch bridge in the entire CardDemo source code base. In the
 * COBOL/CICS source, the program:
 * <ol>
 *   <li>Presents a 3270 selection screen (MONTHLY / YEARLY / CUSTOM
 *       date-range radio selectors plus a {@code (Y/N)} confirmation
 *       field) via the {@code CORPT00} BMS mapset.</li>
 *   <li>On {@code DFHENTER} (ENTER key), the
 *       {@code PROCESS-ENTER-KEY} paragraph (lines 208-456 of the source)
 *       validates the selector + date triplet ({@code SDTMM/SDTDD/SDTYYYY},
 *       {@code EDTMM/EDTDD/EDTYYYY}) and ultimately invokes the
 *       {@code SUBMIT-JOB-TO-INTRDR} paragraph (lines 462-509).</li>
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} assembles a literal 80-byte-per-line
 *       JCL job-submission record (the {@code JOB-DATA} table at lines
 *       81-127) and writes each line to the CICS extra-partition
 *       Transient Data Queue named {@code 'JOBS'} via
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)}
 *       inside the {@code WIRTE-JOBSUB-TDQ} paragraph (lines 515-535).</li>
 *   <li>JES2 picks the JCL off the spool via its internal reader and
 *       dispatches the report batch job (operationally equivalent to
 *       {@code app/jcl/TRANREPT.jcl}, which in turn drives
 *       {@code app/cbl/CBTRN03C.cbl} to produce the printable report).</li>
 * </ol>
 *
 * <p>In the Java target this asynchronous "fire-and-forget" decoupling is
 * preserved by publishing a {@code report.requested} message to Amazon
 * MSK (Kafka) per AAP &sect;0.6.5; a Step Functions trigger Lambda
 * (see {@code com.awsm2.carddemo.adapter.KafkaEventConsumer}) consumes
 * the topic and starts an AWS Batch job that runs the
 * {@code TransactionReportJob} Spring Batch wrapper. The TDQ &rarr; JES
 * bridge becomes MSK &rarr; Step Functions &rarr; AWS Batch &mdash;
 * preserving every behavioral property of the original (asynchronous
 * decoupling, durable hand-off, ordered per-request processing).
 *
 * <h2>Endpoint inventory (AAP &sect;0.3.4)</h2>
 *
 * <ul>
 *   <li>{@link #submitReport(ReportRequestDto) POST /api/reports/submit}
 *       &mdash; submit a report-generation request; the controller
 *       delegates to {@link ReportSubmissionService#submitReport} which
 *       validates the request, generates a UUID {@code requestId}, and
 *       publishes the {@code report.requested} MSK event. Returns HTTP
 *       {@code 201 Created} with a {@link ReportSubmissionResult}
 *       payload wrapped in the standardised {@link ApiResponse}
 *       envelope (AAP &sect;0.3.4 envelope shape).</li>
 * </ul>
 *
 * <h2>Authorization (defense in depth)</h2>
 *
 * <p>Two independent gates enforce that only authenticated callers can
 * submit a report:
 * <ol>
 *   <li>{@link PreAuthorize @PreAuthorize("hasAnyRole('USER','ADMIN')")}
 *       on the method &mdash; method-level gate. The original COBOL
 *       program is reachable from BOTH the regular main menu
 *       ({@code COMEN01.bms} option) and the admin menu
 *       ({@code COADM01.bms} option) per AAP &sect;0.4.1, so either
 *       {@code ROLE_USER} or {@code ROLE_ADMIN} suffices.</li>
 *   <li>{@code SecurityConfig} URL-level matcher in
 *       {@code com.awsm2.carddemo.config.SecurityConfig} &mdash;
 *       rejects unauthenticated requests with HTTP {@code 401} before
 *       method invocation.</li>
 * </ol>
 *
 * <h2>COBOL paragraph translation (AAP &sect;0.7.3 traceability)</h2>
 * <table>
 *   <caption>CORPT00C.cbl &harr; ReportController</caption>
 *   <tr><th>COBOL construct (line range)</th>
 *       <th>Java equivalent</th></tr>
 *   <tr><td>{@code MAIN-PARA} (lines 163-202) &mdash;
 *           {@code EXEC CICS RECEIVE MAP('CORPT0A')} +
 *           {@code EVALUATE EIBAID}</td>
 *       <td>Spring MVC dispatch from {@code @RequestMapping("/api/reports")}
 *           + {@code @PostMapping("/submit")} composing the AAP-required
 *           {@code POST /api/reports/submit} route (Jackson binds the JSON
 *           body into the {@link ReportRequestDto} record)</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (lines 208-456) and
 *           {@code SUBMIT-JOB-TO-INTRDR} (lines 462-509)</td>
 *       <td>Delegated to
 *           {@link ReportSubmissionService#submitReport(ReportRequestDto)}
 *           (lines validate the request, gate confirmation, generate the
 *           UUID requestId, publish MSK event)</td></tr>
 *   <tr><td>{@code WIRTE-JOBSUB-TDQ} (lines 515-535) &mdash;
 *           {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}</td>
 *       <td>{@code KafkaEventPublisher.publishReportRequested(...)}
 *           inside the service &mdash; publishes to MSK topic
 *           {@code report.requested} per AAP &sect;0.6.5</td></tr>
 *   <tr><td>{@code SEND-TRNRPT-SCREEN} (lines 556-580) &mdash;
 *           {@code EXEC CICS SEND MAP('CORPT0A')} success line at
 *           lines 449-450 ({@code "<report-name> report submitted
 *           for printing ..."})</td>
 *       <td>HTTP {@code 201 Created} +
 *           {@link ApiResponse#success(Object, String)} carrying the
 *           {@link ReportSubmissionResult}
 *           ({@code "Report request submitted successfully"})</td></tr>
 * </table>
 *
 * <h2>HTTP status mapping (AAP &sect;0.3.4)</h2>
 *
 * <ul>
 *   <li>{@code 201 Created} &mdash; report request accepted and the
 *       {@code report.requested} MSK event published (the asynchronous
 *       batch job will be started by the Step Functions trigger). The
 *       receipt {@link ReportSubmissionResult} carries the generated
 *       UUID {@code requestId} so the client can correlate the eventual
 *       AWS Batch job-id and OpenSearch audit record (AAP &sect;0.6.6).</li>
 *   <li>{@code 400 Bad Request} &mdash; structural Jakarta Bean
 *       Validation failure (missing/invalid {@code reportType},
 *       malformed {@code confirm} value, etc.) translated by
 *       {@code com.awsm2.carddemo.exception.GlobalExceptionHandler}.
 *       Also returned by the service when cross-field rules fail (e.g.,
 *       missing dates for {@code CUSTOM}, {@code startDate > endDate}).</li>
 *   <li>{@code 401 Unauthorized} &mdash; missing or invalid JWT bearer
 *       token (enforced by {@code SecurityConfig} before method
 *       invocation).</li>
 * </ul>
 *
 * <h2>PCI-DSS &amp; observability discipline (AAP &sect;0.6.6)</h2>
 *
 * <ul>
 *   <li>The controller never logs sensitive data &mdash; report-request
 *       payloads contain only timeframe metadata (no card or account
 *       numbers), so logging the {@code reportType} alone is safe and
 *       PCI-DSS compliant. Date payloads are also non-sensitive but are
 *       intentionally not logged at controller scope to avoid noise; the
 *       service layer logs the {@code requestId} on successful publish.</li>
 *   <li>Audit emission (OpenSearch + CloudWatch metric) is performed by
 *       the service via {@code AuditLogService.auditEvent(...)} so the
 *       record contains the post-validation, normalised payload &mdash;
 *       not the raw HTTP request body.</li>
 *   <li>Structured JSON log lines route to CloudWatch Logs via the
 *       Logback + {@code logstash-logback-encoder} pipeline configured
 *       in {@code src/main/resources/logback-spring.xml}.</li>
 * </ul>
 *
 * <h2>Source provenance</h2>
 *
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CORPT00C.cbl} (CICS TRANID
 *       {@code 'CR00'}, TDQ {@code 'JOBS'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/CORPT00.bms} (mapset
 *       {@code CORPT00}, map {@code CORPT0A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/CORPT00.CPY}
 *       ({@code CORPT0AI} / {@code CORPT0AO}).</li>
 *   <li><b>JCL replaced:</b> {@code app/jcl/TRANREPT.jcl} &mdash;
 *       operationally submitted via the {@code report.requested} MSK
 *       topic + Step Functions trigger + AWS Batch job, NOT directly
 *       from this controller.</li>
 *   <li><b>Driving batch program (post-bridge):</b>
 *       {@code app/cbl/CBTRN03C.cbl} (transaction-report variant per
 *       AAP &sect;0.4.1).</li>
 * </ul>
 *
 * <p><b>Refactor discipline (AAP &sect;0.7.3 Minimal Change Clause):</b>
 * the controller is a thin HTTP boundary &mdash; all business logic
 * (validation, confirmation gating, UUID generation, MSK publish, audit
 * emission) lives in {@link ReportSubmissionService}. No optimisation
 * or enhancement beyond the migration mechanics is introduced here.</p>
 *
 * @see ReportSubmissionService
 * @see ReportRequestDto
 * @see ApiResponse
 * @see com.awsm2.carddemo.adapter.KafkaEventPublisher
 * @see com.awsm2.carddemo.adapter.KafkaEventConsumer
 */
// Replaces: app/cbl/CORPT00C.cbl (CICS TRANID 'CR00') online-to-batch
// bridge — the CICS TDQ 'JOBS' (extra-partition / internal reader) is
// replaced by an MSK Kafka publish to the 'report.requested' topic per
// AAP §0.1.1 + §0.6.5; a Step Functions trigger consumes the event and
// starts the AWS Batch report job.
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Report",
        description = "Report submission. Replaces CICS CORPT00C / Tran-ID CR00 "
                + "(the ONLY online-to-batch bridge in the source system) — "
                + "TDQ 'JOBS' → JES batch job submission is replaced by an MSK "
                + "Kafka publish to the 'report.requested' topic that a Step "
                + "Functions trigger consumes to submit an AWS Batch report job.")
@Validated
public class ReportController {

    /**
     * SLF4J logger emitting structured JSON log lines into the Logback +
     * logstash-logback-encoder pipeline that ships to CloudWatch Logs per
     * AAP &sect;0.6.6 (structured JSON logging requirement). PCI-DSS
     * discipline: never logs full report-request payloads or any
     * customer-identifying data; logs only event-shape metadata such as
     * the report type selector.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReportController.class);

    /**
     * Report-submission service &mdash; injected via constructor (the
     * sole field on this controller). All business logic
     * (request validation, confirmation gating, UUID generation, MSK
     * publish, audit emission) lives in this service per the AAP
     * &sect;0.3.3 layered architecture rule (Controller &rarr; Service
     * &rarr; Repository) and the AAP &sect;0.7.1 adapter-isolation
     * directive ("never inline AWS SDK calls in business logic").
     */
    private final ReportSubmissionService reportSubmissionService;

    /**
     * Constructor injection &mdash; the only permitted form of
     * dependency injection in this codebase per AAP &sect;0.3.3 (DI for
     * loose coupling) and Spring best practice (immutability, explicit
     * dependencies, testability via direct constructor invocation in
     * unit tests).
     *
     * <p>Mirrors the COBOL/CICS pattern of CORPT00C.cbl carrying its
     * dependencies through {@code COPY} directives (COCOM01Y,
     * CORPT00, COTTL01Y, CSDAT01Y, CSMSG01Y, CVTRA05Y, DFHAID,
     * DFHBMSCA per lines 138-149 of the source) &mdash; the Java
     * equivalent injects them at construction time.
     *
     * @param reportSubmissionService the {@link ReportSubmissionService}
     *                                bean managed by Spring; must not
     *                                be {@code null} (Spring guarantees
     *                                non-null injection for declared
     *                                constructor parameters)
     */
    // COBOL: CORPT00C — Spring constructor injection replaces the
    // COBOL COPY directive wiring of dependencies (COCOM01Y, CORPT00,
    // COTTL01Y, CSDAT01Y, CSMSG01Y, CVTRA05Y, DFHAID, DFHBMSCA at
    // lines 138-149 of app/cbl/CORPT00C.cbl).
    public ReportController(ReportSubmissionService reportSubmissionService) {
        this.reportSubmissionService = reportSubmissionService;
    }

    /**
     * Submit a transaction-report request.
     *
     * <p>This endpoint replaces the COBOL/CICS
     * {@code PROCESS-ENTER-KEY} &rarr; {@code SUBMIT-JOB-TO-INTRDR}
     * &rarr; {@code WIRTE-JOBSUB-TDQ} chain in
     * {@code app/cbl/CORPT00C.cbl} (the sole online-to-batch bridge in
     * the CardDemo source per AAP &sect;0.1.1).</p>
     *
     * <p><b>Flow:</b></p>
     * <ol>
     *   <li>Spring binds the JSON request body to the
     *       {@link ReportRequestDto} record and applies Jakarta Bean
     *       Validation. Structural failures (missing/invalid
     *       {@code reportType}, malformed {@code confirm}) yield HTTP
     *       {@code 400 Bad Request} via the
     *       {@code GlobalExceptionHandler} before this method is
     *       entered.</li>
     *   <li>The method emits a PCI-DSS-safe structured log line
     *       carrying only the report-type selector.</li>
     *   <li>{@link ReportSubmissionService#submitReport(ReportRequestDto)}
     *       is invoked. The service:
     *       <ol type="a">
     *         <li>Re-validates the request (defense in depth against
     *             callers that bypass Bean Validation, plus cross-field
     *             rules that Jakarta cannot express &mdash; CUSTOM
     *             date-presence, {@code startDate <= endDate},
     *             LE-style date validation via the ported
     *             {@code DateValidationService}).</li>
     *         <li>Gates submission on {@code confirm == "Y"} (the
     *             COBOL {@code SUBMIT-JOB-TO-INTRDR} confirmation
     *             check at lines 462-494 of the source).</li>
     *         <li>Generates a UUID {@code requestId} (the
     *             corresponding COBOL job used the fixed name
     *             {@code 'TRNRPT00'}; the Java target uses a UUID for
     *             per-request traceability and as the MSK partition
     *             key per AAP &sect;0.6.5).</li>
     *         <li>Publishes the {@code report.requested} message to MSK
     *             via {@code KafkaEventPublisher} (replaces
     *             {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}).</li>
     *         <li>Emits an audit event to OpenSearch +
     *             CloudWatch via {@code AuditLogService} (AAP
     *             &sect;0.6.6).</li>
     *         <li>Returns the {@link ReportSubmissionResult} carrying
     *             the {@code requestId} and acknowledgement
     *             message.</li>
     *       </ol>
     *   </li>
     *   <li>The controller wraps the result in the standardised
     *       {@link ApiResponse} envelope and returns HTTP
     *       {@code 201 Created} (per AAP &sect;0.3.4 status mapping for
     *       a POST that creates a resource &mdash; here, the resource
     *       is the asynchronous report-generation submission).</li>
     * </ol>
     *
     * <p><b>Return type:</b> {@code ResponseEntity<ApiResponse<ReportSubmissionResult>>}
     * &mdash; the controller declares the concrete payload type
     * {@link ReportSubmissionResult} (rather than the agent-prompt's
     * generic {@code Object} placeholder) for type safety and to
     * surface the receipt structure in the generated OpenAPI schema.
     * Per the agent prompt note: <i>"If the service returns a specific
     * DTO type, replace Object with that type in the controller
     * signature."</i></p>
     *
     * <p><b>HTTP status mapping (AAP &sect;0.3.4):</b></p>
     * <ul>
     *   <li>{@code 201 Created} &mdash; report request accepted; receipt
     *       returned. The MSK event is published before the response is
     *       sent, but the batch job itself runs asynchronously &mdash;
     *       the {@code requestId} is the client's correlation handle.</li>
     *   <li>{@code 400 Bad Request} &mdash; Jakarta Bean Validation
     *       failure (translated by {@code GlobalExceptionHandler}), or
     *       service-layer cross-field validation failure (thrown as
     *       {@code ValidationException}, also translated to 400).</li>
     *   <li>{@code 401 Unauthorized} &mdash; enforced by
     *       {@code SecurityConfig} before this method runs.</li>
     * </ul>
     *
     * <p><b>Idempotency note:</b> the underlying MSK producer is
     * configured with {@code acks=all} and
     * {@code enable.idempotence=true} per AAP &sect;0.6.5, so a publish
     * retry within a single producer session does not produce a duplicate
     * event. However, the controller itself does not de-duplicate at the
     * HTTP layer &mdash; clients that retry an HTTP request after a
     * timeout may produce two independent UUID {@code requestId}s and
     * therefore two batch jobs. This matches the COBOL behaviour where
     * the operator pressing ENTER twice on the 3270 terminal would also
     * submit two JOB cards to JES.</p>
     *
     * @param request the {@link ReportRequestDto} record bound from the
     *                JSON request body. Must not be {@code null} (Spring
     *                enforces this for {@code @RequestBody} arguments by
     *                returning HTTP 400 if the body is missing). Carries
     *                the {@code reportType} selector ({@code "MONTHLY"},
     *                {@code "YEARLY"}, or {@code "CUSTOM"}), optional
     *                {@code startDate} / {@code endDate} (required for
     *                {@code CUSTOM}), and the {@code confirm} flag
     *                (required to be {@code "Y"} for the submission to
     *                proceed).
     * @return {@link ResponseEntity} carrying HTTP {@code 201 Created}
     *         and an {@link ApiResponse} envelope wrapping the
     *         {@link ReportSubmissionResult} receipt. The envelope
     *         shape is documented in {@link ApiResponse} per AAP
     *         &sect;0.3.4: {@code code = "OK"}, message =
     *         {@code "Report request submitted successfully"},
     *         data = the {@link ReportSubmissionResult} carrying the
     *         UUID {@code requestId} and a human-readable
     *         acknowledgement.
     */
    @PostMapping("/submit")
    @Operation(
            summary = "Submit a transaction report request",
            description = "Submits a transaction report request for asynchronous batch "
                    + "processing. Supports MONTHLY, YEARLY, and CUSTOM (date-range) "
                    + "report types. Publishes the 'report.requested' MSK Kafka event "
                    + "consumed by a Step Functions trigger that submits an AWS Batch "
                    + "job (replaces the CICS TDQ 'JOBS' → JES submission bridge in "
                    + "COBOL CORPT00C — the ONLY online-to-batch bridge in the source "
                    + "system per AAP §0.1.1 / §0.6.5). Returns HTTP 202 Accepted with "
                    + "the submission receipt carrying the generated requestId; "
                    + "the actual report is generated asynchronously by the AWS Batch "
                    + "TransactionReportJob. Issue CP4-#10: status 202 reflects that "
                    + "the request has been queued for asynchronous processing — no "
                    + "report resource is created at this URI synchronously.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202",
                    description = "Report request accepted for asynchronous processing; "
                            + "the 'report.requested' MSK Kafka event has been published "
                            + "and the submission receipt (carrying the requestId and "
                            + "acknowledgement message) is returned in the ApiResponse "
                            + "envelope."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed — invalid or missing reportType, "
                            + "missing startDate / endDate for CUSTOM, malformed confirm "
                            + "value, startDate after endDate, or LE-style date "
                            + "validation failure (leap year, day-of-month, future-date "
                            + "guard)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT bearer token missing or invalid — enforced by "
                            + "SecurityConfig before method invocation.")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<ReportSubmissionResult>> submitReport(
            @Valid @RequestBody ReportRequestDto request) {
        // COBOL: CORPT00C / Tran-ID CR00 — Report Submission
        //         The ONLY online-to-batch bridge in the source system.
        //         CICS TDQ 'JOBS' → JES batch job submission
        //         → replaced by MSK Kafka 'report.requested' topic +
        //           Step Functions trigger (AAP §0.1.1, §0.6.5).
        //
        // The COBOL PROCESS-ENTER-KEY paragraph (lines 208-456) +
        // SUBMIT-JOB-TO-INTRDR paragraph (lines 462-509) +
        // WIRTE-JOBSUB-TDQ paragraph (lines 515-535) of
        // app/cbl/CORPT00C.cbl are collectively replaced by the
        // service.submitReport(...) call below.

        // PCI-DSS-safe structured log line per AAP §0.6.6 — emits only
        // the report-type selector (a non-sensitive timeframe enum).
        // Date payloads are intentionally not logged at the controller
        // boundary; the service emits a follow-up log with the
        // post-publish requestId for correlation.
        LOG.info("Report submission requested: type={}", request.reportType());

        // Delegate to the service — all business logic (validation,
        // confirmation gating, UUID generation, MSK publish, audit
        // emission) lives there per AAP §0.3.3 layered architecture
        // and the §0.7.1 adapter-isolation rule.
        ReportSubmissionResult submissionReceipt =
                reportSubmissionService.submitReport(request);

        // PCI-DSS-safe acknowledgement log — the service log has
        // already correlated the requestId; this controller-level log
        // emits the report-type selector to mirror the request log
        // for end-to-end traceability of the request/response pair.
        LOG.info("Report submission successful for type={}", request.reportType());

        // HTTP 201 Created per AAP §0.3.4 status mapping for a POST
        // that creates a resource (here, the asynchronous report-
        // generation submission identified by the UUID requestId
        // carried inside the ReportSubmissionResult payload).
        //
        // Envelope shape per AAP §0.3.4: ApiResponse.success(data,
        // message) emits code = "OK", message =
        // "Report request submitted successfully", data = the
        // ReportSubmissionResult receipt, correlationId = null
        // (request-scoped correlation may be added by downstream
        // request-filters via the X-Correlation-Id response header),
        // and timestamp = Instant.now() (UTC ISO-8601).
        //
        // HTTP 202 Accepted per REST conventions and Issue CP4-#10:
        // the operation is asynchronous (an MSK Kafka event has been
        // published; the actual report generation occurs in a separate
        // Step Functions / AWS Batch pipeline). 202 Accepted signals
        // "request accepted, processing will occur later" — distinct
        // from 201 Created which would imply a new resource was
        // available synchronously at a URI. The receipt's requestId
        // is the correlation handle clients use to poll for completion.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(submissionReceipt,
                        "Report request submitted successfully"));
    }
}
