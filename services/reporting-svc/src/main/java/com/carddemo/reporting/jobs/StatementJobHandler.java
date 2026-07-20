package com.carddemo.reporting.jobs;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carddemo.reporting.model.JobAcknowledgement;
import com.carddemo.reporting.model.StatementRequest;

/**
 * [DEFERRED] Invokable async job-stub entrypoint for account-statement generation.
 *
 * <p>[SRC: CBSTM03A/B] Mirrors the legacy batch statement programs {@code CBSTM03A}
 * ("Print Account Statements from Transaction data in two formats: plain text and HTML")
 * and its file-service subroutine {@code CBSTM03B}. This stub returns a typed placeholder
 * only: it performs NO statement generation and NO file output.</p>
 */
@Component
public class StatementJobHandler {

    private static final Logger log = LoggerFactory.getLogger(StatementJobHandler.class);

    /**
     * [DEFERRED] Accepts a statement job and returns a typed acknowledgement placeholder.
     * The correlation id is present in the SLF4J MDC (populated by CorrelationIdFilter).
     *
     * <p>The account identifier is a sensitive customer datum and is therefore
     * <strong>masked</strong> before it is written to the log (see {@link #maskAccountId(String)}):
     * the raw, complete id never appears in log output. The correlation id in the MDC remains the
     * mechanism for tracing a specific request across services.</p>
     *
     * @param request the statement request (optional account id + output format)
     * @return a typed {@link JobAcknowledgement} stub with status ACCEPTED
     */
    public JobAcknowledgement submitStatementJob(StatementRequest request) {
        StatementRequest.FormatEnum format = request != null ? request.getFormat() : null;
        String accountId = request != null ? request.getAccountId() : null;
        // MINOR-3 log hygiene: never emit the raw account identifier at INFO. Log a masked form so
        // operators keep a correlation hint without the full sensitive id appearing in the logs.
        log.info("[DEFERRED] submitStatementJob stub accepted statement job: format={}, accountId={}",
                format, maskAccountId(accountId));
        // jobId is the nil-UUID sentinel, never a random UUID: no job is queued, so we must not
        // hand back a plausible-looking tracking id for work that never happens (finding P4-M06).
        return new JobAcknowledgement()
                .jobId(JobStubs.DEFERRED_JOB_ID)
                .jobType(JobAcknowledgement.JobTypeEnum.STATEMENT)
                .status(JobAcknowledgement.StatusEnum.ACCEPTED)
                .submittedAt(OffsetDateTime.now());
    }

    /**
     * Masks an account identifier for safe logging.
     *
     * <p>Returns {@code "<absent>"} for a {@code null}/blank value; a fully-asterisked token for a
     * value of four characters or fewer (revealing nothing, e.g. {@code "50"} &rarr; {@code "**"});
     * and otherwise every character replaced by {@code '*'} except the last four
     * (e.g. {@code "00000000050"} &rarr; {@code "*******0050"}). The raw, complete identifier is
     * never returned, so it can never reach the log.</p>
     *
     * @param accountId the account identifier to mask; may be {@code null}
     * @return a non-sensitive, log-safe representation of {@code accountId}
     */
    private static String maskAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "<absent>";
        }
        final String trimmed = accountId.strip();
        final int len = trimmed.length();
        if (len <= 4) {
            return "*".repeat(len);
        }
        return "*".repeat(len - 4) + trimmed.substring(len - 4);
    }
}
