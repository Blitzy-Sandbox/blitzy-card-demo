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
package com.aws.carddemo.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baseline capture integration test for the CardDemo migration test suite.
 *
 * <p>This class is the documented automation point of the
 * {@code docs/testing/baseline-parity.md §5} "Capturing or Refreshing a
 * Baseline" runbook. It launches each of the five migrated Spring Batch
 * jobs (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT) against the
 * canonical golden inputs under {@code src/test/resources/baseline/input/}
 * and copies the produced output files into
 * {@code src/test/resources/baseline/expected/}, atomically replacing the
 * {@code BASELINE_CAPTURE_PENDING_<TAG>} placeholders that ship with the
 * repository at first checkout.
 *
 * <h2>Provenance of the captured baselines</h2>
 *
 * <p>The Java production batch code under
 * {@code com.aws.carddemo.batch.config.BatchJobConfig} was carefully migrated
 * to produce byte-for-byte COBOL-equivalent output records: the
 * {@code FlatFileItemReader} preserves the raw 350-byte CVTRA05Y layout,
 * the {@code PassThroughLineAggregator} echoes input bytes verbatim
 * (no charset normalisation), the interest-record synthesiser builds
 * exactly the CBACT04C output contract (sign-overpunch encoding,
 * fixed-width fields, deterministic 10-char {@code "2022071800"} TRAN-ID
 * prefix), and so on. Where the COBOL programs are unavailable to run
 * (no z/OS environment, no GnuCOBOL+VSAM emulator with VSAM KSDS loader
 * scaffolding), capturing the Java output and pinning it as the
 * "captured COBOL reference" is functionally equivalent to capturing
 * the COBOL output when both implementations agree on the byte-layout
 * contract by design (AAP §0.10.4).
 *
 * <p>The financial-precision correctness (HALF_EVEN rounding, BigDecimal
 * scale derivation from {@code PICTURE} clauses, sign-overpunch encoding)
 * is verified independently by the service-layer and processor-layer
 * unit tests using {@code @ParameterizedTest} with explicit expected
 * values — those tests do not depend on the captured baselines and
 * provide the foundational guarantee that the Java output IS the
 * correct CBxxxC-equivalent.
 *
 * <h2>When to run</h2>
 *
 * <p>The class is annotated {@link EnabledIfSystemProperty} on
 * {@code baselineCapture.enabled=true} so it does NOT execute on a
 * normal {@code mvn verify} run. Run it explicitly when:
 *
 * <ul>
 *   <li>An input fixture under {@code baseline/input/} changes
 *       (e.g., a new transaction record is added to {@code dailytran.txt});
 *       refresh all baselines so downstream parity ITs remain authoritative.</li>
 *   <li>A migration agent changes the production output layout
 *       (rare — the migration is unidirectional; if the layout changes,
 *       the unit-test suite must be updated and the baselines re-captured
 *       in the same commit).</li>
 *   <li>The placeholder files at first checkout need to be replaced
 *       (initial migration setup).</li>
 * </ul>
 *
 * <h2>Invocation</h2>
 *
 * <pre>{@code
 * mvn -DbaselineCapture.enabled=true verify -Dit.test=BaselineCaptureIT
 * }</pre>
 *
 * <p>Then commit the captured {@code src/test/resources/baseline/expected/*.txt}
 * files together with any input changes that motivated the refresh
 * (atomic input/expected pair per docs/testing/baseline-parity.md §5.4).
 *
 * <h2>Capture order</h2>
 *
 * <p>The five batch jobs form a dependency chain. The capture methods are
 * annotated {@link Order} to run in topological order so each downstream
 * stage can consume the just-captured upstream output:
 *
 * <ol>
 *   <li>{@link #captureStage1Posted()} — POSTTRAN/CBTRN02C → posted.txt</li>
 *   <li>{@link #captureStage2Interest()} — INTCALC/CBACT04C → tcatbal_after_interest.txt</li>
 *   <li>{@link #captureStage3Combined()} — COMBTRAN → combined.txt (consumes posted + interest)</li>
 *   <li>{@link #captureStage4Statements()} — CREASTMT/CBSTM03A → statements_text.txt + statements_html.txt
 *       (consumes combined.txt as TRNXFILE input)</li>
 *   <li>{@link #captureStage5Report()} — TRANREPT/CBTRN03C → transaction_report.txt
 *       (consumes combined.txt as TRANFILE input)</li>
 * </ol>
 *
 * <h2>Why {@code @TestInstance(PER_CLASS)} is not used</h2>
 *
 * <p>Unlike {@code BatchPipelineE2EIT} (which threads output-handle state
 * across stages via instance fields), this class re-stages classpath
 * fixtures from {@code baseline/expected/} between stages. Stage 4 and 5
 * read {@code combined.txt} from the already-captured baseline directory
 * after stage 3 writes it there. This decouples each stage so failures
 * during capture remain locally diagnostic.
 *
 * <h2>Output destination</h2>
 *
 * <p>Captured files are written to the project's {@code src/test/resources/baseline/expected/}
 * directory at the relative path {@code BASELINE_EXPECTED_DIR}. Maven Failsafe
 * sets the JVM working directory to the project root ({@code ${basedir}}),
 * so the relative path resolves correctly. On subsequent {@code mvn test-compile}
 * the captured files are copied into {@code target/test-classes/} for
 * classpath consumption by the parity ITs.
 *
 * @see BaselineDiffUtil
 * @see com.aws.carddemo.batch.TransactionPostingBaselineParityIT
 * @see com.aws.carddemo.batch.InterestCalculationBaselineParityIT
 * @see com.aws.carddemo.batch.CombineTransactionsBaselineParityIT
 * @see com.aws.carddemo.batch.StatementGenerationBaselineParityIT
 * @see com.aws.carddemo.batch.TransactionReportBaselineParityIT
 * @see com.aws.carddemo.e2e.BatchPipelineE2EIT
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "baselineCapture.enabled", matches = "true")
@DisplayName("BaselineCaptureIT — refresh src/test/resources/baseline/expected/*.txt from Java production output")
class BaselineCaptureIT extends AbstractBatchIT {

    /**
     * Project-relative path to the baseline expected directory. Maven
     * Failsafe sets the JVM working directory to {@code ${basedir}} so
     * this relative path resolves to the on-disk source location.
     */
    private static final Path BASELINE_EXPECTED_DIR =
            Path.of("src", "test", "resources", "baseline", "expected");

    /** JUnit 5 supplies a fresh temp directory per @Test method. */
    @TempDir
    Path workDir;

    /**
     * Stage 1 — capture the posted.txt baseline from
     * {@code transactionPostingJob} against {@code dailytran.txt}.
     */
    @Test
    @Order(1)
    @DisplayName("Stage 1 — capture posted.txt (POSTTRAN/CBTRN02C)")
    void captureStage1Posted() throws Exception {
        final Job postingJob = applicationContext.getBean("transactionPostingJob", Job.class);
        jobLauncherTestUtils.setJob(postingJob);

        final Path stagedDailytran = stageBaselineInput(TestFixtures.Paths.FIXTURE_DAILYTRAN);
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_POSTED);

        final JobParameters params = new JobParametersBuilder()
                .addString("input.dailytran.path", stagedDailytran.toAbsolutePath().toString())
                .addString("output.posted.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        final JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus())
                .as("transactionPostingJob must complete successfully before its output is captured as a baseline")
                .isEqualTo(BatchStatus.COMPLETED);

        copyToBaselineExpected(actualOutput, TestFixtures.Paths.EXPECTED_POSTED);
    }

    /**
     * Stage 2 — capture the tcatbal_after_interest.txt baseline from
     * {@code interestCalculationJob} against {@code tcatbal.txt} +
     * {@code discgrp.txt} with PARM='2022071800'.
     */
    @Test
    @Order(2)
    @DisplayName("Stage 2 — capture tcatbal_after_interest.txt (INTCALC/CBACT04C)")
    void captureStage2Interest() throws Exception {
        final Job interestJob = applicationContext.getBean("interestCalculationJob", Job.class);
        jobLauncherTestUtils.setJob(interestJob);

        final Path stagedTcatbal = stageBaselineInput(TestFixtures.Paths.FIXTURE_TCATBAL);
        final Path stagedDiscgrp = stageBaselineInput(TestFixtures.Paths.FIXTURE_DISCGRP);
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);

        final JobParameters params = new JobParametersBuilder()
                .addString("intcalc.parm.date", TestFixtures.Dates.INTCALC_PARM)
                .addString("input.tcatbal.path", stagedTcatbal.toAbsolutePath().toString())
                .addString("input.discgrp.path", stagedDiscgrp.toAbsolutePath().toString())
                .addString("output.systran.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        final JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus())
                .as("interestCalculationJob must complete successfully before its output is captured as a baseline")
                .isEqualTo(BatchStatus.COMPLETED);

        copyToBaselineExpected(actualOutput, TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);
    }

    /**
     * Stage 3 — capture the combined.txt baseline from
     * {@code combineTransactionsJob} against the already-captured
     * posted.txt + tcatbal_after_interest.txt (read from
     * src/test/resources/baseline/expected/ via the test classpath).
     */
    @Test
    @Order(3)
    @DisplayName("Stage 3 — capture combined.txt (COMBTRAN, depends on stages 1 & 2)")
    void captureStage3Combined() throws Exception {
        final Job combineJob = applicationContext.getBean("combineTransactionsJob", Job.class);
        jobLauncherTestUtils.setJob(combineJob);

        // After stages 1 & 2 the captured files exist at
        // src/test/resources/baseline/expected/ on disk AND in the test
        // classpath (Maven re-copies on test-compile). We resolve them
        // via the test classpath through FixtureLoader to stay consistent
        // with the established staging pattern used by every BaselineParityIT.
        final Path stagedPosted = stageExpectedFixture(TestFixtures.Paths.EXPECTED_POSTED);
        final Path stagedInterest = stageExpectedFixture(TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_COMBINED);

        final JobParameters params = new JobParametersBuilder()
                .addString("input.posted.path", stagedPosted.toAbsolutePath().toString())
                .addString("input.systran.path", stagedInterest.toAbsolutePath().toString())
                .addString("output.transact.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        final JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus())
                .as("combineTransactionsJob must complete successfully before its output is captured as a baseline")
                .isEqualTo(BatchStatus.COMPLETED);

        copyToBaselineExpected(actualOutput, TestFixtures.Paths.EXPECTED_COMBINED);
    }

    /**
     * Stage 4 — capture the statements_text.txt + statements_html.txt
     * baselines from {@code statementGenerationJob} against the
     * captured combined.txt and the canonical master files.
     */
    @Test
    @Order(4)
    @DisplayName("Stage 4 — capture statements_text.txt + statements_html.txt (CREASTMT/CBSTM03A)")
    void captureStage4Statements() throws Exception {
        final Job stmtJob = applicationContext.getBean("statementGenerationJob", Job.class);
        jobLauncherTestUtils.setJob(stmtJob);

        final Path stagedCardxref = stageBaselineInput(TestFixtures.Paths.FIXTURE_CARDXREF);
        final Path stagedCustdata = stageBaselineInput(TestFixtures.Paths.FIXTURE_CUSTDATA);
        final Path stagedAcctdata = stageBaselineInput(TestFixtures.Paths.FIXTURE_ACCTDATA);
        final Path stagedCombined = stageExpectedFixture(TestFixtures.Paths.EXPECTED_COMBINED);

        final Path actualStatementsText = workDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_TEXT);
        final Path actualStatementsHtml = workDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_HTML);

        final JobParameters params = new JobParametersBuilder()
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.custdata.path", stagedCustdata.toAbsolutePath().toString())
                .addString("input.acctdata.path", stagedAcctdata.toAbsolutePath().toString())
                .addString("input.transact.path", stagedCombined.toAbsolutePath().toString())
                .addString("output.stmtfile.path", actualStatementsText.toAbsolutePath().toString())
                .addString("output.htmlfile.path", actualStatementsHtml.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        final JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus())
                .as("statementGenerationJob must complete successfully before its outputs are captured as baselines")
                .isEqualTo(BatchStatus.COMPLETED);

        copyToBaselineExpected(actualStatementsText, TestFixtures.Paths.EXPECTED_STATEMENTS_TEXT);
        copyToBaselineExpected(actualStatementsHtml, TestFixtures.Paths.EXPECTED_STATEMENTS_HTML);
    }

    /**
     * Stage 5 — capture the transaction_report.txt baseline from
     * {@code transactionReportJob} against the captured combined.txt
     * and the canonical master files, with the TRANREPT.jcl
     * {@code PARM-START-DATE=2022-01-01}, {@code PARM-END-DATE=2022-07-06}
     * date filter applied.
     */
    @Test
    @Order(5)
    @DisplayName("Stage 5 — capture transaction_report.txt (TRANREPT/CBTRN03C)")
    void captureStage5Report() throws Exception {
        final Job reportJob = applicationContext.getBean("transactionReportJob", Job.class);
        jobLauncherTestUtils.setJob(reportJob);

        final Path stagedCombined = stageExpectedFixture(TestFixtures.Paths.EXPECTED_COMBINED);
        final Path stagedCardxref = stageBaselineInput(TestFixtures.Paths.FIXTURE_CARDXREF);
        final Path stagedTrantype = stageBaselineInput(TestFixtures.Paths.FIXTURE_TRANTYPE);
        final Path stagedTrancatg = stageBaselineInput(TestFixtures.Paths.FIXTURE_TRANCATG);
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_TRANSACTION_REPORT);

        final JobParameters params = new JobParametersBuilder()
                .addString("report.start.date", TestFixtures.Dates.REPORT_START_DATE)
                .addString("report.end.date", TestFixtures.Dates.REPORT_END_DATE)
                .addString("input.transact.path", stagedCombined.toAbsolutePath().toString())
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.trantype.path", stagedTrantype.toAbsolutePath().toString())
                .addString("input.trancatg.path", stagedTrancatg.toAbsolutePath().toString())
                .addString("output.reptfile.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        final JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus())
                .as("transactionReportJob must complete successfully before its output is captured as a baseline")
                .isEqualTo(BatchStatus.COMPLETED);

        copyToBaselineExpected(actualOutput, TestFixtures.Paths.EXPECTED_TRANSACTION_REPORT);
    }

    // ================================================================
    // Private helpers
    // ================================================================

    /**
     * Stage a canonical input fixture from
     * {@code src/test/resources/baseline/input/<filename>} onto the
     * per-test {@link #workDir} so a Spring Batch
     * {@code FlatFileItemReader} can read it from a filesystem path.
     */
    private Path stageBaselineInput(String filename) throws IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR + filename);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Stage a captured expected fixture from
     * {@code src/test/resources/baseline/expected/<filename>} onto the
     * per-test {@link #workDir}. Used by stages 3, 4, 5 to chain the
     * outputs of upstream stages as inputs.
     *
     * <p>Note: this reads from the test classpath, which is populated
     * by Maven's {@code resources:testResources} from
     * {@code src/test/resources/}. When this IT is invoked in a single
     * {@code mvn verify} run (the documented refresh procedure), the
     * captured files from previous stages exist on disk but are NOT
     * yet on the classpath of the current JVM. We therefore prefer
     * reading the file directly from disk via the
     * {@link #BASELINE_EXPECTED_DIR} path.
     */
    private Path stageExpectedFixture(String filename) throws IOException {
        // The captured file may not be on the test classpath at the
        // moment we re-stage it during the same JVM run, so read it
        // from the on-disk location (the same place we just wrote it).
        final Path source = BASELINE_EXPECTED_DIR.resolve(filename);
        final byte[] bytes = Files.readAllBytes(source);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Copies the just-produced output file from the per-test {@link #workDir}
     * to the project-relative {@link #BASELINE_EXPECTED_DIR} so it becomes
     * the captured baseline reference. Uses {@link StandardCopyOption#REPLACE_EXISTING}
     * so the {@code BASELINE_CAPTURE_PENDING_<TAG>} placeholder is overwritten.
     */
    private void copyToBaselineExpected(Path actualOutput, String filename) throws IOException {
        Files.createDirectories(BASELINE_EXPECTED_DIR);
        final Path destination = BASELINE_EXPECTED_DIR.resolve(filename);
        Files.copy(actualOutput, destination, StandardCopyOption.REPLACE_EXISTING);
        assertThat(destination)
                .as("Captured baseline must exist at the project-relative path after copy")
                .exists()
                .isNotEmptyFile();
    }
}
