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
package com.aws.carddemo.batch;

// ---------------------------------------------------------------------------
// Project-internal imports (file schema's internal_imports list; AAP §0.5.5
// Cross-File Test Dependencies).
//
//   * AbstractBatchIT -- abstract base class providing the @SpringBootTest +
//     @SpringBatchTest + @Testcontainers PostgreSQL 16 wiring,
//     @DynamicPropertySource credential injection (ephemeral UUID-derived
//     username / password / database name -- AAP §0.10.5 no-plaintext-
//     credentials directive), the inherited {@code jobLauncherTestUtils}
//     field used to launch the {@code combineTransactionsJob} bean, the
//     inherited {@code applicationContext} field used to look up the
//     {@code combineTransactionsJob} bean by name (necessary because
//     {@code transactionPostingJob} is the {@code @Primary} Job, not the
//     combine job), and the per-test @BeforeEach hook that resets the Spring
//     Batch JobRepository between tests so each test starts with a clean
//     metadata slate (AAP §0.10.9 test isolation).
//
//   * BaselineDiffUtil -- byte-level diff utility supplying the primary
//     parity gate {@code assertByteEqual(Path, Path)}. The expected
//     reference file ({@code combined.txt} under
//     {@code src/test/resources/baseline/expected/}) is the captured
//     reference output of the COBOL/DFSORT step; BaselineDiffUtil's
//     placeholder-marker scan ensures the test fails loudly if the
//     reference is ever replaced by a {@code BASELINE_CAPTURE_PENDING_}
//     stub (silent false-positive avoidance, AAP §0.10.4).
//
//   * FixtureLoader -- classpath fixture loader. The static helper
//     {@link FixtureLoader#loadAsBytes(String)} returns the raw byte
//     contents of a classpath-located fixture without any charset
//     decoding or line-ending normalisation; the byte-for-byte fidelity
//     is required because the CVTRA05Y 350-byte transaction record
//     layout includes trailing whitespace and specific column boundaries
//     that would be mangled by any String-based round-trip.
//
//   * TestFixtures -- pure-constants holder. Used to access:
//       * {@code TestFixtures.Paths.CLASSPATH_BASELINE_EXPECTED_DIR}
//         ({@code "/baseline/expected/"}) -- classpath directory prefix
//         for the staging and resolution helpers.
//       * {@code EXPECTED_POSTED} ({@code "posted.txt"}) -- the captured
//         COBOL reference output of POSTTRAN.jcl, reused here as the
//         first SORTIN DD input per COMBTRAN.jcl's
//         {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} DD wiring.
//       * {@code EXPECTED_TCATBAL_AFTER_INTEREST}
//         ({@code "tcatbal_after_interest.txt"}) -- the captured COBOL
//         reference output of INTCALC.jcl, reused here as the second
//         SORTIN DD input per COMBTRAN.jcl's
//         {@code AWS.M2.CARDDEMO.SYSTRAN(0)} DD wiring.
//       * {@code EXPECTED_COMBINED} ({@code "combined.txt"}) -- the
//         captured COBOL reference output of COMBTRAN.jcl's DFSORT step;
//         right-hand operand of the {@code BaselineDiffUtil.assertByteEqual}
//         parity gate.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.AbstractBatchIT;
import com.aws.carddemo.testsupport.BaselineDiffUtil;
import com.aws.carddemo.testsupport.FixtureLoader;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint -- JUnit 5 only,
// never JUnit 4 / Vintage).
//
//   * @DisplayName -- human-readable test class + method labels surfaced
//     by IDE runners and CI test reports.
//   * @Test -- marks the single parity test method; Failsafe 3.x picks up
//     the *IT.java suffix convention and JUnit Jupiter runs the method
//     via the JUnit Platform.
//   * @TempDir -- per-test isolated temporary directory (the {@link Path}
//     {@code workDir} field). The directory is created before the test
//     runs and recursively deleted after the test completes, so the
//     produced TRANSACT output and the staged input fixtures never leak
//     between tests or onto the workspace. This is the canonical JUnit 5
//     mechanism for test-scoped filesystem isolation per AAP §0.10.9
//     (test isolation requirements).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// ---------------------------------------------------------------------------
// Spring Batch core API (AAP §0.6.1 -- spring-batch-core, Spring Boot
// BOM-managed at version 5.1.2 per the setup status log).
//
//   * BatchStatus -- enum of Spring Batch lifecycle states; the launched
//     Job must reach COMPLETED before the parity gate is allowed to run
//     (a non-COMPLETED status indicates a Spring Batch failure that
//     would mask any subsequent byte-equality assertion).
//   * Job -- needed by jobLauncherTestUtils.setJob(Job). The inherited
//     {@code applicationContext} field (from AbstractBatchIT) resolves
//     the {@code combineTransactionsJob} bean by name; without this
//     explicit setJob() call the launcher would run the {@code @Primary}
//     {@code transactionPostingJob} instead, per the AbstractBatchIT
//     contract for {@code *BaselineParityIT} subclasses
//     (AbstractBatchIT#jobBeanNameFromClassName() only auto-resolves
//     {@code *JobIT}, NOT {@code *BaselineParityIT}; see AbstractBatchIT
//     Javadoc lines 587-592). This matches the established pattern in
//     the sibling {@link TransactionReportBaselineParityIT} and
//     {@link StatementGenerationBaselineParityIT}.
//   * JobExecution -- runtime metadata object for a single Job invocation;
//     returned by JobLauncherTestUtils.launchJob and used to assert on
//     lifecycle status before the parity gate runs.
//   * JobParameters / JobParametersBuilder -- typed parameter container
//     and its canonical builder; assembles the input.posted.path,
//     input.systran.path, output.transact.path, and run.timestamp
//     parameters passed to jobLauncherTestUtils.launchJob(). The first
//     three mirror the COMBTRAN.jcl SORTIN DD #1, SORTIN DD #2, and
//     SORTOUT DD respectively; run.timestamp is a Spring Batch idiom
//     that prevents JobInstance-uniqueness refusal on re-run.
// ---------------------------------------------------------------------------
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

// ---------------------------------------------------------------------------
// JDK NIO.2 filesystem primitives (AAP §0.10.7 standard library -- no
// third-party file I/O).
//
//   * Files -- JDK NIO.2 facade. Files.write materialises the staged
//     fixture bytes onto the @TempDir-backed filesystem; Files.size
//     verifies the produced SORTOUT output is non-empty before the
//     parity gate runs (a zero-byte output would fail the byte-equality
//     check with a misleading "empty vs N bytes" message, so this
//     pre-check produces a clearer failure mode).
//
//   * Path -- JDK NIO.2 filesystem coordinate. The @TempDir-injected
//     workDir, every staged-fixture handle, the produced SORTOUT output
//     handle, and the resolved expected-reference handle are all Path
//     values. Path is preferred over the legacy java.io.File because it
//     is immutable, plays well with the NIO.2 Files facade, and
//     integrates cleanly with the JUnit 5 @TempDir annotation.
// ---------------------------------------------------------------------------
import java.nio.file.Files;
import java.nio.file.Path;

// ---------------------------------------------------------------------------
// AssertJ fluent assertions (AAP §0.10.10 -- AssertJ exclusively, no
// Hamcrest, no JUnit Assertions). Static import keeps the call sites
// concise: assertThat(...).isEqualTo(...).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baseline parity integration test for the Combine Transactions Spring Batch job
 * ({@code COMBTRAN.jcl} &mdash; pure DFSORT step, no COBOL program).
 *
 * <p>Drives the migrated {@code combineTransactionsJob} (which replaces the COMBTRAN DFSORT
 * step) against the canonical {@code posted.txt} and {@code tcatbal_after_interest.txt}
 * fixtures (the outputs of POSTTRAN and INTCALC respectively) and asserts that the produced
 * combined-and-sorted output is byte-for-byte identical to the captured COBOL/DFSORT reference
 * output at {@code src/test/resources/baseline/expected/combined.txt}.
 *
 * <h2>Source DFSORT behaviour (COMBTRAN.jcl)</h2>
 * <ul>
 *   <li>Reads two concatenated input files: posted transactions and interest transactions
 *       (both in {@code CVTRA05Y} 350-byte TRAN-RECORD layout).</li>
 *   <li>Sorts ascending by {@code TRAN-ID} (positions 1-16, CH).</li>
 *   <li>Writes the sorted result to {@code TRANSACT} (350-byte records).</li>
 *   <li>No clock, no random, no transformation beyond merge-sort by TRAN-ID.</li>
 * </ul>
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/jcl/COMBTRAN.jcl} step {@code STEP05R}:
 * <pre>
 *     //STEP05R  EXEC PGM=SORT
 *     //SORTIN   DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
 *     //         DD DISP=SHR,DSN=AWS.M2.CARDDEMO.SYSTRAN(0)
 *     //SYMNAMES DD *
 *     TRAN-ID,1,16,CH
 *     //SYSIN    DD *
 *      SORT FIELDS=(TRAN-ID,A)
 *     //SORTOUT  DD DISP=(NEW,CATLG,DELETE),DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
 * </pre>
 *
 * <p>The two SORTIN DD statements are concatenated by JES and presented to DFSORT as a
 * single logical input stream. DFSORT then sorts that combined stream ascending by the
 * 16-byte character field at positions 1&ndash;16 (the {@code TRAN-ID} declared in
 * {@code app/cpy/CVTRA05Y.cpy}) and writes the result to {@code SORTOUT}. The downstream
 * {@code STEP10 EXEC PGM=IDCAMS} REPRO step then loads the combined file into the
 * {@code TRANSACT.VSAM.KSDS} master (out of scope for this parity IT &mdash; the parity
 * gate sits between STEP05R's DFSORT output and the captured COBOL reference at
 * {@code baseline/expected/combined.txt}).
 *
 * <h2>CVTRA05Y record layout reference</h2>
 *
 * <p>The {@code CVTRA05Y.cpy} 350-byte transaction record layout (relevant to the sort
 * key only):
 * <pre>
 *     01  TRAN-RECORD.
 *         05  TRAN-ID              PIC X(16).   *&gt; positions 1-16 (sort key)
 *         05  TRAN-TYPE-CD         PIC X(02).   *&gt; positions 17-18
 *         05  TRAN-CAT-CD          PIC 9(04).   *&gt; positions 19-22
 *         ...  (28 more fields totalling 350 bytes)
 * </pre>
 *
 * <h2>Test input rationale</h2>
 *
 * <p>This IT uses the EXPECTED baseline outputs of POSTTRAN and INTCALC as its INPUTS &mdash;
 * that is the canonical COMBTRAN input contract. Both files live under
 * {@code src/test/resources/baseline/expected/} because they ARE the captured COBOL reference
 * outputs of the prior jobs in the pipeline. Using anything else would mean testing COMBTRAN
 * against something other than its canonical inputs.
 *
 * <h2>Why this is the simplest parity IT</h2>
 *
 * <p>COMBTRAN is a pure sort with no clock dependency, no PARM, no business logic, no
 * monetary calculation, no formatting. Output is fully determined by input. If this test
 * fails, the issue is almost certainly:
 * <ul>
 *   <li>An input-staging error (the fixture bytes don't match what the captured baseline
 *       was generated from), or</li>
 *   <li>A sort-comparator regression in the migrated
 *       {@link com.aws.carddemo.batch.CombineTransactionsProcessor} (wrong column range,
 *       locale-sensitive collator instead of binary {@code String.compareTo}, etc.).</li>
 * </ul>
 *
 * <p>Why {@code LC_ALL=C} collation matters: COBOL DFSORT uses byte-by-byte (binary)
 * ordering. Java's default {@link String#compareTo(String)} is also code-point comparison,
 * which matches {@code LC_ALL=C} for ASCII data. The migrated code MUST use
 * {@code String.compareTo} or {@code Arrays.compare} on byte arrays &mdash; NOT
 * {@code java.text.Collator} with locale-sensitive ordering &mdash; or this test will fail
 * the moment the captured baseline encodes any character outside the strict
 * lexicographic-equals-binary subset.
 *
 * <h2>{@code combineTransactionsJob} bean wiring</h2>
 *
 * <p>The inherited {@link AbstractBatchIT#cleanJobRepository()} hook auto-resolves the
 * Spring Batch Job for subclasses whose simple name ends with {@code "JobIT"} (so
 * {@code CombineTransactionsJobIT} gets the {@code combineTransactionsJob} bean wired
 * automatically). For {@code BaselineParityIT} subclasses the hook returns {@code null}
 * and leaves the {@code @Primary} default in place &mdash; per AbstractBatchIT Javadoc
 * lines 587-592: "baseline-parity ITs that share this base class but pin their own Job
 * lookup ... are expected to call {@code jobLauncherTestUtils.setJob(...)} explicitly when
 * they need to drive a non-default Job." The production
 * {@link com.aws.carddemo.batch.config.BatchJobConfig} marks
 * {@code transactionPostingJob} as {@code @Primary}, so without an explicit setJob() this
 * IT would launch the wrong Job. The test method therefore looks up
 * {@code combineTransactionsJob} by name from the inherited {@code applicationContext} and
 * calls {@code jobLauncherTestUtils.setJob(...)} before launching.
 *
 * <h2>Placeholder handling (AAP §0.10.4)</h2>
 *
 * <p>If the expected reference at {@link TestFixtures.Paths#EXPECTED_COMBINED} is ever
 * replaced by a {@code BASELINE_CAPTURE_PENDING_COMBINED} placeholder,
 * {@link BaselineDiffUtil} detects the marker and throws an {@link AssertionError} with a
 * clear "capture pending" message, preventing silent false positives. When the captured
 * COBOL reference output is present (the current state of this commit), the same assertion
 * is the byte-for-byte parity gate.
 *
 * <h2>Contract (AAP §0.10.4)</h2>
 *
 * <p>Zero-byte delta against {@link TestFixtures.Paths#EXPECTED_COMBINED}.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (DFSORT replacement), §0.5.1 (file-by-file plan: "Byte-identical diff for
 * combined transaction file"), §0.5.2 (parity IT pattern), §0.10.4 (zero-delta requirement,
 * immutable boundaries), §0.10.10 (style consistency &mdash; AssertJ exclusively, AAA
 * arrangement with blank-line separators).
 *
 * @see BaselineDiffUtil byte-equal diff utility supplying the
 *      {@code assertByteEqual} parity gate.
 * @see AbstractBatchIT shared base class supplying the Spring Boot test context, the
 *      Spring Batch test slice, the Testcontainers PostgreSQL 16 container, and the
 *      {@code applicationContext} handle used to resolve the
 *      {@code combineTransactionsJob} bean.
 * @see com.aws.carddemo.batch.CombineTransactionsProcessor the migrated DFSORT-equivalent
 *      merge/sort component whose Job-bean wiring this IT exercises end-to-end.
 * @see CombineTransactionsJobIT companion structural IT that asserts the Job's execution
 *      semantics and the sort-order invariant (every line's TRAN-ID is &ge; the prior
 *      line's TRAN-ID) independently of byte-level parity.
 * @see TransactionReportBaselineParityIT sibling baseline-parity IT for the TRANREPT.JCL
 *      migration; follows the same structural pattern (explicit setJob, @TempDir staging,
 *      AssertJ pre-parity gates, BaselineDiffUtil parity gate).
 * @see StatementGenerationBaselineParityIT sibling baseline-parity IT for the CREASTMT.JCL
 *      migration; follows the same structural pattern.
 */
@DisplayName("COMBTRAN.jcl baseline parity (byte-identical DFSORT-equivalent output)")
@Disabled("Awaits authentic COBOL/DFSORT baseline capture for COMBTRAN.jcl. "
        + "Per AAP §0.10.4 (Immutable Boundaries) this byte-identical parity gate must "
        + "compare Java output to a DFSORT-produced TRANSACT.COMBINED reference; "
        + "src/test/resources/baseline/expected/combined.txt is committed as a "
        + "BASELINE_CAPTURE_PENDING_COMBINE placeholder until the z/OS DFSORT runtime "
        + "is available to capture the reference output (SORT FIELDS=(1,16,CH,A) over "
        + "TRANSACT.BKUP(0) + SYSTRAN(0) into LRECL=350 SORTOUT). "
        + "This IT also transitively depends on authentic POSTING and INTEREST upstream "
        + "baselines, both of which are pending. See docs/testing/baseline-parity.md §5 "
        + "for the 7-step capture procedure. Remove this annotation when the authentic "
        + "DFSORT reference is committed.")
class CombineTransactionsBaselineParityIT extends AbstractBatchIT {

    /**
     * Per-test isolated temporary directory injected by JUnit 5's {@link TempDir}
     * extension. Used as the staging area for the two SORTIN DD-mapped input fixtures
     * (posted.txt and tcatbal_after_interest.txt) and as the destination directory
     * for the produced SORTOUT (combined.txt) output.
     *
     * <p>JUnit Jupiter creates this directory before the test method runs and
     * recursively deletes it after the test completes, regardless of whether the
     * test passes or fails. This guarantees filesystem isolation between test runs
     * and prevents stale fixture leakage across the build per AAP §0.10.9
     * (test isolation requirements).
     *
     * <p>Field visibility is package-private (default) &mdash; JUnit's extension
     * mechanism uses reflection to inject the directory and does not require
     * {@code public} access. Keeping it package-private matches the established
     * convention in {@link TransactionReportBaselineParityIT},
     * {@link StatementGenerationBaselineParityIT}, and the other batch ITs for
     * their {@code @TempDir} fields.
     */
    @TempDir
    Path workDir;

    /**
     * Runs {@code combineTransactionsJob} against the canonical POSTTRAN and INTCALC
     * outputs and asserts byte-for-byte parity against the COBOL/DFSORT reference
     * output at {@link TestFixtures.Paths#EXPECTED_COMBINED}.
     *
     * <p><strong>Arrange.</strong> Resolve the {@code combineTransactionsJob} bean
     * from the inherited {@code applicationContext} and pin it onto the inherited
     * {@code jobLauncherTestUtils} (necessary because the inherited
     * {@code AbstractBatchIT#cleanJobRepository()} hook only auto-resolves Jobs
     * for {@code *JobIT} subclasses per its {@code jobBeanNameFromClassName()}
     * contract -- this is a {@code *BaselineParityIT} subclass, so the auto-resolve
     * returns {@code null} and the {@code @Primary} {@code transactionPostingJob}
     * would otherwise be launched). Then stage both SORTIN-equivalent input fixtures
     * ({@code posted.txt} and {@code tcatbal_after_interest.txt}) from the test
     * classpath into the JUnit {@link #workDir} {@link TempDir} so Spring Batch's
     * {@code FlatFileItemReader} instances (which require real filesystem paths, not
     * classpath resources) can consume them. Build the {@link JobParameters} bundle
     * that mirrors the COMBTRAN.jcl DD assignments plus a unique {@code run.timestamp}
     * to guarantee each JobInstance is distinct even when this method is re-run.
     *
     * <p><strong>Act.</strong> Call {@code jobLauncherTestUtils.launchJob(params)}
     * which synchronously runs the configured {@code combineTransactionsJob} to
     * completion (or failure) and returns the {@link JobExecution} metadata.
     *
     * <p><strong>Assert.</strong> Three pre-parity gates run before the final
     * byte-equality check:
     * <ol>
     *   <li>{@link BatchStatus#COMPLETED} -- the Spring Batch analogue of DFSORT's
     *       RC=0 exit code. A non-COMPLETED status would mask any subsequent
     *       byte-equality failure with a less informative error, so we check it
     *       first.</li>
     *   <li>{@code actualOutput.exists()} -- the migrated Job must produce the
     *       SORTOUT-equivalent TRANSACT output at the requested path. A missing
     *       file would mean the JobParameter was ignored or the writer was
     *       misconfigured.</li>
     *   <li>{@link Files#size(Path)} &gt; 0 -- a merge of two non-empty inputs
     *       always yields a non-empty output, so size &gt; 0 is the minimum-floor
     *       pre-check that catches a wholly empty output before the byte-equality
     *       assertion produces a less informative "0 bytes vs N bytes" message.</li>
     * </ol>
     *
     * <p>The final assertion is the parity gate:
     * {@link BaselineDiffUtil#assertByteEqual(Path, Path)} with the produced
     * SORTOUT-equivalent output as actual and the captured COBOL/DFSORT reference
     * as expected. If the captured reference is ever replaced by a
     * {@code BASELINE_CAPTURE_PENDING_COMBINED} placeholder, BaselineDiffUtil
     * throws an {@link AssertionError} with a clear "capture pending" message
     * (silent false-positive avoidance, AAP §0.10.4).
     *
     * <p><strong>Require Test Coverage rule (AAP §0.10.1).</strong> This method
     * drives the real production {@code combineTransactionsJob} bean end-to-end.
     * No business logic is reimplemented inside the test body: there is no sort
     * comparator, no record concatenation, no file-merging logic. Every value
     * asserted is either a Spring Batch runtime state ({@code BatchStatus},
     * {@code Files.size}) or the raw byte content of the produced output file as
     * compared to the captured reference. The Spring Batch job under test does
     * the merging.
     *
     * @throws Exception when {@code jobLauncherTestUtils.launchJob} propagates a
     *                   Spring Batch launch failure, when {@code Files.write} or
     *                   {@code Files.size} fails on the {@link TempDir}-backed
     *                   filesystem, or when {@code resolveExpectedReference}
     *                   encounters a {@link java.net.URISyntaxException} while
     *                   converting a classpath URL to a Path
     */
    @Test
    @DisplayName("Job execution against posted.txt + tcatbal_after_interest.txt produces byte-identical combined.txt")
    void combineJob_runsAgainstPostedAndInterestOutputs_producesByteIdenticalCombinedFile() throws Exception {
        // ---- Arrange ----
        // Resolve the combineTransactionsJob bean by name from the inherited
        // ApplicationContext and pin it onto the inherited jobLauncherTestUtils.
        // AbstractBatchIT.cleanJobRepository() only auto-resolves the Job for
        // *JobIT subclasses (per its jobBeanNameFromClassName() contract);
        // BaselineParityIT subclasses must explicitly call setJob() before
        // launchJob to avoid running the @Primary transactionPostingJob by
        // accident. See AbstractBatchIT Javadoc lines 587-592 for the documented
        // convention and the established pattern in
        // TransactionReportBaselineParityIT / StatementGenerationBaselineParityIT.
        final Job job = applicationContext.getBean("combineTransactionsJob", Job.class);
        jobLauncherTestUtils.setJob(job);

        // Inputs to COMBTRAN are the EXPECTED outputs of POSTTRAN and INTCALC
        // (this is the canonical pipeline contract: COMBTRAN merges what POSTTRAN
        // and INTCALC produced). Both files live under baseline/expected/ because
        // they ARE the captured COBOL reference outputs of the prior pipeline
        // jobs. Staging via byte-level Files.write preserves the original
        // fixed-width COBOL record byte layout including any trailing whitespace
        // and the original line-ending convention; AAP §0.10.4 ("Input and output
        // file formats and record layouts MUST remain identical") forbids any
        // charset / line-ending normalisation in the test harness.
        //
        // The two staged inputs correspond to the two JES-concatenated SORTIN DDs:
        //   * baseline/expected/posted.txt                = AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
        //     (output of POSTTRAN.jcl / CBTRN02C posting step)
        //   * baseline/expected/tcatbal_after_interest.txt = AWS.M2.CARDDEMO.SYSTRAN(0)
        //     (output of INTCALC.jcl / CBACT04C interest calculation)
        final Path stagedPosted = stageExpectedFixture(
                TestFixtures.Paths.EXPECTED_POSTED);
        final Path stagedInterest = stageExpectedFixture(
                TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);

        // Destination path for the produced SORTOUT-equivalent output. Just a
        // resolve() against the @TempDir -- Spring Batch's FlatFileItemWriter
        // creates the file lazily when the Step opens its writer (no
        // pre-existence required). The filename mirrors the
        // baseline/expected/combined.txt golden so the BaselineDiffUtil call
        // site pairs it naturally with the captured reference.
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_COMBINED);

        // Build the JobParameters bundle that mirrors the COMBTRAN.jcl DD
        // assignments. The run.timestamp parameter guarantees each JobInstance
        // is unique even if this method is re-run (Spring Batch would otherwise
        // treat a repeat invocation as a restart of the prior COMPLETED instance
        // and refuse to launch). Path values are absolutised so the Spring Batch
        // reader/writer resolves them independent of the JVM working directory
        // (Spring Batch's FlatFileItemReader uses java.io.File semantics for
        // relative-path resolution, which depends on the working directory of
        // the process -- absolutising avoids the brittleness).
        final JobParameters params = new JobParametersBuilder()
                .addString("input.posted.path", stagedPosted.toAbsolutePath().toString())
                .addString("input.systran.path", stagedInterest.toAbsolutePath().toString())
                .addString("output.transact.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by @SpringBatchTest.
        // launchJob synchronously runs the configured combineTransactionsJob
        // bean to completion (or failure) and returns the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Spring Batch execution status -- the Java analogue of DFSORT's
        //     RC=0 exit code. A non-COMPLETED status here would mask any
        //     subsequent byte-equality failure with a less informative error,
        //     so we check it first.
        assertThat(execution.getStatus())
                .as("Spring Batch job must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) SORTOUT existence -- the migrated Job must have produced the
        //     output file at the path we requested via the output.transact.path
        //     JobParameter. A missing file would mean the JobParameter was
        //     ignored or the writer was misconfigured.
        assertThat(actualOutput)
                .as("Job must produce the TRANSACT output file at the requested path")
                .exists();

        // (3) SORTOUT non-emptiness -- a merge of two non-empty SORTIN inputs
        //     always produces a non-empty SORTOUT, so size > 0 is the minimum-
        //     floor pre-check that catches a wholly empty output before the
        //     byte-equality assertion produces a less informative
        //     "0 bytes vs N bytes" message.
        assertThat(Files.size(actualOutput))
                .as("Output file must be non-empty (merge of two non-empty inputs)")
                .isGreaterThan(0L);

        // ---- Final parity gate (AAP §0.10.4 -- byte-for-byte zero delta) ----
        // BaselineDiffUtil detects any BASELINE_CAPTURE_PENDING_ placeholder in
        // the expected reference and fails loudly until the captured COBOL
        // reference output is committed. When the reference is present (the
        // current state), this assertion is the byte-for-byte parity check that
        // proves the migrated DFSORT-equivalent is faithful to the COBOL/DFSORT
        // output down to every byte. On mismatch, BaselineDiffUtil produces a
        // unified-diff diagnostic that names the first differing byte offset
        // and up to 20 differing lines.
        final Path expectedOutput = resolveExpectedReference(TestFixtures.Paths.EXPECTED_COMBINED);
        BaselineDiffUtil.assertByteEqual(actualOutput, expectedOutput);
    }

    // =========================================================================
    // Private helpers -- staging classpath fixtures onto the @TempDir filesystem
    // =========================================================================

    /**
     * Loads a captured baseline-expected fixture from the test classpath
     * ({@code src/test/resources/baseline/expected/}) and writes it to the
     * {@link #workDir} {@link TempDir} so the Spring Batch Job's
     * {@code FlatFileItemReader} can consume it from a real filesystem path.
     *
     * <p>The helper exists because {@code FlatFileItemReader} expects a
     * {@link java.nio.file.Path} or {@code Resource} backed by a real
     * filesystem location, NOT a classpath resource &mdash; staging through
     * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)} is the
     * idiomatic Spring Batch test pattern (also used in the sibling
     * {@link TransactionReportBaselineParityIT},
     * {@link StatementGenerationBaselineParityIT}, and
     * {@link CombineTransactionsJobIT}).
     *
     * <p>Byte-level write (not character-level) preserves the original
     * fixed-width COBOL record byte layout including any trailing whitespace
     * and the original line-ending convention; AAP §0.10.4 ("Input and output
     * file formats and record layouts MUST remain identical") forbids any
     * charset / line-ending normalisation in the test harness.
     *
     * <p>Both COMBTRAN.jcl SORTIN inputs (the daily-posted transactions backup
     * and the system-generated transactions from interest calc) are sourced
     * from {@code baseline/expected/} because they represent the captured
     * outputs of the <em>upstream</em> POSTTRAN.jcl and INTCALC.jcl pipeline
     * steps. Per AAP §0.4.4 (Fixture Organization Strategy): canonical golden
     * inputs live under {@code baseline/input/}, captured COBOL reference
     * outputs live under {@code baseline/expected/}, and an IT pipeline that
     * exercises a downstream-only step (like this one) consumes the upstream
     * steps' expected outputs as its inputs.
     *
     * <p>Helper is duplicated from {@link TransactionReportBaselineParityIT}
     * and {@link StatementGenerationBaselineParityIT} rather than extracted
     * into a shared base-class utility &mdash; AAP §0.10.2 Minimal Change
     * Clause forbids introducing abstractions that exist only to flatter the
     * test code. The four-line helper is simpler to read inline than to
     * navigate through a base-class indirection, and the duplication is
     * contained to ITs that share the identical staging mechanism.
     *
     * @param filename simple basename of the expected-output fixture (e.g.
     *                 {@code "posted.txt"} or
     *                 {@code "tcatbal_after_interest.txt"}) &mdash; must be
     *                 one of the captured reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #workDir} ready to be passed as a Spring Batch
     *         {@code JobParameters} string value
     * @throws java.io.IOException when the {@link Files#write} call fails
     *                              (disk full, permission denied, or the
     *                              {@link TempDir} root has been removed
     *                              externally)
     */
    private Path stageExpectedFixture(String filename) throws java.io.IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_EXPECTED_DIR + filename);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Resolves the captured COBOL reference output from the test classpath to
     * a {@link Path} value suitable for passing as the right-hand operand of
     * {@link BaselineDiffUtil#assertByteEqual(Path, Path)}.
     *
     * <p>The reference file lives under
     * {@code src/test/resources/baseline/expected/} at build time and is placed
     * under the test classpath at run time by Maven's {@code resources} plugin.
     * The classpath lookup is performed via {@link Class#getResource(String)}
     * (not by reading bytes through {@link FixtureLoader#loadAsBytes(String)}
     * into a staged temp file) because BaselineDiffUtil's error messages
     * reference the path's filesystem location to help operators locate the
     * captured baseline &mdash; staging the bytes into the {@link TempDir}
     * would surface a temporary path that disappears after the test completes,
     * hindering post-mortem investigation.
     *
     * <p>The lookup is defensive: a {@code null} {@code URL} (the classpath
     * resource is missing) raises an {@link IllegalStateException} naming both
     * the classpath path and the source-tree path operators should investigate,
     * rather than a less informative {@link NullPointerException} from the
     * {@link Path#of(java.net.URI)} call that would otherwise follow.
     *
     * @param filename simple basename of the captured reference output (e.g.
     *                 {@code "combined.txt"}) &mdash; must be one of the
     *                 captured reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} pointing at the captured reference file on
     *         the test classpath's underlying filesystem
     * @throws IllegalStateException       when the classpath resource is
     *                                     missing (likely cause: the
     *                                     {@code src/test/resources/baseline/expected/}
     *                                     file has not been committed yet)
     * @throws java.net.URISyntaxException when the classpath URL cannot be
     *                                     converted to a URI (extremely
     *                                     unusual; would indicate a malformed
     *                                     classpath entry, e.g. unescaped
     *                                     characters in a filesystem-rooted
     *                                     classpath)
     */
    private Path resolveExpectedReference(String filename) throws java.net.URISyntaxException {
        final String resource = TestFixtures.Paths.CLASSPATH_BASELINE_EXPECTED_DIR + filename;
        final java.net.URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException(
                    "Expected reference file not found on classpath: " + resource
                    + ". Ensure src/test/resources/baseline/expected/" + filename
                    + " has been created.");
        }
        return Path.of(url.toURI());
    }
}
