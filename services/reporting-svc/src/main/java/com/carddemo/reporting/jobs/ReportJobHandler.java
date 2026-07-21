package com.carddemo.reporting.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carddemo.reporting.model.JobAcknowledgement;
import com.carddemo.reporting.model.JobStatus;
import com.carddemo.reporting.model.ReportRequest;

/**
 * [DEFERRED] Invokable async job-stub entrypoint for transaction-report generation.
 *
 * <p>[SRC: CORPT00C | TRANSACT] Mirrors the legacy online Transaction Reports
 * transaction {@code CR00 -> CORPT00C}, which builds a Monthly/Yearly/Custom report
 * request from screen {@code CORPT00.bms} and submits a batch JCL job asynchronously
 * to the internal reader (fire-and-forget). This stub returns a typed placeholder only:
 * it performs NO {@code TRANSACT} read, NO totals/calculation, and NO report file output.</p>
 */
@Component
public class ReportJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportJobHandler.class);

    /**
     * [DEFERRED] Accepts a report job and returns a typed acknowledgement placeholder.
     * The correlation id is present in the SLF4J MDC (populated by CorrelationIdFilter).
     *
     * @param request the report request (type + optional date window)
     * @return a typed {@link JobAcknowledgement} stub with status ACCEPTED
     */
    public JobAcknowledgement submitReportJob(ReportRequest request) {
        ReportRequest.ReportTypeEnum reportType = request != null ? request.getReportType() : null;
        log.info("[DEFERRED] submitReportJob stub accepted report job: reportType={}", reportType);
        // jobId is the nil-UUID sentinel, never a random UUID: no job is queued, so we must not
        // hand back a plausible-looking tracking id for work that never happens (finding P4-M06).
        return new JobAcknowledgement()
                .jobId(JobStubs.DEFERRED_JOB_ID)
                .jobType(JobAcknowledgement.JobTypeEnum.REPORT)
                .status(JobAcknowledgement.StatusEnum.ACCEPTED)
                .submittedAt(OffsetDateTime.now());
    }

    /**
     * [DEFERRED] Returns an honest typed job-status placeholder for the given job id.
     *
     * <p>Because reporting-svc never actually executes, queues, or stores a job in the walking
     * skeleton, this stub must not fabricate completion (finding P4-M06 / AAP-35). It therefore
     * reports the non-terminal, truthful status {@code ACCEPTED} and leaves {@code submittedAt} and
     * {@code completedAt} {@code null} — the contract explicitly permits a null {@code completedAt}
     * "while the job is still ACCEPTED or RUNNING", and there is no genuine submission time to
     * report. The requested {@code jobId} is echoed back when it is a well-formed UUID; an
     * unparseable id falls back to the nil-UUID sentinel rather than a freshly-minted random UUID,
     * so the response never invents a plausible-looking tracking identifier.</p>
     *
     * @param jobId the job identifier from the caller (echoed when a valid UUID)
     * @return a typed {@link JobStatus} stub with a non-fabricated {@code ACCEPTED} status
     */
    public JobStatus getJobStatus(String jobId) {
        log.info("[DEFERRED] getJobStatus stub invoked: jobId={}", jobId);
        UUID echoedId;
        try {
            echoedId = UUID.fromString(jobId);
        } catch (IllegalArgumentException | NullPointerException ex) {
            echoedId = JobStubs.DEFERRED_JOB_ID;
        }
        // No submittedAt / completedAt: the skeleton neither queues nor runs the job, so fabricating
        // those timestamps (or a COMPLETED status) would misrepresent work that never happened.
        return new JobStatus()
                .jobId(echoedId)
                .jobType(JobStatus.JobTypeEnum.REPORT)
                .status(JobStatus.StatusEnum.ACCEPTED)
                .message("[DEFERRED] reporting-svc is an async job stub: no report job is executed, "
                        + "tracked, or completed in the walking skeleton. completedAt is null "
                        + "because no work is performed.");
    }
}
