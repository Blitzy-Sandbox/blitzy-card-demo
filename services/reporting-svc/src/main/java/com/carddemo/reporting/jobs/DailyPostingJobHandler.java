package com.carddemo.reporting.jobs;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carddemo.reporting.model.JobAcknowledgement;

/**
 * [DEFERRED] Invokable async job-stub entrypoint for the daily transaction posting batch.
 *
 * <p>[SRC: CBTRN02C | TRANSACT] Mirrors the legacy batch program {@code CBTRN02C} ("Daily
 * Transaction Posting"), which reads the daily transaction file and posts each transaction
 * against the {@code TRANSACT} / {@code ACCTDAT} datasets, updating account balances and the
 * transaction-category balances. This stub returns a typed acknowledgement placeholder only:
 * it performs NO dataset read, NO posting, and NO balance update — it exists solely as an
 * invokable, typed entrypoint so the batch job roster is complete and freezable.</p>
 */
@Component
public class DailyPostingJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DailyPostingJobHandler.class);

    /**
     * [DEFERRED] Accepts a daily-posting batch job and returns a typed acknowledgement placeholder.
     * The correlation id is present in the SLF4J MDC (populated by CorrelationIdFilter).
     *
     * <p>The {@code jobId} is the nil-UUID sentinel, never a random UUID: no job is queued or run,
     * so the acknowledgement must not hand back a plausible-looking tracking id for work that never
     * happens (finding P4-M06).</p>
     *
     * @return a typed {@link JobAcknowledgement} stub with status {@code ACCEPTED} and the nil-UUID sentinel
     */
    public JobAcknowledgement submitDailyPostingJob() {
        log.info("[DEFERRED] submitDailyPostingJob stub accepted daily-posting batch job [SRC: CBTRN02C]");
        return new JobAcknowledgement()
                .jobId(JobStubs.DEFERRED_JOB_ID)
                .jobType(JobAcknowledgement.JobTypeEnum.DAILY_POSTING)
                .status(JobAcknowledgement.StatusEnum.ACCEPTED)
                .submittedAt(OffsetDateTime.now());
    }
}
