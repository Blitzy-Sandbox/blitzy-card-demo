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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Report-submission service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/CORPT00C.cbl} (CICS transaction id {@code CR00}).
 *
 * <p>This service is the <b>sole online-to-batch bridge</b> in the
 * source code base per AAP &sect;0.1.1. In the COBOL source,
 * {@code CORPT00C.cbl} validates the operator's monthly / yearly /
 * custom date inputs, assembles a JCL job-submission record, and writes
 * it to the CICS Transient Data Queue ({@code TDQ}) named {@code 'JOBS'}
 * via {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}. The JES2 reader picks
 * the JCL up from the spool and submits the report batch job.</p>
 *
 * <p>In the Java target, this entire flow is replaced by publishing a
 * {@code report.requested} MSK Kafka event. The
 * {@code KafkaEventConsumer} listens on that topic and calls
 * {@code StepFunctionsOrchestrator.startExecution} which in turn
 * submits the AWS Batch job equivalent of the original JCL
 * (per AAP &sect;0.1.1 &quot;The sole online-to-batch bridge (CORPT00C
 * → CICS TDQ JOBS queue → JES submission) translates to an MSK topic
 * (`report.requested`) consumed by a Step Functions trigger&quot;).</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CORPT00C.cbl} (CICS TRANID
 *       {@code 'CR00'}, TDQ {@code 'JOBS'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/CORPT00.bms} (mapset
 *       {@code CORPT00}, map {@code CORPT0A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/CORPT00.CPY}.</li>
 *   <li><b>Downstream JCL replaced:</b> {@code app/jcl/TRANREPT.jcl}
 *       (transaction report) and related batch jobs &mdash; now
 *       orchestrated by the {@code report-pipeline} Step Functions
 *       state machine on receipt of the MSK event.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CORPT00C.cbl &harr; ReportSubmissionService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS} (validate monthly / yearly /
 *       custom dates)</td>
 *       <td>{@link #validate(ReportRequestDto)}</td></tr>
 *   <tr><td>{@code WRITE-JOBSUB-TDQ} ({@code EXEC CICS WRITEQ TD
 *       QUEUE('JOBS') FROM(JCL-RECORD)})</td>
 *       <td>{@link KafkaEventPublisher#publishReportRequested(String,
 *       ReportRequestDto)} &mdash; published to MSK topic
 *       {@code report.requested} (partition key = reportId)</td></tr>
 * </table>
 *
 * <h2>Confirmation gate</h2>
 *
 * <p>The COBOL source displays the assembled inputs for operator
 * confirmation before writing to the JOBS TDQ; the Java target
 * preserves this via {@link ReportRequestDto#confirm()} which must
 * be {@code "Y"} for the publish to occur.</p>
 *
 * @see KafkaEventPublisher
 * @see ReportRequestDto
 */
@Service
public class ReportSubmissionService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportSubmissionService.class);

    /**
     * Allowed report types &mdash; case-insensitive. The COBOL source's
     * BMS map exposes three radio-button-like selections (monthly,
     * yearly, custom) plus a custom date range. The Java target accepts
     * the same three values as a string component on the DTO.
     */
    private static final List<String> ALLOWED_TYPES = List.of("MONTHLY", "YEARLY", "CUSTOM");

    /** ISO-8601 format used for the report-id timestamp suffix. */
    private static final DateTimeFormatter REPORT_ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private final KafkaEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;
    private final DateValidationService dateValidationService;

    public ReportSubmissionService(KafkaEventPublisher kafkaEventPublisher,
                                   AuditLogService auditLogService,
                                   DateValidationService dateValidationService) {
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
        this.dateValidationService = Objects.requireNonNull(dateValidationService,
                "dateValidationService");
    }

    /**
     * Submit a report request by publishing the equivalent MSK event.
     *
     * @param request the report request DTO carrying type, dates, and
     *                {@code confirm = "Y"}
     * @return the generated report ID (also used as the MSK partition
     *         key). Format: {@code "RPT-" + reportType + "-" +
     *         yyyyMMdd'T'HHmmss + "-" + 8-char UUID}.
     * @throws ValidationException if the request is malformed
     */
    @Transactional
    public String submitReport(ReportRequestDto request) {
        Objects.requireNonNull(request, "request");
        validate(request);

        if (!"Y".equalsIgnoreCase(request.confirm())) {
            throw new ValidationException(
                    "NOT_CONFIRMED",
                    "Operator did not confirm the report submission (confirm must be 'Y')",
                    List.of(new ValidationException.FieldError(
                            "confirm", "Set confirm='Y' to submit the report")));
        }

        // Generate a deterministic, unique report ID used as both the
        // Kafka partition key and the cross-system correlation identifier.
        String normalizedType = request.reportType().trim().toUpperCase(Locale.US);
        String reportId = "RPT-"
                + normalizedType + "-"
                + LocalDate.now().atStartOfDay().format(REPORT_ID_FORMATTER) + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        // ---- COBOL WRITE-JOBSUB-TDQ → MSK publish ----------------------
        // The KafkaEventConsumer listens for report.requested events and
        // triggers Step Functions which submits the AWS Batch job
        // equivalent of the legacy JCL (AAP §0.1.1 — sole online-to-batch
        // bridge). Partition key = reportId so per-report ordering is
        // preserved (AAP §0.6.5).
        kafkaEventPublisher.publishReportRequested(reportId, request);

        // ---- Audit -----------------------------------------------------
        Map<String, Object> payload = new HashMap<>();
        payload.put("reportId", reportId);
        payload.put("reportType", normalizedType);
        payload.put("startDate", request.startDate());
        payload.put("endDate", request.endDate());
        auditLogService.auditEvent("report.submitted", "system", payload);

        LOG.info("ReportSubmissionService: published report.requested reportId={} type={} start={} end={}",
                reportId, normalizedType, request.startDate(), request.endDate());

        return reportId;
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    private void validate(ReportRequestDto request) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        if (request.reportType() == null || request.reportType().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "reportType", "reportType is required"));
        } else {
            String normalized = request.reportType().trim().toUpperCase(Locale.US);
            if (!ALLOWED_TYPES.contains(normalized)) {
                errors.add(new ValidationException.FieldError(
                        "reportType",
                        "reportType must be one of MONTHLY, YEARLY, CUSTOM"));
            } else if ("CUSTOM".equals(normalized)) {
                // Custom range requires both startDate and endDate.
                if (request.startDate() == null) {
                    errors.add(new ValidationException.FieldError(
                            "startDate",
                            "startDate is required when reportType is CUSTOM"));
                }
                if (request.endDate() == null) {
                    errors.add(new ValidationException.FieldError(
                            "endDate",
                            "endDate is required when reportType is CUSTOM"));
                }
            }
        }

        // COBOL-equivalent date validation per CSUTLDPY.cpy + CSUTLDTC.cbl.
        if (request.startDate() != null) {
            DateValidationService.DateValidationResult r =
                    dateValidationService.validate(request.startDate().toString());
            if (!r.isValid()) {
                errors.add(new ValidationException.FieldError(
                        "startDate", r.errorMessage()));
            }
        }
        if (request.endDate() != null) {
            DateValidationService.DateValidationResult r =
                    dateValidationService.validate(request.endDate().toString());
            if (!r.isValid()) {
                errors.add(new ValidationException.FieldError(
                        "endDate", r.errorMessage()));
            }
        }

        if (request.startDate() != null && request.endDate() != null
                && request.endDate().isBefore(request.startDate())) {
            errors.add(new ValidationException.FieldError(
                    "endDate", "endDate must be on or after startDate"));
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(
                    "VALIDATION_FAILED",
                    "Report request contains invalid fields",
                    errors);
        }
    }
}
