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
package com.awsm2.carddemo.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring Batch 5 configuration for the CardDemo end-of-day batch pipeline
 * per AAP &sect;0.4.1.
 *
 * <h2>Purpose</h2>
 * <p>Stateless infrastructure {@code @Configuration} class that wires the
 * Spring Batch 5 runtime for the CardDemo batch pipeline. The pipeline
 * comprises the following Spring Batch jobs (each implemented under
 * {@code com.awsm2.carddemo.batch}), all of which trace back to a JCL
 * member retained as REFERENCE under {@code app/jcl/} per AAP
 * &sect;0.2.1:</p>
 * <ul>
 *   <li>{@code DailyTransactionPostingJob} &larr; {@code POSTTRAN.jcl}
 *       ({@code CBTRN02C})</li>
 *   <li>{@code InterestCalculationJob} &larr; {@code INTCALC.jcl}
 *       ({@code CBACT04C})</li>
 *   <li>{@code CombineTransactionsJob} &larr; {@code COMBTRAN.jcl}
 *       (pure utility, DFSORT/REPRO)</li>
 *   <li>{@code StatementGenerationJob} &larr; {@code CREASTMT.JCL}
 *       ({@code CBSTM03A} / {@code CBSTM03B})</li>
 *   <li>{@code TransactionReportJob} &larr; {@code TRANREPT.jcl}
 *       ({@code CBTRN03C})</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.4.1 &amp; &sect;0.6.3)</h2>
 * <ul>
 *   <li>JCL job-step orchestration (e.g., {@code POSTTRAN.jcl},
 *       {@code INTCALC.jcl}, {@code COMBTRAN.jcl}, {@code CREASTMT.JCL},
 *       {@code TRANREPT.jcl}) &mdash; now Spring Batch jobs invoked by
 *       AWS Batch from Step Functions
 *       ({@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json}).</li>
 *   <li>JES batch initiator + JCL {@code EXEC PGM=} step dispatch
 *       &mdash; now {@code JobLauncher.run(Job, JobParameters)} executed
 *       inside the AWS Batch container by the {@code CommandLineRunner}
 *       declared on each {@code com.awsm2.carddemo.batch} job-launcher
 *       class.</li>
 *   <li>JCL {@code COND=} condition codes governing step skip /
 *       continuation &mdash; now Spring Batch step {@code ExitStatus}
 *       mapped to the AWS Batch container exit code, which Step
 *       Functions reads as the {@code Task} state's success / failure
 *       outcome.</li>
 *   <li>JES2 initiator thread pool + JOBQ allocation governing parallel
 *       job execution under z/OS &mdash; now the bounded
 *       {@link ThreadPoolTaskExecutor} wired below, sized for the AWS
 *       Batch container model where each container typically runs ONE
 *       job to completion.</li>
 *   <li>TSO {@code SUBMIT} + JES2 input queue used by operators to
 *       schedule on-demand batch runs &mdash; now the
 *       {@code asyncJobLauncher} bean exposed below, callable from REST
 *       controllers (e.g., {@code ReportController.submit()} per AAP
 *       &sect;0.3.4) without blocking the request thread.</li>
 * </ul>
 *
 * <h2>Spring Batch 5 / Spring Boot 3.x Runtime Model</h2>
 * <p>Spring Boot 3.x with Spring Batch 5 auto-configures the following
 * beans from the {@link Primary @Primary} {@code DataSource} declared in
 * {@link JpaConfig} (the only listed dependency of this file per the
 * agent file schema):</p>
 * <ul>
 *   <li>{@link JobRepository} &mdash; backed by the Spring Batch
 *       metadata tables ({@code BATCH_JOB_INSTANCE},
 *       {@code BATCH_JOB_EXECUTION}, {@code BATCH_STEP_EXECUTION},
 *       {@code BATCH_JOB_EXECUTION_PARAMS},
 *       {@code BATCH_JOB_EXECUTION_CONTEXT},
 *       {@code BATCH_STEP_EXECUTION_CONTEXT}) living in the same RDS
 *       PostgreSQL database as the application data per AAP
 *       &sect;0.4.1.</li>
 *   <li>{@code JobExplorer} &mdash; read-only view of the metadata
 *       schema, used by operational tooling and the Spring Boot
 *       Actuator {@code /actuator/batchstats} endpoint.</li>
 *   <li>{@code PlatformTransactionManager} &mdash; the
 *       {@code JpaTransactionManager} auto-configured by Spring Boot
 *       {@code HibernateJpaAutoConfiguration} on top of the
 *       {@link JpaConfig} {@code DataSource}; consumed by Spring Batch
 *       step-scoped transactions per AAP &sect;0.4.1 transactional
 *       safety requirements.</li>
 *   <li>{@code JobLauncher} (default, named {@code jobLauncher})
 *       &mdash; uses a {@code SyncTaskExecutor} that blocks the calling
 *       thread until the job completes. This is the launcher used by
 *       AWS Batch container invocations so the container exits with
 *       the job's exit code (Step Functions reads the container exit
 *       code as the Task outcome).</li>
 * </ul>
 *
 * <h2>Why {@code @EnableBatchProcessing} Is Declared Here</h2>
 * <p>In Spring Batch 5 / Spring Boot 3.x, {@code @EnableBatchProcessing}
 * is OPTIONAL &mdash; Spring Boot auto-configures the Batch infrastructure
 * by default. It is declared here EXPLICITLY per AAP &sect;0.4.1
 * requirement ("{@code @EnableBatchProcessing}, {@code JobLauncher},
 * {@code JobRepository} configuration") to make the activation of the
 * Batch infrastructure intentional, auditable, and discoverable by
 * static analysis tools that scan for security-sensitive infrastructure
 * annotations. The presence of the annotation does NOT alter behaviour
 * in this configuration (auto-config and the explicit annotation
 * converge on the same bean set) but does signal to future maintainers
 * that the Batch capability is owned by this file.</p>
 *
 * <h2>Schema Discipline (AAP &sect;0.4.1)</h2>
 * <p>Spring Batch's metadata schema is owned by Flyway, NOT by Spring
 * Batch's built-in {@code initialize-schema} DDL pump:</p>
 * <ul>
 *   <li>{@code spring.batch.jdbc.initialize-schema=never} is set in
 *       {@code application.yml} so Spring Batch never auto-creates the
 *       metadata tables at startup &mdash; doing so would conflict with
 *       Flyway and risk schema drift between environments.</li>
 *   <li>Flyway migrations under {@code src/main/resources/db/migration/}
 *       are the single source of truth for both application tables AND
 *       Spring Batch metadata tables, giving Flyway sole ownership of
 *       schema evolution (AAP &sect;0.4.1 / &sect;0.6.2).</li>
 * </ul>
 *
 * <h2>Job Auto-Run Discipline</h2>
 * <p>{@code spring.batch.job.enabled=false} is set in
 * {@code application.yml} so the
 * {@code JobLauncherApplicationRunner} does NOT auto-run ALL discovered
 * {@code Job} beans on application startup. Individual jobs are
 * launched explicitly by:</p>
 * <ul>
 *   <li>The {@code CommandLineRunner} declared in each batch job
 *       launcher class under {@code com.awsm2.carddemo.batch}
 *       &mdash; this is the AWS Batch invocation path.</li>
 *   <li>The {@code ReportController.submit()} REST endpoint &mdash;
 *       this is the REST-triggered async path that uses the
 *       {@code asyncJobLauncher} bean defined here.</li>
 *   <li>Step Functions Lambda triggers consuming the
 *       {@code report.requested} MSK topic per AAP &sect;0.1.1
 *       (online-to-batch bridge).</li>
 * </ul>
 *
 * <p>This discipline prevents the catastrophic double-posting scenario
 * where an end-of-day batch job auto-runs on every ECS task restart
 * (e.g., during a rolling deployment).</p>
 *
 * <h2>Externalised Configuration (AAP &sect;0.7.1)</h2>
 * <p>All thread-pool sizing is externalised via the
 * {@code carddemo.batch.executor.*} property tree, bound onto the
 * {@code @Value}-annotated fields below. Profile overlays in
 * {@code application-local.yml}, {@code application-dev.yml}, and
 * {@code application-prod.yml} tune the executor per environment without
 * code changes. Defaults are conservative (small thread pool, no
 * queueing, graceful shutdown) and are appropriate for the AWS Batch
 * container model where each container runs ONE job.</p>
 *
 * <h2>What This Class Deliberately Does NOT Do</h2>
 * <ul>
 *   <li>Does NOT declare any {@code Job} or {@code Step} beans &mdash;
 *       those live in {@code com.awsm2.carddemo.batch.*} per the AAP
 *       structural breakdown.</li>
 *   <li>Does NOT declare any {@code ItemReader}, {@code ItemWriter}, or
 *       {@code ItemProcessor} &mdash; those live in
 *       {@code com.awsm2.carddemo.batch.reader},
 *       {@code com.awsm2.carddemo.batch.writer}, and
 *       {@code com.awsm2.carddemo.batch.processor} respectively.</li>
 *   <li>Does NOT override the {@code JobRepository},
 *       {@code JobExplorer}, or {@code PlatformTransactionManager}
 *       auto-configured by Spring Boot from the {@link JpaConfig}
 *       {@code DataSource} &mdash; the auto-configured beans are
 *       correct and aligned with the AAP; redefining them would risk
 *       drift (AAP &sect;0.7.1 Minimal Change Clause).</li>
 *   <li>Does NOT contain any business logic, financial calculation,
 *       transactional code, or domain knowledge &mdash; this file is
 *       pure infrastructure wiring per AAP &sect;0.7.1.</li>
 *   <li>Does NOT import any {@code javax.*} package &mdash; Spring Boot
 *       3.x is built on Jakarta EE 10 ({@code jakarta.*}). No
 *       persistence, servlet, or validation API surfaces in this file
 *       so the constraint is trivially satisfied.</li>
 *   <li>Does NOT import any AWS SDK class &mdash; Spring Batch
 *       metadata is stored in RDS PostgreSQL (Spring's
 *       {@code DataSource} abstraction), and job orchestration is owned
 *       by Step Functions / AWS Batch defined under
 *       {@code infrastructure/terraform/}. The AWS SDK isolation rule
 *       (AAP &sect;0.7.1) is honoured because this class never touches
 *       AWS service APIs directly.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.config.JpaConfig
 * @see <a href="https://docs.spring.io/spring-batch/reference/index.html">
 *      Spring Batch 5 Reference</a>
 * @see <a href="https://docs.spring.io/spring-boot/docs/3.3.x/reference/htmlsingle/#howto.batch">
 *      Spring Boot 3.x Reference &mdash; Spring Batch</a>
 */
@Configuration
@EnableBatchProcessing
public class BatchConfig {

    // -------------------------------------------------------------------
    // Phase 3 — Externalised configuration fields (AAP §0.7.1)
    //
    // All thread-pool sizing is bound from the `carddemo.batch.executor.*`
    // property tree in `application.yml`. The default values supplied here
    // via the `${...:default}` syntax keep the executor functional even if
    // a profile overlay omits the property, satisfying the
    // fail-safe-defaults principle without compromising the externalised-
    // configuration discipline.
    // -------------------------------------------------------------------

    /**
     * Async task-executor core pool size &mdash; the number of threads
     * pre-allocated by {@link ThreadPoolTaskExecutor} at startup and
     * retained even when idle.
     *
     * <p>Bound from {@code carddemo.batch.executor.core-pool-size};
     * default {@code 2}. Conservative because each AWS Batch container
     * typically runs ONE job to completion before exiting; the executor
     * exists primarily for the rare case where a Step Functions
     * {@code Parallel} state schedules two sub-jobs concurrently within
     * a single container.</p>
     */
    @Value("${carddemo.batch.executor.core-pool-size:2}")
    private int corePoolSize;

    /**
     * Async task-executor maximum pool size &mdash; the cap beyond which
     * {@link ThreadPoolTaskExecutor} will not create new threads.
     *
     * <p>Bound from {@code carddemo.batch.executor.max-pool-size};
     * default {@code 4}. With {@link #queueCapacity} = {@code 0}
     * (synchronous handoff), submissions beyond {@code maxPoolSize}
     * are rejected with {@code RejectedExecutionException}; this is
     * intentional &mdash; an AWS Batch container that's about to exit
     * should NOT silently queue work.</p>
     */
    @Value("${carddemo.batch.executor.max-pool-size:4}")
    private int maxPoolSize;

    /**
     * Async task-executor queue capacity &mdash; how many submissions
     * the executor will buffer when all threads are busy before either
     * spawning a new thread (up to {@link #maxPoolSize}) or rejecting.
     *
     * <p>Bound from {@code carddemo.batch.executor.queue-capacity};
     * default {@code 0} (synchronous handoff &mdash; the
     * {@link java.util.concurrent.SynchronousQueue} used internally
     * has zero capacity). Unbounded queueing inside a batch container
     * is an anti-pattern; setting this to a non-zero value should be
     * accompanied by careful analysis of container lifecycle and
     * shutdown semantics.</p>
     */
    @Value("${carddemo.batch.executor.queue-capacity:0}")
    private int queueCapacity;

    /**
     * Async task-executor thread-name prefix used to identify threads
     * in stack traces, CloudWatch Logs, and thread-dump captures.
     *
     * <p>Bound from {@code carddemo.batch.executor.thread-name-prefix};
     * default {@code carddemo-batch-}. The trailing hyphen is required
     * because {@link ThreadPoolTaskExecutor} appends a numeric thread
     * ID directly after the prefix (e.g., {@code carddemo-batch-1}).</p>
     */
    @Value("${carddemo.batch.executor.thread-name-prefix:carddemo-batch-}")
    private String threadNamePrefix;

    /**
     * Whether to wait for in-flight tasks to complete on JVM shutdown
     * before forcibly terminating the executor.
     *
     * <p>Bound from
     * {@code carddemo.batch.executor.wait-for-tasks-on-shutdown};
     * default {@code true}. Combined with
     * {@link #awaitTerminationSeconds}, this gives in-flight Spring
     * Batch step executions a bounded window to commit their JPA
     * transactions and update the metadata tables before the container
     * terminates &mdash; protecting the
     * {@code BATCH_STEP_EXECUTION.STATUS} column from being left in
     * the inconsistent {@code STARTED} state after a hard kill.</p>
     */
    @Value("${carddemo.batch.executor.wait-for-tasks-on-shutdown:true}")
    private boolean waitForTasksOnShutdown;

    /**
     * Maximum number of seconds to wait for the executor to terminate
     * gracefully after shutdown is initiated.
     *
     * <p>Bound from
     * {@code carddemo.batch.executor.await-termination-seconds};
     * default {@code 60}. Aligns with the typical AWS ECS task
     * {@code stopTimeout} (default {@code 30 s}, raised to {@code 120 s}
     * in production task definitions) so the executor's graceful
     * shutdown completes within the ECS-supplied shutdown window before
     * Docker sends {@code SIGKILL}.</p>
     */
    @Value("${carddemo.batch.executor.await-termination-seconds:60}")
    private int awaitTerminationSeconds;

    // -------------------------------------------------------------------
    // Phase 4 — TaskExecutor bean (async job launching)
    // -------------------------------------------------------------------

    /**
     * Builds the bounded {@link ThreadPoolTaskExecutor} used by the
     * {@link #asyncJobLauncher(JobRepository)} {@link JobLauncher} for
     * asynchronous Spring Batch job dispatch.
     *
     * <p>For CardDemo's batch model &mdash; where each AWS Batch
     * container runs ONE job and exits with the job's exit code &mdash;
     * synchronous execution (via the Spring Boot auto-configured
     * default {@code jobLauncher} that uses a
     * {@link org.springframework.core.task.SyncTaskExecutor SyncTaskExecutor})
     * is the dominant pattern. This async executor is provided for the
     * narrower set of use cases:</p>
     * <ul>
     *   <li>A Step Functions {@code Parallel} state that schedules two
     *       or more sub-jobs concurrently within a single container
     *       (e.g., the CREASTMT + TRANREPT fan-out documented in AAP
     *       &sect;0.6.3).</li>
     *   <li>A REST endpoint (e.g.,
     *       {@code ReportController.submit()} per AAP &sect;0.3.4) that
     *       launches a batch job and returns immediately to the client
     *       without blocking the request thread.</li>
     * </ul>
     *
     * <p><b>Configuration (defaults):</b></p>
     * <ul>
     *   <li>Core pool: {@code 2} threads</li>
     *   <li>Max pool: {@code 4} threads</li>
     *   <li>Queue capacity: {@code 0} (synchronous handoff; reject when
     *       no thread is available)</li>
     *   <li>Thread name prefix: {@code carddemo-batch-}</li>
     *   <li>Graceful shutdown: waits up to {@code 60} seconds for
     *       in-flight tasks to complete before forcing termination</li>
     * </ul>
     *
     * <p><b>Per AAP &sect;0.7.1</b> the return type is the
     * {@link TaskExecutor} interface rather than the concrete
     * {@link ThreadPoolTaskExecutor} class so that downstream consumers
     * depend on the abstract API. This is the dependency-injection
     * loose-coupling discipline required by the AAP and enables a test
     * configuration to substitute a
     * {@link org.springframework.core.task.SyncTaskExecutor SyncTaskExecutor}
     * for deterministic unit testing of async-launched jobs.</p>
     *
     * <p><b>Replaces:</b> the z/OS JES2 initiator thread pool plus the
     * job-class JOBQ allocation that governed how many JCL jobs of a
     * given class could run in parallel on the mainframe.</p>
     *
     * @return the configured {@link TaskExecutor} (a fully-initialised
     *         {@link ThreadPoolTaskExecutor} returned as the abstract
     *         interface)
     */
    @Bean(name = "batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        // Replaces: JES2 initiator thread pool + JOBQ allocation that
        // governed parallel JCL job execution on z/OS. The Java target
        // uses a bounded ThreadPoolTaskExecutor sized for the AWS Batch
        // container model (one container = one primary job, with the
        // option to fan out into a small number of sub-jobs inside a
        // Step Functions Parallel state).
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(waitForTasksOnShutdown);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        // initialize() is REQUIRED — ThreadPoolTaskExecutor does not
        // pre-initialize its delegate java.util.concurrent.ThreadPoolExecutor
        // until either initialize() or afterPropertiesSet() is called.
        // Without this the executor is unusable and execute() throws
        // IllegalStateException("ThreadPoolTaskExecutor not initialized").
        executor.initialize();
        return executor;
    }

    // -------------------------------------------------------------------
    // Phase 5 — JobLauncher bean (async override of the auto-config default)
    // -------------------------------------------------------------------

    /**
     * Customises the Spring Batch {@link JobLauncher} so it dispatches
     * jobs on the {@link #batchTaskExecutor() batchTaskExecutor} for
     * asynchronous (non-blocking) launches.
     *
     * <p>By default, Spring Batch's auto-configured {@code jobLauncher}
     * bean uses a
     * {@link org.springframework.core.task.SyncTaskExecutor SyncTaskExecutor}
     * which blocks the calling thread until the job completes. This
     * override exposes an alternative
     * {@link TaskExecutorJobLauncher} as a separately-named bean
     * ({@code asyncJobLauncher}) so REST controllers (e.g.,
     * {@code ReportController.submit()}) can launch long-running batch
     * jobs without blocking the request thread; the actual work
     * executes on a {@code carddemo-batch-} thread from the
     * {@link ThreadPoolTaskExecutor} configured in
     * {@link #batchTaskExecutor()}.</p>
     *
     * <p><b>NOTE:</b> Within AWS Batch containers (Step Functions
     * {@code Task} state), the {@code CommandLineRunner} continues to
     * use the synchronous default {@code jobLauncher.run(job, params)}
     * pattern so the container exits with the job's exit code (Step
     * Functions reads the container exit code as the Task outcome).
     * The async variant defined here is NOT used by the AWS Batch
     * invocation path.</p>
     *
     * <p><b>Bean naming &mdash; coexistence with Spring Boot auto-config:</b>
     * This bean is explicitly named {@code asyncJobLauncher} rather
     * than {@code jobLauncher} so it coexists with the Spring Boot
     * auto-configured {@code jobLauncher} (registered by
     * {@code org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration})
     * which retains the {@code SyncTaskExecutor}-based synchronous
     * launcher. Consumers explicitly choose synchronous vs. asynchronous
     * launching by injecting the appropriate bean name via
     * {@code @Qualifier("asyncJobLauncher")} or by type (which resolves
     * to the auto-configured default {@code jobLauncher}).</p>
     *
     * <p>This naming strategy is required because
     * {@code spring.main.allow-bean-definition-overriding=false} is set
     * in {@code application.yml} (per AAP &sect;0.7.1 strict bean-
     * definition discipline); reusing the {@code jobLauncher} name would
     * cause a {@code BeanDefinitionOverrideException} at context
     * refresh.</p>
     *
     * <p><b>Replaces:</b> the {@code TSO SUBMIT} command + the JES2
     * input queue that operators used on z/OS to schedule on-demand
     * batch jobs from interactive sessions. The async REST-triggered
     * launch path through this bean is the modern equivalent.</p>
     *
     * @param jobRepository the auto-configured {@link JobRepository}
     *                      (backed by the Spring Batch metadata tables
     *                      in the RDS PostgreSQL database supplied by
     *                      {@link JpaConfig}); injected by Spring's
     *                      dependency-injection container
     * @return the configured {@link JobLauncher} (a fully-initialised
     *         {@link TaskExecutorJobLauncher} returned as the abstract
     *         interface)
     * @throws Exception if launcher initialisation fails &mdash;
     *                   {@code TaskExecutorJobLauncher.afterPropertiesSet()}
     *                   throws {@link IllegalStateException} only if
     *                   the {@code JobRepository} is null, which
     *                   cannot occur here because the parameter is
     *                   never null when injected by Spring
     */
    @Bean(name = "asyncJobLauncher")
    public JobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        // Replaces: TSO SUBMIT command + JES2 input queue. The Java
        // target launches a Spring Batch Job via this bean's run()
        // method; REST controllers and Step Functions Lambda triggers
        // are the upstream callers in production, but this bean also
        // serves operational tooling and the Spring Batch test harness.
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(batchTaskExecutor());
        // afterPropertiesSet() validates the launcher configuration
        // (non-null JobRepository, non-null TaskExecutor) and prepares
        // it for use. Throwing here on misconfiguration is preferred
        // over deferring the failure until the first run() invocation,
        // because a startup-time failure is loud and visible while a
        // first-run failure is silent until exercised.
        launcher.afterPropertiesSet();
        return launcher;
    }
}
