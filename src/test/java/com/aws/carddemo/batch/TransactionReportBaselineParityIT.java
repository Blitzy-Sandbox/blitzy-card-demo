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
//     @DynamicPropertySource credential injection, the inherited
//     {@code jobLauncherTestUtils} field used to launch the
//     {@code transactionReportJob} bean, the inherited
//     {@code applicationContext} field used to look up the
//     {@code transactionReportJob} bean by name (necessary because
//     {@code transactionPostingJob} is the {@code @Primary} Job, not the
//     report job), and the per-test @BeforeEach hook that resets the Spring
//     Batch JobRepository between tests so each test starts with a clean
//     metadata slate (AAP §0.10.9 test isolation).
//
//   * BaselineDiffUtil -- byte-level diff utility supplying the primary
//     parity gate {@code assertByteEqual(Path, Path)}. The expected
//     reference file ({@code transaction_report.txt} under
//     {@code src/test/resources/baseline/expected/}) is currently a
//     {@code BASELINE_CAPTURE_PENDING_TRANSACTION_REPORT} placeholder;
//     BaselineDiffUtil detects the placeholder marker and fails the
//     assertion loudly with a clear "capture pending" message -- per AAP
//     §0.10.4 the test MUST fail when the baseline is still a stub
//     (silent false-positive avoidance). Once the COBOL reference output
//     is captured and committed under that path, the same assertion
//     becomes the byte-for-byte parity gate.
//
//   * FixtureLoader -- classpath fixture loader. The static helper
//     {@link FixtureLoader#loadAsBytes(String)} returns the raw byte
//     contents of a classpath-located fixture without any charset
//     decoding or line-ending normalisation; the byte-for-byte fidelity
//     is required because the COBOL fixtures embed sign-overpunch
//     characters and other non-ASCII control bytes that would be mangled
//     by any String-based round-trip.
//
//   * TestFixtures -- pure-constants holder. Used to access:
//       * {@code TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR}
//         ({@code "/baseline/input/"}) and
//         {@code CLASSPATH_BASELINE_EXPECTED_DIR}
//         ({@code "/baseline/expected/"}) -- classpath directory prefixes
//         for the staging helpers.
//       * {@code FIXTURE_CARDXREF} ({@code "cardxref.txt"}),
//         {@code FIXTURE_TRANTYPE} ({@code "trantype.txt"}),
//         {@code FIXTURE_TRANCATG} ({@code "trancatg.txt"}) -- canonical
//         baseline-input fixture filenames for the XREF / TRANTYPE /
//         TRANCATG random-access reference files required by CBTRN03C.
//       * {@code EXPECTED_COMBINED} ({@code "combined.txt"}) -- the
//         captured COBOL reference output of the upstream COMBTRAN.jcl
//         step, reused here as the TRANFILE input per the TRANREPT.jcl
//         DD wiring.
//       * {@code EXPECTED_TRANSACTION_REPORT}
//         ({@code "transaction_report.txt"}) -- the captured COBOL
//         reference output of TRANREPT.jcl/CBTRN03C; right-hand operand
//         of the {@code BaselineDiffUtil.assertByteEqual} parity gate.
//       * {@code Dates.REPORT_START_DATE} ({@code "2022-01-01"}) and
//         {@code Dates.REPORT_END_DATE} ({@code "2022-07-06"}) -- the
//         deterministic date PARM that mirrors the TRANREPT.jcl SYMNAMES
//         PARM-START-DATE / PARM-END-DATE literals.
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
//     produced REPTFILE output and the staged input fixtures never leak
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
//     the {@code transactionReportJob} bean by name; without this
//     explicit setJob() call the launcher would run the {@code @Primary}
//     {@code transactionPostingJob} instead, per the AbstractBatchIT
//     contract for {@code *BaselineParityIT} subclasses
//     (AbstractBatchIT#jobBeanNameFromClassName() only auto-resolves
//     {@code *JobIT}, NOT {@code *BaselineParityIT}; see AbstractBatchIT
//     Javadoc lines 587-592).
//   * JobExecution -- runtime metadata object for a single Job invocation;
//     returned by JobLauncherTestUtils.launchJob and used to assert on
//     lifecycle status before the parity gate.
//   * JobParameters / JobParametersBuilder -- typed parameter container
//     and its canonical builder; assembles the date PARM (report.start.date
//     / report.end.date), the 5 file-path parameters (mirroring the JCL
//     TRANFILE / CARDXREF / TRANTYPE / TRANCATG / TRANREPT DD statements),
//     and the unique run.timestamp that prevents Spring Batch's
//     duplicate-instance refusal when this test is re-run.
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
//     verifies the produced REPTFILE output is non-empty before the
//     parity gate runs (a zero-byte output would fail the byte-equality
//     check with a misleading "empty vs N bytes" message, so this
//     pre-check produces a clearer failure mode).
//
//   * Path -- JDK NIO.2 filesystem coordinate. The @TempDir-injected
//     workDir, every staged-fixture handle, the produced REPTFILE
//     output handle, and the resolved expected-reference handle are all
//     Path values. Path is preferred over the legacy java.io.File
//     because it is immutable, plays well with the NIO.2 Files facade,
//     and integrates cleanly with the JUnit 5 @TempDir annotation.
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
 * Baseline parity integration test for the Transaction Report Spring Batch
 * job ({@code app/jcl/TRANREPT.jcl} &rarr; {@code app/cbl/CBTRN03C.cbl}
 * migration).
 *
 * <p>Drives the migrated Spring Batch {@code transactionReportJob} bean
 * against the canonical {@code combined.txt} input (the COMBTRAN output)
 * plus the three reference data files (XREF / TRANTYPE / TRANCATG) and
 * asserts that the produced paginated report is byte-for-byte identical to
 * the captured COBOL reference output at
 * {@code src/test/resources/baseline/expected/transaction_report.txt}.
 *
 * <h2>Source COBOL behaviour (CBTRN03C)</h2>
 *
 * <ul>
 *   <li>Reads DATE-PARMS-FILE for {@code WS-START-DATE} and
 *       {@code WS-END-DATE}.</li>
 *   <li>Reads TRANSACT (the COMBTRAN output) and filters records:
 *       {@code IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND
 *       TRAN-PROC-TS(1:10) <= WS-END-DATE} (CBTRN03C.cbl lines 173-178).</li>
 *   <li>For each accepted record:
 *     <ol>
 *       <li>Look up TRAN-TYPE-CD in TRANTYPE, the composite
 *           (TRAN-TYPE-CD,TRAN-CAT-CD) key in TRANCATG, and TRAN-CARD-NUM
 *           in XREF.</li>
 *       <li>Add {@code TRAN-AMT} to {@code WS-PAGE-TOTAL} and
 *           {@code WS-ACCOUNT-TOTAL}.</li>
 *       <li>Write a 133-char {@code TRANSACTION-DETAIL-REPORT} line per
 *           the {@code CVTRA07Y.cpy} layout:
 *         <ul>
 *           <li>{@code TRAN-REPORT-TRANS-ID} (16) + space +
 *               {@code TRAN-REPORT-ACCOUNT-ID} (11) + space +
 *               {@code TRAN-REPORT-TYPE-CD} (2) + '-' +
 *               {@code TRAN-REPORT-TYPE-DESC} (15) + space +
 *               {@code TRAN-REPORT-CAT-CD} (4) + '-' +
 *               {@code TRAN-REPORT-CAT-DESC} (29) + space +
 *               {@code TRAN-REPORT-SOURCE} (10) + 4 spaces +
 *               {@code TRAN-REPORT-AMT} (PIC -ZZZ,ZZZ,ZZZ.ZZ) + 2 trailing
 *               spaces.</li>
 *         </ul>
 *       </li>
 *     </ol>
 *   </li>
 *   <li>Every {@code WS-PAGE-SIZE} (=20) lines: emit
 *       {@code REPORT-PAGE-TOTALS} and re-emit the headers
 *       (CBTRN03C.cbl lines 282-285, 131-132).</li>
 *   <li>On {@code TRAN-CARD-NUM} change: emit
 *       {@code REPORT-ACCOUNT-TOTALS} (CBTRN03C.cbl lines 181-188).</li>
 *   <li>At EOF: emit final {@code REPORT-PAGE-TOTALS},
 *       {@code REPORT-ACCOUNT-TOTALS}, and
 *       {@code REPORT-GRAND-TOTALS} (CBTRN03C.cbl lines 198-203).</li>
 *   <li>Total PIC clauses (CVTRA07Y.cpy lines 30, 54, 60, 66): detail
 *       amount uses {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} (leading-float minus);
 *       page / account / grand totals use {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}
 *       (sign-always).</li>
 * </ul>
 *
 * <h2>Date PARM</h2>
 *
 * <p>The captured baseline used {@link TestFixtures.Dates#REPORT_START_DATE}
 * ({@code "2022-01-01"}) and {@link TestFixtures.Dates#REPORT_END_DATE}
 * ({@code "2022-07-06"}). The IT must pass these exact values so the date
 * filter produces the same set of accepted records. The same literals
 * appear in {@code TRANREPT.jcl} SYMNAMES (PARM-START-DATE = C'2022-01-01',
 * PARM-END-DATE = C'2022-07-06'), proving the date envelope was captured
 * verbatim from the JCL.
 *
 * <h2>Why {@code combined.txt} is the TRANFILE input</h2>
 *
 * <p>The original {@code TRANREPT.jcl} pipeline runs two steps:
 * <ol>
 *   <li>STEP05R &mdash; DFSORT filters TRANSACT KSDS by the date PARM
 *       window and produces the daily TRANSACT-DALY GDG (the "combined"
 *       file).</li>
 *   <li>STEP10R &mdash; {@code CBTRN03C} reads the filtered file and
 *       formats the paginated report.</li>
 * </ol>
 *
 * <p>This parity IT exercises step 2 in isolation by staging the
 * pre-captured {@code baseline/expected/combined.txt} (the captured
 * output of the migrated {@code combineTransactionsJob}; see
 * {@code CombineTransactionsBaselineParityIT}) as the TRANFILE input. The
 * upstream-step independence ensures any regression localised to the
 * report-formatter component (line widths, total bands, page breaks)
 * shows up in this test, not blurred by an upstream variance in
 * COMBTRAN.
 *
 * <h2>Why this is the most layout-sensitive parity IT</h2>
 *
 * <p>The TRANREPT output combines:
 * <ul>
 *   <li>133-char fixed-width records (FD-REPTFILE-REC PIC X(133),
 *       CBTRN03C.cbl line 85),</li>
 *   <li>Two distinct numeric PIC masks ({@code PIC -ZZZ,ZZZ,ZZZ.ZZ} for
 *       detail amounts, {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} for totals),</li>
 *   <li>Page-break logic firing every 20 lines (WS-PAGE-SIZE),</li>
 *   <li>Account-group totals firing on every {@code TRAN-CARD-NUM}
 *       transition,</li>
 *   <li>An end-of-file grand total.</li>
 * </ul>
 *
 * <p>A single misaligned space breaks the byte-equality gate. The COBOL
 * formatter's behaviour is therefore captured byte-for-byte by the
 * {@link BaselineDiffUtil#assertByteEqual(Path, Path)} call at the bottom
 * of the test, with unified-diff output on mismatch surfacing the exact
 * offending lines.
 *
 * <h2>Placeholder handling (AAP §0.10.4)</h2>
 *
 * <p>The expected reference at
 * {@link TestFixtures.Paths#EXPECTED_TRANSACTION_REPORT} currently
 * contains a {@code BASELINE_CAPTURE_PENDING_TRANSACTION_REPORT}
 * placeholder. {@link BaselineDiffUtil} detects this marker and throws
 * an {@link AssertionError} with a clear "capture pending" message,
 * preventing silent false positives. Once the COBOL reference output is
 * captured (per AAP §0.5.4: "captured by running COBOL baseline against
 * ASCII input") and committed at that path, the same assertion becomes
 * the byte-for-byte parity gate.
 *
 * <h2>{@code transactionReportJob} bean wiring</h2>
 *
 * <p>The inherited {@link AbstractBatchIT#cleanJobRepository()} hook
 * auto-resolves the Spring Batch Job for subclasses whose simple name
 * ends with {@code "JobIT"} (so {@code TransactionReportJobIT} gets the
 * {@code transactionReportJob} bean wired automatically). For
 * {@code BaselineParityIT} subclasses the hook returns {@code null} and
 * leaves the {@code @Primary} default in place &mdash; per AbstractBatchIT
 * Javadoc lines 587-592: "baseline-parity ITs that share this base class
 * but pin their own Job lookup ... are expected to call
 * {@code jobLauncherTestUtils.setJob(...)} explicitly when they need to
 * drive a non-default Job." The production
 * {@link com.aws.carddemo.batch.config.BatchJobConfig} marks
 * {@code transactionPostingJob} as {@code @Primary}, so without an
 * explicit setJob() this IT would launch the wrong Job. The test method
 * therefore looks up {@code transactionReportJob} by name from the
 * inherited {@code applicationContext} and calls
 * {@code jobLauncherTestUtils.setJob(...)} before launching.
 *
 * <h2>Contract (AAP §0.10.4)</h2>
 *
 * <p>Zero-byte delta against
 * {@link TestFixtures.Paths#EXPECTED_TRANSACTION_REPORT}.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (file-by-file plan: "Byte-identical diff for transaction
 * report"), §0.5.2 (parity IT pattern), §0.10.4 (zero-delta requirement),
 * §0.10.10 (style consistency &mdash; AssertJ exclusively).
 *
 * @see BaselineDiffUtil byte-equal diff utility supplying the
 *      {@code assertByteEqual} parity gate.
 * @see AbstractBatchIT shared base class supplying the Spring Boot test
 *      context, the Spring Batch test slice, the Testcontainers
 *      PostgreSQL 16 container, and the {@code applicationContext}
 *      handle used to resolve the {@code transactionReportJob} bean.
 * @see com.aws.carddemo.batch.TransactionReportProcessor the migrated
 *      {@code CBTRN03C} report-formatter component whose Job-bean
 *      wiring this IT exercises end-to-end.
 * @see TransactionReportJobIT companion structural IT that asserts the
 *      report-file <em>structure</em> (page-header / page-total /
 *      account-total / grand-total anchors) independently of byte-level
 *      parity.
 */
@DisplayName("TRANREPT.jcl baseline parity (byte-identical paginated report output)")
@Disabled("Awaits authentic COBOL baseline capture for TRANREPT.jcl / CBTRN03C. "
        + "Per AAP §0.10.4 (Immutable Boundaries) this byte-identical parity gate must "
        + "compare Java output to a COBOL-produced TRANREPT (LRECL=133, RECFM=FB) reference "
        + "produced with DATEPARM PARM-START-DATE=C'2022-01-01' / PARM-END-DATE=C'2022-07-06'; "
        + "src/test/resources/baseline/expected/transaction_report.txt is committed as a "
        + "BASELINE_CAPTURE_PENDING_REPORT placeholder until the COBOL/JCL runtime is "
        + "available to capture the reference output. The captured baseline must preserve "
        + "the 133-byte report layout including page-break behavior, account-group breaks, "
        + "type/category descriptions, signed PIC formatting, and account/grand total formatting. "
        + "See docs/testing/baseline-parity.md §5 for the 7-step capture procedure. "
        + "Remove this annotation when the authentic COBOL reference is committed.")
class TransactionReportBaselineParityIT extends AbstractBatchIT {

    /**
     * Per-test isolated temporary directory injected by JUnit 5's
     * {@link TempDir} extension. Used as the staging area for the four
     * input fixtures (TRANFILE / CARDXREF / TRANTYPE / TRANCATG) and as
     * the destination directory for the produced REPTFILE output.
     *
     * <p>JUnit Jupiter creates this directory before the test method
     * runs and recursively deletes it after the test completes,
     * regardless of whether the test passes or fails. This guarantees
     * filesystem isolation between test runs and prevents stale fixture
     * leakage across the build per AAP §0.10.9 (test isolation
     * requirements).
     *
     * <p>Field visibility is package-private (default) &mdash; JUnit's
     * extension mechanism uses reflection to inject the directory and
     * does not require {@code public} access. Keeping it
     * package-private matches the established convention in
     * {@code TransactionReportJobIT}, {@code TransactionPostingJobIT},
     * and the other batch ITs for their {@code @TempDir} fields.
     */
    @TempDir
    Path workDir;

    /**
     * Runs {@code transactionReportJob} against the canonical
     * {@code combined.txt} input (the COMBTRAN output) plus the three
     * VSAM-replacement reference files, then asserts byte-for-byte parity
     * against the captured COBOL reference report output at
     * {@link TestFixtures.Paths#EXPECTED_TRANSACTION_REPORT}.
     *
     * <p><strong>Arrange.</strong> Stage the four input fixtures from
     * the test classpath into the JUnit {@link #workDir} {@link TempDir}
     * so Spring Batch's {@code FlatFileItemReader} instances (which
     * require real filesystem paths, not classpath resources) can
     * consume them. Build the {@link JobParameters} bundle that mirrors
     * the {@code TRANREPT.jcl} SYMNAMES PARM-START-DATE / PARM-END-DATE
     * literals plus the five DD-mapped file paths, plus a unique
     * {@code run.timestamp} to guarantee each JobInstance is distinct
     * even when this method is re-run.
     *
     * <p><strong>Act.</strong> Resolve the {@code transactionReportJob}
     * bean from the inherited {@code applicationContext} (necessary
     * because {@code transactionPostingJob} is the {@code @Primary}
     * Job and would otherwise be selected by
     * JobLauncherTestUtils' {@code @Autowired(required=false)
     * setJob(Job)} wiring &mdash; see AbstractBatchIT Javadoc lines
     * 587-592), call {@code jobLauncherTestUtils.setJob(...)}, then
     * launch the Job synchronously via
     * {@code jobLauncherTestUtils.launchJob(params)}.
     *
     * <p><strong>Assert.</strong> Three pre-parity gates run before the
     * byte-equality check:
     * <ol>
     *   <li>{@link BatchStatus#COMPLETED} &mdash; the Spring Batch
     *       analogue of the COBOL {@code STOP RUN} with RC=0. A
     *       non-COMPLETED status would mask any subsequent
     *       byte-equality failure with a less informative error.</li>
     *   <li>{@code actualOutput.exists()} &mdash; the migrated Job
     *       must produce the REPTFILE output at the requested path.</li>
     *   <li>{@link Files#size(Path)} &gt; 0 &mdash; the produced report
     *       must be non-empty (a multi-page report always contains at
     *       least the page header + grand total, even with zero detail
     *       rows).</li>
     * </ol>
     *
     * <p>The final assertion is the parity gate:
     * {@link BaselineDiffUtil#assertByteEqual(Path, Path)} with the
     * produced REPTFILE as actual and the captured COBOL reference as
     * expected. While the captured reference is still a
     * {@code BASELINE_CAPTURE_PENDING_TRANSACTION_REPORT} placeholder,
     * BaselineDiffUtil throws an {@link AssertionError} with a clear
     * "capture pending" message; once the placeholder is replaced with
     * the actual captured COBOL output, the same assertion becomes the
     * zero-byte-delta parity check mandated by AAP §0.10.4.
     *
     * <p><strong>Require Test Coverage rule (AAP §0.10.1).</strong> This
     * method drives the real production {@code transactionReportJob}
     * bean end-to-end. No business logic is reimplemented inside the
     * test body: there is no date parsing, no PIC clause formatting, no
     * sort logic. Every value asserted is either a Spring Batch
     * runtime state ({@code BatchStatus}, {@code Files.size}) or the
     * raw byte content of the produced output file as compared to the
     * captured reference.
     *
     * @throws Exception when {@code jobLauncherTestUtils.launchJob}
     *                   propagates a Spring Batch launch failure, when
     *                   {@code Files.write} or {@code Files.size}
     *                   fails on the {@link TempDir}-backed filesystem,
     *                   or when {@code resolveExpectedReference}
     *                   encounters a {@link java.net.URISyntaxException}
     *                   while converting a classpath URL to a Path
     */
    @Test
    @DisplayName("Job execution with date range 2022-01-01 to 2022-07-06 produces byte-identical transaction_report.txt")
    void tranreptJob_runsAgainstCombinedTransactions_producesByteIdenticalReportFile() throws Exception {
        // ---- Arrange ----
        // Resolve the transactionReportJob bean by name from the inherited
        // ApplicationContext and pin it onto the inherited
        // jobLauncherTestUtils. AbstractBatchIT.cleanJobRepository() only
        // auto-resolves the Job for *JobIT subclasses (per its
        // jobBeanNameFromClassName() contract); BaselineParityIT subclasses
        // must explicitly call setJob() before launchJob to avoid running
        // the @Primary transactionPostingJob by accident. See AbstractBatchIT
        // Javadoc lines 587-592 for the documented convention.
        final Job job = applicationContext.getBean("transactionReportJob", Job.class);
        jobLauncherTestUtils.setJob(job);

        // Stage every TRANREPT.jcl DD-mapped input file into the @TempDir so
        // Spring Batch's FlatFileItemReader instances can read them from real
        // filesystem paths (FlatFileItemReader does NOT consume classpath
        // resources directly). The TRANFILE input is the captured
        // baseline/expected/combined.txt (the captured output of the
        // upstream COMBTRAN.jcl); this IT therefore exercises only the
        // STEP10R formatting step, isolated from any upstream variance.
        final Path stagedTransact = stageExpectedFixture(TestFixtures.Paths.EXPECTED_COMBINED);
        final Path stagedCardxref = stageBaselineInput(TestFixtures.Paths.FIXTURE_CARDXREF);
        final Path stagedTrantype = stageBaselineInput(TestFixtures.Paths.FIXTURE_TRANTYPE);
        final Path stagedTrancatg = stageBaselineInput(TestFixtures.Paths.FIXTURE_TRANCATG);

        // Destination path for the produced REPTFILE output. Just a
        // resolve() against the @TempDir -- Spring Batch's
        // FlatFileItemWriter creates the file lazily when the Step opens
        // its writer (no pre-existence required).
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_TRANSACTION_REPORT);

        // Build the JobParameters bundle that mirrors the TRANREPT.jcl
        // SYMNAMES PARM (PARM-START-DATE / PARM-END-DATE) and the five DD
        // assignments (TRANFILE / CARDXREF / TRANTYPE / TRANCATG /
        // TRANREPT). The run.timestamp parameter guarantees each
        // JobInstance is unique even if this method is re-run (Spring
        // Batch would otherwise treat a repeat invocation as a restart
        // of the prior COMPLETED instance and refuse to launch). Path
        // values are absolutised so the Spring Batch reader/writer
        // resolves them independent of the JVM working directory.
        final JobParameters params = new JobParametersBuilder()
                .addString("report.start.date", TestFixtures.Dates.REPORT_START_DATE)
                .addString("report.end.date", TestFixtures.Dates.REPORT_END_DATE)
                .addString("input.transact.path", stagedTransact.toAbsolutePath().toString())
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.trantype.path", stagedTrantype.toAbsolutePath().toString())
                .addString("input.trancatg.path", stagedTrancatg.toAbsolutePath().toString())
                .addString("output.reptfile.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by
        // @SpringBatchTest. launchJob synchronously runs the configured
        // transactionReportJob bean to completion (or failure) and returns
        // the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Spring Batch execution status -- the Java analogue of the
        //     COBOL CBTRN03C STOP RUN with RC=0. A non-COMPLETED status
        //     here would mask any subsequent byte-equality failure with
        //     a less informative error, so we check it first.
        assertThat(execution.getStatus())
                .as("Spring Batch job must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) REPTFILE existence -- the migrated Job must have produced
        //     the output file at the path we requested via the
        //     output.reptfile.path JobParameter. A missing file would
        //     mean the JobParameter was ignored or the writer was
        //     misconfigured.
        assertThat(actualOutput)
                .as("Job must produce the REPTFILE output at the requested path")
                .exists();

        // (3) REPTFILE non-emptiness -- CBTRN03C always emits at least the
        //     report header and the grand total even when no detail rows
        //     pass the date filter, so size > 0 is the minimum-floor
        //     pre-check that catches a wholly empty output before the
        //     byte-equality assertion produces a less informative
        //     "0 bytes vs N bytes" message.
        assertThat(Files.size(actualOutput))
                .as("Report file must be non-empty (multi-page report with totals)")
                .isGreaterThan(0L);

        // ---- Final parity gate (AAP §0.10.4 -- byte-for-byte zero delta) ----
        // BaselineDiffUtil detects the BASELINE_CAPTURE_PENDING_ placeholder
        // in the expected reference and fails loudly until the captured
        // COBOL reference output is committed. Once the placeholder is
        // replaced, this assertion becomes the byte-for-byte parity check
        // that proves the migrated Java formatter is faithful to the
        // CBTRN03C output down to every fixed-width column boundary.
        final Path expectedOutput =
                resolveExpectedReference(TestFixtures.Paths.EXPECTED_TRANSACTION_REPORT);
        BaselineDiffUtil.assertByteEqual(actualOutput, expectedOutput);
    }

    // =========================================================================
    // Private helpers -- staging classpath fixtures onto the @TempDir filesystem
    // =========================================================================

    /**
     * Loads a canonical baseline-input fixture from the test classpath
     * ({@code src/test/resources/baseline/input/}) and writes it to the
     * {@link #workDir} {@link TempDir} so the Spring Batch Job's
     * {@code FlatFileItemReader} can consume it from a real filesystem
     * path.
     *
     * <p>The helper exists because {@code FlatFileItemReader} expects a
     * {@link java.nio.file.Path} or {@code Resource} backed by a real
     * filesystem location, NOT a classpath resource -- staging through
     * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)} is
     * the idiomatic Spring Batch test pattern.
     *
     * <p>Byte-level write (not character-level) preserves the original
     * fixed-width COBOL record byte layout including any trailing
     * whitespace and the original line-ending convention; AAP §0.10.4
     * ("Input and output file formats and record layouts MUST remain
     * identical") forbids any charset / line-ending normalisation in the
     * test harness.
     *
     * @param filename simple basename of the fixture (e.g.
     *                 {@code "cardxref.txt"}) -- must be one of the
     *                 canonical fixtures under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_INPUT_DIR}
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #workDir} ready to be passed as a Spring Batch
     *         {@code JobParameters} string value
     * @throws java.io.IOException when the {@link Files#write} call fails
     *                             (disk full, permission denied, or the
     *                             {@link TempDir} root has been removed
     *                             externally)
     */
    private Path stageBaselineInput(String filename) throws java.io.IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR + filename);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Loads a captured baseline-expected fixture from the test classpath
     * ({@code src/test/resources/baseline/expected/}) and writes it to
     * the {@link #workDir} {@link TempDir}.
     *
     * <p>Used to stage the {@code combined.txt} fixture as the TRANFILE
     * input for this IT; that fixture is the captured output of the
     * upstream COMBTRAN.jcl pipeline step (see
     * {@code CombineTransactionsBaselineParityIT}) and represents what
     * the migrated DFSORT-equivalent step produces when given the
     * canonical daily transactions in {@code dailytran.txt}.
     *
     * <p>Identical mechanism to {@link #stageBaselineInput(String)} but
     * sourced from a different classpath root. The split between
     * baseline-input fixtures and baseline-expected fixtures matches
     * AAP §0.4.4 (Fixture Organization Strategy): canonical golden
     * inputs live under {@code baseline/input/}, captured COBOL
     * reference outputs live under {@code baseline/expected/}, and an
     * IT pipeline that exercises a downstream-only step (like this
     * one) consumes the upstream step's expected output as its input.
     *
     * @param filename simple basename of the expected-output fixture
     *                 (e.g. {@code "combined.txt"}) -- must be one of
     *                 the captured reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #workDir} ready to be passed as a Spring Batch
     *         {@code JobParameters} string value
     * @throws java.io.IOException when the {@link Files#write} call fails
     *                             (disk full, permission denied, or the
     *                             {@link TempDir} root has been removed
     *                             externally)
     */
    private Path stageExpectedFixture(String filename) throws java.io.IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_EXPECTED_DIR + filename);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Resolves the captured COBOL reference output from the test classpath
     * to a {@link Path} value suitable for passing as the right-hand
     * operand of {@link BaselineDiffUtil#assertByteEqual(Path, Path)}.
     *
     * <p>The reference file lives under
     * {@code src/test/resources/baseline/expected/} at build time and is
     * placed under the test classpath at run time by Maven's
     * {@code resources} plugin. The classpath lookup is performed via
     * {@link Class#getResource(String)} (not by reading bytes through
     * {@link FixtureLoader#loadAsBytes(String)} into a staged temp file)
     * because BaselineDiffUtil's error messages reference the path's
     * filesystem location to help operators locate the captured baseline
     * &mdash; staging the bytes into the {@link TempDir} would surface a
     * temporary path that disappears after the test completes, hindering
     * post-mortem investigation.
     *
     * <p>The lookup is defensive: a {@code null} {@code URL} (the
     * classpath resource is missing) raises an
     * {@link IllegalStateException} naming both the classpath path and
     * the source-tree path operators should investigate, rather than a
     * less informative {@link NullPointerException} from the
     * {@link Path#of(java.net.URI)} call that would otherwise follow.
     *
     * @param filename simple basename of the captured reference output
     *                 (e.g. {@code "transaction_report.txt"}) -- must be
     *                 one of the captured reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} pointing at the captured reference
     *         file on the test classpath's underlying filesystem
     * @throws IllegalStateException     when the classpath resource is
     *                                   missing (likely cause: the
     *                                   {@code src/test/resources/baseline/expected/}
     *                                   file has not been committed yet)
     * @throws java.net.URISyntaxException when the classpath URL cannot be
     *                                     converted to a URI (extremely
     *                                     unusual; would indicate a
     *                                     malformed classpath entry,
     *                                     e.g. unescaped characters in a
     *                                     filesystem-rooted classpath)
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
