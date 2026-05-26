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
package com.awsm2.carddemo.batch;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.batch.processor.DailyTransactionToTransactionProcessor;
import com.awsm2.carddemo.batch.reader.DailyTransactionItemReader;
import com.awsm2.carddemo.batch.writer.CombineTransactionS3ArchiveListener;
import com.awsm2.carddemo.batch.writer.TransactionJpaItemWriter;
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
import com.awsm2.carddemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@code @Configuration} class for the
 * {@code combineTransactionsJob} (chunk-oriented).
 *
 * <p><b>// Replaces: app/jcl/COMBTRAN.jcl</b> (pure DFSORT/IDCAMS utility —
 * <em>no</em> COBOL source program backs this job).</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code COMBTRAN.jcl} JCL job consisted of two utility steps:
 * <ol>
 *   <li>{@code STEP05R EXEC PGM=SORT} — concatenated the
 *       {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} (prior-day transaction
 *       backup) and {@code AWS.M2.CARDDEMO.SYSTRAN(0)} (system-generated
 *       transactions from {@code INTCALC.jcl} / {@code CBACT04C}) input
 *       datasets, sorted them by {@code TRAN-ID} (positions 1-16, CH
 *       ascending) using DFSORT {@code SORT FIELDS=(TRAN-ID,A)}, and wrote
 *       the sorted output to the GDG generation
 *       {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}.</li>
 *   <li>{@code STEP10 EXEC PGM=IDCAMS} — ran
 *       {@code REPRO INFILE(TRANSACT.COMBINED) OUTFILE(TRANVSAM)} to load
 *       the combined sorted file into the canonical VSAM KSDS cluster
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement (Chunk-Oriented)</h2>
 *
 * <p>Per AAP &sect;0.4.1 (and the CP6 review feedback F-CP6-Combine-01/02/03/04),
 * this Spring Batch job replaces the DFSORT + IDCAMS REPRO pipeline with a
 * <b>chunk-oriented</b> Spring Batch step:</p>
 * <ul>
 *   <li><b>ItemReader</b> — {@link JpaPagingItemReader} of
 *       {@link DailyTransaction} with database-level ORDER BY
 *       {@code dalytranId} ASC (replaces SORTIN concatenation +
 *       in-memory sort). Page size tuned independently of chunk size.</li>
 *   <li><b>ItemProcessor</b> — stateless field-for-field mapping from
 *       {@link DailyTransaction} to {@link Transaction}
 *       ({@link DailyTransactionToTransactionProcessor}).</li>
 *   <li><b>ItemWriter</b> — {@link TransactionJpaItemWriter} delegating
 *       to the underlying {@link org.springframework.batch.item.database.JpaItemWriter}
 *       (replaces IDCAMS REPRO bulk load).</li>
 *   <li><b>StepExecutionListener</b> — {@link CombineTransactionS3ArchiveListener}
 *       writes the versioned S3 backup once per successful step
 *       (replaces SORTOUT GDG (+1)).</li>
 *   <li><b>Skip / Retry Policy</b> — skips up to {@code skipLimit} items
 *       on {@link DataAccessException} or {@link OptimisticLockingFailureException};
 *       retries up to {@code retryLimit} times on {@link DataAccessException}
 *       (transient connection failures).</li>
 *   <li><b>Idempotent</b> — the job is idempotent at the level of
 *       {@code merge()} semantics in the JPA writer; re-runs with the
 *       same {@code batchRunId} update-in-place rather than duplicating
 *       rows. The {@code batchRunId} parameter is required and
 *       validated.</li>
 *   <li><b>RETURN-CODE Parity</b> — the job's terminal {@link ExitStatus}
 *       is mapped per {@link CardDemoExitStatus}: zero skips →
 *       {@link ExitStatus#COMPLETED}; non-zero skips →
 *       {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS} (COBOL
 *       RETURN-CODE = 4); unhandled exception → {@link ExitStatus#FAILED}
 *       (COBOL RETURN-CODE = 8).</li>
 * </ul>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>This job is <b>Stage 3</b> of the end-of-day batch pipeline
 * orchestrated by AWS Step Functions per AAP &sect;0.6.3:</p>
 * <pre>
 *   POSTTRAN → INTCALC → COMBTRAN → Parallel { CREASTMT, TRANREPT }
 * </pre>
 *
 * @see DailyTransactionItemReader
 * @see DailyTransactionToTransactionProcessor
 * @see TransactionJpaItemWriter
 * @see CombineTransactionS3ArchiveListener
 * @see CardDemoExitStatus
 */
@Configuration("combineTransactionsJobConfiguration")
public class CombineTransactionsJob {

    /**
     * SLF4J logger for the job lifecycle.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionsJob.class);

    /**
     * The Spring Batch {@link Job} bean name. AWS Batch / Step Functions
     * resolve and launch the job by this exact name; do not rename
     * without updating the corresponding AWS Batch job definition's
     * {@code commandArgs}.
     */
    public static final String JOB_NAME = "combineTransactionsJob";

    /**
     * The Spring Batch {@link Step} bean name. The single chunk step
     * comprising the read → process → write pipeline.
     */
    public static final String STEP_NAME = "combineAndLoadStep";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * required batch run identifier. Validated by the inline parameter
     * validator at job-launch time.
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * optional business date used as the S3 generation token (replaces
     * the GDG (+1) generation per AAP &sect;0.6.2).
     */
    public static final String PARAM_BUSINESS_DATE = "businessDate";

    // =========================================================================
    // Constructor-injected collaborators
    // =========================================================================

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DailyTransactionItemReader dailyTransactionItemReader;
    private final DailyTransactionToTransactionProcessor transactionProcessor;
    private final TransactionJpaItemWriter transactionWriter;
    private final CombineTransactionS3ArchiveListener s3ArchiveListener;
    private final AuditLogService auditLogService;

    /**
     * Retained for backward compatibility with existing test code that
     * verifies {@code dailyTransactionRepository.findAll()} interactions —
     * the chunk pipeline does not consume the repository directly (it
     * uses {@link JpaPagingItemReader}) but the field is preserved so
     * the dependency declaration remains stable.
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * Retained for backward compatibility with existing test code that
     * verifies {@code transactionRepository.saveAll(Iterable)}
     * interactions — the chunk pipeline persists via the JPA item
     * writer, but the field is preserved so the dependency declaration
     * remains stable.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Retained for backward compatibility with existing test code that
     * verifies the S3 backup is invoked — the {@link CombineTransactionS3ArchiveListener}
     * is the canonical caller now, but the field is preserved so the
     * dependency declaration remains stable.
     */
    private final S3OutputService s3OutputService;

    // =========================================================================
    // Externalized configuration properties (AAP §0.7.1)
    // =========================================================================

    /**
     * Spring Batch chunk size — the number of items processed and
     * committed per transaction boundary. A smaller value increases
     * commit overhead but limits the rollback scope on failure; a
     * larger value reduces commit overhead but increases the rollback
     * cost. {@code 500} is a balanced default matching sibling batch
     * jobs.
     */
    @Value("${carddemo.batch.combine.chunk-size:500}")
    private int chunkSize;

    /**
     * Skip limit — the maximum number of items that may be skipped due
     * to {@link DataAccessException} or
     * {@link OptimisticLockingFailureException} before the job is
     * declared {@link ExitStatus#FAILED}. The COBOL semantic is that
     * a non-zero reject count surfaces RETURN-CODE = 4, so this limit
     * must be aligned with operational tolerance for individual record
     * failures. Default {@code 100} provides headroom for transient
     * issues while still catching pervasive failure modes.
     */
    @Value("${carddemo.batch.combine.skip-limit:100}")
    private int skipLimit;

    /**
     * Retry limit — the maximum number of times a chunk write may be
     * retried on {@link DataAccessException} (transient connection /
     * deadlock failures) before the chunk is failed. Default
     * {@code 3} matches the Spring Batch tutorial recommended default.
     */
    @Value("${carddemo.batch.combine.retry-limit:3}")
    private int retryLimit;

    /**
     * Bulk-insert batch size — preserved for backward compatibility
     * with externalized configuration; the chunk-oriented pipeline
     * uses the {@code chunkSize} property to control commit boundaries.
     */
    @Value("${carddemo.batch.combine.bulk-insert-size:1000}")
    private int bulkInsertSize;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Constructs the {@code CombineTransactionsJob} with all required
     * collaborators via constructor injection (per AAP &sect;0.7.1).
     *
     * @param jobRepository                Spring Batch metadata repository
     * @param transactionManager           JPA transaction manager
     * @param dailyTransactionItemReader   the {@link JpaPagingItemReader}
     *                                     factory
     * @param transactionProcessor         the stateless DailyTransaction
     *                                     → Transaction processor
     * @param transactionWriter            the JPA item writer for
     *                                     the {@code transactions} table
     * @param s3ArchiveListener            the step listener that writes
     *                                     the S3 archive on step
     *                                     completion
     * @param auditLogService              the audit-log adapter for
     *                                     lifecycle events
     * @param dailyTransactionRepository   retained for backward-compat
     *                                     dependency declaration
     * @param transactionRepository        retained for backward-compat
     *                                     dependency declaration
     * @param s3OutputService              retained for backward-compat
     *                                     dependency declaration
     */
    public CombineTransactionsJob(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  DailyTransactionItemReader dailyTransactionItemReader,
                                  DailyTransactionToTransactionProcessor transactionProcessor,
                                  TransactionJpaItemWriter transactionWriter,
                                  CombineTransactionS3ArchiveListener s3ArchiveListener,
                                  AuditLogService auditLogService,
                                  DailyTransactionRepository dailyTransactionRepository,
                                  TransactionRepository transactionRepository,
                                  S3OutputService s3OutputService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dailyTransactionItemReader = dailyTransactionItemReader;
        this.transactionProcessor = transactionProcessor;
        this.transactionWriter = transactionWriter;
        this.s3ArchiveListener = s3ArchiveListener;
        this.auditLogService = auditLogService;
        this.dailyTransactionRepository = dailyTransactionRepository;
        this.transactionRepository = transactionRepository;
        this.s3OutputService = s3OutputService;
    }

    // =========================================================================
    // Bean: Job
    // =========================================================================

    /**
     * Defines the {@code combineTransactionsJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// Replaces: app/jcl/COMBTRAN.jcl entire job stream (STEP05R DFSORT
     * + STEP10 IDCAMS REPRO).</p>
     *
     * <p>The job is composed of a single chunk-oriented {@link Step}
     * ({@link #combineAndLoadStep(JobExecutionListener)}). Lifecycle
     * audit listening is delegated to the shared
     * {@code sharedAuditJobExecutionListener} bean (defined in
     * {@link BatchJobConfig}) per F-CP6-Combine-06, eliminating the
     * inline anonymous listener that previously duplicated lifecycle-
     * audit logic.</p>
     *
     * <p>Parameter validation is enforced via the shared
     * {@code standardJobParametersValidator} bean defined in
     * {@link BatchJobConfig#standardJobParametersValidator()}. This
     * validator rejects any launch attempt that omits the mandatory
     * {@code batchRunId} JobParameter &mdash; preventing untraceable
     * batch executions (AAP &sect;0.7.1 audit-traceability rule).</p>
     *
     * @param sharedAuditJobExecutionListener the shared lifecycle audit
     *                                        listener bean from
     *                                        {@link BatchJobConfig}
     * @param standardJobParametersValidator  the shared JobParameters
     *                                        validator bean from
     *                                        {@link BatchJobConfig}
     * @return the configured {@link Job} bean — registered in the
     *         {@code ApplicationContext} under the name
     *         {@value #JOB_NAME}
     */
    @Bean
    public Job combineTransactionsJob(JobExecutionListener sharedAuditJobExecutionListener,
                                      JobParametersValidator standardJobParametersValidator) {
        // Replaces: app/jcl/COMBTRAN.jcl entire job stream (STEP05R DFSORT + STEP10 IDCAMS REPRO)
        // Wire the validator so every launch is gated on a non-blank
        // batchRunId (AAP §0.7.1 audit-traceability).
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(standardJobParametersValidator)
                .listener(sharedAuditJobExecutionListener)
                .start(combineAndLoadStep(sharedAuditJobExecutionListener))
                .build();
    }

    // =========================================================================
    // Bean: Step (chunk-oriented per F-CP6-Combine-01)
    // =========================================================================

    /**
     * Defines the chunk-oriented {@link Step} that executes the combined
     * sort and bulk-load.
     *
     * <p>// Replaces: app/jcl/COMBTRAN.jcl STEP05R DFSORT + STEP10
     * IDCAMS REPRO — the entire utility pipeline implemented as a
     * Spring Batch chunk step with:</p>
     * <ol>
     *   <li>{@link JpaPagingItemReader} streaming the
     *       {@code daily_transactions} staging table page-by-page in
     *       ascending {@code dalytranId} order (replaces SORTIN
     *       concatenation + DFSORT in-memory sort);</li>
     *   <li>{@link DailyTransactionToTransactionProcessor} mapping each
     *       row field-for-field;</li>
     *   <li>{@link TransactionJpaItemWriter} bulk-inserting via Hibernate
     *       JDBC batching (replaces IDCAMS REPRO);</li>
     *   <li>{@link CombineTransactionS3ArchiveListener} writing the S3
     *       archive once per successful step (replaces SORTOUT GDG (+1));</li>
     *   <li>{@link JobExecutionListener} (shared) for lifecycle audit
     *       (replaces JES SYSPRINT step lifecycle).</li>
     * </ol>
     *
     * <h4>Skip / Retry Policy (AAP &sect;0.7.1)</h4>
     *
     * <p>The step skips up to {@link #skipLimit} items on
     * {@link DataAccessException} or
     * {@link OptimisticLockingFailureException} — the COBOL semantic
     * being that a single record's persistence failure does not abort
     * the entire batch. The step retries up to {@link #retryLimit}
     * times on {@link DataAccessException} to absorb transient
     * connection / deadlock failures.</p>
     *
     * <h4>Transactional Context</h4>
     *
     * <p>The {@link PlatformTransactionManager} supplied to
     * {@link StepBuilder#chunk(int, PlatformTransactionManager)} is the
     * {@code JpaTransactionManager} from {@code JpaConfig}; this
     * guarantees that (a) Spring Batch metadata writes
     * ({@code BATCH_STEP_EXECUTION}) and (b) the per-chunk JPA
     * inserts participate in the SAME RDS PostgreSQL transactional
     * context — the Java equivalent of the CICS SYNCPOINT semantics
     * from STEP10 IDCAMS REPRO per AAP &sect;0.4.1.</p>
     *
     * @param sharedAuditJobExecutionListener the shared lifecycle audit
     *                                        listener bean from
     *                                        {@link BatchJobConfig}
     *                                        (not used at step level but
     *                                        retained as a parameter so
     *                                        Spring resolves the
     *                                        dependency cleanly)
     * @return the configured {@link Step} bean — registered in the
     *         {@code ApplicationContext} under the name {@value #STEP_NAME}
     */
    @Bean
    public Step combineAndLoadStep(JobExecutionListener sharedAuditJobExecutionListener) {
        // Replaces: app/jcl/COMBTRAN.jcl STEP05R (DFSORT) + STEP10 (IDCAMS REPRO)
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, Transaction>chunk(chunkSize, transactionManager)
                .reader(dailyTransactionItemReader.build())
                .processor(transactionProcessor)
                .writer(transactionWriter)
                // Skip policy — skip individual record failures up to
                // skipLimit (COBOL: per-record reject semantics).
                // Per AAP §0.7.1 transactional discipline, skipped
                // records do not abort the batch but do surface in the
                // step's skip count (used by exitStatusMapper to map
                // to RETURN-CODE = 4 / COMPLETED_WITH_REJECTS).
                .faultTolerant()
                .skip(DataAccessException.class)
                .skip(OptimisticLockingFailureException.class)
                .skipLimit(skipLimit > 0 ? skipLimit : 100)
                // Retry policy — retry transient failures up to retryLimit
                // before applying the skip policy. Mirrors the COBOL
                // semantic that a connection blip should not abort the
                // batch.
                .retry(DataAccessException.class)
                .retryLimit(retryLimit > 0 ? retryLimit : 3)
                // Step listener — writes the S3 archive once per
                // successful step (replaces SORTOUT GDG (+1)).
                .listener(s3ArchiveListener)
                // Step listener — maps step skip count to COBOL
                // RETURN-CODE → Spring Batch ExitStatus per AAP §0.7.1
                // RETURN-CODE parity (F-CP6-Combine-04).
                .listener(combineExitStatusListener())
                .build();
    }

    // =========================================================================
    // Bean: StepExecutionListener — RETURN-CODE → ExitStatus mapping
    // =========================================================================

    /**
     * Defines a {@link org.springframework.batch.core.StepExecutionListener}
     * that maps the step's terminal state to a CardDemo-canonical
     * {@link ExitStatus} per AAP &sect;0.7.1 RETURN-CODE parity.
     *
     * <p>// Replaces: COBOL RETURN-CODE 4 (per CBTRN02C.cbl L229-L230
     * "MOVE 4 TO RETURN-CODE") — when {@code stepExecution.getSkipCount() > 0}
     * the step exit status is overridden to
     * {@link CardDemoExitStatus#COMPLETED_WITH_REJECTS}.</p>
     *
     * @return a {@link org.springframework.batch.core.StepExecutionListener}
     *         that consults the step's skip count and remaps
     *         {@link ExitStatus} accordingly
     */
    @Bean
    public org.springframework.batch.core.StepExecutionListener combineExitStatusListener() {
        return new org.springframework.batch.core.StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                // No-op — initial exit status is COMPLETED by default;
                // afterStep below remaps if rejects occurred.
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                final ExitStatus existing = stepExecution.getExitStatus();

                // Defensive: if the step already failed (exception),
                // preserve the FAILED exit status. RETURN-CODE = 8.
                if (existing != null && !"COMPLETED".equals(existing.getExitCode())) {
                    LOG.info(
                            "Combine step exitStatus={} preserved (RETURN-CODE != 0)",
                            existing.getExitCode());
                    return existing;
                }

                final long skipCount = stepExecution.getSkipCount();
                if (skipCount > 0) {
                    // RETURN-CODE = 4 per CBTRN02C.cbl L229-L230 — the
                    // step completed but with one or more skipped /
                    // rejected records. Step Functions Choice states
                    // can branch on this exit code via downstream SNS
                    // notifications carrying the skip count.
                    LOG.warn(
                            "Combine step completed with skipCount={} — mapping to "
                                    + "COMPLETED_WITH_REJECTS (COBOL RETURN-CODE = 4)",
                            skipCount);
                    return CardDemoExitStatus.COMPLETED_WITH_REJECTS;
                }

                // RETURN-CODE = 0 — clean completion.
                return ExitStatus.COMPLETED;
            }
        };
    }

    // =========================================================================
    // Accessors (visible for tests; package-private would be tighter,
    // but the test classes live in the same package)
    // =========================================================================

    /**
     * @return the configured chunk size for the combine step
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * @return the configured skip limit for the combine step
     */
    public int getSkipLimit() {
        return skipLimit;
    }

    /**
     * @return the configured retry limit for the combine step
     */
    public int getRetryLimit() {
        return retryLimit;
    }

    /**
     * @return the configured bulk-insert size (preserved for backward
     *         compatibility with the externalized configuration
     *         property contract)
     */
    public int getBulkInsertSize() {
        return bulkInsertSize;
    }
}
