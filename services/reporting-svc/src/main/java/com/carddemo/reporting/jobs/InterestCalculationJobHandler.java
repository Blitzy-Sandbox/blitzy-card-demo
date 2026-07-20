package com.carddemo.reporting.jobs;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carddemo.reporting.model.JobAcknowledgement;

/**
 * [DEFERRED] Invokable async job-stub entrypoint for the interest calculation batch.
 *
 * <p>[SRC: CBACT04C | ACCTDAT] Mirrors the legacy batch program {@code CBACT04C} ("Interest
 * Calculation"), which walks the {@code ACCTDAT} accounts and their transaction-category
 * balances, computes interest from the disclosure-group rates, and writes interest transactions.
 * This stub returns a typed acknowledgement placeholder only: it performs NO dataset read, NO
 * interest calculation, and NO balance update — it exists solely as an invokable, typed
 * entrypoint so the batch job roster is complete and freezable.</p>
 */
@Component
public class InterestCalculationJobHandler {

    private static final Logger log = LoggerFactory.getLogger(InterestCalculationJobHandler.class);

    /**
     * [DEFERRED] Accepts an interest-calculation batch job and returns a typed acknowledgement placeholder.
     * The correlation id is present in the SLF4J MDC (populated by CorrelationIdFilter).
     *
     * <p>The {@code jobId} is the nil-UUID sentinel, never a random UUID: no job is queued or run,
     * so the acknowledgement must not hand back a plausible-looking tracking id for work that never
     * happens (finding P4-M06).</p>
     *
     * @return a typed {@link JobAcknowledgement} stub with status {@code ACCEPTED} and the nil-UUID sentinel
     */
    public JobAcknowledgement submitInterestCalculationJob() {
        log.info("[DEFERRED] submitInterestCalculationJob stub accepted interest-calculation batch job [SRC: CBACT04C]");
        return new JobAcknowledgement()
                .jobId(JobStubs.DEFERRED_JOB_ID)
                .jobType(JobAcknowledgement.JobTypeEnum.INTEREST_CALCULATION)
                .status(JobAcknowledgement.StatusEnum.ACCEPTED)
                .submittedAt(OffsetDateTime.now());
    }
}
