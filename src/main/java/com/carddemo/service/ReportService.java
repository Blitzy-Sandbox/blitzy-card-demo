package com.carddemo.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsTemplate;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.exception.ValidationException;

/**
 * Launches transaction-report batch jobs from the online tier, the Java 25 /
 * Spring Boot translation of COBOL program {@code CORPT00C} (CICS transaction
 * {@code CR00}, "Transaction Reports"). Frozen COBOL reference SHA {@code 27d6c6f}.
 *
 * <h2>Legacy behaviour being migrated</h2>
 * On the mainframe, {@code CORPT00C} lets an operator request a Monthly, Yearly,
 * or Custom transaction report from a 3270 screen. It derives the reporting
 * window, requires a {@code Y}/{@code N} confirmation, then builds a JCL job
 * stream and submits it to JES by writing to a CICS <em>extra-partition
 * Transient Data Queue</em> ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}, paragraph
 * {@code WIRTE-JOBSUB-TDQ}). Per the migration architecture the order-preserving,
 * fire-and-forget TDQ&nbsp;&rarr;&nbsp;JES bridge becomes an <strong>SQS FIFO</strong>
 * queue: this service sends one message to {@code carddemo-report-jobs.fifo}
 * carrying the report window, which triggers the Spring Batch
 * {@code TransactionReportJob}. The rationale and alternatives for the
 * TDQ&nbsp;&rarr;&nbsp;SQS FIFO decision are recorded in
 * {@code docs/decision-log.md}, not inline here (Explainability rule).
 *
 * <h2>Report-window derivation (paragraph {@code PROCESS-ENTER-KEY})</h2>
 * <ul>
 *   <li><strong>MONTHLY</strong> &mdash; the current calendar month: first day
 *       through the last day of the month (leap-year aware), mirroring the COBOL
 *       {@code FUNCTION CURRENT-DATE} / end-of-month computation.</li>
 *   <li><strong>YEARLY</strong> &mdash; the current calendar year:
 *       January&nbsp;1 through December&nbsp;31.</li>
 *   <li><strong>CUSTOM</strong> &mdash; a caller-supplied window. Both dates are
 *       mandatory, each is validated with {@link DateValidationService} (the Java
 *       replacement for the {@code CALL 'CSUTLDTC'} performed twice at
 *       {@code CORPT00C} L392/L412), and the start must not be after the end.</li>
 * </ul>
 *
 * <h2>Confirmation gate (paragraph {@code SUBMIT-JOB-TO-INTRDR})</h2>
 * The legacy program refuses to submit until the operator confirms. In the
 * stateless REST model the pseudo-conversational {@code COMMAREA} confirm flag
 * becomes {@link ReportRequest#confirm()}: when it is {@code null} or
 * {@code false} the service returns a {@code PENDING_CONFIRMATION}
 * acknowledgement carrying the confirm prompt and enqueues <em>nothing</em>.
 *
 * <h2>Batch-trigger contract (Gate&nbsp;5)</h2>
 * The SQS message body is the external batch-trigger contract and is modelled by
 * the stable {@link ReportJobMessage} record. Its field names and ISO-8601 date
 * encoding must remain stable across releases; the date encoding
 * ({@link LocalDate#toString()}, {@code YYYY-MM-DD}) is byte-identical to the
 * legacy {@code PARM-START-DATE}/{@code PARM-END-DATE} format.
 *
 * <h2>Observability, safety, and threading</h2>
 * The service logs via SLF4J; the {@code correlationId} MDC value established by
 * the request filter is included automatically. Only the non-sensitive
 * {@code jobId} (and, at debug level, the SQS message id) is logged &mdash; never
 * any queue credential. No monetary arithmetic occurs here, so no
 * {@code float}/{@code double} appears. The bean is stateless and therefore
 * thread-safe; the SQS queue name is externally configured and never hardcoded.
 *
 * @see DateValidationService
 * @see ReportRequest
 * @see ReportResponse
 */
@Service
public class ReportService {

    /** Logger; logs only the non-sensitive {@code jobId} / message id, never credentials. */
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    // ---------------------------------------------------------------------
    // Report-type selector tokens (normalized, upper-cased form of
    // ReportRequest.reportType()). Kept as compile-time constants so they can
    // serve as switch case labels.
    // ---------------------------------------------------------------------

    /** Normalized selector for the current-month report. */
    private static final String TYPE_MONTHLY = "MONTHLY";

    /** Normalized selector for the current-year report. */
    private static final String TYPE_YEARLY = "YEARLY";

    /** Normalized selector for the caller-supplied date-range report. */
    private static final String TYPE_CUSTOM = "CUSTOM";

    // ---------------------------------------------------------------------
    // Human-readable report names — reproduced verbatim from CORPT00C's
    // WS-REPORT-NAME literals so downstream text stays byte-identical.
    // ---------------------------------------------------------------------

    /** {@code CORPT00C} {@code WS-REPORT-NAME} literal for the monthly report. */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    /** {@code CORPT00C} {@code WS-REPORT-NAME} literal for the yearly report. */
    private static final String REPORT_NAME_YEARLY = "Yearly";

    /** {@code CORPT00C} {@code WS-REPORT-NAME} literal for the custom report. */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    // ---------------------------------------------------------------------
    // Response / message literals. The confirm-prompt and submitted-message
    // fragments reproduce the CORPT00C STRING constructions (L465-470, L449-452).
    // ---------------------------------------------------------------------

    /** {@link ReportResponse#status()} value when submission awaits confirmation. */
    private static final String STATUS_PENDING_CONFIRMATION = "PENDING_CONFIRMATION";

    /** Leading fragment of the CORPT00C confirm prompt (L465-470). */
    private static final String MSG_CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /** Trailing fragment of the CORPT00C confirm prompt (L465-470). */
    private static final String MSG_CONFIRM_PROMPT_SUFFIX = " report...";

    /** Trailing fragment of the CORPT00C submission-confirmation message (L449-452). */
    private static final String MSG_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /** CORPT00C literal for an unrecognized / unselected report type (L438). */
    private static final String MSG_UNKNOWN_REPORT_TYPE = "Select a report type to print report...";

    /**
     * Missing custom start date. Follows the CORPT00C
     * "Start Date - ... can NOT be empty..." family (L261-278); the split
     * month/day/year screen fields collapse to a single presence check because
     * the DTO carries a whole {@link LocalDate}.
     */
    private static final String MSG_START_DATE_REQUIRED = "Start Date - can NOT be empty...";

    /** Missing custom end date; see {@link #MSG_START_DATE_REQUIRED} (L280-300). */
    private static final String MSG_END_DATE_REQUIRED = "End Date - can NOT be empty...";

    /**
     * Start-after-end failure. CORPT00C has no exact literal for this ordering
     * rule (it validated each date independently via {@code CSUTLDTC}); the
     * message is a deliberate, decision-logged addition (start&nbsp;&le;&nbsp;end).
     */
    private static final String MSG_START_AFTER_END = "Start Date must not be after End Date...";

    /** Caller-facing field label used when validating the custom start date. */
    private static final String FIELD_START_DATE = "Start Date";

    /** Caller-facing field label used when validating the custom end date. */
    private static final String FIELD_END_DATE = "End Date";

    // ---------------------------------------------------------------------
    // SQS FIFO delivery constants.
    // ---------------------------------------------------------------------

    /**
     * Stable FIFO message-group id. All report submissions share one ordered
     * group, preserving the strictly ordered, single-consumer delivery of the
     * legacy extra-partition TDQ. FIFO queues require a message-group id.
     */
    private static final String MESSAGE_GROUP_ID = "carddemo-reports";

    /**
     * Formatter producing the {@code CCYYMMDD} key form (e.g. {@code 20250115})
     * expected by {@link DateValidationService}, standing in for the
     * {@code CSUTLDTC} date argument.
     */
    private static final DateTimeFormatter DATE_KEY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * Spring Cloud AWS SQS operations template (auto-configured against LocalStack
     * in local/test; never instantiated here). Used to send the FIFO trigger message.
     */
    private final SqsTemplate sqsTemplate;

    /** Date validator, the Java replacement for the COBOL {@code CSUTLDTC} call. */
    private final DateValidationService dateValidationService;

    /**
     * Target FIFO queue name, injected from configuration with a safe default so
     * no queue name or credential is hardcoded.
     */
    private final String reportQueue;

    /**
     * Creates the report-launch service with its collaborators injected by the
     * Spring container (constructor injection).
     *
     * @param sqsTemplate           the SQS operations template used to enqueue the
     *                              FIFO report-trigger message; must not be {@code null}
     * @param dateValidationService the {@code CSUTLDTC}-equivalent date validator
     *                              used for the CUSTOM report window; must not be
     *                              {@code null}
     * @param reportQueue           the FIFO report-queue name, bound from
     *                              {@code carddemo.aws.sqs.report-queue} (defaulting
     *                              to {@code carddemo-report-jobs.fifo})
     */
    public ReportService(
            SqsTemplate sqsTemplate,
            DateValidationService dateValidationService,
            @Value("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}") String reportQueue) {
        this.sqsTemplate = sqsTemplate;
        this.dateValidationService = dateValidationService;
        this.reportQueue = reportQueue;
    }

    /**
     * Resolves the reporting window, applies the confirmation gate, and (once
     * confirmed) enqueues the transaction-report batch job onto the SQS FIFO
     * queue. This is the migration of {@code CORPT00C}'s {@code PROCESS-ENTER-KEY}
     * plus {@code SUBMIT-JOB-TO-INTRDR} paragraphs.
     *
     * <p>Control flow, preserved from the source (goal G3):</p>
     * <ol>
     *   <li>Resolve the report type in the legacy branch order &mdash; MONTHLY,
     *       YEARLY, CUSTOM &mdash; with an explicit default that rejects an
     *       unknown type ({@link ValidationException}).</li>
     *   <li>Apply the confirmation gate: when {@link ReportRequest#confirm()} is
     *       {@code null} or {@code false}, return a {@code PENDING_CONFIRMATION}
     *       acknowledgement and enqueue nothing.</li>
     *   <li>Generate a {@code jobId}, enqueue exactly one FIFO message, and return
     *       an {@code ACCEPTED} acknowledgement.</li>
     * </ol>
     *
     * @param request the validated report request (report type plus, for CUSTOM,
     *                the start/end window and the confirm flag); must not be
     *                {@code null}
     * @return an {@link ReportResponse}: {@code ACCEPTED} with a non-null
     *         {@code jobId} once enqueued, or {@code PENDING_CONFIRMATION} with a
     *         {@code null} {@code jobId} when confirmation is still required
     * @throws ValidationException if the report type is unknown, or a CUSTOM
     *                             window is missing/invalid or has the start after
     *                             the end (HTTP 400)
     */
    public ReportResponse generateReport(ReportRequest request) {
        final String requestedType = request.reportType();
        final String normalizedType =
                (requestedType == null) ? "" : requestedType.trim().toUpperCase(Locale.ROOT);

        // Resolved reporting window; assigned exactly once per branch (or the
        // branch throws), matching CORPT00C's PROCESS-ENTER-KEY EVALUATE order.
        final String reportName;
        final LocalDate startDate;
        final LocalDate endDate;

        switch (normalizedType) {
            case TYPE_MONTHLY -> {
                // Current month: first day -> last day (leap-year aware).
                reportName = REPORT_NAME_MONTHLY;
                startDate = LocalDate.now().withDayOfMonth(1);
                endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
            }
            case TYPE_YEARLY -> {
                // Current year: Jan 1 -> Dec 31.
                reportName = REPORT_NAME_YEARLY;
                final int year = LocalDate.now().getYear();
                startDate = LocalDate.of(year, 1, 1);
                endDate = LocalDate.of(year, 12, 31);
            }
            case TYPE_CUSTOM -> {
                reportName = REPORT_NAME_CUSTOM;
                final LocalDate requestedStart = request.startDate();
                final LocalDate requestedEnd = request.endDate();
                if (requestedStart == null) {
                    throw new ValidationException(MSG_START_DATE_REQUIRED);
                }
                if (requestedEnd == null) {
                    throw new ValidationException(MSG_END_DATE_REQUIRED);
                }
                // Faithful CSUTLDTC re-validation of each date (CORPT00C L392, L412).
                this.dateValidationService.validateDateCcyyMmDd(
                        requestedStart.format(DATE_KEY_FORMAT), FIELD_START_DATE);
                this.dateValidationService.validateDateCcyyMmDd(
                        requestedEnd.format(DATE_KEY_FORMAT), FIELD_END_DATE);
                // Ordering rule (start <= end): a decision-logged addition.
                if (requestedStart.isAfter(requestedEnd)) {
                    throw new ValidationException(MSG_START_AFTER_END);
                }
                startDate = requestedStart;
                endDate = requestedEnd;
            }
            default -> throw new ValidationException(MSG_UNKNOWN_REPORT_TYPE);
        }

        // Confirmation gate (SUBMIT-JOB-TO-INTRDR, L464-474): no confirmation, no submit.
        if (request.confirm() == null || !request.confirm()) {
            final String confirmPrompt = MSG_CONFIRM_PROMPT_PREFIX + reportName + MSG_CONFIRM_PROMPT_SUFFIX;
            log.debug("Report '{}' awaiting confirmation; nothing enqueued", reportName);
            return new ReportResponse(
                    null,
                    STATUS_PENDING_CONFIRMATION,
                    requestedType,
                    startDate,
                    endDate,
                    reportName,
                    confirmPrompt);
        }

        // Submission (WIRTE-JOBSUB-TDQ): a single FIFO message triggers the batch job.
        final String jobId = UUID.randomUUID().toString();
        enqueueReportJob(jobId, reportName, startDate, endDate);

        final String submittedMessage = reportName + MSG_SUBMITTED_SUFFIX;
        return ReportResponse.accepted(jobId, requestedType, startDate, endDate, reportName, submittedMessage);
    }

    /**
     * Sends exactly one report-trigger message to the SQS FIFO queue. This is the
     * migration of {@code CORPT00C}'s TDQ write ({@code WIRTE-JOBSUB-TDQ}): instead
     * of writing a multi-line JCL stream, a single structured {@link ReportJobMessage}
     * is enqueued with a stable message-group id (FIFO ordering) and the
     * {@code jobId} as the deduplication id (each submission is unique, so no
     * content-based deduplication is required).
     *
     * @param jobId      the generated, unique job identifier and FIFO deduplication id
     * @param reportName the human-readable report name ({@code Monthly}/{@code Yearly}/{@code Custom})
     * @param startDate  inclusive reporting-window start
     * @param endDate    inclusive reporting-window end
     */
    private void enqueueReportJob(String jobId, String reportName, LocalDate startDate, LocalDate endDate) {
        final ReportJobMessage message =
                new ReportJobMessage(jobId, reportName, startDate.toString(), endDate.toString());

        final SendResult<ReportJobMessage> sendResult = this.sqsTemplate.send(options -> options
                .queue(this.reportQueue)
                .payload(message)
                .messageGroupId(MESSAGE_GROUP_ID)
                .messageDeduplicationId(jobId));

        log.info("Enqueued '{}' transaction report job [jobId={}] to SQS FIFO queue [{}]",
                reportName, jobId, this.reportQueue);
        if (sendResult != null && log.isDebugEnabled()) {
            log.debug("Report job [jobId={}] accepted by SQS [messageId={}]", jobId, sendResult.messageId());
        }
    }

    /**
     * Immutable SQS FIFO message body that triggers the Spring Batch
     * {@code TransactionReportJob} &mdash; the modern equivalent of the JCL job
     * stream {@code CORPT00C} wrote to the CICS Transient Data Queue.
     *
     * <p><strong>This record is an external batch-trigger contract (Gate&nbsp;5).</strong>
     * Its field names and encodings must remain stable across releases so the
     * report consumer keeps parsing successfully. Dates are ISO-8601
     * ({@code YYYY-MM-DD}) strings, byte-identical to the legacy
     * {@code PARM-START-DATE}/{@code PARM-END-DATE} format; using strings (rather
     * than {@link LocalDate}) keeps the serialized JSON schema explicit and
     * independent of any JSON date-format configuration.</p>
     *
     * @param jobId      unique job/correlation identifier for the enqueued report
     * @param reportName human-readable report name ({@code Monthly}/{@code Yearly}/{@code Custom})
     * @param startDate  inclusive reporting-window start, ISO-8601 {@code YYYY-MM-DD}
     * @param endDate    inclusive reporting-window end, ISO-8601 {@code YYYY-MM-DD}
     */
    public record ReportJobMessage(String jobId, String reportName, String startDate, String endDate) {
    }
}
