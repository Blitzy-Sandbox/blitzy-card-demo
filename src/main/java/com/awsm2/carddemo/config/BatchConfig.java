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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Spring Batch 5 configuration.
 *
 * <p>Per AAP &sect;0.6.3 ("JCL → Step Functions Orchestration") and the
 * CP3 checkpoint contract, this configuration defines the
 * {@link TaskExecutorJobLauncher} required by Spring Batch 5 for
 * asynchronous job launches inside the CardDemo Java target.</p>
 *
 * <h2>Replaces (AAP &sect;0.6.3)</h2>
 * <p>Replaces: JES2 batch initiator (the OS-level process on z/OS that
 * picked up JCL jobs from the input queue and dispatched them to the
 * appropriate initiator class). The end-of-day batch chain documented in
 * {@code README.md} Running-full-batch (POSTTRAN → INTCALC → COMBTRAN →
 * CREASTMT / TRANREPT) is orchestrated by AWS Step Functions
 * (declared in {@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json}),
 * which delegates to AWS Batch job definitions that exec this Spring
 * Boot fat-jar with a job-name argument. Spring Batch then dispatches
 * the named job via this {@link JobLauncher} bean.</p>
 *
 * <h2>Schema discipline (AAP &sect;0.6.2)</h2>
 * <p>Spring Batch 5 maintains its own metadata schema
 * ({@code BATCH_JOB_INSTANCE}, {@code BATCH_JOB_EXECUTION},
 * {@code BATCH_STEP_EXECUTION}, etc.) in the same RDS PostgreSQL database
 * as the application data. Per the AAP "Flyway owns schema" rule:</p>
 * <ul>
 *   <li>Spring Boot's default {@code spring.batch.jdbc.initialize-schema}
 *       value of {@code embedded} works for the {@code local} H2 profile
 *       but MUST be set to {@code never} for {@code dev} / {@code prod}.</li>
 *   <li>Flyway migrations under {@code src/main/resources/db/migration/}
 *       provision the Spring Batch metadata tables alongside the
 *       application tables, giving Flyway sole ownership of schema
 *       evolution.</li>
 *   <li>{@code application.yml} sets
 *       {@code spring.batch.jdbc.initialize-schema: never} explicitly so
 *       no profile silently auto-creates Batch tables.</li>
 * </ul>
 *
 * <h2>Async launcher</h2>
 * <p>{@link TaskExecutorJobLauncher} (replacing the deprecated
 * {@code SimpleJobLauncher} in Spring Batch 4) dispatches the job on a
 * {@link TaskExecutor}-supplied thread, returning immediately to the caller
 * with a {@code JobExecution} reference. For AWS Batch invocation (where
 * each ECS task is a single-shot process) the synchronous behavior of
 * waiting for the job to complete is desired; for Step Functions Lambda
 * triggers the async behavior is desired. Both modes work with the same
 * launcher; the calling code's choice of
 * {@code Future.get()} vs. fire-and-forget controls the semantics.</p>
 *
 * <h2>What this class deliberately does NOT do</h2>
 * <ul>
 *   <li>Does NOT define the {@code JobRepository}, {@code PlatformTransactionManager},
 *       or {@code @EnableBatchProcessing} &mdash; Spring Boot 3.x +
 *       Spring Batch 5 auto-configure these from the application
 *       {@code DataSource} (provided by {@link JpaConfig}) and the default
 *       JPA transaction manager. The auto-configuration is correct and
 *       aligned with the AAP; redefining the beans here would risk
 *       drift.</li>
 *   <li>Does NOT declare any {@code Job} or {@code Step} beans &mdash;
 *       those live in {@code com.awsm2.carddemo.batch.*} and are
 *       discovered by component scanning when the batch jobs are
 *       implemented in subsequent checkpoints (CP4+).</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-batch/reference/index.html">Spring Batch 5 Reference</a>
 * @see JpaConfig
 */
@Configuration
public class BatchConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BatchConfig.class);

    /**
     * Bean name for the async TaskExecutor that Spring Batch dispatches
     * jobs onto. Kept as a constant so other configurations can reference
     * the same name without typo risk.
     */
    static final String BATCH_TASK_EXECUTOR_BEAN = "batchTaskExecutor";

    /**
     * Configures the {@link TaskExecutor} used by the
     * {@link TaskExecutorJobLauncher}. {@link SimpleAsyncTaskExecutor} is
     * appropriate for short-running batch jobs where thread pooling is not
     * required (each job runs in its own ECS task); for long-running
     * multi-step pipelines the user may swap in a {@code ThreadPoolTaskExecutor}.
     *
     * <p>The {@code SimpleAsyncTaskExecutor} creates a fresh thread per
     * {@code execute()} call; the thread is named {@code carddemo-batch-}
     * for diagnostic visibility in CloudWatch / thread-dump capture.</p>
     *
     * @return the async TaskExecutor bean
     */
    @Bean(name = BATCH_TASK_EXECUTOR_BEAN)
    public TaskExecutor batchTaskExecutor() {
        // Replaces: JES2 initiator class — a per-class queue + worker pool on
        // z/OS that picked up JCL jobs and ran them. In the AWS target each
        // batch job runs in its own ECS task, so a per-call thread is fine.
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("carddemo-batch-");
        // Virtual threads are not used here because Spring Batch 5
        // (5.1.x) does not yet officially support virtual-thread job
        // execution; ECS tasks each handle one job, so platform threads
        // are sufficient.
        return executor;
    }

    /**
     * Configures the Spring Batch 5 {@link TaskExecutorJobLauncher}.
     *
     * <p>Replaces the deprecated {@code SimpleJobLauncher} from Spring
     * Batch 4. The TaskExecutorJobLauncher delegates job execution to the
     * configured {@link TaskExecutor}, allowing fire-and-forget launches
     * for Step Functions / Lambda triggers and blocking launches when the
     * caller chooses to {@code Future.get()} on the returned execution.</p>
     *
     * @param jobRepository the Spring Batch metadata repository (auto-
     *                      configured by Spring Boot from the JPA
     *                      DataSource — see {@link JpaConfig})
     * @return the configured JobLauncher
     * @throws Exception if the launcher's afterPropertiesSet validation fails
     *                   (e.g., the JobRepository is null)
     */
    @Bean
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        // Replaces: $S JOBNAME (JES job-submission command). The Java target
        // launches a Job via this bean's run() method; Step Functions / AWS
        // Batch are the upstream callers in production, but this bean also
        // serves operational tooling and integration tests.
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(batchTaskExecutor());
        launcher.afterPropertiesSet();
        LOG.info("Spring Batch JobLauncher configured (TaskExecutorJobLauncher) — "
                + "Flyway owns batch schema (spring.batch.jdbc.initialize-schema=never)");
        return launcher;
    }
}
