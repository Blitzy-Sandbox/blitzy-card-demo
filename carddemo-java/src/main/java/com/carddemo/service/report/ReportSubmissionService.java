package com.carddemo.service.report;

import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.ReportRequest;
import com.carddemo.model.dto.ReportResponse;
import com.carddemo.service.shared.DateValidationService;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Online-to-batch report-submission bridge.
 *
 * <p>Translated from {@code app/cbl/CORPT00C.cbl} (CardDemo SHA {@code 27d6c6f}); the COBOL is a
 * behavioral reference and is not copied. The original CICS program (transaction {@code CR00})
 * builds transaction-report parameters from the {@code CORPT00} screen and submits a batch job by
 * writing JCL to the extra-partition transient-data queue {@code JOBS} ({@code WRITEQ TD}). This
 * service performs the equivalent online-to-batch hand-off by publishing a single report-request
 * message to the SQS FIFO queue {@code carddemo-report-jobs.fifo}, which the downstream Spring
 * Batch transaction-report job consumes.</p>
 *
 * <p>The component is stateless: each call to {@link #submitReport(ReportRequest)} is a
 * self-contained request/response with no conversational state, so the COBOL
 * {@code CARDDEMO-COMMAREA} is not reproduced as server state.</p>
 */
@Service
public class ReportSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ReportSubmissionService.class);

    /** Report-type literal published on the message contract and echoed in user messages. */
    private static final String REPORT_TYPE_MONTHLY = "Monthly";

    /** Report-type literal published on the message contract and echoed in user messages. */
    private static final String REPORT_TYPE_YEARLY = "Yearly";

    /** Report-type literal published on the message contract and echoed in user messages. */
    private static final String REPORT_TYPE_CUSTOM = "Custom";

    /** Constant FIFO message group id (rationale: DECISION_LOG D-015). */
    private static final String REPORT_MESSAGE_GROUP_ID = "carddemo-reports";

    /** COBOL date picture ({@code WS-DATE-FORMAT}, CORPT00C:L72) passed to the date validator. */
    private static final String COBOL_DATE_FORMAT = "YYYY-MM-DD";

    /** Inclusive upper bound for a custom-range month part (COBOL rejects {@code > '12'}). */
    private static final int MAX_MONTH = 12;

    /** Inclusive upper bound for a custom-range day part (COBOL rejects {@code > '31'}). */
    private static final int MAX_DAY = 31;

    private final DateValidationService dateValidationService;
    private final SqsTemplate sqsTemplate;
    private final String reportQueueName;

    /**
     * Creates the service with its collaborators and the destination queue name.
     *
     * @param dateValidationService validates assembled custom-range dates (replaces {@code CSUTLDTC})
     * @param sqsTemplate           publishes the report-request message to SQS (replaces {@code WRITEQ TD})
     * @param reportQueueName       FIFO queue name, defaulting to {@code carddemo-report-jobs.fifo}
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
     * Report-request message published to the SQS FIFO queue and consumed by the downstream batch
     * report job. Serialized to JSON as
     * {@code {"reportType":"Monthly|Yearly|Custom","startDate":"yyyy-MM-dd","endDate":"yyyy-MM-dd"}};
     * this is a stable external contract, so the field names and value formats must not change.
     *
     * @param reportType one of {@code "Monthly"}, {@code "Yearly"}, or {@code "Custom"}
     * @param startDate  inclusive range start, formatted {@code yyyy-MM-dd}
     * @param endDate    inclusive range end, formatted {@code yyyy-MM-dd}
     */
    public record ReportJobMessage(String reportType, String startDate, String endDate) {
    }

    /**
     * Submits a transaction report for batch processing, reproducing the
     * {@code PROCESS-ENTER-KEY} and {@code SUBMIT-JOB-TO-INTRDR} flow of {@code CORPT00C}.
     *
     * <p>The report type is selected in the order monthly, yearly, custom; the date range is
     * derived (monthly and yearly) or validated (custom) before the confirmation flag is checked.
     * The first failed edit throws, mirroring the COBOL transaction-terminating screen send. On a
     * {@code Y}/{@code y} confirmation a single message is published; an {@code N}/{@code n}
     * confirmation cancels without publishing.</p>
     *
     * @param request the report selection and confirmation input
     * @return a success acknowledgement, or a cancellation acknowledgement when declined
     * @throws ValidationException when no type is selected, a custom date is invalid, or the
     *                             confirmation flag is missing or not recognised
     * @throws FileAccessException when the SQS publish fails (the {@code WRITEQ TD} failure path)
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
            String[] range = deriveCustomDates(request);
            startDate = range[0];
            endDate = range[1];
        } else {
            throw new ValidationException("Select a report type to print report...");
        }

        if (!isConfirmed(request.confirm(), reportType)) {
            return new ReportResponse("Report request cancelled.", null);
        }

        return publishReportJob(reportType, startDate, endDate);
    }

    /**
     * Reports whether a single-character selection flag is set, equivalent to the COBOL
     * {@code NOT = SPACES AND LOW-VALUES} test.
     *
     * @param value the flag value
     * @return {@code true} when the value is non-null and not blank
     */
    private boolean isSelected(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Validates the custom date-range parts and returns the assembled {@code yyyy-MM-dd} start and
     * end dates, reproducing the custom cascade of {@code PROCESS-ENTER-KEY} (CORPT00C:L256-436):
     * six empty-field edits, six numeric/range edits, string assembly, then a date-service check of
     * each assembled date. The date-service tolerates the COBOL {@code 2513} (unsupported-range)
     * outcome via {@link DateValidationService.DateValidationResult#isAcceptable()}.
     *
     * @param request the report input carrying the split date parts
     * @return a two-element array of {@code [startDate, endDate]}
     * @throws ValidationException on the first failed edit
     */
    private String[] deriveCustomDates(ReportRequest request) {
        requireNotBlank(request.startMonth(), "Start Date - Month can NOT be empty...");
        requireNotBlank(request.startDay(), "Start Date - Day can NOT be empty...");
        requireNotBlank(request.startYear(), "Start Date - Year can NOT be empty...");
        requireNotBlank(request.endMonth(), "End Date - Month can NOT be empty...");
        requireNotBlank(request.endDay(), "End Date - Day can NOT be empty...");
        requireNotBlank(request.endYear(), "End Date - Year can NOT be empty...");

        int startMonth = parseBounded(request.startMonth(), MAX_MONTH, "Start Date - Not a valid Month...");
        int startDay = parseBounded(request.startDay(), MAX_DAY, "Start Date - Not a valid Day...");
        int startYear = parseNumeric(request.startYear(), "Start Date - Not a valid Year...");
        int endMonth = parseBounded(request.endMonth(), MAX_MONTH, "End Date - Not a valid Month...");
        int endDay = parseBounded(request.endDay(), MAX_DAY, "End Date - Not a valid Day...");
        int endYear = parseNumeric(request.endYear(), "End Date - Not a valid Year...");

        String startDate = String.format(Locale.ROOT, "%04d-%02d-%02d", startYear, startMonth, startDay);
        String endDate = String.format(Locale.ROOT, "%04d-%02d-%02d", endYear, endMonth, endDay);

        if (!dateValidationService.validateDate(startDate, COBOL_DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("Start Date - Not a valid date...");
        }
        if (!dateValidationService.validateDate(endDate, COBOL_DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("End Date - Not a valid date...");
        }

        return new String[] {startDate, endDate};
    }

    /**
     * Throws a {@link ValidationException} carrying {@code message} when {@code value} is null or
     * blank (the COBOL {@code = SPACES OR LOW-VALUES} empty edit).
     *
     * @param value   the date part to check
     * @param message the verbatim COBOL error message for the empty case
     */
    private void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(message);
        }
    }

    /**
     * Parses a trimmed numeric date part and rejects values above {@code upperBound}, reproducing
     * the COBOL {@code IS NOT NUMERIC OR > 'nn'} edit. Values at or below the bound (including zero)
     * pass here and are rejected, when impossible, by the later date-service check.
     *
     * @param value      the date part to parse
     * @param upperBound the inclusive maximum accepted value
     * @param message    the verbatim COBOL error message for the invalid case
     * @return the parsed value
     * @throws ValidationException when the value is not numeric or exceeds {@code upperBound}
     */
    private int parseBounded(String value, int upperBound, String message) {
        int parsed = parseNumeric(value, message);
        if (parsed > upperBound) {
            throw new ValidationException(message);
        }
        return parsed;
    }

    /**
     * Parses a trimmed numeric date part, throwing {@code message} when it is not numeric (the
     * COBOL {@code IS NOT NUMERIC} edit).
     *
     * @param value   the date part to parse
     * @param message the verbatim COBOL error message for the non-numeric case
     * @return the parsed value
     * @throws ValidationException when the value cannot be parsed as an integer
     */
    private int parseNumeric(String value, String message) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException(message);
        }
    }

    /**
     * Evaluates the confirmation flag, reproducing the {@code SUBMIT-JOB-TO-INTRDR} gate
     * (CORPT00C:L462-510): a missing flag or an unrecognised value throws; {@code Y}/{@code y}
     * proceeds to publish; {@code N}/{@code n} cancels.
     *
     * @param confirm    the confirmation flag value
     * @param reportType the resolved report type, named in the missing-confirmation message
     * @return {@code true} to publish ({@code Y}/{@code y}); {@code false} to cancel ({@code N}/{@code n})
     * @throws ValidationException when the flag is missing or is not {@code Y}/{@code y}/{@code N}/{@code n}
     */
    private boolean isConfirmed(String confirm, String reportType) {
        if (confirm == null || confirm.isBlank()) {
            throw new ValidationException("Please confirm to print the " + reportType + " report...");
        }
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            return true;
        }
        if ("N".equals(confirm) || "n".equals(confirm)) {
            return false;
        }
        throw new ValidationException("\"" + confirm + "\" is not a valid value to confirm...");
    }

    /**
     * Publishes a single report-request message to the SQS FIFO queue (the {@code WRITEQ TD}
     * bridge) and returns the success acknowledgement. Sends with the constant message group id
     * and a random deduplication id; see DECISION_LOG D-004 and D-015 for the message-contract
     * rationale.
     *
     * @param reportType the resolved report type
     * @param startDate  inclusive range start, {@code yyyy-MM-dd}
     * @param endDate    inclusive range end, {@code yyyy-MM-dd}
     * @return the success acknowledgement response
     * @throws FileAccessException when the publish fails
     */
    private ReportResponse publishReportJob(String reportType, String startDate, String endDate) {
        ReportJobMessage message = new ReportJobMessage(reportType, startDate, endDate);
        try {
            sqsTemplate.send(options -> options
                    .queue(reportQueueName)
                    .payload(message)
                    .messageGroupId(REPORT_MESSAGE_GROUP_ID)
                    .messageDeduplicationId(UUID.randomUUID().toString()));
            log.info("Submitted {} report job to SQS queue {} for range {}..{}",
                    reportType, reportQueueName, startDate, endDate);
        } catch (RuntimeException ex) {
            log.error("Unable to submit {} report job to SQS queue {}", reportType, reportQueueName, ex);
            throw new FileAccessException("Unable to Write TDQ (JOBS)...", ex);
        }
        return new ReportResponse(reportType + " report submitted for printing ...", null);
    }
}
