package com.carddemo.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Asynchronous <em>job-accepted acknowledgement</em> for a transaction-report
 * generation request.
 *
 * <p>An instance of this record is returned by the report controller
 * immediately after a report request has been enqueued onto the
 * {@code carddemo-report-jobs.fifo} SQS FIFO queue that triggers the Spring
 * Batch report launch. It is the modern, stateless equivalent of the legacy
 * {@code CORPT00C} CICS Transient Data Queue &rarr; JES bridge acknowledgement:
 * rather than returning the rendered report, the caller receives an opaque
 * {@code jobId} that can be polled for completion (HTTP 202 &quot;Accepted&quot;
 * semantics).</p>
 *
 * <p><strong>This DTO is the acknowledgement, not the report content.</strong>
 * The actual report is produced out-of-band by the batch pipeline and delivered
 * as a versioned object in the report S3 bucket.</p>
 *
 * <p>Because the mainframe pseudo-conversational {@code COMMAREA} state is
 * replaced by a stateless REST model, this object holds no server-side session
 * state. The {@code jobId} and {@code message} intentionally expose only an
 * opaque identifier and human-readable confirmation text; no internal
 * infrastructure details (queue URLs, batch execution identifiers, host names,
 * or credentials) are leaked to the caller.</p>
 *
 * <p>Field provenance in the legacy source (referenced by commit
 * {@code 27d6c6f}): {@code reportName} derives from the
 * {@code REPORT-NAME-HEADER} long-name field of copybook {@code CVTRA07Y}
 * ({@code REPT-LONG-NAME PIC X(41)}); {@code reportType}, {@code startDate} and
 * {@code endDate} echo the selection and date-range fields of the
 * {@code CORPT00} BMS map.</p>
 *
 * <p>All monetary values are excluded by design &mdash; this acknowledgement
 * carries no financial fields, so no {@link java.math.BigDecimal} is required.
 * Both date components use {@link LocalDate}.</p>
 *
 * @param jobId      opaque acknowledgement identifier used by the caller to poll
 *                   the enqueued job; carries no internal infrastructure detail
 * @param status     processing status of the enqueued job, for example
 *                   {@link #STATUS_ACCEPTED} (asynchronous accepted, HTTP 202
 *                   semantics)
 * @param reportType echo of the requested report selection derived from the
 *                   {@code CORPT00} map, for example {@code MONTHLY},
 *                   {@code YEARLY} or {@code CUSTOM}
 * @param startDate  echo of the requested reporting-period start date; may be
 *                   {@code null} when it does not apply to the selected report
 *                   type
 * @param endDate    echo of the requested reporting-period end date; may be
 *                   {@code null} when it does not apply to the selected report
 *                   type
 * @param reportName human-readable report title derived from the
 *                   {@code CVTRA07Y} report header; may be {@code null}. Bounded
 *                   to {@value #REPORT_NAME_MAX_LENGTH} characters to match the
 *                   {@code REPT-LONG-NAME PIC X(41)} header width
 * @param message    human-readable confirmation text describing the
 *                   acknowledgement; exposes no internal infrastructure detail
 */
public record ReportResponse(
        String jobId,
        String status,
        String reportType,
        LocalDate startDate,
        LocalDate endDate,
        @Size(max = ReportResponse.REPORT_NAME_MAX_LENGTH) String reportName,
        String message
) {

    /**
     * Maximum length of {@link #reportName}, matching the {@code REPT-LONG-NAME}
     * field width ({@code PIC X(41)}) of the {@code REPORT-NAME-HEADER} structure
     * in copybook {@code CVTRA07Y}.
     */
    public static final int REPORT_NAME_MAX_LENGTH = 41;

    /**
     * Canonical {@link #status} value indicating that the report request has been
     * accepted for asynchronous processing and enqueued for the batch launch
     * (HTTP 202 &quot;Accepted&quot; semantics).
     */
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    /**
     * Creates an acknowledgement whose {@link #status} is
     * {@link #STATUS_ACCEPTED}, echoing the originating request parameters.
     *
     * <p>This is the convenience factory used by the report controller once a
     * request has been successfully enqueued onto the report SQS FIFO queue.</p>
     *
     * @param jobId      opaque acknowledgement identifier for polling
     * @param reportType echo of the requested report selection
     * @param startDate  echo of the reporting-period start date, or {@code null}
     * @param endDate    echo of the reporting-period end date, or {@code null}
     * @param reportName human-readable report title, or {@code null}
     * @param message    human-readable confirmation text
     * @return a new {@code ReportResponse} with {@code status = }
     *         {@link #STATUS_ACCEPTED}
     */
    public static ReportResponse accepted(
            String jobId,
            String reportType,
            LocalDate startDate,
            LocalDate endDate,
            String reportName,
            String message) {
        return new ReportResponse(
                jobId,
                STATUS_ACCEPTED,
                reportType,
                startDate,
                endDate,
                reportName,
                message);
    }
}
