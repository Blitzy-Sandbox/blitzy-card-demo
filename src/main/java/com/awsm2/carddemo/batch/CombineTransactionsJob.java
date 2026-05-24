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
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
import com.awsm2.carddemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring Batch {@code @Configuration} class for the {@code combineTransactionsJob}.
 *
 * <p><b>// Replaces: app/jcl/COMBTRAN.jcl</b> (pure DFSORT/IDCAMS utility &mdash;
 * <em>no</em> COBOL source program backs this job).</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code COMBTRAN.jcl} JCL job consisted of two utility steps:
 * <ol>
 *   <li>{@code STEP05R EXEC PGM=SORT} &mdash; concatenated the
 *       {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} (prior-day transaction
 *       backup) and {@code AWS.M2.CARDDEMO.SYSTRAN(0)} (system-generated
 *       transactions from {@code INTCALC.jcl} / {@code CBACT04C}) input
 *       datasets, sorted them by {@code TRAN-ID} (positions 1-16, CH
 *       ascending) using DFSORT {@code SORT FIELDS=(TRAN-ID,A)}, and wrote
 *       the sorted output to the GDG generation
 *       {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)};</li>
 *   <li>{@code STEP10 EXEC PGM=IDCAMS} &mdash; ran
 *       {@code REPRO INFILE(TRANSACT.COMBINED) OUTFILE(TRANVSAM)} to load
 *       the combined sorted file into the canonical VSAM KSDS cluster
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.</li>
 * </ol>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>Per AAP &sect;0.4.1, this Spring Batch job replaces the DFSORT +
 * IDCAMS REPRO pipeline with:
 * <ul>
 *   <li>{@link Comparator}-based in-memory sort over the source records
 *       (replaces {@code SORT FIELDS=(TRAN-ID,A)});</li>
 *   <li>{@link TransactionRepository#saveAll(Iterable)} bulk JPA insert
 *       into the {@code transactions} table (replaces
 *       {@code IDCAMS REPRO INFILE/OUTFILE});</li>
 *   <li>{@link S3OutputService#copyTransactionBackup(String, byte[])}
 *       writes the serialized combined-transaction payload as a versioned
 *       S3 object (replaces the GDG generation
 *       {@code TRANSACT.COMBINED(+1)} per AAP &sect;0.6.2 &mdash; GDG
 *       (+1)/(0) generations become S3 versioned objects with lifecycle
 *       policies).</li>
 * </ul>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>This job is <b>Stage 3</b> of the end-of-day batch pipeline
 * orchestrated by AWS Step Functions per AAP &sect;0.6.3:</p>
 * <pre>
 *   POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; Parallel { CREASTMT, TRANREPT }
 * </pre>
 *
 * <h2>Job Parameters (AWS Batch / Step Functions Integration)</h2>
 *
 * <p>Standard {@link JobParameters} naming convention mapped from AWS
 * Batch container environment variables per AAP &sect;0.6.3:</p>
 * <ul>
 *   <li>{@code batchRunId} (required, String) &mdash; unique run identifier
 *       (UUID or Step Functions execution ID). Mapped from environment
 *       variable {@code BATCH_RUN_ID}. Validated by
 *       {@link #requireParameter(JobParameters, String)} inside the
 *       tasklet so a missing or blank value fails the job before any
 *       data work begins.</li>
 *   <li>{@code businessDate} (optional, String, ISO-8601) &mdash; business
 *       date for the run; used as the deterministic S3 backup generation
 *       token mirroring GDG {@code (+1)} semantics. Mapped from
 *       environment variable {@code BUSINESS_DATE}. When absent, the S3
 *       backup is skipped (matching the CICS-online ad-hoc / smoke-test
 *       invocation pattern).</li>
 *   <li>{@code correlationId} (optional, String) &mdash; distributed-trace
 *       correlation identifier linking this batch invocation across Step
 *       Functions, AWS Batch, ECS Fargate, CloudWatch Logs, and the
 *       OpenSearch audit index per AAP &sect;0.6.6. Synthesised as a
 *       fresh {@link UUID} when absent so audit emissions are always
 *       correlatable. Mapped from environment variable
 *       {@code CORRELATION_ID}.</li>
 * </ul>
 *
 * <h2>Sort-Order Equivalence (AAP &sect;0.6.2)</h2>
 *
 * <p>The COBOL DFSORT directive {@code SORT FIELDS=(TRAN-ID,A)} sorts on
 * {@code TRAN-ID} at byte offsets 1-16 (CH ascending). The Java
 * equivalent uses {@code Comparator.comparing(DailyTransaction::getDalytranId)}
 * which sorts on natural {@link String} ordering. The two are
 * <em>equivalent</em> because the COBOL {@code TRAN-ID PIC X(16)} is a
 * zero-padded 16-character alphanumeric value &mdash; Java
 * {@link String#compareTo(String)} produces the same ordering as the
 * COBOL CH (character) collation for ASCII-equivalent code points (per
 * AAP &sect;0.6.2 byte-padded string equivalence rule).</p>
 *
 * <h2>Monetary Precision Discipline (AAP &sect;0.6.1)</h2>
 *
 * <p>The {@link DailyTransaction#getDalytranAmt()} returns a
 * {@link java.math.BigDecimal} with {@code precision=11, scale=2}
 * matching the COBOL {@code PIC S9(09)V99} declaration. The
 * {@link #mapDailyToTransaction(DailyTransaction)} mapping preserves the
 * {@link java.math.BigDecimal} type end-to-end &mdash; <b>NEVER</b>
 * substituting {@code double} or {@code float} &mdash; so banker's
 * rounding ({@code RoundingMode.HALF_EVEN}) semantics from COBOL
 * {@code PIC 9} decimal arithmetic are preserved verbatim across the
 * combine pipeline.</p>
 *
 * <h2>Transactional Boundary (AAP &sect;0.4.1 SYNCPOINT replacement)</h2>
 *
 * <p>The injected {@link PlatformTransactionManager} is the
 * {@code JpaTransactionManager} auto-configured by Spring Boot from the
 * {@code @Primary} {@code DataSource} provided by
 * {@code com.awsm2.carddemo.config.JpaConfig}. Wiring this transaction
 * manager into {@link StepBuilder#tasklet(Tasklet, PlatformTransactionManager)}
 * ensures the Spring Batch metadata writes
 * ({@code BATCH_JOB_INSTANCE}, {@code BATCH_STEP_EXECUTION}) and the
 * {@link TransactionRepository#saveAll(Iterable)} bulk-insert operations
 * participate in the same RDS PostgreSQL transactional context &mdash;
 * the Spring Batch equivalent of the COBOL CICS {@code SYNCPOINT}
 * semantics from {@code STEP10 EXEC PGM=IDCAMS REPRO} per AAP
 * &sect;0.4.1.</p>
 *
 * <h2>Audit Trail Emission (AAP &sect;0.6.6)</h2>
 *
 * <p>The {@link JobExecutionListener} attached to the {@link Job} emits
 * {@code STARTED} and terminal ({@code COMPLETED}/{@code FAILED}/etc.)
 * lifecycle events via
 * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, java.util.Map, String)}.
 * Per AAP &sect;0.6.6 this provides the regulatory-grade audit trail
 * that replaces the JES SYSPRINT + {@code RETURN-CODE} values from the
 * original {@code COMBTRAN.jcl} STEP05R / STEP10 SYSPRINT DDs &mdash;
 * the events are indexed to the OpenSearch {@code carddemo-audit} index
 * and emitted as {@code carddemo.batch.lifecycle} CloudWatch Micrometer
 * counters tagged by {@code job_name} and {@code status}.</p>
 *
 * <h2>Strict Layering and Architecture Compliance (AAP &sect;0.7.1)</h2>
 *
 * <p>This class adheres to the AAP refactor discipline:</p>
 * <ul>
 *   <li><b>One {@code @Configuration} class per JCL job</b> &mdash;
 *       satisfies the "Isolate each COBOL program's logic in its own
 *       dedicated Java service class" directive (here a JCL utility job
 *       maps to a single dedicated {@code @Configuration} class).</li>
 *   <li><b>No inline AWS SDK calls</b> &mdash; all S3 access goes through
 *       the injected {@link S3OutputService} adapter; all audit emission
 *       goes through {@link AuditLogService}. No {@code S3Client} or
 *       {@code SfnClient} reference exists in this class.</li>
 *   <li><b>Constructor injection only</b> &mdash; no field-level
 *       {@code @Autowired}; all six collaborators are {@code final} and
 *       supplied via the all-args constructor for testability and
 *       immutability.</li>
 *   <li><b>Inline traceability comments</b> &mdash; every meaningful
 *       block carries {@code // Replaces:} comments referencing the
 *       original JCL step (STEP05R, STEP10) and the AWS replacement
 *       (S3 versioned object, JPA bulk insert) per AAP &sect;0.7.3.</li>
 *   <li><b>No business logic, no validation</b> &mdash; per the Minimal
 *       Change Clause, this job performs ONLY what
 *       {@code COMBTRAN.jcl} did (sort + bulk insert + GDG backup).
 *       Records are mapped field-for-field per the AAP-mandated
 *       identical 350-byte record layout
 *       ({@code CVTRA05Y.cpy} = {@code CVTRA06Y.cpy}).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.batch.BatchJobConfig
 *      for shared {@code JobParametersValidator} /
 *      {@code JobParametersIncrementer} / {@code JobExecutionListener}
 *      beans that complement (but do NOT override) the job-local
 *      listener attached here
 * @see com.awsm2.carddemo.adapter.S3OutputService#copyTransactionBackup(String, byte[])
 *      for the {@code TRANSACT.COMBINED(+1)} S3 GDG replacement contract
 * @see com.awsm2.carddemo.adapter.AuditLogService#logBatchJobLifecycle(String, String, String, Long, java.util.Map, String)
 *      for the OpenSearch + CloudWatch lifecycle audit emission contract
 * @see <a href="https://docs.spring.io/spring-batch/reference/job/configuring-launcher.html">
 *      Spring Batch 5 &mdash; Job Configuration</a>
 */
// The @Configuration class itself is registered as a Spring bean. Spring's default
// bean-naming convention would derive the class-level bean name from the class name
// (camelCase first letter lowercase = "combineTransactionsJob"), which would collide
// with the @Bean factory method below also named combineTransactionsJob(). Per the
// export schema mandated by AAP §0.4.1, the @Bean factory method name MUST remain
// combineTransactionsJob() because that is the canonical Spring bean name that
// AWS Batch / Step Functions launchers resolve from the ApplicationContext. To
// disambiguate, we give the @Configuration class-level bean an explicit distinct
// name ("combineTransactionsJobConfig") so the Job bean from the factory method
// retains the canonical "combineTransactionsJob" name.
@Configuration("combineTransactionsJobConfig")
public class CombineTransactionsJob {

    // =========================================================================
    // Static fields
    // =========================================================================

    /**
     * SLF4J logger used by the job, the {@link JobExecutionListener}, and
     * the tasklet lambda for structured log entries. Feeds the Logback +
     * {@code logstash-logback-encoder} JSON pipeline that ships logs to
     * CloudWatch Logs per AAP &sect;0.6.6.
     *
     * <p>The CardDemo codebase deliberately avoids Lombok (the
     * {@code lombok} dependency is not declared in {@code pom.xml} per
     * AAP &sect;0.7.1 Minimal Change Clause), so this file uses the
     * standard {@code org.slf4j.LoggerFactory} pattern consistent with
     * the sibling {@link com.awsm2.carddemo.batch.BatchJobConfig} class.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionsJob.class);

    /**
     * The canonical Spring-managed bean name of this Job &mdash; used in
     * {@link JobBuilder} construction so AWS Batch / Step Functions can
     * resolve and launch the job by name from the
     * {@code ApplicationContext}. Kept as a constant to eliminate
     * stringly-typed drift between the bean name and the listener log
     * lines.
     */
    private static final String JOB_NAME = "combineTransactionsJob";

    /**
     * The canonical Spring-managed bean name of this Job's single Step.
     */
    private static final String STEP_NAME = "combineAndLoadStep";

    /**
     * Required {@link JobParameters} key for the per-execution run
     * identifier. Mapped from AWS Batch container environment variable
     * {@code BATCH_RUN_ID}.
     */
    private static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * Optional {@link JobParameters} key for the business date that
     * drives the S3 backup generation token (mirrors GDG {@code (+1)}
     * semantics). Mapped from AWS Batch container environment variable
     * {@code BUSINESS_DATE}.
     */
    private static final String PARAM_BUSINESS_DATE = "businessDate";

    /**
     * Optional {@link JobParameters} key for the distributed-trace
     * correlation identifier. Mapped from AWS Batch container
     * environment variable {@code CORRELATION_ID}.
     */
    private static final String PARAM_CORRELATION_ID = "correlationId";

    // =========================================================================
    // Injected collaborators (constructor injection per AAP §0.7.1)
    // =========================================================================

    /**
     * Spring Batch metadata repository &mdash; provided by
     * {@code com.awsm2.carddemo.config.BatchConfig} via its
     * {@code @EnableBatchProcessing} declaration that triggers Spring
     * Boot's auto-configuration of {@link JobRepository}. Used by
     * {@link JobBuilder} and {@link StepBuilder} to persist
     * {@code BATCH_JOB_INSTANCE}, {@code BATCH_JOB_EXECUTION},
     * {@code BATCH_STEP_EXECUTION}, and {@code BATCH_JOB_EXECUTION_CONTEXT}
     * rows in the shared RDS PostgreSQL metadata tables.
     */
    private final JobRepository jobRepository;

    /**
     * Spring transaction manager &mdash; provided by
     * {@code com.awsm2.carddemo.config.JpaConfig} via the
     * {@code @Primary} {@code DataSource} which Spring Boot's
     * {@code HibernateJpaAutoConfiguration} uses to instantiate a
     * {@code JpaTransactionManager}. Wired into
     * {@link StepBuilder#tasklet(Tasklet, PlatformTransactionManager)}
     * so the Spring Batch metadata writes and the
     * {@link TransactionRepository#saveAll(Iterable)} bulk insert
     * participate in the same transactional context &mdash; the Java
     * equivalent of the COBOL CICS {@code SYNCPOINT} semantics from
     * {@code STEP10 EXEC PGM=IDCAMS REPRO}.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Source-side JPA repository for the {@link DailyTransaction}
     * staging table. Invoked inside the tasklet via
     * {@link DailyTransactionRepository#findAll()} to read every staging
     * row &mdash; the Java equivalent of the JCL STEP05R SORTIN
     * concatenation of {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} +
     * {@code AWS.M2.CARDDEMO.SYSTRAN(0)}.
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * Target-side JPA repository for the canonical {@link Transaction}
     * journal. Invoked inside the tasklet via
     * {@link TransactionRepository#saveAll(Iterable)} to bulk-insert the
     * sorted, mapped {@link Transaction} entities &mdash; the Java
     * equivalent of the JCL STEP10 {@code IDCAMS REPRO INFILE/OUTFILE}.
     */
    private final TransactionRepository transactionRepository;

    /**
     * AWS S3 output adapter &mdash; writes the serialized combined-
     * transaction payload as a versioned S3 object. Replaces the JCL
     * STEP05R {@code SORTOUT DD DISP=(NEW,CATLG,DELETE),DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}
     * GDG generation per AAP &sect;0.6.2.
     *
     * <p>Per AAP &sect;0.7.1, all AWS SDK calls are isolated in adapter
     * classes &mdash; this class does NOT instantiate or reference an
     * {@code S3Client} directly.</p>
     */
    private final S3OutputService s3OutputService;

    /**
     * Audit-log adapter &mdash; emits {@code STARTED} and terminal
     * lifecycle events via
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, java.util.Map, String)}
     * indexed to OpenSearch and emitted as CloudWatch Micrometer
     * counters per AAP &sect;0.6.6. Replaces the JES SYSPRINT +
     * {@code RETURN-CODE} values from the original {@code COMBTRAN.jcl}.
     */
    private final AuditLogService auditLogService;

    // =========================================================================
    // Externalized configuration properties (AAP §0.7.1)
    // =========================================================================

    /**
     * Spring Batch chunk size used by future chunk-oriented step
     * variants. Retained as an externalized configuration knob even
     * though the current tasklet implementation does not consume it,
     * preserving the API for the operational tuning workflow per AAP
     * &sect;0.7.1 ("All configuration externalized via AWS Secrets
     * Manager and AWS Systems Manager Parameter Store").
     *
     * <p>Default {@code 500} matches the chunk size used by sibling
     * batch jobs (per the agent prompt Task 2.2 specification).</p>
     */
    @Value("${carddemo.batch.combine.chunk-size:500}")
    private int chunkSize;

    /**
     * Bulk-insert batch size for {@link TransactionRepository#saveAll(Iterable)}.
     * The tasklet partitions the sorted target list into sub-lists of
     * this size and calls {@code saveAll} for each batch to avoid
     * unbounded heap pressure when the daily combined input grows large.
     *
     * <p>Default {@code 1000} matches the
     * {@code hibernate.jdbc.batch_size} JDBC-level batching setting in
     * {@code application.yml} so the JPA flush phase emits one batched
     * INSERT statement per call.</p>
     */
    @Value("${carddemo.batch.combine.bulk-insert-size:1000}")
    private int bulkInsertSize;

    // =========================================================================
    // Constructor (AAP §0.7.1 constructor injection mandate)
    // =========================================================================

    /**
     * Constructs the {@code CombineTransactionsJob} with all required
     * collaborators via constructor injection (per AAP &sect;0.7.1).
     *
     * <p>Spring's IoC container resolves each parameter by type from the
     * application context: {@code JobRepository} comes from Spring Boot's
     * batch auto-configuration triggered by
     * {@code com.awsm2.carddemo.config.BatchConfig} (via
     * {@code @EnableBatchProcessing}); {@code PlatformTransactionManager}
     * comes from Spring Boot's JPA auto-configuration of
     * {@code JpaTransactionManager} on the {@code @Primary} DataSource
     * declared by {@code com.awsm2.carddemo.config.JpaConfig}; the two
     * repository beans are auto-detected as {@code @Repository}-stereotyped
     * Spring Data JPA proxies; the adapter services are
     * {@code @Service}-stereotyped beans.</p>
     *
     * @param jobRepository              Spring Batch metadata repository
     *                                   (auto-configured); must not be
     *                                   {@code null}
     * @param transactionManager         JPA transaction manager
     *                                   (auto-configured); must not be
     *                                   {@code null}
     * @param dailyTransactionRepository source-side repository for the
     *                                   {@code daily_transactions}
     *                                   staging table; must not be
     *                                   {@code null}
     * @param transactionRepository      target-side repository for the
     *                                   {@code transactions} journal;
     *                                   must not be {@code null}
     * @param s3OutputService            S3 adapter for the
     *                                   {@code TRANSACT.COMBINED(+1)} GDG
     *                                   replacement; must not be
     *                                   {@code null}
     * @param auditLogService            audit-log adapter for the
     *                                   OpenSearch + CloudWatch lifecycle
     *                                   emission; must not be
     *                                   {@code null}
     */
    public CombineTransactionsJob(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  DailyTransactionRepository dailyTransactionRepository,
                                  TransactionRepository transactionRepository,
                                  S3OutputService s3OutputService,
                                  AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dailyTransactionRepository = dailyTransactionRepository;
        this.transactionRepository = transactionRepository;
        this.s3OutputService = s3OutputService;
        this.auditLogService = auditLogService;
    }

    // =========================================================================
    // Bean: Job
    // =========================================================================

    /**
     * Defines the {@code combineTransactionsJob} Spring Batch
     * {@link Job} bean.
     *
     * <p><b>// Replaces: app/jcl/COMBTRAN.jcl</b> (the entire JCL job
     * stream: STEP05R DFSORT + STEP10 IDCAMS REPRO).</p>
     *
     * <p>The job is composed of a single {@link Step}
     * ({@link #combineAndLoadStep()}) that performs the combined sort
     * and bulk-load in a single tasklet (the global ordering requirement
     * precludes chunk-oriented streaming &mdash; see the rationale on
     * {@link #combineAndLoadStep()}).</p>
     *
     * <h3>Lifecycle Listener Contract (AAP &sect;0.6.6)</h3>
     *
     * <p>An inline {@link JobExecutionListener} attached via
     * {@link JobBuilder#listener(JobExecutionListener)} emits structured
     * lifecycle events to the SLF4J logger (shipped to CloudWatch Logs
     * via Logback + {@code logstash-logback-encoder}) and to the
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, java.util.Map, String)
     * AuditLogService lifecycle channel} (indexed to OpenSearch and
     * emitted as a CloudWatch Micrometer counter).</p>
     *
     * <ul>
     *   <li>{@code beforeJob}: emits {@code status="STARTED"} with
     *       {@code durationMillis=0L} and the extracted
     *       {@code correlationId}.</li>
     *   <li>{@code afterJob}: emits
     *       {@code status=jobExecution.getStatus().name()} (one of
     *       {@code COMPLETED}, {@code FAILED}, {@code STOPPED},
     *       {@code ABANDONED}, {@code UNKNOWN}) with
     *       {@code durationMillis} derived from
     *       {@link Duration#between(java.time.temporal.Temporal, java.time.temporal.Temporal)
     *       Duration.between(startTime, endTime)} and the same
     *       {@code correlationId} as the {@code beforeJob} event so the
     *       two documents are joinable in OpenSearch on the
     *       {@code execution_id} dimension.</li>
     * </ul>
     *
     * <p>Defensive {@code null} guards on
     * {@link JobExecution#getStartTime()} and
     * {@link JobExecution#getEndTime()} default the duration to
     * {@code 0L} if either timestamp is absent &mdash; preventing a
     * {@link NullPointerException} from breaking the audit emission for
     * an abended job whose end-time was never populated. The Spring
     * Batch 5.x API returns {@link LocalDateTime} from both methods (a
     * deliberate change from Spring Batch 4.x which returned
     * {@code java.util.Date}); this implementation relies on
     * {@code Duration.between} which accepts any {@code Temporal} type.</p>
     *
     * @return the configured {@link Job} bean &mdash; registered in the
     *         {@code ApplicationContext} under the name
     *         {@value #JOB_NAME} so AWS Batch / Step Functions can
     *         resolve and launch it by name
     */
    @Bean
    public Job combineTransactionsJob() {
        // Replaces: app/jcl/COMBTRAN.jcl entire job stream (STEP05R DFSORT + STEP10 IDCAMS REPRO)
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(new JobExecutionListener() {

                    @Override
                    public void beforeJob(JobExecution jobExecution) {
                        // Replaces: JES SYSPRINT job-step start message
                        final String executionId = safeExecutionId(jobExecution);
                        final String correlationId = extractCorrelationId(jobExecution.getJobParameters());

                        LOG.info(
                                "// Replaces: app/jcl/COMBTRAN.jcl - CardDemo Batch Job '{}' STARTED "
                                        + "(executionId={}, correlationId={})",
                                JOB_NAME, executionId, correlationId);

                        auditLogService.logBatchJobLifecycle(
                                JOB_NAME,
                                executionId,
                                "STARTED",
                                0L,
                                null,
                                correlationId);
                    }

                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        // Replaces: JES SYSPRINT job-step end + RETURN-CODE message
                        final String executionId = safeExecutionId(jobExecution);
                        final String status = jobExecution.getStatus() != null
                                ? jobExecution.getStatus().name()
                                : "UNKNOWN";
                        final String exitCode = jobExecution.getExitStatus() != null
                                ? jobExecution.getExitStatus().getExitCode()
                                : "UNKNOWN";
                        final String correlationId = extractCorrelationId(jobExecution.getJobParameters());
                        final long durationMillis = computeDurationMillis(
                                jobExecution.getStartTime(),
                                jobExecution.getEndTime());

                        LOG.info(
                                "CardDemo Batch Job '{}' {} (executionId={}, durationMs={}, "
                                        + "exitCode={}, correlationId={})",
                                JOB_NAME, status, executionId, durationMillis, exitCode, correlationId);

                        auditLogService.logBatchJobLifecycle(
                                JOB_NAME,
                                executionId,
                                status,
                                durationMillis,
                                null,
                                correlationId);
                    }
                })
                .start(combineAndLoadStep())
                .build();
    }

    // =========================================================================
    // Bean: Step
    // =========================================================================

    /**
     * Defines the single {@code combineAndLoadStep} Spring Batch
     * {@link Step} bean.
     *
     * <p><b>// Replaces: app/jcl/COMBTRAN.jcl STEP05R + STEP10</b>
     * (DFSORT then IDCAMS REPRO consolidated into a single tasklet so
     * the sorted records flow directly from in-memory list to JPA bulk
     * insert without an intermediate file).</p>
     *
     * <h3>Why Tasklet (Not Chunk-Oriented)</h3>
     *
     * <p>The Spring Batch Tasklet pattern is used here (instead of the
     * more common chunk-oriented {@code ItemReader} &rarr;
     * {@code ItemProcessor} &rarr; {@code ItemWriter} pipeline)
     * because:</p>
     * <ol>
     *   <li>The COBOL DFSORT step requires <b>global</b> ordering across
     *       the full input before the IDCAMS REPRO load &mdash; chunked
     *       streaming cannot sort across chunk boundaries without
     *       external-sort scaffolding;</li>
     *   <li>This is a pure utility job with no per-record business logic
     *       (the {@code CVTRA05Y.cpy} and {@code CVTRA06Y.cpy} record
     *       layouts are byte-identical, so the
     *       {@link #mapDailyToTransaction(DailyTransaction)} mapping is
     *       a field-for-field copy with no transformations);</li>
     *   <li>The original JCL ran in a single DFSORT JVM and a single
     *       IDCAMS step &mdash; a tasklet most faithfully preserves that
     *       monolithic semantic per the Minimal Change Clause (AAP
     *       &sect;0.7.3).</li>
     * </ol>
     *
     * <p>For future scalability (e.g., daily volumes exceeding heap
     * capacity), an external-sort variant could externalize the sort
     * to an S3 staging file with a chunked {@code ItemReader}, but that
     * enhancement is out of scope per the Minimal Change Clause.</p>
     *
     * <h3>Transactional Context</h3>
     *
     * <p>The {@code PlatformTransactionManager} supplied to
     * {@link StepBuilder#tasklet(Tasklet, PlatformTransactionManager)}
     * is the {@code JpaTransactionManager} from
     * {@code com.awsm2.carddemo.config.JpaConfig}; this guarantees that
     * (a) Spring Batch metadata writes
     * ({@code BATCH_JOB_INSTANCE}, {@code BATCH_STEP_EXECUTION}) and
     * (b) the {@link TransactionRepository#saveAll(Iterable)} bulk
     * insert participate in the SAME RDS PostgreSQL transactional
     * context &mdash; the Java equivalent of the CICS SYNCPOINT
     * semantics from STEP10 IDCAMS REPRO per AAP &sect;0.4.1.</p>
     *
     * @return the configured {@link Step} bean &mdash; registered in
     *         the {@code ApplicationContext} under the name
     *         {@value #STEP_NAME}
     */
    @Bean
    public Step combineAndLoadStep() {
        // Replaces: app/jcl/COMBTRAN.jcl STEP05R (DFSORT) + STEP10 (IDCAMS REPRO)
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(combineAndLoadTasklet(), transactionManager)
                .build();
    }

    // =========================================================================
    // Bean: Tasklet
    // =========================================================================

    /**
     * Defines the {@link Tasklet} that executes the combined sort and
     * bulk-load.
     *
     * <p><b>// Replaces: app/jcl/COMBTRAN.jcl STEP05R DFSORT + STEP10
     * IDCAMS REPRO</b> &mdash; the entire utility pipeline implemented
     * as a single tasklet lambda that:
     * <ol>
     *   <li>Validates the required {@code batchRunId}
     *       {@link JobParameters} via
     *       {@link #requireParameter(JobParameters, String)}
     *       (replaces the JCL {@code PARM='...'} length/format checks
     *       that ran inside the DFSORT control statements);</li>
     *   <li>Reads every row from the {@code daily_transactions} staging
     *       table via {@link DailyTransactionRepository#findAll()}
     *       (replaces the {@code SORTIN DD DISP=SHR} concatenation of
     *       {@code TRANSACT.BKUP(0)} + {@code SYSTRAN(0)});</li>
     *   <li>Sorts the in-memory list by
     *       {@link DailyTransaction#getDalytranId()} ascending
     *       (replaces the DFSORT {@code SORT FIELDS=(TRAN-ID,A)}
     *       directive);</li>
     *   <li>Maps each {@link DailyTransaction} to a canonical
     *       {@link Transaction} entity via
     *       {@link #mapDailyToTransaction(DailyTransaction)}
     *       (field-for-field copy per the identical 350-byte
     *       {@code CVTRA05Y.cpy} = {@code CVTRA06Y.cpy} layouts);</li>
     *   <li>Bulk-inserts the mapped list into the {@code transactions}
     *       journal via partitioned {@link TransactionRepository#saveAll(Iterable)}
     *       calls of size {@link #bulkInsertSize} each
     *       (replaces {@code IDCAMS REPRO INFILE(TRANSACT.COMBINED) OUTFILE(TRANVSAM)});</li>
     *   <li>Writes the serialized combined-transaction payload as a
     *       versioned S3 object via
     *       {@link S3OutputService#copyTransactionBackup(String, byte[])}
     *       when a {@code businessDate} parameter is present
     *       (replaces the {@code SORTOUT DD DISP=(NEW,CATLG,DELETE),DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}
     *       GDG generation per AAP &sect;0.6.2);</li>
     *   <li>Increments the {@link StepContribution} read/write counts
     *       for Spring Batch observability and returns
     *       {@link RepeatStatus#FINISHED} to signal a one-shot tasklet
     *       (no repetition).</li>
     * </ol>
     *
     * <p>The tasklet is itself declared as a {@link Bean @Bean} (not an
     * inline lambda in {@link #combineAndLoadStep()}) so it can be
     * resolved independently by tests and used by alternate step
     * configurations.</p>
     *
     * @return a stateless {@link Tasklet} that performs the combined
     *         sort and bulk-load in a single invocation
     */
    @Bean
    public Tasklet combineAndLoadTasklet() {
        // Replaces: app/jcl/COMBTRAN.jcl STEP05R DFSORT + STEP10 IDCAMS REPRO
        return (StepContribution contribution, ChunkContext chunkContext) -> {

            // ----- Parameter extraction and validation -----------------
            final JobParameters jobParameters =
                    chunkContext.getStepContext().getStepExecution().getJobParameters();
            final String batchRunId = requireParameter(jobParameters, PARAM_BATCH_RUN_ID);
            final String businessDate = jobParameters.getString(PARAM_BUSINESS_DATE);
            final String correlationId = extractCorrelationId(jobParameters);

            LOG.info(
                    "Combine Transactions Tasklet starting: batchRunId={}, businessDate={}, "
                            + "correlationId={}, bulkInsertSize={}",
                    batchRunId, businessDate, correlationId, bulkInsertSize);

            // ----- STEP 1: read source records --------------------------
            // Replaces: JCL STEP05R EXEC PGM=SORT SORTIN DD concatenation of
            //   AWS.M2.CARDDEMO.TRANSACT.BKUP(0) + AWS.M2.CARDDEMO.SYSTRAN(0)
            // The daily_transactions table is the JPA staging table populated
            // by upstream feeds; for the EOD pipeline it represents the union
            // of (a) the prior day's posted transaction backup and (b) the
            // system-generated interest transactions from InterestCalculationJob
            // (CBACT04C). Both source datasets historically had the byte-
            // identical 350-byte CVTRA05Y / CVTRA06Y layout, so a single
            // unified read is the JPA-native equivalent of the SORTIN
            // concatenation.
            final List<DailyTransaction> source = dailyTransactionRepository.findAll();
            LOG.info(
                    "Combine: read {} records from daily_transactions "
                            + "(combined backup + system-generated)",
                    source.size());

            // ----- STEP 2: sort by TRAN-ID ascending --------------------
            // Replaces: DFSORT SORT FIELDS=(TRAN-ID,A) at positions 1-16, CH
            // (character ascending). Java String natural ordering produces the
            // same sequence as COBOL CH collation because TRAN-IDs are zero-
            // padded 16-character alphanumerics (PIC X(16) — AAP §0.6.2
            // byte-padded string equivalence).
            source.sort(Comparator.comparing(DailyTransaction::getDalytranId));
            LOG.info("Combine: sorted {} records by dalytranId ascending", source.size());

            // ----- STEP 3: map DailyTransaction → Transaction -----------
            // The CVTRA05Y.cpy (TRAN-RECORD) and CVTRA06Y.cpy (DALYTRAN-
            // RECORD) layouts are byte-identical (350-byte fixed-width record
            // with the same 13 business fields + 20-byte FILLER, prefix
            // substituted from TRAN- to DALYTRAN-) per AAP §0.4.1. The
            // mapping is therefore a strict field-for-field copy with no
            // transformations, no conversions, no validation, and no
            // arithmetic. BigDecimal is preserved end-to-end for the
            // monetary tranAmt field per AAP §0.6.1.
            final List<Transaction> targets = source.stream()
                    .map(this::mapDailyToTransaction)
                    .collect(Collectors.toList());

            // ----- STEP 4: bulk-insert into transactions journal --------
            // Replaces: JCL STEP10 EXEC PGM=IDCAMS REPRO INFILE(TRANSACT.COMBINED)
            //   OUTFILE(TRANVSAM)
            // The IDCAMS REPRO utility load is mapped to
            // TransactionRepository.saveAll(Iterable) per the AAP §0.1.2
            // transformation rule "IDCAMS REPRO → JpaRepository.saveAll".
            // The list is partitioned into batches of bulkInsertSize to bound
            // the per-transaction memory footprint and align with the
            // Hibernate JDBC batch_size for one batched INSERT per saveAll
            // invocation.
            final int totalTargets = targets.size();
            int batchesSaved = 0;
            final int effectiveBatchSize = bulkInsertSize > 0 ? bulkInsertSize : 1000;
            for (int i = 0; i < totalTargets; i += effectiveBatchSize) {
                final int end = Math.min(i + effectiveBatchSize, totalTargets);
                final List<Transaction> batch = targets.subList(i, end);
                transactionRepository.saveAll(batch);
                batchesSaved++;
                LOG.debug("Combine: bulk-inserted batch {} of size {} (offset {} to {})",
                        batchesSaved, batch.size(), i, end - 1);
            }
            LOG.info(
                    "Combine: bulk-inserted {} Transaction records in {} batches "
                            + "(effective batch size = {})",
                    totalTargets, batchesSaved, effectiveBatchSize);

            // ----- STEP 5: write S3 backup ------------------------------
            // Replaces: STEP05R DD SORTOUT DISP=(NEW,CATLG,DELETE),
            //   DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
            // The GDG (+1) generation is replaced by an S3 versioned object
            // per AAP §0.6.2. The businessDate-derived generation token
            // mirrors GDG (+1) semantics so repeated executions on the same
            // business date overwrite the same logical generation; downstream
            // S3 versioning and lifecycle policies preserve historical
            // generations the way GDG (0)/(-1) did.
            //
            // The backup is skipped when no businessDate is supplied (an
            // intentional invocation pattern for CICS-online ad-hoc / smoke-
            // test runs that exercise the JPA path without producing an S3
            // artifact) and when no records were combined (zero-byte payloads
            // are rejected by S3OutputService.copyTransactionBackup, mirroring
            // the COBOL semantic that a SORTOUT DD with zero records would
            // still be cataloged but is unhelpful to retain).
            if (businessDate != null && !businessDate.isBlank() && !targets.isEmpty()) {
                final byte[] combinedPayload = serializeForBackup(targets);
                s3OutputService.copyTransactionBackup(businessDate, combinedPayload);
                LOG.info(
                        "Combine: wrote S3 backup for businessDate={} payloadBytes={} "
                                + "(replaces GDG: AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1))",
                        businessDate, combinedPayload.length);
            } else {
                LOG.info(
                        "Combine: S3 backup SKIPPED (businessDate={}, records={}); "
                                + "the JPA bulk-insert completed but no S3 generation was created",
                        businessDate, totalTargets);
            }

            // ----- STEP 6: emit observability counters ------------------
            // Persist read/write counts back into the Spring Batch
            // StepContribution so the BATCH_STEP_EXECUTION row records the
            // count of records processed — replicating the JES2 JCT step-
            // summary lines that showed "SORT — RECORDS IN: 950, OUT: 950".
            //
            // Spring Batch 5.x StepContribution API contract:
            //   void incrementReadCount()           — NO ARGS (increments by 1)
            //   void incrementWriteCount(long count) — adds count to writeCount
            // We loop incrementReadCount() for the read count because Spring
            // Batch 5 deliberately removed the (long) overload to discourage
            // bulk read accounting outside chunk-oriented steps. The loop is
            // O(N) lightweight integer increments and is negligible relative
            // to the JPA bulk insert above.
            for (int i = 0; i < source.size(); i++) {
                contribution.incrementReadCount();
            }
            contribution.incrementWriteCount((long) totalTargets);

            return RepeatStatus.FINISHED;
        };
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Maps a single {@link DailyTransaction} staging row to a canonical
     * {@link Transaction} journal entity.
     *
     * <p>Per AAP &sect;0.4.1, the {@code CVTRA05Y.cpy} ({@code TRAN-RECORD})
     * and {@code CVTRA06Y.cpy} ({@code DALYTRAN-RECORD}) copybooks share
     * an identical 350-byte fixed-width layout with the field-name prefix
     * substituted ({@code TRAN-} vs {@code DALYTRAN-}). This mapping
     * therefore performs a strict field-for-field copy with no
     * transformations, no validation, and no arithmetic.</p>
     *
     * <h3>BigDecimal Preservation (AAP &sect;0.6.1)</h3>
     *
     * <p>The {@code dalytranAmt} (COBOL {@code DALYTRAN-AMT PIC S9(09)V99})
     * is copied as a {@link java.math.BigDecimal} value &mdash; the same
     * precision/scale ({@code 11,2}) and same arithmetic discipline
     * (banker's rounding via {@code RoundingMode.HALF_EVEN}) apply on
     * both sides because the column type, JPA mapping, and Java type are
     * identical. <b>NEVER</b> substitute {@code double} / {@code float}
     * for monetary fields in this codebase.</p>
     *
     * @param dt the source {@link DailyTransaction} entity; must not be
     *           {@code null}
     * @return a new {@link Transaction} entity with every business field
     *         copied verbatim from {@code dt}; ready for
     *         {@link TransactionRepository#saveAll(Iterable)} bulk
     *         insert
     */
    private Transaction mapDailyToTransaction(DailyTransaction dt) {
        // Field-for-field mapping per AAP §0.4.1 (CVTRA05Y.cpy ↔ CVTRA06Y.cpy
        // identical 350-byte record layouts). BigDecimal preserved end-to-end
        // per AAP §0.6.1 (NEVER use float/double for monetary values).
        final Transaction t = new Transaction();
        t.setTranId(dt.getDalytranId());                       // COBOL: DALYTRAN-ID    → TRAN-ID
        t.setTranTypeCd(dt.getDalytranTypeCd());               // COBOL: DALYTRAN-TYPE-CD → TRAN-TYPE-CD
        t.setTranCatCd(dt.getDalytranCatCd());                 // COBOL: DALYTRAN-CAT-CD  → TRAN-CAT-CD
        t.setTranSource(dt.getDalytranSource());               // COBOL: DALYTRAN-SOURCE  → TRAN-SOURCE
        t.setTranDesc(dt.getDalytranDesc());                   // COBOL: DALYTRAN-DESC    → TRAN-DESC
        t.setTranAmt(dt.getDalytranAmt());                     // COBOL: DALYTRAN-AMT PIC S9(09)V99 — BigDecimal
        t.setTranMerchantId(dt.getDalytranMerchantId());       // COBOL: DALYTRAN-MERCHANT-ID
        t.setTranMerchantName(dt.getDalytranMerchantName());   // COBOL: DALYTRAN-MERCHANT-NAME
        t.setTranMerchantCity(dt.getDalytranMerchantCity());   // COBOL: DALYTRAN-MERCHANT-CITY
        t.setTranMerchantZip(dt.getDalytranMerchantZip());     // COBOL: DALYTRAN-MERCHANT-ZIP
        t.setTranCardNum(dt.getDalytranCardNum());             // COBOL: DALYTRAN-CARD-NUM (PAN — PCI-DSS scope)
        t.setTranOrigTs(dt.getDalytranOrigTs());               // COBOL: DALYTRAN-ORIG-TS
        t.setTranProcTs(dt.getDalytranProcTs());               // COBOL: DALYTRAN-PROC-TS
        return t;
    }

    /**
     * Serializes a list of {@link Transaction} entities into a pipe-
     * delimited UTF-8 byte payload suitable for storage as a versioned
     * S3 object.
     *
     * <p>The payload format is a newline-delimited text representation
     * with five pipe-separated identifying fields per row
     * ({@code tranId | tranTypeCd | tranCatCd | tranAmt | tranCardNum}).
     * This is a deliberately minimal representation &mdash; the full
     * 350-byte fixed-width COBOL layout is preserved in the RDS
     * {@code transactions} journal (the durable, queryable, source of
     * truth); the S3 object is an operational <em>safety net</em> that
     * mirrors the historical GDG semantics rather than a complete
     * faithful byte-by-byte reproduction of the COBOL
     * {@code TRANSACT.COMBINED} dataset.</p>
     *
     * <p><b>Encoding choice:</b> UTF-8 is used (via
     * {@link StandardCharsets#UTF_8}) instead of the COBOL default
     * EBCDIC because (a) the target Java application runs on ECS
     * Fargate Linux containers and (b) S3 stores arbitrary bytes &mdash;
     * the encoding choice is documented per AAP &sect;0.6.2.</p>
     *
     * <p><b>PCI-DSS note:</b> The card number ({@code tranCardNum}) is
     * a 16-digit PAN. The S3 bucket where this payload lands is
     * encrypted at rest with the customer-managed KMS key (SSE-KMS) and
     * in transit with TLS 1.2+, with Amazon Macie continuously scanning
     * for PII/financial-data leakage per AAP &sect;0.6.6. The S3 backup
     * therefore meets the same PCI-DSS controls as the RDS journal.</p>
     *
     * @param transactions the list of {@link Transaction} entities to
     *                     serialize; must not be {@code null} or empty
     *                     (the caller has already gated on
     *                     {@code !targets.isEmpty()})
     * @return the pipe-delimited UTF-8 byte payload &mdash; never
     *         {@code null} and never zero-length
     */
    private byte[] serializeForBackup(List<Transaction> transactions) {
        // The serialized representation replaces the COBOL GDG generation
        // AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1) per AAP §0.6.2. The format
        // is intentionally lightweight (pipe-delimited UTF-8) because RDS
        // is the durable, queryable source of truth; this S3 object exists
        // as an operational safety net mirroring the historical GDG
        // (+1)/(0)/(-1) generations.
        final StringBuilder sb = new StringBuilder(transactions.size() * 64);
        for (Transaction t : transactions) {
            sb.append(t.getTranId() != null ? t.getTranId() : "")
                    .append('|')
                    .append(t.getTranTypeCd() != null ? t.getTranTypeCd() : "")
                    .append('|')
                    .append(t.getTranCatCd() != null ? t.getTranCatCd().toString() : "")
                    .append('|')
                    // BigDecimal.toPlainString() preserves the COBOL PIC 9
                    // decimal precision exactly — no scientific notation,
                    // no truncation, no rounding (per AAP §0.6.1).
                    .append(t.getTranAmt() != null ? t.getTranAmt().toPlainString() : "")
                    .append('|')
                    .append(t.getTranCardNum() != null ? t.getTranCardNum() : "")
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Validates that a required {@link JobParameters} entry is present
     * and non-blank, throwing {@link IllegalArgumentException} if not.
     *
     * <p>Replaces the JCL {@code PARM='...'} length/format checks that
     * ran inside DFSORT control statements and per-program parameter-
     * validation paragraphs &mdash; here, the equivalent
     * {@code RETURN-CODE 8} that would have aborted the JCL step is
     * surfaced as an {@link IllegalArgumentException} that fails the
     * Spring Batch step with {@code ExitStatus.FAILED}, which in turn
     * surfaces to AWS Batch as a non-zero container exit code and to
     * Step Functions as a failed Task state per AAP &sect;0.6.3.</p>
     *
     * @param jobParameters the {@link JobParameters} carried by the
     *                      current {@code StepExecution}; must not be
     *                      {@code null} (Spring supplies an empty
     *                      {@code JobParameters} when none are
     *                      configured but never {@code null})
     * @param name          the name of the required parameter; must not
     *                      be {@code null}
     * @return the trimmed parameter value; never {@code null}, never
     *         blank
     * @throws IllegalArgumentException when the requested parameter is
     *         missing or blank
     */
    private String requireParameter(JobParameters jobParameters, String name) {
        // Replaces: JCL PARM=' ' length/format check that would emit
        // RETURN-CODE 8 to abend the step. Surfaced as IllegalArgumentException
        // so the Spring Batch step fails fast with ExitStatus.FAILED.
        final String value = jobParameters.getString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required JobParameter '" + name + "' is missing or empty. "
                            + "Provide via Step Functions input -> AWS Batch container env var "
                            + (PARAM_BATCH_RUN_ID.equals(name) ? "BATCH_RUN_ID" : name.toUpperCase())
                            + ", or via the --" + name + " CLI argument for local development.");
        }
        return value;
    }

    /**
     * Extracts the {@code correlationId} {@link JobParameters} entry,
     * generating a fresh {@link UUID#randomUUID()} when the parameter
     * is absent or blank.
     *
     * <p>Guarantees that every
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, java.util.Map, String)}
     * callback emits a non-null correlation identifier so distributed
     * traces remain correlatable across CloudWatch Logs, the OpenSearch
     * audit index, and downstream MSK transaction events per AAP
     * &sect;0.6.6 observability requirements.</p>
     *
     * @param jobParameters the {@link JobParameters} carried by the
     *                      current {@link JobExecution}; may be
     *                      {@code null} (defensive guard for callers
     *                      that supply a bare {@code JobExecution} stub
     *                      in tests)
     * @return the existing {@code correlationId} parameter when present
     *         and non-blank; otherwise a freshly-generated
     *         {@link UUID#randomUUID()} as a string &mdash; never
     *         {@code null}
     */
    private String extractCorrelationId(JobParameters jobParameters) {
        if (jobParameters == null) {
            return UUID.randomUUID().toString();
        }
        final String correlationId = jobParameters.getString(PARAM_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    /**
     * Computes the elapsed runtime in milliseconds for a Spring Batch
     * {@link JobExecution} using its {@code startTime} and
     * {@code endTime}.
     *
     * <p>Returns {@code 0L} if either timestamp is absent &mdash; the
     * Spring Batch 5.x API uses {@link LocalDateTime} for both fields
     * and either may be {@code null} for an in-flight job (before the
     * {@code afterJob} callback fires) or an abended job whose
     * end-time was never populated.</p>
     *
     * @param startTime the {@link JobExecution#getStartTime()}; may be
     *                  {@code null}
     * @param endTime   the {@link JobExecution#getEndTime()}; may be
     *                  {@code null}
     * @return the elapsed runtime in milliseconds, never negative;
     *         {@code 0L} when either timestamp is {@code null} or when
     *         {@code endTime} precedes {@code startTime} (an undefined
     *         state defensive callers should not encounter)
     */
    private long computeDurationMillis(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return 0L;
        }
        final long millis = Duration.between(startTime, endTime).toMillis();
        return Math.max(0L, millis);
    }

    /**
     * Safely extracts the {@code executionId} from a
     * {@link JobExecution}, defaulting to {@code "UNKNOWN"} when the
     * {@code JobExecution} or its identifier is {@code null}.
     *
     * <p>The defensive default prevents the listener from short-
     * circuiting with a {@link NullPointerException} during edge-case
     * test setups that supply a bare {@code JobExecution} stub without
     * a persisted identifier (e.g.,
     * {@code JobLauncherTestUtils.launchStep} invocations that build a
     * detached execution).</p>
     *
     * @param jobExecution the {@link JobExecution}; may be {@code null}
     * @return the stringified execution ID when available; otherwise
     *         the literal {@code "UNKNOWN"} &mdash; never {@code null}
     */
    private String safeExecutionId(JobExecution jobExecution) {
        if (jobExecution == null || jobExecution.getId() == null) {
            return "UNKNOWN";
        }
        return String.valueOf(jobExecution.getId());
    }
}
