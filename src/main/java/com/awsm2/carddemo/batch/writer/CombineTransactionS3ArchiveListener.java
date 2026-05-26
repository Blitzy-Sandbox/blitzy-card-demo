/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.batch.writer;

import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.annotation.AfterChunk;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * Spring Batch {@link StepExecutionListener} that writes a single
 * versioned S3 backup object at the end of the combine step.
 *
 * <p><b>// Replaces: app/jcl/COMBTRAN.jcl STEP05R DD SORTOUT
 * DISP=(NEW,CATLG,DELETE), DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)</b>
 * — the GDG generation (+1) is implemented here as an S3 versioned
 * object via {@link S3OutputService#copyTransactionBackup(String, byte[])}
 * per AAP &sect;0.6.2.</p>
 *
 * <h2>Why a Listener (Not an ItemWriter)</h2>
 *
 * <p>The S3 backup is a step-summary artifact, not a per-item write:</p>
 * <ul>
 *   <li>The COBOL semantic produces ONE GDG generation per job run
 *       containing the complete sorted set of records — not one S3
 *       object per record.</li>
 *   <li>Writing per-chunk S3 objects would create multiple GDG-
 *       equivalent generations per job, violating the COBOL semantic.</li>
 *   <li>The chunk-oriented {@code ItemWriter} pipeline handles the
 *       per-record JPA insert; the S3 backup is orthogonal to that
 *       pipeline.</li>
 * </ul>
 *
 * <p>Implementing the S3 backup as a {@link StepExecutionListener#afterStep}
 * callback isolates the AWS SDK call from the per-chunk transaction
 * boundary (S3 is non-transactional and cannot participate in the JPA
 * chunk transaction) and ensures the backup is written once per
 * successful step execution.</p>
 *
 * <h2>Idempotency &amp; Re-Run Safety (F-CP6-Combine-05)</h2>
 *
 * <p>The listener writes the S3 object using the {@code businessDate}
 * parameter as the generation token. S3 object versioning preserves
 * historical generations the same way the GDG (0)/(-1) generations did
 * on z/OS — re-running the job for the same business date overwrites
 * the same logical generation in S3 while preserving the version
 * history. The {@code batchRunId} parameter is logged on every
 * invocation for traceability per AAP &sect;0.6.3.</p>
 *
 * <h2>Skip Conditions</h2>
 *
 * <p>The backup is skipped when:</p>
 * <ul>
 *   <li><b>No {@code businessDate} parameter</b> — ad-hoc / smoke-test
 *       runs that exercise the JPA path without producing an S3
 *       artifact. This is an intentional invocation pattern for CICS-
 *       online ad-hoc inspection.</li>
 *   <li><b>Step ExitStatus is not COMPLETED</b> — failed or stopped
 *       steps do not produce a backup (mirrors the COBOL semantic that
 *       a failed STEP05R would not catalog a SORTOUT DD).</li>
 *   <li><b>Write count is zero</b> — empty datasets do not produce a
 *       backup (mirrors the COBOL semantic that a SORTOUT DD with
 *       zero records would still be cataloged but is unhelpful to
 *       retain).</li>
 * </ul>
 *
 * <h2>PCI-DSS Note</h2>
 *
 * <p>The backup payload may include {@code TRAN-CARD-NUM} (PAN). The
 * S3 bucket where this payload lands is encrypted at rest with the
 * customer-managed KMS key (SSE-KMS) and in transit with TLS 1.2+,
 * with Amazon Macie continuously scanning for PII/financial-data
 * leakage per AAP &sect;0.6.6. The S3 backup therefore meets the
 * same PCI-DSS controls as the RDS journal.</p>
 *
 * <h2>Source Lineage (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>JCL:</b> {@code app/jcl/COMBTRAN.jcl} STEP05R SORTOUT DD
 *       GDG generation</li>
 *   <li><b>COBOL:</b> none — pure DFSORT output</li>
 *   <li><b>GDG base:</b> {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED} →
 *       S3 versioned object per AAP &sect;0.6.2</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.adapter.S3OutputService#copyTransactionBackup(String, byte[])
 * @see com.awsm2.carddemo.batch.CombineTransactionsJob
 */
@Component
public class CombineTransactionS3ArchiveListener implements StepExecutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionS3ArchiveListener.class);

    /**
     * The {@link org.springframework.batch.core.JobParameters} key used
     * to extract the business date (used as the S3 object's generation
     * token; replaces the JCL {@code SORTOUT DD GDG (+1)} semantics).
     */
    public static final String PARAM_BUSINESS_DATE = "businessDate";

    /**
     * The {@link org.springframework.batch.core.JobParameters} key used
     * to extract the batch run ID (logged for traceability per AAP
     * &sect;0.6.3 idempotency requirements; not used as the S3 object
     * key but recorded in the audit emission).
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    private final S3OutputService s3OutputService;
    private final TransactionRepository transactionRepository;

    /**
     * Constructs the listener with its required collaborators.
     *
     * @param s3OutputService       the S3 adapter for writing the
     *                              versioned backup object; must not
     *                              be {@code null}
     * @param transactionRepository the journal repository, used to
     *                              read the newly-inserted batch of
     *                              transactions for serialization to
     *                              S3; must not be {@code null}
     */
    public CombineTransactionS3ArchiveListener(S3OutputService s3OutputService,
                                               TransactionRepository transactionRepository) {
        this.s3OutputService = s3OutputService;
        this.transactionRepository = transactionRepository;
    }

    /**
     * No-op before-step callback. The listener performs its work
     * exclusively in {@link #afterStep(StepExecution)} once the chunk
     * pipeline has committed all inserts to the {@code transactions}
     * journal.
     *
     * @param stepExecution the current step execution (unused)
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        // Intentional no-op. The S3 backup is written after the
        // chunk pipeline has committed all per-chunk transactions to
        // the JPA journal — see afterStep().
    }

    /**
     * Writes the S3 backup object after the combine step completes
     * successfully.
     *
     * <p>// Replaces: JCL COMBTRAN.jcl STEP05R DD SORTOUT
     * DISP=(NEW,CATLG,DELETE), DSN=...COMBINED(+1)</p>
     *
     * <p>Reads the newly-loaded transactions from the journal in
     * ascending {@code tranId} order, serializes them to a pipe-
     * delimited UTF-8 byte payload, and uploads via
     * {@link S3OutputService#copyTransactionBackup(String, byte[])}.
     * The backup is skipped under the conditions documented in the
     * class-level Javadoc.</p>
     *
     * @param stepExecution the current step execution carrying the
     *                      {@link org.springframework.batch.core.JobParameters},
     *                      write count, and exit status
     * @return the step execution's existing {@link ExitStatus}, or
     *         {@link ExitStatus#FAILED} if the S3 archive write fails
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        final ExitStatus exitStatus = stepExecution.getExitStatus();
        final String businessDate =
                stepExecution.getJobParameters().getString(PARAM_BUSINESS_DATE);
        final String batchRunId =
                stepExecution.getJobParameters().getString(PARAM_BATCH_RUN_ID);

        // Skip if the step did not complete successfully — mirrors the
        // COBOL semantic that a failed STEP05R would not catalog the
        // SORTOUT DD generation.
        if (exitStatus == null || !"COMPLETED".equals(exitStatus.getExitCode())) {
            LOG.info(
                    "Combine S3 Archive: SKIPPED because step exitStatus={} "
                            + "(batchRunId={}, businessDate={})",
                    exitStatus != null ? exitStatus.getExitCode() : "null",
                    batchRunId, businessDate);
            return exitStatus;
        }

        // Skip if no records were written — mirrors the COBOL semantic
        // that a zero-record SORTOUT DD would catalog an empty
        // generation but is unhelpful to retain.
        if (stepExecution.getWriteCount() == 0) {
            LOG.info(
                    "Combine S3 Archive: SKIPPED because writeCount=0 "
                            + "(batchRunId={}, businessDate={})",
                    batchRunId, businessDate);
            return exitStatus;
        }

        // Skip if no businessDate parameter — ad-hoc / smoke-test
        // runs that exercise the JPA path without producing an S3
        // artifact. This is an intentional invocation pattern for
        // CICS-online ad-hoc inspection per AAP §0.6.2.
        if (businessDate == null || businessDate.isBlank()) {
            LOG.info(
                    "Combine S3 Archive: SKIPPED because no businessDate "
                            + "(batchRunId={}, writeCount={})",
                    batchRunId, stepExecution.getWriteCount());
            return exitStatus;
        }

        // Read the journal in ascending tranId order so the S3 object
        // mirrors the COBOL SORTOUT GDG sort order exactly.
        final List<Transaction> allTransactions = transactionRepository.findAll();
        allTransactions.sort(Comparator.comparing(Transaction::getTranId));

        // Serialize to a pipe-delimited UTF-8 byte payload — the same
        // format as the original CombineTransactionsJob tasklet, kept
        // unchanged to preserve byte-for-byte parity with any
        // downstream consumer that reads the S3 archive.
        final byte[] payload = serializeForBackup(allTransactions);

        // Upload to S3 — the SSE-KMS encryption, versioning, and TLS
        // 1.2+ in-transit protection are configured on the S3OutputService
        // per AAP §0.7.1. Spring Batch logs and swallows exceptions thrown
        // from afterStep callbacks, so the listener must explicitly mark
        // the StepExecution FAILED to preserve the JCL DISP=(...,DELETE)
        // failure semantic when the GDG-equivalent S3 archive cannot be
        // cataloged.
        try {
            s3OutputService.copyTransactionBackup(businessDate, payload);
        } catch (RuntimeException e) {
            stepExecution.setStatus(BatchStatus.FAILED);
            stepExecution.addFailureException(e);
            LOG.error(
                    "Combine S3 Archive: FAILED to write backup for businessDate={} "
                            + "(batchRunId={}, writeCount={}); marking step FAILED",
                    businessDate, batchRunId, stepExecution.getWriteCount(), e);
            return ExitStatus.FAILED.addExitDescription(e);
        }

        LOG.info(
                "Combine S3 Archive: wrote {} bytes for businessDate={} "
                        + "(batchRunId={}, writeCount={}, replaces GDG: "
                        + "AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1))",
                payload.length, businessDate, batchRunId,
                stepExecution.getWriteCount());

        return exitStatus;
    }

    /**
     * Optional after-chunk callback invoked by Spring Batch after each
     * chunk commit. Intentionally a no-op — the S3 backup is written
     * once after the full step completes (see
     * {@link #afterStep(StepExecution)}).
     *
     * @param context the current chunk context (unused)
     */
    @AfterChunk
    public void afterChunk(ChunkContext context) {
        // Intentional no-op. The S3 archive is written once per step,
        // not once per chunk.
    }

    /**
     * Serializes a list of {@link Transaction} entities into a pipe-
     * delimited UTF-8 byte payload suitable for storage as a versioned
     * S3 object.
     *
     * <p>The payload format is a newline-delimited text representation
     * with five pipe-separated identifying fields per row
     * ({@code tranId | tranTypeCd | tranCatCd | tranAmt | tranCardNum}).
     * This is a deliberately minimal representation — the full 350-byte
     * fixed-width COBOL layout is preserved in the RDS
     * {@code transactions} journal (the durable, queryable source of
     * truth); the S3 object is an operational safety net that mirrors
     * the historical GDG semantics rather than a complete faithful
     * byte-by-byte reproduction of the COBOL TRANSACT.COMBINED
     * dataset.</p>
     *
     * @param transactions the list of {@link Transaction} entities to
     *                     serialize; must not be {@code null} or empty
     * @return the pipe-delimited UTF-8 byte payload — never {@code null}
     *         and never zero-length
     */
    private byte[] serializeForBackup(List<Transaction> transactions) {
        // The serialized representation replaces the COBOL GDG
        // generation AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1) per AAP
        // §0.6.2. The format is intentionally lightweight (pipe-
        // delimited UTF-8) because RDS is the durable, queryable
        // source of truth; this S3 object exists as an operational
        // safety net mirroring the historical GDG (+1)/(0)/(-1)
        // generations.
        final StringBuilder sb = new StringBuilder(transactions.size() * 64);
        for (Transaction t : transactions) {
            sb.append(t.getTranId() != null ? t.getTranId() : "")
                    .append('|')
                    .append(t.getTranTypeCd() != null ? t.getTranTypeCd() : "")
                    .append('|')
                    .append(t.getTranCatCd() != null ? t.getTranCatCd().toString() : "")
                    .append('|')
                    // BigDecimal.toPlainString() preserves the COBOL
                    // PIC 9 decimal precision exactly — no scientific
                    // notation, no truncation, no rounding (per AAP §0.6.1).
                    .append(t.getTranAmt() != null ? t.getTranAmt().toPlainString() : "")
                    .append('|')
                    .append(t.getTranCardNum() != null ? t.getTranCardNum() : "")
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
