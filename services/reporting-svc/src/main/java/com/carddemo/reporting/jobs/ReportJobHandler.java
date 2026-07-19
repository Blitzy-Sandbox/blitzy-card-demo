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
        return new JobAcknowledgement()
                .jobId(UUID.randomUUID())
                .jobType(JobAcknowledgement.JobTypeEnum.REPORT)
                .status(JobAcknowledgement.StatusEnum.ACCEPTED)
                .submittedAt(OffsetDateTime.now());
    }

    /**
     * [DEFERRED] Returns a typed job-status placeholder for the given job id.
     *
     * @param jobId the job identifier
     * @return a typed {@link JobStatus} stub with status COMPLETED
     */
    public JobStatus getJobStatus(String jobId) {
        log.info("[DEFERRED] getJobStatus stub invoked: jobId={}", jobId);
        JobStatus status = new JobStatus()
                .jobType(JobStatus.JobTypeEnum.REPORT)
                .status(JobStatus.StatusEnum.COMPLETED)
                .submittedAt(OffsetDateTime.now())
                .completedAt(OffsetDateTime.now())
                .message("[DEFERRED] stub");
        try {
            status.jobId(UUID.fromString(jobId));
        } catch (IllegalArgumentException | NullPointerException ex) {
            status.jobId(UUID.randomUUID());
        }
        return status;
    }
}
