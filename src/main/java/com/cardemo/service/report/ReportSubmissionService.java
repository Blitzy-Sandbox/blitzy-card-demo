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
package com.cardemo.service.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Spring service implementing the online-to-batch bridge, migrated from
 * COBOL program CORPT00C.cbl (649 lines).
 *
 * <p>In the original COBOL/CICS implementation, this program collected report
 * date criteria from a BMS 3270 screen (CORPT0A map), validated them, and then
 * submitted JCL records to the 'JOBS' extrapartition Transient Data Queue (TDQ)
 * via {@code EXEC CICS WRITEQ TD} for JES batch execution.</p>
 *
 * <p>In this Java migration, the JCL card image generation (1000 x 80 byte records)
 * is replaced by a single JSON message published to an SQS FIFO queue. The batch
 * report generation job picks up this message to execute the appropriate report.</p>
 *
 * <h3>Report Types Supported</h3>
 * <ul>
 *   <li><strong>Monthly</strong> — First day to last day of the current month</li>
 *   <li><strong>Yearly</strong> — January 1 to December 31 of the current year</li>
 *   <li><strong>Custom</strong> — User-specified start and end date range</li>
 * </ul>
 *
 * <h3>COBOL Paragraph Coverage</h3>
 * <table>
 *   <caption>COBOL-to-Java method mapping for CORPT00C.cbl</caption>
 *   <tr><th>COBOL Paragraph</th><th>Lines</th><th>Java Method</th><th>Notes</th></tr>
 *   <tr><td>MAIN-PARA</td><td>163-202</td><td>Controller + submitReport entry</td>
 *       <td>Pseudo-conversational model to stateless REST</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>208-456</td><td>{@link #submitReport}</td>
 *       <td>Report type EVALUATE TRUE to if/else chain</td></tr>
 *   <tr><td>SUBMIT-JOB-TO-INTRDR</td><td>462-510</td>
 *       <td>{@link #submitReport} + {@link #sendToSqs}</td>
 *       <td>Confirmation validation + JCL iteration to single SQS message</td></tr>
 *   <tr><td>WIRTE-JOBSUB-TDQ</td><td>515-535</td><td>{@link #sendToSqs}</td>
 *       <td>CICS WRITEQ TD QUEUE('JOBS') to SQS sendMessage</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>540-551</td><td>Controller routing</td>
 *       <td>XCTL to REST response</td></tr>
 *   <tr><td>SEND-TRNRPT-SCREEN</td><td>556-580</td><td>Controller response</td>
 *       <td>BMS SEND MAP to JSON response</td></tr>
 *   <tr><td>RECEIVE-TRNRPT-SCREEN</td><td>596-604</td><td>Controller RequestBody</td>
 *       <td>BMS RECEIVE MAP to JSON request</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>609-628</td><td>N/A</td>
 *       <td>Screen metadata not needed for REST</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>633-646</td><td>N/A</td>
 *       <td>DTO statelessness eliminates field reset</td></tr>
 * </table>
 *
 * <p>Source traceability: CORPT00C.cbl — CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see com.cardemo.service.shared.DateValidationService
 * @see com.cardemo.model.dto.ReportRequest
 */
@Service
public class ReportSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ReportSubmissionService.class);

    /**
     * Date formatter for SQS message payload serialization.
     * Formats LocalDate to 'yyyy-MM-dd' matching COBOL WS-DATE-FORMAT
     * (CORPT00C.cbl line 72).
     */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * SQS FIFO message group ID for report submissions.
     * All report jobs are placed in the same group to preserve FIFO ordering,
     * matching the TDQ 'JOBS' sequential write semantics (Decision D-004).
     */
    private static final String MESSAGE_GROUP_ID = "report-submissions";

    /**
     * Start position of the CEEDAYS message number in the 80-byte fullMessage.
     * Layout: severity(4) + "Mesg Code: "(11) = message number starts at index 15.
     */
    private static final int MSG_NUM_START = 15;

    /**
     * End position (exclusive) of the CEEDAYS message number in the 80-byte fullMessage.
     * Message number is a 4-character field: positions 15-18 (inclusive).
     */
    private static final int MSG_NUM_END = 19;

    /**
     * CEEDAYS message number 2513 is treated as an acceptable warning by COBOL
     * CORPT00C.cbl (lines 399 and 419). When this message number is returned,
     * the date is still considered valid despite a non-zero severity code.
     * This preserves the exact COBOL conditional:
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'}.
     */
    private static final String CEEDAYS_ACCEPTABLE_MSG = "2513";

    private final SqsClient sqsClient;
    private final DateValidationService dateValidationService;
    private final ObjectMapper objectMapper;

    /**
     * SQS queue name injected from application.yml property
     * {@code carddemo.aws.sqs.report-queue}. This is the queue NAME, not URL.
     * The actual URL is resolved at startup via {@link #resolveQueueUrl()}.
     * Configured per environment: LocalStack for local/test, real AWS for production.
     * No hardcoded queue URLs per AAP requirements.
     */
    @Value("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}")
    private String reportQueueName;

    /**
     * Resolved SQS queue URL. Populated by {@link #resolveQueueUrl()} during
     * bean initialization. This is the full URL required by the SQS SendMessage API,
     * resolved from the queue name via the SQS GetQueueUrl operation.
     */
    private String reportQueueUrl;

    /**
     * Constructs a new ReportSubmissionService with required dependencies.
     * All dependencies are injected via Spring constructor injection.
     *
     * @param sqsClient             AWS SQS client for message publishing (from AwsConfig bean)
     * @param dateValidationService date validation service replacing COBOL CALL 'CSUTLDTC'
     * @param objectMapper          Jackson object mapper for JSON serialization of SQS payloads
     */
    public ReportSubmissionService(SqsClient sqsClient,
                                   DateValidationService dateValidationService,
                                   ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.dateValidationService = dateValidationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the SQS queue URL from the configured queue name at bean initialization.
     *
     * <p>The SQS SendMessage API requires a full queue URL (e.g.,
     * {@code http://localhost:4566/000000000000/carddemo-report-jobs.fifo} for LocalStack
     * or {@code https://sqs.us-east-1.amazonaws.com/123456789012/carddemo-report-jobs.fifo}
     * for real AWS). Rather than hardcoding or manually constructing the URL, this method
     * uses the SQS {@code GetQueueUrl} API to resolve it from the queue name.</p>
     *
     * <p>This approach is self-resolving across environments: LocalStack, staging, and
     * production all return the correct URL for their respective endpoints.</p>
     *
     * <p>If the queue does not yet exist (e.g., during integration testing where the
     * queue is created in {@code @BeforeAll} after the Spring context loads), the
     * resolution is deferred to the first message send via {@link #ensureQueueUrl()}.</p>
     */
    @PostConstruct
    void resolveQueueUrl() {
        try {
            this.reportQueueUrl = sqsClient.getQueueUrl(
                    GetQueueUrlRequest.builder()
                            .queueName(reportQueueName)
                            .build()
            ).queueUrl();
            log.info("Resolved SQS queue URL for '{}': {}", reportQueueName, reportQueueUrl);
        } catch (SqsException e) {
            log.warn("SQS queue '{}' not available at startup — URL will be resolved lazily on first use. "
                    + "This is expected in test environments where the queue is created after context initialization. "
                    + "Error: {}", reportQueueName, e.getMessage());
        }
    }

    /**
     * Ensures the SQS queue URL is resolved before sending a message.
     * If the URL was not resolved during {@code @PostConstruct} (e.g., queue did not
     * exist at startup), it is resolved now.
     *
     * @return the resolved SQS queue URL
     * @throws CardDemoException if the queue URL cannot be resolved
     */
    private String ensureQueueUrl() {
        if (reportQueueUrl != null && !reportQueueUrl.isBlank()) {
            return reportQueueUrl;
        }
        try {
            this.reportQueueUrl = sqsClient.getQueueUrl(
                    GetQueueUrlRequest.builder()
                            .queueName(reportQueueName)
                            .build()
            ).queueUrl();
            log.info("Lazily resolved SQS queue URL for '{}': {}", reportQueueName, reportQueueUrl);
            return reportQueueUrl;
        } catch (SqsException e) {
            log.error("Failed to resolve SQS queue URL for '{}': {}",
                    reportQueueName, e.getMessage(), e);
            throw new CardDemoException(
                    "Failed to resolve SQS report queue URL for queue: " + reportQueueName);
        }
    }

    /**
     * Processes a report submission request. Determines report type (monthly/yearly/custom),
     * calculates date ranges, validates dates and confirmation, and publishes to SQS
     * for batch processing.
     *
     * <p>Equivalent to COBOL CORPT00C.cbl paragraphs:</p>
     * <ul>
     *   <li>PROCESS-ENTER-KEY (lines 208-456) — report type selection and date calculation</li>
     *   <li>SUBMIT-JOB-TO-INTRDR (lines 462-510) — confirmation validation and TDQ write</li>
     * </ul>
     *
     * <p>The COBOL {@code EVALUATE TRUE} on report type selection (line 212) maps to the
     * if/else chain below. Each branch calculates start/end dates and proceeds to
     * confirmation validation and SQS publish.</p>
     *
     * <p>Execution flow (matching COBOL exactly):</p>
     * <ol>
     *   <li>Determine report type and calculate date range (PROCESS-ENTER-KEY)</li>
     *   <li>For custom reports: validate date fields (lines 256-436)</li>
     *   <li>Validate confirmation indicator (SUBMIT-JOB-TO-INTRDR lines 464-494)</li>
     *   <li>Publish to SQS FIFO queue (WIRTE-JOBSUB-TDQ)</li>
     * </ol>
     *
     * @param request the report request containing type selection and optional custom dates
     * @return confirmation message string (e.g., "Monthly report submitted for printing ...")
     * @throws ValidationException if input validation fails (confirmation, dates, or report type)
     * @throws CardDemoException   if SQS publishing or JSON serialization fails
     */
    public String submitReport(ReportRequest request) {
        String reportType = determineReportType(request);
        log.info("Processing report submission request: type={}", reportType);

        // ─── PROCESS-ENTER-KEY EVALUATE TRUE (lines 212-443) ───────────
        // Determine report type and calculate date range
        String reportName;
        LocalDate startDate;
        LocalDate endDate;

        if (request.isMonthly()) {
            // Monthly report (COBOL lines 213-238)
            reportName = "Monthly";

            // When explicit startDate/endDate are provided alongside monthly=true,
            // the provided dates take precedence over the current-month defaults.
            // This enables requesting monthly reports for any specific month.
            if (request.getStartDate() != null && request.getEndDate() != null) {
                startDate = request.getStartDate();
                endDate = request.getEndDate();
            } else {
                // Default: use current month (FUNCTION CURRENT-DATE)
                LocalDate now = LocalDate.now();
                // Start date = first day of current month
                // COBOL line 219: MOVE '01' TO WS-START-DATE-DD
                startDate = now.withDayOfMonth(1);
                // End date = last day of current month
                // java.time.YearMonth.atEndOfMonth() provides identical semantics
                endDate = YearMonth.from(now).atEndOfMonth();
            }

        } else if (request.isYearly()) {
            // Yearly report (COBOL lines 239-255)
            reportName = "Yearly";

            // When explicit dates are provided alongside yearly=true,
            // the provided dates take precedence over the current-year defaults.
            if (request.getStartDate() != null && request.getEndDate() != null) {
                startDate = request.getStartDate();
                endDate = request.getEndDate();
            } else {
                LocalDate now = LocalDate.now();
                // Start date = January 1 of current year (lines 245-246)
                startDate = LocalDate.of(now.getYear(), 1, 1);
                // End date = December 31 of current year (lines 250-251)
                endDate = LocalDate.of(now.getYear(), 12, 31);
            }

        } else if (request.isCustom()) {
            // Custom report (COBOL lines 256-436)
            // Extensive field-by-field validation cascade, then CSUTLDTC date validation
            reportName = "Custom";
            validateCustomDates(request);
            startDate = request.getStartDate();
            endDate = request.getEndDate();

        } else {
            // WHEN OTHER (lines 437-443)
            // No report type selected — error
            throw new ValidationException(
                    "Select a report type to print report...");
        }

        // ─── SUBMIT-JOB-TO-INTRDR confirmation flow (lines 464-494) ───
        // Validates Y/N confirmation before submission
        validateConfirmation(request.getConfirm(), reportName);

        // ─── SQS publish replacing WIRTE-JOBSUB-TDQ (lines 515-535) ───
        // In COBOL, this iterates through up to 1000 JCL card images (80 bytes each)
        // and writes each to TDQ 'JOBS'. In Java, a single JSON message replaces all
        // JCL card images with structured report parameters.
        sendToSqs(reportName, startDate, endDate);

        // Post-submission success message (lines 445-456)
        // COBOL STRING: WS-REPORT-NAME DELIMITED BY SPACE
        //               ' report submitted for printing ...' DELIMITED BY SIZE
        // Since reportName has no trailing spaces, concatenation produces identical result
        log.info("{} report submitted for printing, startDate={}, endDate={}",
                reportName, startDate, endDate);
        return reportName + " report submitted for printing ...";
    }

    /**
     * Publishes report parameters to SQS FIFO queue for batch job pickup.
     *
     * <p>Replaces COBOL WIRTE-JOBSUB-TDQ paragraph (lines 515-535) and
     * the JCL card image iteration in SUBMIT-JOB-TO-INTRDR (lines 496-508).</p>
     *
     * <p>In COBOL, this wrote up to 1000 JCL card images (80 bytes each) to TDQ 'JOBS'
     * via {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}. In Java, a single JSON message
     * containing report parameters is published to the SQS FIFO queue. The batch
     * job extracts these parameters to execute the report.</p>
     *
     * <p>FIFO queue parameters:</p>
     * <ul>
     *   <li>{@code messageGroupId} = "report-submissions" — all report jobs in one
     *       ordered group, preserving TDQ sequential semantics (Decision D-004)</li>
     *   <li>{@code messageDeduplicationId} = UUID — ensures uniqueness per submission</li>
     * </ul>
     *
     * @param reportName the report type name (Monthly/Yearly/Custom)
     * @param startDate  the report start date
     * @param endDate    the report end date
     * @throws CardDemoException if SQS publish or JSON serialization fails
     */
    private void sendToSqs(String reportName, LocalDate startDate, LocalDate endDate) {
        // Build JSON message payload — replaces COBOL JOB-DATA JCL card image generation
        // (CORPT00C.cbl lines 81-128, 1000 x 80 bytes)
        Map<String, String> messagePayload = new HashMap<>();
        messagePayload.put("reportType", reportName);
        messagePayload.put("startDate", startDate.format(DATE_FORMAT));
        messagePayload.put("endDate", endDate.format(DATE_FORMAT));
        messagePayload.put("submittedAt", LocalDate.now().toString());

        String messageBody;
        try {
            messageBody = objectMapper.writeValueAsString(messagePayload);
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize report parameters: {}", e.getMessage(), e);
            throw new CardDemoException("Unable to serialize report parameters");
        }

        try {
            // Build SQS FIFO send request
            // messageGroupId ensures FIFO ordering within the report-submissions group
            // messageDeduplicationId ensures each submission is unique (required for FIFO queues)
            SendMessageRequest sendRequest = SendMessageRequest.builder()
                    .queueUrl(ensureQueueUrl())
                    .messageBody(messageBody)
                    .messageGroupId(MESSAGE_GROUP_ID)
                    .messageDeduplicationId(UUID.randomUUID().toString())
                    .build();

            sqsClient.sendMessage(sendRequest);
            log.debug("SQS message published successfully to queue: {}", reportQueueUrl);

        } catch (SqsException e) {
            // Preserves COBOL error text from WIRTE-JOBSUB-TDQ (lines 531-532):
            // MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE
            // Maps RESP(WS-RESP-CD) error path to SqsException handling
            log.error("Unable to Write TDQ (JOBS)... SQS error: {}", e.getMessage(), e);
            throw new CardDemoException(
                    "Unable to Write TDQ (JOBS)...",
                    "SQS_WRITE_ERROR",
                    null,
                    e);
        }
    }

    /**
     * Validates the confirmation indicator from the report request.
     *
     * <p>Equivalent to COBOL SUBMIT-JOB-TO-INTRDR paragraph confirmation flow
     * (lines 464-494). The COBOL BMS field CONFIRMI is PIC X(1).</p>
     *
     * <p>Validation rules (preserving COBOL behavior exactly):</p>
     * <ul>
     *   <li>Empty/null confirmation — "Please confirm to print the {name} report..."</li>
     *   <li>'Y' or 'y' — proceed (valid confirmation)</li>
     *   <li>'N' or 'n' — cancellation (report not submitted)</li>
     *   <li>Any other value — '"{value}" is not a valid value to confirm...'</li>
     * </ul>
     *
     * @param confirm    the confirmation indicator string (expected "Y" or "N")
     * @param reportName the report type name for error message construction
     * @throws ValidationException if confirmation is missing, declined, or invalid
     */
    private void validateConfirmation(String confirm, String reportName) {
        // Check for empty confirmation (COBOL lines 464-474)
        // COBOL: IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES
        if (confirm == null || confirm.isBlank()) {
            throw new ValidationException(
                    "Please confirm to print the " + reportName + " report...");
        }

        // EVALUATE TRUE on confirm value (COBOL lines 477-494)
        String trimmedConfirm = confirm.trim();
        if ("Y".equalsIgnoreCase(trimmedConfirm)) {
            // 'Y' or 'y' — proceed with submission (COBOL line 479: CONTINUE)
            return;
        }

        if ("N".equalsIgnoreCase(trimmedConfirm)) {
            // 'N' or 'n' — cancel submission (COBOL lines 480-483)
            // In COBOL, this performs INITIALIZE-ALL-FIELDS and sets WS-ERR-FLG
            throw new ValidationException("Report submission cancelled by user");
        }

        // OTHER — invalid confirmation value (COBOL lines 484-493)
        // COBOL STRING: '"' DELIMITED BY SIZE
        //               CONFIRMI OF CORPT0AI DELIMITED BY SPACE
        //               '" is not a valid value to confirm...' DELIMITED BY SIZE
        // For PIC X(1), DELIMITED BY SPACE gives the character itself (non-space)
        String displayValue = trimmedConfirm.contains(" ")
                ? trimmedConfirm.substring(0, trimmedConfirm.indexOf(' '))
                : trimmedConfirm;
        throw new ValidationException(
                "\"" + displayValue + "\" is not a valid value to confirm...");
    }

    /**
     * Validates custom date range inputs for the custom report type.
     *
     * <p>Equivalent to COBOL CORPT00C.cbl lines 256-436 (custom report date
     * validation within PROCESS-ENTER-KEY):</p>
     * <ol>
     *   <li>Null/missing checks for start and end dates (lines 258-300)</li>
     *   <li>Date validity via DateValidationService/CEEDAYS (lines 388-426)</li>
     *   <li>Date range check: start must not be after end</li>
     * </ol>
     *
     * <p>In COBOL, individual month/day/year fields (SDTMMI, SDTDDI, SDTYYYYI,
     * EDTMMI, EDTDDI, EDTYYYYI) are checked separately. Since the Java DTO uses
     * {@link java.time.LocalDate}, a null startDate covers all three sub-field
     * empty checks, and LocalDate parsing handles numeric and range validation.
     * The CEEDAYS validation via {@link DateValidationService#validateWithCeedays}
     * provides an additional layer of date validity checking.</p>
     *
     * @param request the report request containing custom date range
     * @throws ValidationException with field-specific error messages matching COBOL exactly
     */
    private void validateCustomDates(ReportRequest request) {
        // Null checks for start date (maps COBOL empty field checks lines 259-279)
        // In COBOL, checks are: start month empty, start day empty, start year empty
        // With LocalDate, a null startDate covers all three sub-field checks
        if (request.getStartDate() == null) {
            throw new ValidationException(
                    "Start Date - Month can NOT be empty...");
        }

        // Null checks for end date (maps COBOL empty field checks lines 280-300)
        // In COBOL, checks are: end month empty, end day empty, end year empty
        if (request.getEndDate() == null) {
            throw new ValidationException(
                    "End Date - Month can NOT be empty...");
        }

        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();

        // Date validity via DateValidationService (maps CALL 'CSUTLDTC' lines 388-406)
        // COBOL: CALL 'CSUTLDTC' USING CSUTLDTC-PARM (start date)
        //        IF CSUTLDTC-RESULT-SEV-CD NOT = '0000'
        //            IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
        //                MOVE 'Start Date - Not a valid date...' TO WS-MESSAGE
        String startDateStr = startDate.format(DATE_FORMAT);
        DateValidationResult startResult =
                dateValidationService.validateWithCeedays(startDateStr, "YYYY-MM-DD");

        if (startResult.severityCode() != 0) {
            // Check if message number is the acceptable 2513 warning
            // COBOL preserves dates with msg-num 2513 as valid
            String msgNum = extractMessageNumber(startResult.fullMessage());
            if (!CEEDAYS_ACCEPTABLE_MSG.equals(msgNum)) {
                throw new ValidationException(
                        "Start Date - Not a valid date...");
            }
        }

        // End date validity via DateValidationService (maps CALL 'CSUTLDTC' lines 408-426)
        // COBOL: CALL 'CSUTLDTC' USING CSUTLDTC-PARM (end date)
        //        IF CSUTLDTC-RESULT-SEV-CD NOT = '0000'
        //            IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
        //                MOVE 'End Date - Not a valid date...' TO WS-MESSAGE
        String endDateStr = endDate.format(DATE_FORMAT);
        DateValidationResult endResult =
                dateValidationService.validateWithCeedays(endDateStr, "YYYY-MM-DD");

        if (endResult.severityCode() != 0) {
            String msgNum = extractMessageNumber(endResult.fullMessage());
            if (!CEEDAYS_ACCEPTABLE_MSG.equals(msgNum)) {
                throw new ValidationException(
                        "End Date - Not a valid date...");
            }
        }

        // Range check: start date must be before or equal to end date
        // This is an implicit validation from the COBOL date range semantics
        if (startDate.isAfter(endDate)) {
            throw new ValidationException(
                    "Start Date must be before End Date");
        }

        log.debug("Custom date range validated: {} to {}", startDate, endDate);
    }

    /**
     * Determines the report type name from the request flags.
     *
     * <p>Maps the boolean flags in {@link ReportRequest} to human-readable report
     * type names matching COBOL WS-REPORT-NAME values (PIC X(10)).</p>
     *
     * @param request the report request
     * @return "Monthly", "Yearly", "Custom", or "Unknown"
     */
    private String determineReportType(ReportRequest request) {
        if (request.isMonthly()) {
            return "Monthly";
        }
        if (request.isYearly()) {
            return "Yearly";
        }
        if (request.isCustom()) {
            return "Custom";
        }
        return "Unknown";
    }

    /**
     * Extracts the 4-digit CEEDAYS message number from the 80-byte formatted
     * fullMessage string produced by {@link DateValidationService}.
     *
     * <p>The fullMessage layout follows the COBOL WS-MESSAGE structure from
     * CSUTLDTC.cbl, where the message number occupies bytes 15-18 (0-indexed):
     * severity(4) + "Mesg Code: "(11) = msgNo starts at position 15.</p>
     *
     * @param fullMessage the 80-byte formatted CEEDAYS result message
     * @return the 4-character message number string, or empty string if extraction fails
     */
    private static String extractMessageNumber(String fullMessage) {
        if (fullMessage != null && fullMessage.length() >= MSG_NUM_END) {
            return fullMessage.substring(MSG_NUM_START, MSG_NUM_END);
        }
        return "";
    }
}
