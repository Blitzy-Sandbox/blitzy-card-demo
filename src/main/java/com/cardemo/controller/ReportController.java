/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService;

/**
 * Spring MVC REST controller providing report submission operations, replacing
 * CICS BMS screen CORPT00 and COBOL program CORPT00C.cbl (649 lines).
 *
 * <p>This controller is the <strong>online-to-batch bridge</strong> — it accepts report
 * criteria from the client and delegates to {@link ReportSubmissionService}, which
 * publishes to an SQS FIFO queue ({@code carddemo-report-jobs.fifo}) replacing the
 * original CICS Transient Data Queue (TDQ) WRITEQ TD QUEUE('JOBS'). The actual report
 * generation is handled asynchronously by Spring Batch jobs.</p>
 *
 * <h3>COBOL Program Coverage</h3>
 * <table>
 *   <caption>CORPT00C.cbl paragraph to REST endpoint mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Lines</th><th>Java Mapping</th></tr>
 *   <tr><td>MAIN-PARA</td><td>163-202</td>
 *       <td>Request routing — pseudo-conversational model to stateless REST</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>208-456</td>
 *       <td>{@link #submitReport(ReportRequest)} entry point</td></tr>
 *   <tr><td>SUBMIT-JOB-TO-INTRDR</td><td>462-510</td>
 *       <td>Confirmation check + service delegation</td></tr>
 *   <tr><td>WIRTE-JOBSUB-TDQ</td><td>515-535</td>
 *       <td>{@link ReportSubmissionService#submitReport(ReportRequest)} — SQS publish</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>540-551</td>
 *       <td>Cancellation response (HTTP 200 with message)</td></tr>
 *   <tr><td>SEND-TRNRPT-SCREEN</td><td>556-580</td>
 *       <td>JSON response body via ResponseEntity</td></tr>
 *   <tr><td>RECEIVE-TRNRPT-SCREEN</td><td>596-604</td>
 *       <td>{@code @RequestBody} JSON deserialization</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>609-628</td>
 *       <td>N/A — screen metadata not applicable to REST</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>633-646</td>
 *       <td>N/A — DTO statelessness eliminates field reset</td></tr>
 * </table>
 *
 * <h3>HTTP Status Codes</h3>
 * <ul>
 *   <li><strong>200 OK</strong> — Report submission cancelled (confirm = "N" or blank)</li>
 *   <li><strong>202 Accepted</strong> — Report queued for async batch processing</li>
 *   <li><strong>400 Bad Request</strong> — Validation failure (handled by ControllerAdvice)</li>
 *   <li><strong>500 Internal Server Error</strong> — SQS queue failure (handled by ControllerAdvice)</li>
 * </ul>
 *
 * <h3>Online-to-Batch Bridge Pattern</h3>
 * <p>In the original COBOL/CICS implementation:</p>
 * <ol>
 *   <li>User selects report type on BMS 3270 screen (monthly/yearly/custom)</li>
 *   <li>User enters date range for custom reports</li>
 *   <li>User confirms with ENTER (or cancels with PF3)</li>
 *   <li>COBOL writes JCL card images to CICS TDQ QUEUE('JOBS')</li>
 *   <li>JES batch scheduler picks up TDQ message and executes TRANREPT job</li>
 * </ol>
 * <p>In the Java REST migration (AAP §0.4.3 Observer Pattern):</p>
 * <ol>
 *   <li>Client sends POST /api/reports/submit with ReportRequest JSON</li>
 *   <li>Controller validates confirmation and delegates to service</li>
 *   <li>Service publishes JSON message to SQS FIFO queue</li>
 *   <li>Spring Batch listener receives message and executes report job</li>
 *   <li>Controller returns HTTP 202 with SQS message ID as confirmation</li>
 * </ol>
 *
 * <p>Source traceability: CORPT00C.cbl — CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see ReportSubmissionService
 * @see ReportRequest
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs report submission parameters at info level before delegating to the service,
     * supporting the AAP §0.7.1 observability requirement for structured logging
     * with correlation IDs propagated via MDC.
     */
    private static final Logger logger = LoggerFactory.getLogger(ReportController.class);

    /**
     * Report submission service implementing the online-to-batch bridge.
     * Validates report criteria, calculates date ranges, and publishes to SQS FIFO queue.
     * Injected via constructor injection (no @Autowired annotation per Spring best practices).
     */
    private final ReportSubmissionService reportSubmissionService;

    /**
     * Constructs a new ReportController with the required ReportSubmissionService dependency.
     *
     * <p>Uses Spring constructor injection, which is the recommended dependency injection
     * pattern. When a class has a single constructor, Spring automatically uses it for
     * injection without requiring the {@code @Autowired} annotation.</p>
     *
     * @param reportSubmissionService the service handling report criteria validation
     *                                 and SQS queue publishing; must not be {@code null}
     */
    public ReportController(ReportSubmissionService reportSubmissionService) {
        this.reportSubmissionService = reportSubmissionService;
    }

    /**
     * Submits report criteria for asynchronous batch processing via SQS queue.
     *
     * <p>This endpoint is the Java equivalent of COBOL CORPT00C.cbl paragraphs
     * PROCESS-ENTER-KEY (lines 208-456) and SUBMIT-JOB-TO-INTRDR (lines 462-510).
     * It preserves the online-to-batch bridge pattern where report generation is
     * decoupled from report request submission.</p>
     *
     * <h3>Confirmation Flow (COBOL CORPT00C.cbl lines 464-494)</h3>
     * <p>The COBOL program validates the CONFIRMI field before writing to TDQ:</p>
     * <ul>
     *   <li>If CONFIRMI is SPACES or LOW-VALUES → prompt user to confirm (error screen)</li>
     *   <li>If CONFIRMI = 'Y' or 'y' → proceed with TDQ WRITEQ</li>
     *   <li>If CONFIRMI = 'N' or 'n' → cancel submission, reset fields</li>
     *   <li>Otherwise → invalid confirmation value error</li>
     * </ul>
     * <p>In the REST migration, the cancellation check is handled at the controller level:
     * if {@code confirm} is {@code "N"}, {@code "n"}, {@code null}, or blank, the submission
     * is cancelled with HTTP 200. This mirrors COBOL's PF3/cancel flow
     * (RETURN-TO-PREV-SCREEN, lines 540-551).</p>
     *
     * <h3>HTTP Response Codes</h3>
     * <ul>
     *   <li><strong>200 OK</strong> — Submission cancelled (confirm is "N", null, or blank)</li>
     *   <li><strong>202 Accepted</strong> — Report criteria queued for async batch processing.
     *       The response body contains the SQS message ID as a confirmation reference.
     *       HTTP 202 (not 200 or 201) is architecturally significant because the report
     *       is not generated synchronously — it is queued for batch processing, preserving
     *       the COBOL online-to-batch bridge semantics.</li>
     *   <li><strong>400 Bad Request</strong> — Validation failure (invalid date range,
     *       no report type selected, start date after end date). Thrown as
     *       {@code ValidationException} by the service and handled by
     *       {@code @ControllerAdvice}.</li>
     *   <li><strong>500 Internal Server Error</strong> — SQS queue unavailable or publish
     *       failure. Thrown as {@code CardDemoException} by the service and handled by
     *       {@code @ControllerAdvice}.</li>
     * </ul>
     *
     * @param request the report criteria DTO containing report type (monthly/yearly/custom),
     *                optional date range (startDate/endDate), and confirmation indicator;
     *                validated via Jakarta Bean Validation ({@code @Valid}) triggering
     *                constraints defined on the DTO fields
     * @return {@code ResponseEntity<String>} with either:
     *         <ul>
     *           <li>HTTP 200 with cancellation message if submission is cancelled</li>
     *           <li>HTTP 202 with SQS message ID if submission is accepted for processing</li>
     *         </ul>
     */
    @PostMapping("/submit")
    public ResponseEntity<String> submitReport(@Valid @RequestBody ReportRequest request) {

        // Log report submission parameters for observability (AAP §0.7.1).
        // Correlation ID is automatically injected via MDC by CorrelationIdFilter.
        logger.info("Report submission received: monthly={} yearly={} custom={} confirm={}",
                request.isMonthly(), request.isYearly(), request.isCustom(),
                request.getConfirm());

        // Confirmation check — mirrors COBOL CORPT00C.cbl SUBMIT-JOB-TO-INTRDR
        // paragraph (lines 462-510). In COBOL, the confirmation field is checked
        // for 'Y'/'y' explicitly; any other value (SPACES, LOW-VALUES, 'N', etc.)
        // cancels the submission and reinitializes the screen fields.
        // The REST equivalent requires an explicit 'Y' to proceed, matching the
        // COBOL behavioral parity requirement.
        String confirm = request.getConfirm();
        if (!"Y".equalsIgnoreCase(confirm)) {
            logger.info("Report submission cancelled by user (confirm='{}')", confirm);
            return ResponseEntity.ok("Report submission cancelled");
        }

        // Delegate to ReportSubmissionService for report type validation,
        // date range calculation, and SQS FIFO queue publishing.
        // All business logic (date validation, report type mutual exclusivity,
        // SQS message construction) is in the service layer — controller contains
        // no business logic per architectural conventions.
        String messageId = reportSubmissionService.submitReport(request);

        logger.info("Report submitted successfully: messageId={} monthly={} yearly={} custom={}",
                messageId, request.isMonthly(), request.isYearly(), request.isCustom());

        // Return HTTP 202 Accepted — the report is not generated synchronously.
        // It is queued for batch processing via SQS, preserving the COBOL
        // online-to-batch bridge pattern (CORPT00C → CICS TDQ → JES batch).
        // The SQS message ID serves as a confirmation reference for the client.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(messageId);
    }
}
