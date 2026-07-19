package com.carddemo.reporting.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

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
     * @param request the statement request (optional account id + output format)
     * @return a typed {@link JobAcknowledgement} stub with status ACCEPTED
     */
    public JobAcknowledgement submitStatementJob(StatementRequest request) {
        StatementRequest.FormatEnum format = request != null ? request.getFormat() : null;
        String accountId = request != null ? request.getAccountId() : null;
        log.info("[DEFERRED] submitStatementJob stub accepted statement job: format={}, accountId={}",
                format, accountId);
        return new JobAcknowledgement()
                .jobId(UUID.randomUUID())
                .jobType(JobAcknowledgement.JobTypeEnum.STATEMENT)
                .status(JobAcknowledgement.StatusEnum.ACCEPTED)
                .submittedAt(OffsetDateTime.now());
    }
}
