package com.cardemo.model.dto;

/**
 * REST response payload returned by {@code POST /api/reports/submit} &mdash; the documented
 * external-interface contract for the AWS CardDemo <strong>Transaction Reports</strong> bridge
 * (program {@code app/cbl/CORPT00C.cbl}, CICS transaction {@code CR00}, BMS map {@code CORPT0A}).
 *
 * <p>This Data Transfer Object is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of the
 * <em>accepted report job</em> acknowledgement defined in {@code docs/api-contracts.md} &sect;5.7. The
 * contract specifies exactly three fields for the {@code 202 Accepted} response &mdash; {@link #jobId},
 * {@link #reportType} and {@link #status} &mdash; and this record carries precisely those, with no
 * additional fields (Minimal Change Clause, AAP&nbsp;&sect;0.7.1; external-interface preservation,
 * AAP&nbsp;&sect;0.7.2).</p>
 *
 * <h2>Why a dedicated response shape (the CORPT00C&nbsp;&rarr;&nbsp;SQS bridge)</h2>
 * <p>{@code CORPT00C} is the single online&rarr;batch coupling point in the estate (AAP&nbsp;&sect;0.6.3).
 * On a {@code 'Y'} confirmation the mainframe built a JCL deck and wrote it to the CICS Transient Data
 * Queue {@code JOBS} ({@code WRITEQ TD}), triggering asynchronous JES batch submission of the downstream
 * report job ({@code TRANREPT}&nbsp;&rarr;&nbsp;{@code CBTRN03C}). The migrated system replaces that
 * hand-off with a single AWS SQS publish to {@code carddemo-report-jobs.fifo}, which triggers the
 * corresponding Spring Batch report job. Because the work is performed <em>asynchronously</em> by the
 * batch job, the endpoint returns {@code 202 Accepted} with a {@link #jobId} (the queued-job identifier)
 * rather than the finished report &mdash; exactly as documented in &sect;5.7.</p>
 *
 * <h2>Field contract (docs/api-contracts.md &sect;5.7)</h2>
 * <ul>
 *   <li>{@link #jobId} &mdash; a generated, stable identifier for the queued report job. The service
 *       generates one {@link java.util.UUID} per confirmed submission and uses the same value as the SQS
 *       FIFO {@code MessageDeduplicationId}, so the {@code jobId} returned to the caller is the very
 *       identifier under which the job was enqueued. {@code null} on a cancellation (see below).</li>
 *   <li>{@link #reportType} &mdash; the canonical machine report-type token carried in the SQS payload
 *       ({@code MONTHLY} / {@code YEARLY} / {@code CUSTOM}), echoing the operator's report-type
 *       selection.</li>
 *   <li>{@link #status} &mdash; the submission outcome: {@code "SUBMITTED"} when the report job was
 *       published to SQS for asynchronous processing ({@code 202 Accepted}). The cancellation path
 *       (operator answered {@code 'N'}) reuses this same shape with {@code status == "CANCELLED"} and a
 *       {@code null} {@link #jobId} at {@code 200 OK}; this conversational outcome is handled in service
 *       logic (&sect;5.7 "Notes"), nothing is queued, and the 202 submit contract is not weakened.</li>
 * </ul>
 *
 * <p>This is a pure boundary type: it carries no business logic, no I/O and no mutable static state. It
 * is intentionally separate from the SQS payload record {@code ReportSubmissionService.ReportJobMessage}
 * (which carries {@code reportType} + the derived start/end dates for the batch job) &mdash; the API
 * response and the message payload are distinct contracts.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit SHA
 * {@code 27d6c6f}; the COBOL and BMS sources are read-only reference material and are never copied into
 * this repository (AAP&nbsp;&sect;0.7.2).</p>
 *
 * @param jobId      the generated identifier of the queued report job ({@code null} on cancellation)
 * @param reportType the canonical machine report-type token ({@code MONTHLY} / {@code YEARLY} /
 *                   {@code CUSTOM})
 * @param status     the submission outcome ({@code "SUBMITTED"} when queued; {@code "CANCELLED"} on a
 *                   {@code 'N'} confirmation)
 * @see com.cardemo.service.report.ReportSubmissionService
 * @see com.cardemo.controller.ReportController
 */
public record ReportSubmissionResponse(String jobId, String reportType, String status) {
}
