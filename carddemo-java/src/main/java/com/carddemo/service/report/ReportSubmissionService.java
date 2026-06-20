package com.carddemo.service.report;

import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.ReportRequest;
import com.carddemo.model.dto.ReportResponse;
import com.carddemo.service.shared.DateValidationService;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Online-to-batch report-submission bridge. Translated from {@code app/cbl/CORPT00C.cbl}
 * (CICS transaction {@code CR00}; CardDemo source commit {@code 27d6c6f}; COBOL not copied).
 *
 * <p>The original program builds a transaction-report request from the {@code CORPT00} BMS
 * screen and submits a JCL job to the internal reader through the CICS extra-partition
 * transient-data queue ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}). This service reproduces
 * that flow as a stateless request&rarr;response: it selects the report type, derives the
 * reporting date range, validates user-entered custom dates, gates on an explicit
 * confirmation, and publishes a single report-job message to the SQS FIFO queue that
 * replaces the TDQ. The downstream Spring Batch transaction-report job consumes the message.
 *
 * <p>Behavior mirrors the COBOL exactly and is <strong>fail-fast</strong>: the source sends an
 * error screen and returns from the transaction on the first validation failure, so each check
 * here throws {@link ValidationException} on the first failure, in the original evaluation order
 * ({@code PROCESS-ENTER-KEY} then {@code SUBMIT-JOB-TO-INTRDR}). No conversational state is held;
 * the COBOL {@code CARDDEMO-COMMAREA} is not reproduced as server state.
 *
 * <p>The published message is a byte-stable external contract: a JSON object
 * {@code {"reportType":"Monthly|Yearly|Custom","startDate":"yyyy-MM-dd","endDate":"yyyy-MM-dd"}}
 * produced from the nested {@link ReportJobMessage} record. The report-type literals match the
 * COBOL {@code WS-REPORT-NAME} values.
 */
@Service
public class ReportSubmissionService {

    /** Logger used to record successful publishes and publish failures (correlation IDs come from the MDC filter). */
    private static final Logger log = LoggerFactory.getLogger(ReportSubmissionService.class);

    /** Constant FIFO message group id, preserving the single point-to-point ordering of the source TDQ. */
    private static final String REPORT_MESSAGE_GROUP_ID = "carddemo-reports";

    /** COBOL {@code WS-DATE-FORMAT} picture passed to the date-validation service ({@code CORPT00C} L72). */
    private static final String COBOL_DATE_FORMAT = "YYYY-MM-DD";

    /** Report-type contract literal for the current-month report ({@code WS-REPORT-NAME} = 'Monthly'). */
    private static final String REPORT_TYPE_MONTHLY = "Monthly";

    /** Report-type contract literal for the current-year report ({@code WS-REPORT-NAME} = 'Yearly'). */
    private static final String REPORT_TYPE_YEARLY = "Yearly";

    /** Report-type contract literal for the user-supplied range report ({@code WS-REPORT-NAME} = 'Custom'). */
    private static final String REPORT_TYPE_CUSTOM = "Custom";

    /** Upper calendar bound applied to a month field, matching the COBOL {@code > '12'} check. */
    private static final int MAX_MONTH = 12;

    /** Upper calendar bound applied to a day field, matching the COBOL {@code > '31'} check. */
    private static final int MAX_DAY = 31;

    /** Date-validation collaborator replacing the {@code CSUTLDTC} subprogram call. */
    private final DateValidationService dateValidationService;

    /** Auto-configured SQS operations template used to publish report-job messages. */
    private final SqsTemplate sqsTemplate;

    /** Target SQS FIFO queue name (the TDQ replacement), resolved from configuration. */
    private final String reportQueueName;

    /**
     * Creates the service with its injected collaborators and the configured FIFO queue name.
     *
     * @param dateValidationService the date-validation service ({@code CSUTLDTC} replacement)
     * @param sqsTemplate           the auto-configured SQS template (backed by the {@code SqsAsyncClient} bean)
     * @param reportQueueName       the report-job FIFO queue name, defaulting to {@code carddemo-report-jobs.fifo}
     */
    public ReportSubmissionService(
            DateValidationService dateValidationService,
            SqsTemplate sqsTemplate,
            @Value("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}") String reportQueueName) {
        this.dateValidationService = dateValidationService;
        this.sqsTemplate = sqsTemplate;
        this.reportQueueName = reportQueueName;
    }

    /**
     * Submits a transaction-report request, reproducing {@code CORPT00C}'s {@code PROCESS-ENTER-KEY}
     * and {@code SUBMIT-JOB-TO-INTRDR} logic.
     *
     * <p>Selection order is Monthly, then Yearly, then Custom, then "no type selected". Monthly and
     * Yearly ranges are system-generated and skip date validation; the Custom range is validated
     * field-by-field. After the range is established the confirmation flag is gated, and only an
     * affirmative confirmation publishes the report-job message.
     *
     * @param request the report-submission request (type flags, custom date parts, confirmation flag)
     * @return a {@link ReportResponse} carrying the submission acknowledgement on success, or a
     *         neutral cancellation acknowledgement when the user declines confirmation
     * @throws ValidationException when no type is selected, a custom date is invalid, or the
     *                             confirmation value is missing or not recognised
     * @throws FileAccessException when the SQS publish fails (the COBOL {@code WRITEQ TD} error path)
     */
    public ReportResponse submitReport(ReportRequest request) {
        String reportType;
        String startDate;
        String endDate;

        if (isSelected(request.monthly())) {
            reportType = REPORT_TYPE_MONTHLY;
            LocalDate today = LocalDate.now();
            startDate = today.withDayOfMonth(1).toString();
            endDate = today.with(TemporalAdjusters.lastDayOfMonth()).toString();
        } else if (isSelected(request.yearly())) {
            reportType = REPORT_TYPE_YEARLY;
            int year = LocalDate.now().getYear();
            startDate = LocalDate.of(year, 1, 1).toString();
            endDate = LocalDate.of(year, 12, 31).toString();
        } else if (isSelected(request.custom())) {
            reportType = REPORT_TYPE_CUSTOM;
            String[] range = validateCustomRange(request);
            startDate = range[0];
            endDate = range[1];
        } else {
            throw new ValidationException("Select a report type to print report...");
        }

        return confirmAndSubmit(request, reportType, startDate, endDate);
    }

    /**
     * Reports whether a single-character selection/flag field is set, mirroring the COBOL
     * {@code NOT = SPACES AND LOW-VALUES} test.
     *
     * @param value the flag value
     * @return {@code true} when the value is non-null and not blank
     */
    private static boolean isSelected(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Validates the user-entered custom date range and returns the assembled start and end dates,
     * reproducing the {@code WHEN CUSTOMI} cascade of {@code CORPT00C} ({@code L256-436}).
     *
     * <p>Checks run strictly in order and fail fast: the six empty checks, then the per-field
     * numeric and range checks (month {@code > 12}, day {@code > 31}, year numeric only), then the
     * date-service validation of the assembled start and end dates. No lower-bound or start&le;end
     * cross-field check is added, matching the source.
     *
     * @param request the report-submission request supplying the split date parts
     * @return a two-element array {@code [startDate, endDate]} as {@code yyyy-MM-dd} strings
     * @throws ValidationException on the first failing check, with the COBOL message text
     */
    private String[] validateCustomRange(ReportRequest request) {
        requireNotBlank(request.startMonth(), "Start Date - Month can NOT be empty...");
        requireNotBlank(request.startDay(), "Start Date - Day can NOT be empty...");
        requireNotBlank(request.startYear(), "Start Date - Year can NOT be empty...");
        requireNotBlank(request.endMonth(), "End Date - Month can NOT be empty...");
        requireNotBlank(request.endDay(), "End Date - Day can NOT be empty...");
        requireNotBlank(request.endYear(), "End Date - Year can NOT be empty...");

        int startMonth = parseBoundedPart(request.startMonth(), MAX_MONTH, "Start Date - Not a valid Month...");
        int startDay = parseBoundedPart(request.startDay(), MAX_DAY, "Start Date - Not a valid Day...");
        int startYear = parseYearPart(request.startYear(), "Start Date - Not a valid Year...");
        int endMonth = parseBoundedPart(request.endMonth(), MAX_MONTH, "End Date - Not a valid Month...");
        int endDay = parseBoundedPart(request.endDay(), MAX_DAY, "End Date - Not a valid Day...");
        int endYear = parseYearPart(request.endYear(), "End Date - Not a valid Year...");

        String startDate = String.format("%04d-%02d-%02d", startYear, startMonth, startDay);
        String endDate = String.format("%04d-%02d-%02d", endYear, endMonth, endDay);

        if (!dateValidationService.validateDate(startDate, COBOL_DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("Start Date - Not a valid date...");
        }
        if (!dateValidationService.validateDate(endDate, COBOL_DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("End Date - Not a valid date...");
        }

        return new String[] {startDate, endDate};
    }

    /**
     * Throws when a custom date part is missing, mirroring the COBOL {@code = SPACES OR LOW-VALUES}
     * empty checks.
     *
     * @param value   the date part
     * @param message the COBOL "can NOT be empty" message to raise when blank
     * @throws ValidationException when the value is null or blank
     */
    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(message);
        }
    }

    /**
     * Parses a month or day part to an integer and enforces its upper calendar bound, mirroring the
     * COBOL {@code NUMVAL-C} conversion followed by the {@code IS NOT NUMERIC OR > 'nn'} check.
     *
     * @param value      the trimmed-then-parsed date part
     * @param upperBound the inclusive maximum permitted value ({@link #MAX_MONTH} or {@link #MAX_DAY})
     * @param message    the COBOL "Not a valid ..." message to raise on failure
     * @return the parsed integer value
     * @throws ValidationException when the value is non-numeric or exceeds {@code upperBound}
     */
    private static int parseBoundedPart(String value, int upperBound, String message) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException(message);
        }
        if (parsed > upperBound) {
            throw new ValidationException(message);
        }
        return parsed;
    }

    /**
     * Parses a year part to an integer, mirroring the COBOL {@code NUMVAL-C} conversion followed by
     * the {@code IS NOT NUMERIC} check. The source applies no range bound to the year here.
     *
     * @param value   the trimmed-then-parsed year part
     * @param message the COBOL "Not a valid Year" message to raise on failure
     * @return the parsed integer value
     * @throws ValidationException when the value is non-numeric
     */
    private static int parseYearPart(String value, String message) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException(message);
        }
    }

    /**
     * Applies the confirmation gate ({@code SUBMIT-JOB-TO-INTRDR}, {@code L462-510}) and submits the
     * report job when confirmed.
     *
     * <p>A missing confirmation raises the COBOL "Please confirm" prompt; an affirmative {@code Y}/{@code y}
     * publishes the job; a negative {@code N}/{@code n} cancels without publishing; any other value is
     * rejected as an invalid confirmation.
     *
     * @param request    the report-submission request supplying the confirmation flag
     * @param reportType  the resolved report-type literal
     * @param startDate   the resolved start date ({@code yyyy-MM-dd})
     * @param endDate     the resolved end date ({@code yyyy-MM-dd})
     * @return the submission acknowledgement, or a neutral cancellation acknowledgement on decline
     * @throws ValidationException when the confirmation is missing or unrecognised
     * @throws FileAccessException when the SQS publish fails
     */
    private ReportResponse confirmAndSubmit(ReportRequest request, String reportType, String startDate, String endDate) {
        String confirm = request.confirm();
        if (confirm == null || confirm.isBlank()) {
            throw new ValidationException("Please confirm to print the " + reportType + " report...");
        }
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            return publishReportJob(reportType, startDate, endDate);
        }
        if ("N".equals(confirm) || "n".equals(confirm)) {
            log.info("{} report submission declined by user; no message published.", reportType);
            return new ReportResponse("Report request cancelled.", null);
        }
        throw new ValidationException("\"" + confirm + "\" is not a valid value to confirm...");
    }

    /**
     * Publishes a single report-job message to the SQS FIFO queue, reproducing the COBOL
     * {@code WIRTE-JOBSUB-TDQ} write ({@code L515-535}). A constant message group id preserves the
     * total ordering of the single source TDQ; a random deduplication id keeps each submission
     * distinct.
     *
     * @param reportType the report-type literal carried in the message
     * @param startDate  the start date carried in the message ({@code yyyy-MM-dd})
     * @param endDate    the end date carried in the message ({@code yyyy-MM-dd})
     * @return the submission acknowledgement response
     * @throws FileAccessException when the publish fails (the COBOL non-NORMAL {@code RESP} path)
     */
    private ReportResponse publishReportJob(String reportType, String startDate, String endDate) {
        ReportJobMessage message = new ReportJobMessage(reportType, startDate, endDate);
        try {
            sqsTemplate.<ReportJobMessage>send(options -> options
                    .queue(reportQueueName)
                    .payload(message)
                    .messageGroupId(REPORT_MESSAGE_GROUP_ID)
                    .messageDeduplicationId(UUID.randomUUID().toString()));
        } catch (RuntimeException ex) {
            log.error("Unable to publish {} report job to SQS queue {}: {}", reportType, reportQueueName, ex.getMessage());
            throw new FileAccessException("Unable to Write TDQ (JOBS)...", ex);
        }
        log.info("Submitted {} report job to SQS queue {} for range {}..{}", reportType, reportQueueName, startDate, endDate);
        return new ReportResponse(reportType + " report submitted for printing ...", null);
    }

    /**
     * Byte-stable report-job message published to the SQS FIFO queue (the TDQ payload). Serialized to
     * JSON as {@code {"reportType":...,"startDate":...,"endDate":...}} and consumed by the downstream
     * transaction-report batch job. Field names and the report-type literals must not change.
     *
     * @param reportType the report-type literal ({@code "Monthly"}, {@code "Yearly"}, or {@code "Custom"})
     * @param startDate  the inclusive range start as {@code yyyy-MM-dd}
     * @param endDate    the inclusive range end as {@code yyyy-MM-dd}
     */
    public record ReportJobMessage(String reportType, String startDate, String endDate) {
    }
}
