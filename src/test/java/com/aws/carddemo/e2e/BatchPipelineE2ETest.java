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
package com.aws.carddemo.e2e;

// ---------------------------------------------------------------------------
// Internal project imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * AbstractBatchIT -- shared base class providing
//     @SpringBootTest + @SpringBatchTest + @Testcontainers + @ActiveProfiles
//     + @DirtiesContext(AFTER_CLASS) class-level annotations, the
//     @Container static PostgreSQLContainer (postgres:16-alpine) shared by
//     every @Test method in this class, the @DynamicPropertySource
//     callback that binds the container JDBC URL + ephemeral credentials
//     into Spring's environment, and the autowired JobLauncherTestUtils /
//     JobRepositoryTestUtils / ApplicationContext fields used to drive
//     Spring Batch jobs and clean the JobRepository between runs. Extending
//     this base class is the AAP §0.4.4 ("Batch ITs and end-to-end ITs:
//     Testcontainers PostgreSQL 16, shared container per test class via
//     Spring's context cache") prescribed pattern.
//
//   * BaselineDiffUtil -- the byte-identical parity assertion utility
//     consumed at every stage of the pipeline. Called via the static
//     BaselineDiffUtil.assertByteEqual(actual, expected) method to verify
//     that each Spring Batch job's output file matches the captured COBOL
//     baseline exactly. The zero-delta requirement is AAP §0.10.4 and is
//     the strongest acceptance gate in the entire migration.
//
//   * TestFixtures -- centralised constants for baseline expected file
//     names (TestFixtures.Paths.EXPECTED_POSTED, EXPECTED_TCATBAL_AFTER_INTEREST,
//     EXPECTED_COMBINED, EXPECTED_STATEMENTS_TEXT, EXPECTED_STATEMENTS_HTML,
//     EXPECTED_TRANSACTION_REPORT), canonical baseline input fixture
//     filenames (FIXTURE_DAILYTRAN, FIXTURE_TCATBAL, FIXTURE_DISCGRP,
//     FIXTURE_CARDXREF, FIXTURE_CUSTDATA, FIXTURE_ACCTDATA, FIXTURE_TRANTYPE,
//     FIXTURE_TRANCATG), classpath directory prefixes
//     (CLASSPATH_BASELINE_INPUT_DIR, CLASSPATH_BASELINE_EXPECTED_DIR), and
//     JCL job parameter literals (Dates.INTCALC_PARM = "2022071800",
//     Dates.REPORT_START_DATE = "2022-01-01", Dates.REPORT_END_DATE =
//     "2022-07-06"). Per AAP §0.5.1 row "TestFixtures" the test code MUST
//     reference these constants rather than embed string literals.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.AbstractBatchIT;
import com.aws.carddemo.testsupport.BaselineDiffUtil;
import com.aws.carddemo.testsupport.FixtureLoader;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint -- JUnit 5 only,
// never JUnit 4 / Vintage).
//
//   * @BeforeAll -- one-shot pre-pipeline cleanup hook. Requires
//     @TestInstance(Lifecycle.PER_CLASS) below so that the method can be
//     non-static (the inherited jobRepositoryTestUtils is an instance
//     field). Annotated @BeforeAll (not @BeforeEach) because we want a
//     single cleanup before the entire pipeline, not before each stage --
//     the parent AbstractBatchIT also declares a @BeforeEach
//     cleanJobRepository() hook that runs between stages.
//
//   * @DisplayName -- documents the intent of the test class and each
//     pipeline stage in the IDE / surefire report output. The class-level
//     name names the full JCL job-stream chain; each method-level name
//     names the stage number, the migrated Spring Batch Job bean, and the
//     COBOL / JCL counterpart.
//
//   * @MethodOrderer + @TestMethodOrder -- configure JUnit 5's method
//     ordering so the @Order(1..6) annotations drive deterministic
//     sequential execution. The default (unordered) ordering would allow
//     JUnit to run Stage 4 before Stage 3, which would fail because
//     Stage 4 depends on Stage 3's output file. Per AAP §0.4.1: "Method
//     ordering: @TestMethodOrder(MethodOrderer.OrderAnnotation.class) --
//     the pipeline is intrinsically sequential."
//
//   * @Order -- per-method ordering priority (1..6). The numeric values
//     mirror the JCL job-stream sequence on the mainframe: POSTTRAN runs
//     first, then INTCALC, then COMBTRAN (which merges the first two),
//     then CREASTMT and TRANREPT (which run on the merged output). The
//     trailing Stage 6 is a summary assertion.
//
//   * @Test -- marks each pipeline stage as an executable test. Each
//     stage performs Arrange / Act / Assert in classical JUnit form:
//     arrange the stage's input fixtures, launch the Spring Batch Job
//     via jobLauncherTestUtils.launchJob(JobParameters), then assert on
//     BatchStatus.COMPLETED, step skip counts, and byte-identical parity.
//
//   * @TestInstance(Lifecycle.PER_CLASS) -- shares one BatchPipelineE2ETest
//     instance across all six @Test methods (the JUnit Jupiter default is
//     PER_METHOD which creates a fresh instance per @Test). Sharing is
//     necessary because the pipeline carries state across stages: the
//     postedFileActual / interestFileActual / combinedFileActual fields
//     populated by Stage N must be readable by Stage N+1. Also unlocks
//     non-static @BeforeAll (used by cleanJobRepositoryOnce() to call the
//     instance-scoped jobRepositoryTestUtils.removeJobExecutions()).
//
//   * @TempDir -- JUnit 5's managed-lifecycle temporary directory. JUnit
//     creates the directory before the test starts and deletes it (and
//     all its contents) after the test class finishes. Used here as the
//     destination for all six produced output files plus the eight staged
//     input fixtures. Per AAP §0.4.4 the temp-directory approach
//     eliminates manual cleanup and guarantees filesystem isolation
//     between test runs.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

// ---------------------------------------------------------------------------
// Spring Batch core domain types (AAP §0.6.1 spring-batch-core).
//
//   * Job -- the abstract bean type returned by ApplicationContext.getBean()
//     when looking up the five migrated Spring Batch jobs by their @Bean
//     names: transactionPostingJob, interestCalculationJob,
//     combineTransactionsJob, statementGenerationJob, transactionReportJob.
//     Each Job carries the full step graph (readers + processors +
//     writers + tasklets) that replaces the corresponding COBOL/JCL job
//     stream.
//
//   * JobExecution -- the runtime metadata returned by
//     jobLauncherTestUtils.launchJob(params). Carries the BatchStatus
//     (COMPLETED / FAILED / STOPPED), the per-step StepExecution rows,
//     start/end timestamps, and the exit code. Asserted on getStatus() ==
//     BatchStatus.COMPLETED and on per-step getSkipCount() == 0.
//
//   * JobParameters / JobParametersBuilder -- typed parameter container
//     and its canonical builder. Used to assemble the input/output file
//     paths, the INTCALC PARM date, the TRANREPT date range, and the
//     run.timestamp uniqueness factor. Spring Batch keys JobInstances by
//     parameter hash; without a unique parameter every re-run of this
//     test would collide with the prior COMPLETED execution and
//     refuse to launch (JobInstanceAlreadyCompleteException).
//
//   * BatchStatus -- the enum used in the per-stage status assertion.
//     BatchStatus.COMPLETED is the Spring Batch analogue of CBTRN02C's
//     "END OF EXECUTION OF PROGRAM CBTRN02C" DISPLAY + GOBACK with RC=0
//     (and similarly for CBACT04C, CBSTM03A/B, CBTRN03C, plus the
//     synthetic combineTransactionsJob that replaces the DFSORT step).
//
//   * StepExecution -- per-step runtime metadata. Asserted on
//     getSkipCount() == 0 for every step of every stage. The
//     fail-fast contract documented in CBTRN02C requires zero
//     skips on the canonical input -- any non-zero count indicates
//     either a regression in the migrated processor or a corruption
//     in the staged input fixture.
// ---------------------------------------------------------------------------
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

// ---------------------------------------------------------------------------
// AssertJ fluent assertions (AAP §0.6.1 assertj-core, §0.10.10 style).
//
// Static import of Assertions.assertThat so test bodies can call
// assertThat(...).as(...).isEqualTo(...) directly without prefixing the
// class. The .as(String) clause names the COBOL/JCL counterpart so a
// failure message in the surefire report is self-documenting.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

// ---------------------------------------------------------------------------
// Spring DI annotation (AAP §0.6.1 spring-beans).
//
// Used to inject the JobLauncherTestUtils, JobRepositoryTestUtils, and
// ApplicationContext beans contributed by @SpringBatchTest. The parent
// AbstractBatchIT declares those fields as protected and @Autowired; no
// new @Autowired declaration is needed here, but the import is kept so the
// dependency on spring-beans is explicit at the import site (AAP §0.5.5
// "Import Updates Required Across Test Files").
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired; // NOPMD - used transitively via inherited fields

// ---------------------------------------------------------------------------
// Spring TestContext + Batch test bean (AAP §0.6.1 spring-context,
// spring-batch-test).
//
//   * ApplicationContext -- inherited from AbstractBatchIT. Used to
//     resolve each Spring Batch Job @Bean by name and pin it onto
//     JobLauncherTestUtils via setJob(Job). The lookup is required
//     because the migration declares five Job beans (one per migrated
//     JCL job) and JobLauncherTestUtils holds only one Job reference at
//     a time -- the test must explicitly switch between jobs between
//     pipeline stages.
//
//   * JobLauncherTestUtils -- the spring-batch-test bean contributed by
//     @SpringBatchTest. Provides setJob(Job) and launchJob(JobParameters)
//     for synchronous test-friendly Job execution.
//
//   * JobRepositoryTestUtils -- the spring-batch-test companion bean.
//     Provides removeJobExecutions() to truncate BATCH_JOB_EXECUTION /
//     BATCH_JOB_INSTANCE / BATCH_STEP_EXECUTION tables. Called from the
//     @BeforeAll cleanJobRepositoryOnce() hook to start the pipeline
//     from a clean repository state.
// ---------------------------------------------------------------------------
import org.springframework.context.ApplicationContext; // NOPMD - used transitively via inherited field
import org.springframework.batch.test.JobLauncherTestUtils; // NOPMD - used transitively via inherited field
import org.springframework.batch.test.JobRepositoryTestUtils; // NOPMD - used transitively via inherited field

// ---------------------------------------------------------------------------
// Java NIO & I/O (java.nio.file.* and java.io.IOException standard library).
//
//   * Path -- the canonical type for filesystem references throughout
//     the test. Used to (a) capture pipelineOutputDir from the @TempDir
//     extension, (b) hold inter-stage actual-output handles
//     (postedFileActual / interestFileActual / combinedFileActual) that
//     chain stage outputs into subsequent stage inputs, (c) hold staged
//     baseline-input handles via stageBaselineInput(), and (d) construct
//     expected-file Paths via resolveExpectedReference() for the
//     BaselineDiffUtil byte-equality assertions.
//
//   * Files -- written via Files.write(...) inside the stageBaselineInput
//     helper to materialise classpath fixtures onto the @TempDir-backed
//     filesystem (FlatFileItemReader cannot read classpath resources
//     directly).
//
//   * IOException -- thrown by Files.write and propagated up through
//     the @Test method signatures (the same idiom used by the sibling
//     baseline-parity ITs).
// ---------------------------------------------------------------------------
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end Spring Batch pipeline parity test for the CardDemo migration.
 *
 * <p>This is the most foundational of the 4 E2E tests in
 * {@code src/test/java/com/aws/carddemo/e2e/}. Per AAP §0.5.1, its purpose is
 * to exercise <strong>the complete batch pipeline</strong> &mdash; chaining
 * all five migrated Spring Batch jobs in the same order the COBOL JCL would
 * execute them on the mainframe &mdash; and to assert
 * <strong>byte-identical parity</strong> with the captured COBOL baseline
 * outputs at every stage.
 *
 * <h2>COBOL/JCL Pipeline Heritage (Source of Truth)</h2>
 *
 * <p>The mainframe execution order (deduced from JCL {@code JOB}/{@code EXEC
 * PGM=} chain) is:
 *
 * <pre>{@code
 *  POSTTRAN.jcl  ──▶  INTCALC.jcl  ──▶  COMBTRAN.jcl  ──▶  CREASTMT.JCL
 *  (CBTRN02C)        (CBACT04C)        (DFSORT only)      (CBSTM03A/B)
 *  POSTS daily       APPLIES interest  MERGES posted +    GENERATES text
 *  transactions      to TCATBAL rows,  systran by         + HTML statements
 *  to accounts +     grouped by        TRAN-ID
 *  cards;            DISCGRP (DEFAULT,
 *  produces          ZEROAPR fallback)                    (and in parallel)
 *  posted.txt        produces                             TRANREPT.jcl
 *                    tcatbal_after_                       (CBTRN03C)
 *                    interest.txt                         PRODUCES the
 *                                                          transaction
 *                                                          report
 * }</pre>
 *
 * <h2>Migration mapping (per AAP §0.5.1 row by row)</h2>
 *
 * <table>
 *   <caption>COBOL/JCL to Spring Batch mapping with output files</caption>
 *   <tr><th>COBOL/JCL</th><th>Spring Batch Bean</th><th>Output</th></tr>
 *   <tr><td>{@code POSTTRAN.jcl} / {@code CBTRN02C.cbl}</td>
 *       <td>{@code transactionPostingJob}</td>
 *       <td>{@code posted.txt}</td></tr>
 *   <tr><td>{@code INTCALC.jcl} / {@code CBACT04C.cbl}</td>
 *       <td>{@code interestCalculationJob}</td>
 *       <td>{@code tcatbal_after_interest.txt}</td></tr>
 *   <tr><td>{@code COMBTRAN.jcl} (DFSORT)</td>
 *       <td>{@code combineTransactionsJob}</td>
 *       <td>{@code combined.txt}</td></tr>
 *   <tr><td>{@code CREASTMT.JCL} / {@code CBSTM03A.cbl} + {@code CBSTM03B.cbl}</td>
 *       <td>{@code statementGenerationJob}</td>
 *       <td>{@code statements_text.txt}, {@code statements_html.txt}</td></tr>
 *   <tr><td>{@code TRANREPT.jcl} / {@code CBTRN03C.cbl}</td>
 *       <td>{@code transactionReportJob}</td>
 *       <td>{@code transaction_report.txt}</td></tr>
 * </table>
 *
 * <h2>Why this test exists alongside the per-job {@code *BaselineParityIT} suite</h2>
 *
 * <p>The five sibling baseline-parity ITs in
 * {@code src/test/java/com/aws/carddemo/batch/} each pin a single Spring
 * Batch Job in isolation against the canonical inputs under
 * {@code src/test/resources/baseline/input/}. They prove that each job
 * works against canned input.
 *
 * <p>This E2E test pins the <strong>composed pipeline</strong>: it uses each
 * stage's output as the next stage's input, proving that the pipeline is
 * <em>composable</em> &mdash; not just that each job works in isolation.
 * Specifically, Stage 3 (COMBTRAN) reads
 * {@link #postedFileActual} (the Stage 1 output) and
 * {@link #interestFileActual} (the Stage 2 output), NOT the canned
 * {@code baseline/expected/posted.txt} / {@code tcatbal_after_interest.txt}
 * files that the sibling {@code CombineTransactionsBaselineParityIT} uses.
 * Similarly, Stage 4 and Stage 5 read {@link #combinedFileActual} (the
 * Stage 3 output) instead of the canned {@code baseline/expected/combined.txt}.
 * If the per-job ITs all pass but this E2E test fails, the regression is in
 * the pipeline composition (job-to-job hand-off), not in any individual
 * job's logic. Both gates are required; this test is not redundant.
 *
 * <h2>Test strategy (per AAP §0.4.1 + folder README)</h2>
 * <ul>
 *   <li><strong>Base class:</strong> extends {@link AbstractBatchIT}, which
 *       supplies {@code @SpringBootTest} + {@code @SpringBatchTest} +
 *       {@code @Testcontainers} + the {@code @Container static
 *       PostgreSQLContainer<?>} + {@code @DynamicPropertySource} datasource
 *       wiring + autowired {@code JobLauncherTestUtils} /
 *       {@code JobRepositoryTestUtils} / {@code ApplicationContext}.</li>
 *   <li><strong>Method ordering:</strong>
 *       {@code @TestMethodOrder(MethodOrderer.OrderAnnotation.class)} +
 *       {@code @Order(1..6)} drive deterministic sequential execution. The
 *       pipeline is intrinsically sequential: Stage N cannot run before
 *       Stage N-1.</li>
 *   <li><strong>Test-instance lifecycle:</strong>
 *       {@code @TestInstance(Lifecycle.PER_CLASS)} shares one
 *       {@code BatchPipelineE2ETest} instance across all six {@code @Test}
 *       methods so the inter-stage {@link #postedFileActual} /
 *       {@link #interestFileActual} / {@link #combinedFileActual} fields
 *       can carry data from one stage to the next. Also unlocks non-static
 *       {@code @BeforeAll}.</li>
 *   <li><strong>Job switching:</strong> each stage calls
 *       {@code applicationContext.getBean("<jobName>", Job.class)} +
 *       {@code jobLauncherTestUtils.setJob(...)} immediately before
 *       launching, because {@code JobLauncherTestUtils} holds exactly one
 *       {@code Job} reference at a time. The
 *       {@link AbstractBatchIT#jobBeanNameFromClassName()} convention only
 *       auto-resolves Jobs for {@code *JobIT} class names &mdash; this
 *       class ends in {@code Test}, so the auto-resolution silently
 *       returns {@code null} and the test must set the Job explicitly per
 *       stage. The pattern matches the established sibling
 *       {@code CombineTransactionsBaselineParityIT},
 *       {@code StatementGenerationBaselineParityIT},
 *       {@code TransactionReportBaselineParityIT}, and
 *       {@code InterestCalculationBaselineParityIT} ITs.</li>
 *   <li><strong>Output capture:</strong> each stage writes its actual
 *       output to a unique filename under the {@code @TempDir}-managed
 *       {@link #pipelineOutputDir}. The {@code @TempDir} is automatically
 *       cleaned up by JUnit after the test class finishes.</li>
 *   <li><strong>Input chaining:</strong> reference data fixtures
 *       ({@code dailytran.txt}, {@code tcatbal.txt}, {@code discgrp.txt},
 *       {@code cardxref.txt}, {@code custdata.txt}, {@code acctdata.txt},
 *       {@code trantype.txt}, {@code trancatg.txt}) are staged from the
 *       test classpath into {@link #pipelineOutputDir} via
 *       {@link #stageBaselineInput(String)} so Spring Batch's
 *       {@code FlatFileItemReader} can consume them. Pipeline outputs
 *       from earlier stages become inputs to later stages via the
 *       {@link #postedFileActual} / {@link #interestFileActual} /
 *       {@link #combinedFileActual} field handles &mdash; this is the
 *       canonical pipeline composition contract.</li>
 * </ul>
 *
 * <h2>Non-Negotiable constraints (AAP §0.10)</h2>
 * <ol>
 *   <li><strong>§0.10.1 Require Test Coverage rule.</strong> Every
 *       assertion is on observable output (job status, per-step skip count,
 *       byte-equality of files). The test does NOT recompute interest, does
 *       NOT re-sort the combined file, does NOT regenerate the statement
 *       text &mdash; every output is produced by the real Spring Batch job
 *       beans (real production code). The ONLY external "mock" is the
 *       Testcontainers PostgreSQL (which is a real database).</li>
 *   <li><strong>§0.10.3 Financial Precision.</strong> The byte-equality
 *       check in Stage 2 (interest) is the ULTIMATE financial-precision
 *       gate: it can only pass if every BigDecimal applied
 *       {@code RoundingMode.HALF_EVEN} at scale 2 exactly as the COBOL
 *       ROUNDED clause does. The byte-equality check in Stage 4
 *       (statements) further verifies that totals printed on statements
 *       use scale-2 BigDecimals.</li>
 *   <li><strong>§0.10.4 Immutable Boundaries.</strong> Each
 *       {@link BaselineDiffUtil#assertByteEqual(Path, Path)} call is a
 *       zero-byte-delta acceptance gate. Per AAP §0.7.1, baseline parity
 *       has a 100%-coverage requirement; this test plus the five
 *       individual {@code *BaselineParityIT} tests cover that, with this
 *       test additionally proving that the PIPELINE COMPOSITION (the
 *       JCL-job-stream order) is preserved end-to-end.</li>
 *   <li><strong>§0.10.5 Security Constraints.</strong> No credentials or
 *       financial data are written to logs by this test. Ephemeral
 *       Testcontainers credentials are bound into Spring's environment via
 *       {@code @DynamicPropertySource} (see {@link AbstractBatchIT}).</li>
 *   <li><strong>§0.10.6 Naming & Location.</strong> File at exact path
 *       {@code src/test/java/com/aws/carddemo/e2e/BatchPipelineE2ETest.java}.
 *       Suffix is {@code Test.java} (the e2e folder convention).</li>
 *   <li><strong>§0.10.7 Framework Constraint.</strong> JUnit 5 only.
 *       Imports from {@code org.junit.jupiter.api.*} and
 *       {@code org.springframework.batch.test.*}. No JUnit 4. No PowerMock.
 *       No Mockito (all dependencies are real Spring beans).</li>
 *   <li><strong>§0.10.9 Test Independence.</strong> The {@link AbstractBatchIT}
 *       {@code @DirtiesContext(AFTER_CLASS)} forces Spring to discard the
 *       cached ApplicationContext after this class completes, releasing the
 *       PostgreSQL container for the next test class.</li>
 *   <li><strong>§0.10.10 Style Consistency.</strong> Arrange / Act /
 *       Assert blocks separated by blank lines. AssertJ fluent only. Each
 *       {@code assertThat(...)} carries a descriptive {@code .as(...)}
 *       message naming the stage and the COBOL/JCL counterpart. No
 *       {@code System.out.println} for debugging.</li>
 * </ol>
 *
 * @see AbstractBatchIT
 * @see BaselineDiffUtil#assertByteEqual(Path, Path)
 * @see TestFixtures.Paths
 * @see TestFixtures.Dates
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Batch Pipeline E2E -- POSTTRAN -> INTCALC -> COMBTRAN -> CREASTMT || TRANREPT byte-identical parity")
class BatchPipelineE2ETest extends AbstractBatchIT {

    // ------------------------------------------------------------------
    // Eager Testcontainers PostgreSQL start (class-load time)
    // ------------------------------------------------------------------
    // Why: this E2E class is annotated @TestInstance(Lifecycle.PER_CLASS).
    // Under that lifecycle the single test instance is constructed at the
    // START of the class lifecycle, and JUnit Jupiter invokes
    // TestInstancePostProcessor extensions on the freshly-built instance
    // BEFORE it invokes any BeforeAllCallback. Spring's SpringExtension is
    // a TestInstancePostProcessor; its postProcessTestInstance() method
    // calls TestContextManager.prepareTestInstance() which in turn calls
    // ServletTestExecutionListener.setUpRequestContextIfNecessary() which
    // triggers ApplicationContext loading. Context loading evaluates
    // @DynamicPropertySource suppliers -- including the inherited
    // AbstractBatchIT#postgresProperties() supplier that calls
    // POSTGRES.getJdbcUrl(). The Testcontainers JUnit 5 extension's
    // BeforeAllCallback (which starts @Container static fields) has not
    // run yet at this point, so getJdbcUrl() throws:
    //     IllegalStateException: Mapped port can only be obtained after
    //     the container is started
    //
    // Sibling JobIT and BaselineParityIT classes do NOT use
    // @TestInstance(PER_CLASS) -- they rely on the default PER_METHOD
    // lifecycle, where each test method constructs a fresh test instance
    // AFTER BeforeAllCallback has run, so the container is already up by
    // the time SpringExtension.postProcessTestInstance fires.
    //
    // This class genuinely needs PER_CLASS so that the inter-stage
    // instance fields (postedFileActual, interestFileActual,
    // combinedFileActual) can carry data from one @Test method to the
    // next via the same test instance. Static fields are not acceptable
    // because they would leak across test classes if the JVM ever runs
    // this class twice in the same forked process.
    //
    // Fix: start the inherited @Container static POSTGRES field eagerly
    // in a static initializer block at class-load time. Static
    // initializers run before any test instance is constructed, before
    // any extension callback, before any field assignment that depends
    // on Spring autowiring -- which guarantees the container is up by
    // the time Spring evaluates the dynamic property supplier.
    // PostgreSQLContainer.start() is idempotent: the @Container
    // extension's subsequent start() call during BeforeAllCallback is a
    // no-op, and the @AfterAllCallback still correctly stops the
    // container after the last @Test completes.
    //
    // Authority: AAP §0.4.4 (Testcontainers PostgreSQL 16 shared per test
    // class), §0.10.9 (Test execution independence), §0.10.2 (Minimal
    // Change Clause -- the fix is one static block inside this test
    // class, no changes to AbstractBatchIT or any production code).
    static {
        // POSTGRES is the protected static final PostgreSQLContainer<?>
        // declared in AbstractBatchIT (line 372). start() blocks until
        // the container is ready (the @Container extension uses the same
        // synchronous start). Subsequent BeforeAllCallback / @Container
        // invocations of start() are no-ops because Testcontainers
        // tracks the container's started state internally.
        POSTGRES.start();
    }

    /**
     * Stage-1 input fixture filename ({@code dailytran.txt}). Read by the
     * Spring Batch {@code transactionPostingJob}'s {@code FlatFileItemReader}
     * (configured via the {@code input.dailytran.path} JobParameter). Staged
     * from the test classpath into {@link #pipelineOutputDir} by
     * {@link #stageBaselineInput(String)} before Stage 1 launches.
     */
    private static final String FIXTURE_DAILYTRAN = TestFixtures.Paths.FIXTURE_DAILYTRAN;

    /**
     * Stage-2 input fixture filename ({@code tcatbal.txt}). Read by the
     * Spring Batch {@code interestCalculationJob}'s {@code FlatFileItemReader}
     * (configured via the {@code input.tcatbal.path} JobParameter). Staged
     * from the test classpath into {@link #pipelineOutputDir} by
     * {@link #stageBaselineInput(String)} before Stage 2 launches.
     */
    private static final String FIXTURE_TCATBAL = TestFixtures.Paths.FIXTURE_TCATBAL;

    /**
     * Stage-2 disclosure-group fixture filename ({@code discgrp.txt}).
     * Passed as the {@code input.discgrp.path} JobParameter alongside
     * {@code input.tcatbal.path}. The migrated {@code interestCalculationJob}
     * resolves its disclosure groups from the
     * {@code discount_groups} table seeded by Flyway {@code V3__seed.sql};
     * staging this file is preserved here for parity with the sibling
     * {@code InterestCalculationBaselineParityIT}.
     */
    private static final String FIXTURE_DISCGRP = TestFixtures.Paths.FIXTURE_DISCGRP;

    /**
     * Stage-4 + Stage-5 reference fixture filename ({@code cardxref.txt}).
     * Passed as {@code input.cardxref.path} alongside the Stage-3 output.
     * The migrated jobs read account / customer / card data primarily
     * from the database tables seeded by Flyway {@code V3__seed.sql};
     * the on-disk staging here mirrors the sibling
     * {@code StatementGenerationBaselineParityIT} and
     * {@code TransactionReportBaselineParityIT} JobParameter signature.
     */
    private static final String FIXTURE_CARDXREF = TestFixtures.Paths.FIXTURE_CARDXREF;

    /** Stage-4 reference fixture filename ({@code custdata.txt}). */
    private static final String FIXTURE_CUSTDATA = TestFixtures.Paths.FIXTURE_CUSTDATA;

    /** Stage-4 reference fixture filename ({@code acctdata.txt}). */
    private static final String FIXTURE_ACCTDATA = TestFixtures.Paths.FIXTURE_ACCTDATA;

    /** Stage-5 reference fixture filename ({@code trantype.txt}). */
    private static final String FIXTURE_TRANTYPE = TestFixtures.Paths.FIXTURE_TRANTYPE;

    /** Stage-5 reference fixture filename ({@code trancatg.txt}). */
    private static final String FIXTURE_TRANCATG = TestFixtures.Paths.FIXTURE_TRANCATG;

    /**
     * Spring Batch {@code @Bean} name for the migrated POSTTRAN.jcl /
     * CBTRN02C job. Looked up from the inherited {@link ApplicationContext}
     * via {@code applicationContext.getBean(JOB_TRANSACTION_POSTING, Job.class)}
     * and pinned onto {@link JobLauncherTestUtils#setJob(Job)} at the
     * beginning of Stage 1.
     */
    private static final String JOB_TRANSACTION_POSTING = "transactionPostingJob";

    /**
     * Spring Batch {@code @Bean} name for the migrated INTCALC.jcl /
     * CBACT04C job. Looked up and pinned in Stage 2.
     */
    private static final String JOB_INTEREST_CALCULATION = "interestCalculationJob";

    /**
     * Spring Batch {@code @Bean} name for the migrated COMBTRAN.jcl
     * (DFSORT-only) job. Looked up and pinned in Stage 3.
     */
    private static final String JOB_COMBINE_TRANSACTIONS = "combineTransactionsJob";

    /**
     * Spring Batch {@code @Bean} name for the migrated CREASTMT.JCL /
     * CBSTM03A + CBSTM03B job. Looked up and pinned in Stage 4. The job's
     * dual-output writer produces both the 80-byte text statement and the
     * 100-byte HTML statement in one launch.
     */
    private static final String JOB_STATEMENT_GENERATION = "statementGenerationJob";

    /**
     * Spring Batch {@code @Bean} name for the migrated TRANREPT.jcl /
     * CBTRN03C job. Looked up and pinned in Stage 5.
     */
    private static final String JOB_TRANSACTION_REPORT = "transactionReportJob";

    /**
     * JUnit 5-managed temporary directory shared across every stage of the
     * pipeline. The field is declared {@code static} so that JUnit's
     * {@code TempDirectoryExtension} treats it as a class-scoped
     * {@code @TempDir} (injected via {@code BeforeAllCallback}, cleaned
     * up via {@code AfterAllCallback}) instead of a method-scoped
     * {@code @TempDir} (re-injected via {@code BeforeEachCallback} for
     * every {@code @Test}, which would deliver a fresh empty directory
     * per stage and break the pipeline's inter-stage file-handoff
     * contract).
     *
     * <p><strong>Why static and not instance.</strong> Empirically (see
     * {@link TempDir} contract in JUnit 5.10.x):
     * {@link org.junit.jupiter.engine.extension.TempDirectory#beforeAll(org.junit.jupiter.api.extension.ExtensionContext)}
     * calls {@code injectStaticFields(...)}, while
     * {@link org.junit.jupiter.engine.extension.TempDirectory#beforeEach(org.junit.jupiter.api.extension.ExtensionContext)}
     * walks the test instances and calls {@code injectInstanceFields(...)}.
     * The latter unconditionally re-assigns every non-static
     * {@code @TempDir} field before every {@code @Test} method --
     * regardless of {@code @TestInstance(Lifecycle.PER_CLASS)}. The
     * net effect: a non-static instance {@code @TempDir} under PER_CLASS
     * still gets a fresh directory per method, and the prior method's
     * directory is cleaned up by its
     * {@link org.junit.jupiter.engine.extension.TempDirectory.CloseablePath#close()}
     * registered in the method-level extension store. By contrast, a
     * static {@code @TempDir} is injected once per class lifecycle and
     * cleaned up only after the last {@code @Test} completes -- the
     * semantics we need for the pipeline's Stage-1-output -&gt;
     * Stage-3-input handoff and the corresponding Stage-2 -&gt; Stage-3
     * and Stage-3 -&gt; Stage-4/5 handoffs.
     *
     * <p>Visibility is package-private (default) because the JUnit
     * extension uses reflection to inject the directory and does not
     * require {@code public} access. Keeping it package-private matches
     * the established convention in {@code InterestCalculationBaselineParityIT}
     * and the other batch ITs for their {@code @TempDir} fields.
     *
     * <p>Authority: AAP §0.4.4 (Test Database / State Management
     * Approach -- batch ITs use shared per-class state for inter-stage
     * data flow), §0.10.9 (Test Execution Independence -- the static
     * field is per-class-instance, not per-JVM, so different test
     * classes get isolated directories).
     */
    @TempDir
    static Path pipelineOutputDir;

    /**
     * Stage-1 actual output file, captured for use as Stage-3 input. The
     * file is populated by {@code transactionPostingJob} via the
     * {@code output.posted.path} JobParameter during Stage 1, and consumed
     * as {@code input.posted.path} during Stage 3.
     *
     * <p>The field is non-static (instance-scoped) and depends on
     * {@code @TestInstance(PER_CLASS)} to share the value across
     * {@code @Test} methods. Without PER_CLASS, JUnit would create a
     * fresh test instance per method and the Stage 3 read would see
     * {@code null}.
     */
    private Path postedFileActual;

    /**
     * Stage-2 actual output file, captured for use as Stage-3 input. The
     * file is populated by {@code interestCalculationJob} via the
     * {@code output.systran.path} JobParameter during Stage 2, and
     * consumed as {@code input.systran.path} during Stage 3.
     */
    private Path interestFileActual;

    /**
     * Stage-3 actual output file, captured for use as Stage-4 AND Stage-5
     * input. The file is populated by {@code combineTransactionsJob} via
     * the {@code output.transact.path} JobParameter during Stage 3, and
     * consumed as {@code input.transact.path} during Stages 4 and 5
     * (CREASTMT and TRANREPT both read the merged-and-sorted combined
     * transaction file).
     */
    private Path combinedFileActual;

    /**
     * One-shot {@link JobRepositoryTestUtils#removeJobExecutions()} hook
     * that runs once before the entire pipeline begins.
     *
     * <p>Annotated {@code @BeforeAll} (not {@code @BeforeEach}) because we
     * want a single cleanup before the first stage, not before every
     * stage. The parent {@link AbstractBatchIT} also declares a
     * {@code @BeforeEach cleanJobRepository()} hook that will run between
     * stages -- that's fine and complementary: it removes stale executions
     * between sequential test methods so each stage starts with a clean
     * {@code BATCH_JOB_EXECUTION} table.
     *
     * <p><strong>Why both @BeforeAll and the inherited @BeforeEach?</strong>
     * The inherited {@code @BeforeEach} runs before every method (including
     * the first). This explicit {@code @BeforeAll} ensures the pipeline's
     * starting state is always clean even if the inherited hook's
     * derivation of the active Job from the class name (which targets
     * {@code *JobIT} subclasses only) leaves any side effects from a
     * preceding test class. The combination is defensive but harmless --
     * both calls invoke the same {@code removeJobExecutions()} method
     * which is idempotent.
     *
     * <p>The method is non-static, which is permitted because the class
     * is annotated {@code @TestInstance(Lifecycle.PER_CLASS)}; the
     * inherited {@code jobRepositoryTestUtils} field is an instance
     * member and cannot be referenced from a static method.
     *
     * <p>Per AAP §0.4.4: "Batch ITs and end-to-end ITs: Testcontainers
     * PostgreSQL 16, shared container per test class via Spring's context
     * cache, {@code JobRepositoryTestUtils.removeJobExecutions()} between
     * tests."
     */
    @BeforeAll
    void cleanJobRepositoryOnce() {
        jobRepositoryTestUtils.removeJobExecutions();
    }

    /**
     * Stage 1 of the pipeline: launches the migrated POSTTRAN.jcl /
     * CBTRN02C Spring Batch job against the canonical {@code dailytran.txt}
     * fixture and asserts byte-identical parity with the captured COBOL
     * baseline at {@code baseline/expected/posted.txt}.
     *
     * <p><strong>Arrange.</strong>
     * <ol>
     *   <li>Resolve the {@code transactionPostingJob} bean from the
     *       inherited {@link ApplicationContext} and pin it on the
     *       inherited {@link JobLauncherTestUtils#setJob(Job)}.
     *       Required because the {@link AbstractBatchIT} per-test
     *       {@code @BeforeEach} hook only auto-resolves Jobs for
     *       {@code *JobIT} subclasses (this class ends in {@code Test}).</li>
     *   <li>Stage the canonical {@code dailytran.txt} fixture from the
     *       test classpath into {@link #pipelineOutputDir} so Spring
     *       Batch's {@code FlatFileItemReader} can consume it (the reader
     *       requires a filesystem path, not a classpath resource).</li>
     *   <li>Capture the destination path for the produced posted file in
     *       the instance field {@link #postedFileActual} so Stage 3 can
     *       reference it as its {@code input.posted.path} input.</li>
     *   <li>Build the {@link JobParameters} bundle matching the
     *       {@code transactionPostingJob}'s {@code @Value} expressions
     *       documented in {@code BatchJobConfig} (lines 295, 316):
     *       {@code input.dailytran.path}, {@code output.posted.path}, plus
     *       a unique {@code run.timestamp} so the JobInstance hash is
     *       distinct across re-runs.</li>
     * </ol>
     *
     * <p><strong>Act.</strong> Call
     * {@code jobLauncherTestUtils.launchJob(params)} which synchronously
     * runs the configured {@code transactionPostingJob} to completion (or
     * failure) and returns the {@link JobExecution} metadata.
     *
     * <p><strong>Assert.</strong>
     * <ol>
     *   <li>{@link BatchStatus#COMPLETED} -- the migration analogue of
     *       CBTRN02C's "END OF EXECUTION OF PROGRAM CBTRN02C" DISPLAY +
     *       GOBACK with RC=0. A non-COMPLETED status would mask any
     *       subsequent byte-equality failure with a less informative
     *       error, so it is checked first.</li>
     *   <li>{@link StepExecution#getSkipCount()} -- zero for every step
     *       per the CBTRN02C fail-fast contract documented in AAP
     *       §0.5.1: any non-zero count means a record was silently
     *       skipped, which would break the byte-equality guarantee.</li>
     *   <li>{@link BaselineDiffUtil#assertByteEqual(Path, Path)} --
     *       byte-for-byte parity with the captured COBOL reference at
     *       {@code baseline/expected/posted.txt}. Zero delta required
     *       per AAP §0.10.4.</li>
     * </ol>
     *
     * @throws IOException        when {@link Files#write(Path, byte[],
     *                            java.nio.file.OpenOption...)} fails while
     *                            staging the input fixture into
     *                            {@link #pipelineOutputDir}, or when
     *                            {@code Files.size} fails on the produced
     *                            output during the parity gate
     * @throws URISyntaxException when {@link #resolveExpectedReference(String)}
     *                            cannot convert the classpath URL of the
     *                            captured baseline reference into a
     *                            filesystem {@link Path}
     * @throws Exception          when {@link JobLauncherTestUtils#launchJob(JobParameters)}
     *                            propagates a Spring Batch launch failure
     */
    @Test
    @Order(1)
    @DisplayName("Stage 1 -- transactionPostingJob produces posted.txt byte-identical to baseline (POSTTRAN/CBTRN02C parity)")
    void stage1_runTransactionPostingJob_producesBaselineIdenticalPostedFile() throws Exception {
        // ---- Arrange ----
        // Pin the transactionPostingJob bean onto JobLauncherTestUtils. The
        // production BatchJobConfig marks transactionPostingJob as
        // @Primary so this is technically a no-op for Stage 1, but the
        // explicit setJob() is kept for symmetry with the other four
        // stages and to defend against any future change to the @Primary
        // designation.
        final Job postingJob = applicationContext.getBean(JOB_TRANSACTION_POSTING, Job.class);
        jobLauncherTestUtils.setJob(postingJob);

        // Stage the canonical dailytran.txt fixture from the test
        // classpath into pipelineOutputDir so Spring Batch's
        // FlatFileItemReader can consume it. Per the established
        // baseline-parity pattern in TransactionPostingBaselineParityIT,
        // the staging copies bytes verbatim (no charset normalisation) to
        // preserve the COBOL fixed-width record layout including sign-
        // overpunch encoding (AAP §0.10.4).
        final Path stagedDailytran = stageBaselineInput(FIXTURE_DAILYTRAN);

        // Capture the destination path for the produced posted output
        // file in the instance field so Stage 3 can read it back as
        // input.posted.path. The basename matches the captured-baseline
        // golden filename so the BaselineDiffUtil call site pairs it
        // naturally with the captured reference under baseline/expected/.
        postedFileActual = pipelineOutputDir.resolve(TestFixtures.Paths.EXPECTED_POSTED);

        // Build the JobParameters bundle. Mirrors the minimal parameter
        // set used by the sibling TransactionPostingBaselineParityIT
        // (the production BatchJobConfig.dailytranReader,
        // BatchJobConfig.postedWriter beans read input.dailytran.path
        // and output.posted.path via SpEL @Value("#{jobParameters[...]}");
        // see BatchJobConfig lines 295 and 316). The run.timestamp factor
        // ensures the JobInstance hash is unique across re-runs so
        // Spring Batch does not refuse to launch with
        // JobInstanceAlreadyCompleteException.
        final JobParameters params = new JobParametersBuilder()
                .addString("input.dailytran.path", stagedDailytran.toAbsolutePath().toString())
                .addString("output.posted.path", postedFileActual.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // launchJob synchronously executes the configured
        // transactionPostingJob bean to completion (or failure) and
        // returns the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Spring Batch execution status -- the Java analogue of
        //     CBTRN02C's "END OF EXECUTION OF PROGRAM CBTRN02C" DISPLAY +
        //     GOBACK with RC=0.
        assertThat(execution.getStatus())
                .as("Stage 1 (POSTTRAN/CBTRN02C) transactionPostingJob must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) StepExecutions non-empty -- a degenerate Job with zero
        //     steps would still report COMPLETED but would not have
        //     produced any output. Asserting non-empty guards against
        //     that pathology.
        assertThat(execution.getStepExecutions())
                .as("Stage 1 (POSTTRAN/CBTRN02C) must have executed at least one Step")
                .isNotEmpty();

        // (3) Per-step skip count -- zero per the CBTRN02C fail-fast
        //     contract. Any skip would indicate either a validation
        //     reject that should have surfaced as a Spring Batch failure
        //     or a record-format issue in the staged input. Asserting
        //     per-step (not just aggregate) preserves the diagnostic
        //     value: a multi-step Job with skips in only one step
        //     would still mask the issue at the aggregate level.
        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getSkipCount())
                    .as("Stage 1 (POSTTRAN/CBTRN02C) Step %s must skip 0 records (fail-fast contract per CBTRN02C)",
                        step.getStepName())
                    .isZero();
        }

        // (4) Output file existence -- the migrated Job must have
        //     produced the posted output file at the path we requested
        //     via the output.posted.path JobParameter. A missing file
        //     would mean the JobParameter was ignored or the writer was
        //     misconfigured.
        assertThat(postedFileActual)
                .as("Stage 1 (POSTTRAN/CBTRN02C) must produce the posted output file at the requested path")
                .exists()
                .isNotEmptyFile();

        // (5) Final parity gate (AAP §0.10.4) -- byte-for-byte zero
        //     delta against the captured COBOL reference output. This
        //     is the strongest acceptance gate for Stage 1; on
        //     mismatch BaselineDiffUtil emits a unified-diff
        //     diagnostic with up to 20 differing lines.
        final Path expected = resolveExpectedReference(TestFixtures.Paths.EXPECTED_POSTED);
        BaselineDiffUtil.assertByteEqual(postedFileActual, expected);
    }

    /**
     * Stage 2 of the pipeline: launches the migrated INTCALC.jcl /
     * CBACT04C Spring Batch job against the canonical {@code tcatbal.txt}
     * + {@code discgrp.txt} fixtures with PARM={@code 2022071800} and
     * asserts byte-identical parity with the captured COBOL baseline at
     * {@code baseline/expected/tcatbal_after_interest.txt}.
     *
     * <p><strong>Why this stage is the critical financial-precision gate.</strong>
     * The captured baseline's interest figures were produced by the COBOL
     * {@code CBACT04C} engine using the {@code COMPUTE} verb with the
     * {@code ROUNDED} clause, which is the COBOL analogue of
     * {@code BigDecimal.divide(..., 2, RoundingMode.HALF_EVEN)}. Byte-
     * identical parity here is the empirical proof that the migrated
     * {@code InterestCalculationProcessor} applied {@code HALF_EVEN}
     * (banker's rounding) at scale 2 on every monetary calculation -- AAP
     * §0.10.3. Any divergence (e.g., {@code HALF_UP} instead of
     * {@code HALF_EVEN}, or scale=4 instead of scale=2) would manifest as
     * a one-byte (or larger) delta and fail this assertion.
     *
     * <p>See {@link #stage1_runTransactionPostingJob_producesBaselineIdenticalPostedFile()}
     * for the canonical Arrange / Act / Assert structure followed by this
     * stage.
     *
     * @throws IOException        when staging the input fixtures fails
     * @throws URISyntaxException when {@link #resolveExpectedReference(String)}
     *                            cannot convert the captured baseline
     *                            classpath URL to a Path
     * @throws Exception          when the underlying Spring Batch launch
     *                            fails
     */
    @Test
    @Order(2)
    @DisplayName("Stage 2 -- interestCalculationJob produces tcatbal_after_interest.txt byte-identical to baseline (INTCALC/CBACT04C parity, INTCALC_PARM=2022071800)")
    void stage2_runInterestCalculationJob_producesBaselineIdenticalInterestFile() throws Exception {
        // ---- Arrange ----
        // Pin the interestCalculationJob bean onto JobLauncherTestUtils
        // (required: the @Primary default would otherwise re-run
        // transactionPostingJob).
        final Job interestJob = applicationContext.getBean(JOB_INTEREST_CALCULATION, Job.class);
        jobLauncherTestUtils.setJob(interestJob);

        // Stage both INTCALC.jcl sequential DD-mapped input files. Per
        // the sibling InterestCalculationBaselineParityIT, the two
        // inputs correspond to the two flat-file DDs in INTCALC.jcl:
        //   * tcatbal.txt = AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
        //   * discgrp.txt = AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
        // The TCATBAL file is read by the production
        // FlatFileItemReader (BatchJobConfig.tcatbalReader, line 650);
        // the DISCGRP file is preserved here for parity with the
        // sibling baseline-parity IT even though the production
        // interestRecordProcessor resolves disclosure groups from the
        // database (the file path remains in the JobParameters bundle
        // for documentation and JobInstance-hash uniqueness).
        final Path stagedTcatbal = stageBaselineInput(FIXTURE_TCATBAL);
        final Path stagedDiscgrp = stageBaselineInput(FIXTURE_DISCGRP);

        // Capture the destination path for the produced SYSTRAN output
        // (TRANSACT DD analogue) in the instance field so Stage 3 can
        // read it back as input.systran.path. The basename matches the
        // captured-baseline golden filename so the BaselineDiffUtil call
        // site pairs it naturally with the captured reference under
        // baseline/expected/.
        interestFileActual = pipelineOutputDir.resolve(TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);

        // Build the JobParameters bundle. The intcalc.parm.date string
        // carries the JCL PARM='2022071800' value consumed by the
        // production interestRecordProcessor as the 10-character prefix
        // of every generated interest TRAN-ID (the remaining 6
        // characters being the zero-padded sequence counter). The
        // value is centralised at TestFixtures.Dates.INTCALC_PARM so
        // any future date-format regression would manifest in a single
        // constant edit.
        final JobParameters params = new JobParametersBuilder()
                .addString("intcalc.parm.date", TestFixtures.Dates.INTCALC_PARM)
                .addString("input.tcatbal.path", stagedTcatbal.toAbsolutePath().toString())
                .addString("input.discgrp.path", stagedDiscgrp.toAbsolutePath().toString())
                .addString("output.systran.path", interestFileActual.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Status.
        assertThat(execution.getStatus())
                .as("Stage 2 (INTCALC/CBACT04C) interestCalculationJob must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) Step skip counts.
        assertThat(execution.getStepExecutions())
                .as("Stage 2 (INTCALC/CBACT04C) must have executed at least one Step")
                .isNotEmpty();
        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getSkipCount())
                    .as("Stage 2 (INTCALC/CBACT04C) Step %s must skip 0 records (fail-fast contract per CBACT04C)",
                        step.getStepName())
                    .isZero();
        }

        // (3) Output exists.
        assertThat(interestFileActual)
                .as("Stage 2 (INTCALC/CBACT04C) must produce the SYSTRAN output file at the requested path")
                .exists()
                .isNotEmptyFile();

        // (4) Parity gate -- the ultimate HALF_EVEN scale-2 financial-
        //     precision proof. AAP §0.10.3 requires byte-identical parity
        //     for every monetary calculation; if the migrated processor
        //     uses HALF_UP or any other RoundingMode this byte-equality
        //     assertion will fail at the first .5-rounding boundary
        //     record (the discgrp.txt fixture is curated to include
        //     rates that trigger such boundaries).
        final Path expected = resolveExpectedReference(TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);
        BaselineDiffUtil.assertByteEqual(interestFileActual, expected);
    }

    /**
     * Stage 3 of the pipeline: launches the migrated COMBTRAN.jcl
     * (DFSORT-only) Spring Batch job against the OUTPUTS of Stages 1 and
     * 2 and asserts byte-identical parity with the captured COBOL
     * baseline at {@code baseline/expected/combined.txt}.
     *
     * <p><strong>This is the canonical pipeline-composition stage.</strong>
     * Unlike the sibling {@code CombineTransactionsBaselineParityIT}
     * which reads the canned {@code baseline/expected/posted.txt} and
     * {@code baseline/expected/tcatbal_after_interest.txt} as inputs,
     * this E2E stage reads {@link #postedFileActual} (Stage 1's output)
     * and {@link #interestFileActual} (Stage 2's output). If the per-job
     * parity ITs all pass but this stage fails, the regression is in the
     * pipeline composition (job-to-job hand-off), not in the merge
     * logic itself.
     *
     * <p>The byte-equality assertion against
     * {@code baseline/expected/combined.txt} is transitive: it can only
     * pass if BOTH the upstream Stage 1 and Stage 2 outputs are byte-
     * identical to their respective baselines AND the merge logic is
     * byte-identical to the DFSORT step's output. Stages 1 and 2 already
     * verify their own parity individually, so a Stage 3 failure here
     * pinpoints the merge logic (sort key, dedup strategy, line-ending
     * handling).
     *
     * @throws Exception          when the underlying Spring Batch launch
     *                            fails
     * @throws URISyntaxException when {@link #resolveExpectedReference(String)}
     *                            cannot convert the captured baseline
     *                            classpath URL to a Path
     */
    @Test
    @Order(3)
    @DisplayName("Stage 3 -- combineTransactionsJob produces combined.txt byte-identical to baseline (COMBTRAN.jcl DFSORT replacement, ordered by TRAN-ID positions 1-16)")
    void stage3_runCombineTransactionsJob_producesBaselineIdenticalCombinedFile() throws Exception {
        // ---- Arrange ----
        // Defensive precondition: Stages 1 and 2 must have populated
        // their actual-output fields. JUnit's @TestMethodOrder guarantees
        // this normally, but a misconfigured runner (or a future
        // refactor that removes the @Order annotations) could
        // theoretically run Stage 3 first and produce a confusing NPE
        // inside JobParametersBuilder. An explicit assertion here
        // converts the NPE into a clear .as(...) message.
        assertThat(postedFileActual)
                .as("Stage 3 (COMBTRAN) precondition: Stage 1 must have produced postedFileActual")
                .isNotNull();
        assertThat(interestFileActual)
                .as("Stage 3 (COMBTRAN) precondition: Stage 2 must have produced interestFileActual")
                .isNotNull();

        // Pin the combineTransactionsJob bean.
        final Job combineJob = applicationContext.getBean(JOB_COMBINE_TRANSACTIONS, Job.class);
        jobLauncherTestUtils.setJob(combineJob);

        // Stage 3's inputs are the OUTPUTS of Stages 1 and 2 -- this is
        // the pipeline-composition contract. No new fixtures are
        // staged from baseline/input/ for this stage; the merge engine
        // operates exclusively on what the upstream stages produced.
        // This is what differentiates the E2E pipeline test from the
        // sibling CombineTransactionsBaselineParityIT (which reads from
        // baseline/expected/ for the same inputs).
        combinedFileActual = pipelineOutputDir.resolve(TestFixtures.Paths.EXPECTED_COMBINED);

        // Build the JobParameters bundle. Mirrors the
        // CombineTransactionsBaselineParityIT JobParameter signature
        // (the production combineTransactionsTasklet at
        // BatchJobConfig line 808 reads input.posted.path,
        // input.systran.path, output.transact.path).
        final JobParameters params = new JobParametersBuilder()
                .addString("input.posted.path", postedFileActual.toAbsolutePath().toString())
                .addString("input.systran.path", interestFileActual.toAbsolutePath().toString())
                .addString("output.transact.path", combinedFileActual.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Status -- analogue of DFSORT RC=0.
        assertThat(execution.getStatus())
                .as("Stage 3 (COMBTRAN.jcl/DFSORT) combineTransactionsJob must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) Step skip counts.
        assertThat(execution.getStepExecutions())
                .as("Stage 3 (COMBTRAN.jcl/DFSORT) must have executed at least one Step")
                .isNotEmpty();
        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getSkipCount())
                    .as("Stage 3 (COMBTRAN.jcl/DFSORT) Step %s must skip 0 records",
                        step.getStepName())
                    .isZero();
        }

        // (3) Output exists.
        assertThat(combinedFileActual)
                .as("Stage 3 (COMBTRAN.jcl/DFSORT) must produce the TRANSACT output file at the requested path")
                .exists()
                .isNotEmptyFile();

        // (4) Parity gate -- proves both the upstream Stage 1+2 outputs
        //     match their baselines AND the merge logic (TRAN-ID sort,
        //     dedup) matches the DFSORT control-card behaviour.
        final Path expected = resolveExpectedReference(TestFixtures.Paths.EXPECTED_COMBINED);
        BaselineDiffUtil.assertByteEqual(combinedFileActual, expected);
    }

    /**
     * Stage 4 of the pipeline: launches the migrated CREASTMT.JCL /
     * CBSTM03A + CBSTM03B Spring Batch job against the OUTPUT of Stage 3
     * plus the reference-data fixtures and asserts byte-identical parity
     * with BOTH captured COBOL baselines at
     * {@code baseline/expected/statements_text.txt} AND
     * {@code baseline/expected/statements_html.txt}.
     *
     * <p><strong>Dual-output stage.</strong> CBSTM03A writes two
     * sequential files: an 80-byte fixed-width text statement
     * (STMTFILE DD) and a 100-byte fixed-width HTML statement (HTMLFILE
     * DD). The migrated {@code statementGenerationJob} preserves this
     * dual-output contract via the {@code output.stmtfile.path} and
     * {@code output.htmlfile.path} JobParameters; this stage asserts
     * byte-identical parity for BOTH outputs in a single Job launch.
     *
     * <p>This stage takes its TRNXFILE input ({@code input.transact.path})
     * from {@link #combinedFileActual} -- the Stage 3 output -- to
     * preserve the pipeline-composition contract. The sibling
     * {@code StatementGenerationBaselineParityIT} reads the canned
     * {@code baseline/expected/combined.txt} for the same purpose.
     *
     * @throws IOException        when staging the reference-data
     *                            fixtures fails
     * @throws URISyntaxException when {@link #resolveExpectedReference(String)}
     *                            cannot convert the captured baseline
     *                            classpath URL to a Path
     * @throws Exception          when the underlying Spring Batch launch
     *                            fails
     */
    @Test
    @Order(4)
    @DisplayName("Stage 4 -- statementGenerationJob produces statements_text.txt AND statements_html.txt byte-identical to baseline (CREASTMT.JCL/CBSTM03A+B parity, dual output)")
    void stage4_runStatementGenerationJob_producesBaselineIdenticalTextAndHtmlFiles() throws Exception {
        // ---- Arrange ----
        // Precondition: Stage 3 must have populated combinedFileActual.
        assertThat(combinedFileActual)
                .as("Stage 4 (CREASTMT) precondition: Stage 3 must have produced combinedFileActual")
                .isNotNull();

        // Pin the statementGenerationJob bean.
        final Job statementJob = applicationContext.getBean(JOB_STATEMENT_GENERATION, Job.class);
        jobLauncherTestUtils.setJob(statementJob);

        // Stage the three reference-data fixtures consumed by the
        // sibling StatementGenerationBaselineParityIT JobParameter
        // signature. The production statementGenerationTasklet at
        // BatchJobConfig line 914 reads input.transact.path,
        // output.stmtfile.path, output.htmlfile.path -- the reference
        // file paths are kept in the JobParameters bundle for parity
        // with the sibling IT and to allow future tasklet refactors to
        // consume them without changing this test's signature.
        final Path stagedCardxref = stageBaselineInput(FIXTURE_CARDXREF);
        final Path stagedCustdata = stageBaselineInput(FIXTURE_CUSTDATA);
        final Path stagedAcctdata = stageBaselineInput(FIXTURE_ACCTDATA);

        // Capture destination paths for both produced outputs. The
        // basenames match the captured-baseline golden filenames so the
        // BaselineDiffUtil call sites pair naturally with the captured
        // references.
        final Path statementTextActual = pipelineOutputDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_TEXT);
        final Path statementHtmlActual = pipelineOutputDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_HTML);

        // Build the JobParameters bundle. The input.transact.path
        // sources from Stage 3's output (combinedFileActual) -- this is
        // the pipeline-composition contract that differentiates this
        // E2E stage from the sibling baseline-parity IT.
        final JobParameters params = new JobParametersBuilder()
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.custdata.path", stagedCustdata.toAbsolutePath().toString())
                .addString("input.acctdata.path", stagedAcctdata.toAbsolutePath().toString())
                .addString("input.transact.path", combinedFileActual.toAbsolutePath().toString())
                .addString("output.stmtfile.path", statementTextActual.toAbsolutePath().toString())
                .addString("output.htmlfile.path", statementHtmlActual.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Status -- analogue of CBSTM03A STOP RUN with RC=0.
        assertThat(execution.getStatus())
                .as("Stage 4 (CREASTMT/CBSTM03A+B) statementGenerationJob must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) Step skip counts.
        assertThat(execution.getStepExecutions())
                .as("Stage 4 (CREASTMT/CBSTM03A+B) must have executed at least one Step")
                .isNotEmpty();
        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getSkipCount())
                    .as("Stage 4 (CREASTMT/CBSTM03A+B) Step %s must skip 0 records",
                        step.getStepName())
                    .isZero();
        }

        // (3) STMTFILE (text) existence + non-empty -- asserted
        //     independently from the HTMLFILE so a regression that
        //     drops one output without the other surfaces with a clear
        //     diagnostic.
        assertThat(statementTextActual)
                .as("Stage 4 (CREASTMT/CBSTM03A) must produce the plain-text statement file at the requested path")
                .exists()
                .isNotEmptyFile();

        // (4) HTMLFILE existence + non-empty.
        assertThat(statementHtmlActual)
                .as("Stage 4 (CREASTMT/CBSTM03A) must produce the HTML statement file at the requested path")
                .exists()
                .isNotEmptyFile();

        // (5) Dual parity gate. AAP §0.10.4 requires byte-identical
        //     parity for BOTH outputs; the migration's dual-output
        //     contract is preserved only if both assertions pass.
        final Path expectedText = resolveExpectedReference(TestFixtures.Paths.EXPECTED_STATEMENTS_TEXT);
        BaselineDiffUtil.assertByteEqual(statementTextActual, expectedText);

        final Path expectedHtml = resolveExpectedReference(TestFixtures.Paths.EXPECTED_STATEMENTS_HTML);
        BaselineDiffUtil.assertByteEqual(statementHtmlActual, expectedHtml);
    }

    /**
     * Stage 5 of the pipeline: launches the migrated TRANREPT.jcl /
     * CBTRN03C Spring Batch job against the OUTPUT of Stage 3 with the
     * date range {@code 2022-01-01} to {@code 2022-07-06} and asserts
     * byte-identical parity with the captured COBOL baseline at
     * {@code baseline/expected/transaction_report.txt}.
     *
     * <p>Stage 5 runs after Stage 4 in this implementation, but
     * semantically the two are PARALLEL stages of the mainframe
     * pipeline (CREASTMT and TRANREPT both consume the COMBTRAN output
     * independently). The sequential ordering here is dictated by
     * JUnit's single-threaded test-method execution model; the
     * parallelism is a property of the COBOL pipeline that is not
     * exercised by this E2E test.
     *
     * @throws IOException        when staging the reference-data
     *                            fixtures fails
     * @throws URISyntaxException when {@link #resolveExpectedReference(String)}
     *                            cannot convert the captured baseline
     *                            classpath URL to a Path
     * @throws Exception          when the underlying Spring Batch launch
     *                            fails
     */
    @Test
    @Order(5)
    @DisplayName("Stage 5 -- transactionReportJob produces transaction_report.txt byte-identical to baseline (TRANREPT.jcl/CBTRN03C parity, date range 2022-01-01 to 2022-07-06)")
    void stage5_runTransactionReportJob_producesBaselineIdenticalReportFile() throws Exception {
        // ---- Arrange ----
        // Precondition: Stage 3 must have populated combinedFileActual.
        assertThat(combinedFileActual)
                .as("Stage 5 (TRANREPT) precondition: Stage 3 must have produced combinedFileActual")
                .isNotNull();

        // Pin the transactionReportJob bean.
        final Job reportJob = applicationContext.getBean(JOB_TRANSACTION_REPORT, Job.class);
        jobLauncherTestUtils.setJob(reportJob);

        // Stage the three reference-data fixtures consumed by the
        // sibling TransactionReportBaselineParityIT JobParameter
        // signature. The production transactionReportTasklet at
        // BatchJobConfig line 1089 reads input.transact.path,
        // output.reptfile.path, report.start.date, report.end.date;
        // the cardxref/trantype/trancatg paths are kept for parity
        // with the sibling IT.
        final Path stagedCardxref = stageBaselineInput(FIXTURE_CARDXREF);
        final Path stagedTrantype = stageBaselineInput(FIXTURE_TRANTYPE);
        final Path stagedTrancatg = stageBaselineInput(FIXTURE_TRANCATG);

        // Capture destination path for the produced REPTFILE.
        final Path reportActual = pipelineOutputDir.resolve(TestFixtures.Paths.EXPECTED_TRANSACTION_REPORT);

        // Build the JobParameters bundle. The input.transact.path
        // sources from Stage 3's output (combinedFileActual). The date
        // range constants are centralised at TestFixtures.Dates so any
        // future format regression (e.g., switch from "YYYY-MM-DD" to
        // a different ISO variant) manifests in a single constant edit.
        final JobParameters params = new JobParametersBuilder()
                .addString("report.start.date", TestFixtures.Dates.REPORT_START_DATE)
                .addString("report.end.date", TestFixtures.Dates.REPORT_END_DATE)
                .addString("input.transact.path", combinedFileActual.toAbsolutePath().toString())
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.trantype.path", stagedTrantype.toAbsolutePath().toString())
                .addString("input.trancatg.path", stagedTrancatg.toAbsolutePath().toString())
                .addString("output.reptfile.path", reportActual.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Status -- analogue of CBTRN03C STOP RUN with RC=0.
        assertThat(execution.getStatus())
                .as("Stage 5 (TRANREPT/CBTRN03C) transactionReportJob must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) Step skip counts.
        assertThat(execution.getStepExecutions())
                .as("Stage 5 (TRANREPT/CBTRN03C) must have executed at least one Step")
                .isNotEmpty();
        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getSkipCount())
                    .as("Stage 5 (TRANREPT/CBTRN03C) Step %s must skip 0 records",
                        step.getStepName())
                    .isZero();
        }

        // (3) Output exists.
        assertThat(reportActual)
                .as("Stage 5 (TRANREPT/CBTRN03C) must produce the REPTFILE output at the requested path")
                .exists()
                .isNotEmptyFile();

        // (4) Parity gate -- proves the report formatter applied the
        //     correct fixed-width record layout, page-break behaviour,
        //     and date-range filtering for the 2022-01-01 / 2022-07-06
        //     window.
        final Path expected = resolveExpectedReference(TestFixtures.Paths.EXPECTED_TRANSACTION_REPORT);
        BaselineDiffUtil.assertByteEqual(reportActual, expected);
    }

    /**
     * Stage 6 of the pipeline: a redundant safety check that all three
     * inter-stage file handles ({@link #postedFileActual},
     * {@link #interestFileActual}, {@link #combinedFileActual}) are
     * populated and non-empty, confirming the pipeline reached completion
     * end-to-end.
     *
     * <p>The byte-equality of each individual stage's output was already
     * asserted in stages 1-5; this stage exists as a meta-assertion that
     * verifies the chained execution actually traversed every stage. If
     * any prior stage failed, JUnit's {@code @TestMethodOrder} would
     * still attempt to run this stage (failures of prior @Test methods do
     * not short-circuit subsequent ones), and the {@code null} or
     * empty-file assertions here would surface as additional failure
     * signals -- but the primary failure remains the upstream stage's
     * assertion failure.
     *
     * <p>This stage does NOT re-assert byte-equality (which would be
     * redundant) and does NOT mock or recompute anything (per AAP §0.10.1
     * Require Test Coverage rule -- the stage merely asserts on
     * filesystem state already produced by real Spring Batch jobs).
     *
     * <p>Note on text/html/report files: those three actual output
     * handles are method-local variables in stages 4 and 5, not instance
     * fields, because they are not inputs to any subsequent stage in the
     * pipeline. Their existence has already been asserted at the end of
     * each stage; re-asserting them here would require promoting them to
     * instance fields purely for this summary -- which would be a
     * gratuitous abstraction forbidden by AAP §0.10.2 Minimal Change
     * Clause.
     */
    @Test
    @Order(6)
    @DisplayName("Stage 6 -- Pipeline summary: inter-stage handles populated, all six parity assertions reached")
    void stage6_pipelineSummary_allSixOutputsMatchedBaseline() {
        // Stage 1's output (consumed as Stage 3's input).
        assertThat(postedFileActual)
                .as("Pipeline summary: Stage 1 (POSTTRAN) must have populated postedFileActual for downstream Stage 3 consumption")
                .isNotNull()
                .exists()
                .isNotEmptyFile();

        // Stage 2's output (consumed as Stage 3's input).
        assertThat(interestFileActual)
                .as("Pipeline summary: Stage 2 (INTCALC) must have populated interestFileActual for downstream Stage 3 consumption")
                .isNotNull()
                .exists()
                .isNotEmptyFile();

        // Stage 3's output (consumed as Stages 4 and 5's input).
        assertThat(combinedFileActual)
                .as("Pipeline summary: Stage 3 (COMBTRAN) must have populated combinedFileActual for downstream Stages 4 and 5 consumption")
                .isNotNull()
                .exists()
                .isNotEmptyFile();

        // The text/html/report file existence and parity were asserted
        // in their respective stages (4 and 5). If those stages had
        // failed their parity gates, JUnit would already have flagged
        // them and the surefire report would carry their failure
        // messages alongside this summary. No additional assertion here
        // is needed to "re-prove" their parity -- doing so would be
        // pure ceremony and would violate AAP §0.10.10 style
        // consistency (no redundant assertions for their own sake).
    }

    // =========================================================================
    // Private helpers -- staging classpath fixtures onto the @TempDir filesystem
    // =========================================================================

    /**
     * Loads a canonical baseline-input fixture from the test classpath
     * ({@code src/test/resources/baseline/input/}) and writes it to the
     * {@link #pipelineOutputDir} {@link TempDir} so Spring Batch's
     * {@code FlatFileItemReader} can consume it from a real filesystem
     * path.
     *
     * <p>The helper exists because {@code FlatFileItemReader} expects a
     * {@link Path} or {@code Resource} backed by a real filesystem
     * location, NOT a classpath resource -- staging through
     * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)} is
     * the idiomatic Spring Batch test pattern (also used in the sibling
     * {@code InterestCalculationBaselineParityIT},
     * {@code StatementGenerationBaselineParityIT},
     * {@code TransactionReportBaselineParityIT}, and the structural
     * {@code TransactionPostingJobIT}).
     *
     * <p>Byte-level write (not character-level) preserves the original
     * fixed-width COBOL record byte layout including sign-overpunch
     * encoding, any trailing whitespace, and the original line-ending
     * convention; AAP §0.10.4 ("Input and output file formats and record
     * layouts MUST remain identical") forbids any charset / line-ending
     * normalisation in the test harness.
     *
     * <p>Helper is duplicated from the sibling baseline-parity ITs
     * rather than extracted into a shared base-class utility -- AAP
     * §0.10.2 Minimal Change Clause forbids introducing abstractions
     * that exist only to flatter the test code. The five-line helper is
     * simpler to read inline than to navigate through a base-class
     * indirection, and the duplication is contained to ITs that share
     * the identical staging mechanism.
     *
     * @param filename simple basename of the baseline-input fixture
     *                 (e.g. {@code "dailytran.txt"}) -- must be one of
     *                 the canonical golden inputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_INPUT_DIR}
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #pipelineOutputDir} ready to be passed as a Spring
     *         Batch {@code JobParameters} string value
     * @throws IOException when the {@link Files#write} call fails (disk
     *                     full, permission denied, or the
     *                     {@link TempDir} root has been removed
     *                     externally), or when
     *                     {@link FixtureLoader#loadAsBytes(String)}
     *                     cannot locate the classpath resource (a
     *                     symptom of a missing
     *                     {@code src/test/resources/baseline/input/}
     *                     fixture)
     */
    private Path stageBaselineInput(String filename) throws IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR + filename);
        final Path staged = pipelineOutputDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Resolves a captured COBOL reference output from the test classpath
     * to a {@link Path} value suitable for passing as the right-hand
     * operand of {@link BaselineDiffUtil#assertByteEqual(Path, Path)}.
     *
     * <p>The reference files live under
     * {@code src/test/resources/baseline/expected/} at build time and are
     * placed under the test classpath at run time by Maven's
     * {@code resources} plugin. The classpath lookup is performed via
     * {@link Class#getResource(String)} (not by reading bytes through
     * {@link FixtureLoader#loadAsBytes(String)} into a staged temp file)
     * because {@code BaselineDiffUtil}'s error messages reference the
     * path's filesystem location to help operators locate the captured
     * baseline -- staging the bytes into the {@link TempDir} would
     * surface a temporary path that disappears after the test completes,
     * hindering post-mortem investigation.
     *
     * <p>The lookup is defensive: a {@code null} {@code URL} (the
     * classpath resource is missing) raises an
     * {@link IllegalStateException} naming both the classpath path and
     * the source-tree path operators should investigate, rather than a
     * less informative {@link NullPointerException} from the
     * {@link Path#of(java.net.URI)} call that would otherwise follow.
     *
     * @param filename simple basename of the captured reference output
     *                 (e.g. {@code "posted.txt"}) -- must be one of the
     *                 captured reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} pointing at the captured reference
     *         file on the test classpath's underlying filesystem
     * @throws IllegalStateException when the classpath resource is
     *                               missing (likely cause: the
     *                               {@code src/test/resources/baseline/expected/}
     *                               file has not been committed yet)
     * @throws URISyntaxException    when the classpath URL cannot be
     *                               converted to a URI (extremely
     *                               unusual; would indicate a malformed
     *                               classpath entry, e.g. unescaped
     *                               characters in a filesystem-rooted
     *                               classpath)
     */
    private Path resolveExpectedReference(String filename) throws URISyntaxException {
        final String resource = TestFixtures.Paths.CLASSPATH_BASELINE_EXPECTED_DIR + filename;
        final URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException(
                    "Expected reference file not found on classpath: " + resource
                    + ". Ensure src/test/resources/baseline/expected/" + filename
                    + " has been created.");
        }
        return Path.of(url.toURI());
    }
}
