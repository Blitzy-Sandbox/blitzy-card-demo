package com.carddemo.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link BatchConfig} &mdash; verifies the master {@code cardDemoBatchPipelineJob}
 * flow graph: bean identity, JCL pipeline ordering, {@code COND=(0,NE)} stop-on-failure parity, and the
 * reject-count continue-forward branch.
 *
 * <h2>What this test owns</h2>
 * <p>{@link BatchConfig} is a Spring Batch {@code @Configuration} (no {@code @EnableBatchProcessing})
 * whose sole responsibility is assembling five migrated stage steps into one {@link Job} in the exact
 * order of the legacy JCL job stream, plus the {@code postingRejectDecider} routing. The individual
 * step beans (with their readers/writers/S3/SQS wiring) live under {@code com.carddemo.batch} and are
 * out of scope here. To exercise the <em>flow/order/COND</em> logic that {@code BatchConfig} owns
 * without provisioning that heavy I/O machinery, this test boots the real application context (so the
 * real {@code Job} graph is assembled) but <strong>overrides the five named {@code Step} beans</strong>
 * with lightweight, instrumented no-op tasklet steps (see {@link StepOverrides}) that record their
 * execution order and can be forced to {@code FAILED}. Because the override bean names match the
 * production step-bean names and {@code spring.main.allow-bean-definition-overriding=true}, the
 * instrumented steps replace the real ones while {@code BatchConfig}'s real flow (ordering + decider +
 * transitions) is executed verbatim.</p>
 *
 * <h2>JCL lineage (read-only reference, source commit SHA {@code 27d6c6f}; NOT copied)</h2>
 * <pre>
 *   POSTTRAN (app/jcl/POSTTRAN.jcl, CBTRN02C)     -&gt; postTransactionStep
 *   INTCALC  (app/jcl/INTCALC.jcl,  CBACT04C)     -&gt; interestCalculationStep
 *   COMBTRAN (app/jcl/COMBTRAN.jcl, SORT + REPRO) -&gt; combineTransactionStep
 *   CREASTMT (app/jcl/CREASTMT.JCL, CBSTM03A)     -&gt; statementStep
 *   TRANREPT (app/jcl/TRANREPT.jcl, CBTRN03C)     -&gt; transactionReportStep
 * </pre>
 * <p>{@code CREASTMT.JCL} (note the UPPERCASE {@code .JCL} extension) chains its steps with
 * {@code COND=(0,NE)} ("run this step only if every prior step returned RC&nbsp;0"); a non-zero prior
 * return code bypasses all subsequent steps. In Spring Batch this is a {@code FlowBuilder} transition
 * that advances only {@code .on("COMPLETED")} (and {@code "COMPLETED_WITH_REJECTS"}); a {@code FAILED}
 * step has no forward edge, so the pipeline halts and the job ends {@code FAILED}.</p>
 *
 * <h2>Environment</h2>
 * <p>A Testcontainers PostgreSQL&nbsp;16 database backs the Spring Batch metadata tables and the Flyway
 * migrations ({@code V1}/{@code V2}/{@code V3}) run by the {@code test} profile. The test requires
 * <strong>zero live AWS</strong> (AAP&nbsp;&sect;0.7.7): the overridden steps are no-op tasklets that
 * perform no S3/SQS I/O, and the S3/SQS/SNS auto-configurations are excluded so no AWS endpoint is
 * ever contacted; the one eager AWS collaborator ({@code ReportService}'s {@link SqsTemplate}) is
 * satisfied by a Mockito bean.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(BatchConfigIT.StepOverrides.class)
@TestPropertySource(properties = {
        // Instrumented StepOverrides beans reuse the production step-bean names, so overriding must be allowed.
        "spring.main.allow-bean-definition-overriding=true",
        // Never auto-run any job on startup; each test launches cardDemoBatchPipelineJob explicitly.
        "spring.batch.job.enabled=false",
        // Zero live AWS: drop the S3/SQS/SNS auto-configuration entirely so the context contacts no AWS
        // endpoint and registers no startup SQS listener. HealthIndicators consumes the AWS clients via
        // ObjectProvider (tolerates their absence), the S3-backed writers are @StepScope (never created by
        // the no-op tasklets), and ReportService's SqsTemplate is supplied by @MockitoBean below.
        "spring.autoconfigure.exclude="
                + "io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration,"
                + "io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration,"
                + "io.awspring.cloud.autoconfigure.sns.SnsAutoConfiguration"
})
@DisplayName("BatchConfig integration — cardDemoBatchPipelineJob flow, JCL ordering, and COND=(0,NE) parity")
class BatchConfigIT {

    /**
     * Ordered logical names of the five pipeline steps, in the exact legacy JCL sequence
     * POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT &rarr; TRANREPT.
     */
    private static final List<String> EXPECTED_STEPS = List.of(
            "postTransactionStep",
            "interestCalculationStep",
            "combineTransactionStep",
            "statementStep",
            "transactionReportStep");

    /**
     * Records the execution order of the instrumented steps for a single launch. Thread-safe because a
     * job may execute steps on a task executor; reset before every test by {@link #resetInstrumentation()}.
     */
    static final CopyOnWriteArrayList<String> EXECUTION_ORDER = new CopyOnWriteArrayList<>();

    /**
     * When set to a step's logical name, that instrumented step throws to force a {@code FAILED} exit,
     * simulating a non-zero JCL return code. {@code null} means every step succeeds.
     */
    static volatile String STEP_TO_FAIL;

    /**
     * When {@code true}, the instrumented {@code postTransactionStep} seeds a positive reject count into
     * its step execution context (mirroring {@code CBTRN02C RETURN-CODE 4}), driving the
     * {@code postingRejectDecider} down its {@code COMPLETED_WITH_REJECTS} branch.
     */
    static volatile boolean SEED_REJECTS;

    /**
     * Single-node Testcontainers PostgreSQL&nbsp;16 (community image; never {@code -pro}, never
     * {@code latest}).
     *
     * <p>Uses the Testcontainers&nbsp;2.x class {@link org.testcontainers.postgresql.PostgreSQLContainer},
     * which is a <em>concrete, non-generic</em> self-bound subclass of {@code JdbcDatabaseContainer}.
     * It therefore takes no type argument &mdash; unlike the legacy
     * {@code org.testcontainers.containers.PostgreSQLContainer} (deprecated in 2.x), which was generic
     * and required a {@code <?>} wildcard to dodge a {@code rawtypes} warning. Both the deprecation of
     * the legacy class and a {@code rawtypes} warning are avoided here, keeping the build warning-free
     * under {@code -Xlint:all} (Gate&nbsp;2).</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    /**
     * Wires the container's JDBC coordinates into the Spring {@code Environment} and forces Spring Batch
     * to create its metadata schema against the container (the {@code test} profile also runs Flyway).
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void datasourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Initialize the Spring Batch metadata tables (BATCH_JOB_*, BATCH_STEP_*) in the container.
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");
    }

    /** The master pipeline job assembled by {@link BatchConfig} (the bean under test). */
    @Autowired
    @Qualifier("cardDemoBatchPipelineJob")
    private Job cardDemoBatchPipelineJob;

    /** Spring Boot's auto-configured synchronous launcher; makes {@code run(...)} block until completion. */
    @Autowired
    private JobLauncher jobLauncher;

    /**
     * Satisfies the single eager AWS client dependency in the context. With
     * {@code SqsAutoConfiguration} excluded, no {@link SqsAsyncClient} is auto-configured, yet
     * {@code AwsConfig}'s {@code sqsTemplate} bean (injected into {@code ReportService})
     * is built over one. A Mockito {@link SqsAsyncClient} lets that send-only template be assembled
     * (construction performs no network I/O); it is never exercised here since no report is enqueued.
     */
    @MockitoBean
    private SqsAsyncClient sqsAsyncClient;

    /** Clears instrumentation between tests so ordering/failure assertions are independent. */
    @BeforeEach
    void resetInstrumentation() {
        EXECUTION_ORDER.clear();
        STEP_TO_FAIL = null;
        SEED_REJECTS = false;
    }

    /**
     * Builds a unique {@link JobParameters} for each launch so every run is a fresh, restartable
     * {@code JobInstance} and never collides with {@code JobInstanceAlreadyCompleteException}.
     *
     * @return job parameters carrying a nanosecond-resolution {@code run.id}
     */
    private static JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 2 — the Job bean is present and correctly named (+ resilient static-structure assertion)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("cardDemoBatchPipelineJob bean exists, is named 'cardDemoBatchPipelineJob', and contains all five steps")
    void jobBeanIsPresentAndNamed() {
        assertThat(cardDemoBatchPipelineJob).isNotNull();
        assertThat(cardDemoBatchPipelineJob.getName()).isEqualTo("cardDemoBatchPipelineJob");

        // Resilient minimum: inspect the assembled flow graph directly (independent of launching). A
        // FlowJob does not guarantee iteration order for getStepNames(), so assert membership only here;
        // strict JCL ordering is verified by the launch-based test below via EXECUTION_ORDER.
        assertThat(cardDemoBatchPipelineJob).isInstanceOf(AbstractJob.class);
        final AbstractJob abstractJob = (AbstractJob) cardDemoBatchPipelineJob;
        assertThat(abstractJob.getStepNames())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_STEPS);
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 3 — an all-success run completes in the exact JCL pipeline order
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("All steps succeed → job COMPLETED and steps execute in JCL order "
            + "POSTTRAN→INTCALC→COMBTRAN→CREASTMT→TRANREPT")
    void allStepsSucceed_completesInJclOrder() throws Exception {
        STEP_TO_FAIL = null;

        final JobExecution execution = jobLauncher.run(cardDemoBatchPipelineJob, uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // JCL pipeline @ SHA 27d6c6f: POSTTRAN(POSTTRAN.jcl) → INTCALC(INTCALC.jcl) →
        // COMBTRAN(COMBTRAN.jcl) → CREASTMT(CREASTMT.JCL) → TRANREPT(TRANREPT.jcl).
        assertThat(EXECUTION_ORDER).containsExactlyElementsOf(EXPECTED_STEPS);
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 4 — a forced early-step failure stops the pipeline (JCL COND=(0,NE) parity)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Early step FAILED → job FAILED and downstream steps never run (COND=(0,NE) parity)")
    void earlyStepFailure_stopsPipeline() throws Exception {
        STEP_TO_FAIL = "interestCalculationStep";

        final JobExecution execution = jobLauncher.run(cardDemoBatchPipelineJob, uniqueParameters());

        // CREASTMT.JCL COND=(0,NE) parity: a non-zero prior RC bypasses subsequent steps. A FAILED step
        // has no .on("COMPLETED") transition, so BatchConfig's flow halts and the job ends FAILED.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(EXECUTION_ORDER)
                .contains("postTransactionStep", "interestCalculationStep")
                .doesNotContain("combineTransactionStep", "statementStep", "transactionReportStep");
    }

    // ------------------------------------------------------------------------------------------------
    // Phase 5 — a positive reject count continues the pipeline (COMPLETED_WITH_REJECTS branch)
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Posting rejects (>0) → decider routes COMPLETED_WITH_REJECTS forward; pipeline still "
            + "reaches TRANREPT and completes")
    void rejectsDoNotStopPipeline() throws Exception {
        // The instrumented postTransactionStep seeds rejectCount=1 into its step execution context,
        // exercising BatchConfig.postingRejectDecider's COMPLETED_WITH_REJECTS branch. This mirrors
        // CBTRN02C RETURN-CODE 4 (rejects logged, pipeline continues). Pure-branch coverage of the
        // decider's 0/>0 outcomes also lives in the unit test BatchConfigTest.
        SEED_REJECTS = true;

        final JobExecution execution = jobLauncher.run(cardDemoBatchPipelineJob, uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(EXECUTION_ORDER).containsExactlyElementsOf(EXPECTED_STEPS);
    }

    /**
     * Test-only configuration that overrides the five production {@code Step} beans with instrumented,
     * no-op tasklet steps. Each bean method name equals the corresponding production step-bean name, so
     * (with bean-definition overriding enabled) these replace the real steps while {@code BatchConfig}'s
     * real {@code Job} flow is exercised unchanged.
     */
    @TestConfiguration
    static class StepOverrides {

        /**
         * Builds a singleton tasklet {@link Step} that records its name into {@link #EXECUTION_ORDER}, may
         * seed a reject count (posting step only), and may throw to force {@code FAILED}.
         *
         * @param name               the logical step name (also the bean name)
         * @param jobRepository      the auto-configured Spring Batch job repository
         * @param transactionManager the platform transaction manager backing the tasklet
         * @return an instrumented tasklet step
         */
        private static Step recordingStep(final String name,
                                          final JobRepository jobRepository,
                                          final PlatformTransactionManager transactionManager) {
            return new StepBuilder(name, jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        EXECUTION_ORDER.add(name);
                        if (SEED_REJECTS && "postTransactionStep".equals(name)) {
                            // Mirrors com.carddemo.batch.PostTransactionItemWriter.REJECT_COUNT_KEY
                            // ("rejectCount"); referenced as a literal because that writer is not a
                            // declared dependency of this test. CBTRN02C sets RETURN-CODE 4 when
                            // WS-REJECT-COUNT > 0 — a warning that must NOT stop the pipeline.
                            chunkContext.getStepContext().getStepExecution()
                                    .getExecutionContext().putLong("rejectCount", 1L);
                        }
                        if (name.equals(STEP_TO_FAIL)) {
                            throw new IllegalStateException("forced failure for COND parity: " + name);
                        }
                        return RepeatStatus.FINISHED;
                    }, transactionManager)
                    .build();
        }

        @Bean
        Step postTransactionStep(final JobRepository jobRepository,
                                 final PlatformTransactionManager transactionManager) {
            return recordingStep("postTransactionStep", jobRepository, transactionManager);
        }

        @Bean
        Step interestCalculationStep(final JobRepository jobRepository,
                                     final PlatformTransactionManager transactionManager) {
            return recordingStep("interestCalculationStep", jobRepository, transactionManager);
        }

        @Bean
        Step combineTransactionStep(final JobRepository jobRepository,
                                    final PlatformTransactionManager transactionManager) {
            return recordingStep("combineTransactionStep", jobRepository, transactionManager);
        }

        @Bean
        Step statementStep(final JobRepository jobRepository,
                           final PlatformTransactionManager transactionManager) {
            return recordingStep("statementStep", jobRepository, transactionManager);
        }

        @Bean
        Step transactionReportStep(final JobRepository jobRepository,
                                   final PlatformTransactionManager transactionManager) {
            return recordingStep("transactionReportStep", jobRepository, transactionManager);
        }
    }
}
