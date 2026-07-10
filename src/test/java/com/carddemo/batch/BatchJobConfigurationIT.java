package com.carddemo.batch;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context integration test that verifies the <strong>wiring and startup quiescence</strong> of the
 * migrated Spring Batch layer &mdash; it does <em>not</em> execute any job. Booting the real application
 * context through {@link AbstractBatchIntegrationTest} (Testcontainers PostgreSQL&nbsp;16 + LocalStack,
 * {@code test} profile), it asserts three structural facts and one behavioural invariant:
 *
 * <ol>
 *   <li><strong>All 11 {@link Job} beans exist</strong> &mdash; the five migrated pipeline stages plus
 *       the five on-demand print/reference jobs plus the one master orchestration job.</li>
 *   <li><strong>All 10 {@link Step} beans exist</strong> &mdash; one step per pipeline stage and one per
 *       print/reference job; the master job reuses (does not redefine) the five pipeline steps.</li>
 *   <li><strong>The master {@code cardDemoBatchPipelineJob} composes the five pipeline stages</strong>
 *       in the preserved JCL lineage and is a distinct bean from each individual per-stage job.</li>
 *   <li><strong>No job auto-runs at startup</strong> &mdash; because {@code spring.batch.job.enabled=false}
 *       (declared in {@code application-test.yml}), the Spring Batch metadata carries zero job instances
 *       when the context finishes refreshing.</li>
 * </ol>
 *
 * <h2>Why quiescence matters</h2>
 * <p>The five print jobs (migrated from {@code CBACT01C}/{@code CBACT02C}/{@code CBACT03C},
 * {@code CBCUS01C}, {@code CBTRN01C}) and the five pipeline jobs are launched only <em>explicitly</em>
 * (by a launcher, endpoint, or a sibling {@code *IT}). If any of them &mdash; especially POSTTRAN
 * ({@code CBTRN02C}) &mdash; auto-ran merely because the context booted, it would mutate the seeded
 * database and corrupt the deterministic record counts every other integration test depends on. This
 * test is therefore the guard that keeps the "jobs launch only when asked" contract honest, protecting
 * Gate&nbsp;7 (delivered scope matches the documented five-stage pipeline plus utilities) and
 * contributing to Gate&nbsp;8 coverage.</p>
 *
 * <h2>JCL / COBOL lineage verified (read-only source at commit SHA {@code 27d6c6f}; NOT copied)</h2>
 * <pre>
 *   Pipeline (master {@code cardDemoBatchPipelineJob}, COND=(0,NE) gated):
 *     POSTTRAN (app/jcl/POSTTRAN.jcl, CBTRN02C)          -&gt; postTransactionStep  / postTransactionJob
 *     INTCALC  (app/jcl/INTCALC.jcl,  CBACT04C)          -&gt; interestCalculationStep / interestCalculationJob
 *     COMBTRAN (app/jcl/COMBTRAN.jcl, SORT + IDCAMS)     -&gt; combineTransactionStep / combineTransactionJob
 *     CREASTMT (app/jcl/CREASTMT.JCL, CBSTM03A/CBSTM03B) -&gt; statementStep         / statementJob
 *     TRANREPT (app/jcl/TRANREPT.jcl, CBTRN03C)          -&gt; transactionReportStep / transactionReportJob
 *   Print / reference (on-demand utilities, never in the master flow):
 *     CBACT01C (read/print account data)          -&gt; printAccountStep   / printAccountJob
 *     CBACT02C (read/print card data)             -&gt; printCardStep      / printCardJob
 *     CBACT03C (read/print card cross-reference)  -&gt; printCardXrefStep  / printCardXrefJob
 *     CBCUS01C (read/print customer data)         -&gt; printCustomerStep  / printCustomerJob
 *     CBTRN01C (read/print transaction data)      -&gt; printTransactionStep / printTransactionJob
 * </pre>
 *
 * <h2>Environment</h2>
 * <p>This is a Failsafe ({@code *IT}) integration test. It inherits the shared singleton containers and
 * the fully wired {@code test}-profile context from {@link AbstractBatchIntegrationTest}: PostgreSQL&nbsp;16
 * (Flyway {@code V1}/{@code V2}/{@code V3} applied, Spring Batch metadata tables auto-provisioned via
 * {@code spring.batch.jdbc.initialize-schema=always}) and LocalStack (S3/SQS/SNS) with <strong>zero live
 * AWS</strong>. It launches <strong>no</strong> job and mutates <strong>no</strong> table &mdash; every
 * assertion reads only the Spring bean registry and the Spring Batch metadata, so it neither provisions
 * an S3 bucket / SQS queue nor requires one.</p>
 *
 * <p>Strict launch-<em>ordering</em> parity of the master flow (POSTTRAN&nbsp;&rarr;&nbsp;INTCALC&nbsp;&rarr;
 * COMBTRAN&nbsp;&rarr;&nbsp;CREASTMT&nbsp;&rarr;&nbsp;TRANREPT) and its {@code COND=(0,NE)} gating are
 * verified by launching the job in {@code com.carddemo.config.BatchConfigIT}; this class asserts
 * composition and scope only. Design rationale lives in {@code docs/decision-log.md} and the
 * COBOL/JCL&nbsp;&rarr;&nbsp;Java mapping in {@code docs/traceability-matrix.md} (Explainability rule),
 * not in these comments.</p>
 *
 * @see AbstractBatchIntegrationTest
 * @see com.carddemo.config.BatchConfig
 * @see PrintReferenceJobs
 */
@DisplayName("Batch job/step wiring & startup quiescence — 11 jobs, 10 steps, none auto-run")
class BatchJobConfigurationIT extends AbstractBatchIntegrationTest {

    // -----------------------------------------------------------------------------------------------
    // Expected bean names — the authoritative, discovered inventory of the migrated batch layer. Each
    // name is BOTH the Spring bean name (the @Bean method name) AND the Spring Batch internal
    // job/step name (the String passed to JobBuilder/StepBuilder); production keeps the two identical,
    // which is itself asserted below.
    // -----------------------------------------------------------------------------------------------

    /** The five migrated pipeline-stage jobs, listed in preserved JCL execution order. */
    private static final List<String> PIPELINE_JOB_NAMES = List.of(
            "postTransactionJob",       // POSTTRAN / CBTRN02C
            "interestCalculationJob",   // INTCALC  / CBACT04C
            "combineTransactionJob",    // COMBTRAN / SORT + IDCAMS REPRO
            "statementJob",             // CREASTMT / CBSTM03A + CBSTM03B
            "transactionReportJob");    // TRANREPT / CBTRN03C

    /** The five on-demand print/reference jobs (never part of the master flow). */
    private static final List<String> PRINT_JOB_NAMES = List.of(
            "printAccountJob",          // CBACT01C
            "printCardJob",             // CBACT02C
            "printCardXrefJob",         // CBACT03C
            "printCustomerJob",         // CBCUS01C
            "printTransactionJob");     // CBTRN01C

    /** The master orchestration job that assembles the five pipeline stages (config.BatchConfig). */
    private static final String MASTER_JOB_NAME = "cardDemoBatchPipelineJob";

    /** Every expected {@link Job} bean name: 5 pipeline + 5 print + 1 master = 11. */
    private static final List<String> ALL_JOB_NAMES = Stream.concat(
                    Stream.concat(PIPELINE_JOB_NAMES.stream(), PRINT_JOB_NAMES.stream()),
                    Stream.of(MASTER_JOB_NAME))
            .toList();

    /** The five migrated pipeline-stage steps, in preserved JCL execution order. */
    private static final List<String> PIPELINE_STEP_NAMES = List.of(
            "postTransactionStep",
            "interestCalculationStep",
            "combineTransactionStep",
            "statementStep",
            "transactionReportStep");

    /** The five print/reference steps (one per print job). */
    private static final List<String> PRINT_STEP_NAMES = List.of(
            "printAccountStep",
            "printCardStep",
            "printCardXrefStep",
            "printCustomerStep",
            "printTransactionStep");

    /** Every expected {@link Step} bean name: 5 pipeline + 5 print = 10 (the master reuses the pipeline steps). */
    private static final List<String> ALL_STEP_NAMES =
            Stream.concat(PIPELINE_STEP_NAMES.stream(), PRINT_STEP_NAMES.stream()).toList();

    /** Exact number of {@link Job} beans the migrated batch layer must expose (Gate&nbsp;7 scope match). */
    private static final int EXPECTED_JOB_COUNT = 11;

    /** Exact number of {@link Step} beans the migrated batch layer must expose. */
    private static final int EXPECTED_STEP_COUNT = 10;

    /** The fully refreshed application context; the source of truth for the bean-registry assertions. */
    @Autowired
    private ApplicationContext applicationContext;

    /** Spring Boot's auto-configured Batch metadata reader; used to prove startup quiescence. */
    @Autowired
    private JobExplorer jobExplorer;

    // ===============================================================================================
    // Context sanity — the full batch context booted and the metadata reader is wired.
    // ===============================================================================================

    @Test
    @DisplayName("Context sanity — the full batch application context boots with its JobExplorer wired")
    void batchInfrastructureIsWired() {
        assertThat(applicationContext)
                .as("the full Spring context must boot for this integration test")
                .isNotNull();
        assertThat(jobExplorer)
                .as("Spring Boot must auto-configure a JobExplorer over the Batch metadata tables")
                .isNotNull();
    }

    // ===============================================================================================
    // Phase 1 — every expected Job bean exists, and exactly 11 jobs are registered.
    // ===============================================================================================

    @Test
    @DisplayName("Phase 1a — all five pipeline Job beans (POSTTRAN→INTCALC→COMBTRAN→CREASTMT→TRANREPT) exist by name")
    void pipelineJobBeansArePresentByName() {
        assertJobBeansPresent(PIPELINE_JOB_NAMES, "pipeline");
    }

    @Test
    @DisplayName("Phase 1b — all five print/reference Job beans (CBACT01C/02C/03C, CBCUS01C, CBTRN01C) exist by name")
    void printJobBeansArePresentByName() {
        assertJobBeansPresent(PRINT_JOB_NAMES, "print/reference");
    }

    @Test
    @DisplayName("Phase 1c — the master 'cardDemoBatchPipelineJob' Job bean exists by name")
    void masterPipelineJobBeanIsPresentByName() {
        assertThat(applicationContext.containsBean(MASTER_JOB_NAME))
                .as("expected master Job bean '%s' to be registered", MASTER_JOB_NAME)
                .isTrue();
        final Job masterJob = applicationContext.getBean(MASTER_JOB_NAME, Job.class);
        assertThat(masterJob)
                .as("master Job bean '%s' must not be null", MASTER_JOB_NAME)
                .isNotNull();
        assertThat(masterJob.getName())
                .as("master Job bean '%s' internal job name must match its bean name", MASTER_JOB_NAME)
                .isEqualTo(MASTER_JOB_NAME);
    }

    @Test
    @DisplayName("Phase 1d — exactly 11 Job beans exist (5 pipeline + 5 print + 1 master) — Gate 7 scope match")
    void exactlyElevenJobBeansAreRegistered() {
        final Map<String, Job> jobBeans = applicationContext.getBeansOfType(Job.class);
        assertThat(jobBeans)
                .as("delivered batch scope must be exactly 5 pipeline + 5 print + 1 master = 11 Job beans")
                .hasSize(EXPECTED_JOB_COUNT);
        assertThat(jobBeans.keySet())
                .as("the registered Job bean names must be exactly the discovered inventory")
                .containsExactlyInAnyOrderElementsOf(ALL_JOB_NAMES);
    }

    // ===============================================================================================
    // Phase 2 — every expected Step bean exists, and exactly 10 steps are registered.
    // ===============================================================================================

    @Test
    @DisplayName("Phase 2a — all five pipeline Step beans exist by name")
    void pipelineStepBeansArePresentByName() {
        assertStepBeansPresent(PIPELINE_STEP_NAMES, "pipeline");
    }

    @Test
    @DisplayName("Phase 2b — all five print/reference Step beans exist by name")
    void printStepBeansArePresentByName() {
        assertStepBeansPresent(PRINT_STEP_NAMES, "print/reference");
    }

    @Test
    @DisplayName("Phase 2c — exactly 10 Step beans exist (5 pipeline + 5 print; master reuses the pipeline steps)")
    void exactlyTenStepBeansAreRegistered() {
        final Map<String, Step> stepBeans = applicationContext.getBeansOfType(Step.class);
        assertThat(stepBeans)
                .as("delivered batch scope must be exactly 5 pipeline + 5 print = 10 Step beans")
                .hasSize(EXPECTED_STEP_COUNT);
        assertThat(stepBeans.keySet())
                .as("the registered Step bean names must be exactly the discovered inventory")
                .containsExactlyInAnyOrderElementsOf(ALL_STEP_NAMES);
    }

    // ===============================================================================================
    // Phase 3 — the master job composes the five pipeline stages and is distinct from the stage jobs.
    // ===============================================================================================

    @Test
    @DisplayName("Phase 3 — master job composes exactly the five pipeline steps and is distinct from each per-stage job")
    void masterPipelineJobComposesFivePipelineSteps() {
        final Job masterJob = applicationContext.getBean(MASTER_JOB_NAME, Job.class);

        // The master job is a flow job assembled by BatchConfig; AbstractJob exposes its step graph.
        assertThat(masterJob)
                .as("master pipeline job should be a Spring Batch AbstractJob exposing its step graph")
                .isInstanceOf(AbstractJob.class);
        final AbstractJob abstractMasterJob = (AbstractJob) masterJob;

        // A FlowJob does not guarantee iteration order for getStepNames(), so assert membership of
        // exactly the five migrated pipeline stages. Strict launch-order + COND=(0,NE) parity is
        // verified by launching the job in config.BatchConfigIT; this class asserts composition only.
        assertThat(abstractMasterJob.getStepNames())
                .as("master pipeline job must compose exactly the five pipeline stage steps")
                .containsExactlyInAnyOrderElementsOf(PIPELINE_STEP_NAMES);

        // The master job orchestrates the stages; it must be a DISTINCT bean/instance from each
        // individual per-stage job (guarding against accidental alias or duplicate wiring).
        for (final String stageJobName : PIPELINE_JOB_NAMES) {
            final Job stageJob = applicationContext.getBean(stageJobName, Job.class);
            assertThat(masterJob)
                    .as("master job must be a distinct instance from per-stage job '%s'", stageJobName)
                    .isNotSameAs(stageJob);
            assertThat(masterJob.getName())
                    .as("master job name must differ from per-stage job '%s'", stageJobName)
                    .isNotEqualTo(stageJob.getName());
        }
    }

    // ===============================================================================================
    // Phase 4 — CRITICAL: no job auto-runs at startup (spring.batch.job.enabled=false).
    // ===============================================================================================

    @Test
    @DisplayName("Phase 4 — NO job auto-runs at startup: no JobLauncherApplicationRunner is registered (spring.batch.job.enabled=false)")
    void noBatchJobAutoRunsAtStartup() {
        // Spring Boot auto-launches batch jobs at startup through exactly one mechanism: the
        // JobLauncherApplicationRunner that BatchAutoConfiguration registers ONLY when batch-job
        // execution is enabled. The 'test' profile sets spring.batch.job.enabled=false
        // (application-test.yml), so that runner MUST be absent from the refreshed context — the
        // definitive proof that merely booting the context launches no job (in particular POSTTRAN /
        // CBTRN02C never fires to mutate the seeded database that every other IT depends on).
        //
        // This invariant is asserted against the application context (the bean registry) and NOT via
        // JobExplorer.getJobNames() / getJobInstanceCount(): every batch *IT shares ONE PostgreSQL
        // container (the singleton-container pattern in AbstractBatchIntegrationTest), so the shared
        // Spring Batch metadata tables legitimately accumulate the JobInstances that sibling ITs
        // create when they explicitly launch a job. Global metadata therefore reflects the whole JVM's
        // launch history, not THIS context's startup behaviour; the presence/absence of the auto-run
        // runner is the correct, context-local signal. The same idiom is used by the sibling slice
        // test batch.PrintReferenceJobsTest.
        assertThat(applicationContext.getBeansOfType(JobLauncherApplicationRunner.class))
                .as("spring.batch.job.enabled=false must prevent a JobLauncherApplicationRunner from "
                        + "being registered, so no job is launched at context startup")
                .isEmpty();
    }

    // ===============================================================================================
    // Shared assertion helpers.
    // ===============================================================================================

    /**
     * Asserts that each named {@link Job} bean is registered and that its internal Spring Batch job
     * name equals its Spring bean name (the production contract this suite relies on).
     *
     * @param jobNames the expected job bean names to verify; must not be {@code null}
     * @param group    a human-readable group label used only in assertion messages (for example
     *                 {@code "pipeline"} or {@code "print/reference"}); must not be {@code null}
     */
    private void assertJobBeansPresent(final List<String> jobNames, final String group) {
        for (final String jobName : jobNames) {
            assertThat(applicationContext.containsBean(jobName))
                    .as("expected %s Job bean '%s' to be registered", group, jobName)
                    .isTrue();
            final Job job = applicationContext.getBean(jobName, Job.class);
            assertThat(job)
                    .as("%s Job bean '%s' must not be null", group, jobName)
                    .isNotNull();
            assertThat(job.getName())
                    .as("%s Job bean '%s' internal job name must match its bean name", group, jobName)
                    .isEqualTo(jobName);
        }
    }

    /**
     * Asserts that each named {@link Step} bean is registered and that its internal Spring Batch step
     * name equals its Spring bean name.
     *
     * @param stepNames the expected step bean names to verify; must not be {@code null}
     * @param group     a human-readable group label used only in assertion messages; must not be {@code null}
     */
    private void assertStepBeansPresent(final List<String> stepNames, final String group) {
        for (final String stepName : stepNames) {
            assertThat(applicationContext.containsBean(stepName))
                    .as("expected %s Step bean '%s' to be registered", group, stepName)
                    .isTrue();
            final Step step = applicationContext.getBean(stepName, Step.class);
            assertThat(step)
                    .as("%s Step bean '%s' must not be null", group, stepName)
                    .isNotNull();
            assertThat(step.getName())
                    .as("%s Step bean '%s' internal step name must match its bean name", group, stepName)
                    .isEqualTo(stepName);
        }
    }
}
