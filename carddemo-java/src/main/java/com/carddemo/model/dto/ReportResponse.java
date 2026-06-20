package com.carddemo.model.dto;

/**
 * Response payload for {@code POST /api/reports/submit}.
 *
 * <p>Returned by the report controller after the report-submission service
 * (re-platformed from the COBOL {@code CORPT00C} online program) publishes the
 * report-job message to the SQS FIFO queue that replaces the CICS transient
 * data queue ({@code WRITEQ TD}) online-to-batch bridge. {@code CORPT00C} only
 * queues a report job for the batch pipeline &mdash; it does not render report
 * data on screen &mdash; so this payload mirrors the {@code CORPT00}
 * symbolic-map output messaging: a concise submission acknowledgement together
 * with the screen's {@code ERRMSG} error text, rather than echoing the
 * report-selection form. Source lineage: original repository commit
 * {@code 27d6c6f}.</p>
 *
 * @param confirmationMessage human-readable acknowledgement that the report job
 *                            was successfully queued for batch processing (the
 *                            online-to-batch TDQ write success); {@code null}
 *                            when the submission did not complete
 * @param errorMessage        error text surfaced to the caller when submission
 *                            fails, mirroring the {@code CORPT00} {@code ERRMSG}
 *                            field (X78); {@code null} on success
 */
public record ReportResponse(
        String confirmationMessage,
        String errorMessage) {
}
