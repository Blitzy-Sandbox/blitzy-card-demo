package com.carddemo.reporting.jobs;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carddemo.reporting.model.JobAcknowledgement;

/**
 * [DEFERRED] Invokable async job-stub entrypoint for the batch transaction report.
 *
 * <p>[SRC: CBTRN03C | TRANSACT] Mirrors the legacy batch program {@code CBTRN03C} ("Transaction
 * Detail Report"), which reads the {@code TRANSACT} dataset, groups by account/card, and prints a
 * transaction detail report with page and grand totals. This is deliberately <strong>distinct</strong>
 * from the online Transaction Reports program ({@code CORPT00C}, handled by
 * {@link ReportJobHandler}): {@code CBTRN03C} is the batch report generator. This stub returns a
 * typed acknowledgement placeholder only: it performs NO dataset read, NO totalling, and NO report
 * file output — it exists solely as an invokable, typed entrypoint so the batch job roster is
 * complete and freezable.</p>
 */
@Component
public class TransactionReportJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TransactionReportJobHandler.class);

    /**
     * [DEFERRED] Accepts a transaction-report batch job and returns a typed acknowledgement placeholder.
     * The correlation id is present in the SLF4J MDC (populated by CorrelationIdFilter).
     *
     * <p>The {@code jobId} is the nil-UUID sentinel, never a random UUID: no job is queued or run,
     * so the acknowledgement must not hand back a plausible-looking tracking id for work that never
     * happens (finding P4-M06).</p>
     *
     * @return a typed {@link JobAcknowledgement} stub with status {@code ACCEPTED} and the nil-UUID sentinel
     */
    public JobAcknowledgement submitTransactionReportJob() {
        log.info("[DEFERRED] submitTransactionReportJob stub accepted transaction-report batch job [SRC: CBTRN03C]");
        return new JobAcknowledgement()
                .jobId(JobStubs.DEFERRED_JOB_ID)
                .jobType(JobAcknowledgement.JobTypeEnum.TRANSACTION_REPORT)
                .status(JobAcknowledgement.StatusEnum.ACCEPTED)
                .submittedAt(OffsetDateTime.now());
    }
}
