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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared Spring Batch infrastructure for {@code com.awsm2.carddemo.batch} jobs.
 *
 * <p><b>Replaces:</b> shared JCL {@code JCLLIB} / PROC mechanics that, on z/OS,
 * factored out the common parameter, condition-code, and SYSPRINT/SYSOUT
 * handling boilerplate shared by every job step in the end-of-day pipeline.</p>
 *
 * <h2>Purpose</h2>
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.3, this {@code @Configuration} class
 * provides reusable beans that standardize {@code JobParameters} handling,
 * validation, and audit-logging conventions for the five CardDemo batch
 * jobs:</p>
 * <ul>
 *   <li>{@code DailyTransactionPostingJob} &mdash; replaces
 *       {@code app/cbl/CBTRN02C.cbl} + {@code app/jcl/POSTTRAN.jcl}</li>
 *   <li>{@code InterestCalculationJob} &mdash; replaces
 *       {@code app/cbl/CBACT04C.cbl} + {@code app/jcl/INTCALC.jcl}</li>
 *   <li>{@code CombineTransactionsJob} &mdash; replaces
 *       {@code app/jcl/COMBTRAN.jcl} (pure utility, DFSORT/REPRO)</li>
 *   <li>{@code StatementGenerationJob} &mdash; replaces
 *       {@code app/cbl/CBSTM03A.CBL} + {@code app/cbl/CBSTM03B.CBL} +
 *       {@code app/jcl/CREASTMT.JCL}</li>
 *   <li>{@code TransactionReportJob} &mdash; replaces
 *       {@code app/cbl/CBTRN03C.cbl} + {@code app/jcl/TRANREPT.jcl}</li>
 * </ul>
 *
 * <h2>Strict Bean-Definition Discipline &mdash; What This Class Does NOT Do</h2>
 * <p>The {@code @EnableBatchProcessing} annotation, {@code JobRepository},
 * {@code JobLauncher}, {@code asyncJobLauncher}, {@code batchTaskExecutor},
 * and {@code PlatformTransactionManager} are <strong>already</strong> owned
 * by the foundational {@code com.awsm2.carddemo.config.BatchConfig} class.
 * This supplementary {@code BatchJobConfig} class therefore <strong>MUST
 * NOT</strong> re-declare any of those bean definitions &mdash; duplicating
 * {@code @EnableBatchProcessing} would trigger
 * {@code BeanDefinitionOverrideException} (or
 * {@code NoUniqueBeanDefinitionException} for {@code JobRepository} and
 * {@code JobLauncher}) at {@code ApplicationContext} refresh, especially
 * given {@code spring.main.allow-bean-definition-overriding=false} is set
 * in {@code application.yml} per AAP &sect;0.7.1.</p>
 *
 * <p>Two configuration classes named {@code BatchConfig}-ish coexist
 * intentionally:</p>
 * <ul>
 *   <li>{@code com.awsm2.carddemo.config.BatchConfig} &mdash; foundational
 *       Spring Batch infrastructure (executor, async launcher, schema
 *       initialization policy, auto-start gate).</li>
 *   <li>{@code com.awsm2.carddemo.batch.BatchJobConfig} (this class) &mdash;
 *       CardDemo-specific job conventions (parameter validation, run-id
 *       incrementer, shared audit listener).</li>
 * </ul>
 *
 * <h2>AWS Batch / Step Functions Integration (AAP &sect;0.6.3)</h2>
 * <p>Standard {@code JobParameters} naming convention expected from
 * AWS Step Functions input &rarr; AWS Batch container environment
 * variables &rarr; Spring Batch {@code JobParameters}:</p>
 * <ul>
 *   <li>{@code BATCH_RUN_ID} &rarr; {@code batchRunId} (required for every
 *       job; validated by {@link #standardJobParametersValidator()})</li>
 *   <li>{@code BUSINESS_DATE} &rarr; {@code businessDate}</li>
 *   <li>{@code PARM_DATE} &rarr; {@code parmDate}
 *       ({@code CBACT04C}-specific, {@code YYYYMMDDHH})</li>
 *   <li>{@code STATEMENT_MONTH} &rarr; {@code statementMonth}
 *       ({@code CBSTM03A}/{@code B}-specific, {@code YYYY-MM})</li>
 *   <li>{@code START_DATE} &rarr; {@code startDate}
 *       ({@code CBTRN03C}-specific, ISO-8601)</li>
 *   <li>{@code END_DATE} &rarr; {@code endDate}
 *       ({@code CBTRN03C}-specific, ISO-8601)</li>
 *   <li>{@code CORRELATION_ID} &rarr; {@code correlationId} (optional;
 *       defaulted to a fresh {@link java.util.UUID UUID} if absent so every
 *       audit emission has a non-null correlation key)</li>
 * </ul>
 *
 * <h2>Lombok Avoidance &mdash; SLF4J Logger Pattern</h2>
 * <p>The CardDemo codebase deliberately avoids Lombok &mdash; the
 * {@code lombok} package is not declared in {@code pom.xml} per AAP
 * &sect;0.7.1 Minimal Change Clause. This file therefore uses the standard
 * {@code org.slf4j.LoggerFactory} pattern consistent with siblings such as
 * {@code SecretsManagerConfig.java}, {@code OpenSearchConfig.java}, and
 * {@code AuditLogService.java} that all declare
 * {@code private static final Logger LOG = LoggerFactory.getLogger(...)}.</p>
 *
 * @see com.awsm2.carddemo.adapter.AuditLogService#logBatchJobLifecycle(String, String, String, Long, java.util.Map, String)
 * @see <a href="https://docs.spring.io/spring-batch/reference/job/configuring-launcher.html">
 *      Spring Batch 5 &mdash; Job Configuration</a>
 */
@Configuration
public class BatchJobConfig {

    /**
     * SLF4J logger emitting structured INFO-level lifecycle log entries from
     * the {@link #sharedAuditJobExecutionListener(AuditLogService) shared
     * audit listener}. Feeds the Logback + logstash-logback-encoder JSON
     * pipeline that ships structured logs to CloudWatch Logs per AAP
     * &sect;0.6.6.
     */
    private static final Logger LOG = LoggerFactory.getLogger(BatchJobConfig.class);

    /**
     * Required {@code JobParameter} name asserted by
     * {@link #standardJobParametersValidator()}. Mirrors the AWS Batch
     * environment variable {@code BATCH_RUN_ID} populated by the Step
     * Functions {@code Task} state input parameters per AAP &sect;0.6.3.
     */
    private static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * Optional {@code JobParameter} name read by
     * {@link #extractCorrelationId(JobParameters)} for distributed
     * trace correlation across CloudWatch Logs, OpenSearch audit index,
     * and downstream MSK events.
     */
    private static final String PARAM_CORRELATION_ID = "correlationId";

    // -------------------------------------------------------------------
    // Phase 2 — Bean: Standard JobParametersValidator
    // -------------------------------------------------------------------

    /**
     * Provides the standard {@link JobParametersValidator} for CardDemo
     * batch jobs. Individual jobs attach this validator to their
     * {@code JobBuilder} via {@code .validator(...)} so the validator runs
     * before the {@code JobLauncher} starts the job &mdash; preventing the
     * job from being launched with missing required parameters and catching
     * AWS Batch misconfiguration (e.g., a missing {@code BATCH_RUN_ID}
     * environment variable) early.
     *
     * <p><b>Replaces:</b> the JCL {@code PARM='...'} parameter and
     * {@code SYMBOLIC=&value} substitution checks that ran inside
     * {@code IEFBR14} dummy steps or the program's own
     * {@code WHEN-OTHER}-based parameter validation block (as in
     * {@code app/cbl/CBACT04C.cbl} where {@code WS-PARM-DATE} was checked
     * against a length-10 mask).</p>
     *
     * <h3>Validation Rules</h3>
     * <ul>
     *   <li>{@code JobParameters} must be non-null (defensive guard;
     *       Spring normally supplies an empty {@code JobParameters} but
     *       never {@code null}).</li>
     *   <li>{@code batchRunId} must be present and non-blank &mdash; every
     *       CardDemo batch job in production runs under AWS Batch +
     *       Step Functions, both of which supply a per-execution
     *       {@code batchRunId} for traceability and idempotency.</li>
     * </ul>
     *
     * <p>The {@code correlationId} parameter is intentionally NOT required
     * because the {@link #extractCorrelationId(JobParameters) helper}
     * synthesises a fresh {@link UUID} when absent, ensuring downstream
     * audit emissions are always correlatable.</p>
     *
     * @return a stateless {@link JobParametersValidator} suitable for
     *         attaching to every CardDemo {@code Job} via
     *         {@code .validator(standardJobParametersValidator)}
     */
    @Bean
    public JobParametersValidator standardJobParametersValidator() {
        // Replaces: PARM='...' length/format checks scattered across JCL
        // EXEC statements and per-program parameter-validation paragraphs
        // (e.g., CBACT04C 0500-PARM-CHECK).
        return (JobParameters jobParameters) -> {
            if (jobParameters == null) {
                throw new JobParametersInvalidException(
                        "JobParameters must not be null. Spring Batch should supply at least an empty "
                                + "JobParameters instance; a null value indicates a programmatic launch "
                                + "error in the upstream caller.");
            }
            String batchRunId = jobParameters.getString(PARAM_BATCH_RUN_ID);
            if (batchRunId == null || batchRunId.isBlank()) {
                throw new JobParametersInvalidException(
                        "Required JobParameter '" + PARAM_BATCH_RUN_ID + "' is missing or empty. "
                                + "Provide via Step Functions input -> AWS Batch container env var "
                                + "BATCH_RUN_ID, or via the --carddemo.batch.runId CLI argument for "
                                + "local development.");
            }
            // correlationId is intentionally OPTIONAL — extractCorrelationId(...)
            // synthesises a fresh UUID when absent so the AuditLogService
            // call sites always receive a non-null correlation key.
        };
    }

    // -------------------------------------------------------------------
    // Phase 3 — Bean: Shared JobParametersIncrementer
    // -------------------------------------------------------------------

    /**
     * Provides the standard {@link JobParametersIncrementer} for CardDemo
     * batch jobs. Uses Spring Batch's built-in {@link RunIdIncrementer}
     * which appends a monotonically increasing {@code run.id} property to
     * the supplied {@link JobParameters} on each invocation, guaranteeing
     * that successive launches produce distinct
     * {@code BATCH_JOB_INSTANCE} rows in the Spring Batch metadata
     * tables.
     *
     * <p>Spring Batch refuses by default to re-launch a {@code Job}
     * with parameters identical to an already-completed
     * {@code JobInstance} (it throws
     * {@code JobInstanceAlreadyCompleteException}). Attaching this
     * incrementer to a {@code JobBuilder} via {@code .incrementer(...)}
     * allows callers to invoke {@code JobLauncher.run(job,
     * incrementer.getNext(prevParams))} or the convenience
     * {@code JobOperator.startNextInstance(jobName)} to create a fresh
     * instance.</p>
     *
     * <h3>When This Matters</h3>
     * <ul>
     *   <li><b>Local development:</b> repeated {@code mvn spring-boot:run}
     *       (or {@code java -jar carddemo.jar}) launches with otherwise-
     *       identical {@code JobParameters} &mdash; the incremented
     *       {@code run.id} keeps them distinct.</li>
     *   <li><b>AWS Batch + Step Functions:</b> each execution supplies a
     *       per-execution {@code batchRunId} so this incrementer is
     *       largely redundant in production. It remains useful as a
     *       defensive default in case a Step Functions input misses
     *       {@code BATCH_RUN_ID}.</li>
     * </ul>
     *
     * <p><b>Replaces:</b> the {@code JES2 JCT} job number that
     * monotonically increased with each {@code TSO SUBMIT}, ensuring
     * distinct catalog entries on z/OS even for identically-parameterised
     * batch submissions.</p>
     *
     * @return the configured {@link JobParametersIncrementer}; the
     *         returned instance is a {@link RunIdIncrementer} for
     *         {@code instanceof} introspection by Spring Batch test
     *         harnesses
     */
    @Bean
    public JobParametersIncrementer cardDemoJobParametersIncrementer() {
        // Replaces: JES2 JCT job-number assignment that guaranteed
        // distinct catalog entries for each TSO SUBMIT, allowing operators
        // to re-run a JCL job with identical parameters without
        // collision.
        return new RunIdIncrementer();
    }

    // -------------------------------------------------------------------
    // Phase 4 — Bean: Shared JobExecutionListener
    // -------------------------------------------------------------------

    /**
     * Provides a centralised {@link JobExecutionListener} that logs batch
     * job lifecycle transitions (start, complete, fail, abend) to:
     * <ol>
     *   <li>The {@link #LOG SLF4J logger} (consumed by Logback +
     *       {@code logstash-logback-encoder} and shipped to CloudWatch
     *       Logs per AAP &sect;0.6.6 observability requirements);</li>
     *   <li>The {@link AuditLogService#logBatchJobLifecycle(String,
     *       String, String, Long, java.util.Map, String) AuditLogService
     *       lifecycle channel} which indexes events into the OpenSearch
     *       audit index and emits the
     *       {@code carddemo.batch.lifecycle} Micrometer counter that
     *       drives CloudWatch dashboards and alarms.</li>
     * </ol>
     *
     * <p>Individual CardDemo job classes currently use inline
     * {@code JobExecutionListener} implementations for job-name-specific
     * log formatting. This shared listener is provided as a centralised
     * alternative recommended for newly added jobs (e.g., a future
     * {@code ReconciliationJob}) where the standard log format suffices,
     * eliminating boilerplate duplication.</p>
     *
     * <p><b>Replaces:</b> the JCL {@code //SYSPRINT DD SYSOUT=*} that
     * captured z/OS job-step start and end messages plus the
     * {@code RETURN-CODE} that was emitted to the JES2 SYSPRINT for
     * operator inspection &mdash; both consolidated here into a single
     * structured audit emission per lifecycle transition.</p>
     *
     * <h3>beforeJob Contract</h3>
     * <p>Emits {@code status="STARTED"} with {@code durationMillis=0L},
     * a {@code null} payload, and the extracted {@code correlationId}.
     * The {@code JobParameters} are stringified to the structured log
     * (audit emission does not include the parameters because they are
     * indexed separately on the {@code execution_id} composite document
     * ID).</p>
     *
     * <h3>afterJob Contract</h3>
     * <p>Emits {@code status=jobExecution.getStatus().name()} (one of
     * {@code COMPLETED}, {@code FAILED}, {@code STOPPED}, {@code ABANDONED},
     * {@code UNKNOWN} per the {@code BatchStatus} enum) with
     * {@code durationMillis} derived from
     * {@code Duration.between(startTime, endTime).toMillis()} and the
     * same {@code correlationId} as the {@code beforeJob} event so the
     * two documents are joinable in OpenSearch on {@code execution_id}
     * (the {@code AuditLogService} suffixes the document ID with the
     * status to keep start and complete documents distinct while
     * preserving the join key per the
     * {@link AuditLogService#logBatchJobLifecycle} Javadoc).</p>
     *
     * <p>Defensive null guards on {@code getStartTime()} and
     * {@code getEndTime()} default the duration to {@code 0L} if either
     * timestamp is absent &mdash; preventing a {@code NullPointerException}
     * from breaking the audit emission for an abended job whose
     * end-time was never populated. The Spring Batch 5.x API returns
     * {@link LocalDateTime} from both methods (a deliberate change
     * from Spring Batch 4.x which returned {@code java.util.Date}); this
     * implementation relies on
     * {@link Duration#between(java.time.temporal.Temporal, java.time.temporal.Temporal)
     * Duration.between} which accepts any {@code Temporal} type.</p>
     *
     * @param auditLogService injected via the {@code @Bean} method
     *                        parameter mechanism &mdash; Spring's
     *                        dependency-injection container locates the
     *                        single {@code @Service} bean declared by
     *                        {@link AuditLogService} and supplies it
     *                        here without any field-level
     *                        {@code @Autowired} annotation
     * @return a stateless {@link JobExecutionListener} suitable for
     *         attaching to a {@code Job} via
     *         {@code .listener(sharedAuditJobExecutionListener)}
     */
    @Bean
    public JobExecutionListener sharedAuditJobExecutionListener(AuditLogService auditLogService) {
        // Replaces: SYSPRINT DD SYSOUT=* + RETURN-CODE inspection that
        // captured z/OS job-step lifecycle on JES2 spool. Consolidated
        // here into a single structured audit emission per lifecycle
        // transition, indexed into OpenSearch + emitted as a CloudWatch
        // Micrometer counter for alarming.
        return new JobExecutionListener() {

            @Override
            public void beforeJob(JobExecution jobExecution) {
                final String jobName = safeJobName(jobExecution);
                final String executionId = safeExecutionId(jobExecution);
                final JobParameters jobParameters = jobExecution.getJobParameters();
                final String correlationId = extractCorrelationId(jobParameters);

                LOG.info("CardDemo Batch Job '{}' STARTED (executionId={}, params={})",
                        jobName,
                        executionId,
                        jobParameters != null ? jobParameters.getParameters() : "{}");

                auditLogService.logBatchJobLifecycle(
                        jobName,
                        executionId,
                        "STARTED",
                        0L,
                        null,
                        correlationId);
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                final String jobName = safeJobName(jobExecution);
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

                LOG.info("CardDemo Batch Job '{}' {} (executionId={}, durationMs={}, exitCode={})",
                        jobName,
                        status,
                        executionId,
                        durationMillis,
                        exitCode);

                auditLogService.logBatchJobLifecycle(
                        jobName,
                        executionId,
                        status,
                        durationMillis,
                        null,
                        correlationId);
            }
        };
    }

    // -------------------------------------------------------------------
    // Private helpers (package-private would expose them to in-package
    // tests; static-private keeps the surface area minimal per AAP
    // §0.7.1 Minimal Change Clause).
    // -------------------------------------------------------------------

    /**
     * Extracts the {@code correlationId} {@link JobParameters} entry,
     * generating a fresh {@link UUID#randomUUID()} when the parameter
     * is absent or blank. Guarantees that every
     * {@link AuditLogService#logBatchJobLifecycle} callback emits a
     * non-null correlation identifier so distributed traces remain
     * correlatable across CloudWatch Logs, OpenSearch audit index, and
     * downstream MSK transaction events per AAP &sect;0.6.6
     * observability requirements.
     *
     * <p>This helper is {@code private static} (not exposed via the
     * {@code BatchJobConfig} public API) because it is purely an
     * internal listener concern; any caller that needs a correlation
     * ID should source it from the MDC (per the application's
     * {@code logback-spring.xml} pattern) or the upstream Step Functions
     * execution input.</p>
     *
     * @param jobParameters the {@link JobParameters} carried by the
     *                      current {@link JobExecution}; may be
     *                      {@code null} (defensive guard for callers
     *                      that supply a bare {@code JobExecution}
     *                      stub in tests)
     * @return the existing {@code correlationId} parameter when present
     *         and non-blank; otherwise a freshly-generated
     *         {@link UUID#randomUUID()} as a string
     */
    private static String extractCorrelationId(JobParameters jobParameters) {
        if (jobParameters == null) {
            return UUID.randomUUID().toString();
        }
        String correlationId = jobParameters.getString(PARAM_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    /**
     * Computes the elapsed runtime in milliseconds for a Spring Batch
     * {@link JobExecution} using its {@code startTime} and
     * {@code endTime}. Returns {@code 0L} if either timestamp is
     * absent &mdash; the Spring Batch 5.x API uses {@link LocalDateTime}
     * for both fields and either may be {@code null} for an in-flight
     * job (before {@code afterJob}) or an abended job whose end-time
     * was never populated.
     *
     * @param startTime the {@code JobExecution.startTime}; may be
     *                  {@code null}
     * @param endTime   the {@code JobExecution.endTime}; may be
     *                  {@code null}
     * @return the elapsed runtime in milliseconds, never negative; {@code 0L}
     *         when either timestamp is {@code null} or when
     *         {@code endTime} is before {@code startTime} (an undefined
     *         state defensive callers should not encounter)
     */
    private static long computeDurationMillis(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return 0L;
        }
        long millis = Duration.between(startTime, endTime).toMillis();
        return Math.max(0L, millis);
    }

    /**
     * Safely extracts the {@code jobName} from a {@link JobExecution},
     * defaulting to {@code "UNKNOWN_JOB"} when either the
     * {@code JobExecution} or its {@code JobInstance} is null. The
     * defensive default prevents the listener from short-circuiting
     * with a {@link NullPointerException} during edge-case test setups
     * that supply a bare {@code JobExecution} stub.
     *
     * @param jobExecution the {@link JobExecution} carrying the
     *                     {@code JobInstance}; may be {@code null}
     * @return the {@code jobName} when available; otherwise the
     *         literal {@code "UNKNOWN_JOB"}
     */
    private static String safeJobName(JobExecution jobExecution) {
        if (jobExecution == null || jobExecution.getJobInstance() == null) {
            return "UNKNOWN_JOB";
        }
        String jobName = jobExecution.getJobInstance().getJobName();
        return (jobName != null && !jobName.isBlank()) ? jobName : "UNKNOWN_JOB";
    }

    /**
     * Safely extracts the {@code executionId} from a
     * {@link JobExecution}, defaulting to {@code "UNKNOWN"} when the
     * {@code JobExecution} or its identifier is null. The default
     * mirrors {@link #safeJobName(JobExecution)} so the audit emission
     * never carries a literal {@code "null"} string.
     *
     * @param jobExecution the {@link JobExecution} carrying the numeric
     *                     execution identifier; may be {@code null}
     * @return the stringified {@code executionId} when available;
     *         otherwise the literal {@code "UNKNOWN"}
     */
    private static String safeExecutionId(JobExecution jobExecution) {
        if (jobExecution == null || jobExecution.getId() == null) {
            return "UNKNOWN";
        }
        return String.valueOf(jobExecution.getId());
    }
}
