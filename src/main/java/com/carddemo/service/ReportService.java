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
package com.carddemo.service;

import com.carddemo.config.AwsConfig;
import com.carddemo.dto.ReportDto;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Report-submission service backing {@code POST /api/reports/submit}, replacing
 * CICS transaction {@code CR00} / program {@code app/cbl/CORPT00C.cbl} at source
 * commit SHA {@code 27d6c6f}.
 *
 * <p>This service realises the <strong>F-011 asynchronous submit-then-process
 * bridge</strong>. The legacy program builds a JCL stream and writes it to the
 * CICS extra-partition Transient Data Queue {@code JOBS} (paragraphs
 * {@code SUBMIT-JOB-TO-INTRDR} and {@code WIRTE-JOBSUB-TDQ}) so that an internal
 * reader triggers the transaction-report batch job. The target preserves the
 * observable submit-then-process semantics while discarding the mainframe
 * mechanics: instead of assembling JCL and writing to a TDQ, it publishes a
 * single typed request message to the SQS FIFO queue
 * {@code carddemo-report-jobs.fifo}, whose name is resolved from
 * {@link AwsConfig.AwsResourceProperties} and never hardcoded.</p>
 *
 * <p>The {@code PROCESS-ENTER-KEY} paragraph is realised by
 * {@link #submitReport(ReportDto.SubmitRequest)}, which preserves the legacy
 * control flow exactly:</p>
 * <ol>
 *   <li><strong>Report-type selection</strong> &mdash; the {@code EVALUATE TRUE}
 *       construct is preserved with its original precedence
 *       (monthly, then yearly, then custom); when none is chosen the request is
 *       rejected with the program's exact message.</li>
 *   <li><strong>Date-range computation</strong> &mdash; the monthly range spans
 *       the first to the last day of the current month, the yearly range spans
 *       1&nbsp;January to 31&nbsp;December of the current year, and the custom
 *       range is built from the six user-supplied segments after the program's
 *       ordered empty, segment-range, and assembled-date checks (the latter via
 *       {@link DateValidationService}, which encapsulates the {@code CSUTLDTC}
 *       date-validation contract from {@code CSUTLDPY}).</li>
 *   <li><strong>Confirmation gate</strong> &mdash; the {@code SUBMIT-JOB-TO-INTRDR}
 *       confirm logic is preserved: a blank confirmation is rejected, {@code N}
 *       cancels without publishing, {@code Y} proceeds, and any other value is
 *       rejected with the program's exact message.</li>
 *   <li><strong>Publish</strong> &mdash; on confirmation a single FIFO message is
 *       published, replacing the {@code WRITEQ TD} loop.</li>
 * </ol>
 *
 * <p>Every user-visible message text is preserved byte-for-byte from the COBOL
 * source. Validation and confirmation failures raise {@link ValidationException};
 * a publish failure (the analogue of the legacy {@code "Unable to Write TDQ
 * (JOBS)..."} path) raises {@link FileAccessException}. Neither builds a
 * transport-level response &mdash; mapping to HTTP is performed by the
 * centralized exception handler and the sibling controller.</p>
 *
 * <p>The service is stateless and thread-safe; its collaborators are supplied by
 * constructor injection.</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final String REPORT_MONTHLY = "Monthly";
    private static final String REPORT_YEARLY = "Yearly";
    private static final String REPORT_CUSTOM = "Custom";

    private static final String REPORT_JOBS_MESSAGE_GROUP_ID = "report-jobs";

    private static final String START_DATE_LABEL = "Start Date";
    private static final String END_DATE_LABEL = "End Date";

    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    private static final String MSG_CONFIRM_PREFIX = "Please confirm to print the ";
    private static final String MSG_CONFIRM_SUFFIX = " report...";
    private static final String MSG_INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";
    private static final String MSG_SUBMITTED_SUFFIX = " report submitted for printing ...";

    private static final String MSG_WRITE_QUEUE_FAILED = "Unable to Write TDQ (JOBS)...";

    private static final int MAX_MONTH = 12;
    private static final int MAX_DAY = 31;
    private static final int FIRST_MONTH = 1;
    private static final int LAST_MONTH = 12;
    private static final int FIRST_DAY = 1;
    private static final int LAST_DAY_OF_DECEMBER = 31;

    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    private final DateValidationService dateValidationService;

    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    private final SqsTemplate sqsTemplate;

    /**
     * Creates the service with its collaborators.
     *
     * @param dateValidationService the date-validation service that enforces the
     *                              {@code CSUTLDTC} assembled-date contract; must
     *                              not be {@code null}
     * @param awsResourceProperties the typed source of the externalized AWS
     *                              resource names, supplying the FIFO report-queue
     *                              name; must not be {@code null}
     * @param sqsTemplate           the Spring Cloud AWS template used to publish
     *                              the report-request message; must not be
     *                              {@code null}
     */
    public ReportService(DateValidationService dateValidationService,
                         AwsConfig.AwsResourceProperties awsResourceProperties,
                         SqsTemplate sqsTemplate) {
        this.dateValidationService = dateValidationService;
        this.awsResourceProperties = awsResourceProperties;
        this.sqsTemplate = sqsTemplate;
    }

    /**
     * Processes a report-submission request, reproducing the
     * {@code PROCESS-ENTER-KEY} flow of {@code CORPT00C}.
     *
     * <p>Selects the report type, computes or validates the reporting date range,
     * enforces the confirmation gate, and &mdash; on confirmation &mdash; publishes
     * exactly one request message to the FIFO report queue.</p>
     *
     * @param request the report-submission request carrying the report-type flags,
     *                custom date segments, and confirmation flag
     * @return the result of the submission, including the resolved report name, the
     *         {@code YYYY-MM-DD} date range, whether a message was published, and
     *         the user-visible confirmation message
     * @throws ValidationException if no report type is selected, if a custom date
     *                             segment is empty or invalid, if the assembled
     *                             custom date is not a valid date, if confirmation
     *                             is blank, or if the confirmation value is neither
     *                             {@code Y} nor {@code N}
     * @throws FileAccessException if publishing the request message to the queue
     *                             fails
     */
    public SubmitResult submitReport(ReportDto.SubmitRequest request) {
        String reportName;
        LocalDate startDate;
        LocalDate endDate;

        if (isSelected(request.monthly())) {
            reportName = REPORT_MONTHLY;
            LocalDate today = LocalDate.now();
            startDate = today.withDayOfMonth(FIRST_DAY);
            endDate = today.withDayOfMonth(today.lengthOfMonth());
        } else if (isSelected(request.yearly())) {
            reportName = REPORT_YEARLY;
            int year = LocalDate.now().getYear();
            startDate = LocalDate.of(year, FIRST_MONTH, FIRST_DAY);
            endDate = LocalDate.of(year, LAST_MONTH, LAST_DAY_OF_DECEMBER);
        } else if (isSelected(request.custom())) {
            reportName = REPORT_CUSTOM;
            DateRange range = computeCustomRange(request);
            startDate = range.start();
            endDate = range.end();
        } else {
            throw new ValidationException(MSG_SELECT_REPORT_TYPE);
        }

        String startDateText = startDate.format(REPORT_DATE_FORMAT);
        String endDateText = endDate.format(REPORT_DATE_FORMAT);

        if (!isConfirmed(request.confirm(), reportName)) {
            return new SubmitResult(reportName, startDateText, endDateText, false, "");
        }

        publishReportRequest(reportName, startDateText, endDateText);

        log.info("Submitted {} report for printing for range {} to {}",
                reportName, startDateText, endDateText);
        return new SubmitResult(reportName, startDateText, endDateText, true,
                reportName + MSG_SUBMITTED_SUFFIX);
    }

    /**
     * Builds and validates the custom reporting date range from the six request
     * segments, preserving the program's ordered checks: all empty-segment checks
     * first, then all segment-range checks, then the assembled start date and end
     * date checks.
     *
     * @param request the report-submission request supplying the date segments
     * @return the validated start and end dates
     * @throws ValidationException on the first failing check, carrying that check's
     *                             exact message
     */
    private DateRange computeCustomRange(ReportDto.SubmitRequest request) {
        requireNotEmpty(request.startMonth(), MSG_START_MONTH_EMPTY);
        requireNotEmpty(request.startDay(), MSG_START_DAY_EMPTY);
        requireNotEmpty(request.startYear(), MSG_START_YEAR_EMPTY);
        requireNotEmpty(request.endMonth(), MSG_END_MONTH_EMPTY);
        requireNotEmpty(request.endDay(), MSG_END_DAY_EMPTY);
        requireNotEmpty(request.endYear(), MSG_END_YEAR_EMPTY);

        requireValidMonthSegment(request.startMonth(), MSG_START_MONTH_INVALID);
        requireValidDaySegment(request.startDay(), MSG_START_DAY_INVALID);
        requireNumericSegment(request.startYear(), MSG_START_YEAR_INVALID);
        requireValidMonthSegment(request.endMonth(), MSG_END_MONTH_INVALID);
        requireValidDaySegment(request.endDay(), MSG_END_DAY_INVALID);
        requireNumericSegment(request.endYear(), MSG_END_YEAR_INVALID);

        LocalDate start = validateAssembledDate(request.startYear(), request.startMonth(),
                request.startDay(), START_DATE_LABEL, MSG_START_DATE_INVALID);
        LocalDate end = validateAssembledDate(request.endYear(), request.endMonth(),
                request.endDay(), END_DATE_LABEL, MSG_END_DATE_INVALID);
        return new DateRange(start, end);
    }

    /**
     * Validates the confirmation flag and reports whether the submission should
     * proceed, preserving the {@code SUBMIT-JOB-TO-INTRDR} confirm logic.
     *
     * @param confirm    the confirmation flag from the request
     * @param reportName the resolved report name, used to compose the blank-confirm
     *                   prompt
     * @return {@code true} when the value confirms the submission ({@code Y}/{@code y});
     *         {@code false} when the value cancels it ({@code N}/{@code n})
     * @throws ValidationException if the value is blank or is neither {@code Y} nor
     *                             {@code N}
     */
    private boolean isConfirmed(String confirm, String reportName) {
        if (isBlank(confirm)) {
            throw new ValidationException(MSG_CONFIRM_PREFIX + reportName + MSG_CONFIRM_SUFFIX);
        }
        String value = confirm.trim();
        if ("Y".equalsIgnoreCase(value)) {
            return true;
        }
        if ("N".equalsIgnoreCase(value)) {
            return false;
        }
        throw new ValidationException("\"" + value + MSG_INVALID_CONFIRM_SUFFIX);
    }

    /**
     * Publishes a single report-request message to the FIFO report queue,
     * replacing the legacy {@code WRITEQ TD QUEUE('JOBS')} write loop.
     *
     * <p>The queue name is read from {@link AwsConfig.AwsResourceProperties}. The
     * FIFO contract requires a message group id and a deduplication id; the group
     * id serialises report submissions and the per-message {@link UUID}
     * deduplication id admits each distinct submission.</p>
     *
     * @param reportName the resolved report name
     * @param startDate  the inclusive range start in {@code YYYY-MM-DD} form
     * @param endDate    the inclusive range end in {@code YYYY-MM-DD} form
     * @throws FileAccessException if the publish operation fails
     */
    private void publishReportRequest(String reportName, String startDate, String endDate) {
        String queueName = awsResourceProperties.getSqs().getReportQueue();
        ReportRequestMessage payload = new ReportRequestMessage(reportName, startDate, endDate);
        try {
            sqsTemplate.send(options -> options
                    .queue(queueName)
                    .payload(payload)
                    .messageGroupId(REPORT_JOBS_MESSAGE_GROUP_ID)
                    .messageDeduplicationId(UUID.randomUUID().toString()));
        } catch (RuntimeException ex) {
            throw new FileAccessException(MSG_WRITE_QUEUE_FAILED, ex);
        }
    }

    /**
     * Rejects a blank segment with the supplied message.
     *
     * @param segment the segment value
     * @param message the exact message raised when the segment is blank
     * @throws ValidationException if the segment is {@code null}, empty, or whitespace
     */
    private void requireNotEmpty(String segment, String message) {
        if (isBlank(segment)) {
            throw new ValidationException(message);
        }
    }

    /**
     * Rejects a month segment that is non-numeric or greater than twelve.
     *
     * @param segment the month segment value
     * @param message the exact message raised when the segment is invalid
     * @throws ValidationException if the segment is non-numeric or exceeds twelve
     */
    private void requireValidMonthSegment(String segment, String message) {
        int value = parseSegment(segment);
        if (value < 0 || value > MAX_MONTH) {
            throw new ValidationException(message);
        }
    }

    /**
     * Rejects a day segment that is non-numeric or greater than thirty-one.
     *
     * @param segment the day segment value
     * @param message the exact message raised when the segment is invalid
     * @throws ValidationException if the segment is non-numeric or exceeds thirty-one
     */
    private void requireValidDaySegment(String segment, String message) {
        int value = parseSegment(segment);
        if (value < 0 || value > MAX_DAY) {
            throw new ValidationException(message);
        }
    }

    /**
     * Rejects a segment that is non-numeric.
     *
     * @param segment the segment value
     * @param message the exact message raised when the segment is non-numeric
     * @throws ValidationException if the segment is non-numeric
     */
    private void requireNumericSegment(String segment, String message) {
        if (parseSegment(segment) < 0) {
            throw new ValidationException(message);
        }
    }

    /**
     * Validates an assembled date through {@link DateValidationService}, mapping any
     * validation failure onto the program's exact "not a valid date" message.
     *
     * @param year           the year segment
     * @param month          the month segment
     * @param day            the day segment
     * @param fieldLabel     the field label passed to the validator
     * @param invalidMessage the exact message raised when the assembled date is invalid
     * @return the parsed {@link LocalDate}
     * @throws ValidationException if the assembled date is not a valid date
     */
    private LocalDate validateAssembledDate(String year, String month, String day,
                                            String fieldLabel, String invalidMessage) {
        try {
            return dateValidationService.validateDateParts(year.trim(), month.trim(), day.trim(), fieldLabel);
        } catch (ValidationException ex) {
            throw new ValidationException(invalidMessage, ex);
        }
    }

    /**
     * Parses a numeric segment to its integer value.
     *
     * @param segment the segment value
     * @return the parsed non-negative integer, or {@code -1} when the trimmed value
     *         is not a base-ten integer
     */
    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Reports whether a report-type flag is selected (non-blank).
     *
     * @param flag the flag value
     * @return {@code true} when the flag is non-{@code null} and not whitespace
     */
    private static boolean isSelected(String flag) {
        return flag != null && !flag.isBlank();
    }

    /**
     * Reports whether a value is blank.
     *
     * @param value the value
     * @return {@code true} when the value is {@code null}, empty, or whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Immutable holder for a validated reporting date range.
     *
     * @param start the inclusive range start
     * @param end   the inclusive range end
     */
    private record DateRange(LocalDate start, LocalDate end) {
    }

    /**
     * Result of a report submission.
     *
     * @param reportName the resolved report name ({@code Monthly}, {@code Yearly},
     *                   or {@code Custom})
     * @param startDate  the inclusive range start in {@code YYYY-MM-DD} form
     * @param endDate    the inclusive range end in {@code YYYY-MM-DD} form
     * @param submitted  {@code true} when a request message was published;
     *                   {@code false} when the submission was cancelled
     * @param message    the user-visible confirmation message ({@code "&lt;name&gt;
     *                   report submitted for printing ..."} on submission; empty when
     *                   cancelled)
     */
    public record SubmitResult(String reportName, String startDate, String endDate,
                               boolean submitted, String message) {
    }

    /**
     * Typed payload published to the FIFO report queue, replacing the legacy JCL
     * stream written to the {@code JOBS} Transient Data Queue.
     *
     * @param reportName the resolved report name
     * @param startDate  the inclusive range start in {@code YYYY-MM-DD} form
     * @param endDate    the inclusive range end in {@code YYYY-MM-DD} form
     */
    public record ReportRequestMessage(String reportName, String startDate, String endDate) {
    }
}
