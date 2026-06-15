package com.carddemo.model.dto;

/**
 * Response payload for {@code POST /api/reports/submit}.
 *
 * <p>Returned by {@code controller.ReportController} after
 * {@code service.report.ReportSubmissionService} (migrated from the COBOL online
 * program {@code CORPT00C}) publishes the report-job message to the SQS FIFO
 * queue - the modern bridge for the original CICS transient-data-queue
 * {@code WRITEQ TD} online-to-batch hand-off.
 *
 * <p>{@code CORPT00C} only submits a report job to the batch pipeline; it does
 * not render report data on screen. This DTO therefore conveys a concise
 * submission acknowledgement plus any error text, mirroring the
 * {@code CORPT00} symbolic-map output messaging (the {@code ERRMSG} field,
 * {@code PIC X(78)}). Lineage: source commit {@code 27d6c6f} (COBOL not copied).
 *
 * @param confirmationMessage human-readable acknowledgement that the report job
 *                            was accepted and queued for batch processing
 *                            (online-to-batch {@code WRITEQ TD} success); may be
 *                            {@code null} when the submission failed
 * @param errorMessage        error text describing why the submission could not
 *                            be queued ({@code CORPT00}'s {@code ERRMSG}); may be
 *                            {@code null} when the submission succeeded
 */
public record ReportResponse(
        String confirmationMessage,
        String errorMessage) {
}
