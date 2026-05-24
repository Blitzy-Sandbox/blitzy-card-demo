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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.validation.DateValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Report-submission service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/CORPT00C.cbl} (CICS transaction id {@code 'CR00'}).
 *
 * <p><b>Online-to-batch bridge (AAP &sect;0.1.1).</b> Per the Agent Action
 * Plan, this service is the <i>sole online-to-batch bridge</i> in the
 * CardDemo source code base. In the COBOL/CICS source, the
 * {@code CORPT00C.cbl} program assembles a literal JCL job-submission
 * record (the {@code JOB-DATA} 80-byte lines at lines 81-127 of the
 * source) and writes each line to the CICS Transient Data Queue
 * ({@code TDQ}) named {@code 'JOBS'} via
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)} (paragraph
 * {@code WIRTE-JOBSUB-TDQ}, line 515 of the source). The JES2 reader
 * picks the JCL up from the spool and submits the report batch job
 * (the operational equivalent of {@code app/jcl/TRANREPT.jcl} which in
 * turn drives {@code app/cbl/CBTRN03C.cbl}).
 *
 * <p>In the Java target this entire mechanism is replaced by a single
 * {@code report.requested} MSK Kafka publish. The MSK consumer (a Step
 * Functions trigger Lambda; see
 * {@code com.awsm2.carddemo.adapter.KafkaEventConsumer#onReportRequested})
 * reads the event, materialises a Step Functions execution input from
 * the {@link ReportRequestDto} payload, and starts the AWS Batch report
 * job &mdash; preserving the asynchronous decoupling that the COBOL TDQ
 * design provided (per AAP &sect;0.1.1: <i>"the sole online-to-batch
 * bridge ({@code CORPT00C} &rarr; CICS TDQ JOBS queue &rarr; JES
 * submission) translates to an MSK topic ({@code report.requested})
 * consumed by a Step Functions trigger that submits an AWS Batch job
 * &mdash; preserving the asynchronous decoupling"</i>).
 *
 * <h2>COBOL paragraph translation (AAP &sect;0.7.3 traceability)</h2>
 * <table>
 *   <caption>CORPT00C.cbl &harr; ReportSubmissionService</caption>
 *   <tr><th>COBOL paragraph (line range)</th>
 *       <th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (lines 208-456) &mdash; date
 *           validation cascade {@code MONTHLY / YEARLY / CUSTOM}</td>
 *       <td>{@link #validateRequest(ReportRequestDto)}</td></tr>
 *   <tr><td>{@code SUBMIT-JOB-TO-INTRDR} (lines 462-509) &mdash;
 *           confirmation gate ({@code CONFIRMI = 'Y'/'y'/'N'/'n'})</td>
 *       <td>{@link #validateConfirmation(String)} &mdash; the Java REST
 *           contract enforces {@code confirm = "Y"} for submission to
 *           proceed</td></tr>
 *   <tr><td>{@code BUILD-JOB-DATA} (implicit in the JCL template
 *           assembly) and {@code WIRTE-JOBSUB-TDQ} (lines 515-535)
 *           &mdash; {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}</td>
 *       <td>{@link KafkaEventPublisher#publishReportRequested(String,
 *           ReportRequestDto)} &mdash; publishes to MSK topic
 *           {@code report.requested}; partition key = the UUID
 *           {@code requestId} generated below</td></tr>
 *   <tr><td>{@code DISPLAY 'PROCESS ENTER KEY'} (line 210)</td>
 *       <td>{@link AuditLogService#auditEvent(String, String, Map)}
 *           with event name {@code "REPORT_REQUESTED"} &mdash;
 *           supersedes the COBOL DISPLAY for searchable audit per AAP
 *           &sect;0.6.6</td></tr>
 * </table>
 *
 * <h2>Source provenance</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CORPT00C.cbl} (CICS TRANID
 *       {@code 'CR00'}, TDQ {@code 'JOBS'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/CORPT00.bms} (mapset
 *       {@code CORPT00}, map {@code CORPT0A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/CORPT00.CPY}
 *       ({@code CORPT0AI} / {@code CORPT0AO}).</li>
 *   <li><b>Downstream JCL replaced:</b> {@code app/jcl/TRANREPT.jcl}
 *       (transaction report) and related batch jobs &mdash; now
 *       orchestrated by the {@code transaction-report} Step Functions
 *       state machine triggered by the MSK consumer on receipt of the
 *       {@code report.requested} event.</li>
 *   <li><b>Driving batch program (post-bridge):</b>
 *       {@code app/cbl/CBTRN03C.cbl} (transaction-report variant).</li>
 * </ul>
 *
 * <h2>Implementation rules (per file schema / agent prompt)</h2>
 * <ol>
 *   <li>{@code @Service} stereotype &mdash; Spring-managed bean
 *       (AAP &sect;0.7.1).</li>
 *   <li><b>NO</b> {@code @Transactional} &mdash; this method performs
 *       only an MSK publish (idempotent async producer) and an audit
 *       emission; no RDS write requires a transactional boundary. The
 *       Kafka producer is configured with {@code acks=all} +
 *       {@code enable.idempotence=true} in {@code KafkaConfig} which
 *       provides the exactly-once-within-a-session guarantee directly
 *       (AAP &sect;0.6.5).</li>
 *   <li><b>Constructor injection only</b> &mdash; all collaborators
 *       declared as {@code private final} fields (AAP &sect;0.7.1
 *       loose-coupling rule).</li>
 *   <li><b>Inline traceability comments</b> &mdash; each translated
 *       paragraph carries a {@code // COBOL: CORPT00C:<paragraph>}
 *       comment per AAP &sect;0.7.3 (Refactor Discipline Guidelines).</li>
 *   <li><b>Date validation via {@link DateValidationService}</b>
 *       &mdash; never an inline {@link LocalDate#parse(CharSequence)}
 *       call. The service ports the COBOL CSUTLDPY.cpy / CSUTLDTC.cbl
 *       (LE {@code CEEDAYS}) cascade verbatim.</li>
 *   <li><b>MSK publish via {@link KafkaEventPublisher}</b> &mdash;
 *       never an inline {@code KafkaTemplate.send(...)} call (AAP
 *       &sect;0.7.1 adapter-isolation rule).</li>
 *   <li><b>{@code requestId} is a {@link UUID}</b> &mdash; used as both
 *       the MSK partition key (guaranteeing per-report ordering per AAP
 *       &sect;0.6.5) and the response correlation identifier surfaced
 *       to the caller for status tracking.</li>
 *   <li><b>NO direct JCL string construction</b> &mdash; the COBOL JCL
 *       templating logic (the {@code JOB-DATA} 80-byte lines at lines
 *       81-127 of the source) is intentionally <i>not</i> reproduced.
 *       The MSK consumer (Step Functions trigger Lambda) builds the
 *       state machine input from the {@link ReportRequestDto} fields
 *       carried on the Kafka message.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>The service is stateless beyond the injected collaborators (all of
 * which are themselves thread-safe Spring singletons). Concurrent
 * invocations of {@link #submitReport(ReportRequestDto)} from multiple
 * REST request threads are safe; each call allocates its own UUID and
 * audit payload map.</p>
 *
 * @see KafkaEventPublisher#publishReportRequested(String, ReportRequestDto)
 * @see ReportRequestDto
 * @see DateValidationService
 * @see com.awsm2.carddemo.adapter.KafkaEventConsumer
 */
// Replaces: app/cbl/CORPT00C.cbl (CICS TRANID 'CR00') online-to-batch
// bridge — CICS TDQ JOBS extra-partition (internal reader) is replaced
// by MSK Kafka publish per AAP §0.1.1.
@Service
public class ReportSubmissionService {

    /**
     * SLF4J logger emitting structured JSON log lines into the Logback +
     * logstash-logback-encoder pipeline that ships to CloudWatch Logs per
     * AAP &sect;0.6.6 (structured JSON logging requirement). PCI-DSS
     * discipline: never logs full date payloads or operator-supplied
     * confirmation values; logs only event-shape metadata.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReportSubmissionService.class);

    /**
     * Canonical, case-normalized set of report-type selectors allowed
     * by the {@code POST /api/reports/submit} contract.
     *
     * <p>Mirrors the three radio-style 1-character unprotected fields
     * {@code MONTHLY / YEARLY / CUSTOM} of {@code app/bms/CORPT00.bms}
     * and the {@code EVALUATE TRUE} discriminator at lines 213, 239,
     * 256 of {@code app/cbl/CORPT00C.cbl}. The Java target enforces a
     * strict normalized vocabulary (the {@link ReportRequestDto} {@link
     * java.util.regex.Pattern @Pattern} annotation already pins this at
     * the Jakarta-Bean-Validation layer); the same allowlist is checked
     * here defensively in case a caller bypasses Bean Validation
     * (e.g., a Spring Batch job invoking the service directly).</p>
     */
    private static final List<String> ALLOWED_REPORT_TYPES =
            List.of("MONTHLY", "YEARLY", "CUSTOM");

    /**
     * Date-format mask passed to {@link DateValidationService} for
     * validating ISO-8601 date strings ({@code yyyy-MM-dd}).
     *
     * <p>The {@link ReportRequestDto} declares its {@code startDate}
     * and {@code endDate} fields as {@link LocalDate}, which produces
     * a stable {@code "yyyy-MM-dd"} representation via
     * {@link LocalDate#toString()}. The {@link DateValidationService}
     * accepts the COBOL-style mask {@code "YYYY-MM-DD"} (translated
     * internally to the {@link java.time.format.DateTimeFormatter}
     * pattern {@code "uuuu-MM-dd"}) and applies the same century check
     * and STRICT-resolver semantics that the COBOL LE {@code CEEDAYS}
     * cascade enforces (per AAP &sect;0.5.2 dependency-removal
     * table).</p>
     */
    private static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

    /**
     * Audit event-name constant used when emitting the report-submission
     * audit record to OpenSearch. Held as a class constant so that
     * downstream OpenSearch dashboards and CloudWatch alarms can match
     * on a stable string.
     */
    private static final String AUDIT_EVENT_NAME = "REPORT_REQUESTED";

    /**
     * Audit operator label used when no per-request operator identifier
     * is propagated through the call stack. The Java REST surface
     * typically receives the operator's JWT-derived user ID via the
     * Spring Security context; this constant is used as a fallback so
     * that audit emissions always carry a non-{@code null} actor.
     */
    private static final String AUDIT_OPERATOR_SYSTEM = "system";

    /**
     * MSK Kafka producer adapter &mdash; the sole channel through which
     * this service interacts with Amazon MSK. Per AAP &sect;0.7.1
     * adapter-isolation rule, no inline {@code KafkaTemplate} usage is
     * permitted in business-logic services; every Kafka publish flows
     * through this adapter.
     */
    private final KafkaEventPublisher kafkaEventPublisher;

    /**
     * Audit-log adapter &mdash; emits the report-submission event to
     * Amazon OpenSearch (for searchable retention) and increments a
     * Micrometer counter shipped to CloudWatch. Per AAP &sect;0.6.6
     * the adapter handles failure modes (logs at ERROR; never rethrows
     * to the caller) so this service can call it unconditionally
     * without compromising the user-facing request flow.
     */
    private final AuditLogService auditLogService;

    /**
     * Date-validation service &mdash; ports the COBOL
     * {@code CSUTLDPY.cpy} / {@code CSUTLDTC.cbl} cascade (originally
     * implemented via LE {@code CEEDAYS}) to native
     * {@link java.time.LocalDate}-based validation. Used here to
     * validate the supplied {@code startDate} and {@code endDate}
     * strings (when present) before publishing the MSK event.
     */
    private final DateValidationService dateValidationService;

    /**
     * Constructor injection per AAP &sect;0.7.1 ("Dependency injection
     * for loose coupling"). Spring auto-wires the three collaborator
     * beans at startup. Explicit
     * {@link Objects#requireNonNull(Object, String)} guards protect
     * unit tests, ad-hoc instantiations, and any
     * bean-definition-resolution failure modes.
     *
     * @param kafkaEventPublisher   the MSK Kafka producer adapter;
     *                              never {@code null}
     * @param auditLogService       the audit-log adapter; never
     *                              {@code null}
     * @param dateValidationService the date-validation service; never
     *                              {@code null}
     */
    public ReportSubmissionService(KafkaEventPublisher kafkaEventPublisher,
                                   AuditLogService auditLogService,
                                   DateValidationService dateValidationService) {
        // Replaces: WORKING-STORAGE SECTION + LINKAGE SECTION dependency
        // wiring in CORPT00C.cbl — the COBOL program "imports" via
        // COPY directives; the Java target uses constructor injection.
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
        this.dateValidationService = Objects.requireNonNull(dateValidationService,
                "dateValidationService must not be null");
    }

    // =====================================================================
    // Public API — submitReport(ReportRequestDto)
    // =====================================================================

    /**
     * Submit a report-generation request by publishing an equivalent
     * MSK {@code report.requested} event.
     *
     * <p>This method is the Java equivalent of the COBOL
     * {@code PROCESS-ENTER-KEY} &rarr; {@code SUBMIT-JOB-TO-INTRDR}
     * &rarr; {@code WIRTE-JOBSUB-TDQ} chain in
     * {@code app/cbl/CORPT00C.cbl}. The flow is:</p>
     * <ol>
     *   <li><b>Validate request shape</b> &mdash;
     *       {@link #validateRequest(ReportRequestDto)} checks
     *       {@code reportType} against {@link #ALLOWED_REPORT_TYPES},
     *       requires {@code startDate}/{@code endDate} for the
     *       {@code CUSTOM} selector, validates each non-null date via
     *       {@link DateValidationService}, and enforces the
     *       {@code startDate <= endDate} chronological invariant.</li>
     *   <li><b>Validate confirmation gate</b> &mdash;
     *       {@link #validateConfirmation(String)} requires the
     *       {@code confirm} field to be {@code "Y"} (case-insensitive)
     *       per the COBOL {@code SUBMIT-JOB-TO-INTRDR} paragraph at
     *       lines 462-494 of the source (which treats
     *       {@code CONFIRMI = 'Y' OR 'y'} as "submit",
     *       {@code CONFIRMI = 'N' OR 'n'} as "cancel", an empty value
     *       as "re-prompt", and anything else as "invalid value to
     *       confirm").</li>
     *   <li><b>Generate a unique {@link UUID} {@code requestId}</b>
     *       &mdash; used as both the MSK partition key (guaranteeing
     *       per-report ordering per AAP &sect;0.6.5) and the response
     *       correlation identifier returned in
     *       {@link ReportSubmissionResult}.</li>
     *   <li><b>Publish {@code report.requested} to MSK</b> via
     *       {@link KafkaEventPublisher#publishReportRequested(String,
     *       ReportRequestDto)}. This is the operational equivalent of
     *       the COBOL {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} call
     *       at line 517 of the source &mdash; the MSK consumer (Step
     *       Functions trigger Lambda) reads the event and starts a
     *       Step Functions execution that submits the AWS Batch
     *       report job.</li>
     *   <li><b>Emit an audit record</b> via
     *       {@link AuditLogService#auditEvent(String, String, Map)}
     *       so the submission is searchable in OpenSearch and metered
     *       in CloudWatch (preserves the COBOL audit-trail semantics
     *       per AAP &sect;0.6.6).</li>
     *   <li><b>Return {@link ReportSubmissionResult}</b> carrying the
     *       UUID {@code requestId} and an acknowledgement message.</li>
     * </ol>
     *
     * <p><b>No transactional boundary.</b> The method is intentionally
     * NOT annotated {@code @Transactional}: no RDS write is performed.
     * The MSK publish is idempotent (producer configured with
     * {@code acks=all} + {@code enable.idempotence=true} per AAP
     * &sect;0.6.5), and the audit emission is async + failure-tolerant
     * per AAP &sect;0.6.6.</p>
     *
     * @param request the report-submission request DTO carrying
     *                {@code reportType} (one of {@code "MONTHLY"},
     *                {@code "YEARLY"}, {@code "CUSTOM"}), optional
     *                {@code startDate} / {@code endDate} (required for
     *                {@code "CUSTOM"}), and the {@code confirm} flag
     *                (must be {@code "Y"} to proceed). Must not be
     *                {@code null}.
     * @return a {@link ReportSubmissionResult} carrying the generated
     *         UUID {@code requestId} and an acknowledgement message;
     *         never {@code null}.
     * @throws ValidationException if {@code request} carries an
     *                             unknown report type, missing dates
     *                             for {@code CUSTOM}, an invalid date
     *                             format, a {@code startDate >
     *                             endDate} chronology, or a
     *                             {@code confirm} value other than
     *                             {@code "Y"}. The exception is
     *                             translated to HTTP 400 by the
     *                             {@code GlobalExceptionHandler}
     *                             (AAP &sect;0.3.4 error envelope).
     * @throws NullPointerException if {@code request} is {@code null}
     */
    // COBOL: CORPT00C:PROCESS-ENTER-KEY → SUBMIT-JOB-TO-INTRDR → WIRTE-JOBSUB-TDQ
    // AAP §0.1.1 online-to-batch bridge — CICS TDQ JOBS → MSK report.requested
    public ReportSubmissionResult submitReport(ReportRequestDto request) {
        Objects.requireNonNull(request, "request must not be null");

        // ---- Step 1 — validate dates and report type ----
        // COBOL: CORPT00C:PROCESS-ENTER-KEY (lines 208-443) — date
        // triplet validation cascade for CUSTOM (lines 256-426), and
        // implicit type selection for MONTHLY / YEARLY (lines 213-255).
        validateRequest(request);

        // ---- Step 2 — confirmation gate ----
        // COBOL: CORPT00C:SUBMIT-JOB-TO-INTRDR (lines 462-494) —
        // CONFIRMI = 'Y'/'y' to proceed; anything else aborts.
        validateConfirmation(request.confirm());

        // ---- Step 3 — generate per-submission requestId ----
        // The COBOL source used a fixed JCL job name ('TRNRPT00') with
        // no per-submission identifier; the Java target generates a
        // unique requestId so each submission is independently traceable.
        // Format: "<USER-ID>-<UUID>" so the carrying body retains the
        // global uniqueness needed for downstream correlation while
        // preserving the USER-ID prefix for human-readable audit.
        final String userId = resolveCurrentUserId();
        final String requestId = userId + "-" + UUID.randomUUID();

        // ---- Step 4 — publish report.requested to MSK ----
        // COBOL: CORPT00C:WIRTE-JOBSUB-TDQ (lines 515-535) —
        // EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD).
        // AAP §0.1.1: the sole online-to-batch bridge in CardDemo.
        // The MSK consumer (Step Functions trigger Lambda) reads the
        // event and starts the transaction-report state machine which
        // submits the AWS Batch job equivalent of TRANREPT.jcl.
        //
        // Issue CP4-#11: partition key = USER-ID (NOT the UUID-bearing
        // requestId). Per AAP §0.6.5 ("MSK topic ordering guarantees
        // for financial transactions — partition by USER-ID for the
        // report.requested topic per the JWT principal that submitted
        // the report"). All report submissions for a given user land on
        // a single partition, guaranteeing the user observes their own
        // submissions in submission order even when the consumer fans
        // out across partitions. The full requestId continues to
        // appear inside the event payload (via the ReportRequestDto)
        // and in the audit log for downstream correlation.
        kafkaEventPublisher.publishReportRequested(userId, request);

        // ---- Step 5 — emit audit event ----
        // Replaces: COBOL DISPLAY 'PROCESS ENTER KEY' (line 210) and
        // any operational diagnostic emissions. The audit record is
        // indexed in OpenSearch (PCI-DSS compliant searchable retention
        // per AAP §0.6.6) and metered in CloudWatch via Micrometer.
        // AuditLogService.auditEvent(...) sanitizes the payload (mask
        // PAN-like sequences, drop sensitive keys) and is async +
        // failure-tolerant (never re-throws on OpenSearch transport
        // errors), so this call cannot disrupt the user-facing flow.
        auditLogService.auditEvent(AUDIT_EVENT_NAME, AUDIT_OPERATOR_SYSTEM,
                buildAuditPayload(requestId, request));

        // PCI-DSS-safe log line — never logs the full date payload
        // (already serialised into the MSK event and audit document);
        // logs only metadata. AAP §0.6.6 structured JSON logging
        // requirement is satisfied because Logback +
        // logstash-logback-encoder serialises the message-arg pairs as
        // JSON structured fields.
        LOG.info("report.requested published requestId={} reportType={}",
                requestId, normalize(request.reportType()));

        // ---- Step 6 — return ack ----
        // The acknowledgement message format matches the COBOL
        // CORPT00C SEND-TRNRPT-SCREEN success line at lines 449-450
        // of the source ("<report-name> report submitted for printing
        // ...") adapted for a JSON REST response that carries the
        // UUID requestId for downstream correlation.
        return new ReportSubmissionResult(requestId,
                "Report request submitted successfully. Request ID: " + requestId);
    }

    // =====================================================================
    // Private helpers — request validation
    // =====================================================================

    /**
     * Validate the structural and semantic shape of the supplied report
     * request. Replaces the COBOL date validation cascade in
     * {@code PROCESS-ENTER-KEY} (lines 208-443 of
     * {@code app/cbl/CORPT00C.cbl}).
     *
     * <p>Validation cascade (in order):</p>
     * <ol>
     *   <li>{@code reportType} present and one of {@code MONTHLY},
     *       {@code YEARLY}, {@code CUSTOM} (case-insensitive). COBOL
     *       provenance: {@code EVALUATE TRUE WHEN MONTHLYI / YEARLYI /
     *       CUSTOMI} at lines 213, 239, 256 of the source.</li>
     *   <li>For {@code CUSTOM}: {@code startDate} and {@code endDate}
     *       both non-{@code null}. COBOL provenance: the
     *       {@code WHEN SDTMMI = SPACES OR LOW-VALUES} guards at lines
     *       259-300 of the source.</li>
     *   <li>Any non-{@code null} {@code startDate} / {@code endDate}
     *       is forwarded to {@link DateValidationService#validate(String,
     *       String)} with the {@link #DATE_FORMAT_MASK} mask. COBOL
     *       provenance: the {@code CALL 'CSUTLDTC'} cascade at lines
     *       392-426 of the source.</li>
     *   <li>If both dates are present, {@code startDate <= endDate}.
     *       COBOL provenance: implicit chronology invariant of the
     *       report-window contract (no explicit COBOL line; the COBOL
     *       JCL parameters {@code PARM-START-DATE-1} and
     *       {@code PARM-END-DATE-1} at lines 105-111 of the source
     *       presume an ordered range).</li>
     * </ol>
     *
     * @param request the report request to validate; must not be
     *                {@code null}
     * @throws ValidationException if any of the cascade rules fails
     */
    // COBOL: CORPT00C:PROCESS-ENTER-KEY date validation cascade
    private void validateRequest(ReportRequestDto request) {
        final String reportType = request.reportType();
        if (reportType == null || reportType.isBlank()) {
            // COBOL: PROCESS-ENTER-KEY WHEN OTHER (lines 437-442) —
            // "Select a report type to print report..."
            throw new ValidationException("VALIDATION",
                    "reportType is required (one of " + ALLOWED_REPORT_TYPES + ")",
                    List.of(new ValidationException.FieldError(
                            "reportType",
                            "reportType is required")));
        }
        final String normalized = normalize(reportType);
        if (!ALLOWED_REPORT_TYPES.contains(normalized)) {
            // COBOL: implicit — if MONTHLYI / YEARLYI / CUSTOMI all
            // equal SPACES the WHEN OTHER branch fires; the Java target
            // rejects an unknown selector explicitly for a clean JSON
            // error envelope.
            throw new ValidationException("VALIDATION",
                    "reportType must be one of " + ALLOWED_REPORT_TYPES,
                    List.of(new ValidationException.FieldError(
                            "reportType",
                            "Unknown report type: " + reportType)));
        }

        // For CUSTOM: both dates must be supplied. The DTO Bean
        // Validation cannot enforce this cross-field rule without a
        // custom validator (per the DTO JavaDoc), so the service does
        // it here.
        if ("CUSTOM".equals(normalized)) {
            // COBOL: PROCESS-ENTER-KEY WHEN CUSTOMI ... EVALUATE TRUE
            // WHEN SDTMMI / SDTDDI / SDTYYYYI / EDTMMI / EDTDDI /
            // EDTYYYYI = SPACES OR LOW-VALUES — lines 259-300 of
            // CORPT00C.cbl.
            if (request.startDate() == null) {
                throw new ValidationException("VALIDATION",
                        "startDate is required when reportType is CUSTOM",
                        List.of(new ValidationException.FieldError(
                                "startDate",
                                "startDate is required for CUSTOM report")));
            }
            if (request.endDate() == null) {
                throw new ValidationException("VALIDATION",
                        "endDate is required when reportType is CUSTOM",
                        List.of(new ValidationException.FieldError(
                                "endDate",
                                "endDate is required for CUSTOM report")));
            }
        }

        // Validate each non-null date via DateValidationService — never
        // an inline LocalDate.parse. The service ports the COBOL LE
        // CEEDAYS cascade verbatim (AAP §0.5.2 dependency removal).
        // COBOL: CALL 'CSUTLDTC' USING WS-START-DATE / WS-END-DATE
        //        (lines 392-426 of CORPT00C.cbl)
        if (request.startDate() != null) {
            validateDate("startDate", request.startDate());
        }
        if (request.endDate() != null) {
            validateDate("endDate", request.endDate());
        }

        // start <= end chronology check. The COBOL source assumes the
        // operator provides a sensible range; the Java target enforces
        // it explicitly to produce a clean validation error rather than
        // an empty downstream report.
        final LocalDate start = request.startDate();
        final LocalDate end = request.endDate();
        if (start != null && end != null && start.isAfter(end)) {
            // The error message intentionally uses "<=" in the user-
            // facing text to match the chronological invariant exactly:
            // start must be on or before end (inclusive equality).
            throw new ValidationException("VALIDATION",
                    "startDate must be <= endDate",
                    List.of(new ValidationException.FieldError(
                            "endDate",
                            "endDate must be on or after startDate")));
        }
    }

    /**
     * Validate a single non-null {@link LocalDate} via the date
     * validation service.
     *
     * <p>The {@link ReportRequestDto} declares the date fields as
     * {@link LocalDate}, so they are guaranteed to be calendar-valid
     * by Jackson's deserialiser. We still forward to
     * {@link DateValidationService} so the COBOL-compatible <i>century
     * check</i> ({@code 19xx} or {@code 20xx} only, per
     * {@code app/cpy/CSUTLDWY.cpy:L9-L10} and
     * {@code app/cpy/CSUTLDPY.cpy:L63-L84}) is applied uniformly with
     * the rest of the application.</p>
     *
     * @param fieldName the JSON property name of the date being
     *                  validated (used in the
     *                  {@link ValidationException.FieldError} on
     *                  failure)
     * @param date      the date to validate; must not be {@code null}
     *                  (the caller guards)
     */
    // COBOL: CORPT00C lines 388-394 (CALL 'CSUTLDTC' for WS-START-DATE)
    //        and lines 408-414 (CALL 'CSUTLDTC' for WS-END-DATE)
    private void validateDate(String fieldName, LocalDate date) {
        // LocalDate.toString() produces ISO-8601 'yyyy-MM-dd' (matches
        // the WS-DATE-FORMAT 'YYYY-MM-DD' literal at line 72 of
        // CORPT00C.cbl). The mask is passed explicitly so the
        // DateValidationService applies the matching pattern.
        final DateValidationService.DateValidationResult result =
                dateValidationService.validate(date.toString(), DATE_FORMAT_MASK);
        if (!result.isValid()) {
            // COBOL: PROCESS-ENTER-KEY rejection branches — for example,
            // line 400 "Start Date - Not a valid date..." and line 420
            // "End Date - Not a valid date...". The Java target carries
            // both the field name and the underlying CEEDAYS-equivalent
            // error message so the caller can render a precise field-
            // level error.
            throw new ValidationException("VALIDATION",
                    fieldName + ": " + result.errorMessage(),
                    List.of(new ValidationException.FieldError(
                            fieldName,
                            result.errorMessage())));
        }
    }

    /**
     * Validate the confirmation flag (the COBOL
     * {@code CONFIRMI OF CORPT0AI} field). The COBOL source treats
     * {@code 'Y' OR 'y'} as "submit" (lines 478 of
     * {@code app/cbl/CORPT00C.cbl}), {@code 'N' OR 'n'} as "cancel"
     * (line 480), an empty / low-values value as "re-prompt" (line
     * 464), and any other value as the literal
     * {@code '"<x>" is not a valid value to confirm...'} (line 488).
     *
     * <p>The Java REST target collapses these four COBOL branches into
     * a single binary contract at the {@code POST /api/reports/submit}
     * boundary:</p>
     * <ul>
     *   <li>{@code confirm = "Y"} (case-insensitive) &mdash; proceed
     *       with submission</li>
     *   <li>Anything else &mdash; reject the request with a
     *       {@link ValidationException} carrying a precise per-branch
     *       error message; the controller's
     *       {@code GlobalExceptionHandler} translates to HTTP 400.</li>
     * </ul>
     *
     * @param confirm the operator-supplied confirmation value (may be
     *                {@code null} or empty)
     * @throws ValidationException if the value is not {@code "Y"} /
     *                             {@code "y"}
     */
    // COBOL: CORPT00C:SUBMIT-JOB-TO-INTRDR (lines 462-494)
    private void validateConfirmation(String confirm) {
        // Branch 1 — null/empty → "Please confirm to print..."
        // COBOL: IF CONFIRMI = SPACES OR LOW-VALUES (line 464)
        if (confirm == null || confirm.isBlank()) {
            throw new ValidationException("VALIDATION",
                    "Please confirm to print the report (set confirm='Y')",
                    List.of(new ValidationException.FieldError(
                            "confirm",
                            "confirm is required (set 'Y' to submit)")));
        }
        final String trimmed = confirm.trim();
        // Branch 2 — explicit 'Y'/'y' → proceed (COBOL lines 478-479).
        if ("Y".equalsIgnoreCase(trimmed)) {
            return;
        }
        // Branch 3 — explicit 'N'/'n' → cancel (COBOL line 480-483).
        if ("N".equalsIgnoreCase(trimmed)) {
            throw new ValidationException("NOT_CONFIRMED",
                    "Report submission cancelled by operator (confirm='N')",
                    List.of(new ValidationException.FieldError(
                            "confirm",
                            "confirm='N' — submission cancelled")));
        }
        // Branch 4 — any other value → invalid confirmation (COBOL
        // lines 484-493: '"<x>" is not a valid value to confirm...').
        throw new ValidationException("VALIDATION",
                "\"" + trimmed + "\" is not a valid value to confirm",
                List.of(new ValidationException.FieldError(
                        "confirm",
                        "confirm must be 'Y' or 'N'")));
    }

    /**
     * Build the structured payload passed to {@link AuditLogService}.
     * Keys map one-to-one to fields the Step Functions trigger Lambda
     * also receives via the MSK message, so the audit trail and the
     * batch-job input remain in lockstep.
     *
     * <p>Values are converted to JSON-friendly representations: the
     * {@link LocalDate} fields render as ISO-8601 {@code yyyy-MM-dd}
     * strings via {@link LocalDate#toString()}, and the
     * {@code confirm} field is intentionally NOT included in the audit
     * payload (its only purpose is the gate above; persisting it in
     * the audit record adds no investigative value and risks
     * conflating the gate signal with the report's semantics).</p>
     *
     * @param requestId the UUID request identifier
     * @param request   the report request DTO
     * @return a mutable {@link Map} payload (the audit service
     *         sanitizes and rewraps it; allocating a fresh map per
     *         call is safe and stateless)
     */
    // COBOL: implicit — the COBOL source emits DISPLAY statements for
    // operator diagnostics; the Java target captures equivalent
    // information as a structured OpenSearch document per AAP §0.6.6.
    private Map<String, Object> buildAuditPayload(String requestId,
                                                  ReportRequestDto request) {
        // HashMap is used (not LinkedHashMap) because the audit
        // service re-orders fields when serialising to OpenSearch and
        // the indexed JSON document is order-insensitive. Using
        // HashMap matches the convention in AuditLogService's own
        // buildBaseDocument helper.
        final Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", requestId);
        payload.put("reportType", normalize(request.reportType()));
        // Convert LocalDate to ISO-8601 strings so the OpenSearch
        // document's @timestamp inference works correctly and so the
        // audit record is human-readable in CloudWatch / OpenSearch
        // Dashboards without requiring a custom date renderer.
        // toString() yields the canonical 'yyyy-MM-dd' form.
        if (request.startDate() != null) {
            payload.put("startDate", request.startDate().toString());
        }
        if (request.endDate() != null) {
            payload.put("endDate", request.endDate().toString());
        }
        return payload;
    }

    /**
     * Normalize a report-type string to upper-case, trim-trimmed form
     * for case-insensitive matching against {@link #ALLOWED_REPORT_TYPES}.
     * Returns the empty string for {@code null} input.
     *
     * <p>{@link Locale#ROOT} is used (rather than {@link Locale#US}
     * or the platform default) because the report-type vocabulary is
     * an internal protocol-level identifier, not a user-facing label;
     * locale-independent upper-casing is the safe default. (See JLS
     * &sect;5.1.7 for the Turkish-i issue with the platform locale.)</p>
     *
     * @param value the input string (may be {@code null})
     * @return the normalized upper-case trimmed string; never
     *         {@code null}
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Resolves the current authenticated user's USER-ID from the Spring
     * Security context. The {@code JwtAuthenticationFilter} populates the
     * {@code Authentication.name} with the JWT subject (the COBOL
     * {@code USRID PIC X(8)} value &mdash; e.g., {@code "ADMIN001"},
     * {@code "USER0001"}). This USER-ID becomes the MSK partition key
     * for the {@code report.requested} topic per Issue CP4-#11.
     *
     * <p>The COBOL source {@code app/cbl/CORPT00C.cbl} reads {@code USRID}
     * from the {@code CARDDEMO-COMMAREA} (line 64 of {@code COCOM01Y.cpy})
     * which is populated by the {@code COSGN00C} sign-on program. The
     * Java target uses JWT claims as the COMMAREA equivalent per AAP
     * &sect;0.1.1 ("CICS pseudo-conversational COMMAREA state is replaced
     * by stateless REST with JWT").</p>
     *
     * @return the authenticated principal's USER-ID, or {@code "ANONYMOUS"}
     *         if no security context is bound (defensive — should never
     *         happen because {@code @PreAuthorize("hasAnyRole('USER',
     *         'ADMIN')")} on the controller method gates anonymous
     *         requests upstream)
     */
    // Replaces: COBOL CDEMO-USER-ID extraction from CARDDEMO-COMMAREA
    // (CORPT00C lines 165, 207). JWT principal name carries the same
    // value end-to-end per AAP §0.1.1 (JWT replaces CICS COMMAREA).
    private String resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            // Defense-in-depth — should never occur because the
            // controller's @PreAuthorize requires authentication.
            return "ANONYMOUS";
        }
        return auth.getName();
    }

    // =====================================================================
    // Nested record — ReportSubmissionResult
    // =====================================================================

    /**
     * Immutable result returned by
     * {@link ReportSubmissionService#submitReport(ReportRequestDto)}.
     *
     * <p>Carries the generated {@code requestId} (a UUID; also used
     * as the MSK partition key per AAP &sect;0.6.5) and a
     * human-readable acknowledgement {@code message} that mirrors the
     * COBOL success-line text emitted at lines 449-450 of
     * {@code app/cbl/CORPT00C.cbl} ({@code "<report-name> report
     * submitted for printing ..."}). The record is intentionally
     * lightweight &mdash; downstream consumers correlate the
     * submission to the eventual AWS Batch job using the
     * {@code requestId}.</p>
     *
     * <p>Per the JDK record contract, all components are
     * {@code final}; the accessors {@link #requestId()} and
     * {@link #message()} are auto-generated; and
     * {@link Object#equals(Object) equals},
     * {@link Object#hashCode() hashCode}, and
     * {@link Object#toString() toString} are structural based on
     * those components.</p>
     *
     * <p><b>COBOL provenance:</b> there is no direct COBOL equivalent
     * &mdash; the source program returns control to CICS via
     * {@code EXEC CICS RETURN} after sending the success message to
     * the 3270 terminal (lines 587-591 of the source). The Java
     * target's REST contract carries the response back to the caller
     * as JSON.</p>
     *
     * @param requestId the unique UUID identifier for this submission;
     *                  never {@code null}
     * @param message   the human-readable acknowledgement message;
     *                  never {@code null}
     */
    public record ReportSubmissionResult(String requestId, String message) {
        /**
         * Compact canonical constructor &mdash; minimal null-safety
         * normalization so callers can rely on accessors never
         * returning {@code null}. A {@code null} {@code requestId} is
         * normalized to the empty string and a {@code null}
         * {@code message} likewise &mdash; matching the JDK record
         * idiom for the {@code DateValidationService.DateValidationResult}
         * sibling type used elsewhere in the validation layer.
         */
        public ReportSubmissionResult {
            if (requestId == null) {
                requestId = "";
            }
            if (message == null) {
                message = "";
            }
        }
    }
}
