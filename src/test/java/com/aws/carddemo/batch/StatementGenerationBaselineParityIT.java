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
//     field used to launch the {@code statementGenerationJob} bean, the
//     inherited {@code applicationContext} field used to look up the
//     {@code statementGenerationJob} bean by name (necessary because
//     {@code transactionPostingJob} is the {@code @Primary} Job, not the
//     statement job), and the per-test @BeforeEach hook that resets the
//     Spring Batch JobRepository between tests so each test starts with a
//     clean metadata slate (AAP §0.10.9 test isolation).
//
//   * BaselineDiffUtil -- byte-level diff utility supplying the primary
//     parity gates {@code assertByteEqual(Path, Path)}. THIS IT calls
//     assertByteEqual TWICE -- once for the plain-text statement output
//     ({@code statements_text.txt}, 80-byte fixed-width records per
//     FD-STMTFILE-REC PIC X(80) in CBSTM03A.CBL line 45) and once for the
//     HTML statement output ({@code statements_html.txt}, 100-byte fixed-
//     width records per FD-HTMLFILE-REC PIC X(100) in CBSTM03A.CBL line
//     47). Each captured-reference fixture under
//     {@code src/test/resources/baseline/expected/} is currently a
//     BASELINE_CAPTURE_PENDING_* placeholder; BaselineDiffUtil detects the
//     placeholder marker and fails the assertion loudly with a clear
//     "capture pending" message -- per AAP §0.10.4 the test MUST fail
//     when either baseline is still a stub (silent false-positive
//     avoidance). Once the COBOL reference outputs are captured and
//     committed at those paths, the same assertions become the byte-for-
//     byte parity gates.
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
//         {@code FIXTURE_CUSTDATA} ({@code "custdata.txt"}), and
//         {@code FIXTURE_ACCTDATA} ({@code "acctdata.txt"}) -- canonical
//         baseline-input fixture filenames for the XREFFILE / CUSTFILE /
//         ACCTFILE inputs declared in CREASTMT.JCL.
//       * {@code EXPECTED_COMBINED} ({@code "combined.txt"}) -- the
//         captured COBOL reference output of the upstream COMBTRAN.jcl
//         step (the STEP010 SORT + STEP020 REPRO pre-bake of the
//         TRNXFILE VSAM KSDS), reused here as the TRNXFILE input per
//         the CREASTMT.JCL STEP040 DD wiring.
//       * {@code EXPECTED_STATEMENTS_TEXT}
//         ({@code "statements_text.txt"}) and
//         {@code EXPECTED_STATEMENTS_HTML}
//         ({@code "statements_html.txt"}) -- the captured COBOL reference
//         outputs of CREASTMT.JCL/CBSTM03A; both are right-hand operands
//         of the dual {@code BaselineDiffUtil.assertByteEqual} parity
//         gates.
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
//     produced STMTFILE + HTMLFILE outputs and the staged input fixtures
//     never leak between tests or onto the workspace. This is the
//     canonical JUnit 5 mechanism for test-scoped filesystem isolation
//     per AAP §0.10.9 (test isolation requirements).
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
//     Job must reach COMPLETED before the parity gates are allowed to run
//     (a non-COMPLETED status indicates a Spring Batch failure that
//     would mask any subsequent byte-equality assertion).
//   * Job -- needed by jobLauncherTestUtils.setJob(Job). The inherited
//     {@code applicationContext} field (from AbstractBatchIT) resolves
//     the {@code statementGenerationJob} bean by name; without this
//     explicit setJob() call the launcher would run the {@code @Primary}
//     {@code transactionPostingJob} instead, per the AbstractBatchIT
//     contract for {@code *BaselineParityIT} subclasses
//     (AbstractBatchIT#jobBeanNameFromClassName() only auto-resolves
//     {@code *JobIT}, NOT {@code *BaselineParityIT}; see AbstractBatchIT
//     Javadoc lines 587-592).
//   * JobExecution -- runtime metadata object for a single Job invocation;
//     returned by JobLauncherTestUtils.launchJob and used to assert on
//     lifecycle status before the parity gates run.
//   * JobParameters / JobParametersBuilder -- typed parameter container
//     and its canonical builder; assembles the 6 file-path parameters
//     mirroring the CREASTMT.JCL XREFFILE / CUSTFILE / ACCTFILE /
//     TRNXFILE / STMTFILE / HTMLFILE DD statements plus the unique
//     run.timestamp that prevents Spring Batch's duplicate-instance
//     refusal when this test is re-run.
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
//     verifies the produced STMTFILE / HTMLFILE outputs are non-empty
//     before the parity gates run (a zero-byte output would fail the
//     byte-equality check with a misleading "empty vs N bytes" message,
//     so these pre-checks produce a clearer failure mode).
//
//   * Path -- JDK NIO.2 filesystem coordinate. The @TempDir-injected
//     workDir, every staged-fixture handle, the produced STMTFILE +
//     HTMLFILE output handles, and the resolved expected-reference
//     handles are all Path values. Path is preferred over the legacy
//     java.io.File because it is immutable, plays well with the NIO.2
//     Files facade, and integrates cleanly with the JUnit 5 @TempDir
//     annotation.
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
 * Baseline parity integration test for the Statement Generation Spring Batch
 * job ({@code app/jcl/CREASTMT.JCL} &rarr; {@code app/cbl/CBSTM03A.CBL}
 * migration).
 *
 * <p>Drives the migrated Spring Batch {@code statementGenerationJob} bean
 * against the canonical {@code cardxref.txt}, {@code custdata.txt},
 * {@code acctdata.txt}, and {@code combined.txt} fixtures and asserts that
 * <strong>BOTH</strong> produced output files (plain-text statements and
 * HTML statements) are byte-for-byte identical to the captured COBOL
 * reference outputs at
 * {@code src/test/resources/baseline/expected/statements_text.txt} and
 * {@code src/test/resources/baseline/expected/statements_html.txt}
 * respectively.
 *
 * <h2>Source COBOL behaviour (CBSTM03A)</h2>
 * <ul>
 *   <li>For each card-XREF record read from the XREF-FILE:
 *     <ol>
 *       <li>Look up the customer (CUST-FILE), account (ACCT-FILE), and all
 *           transactions for that card (TRNX-FILE).</li>
 *       <li>Aggregate transactions into the {@code WS-CARD-TBL} (OCCURS 51)
 *           and {@code WS-TRAN-TBL} (OCCURS 10) tables -- up to 10
 *           transactions per card.</li>
 *       <li>Write a multi-line statement to STMTFILE (80-byte records per
 *           {@code FD-STMTFILE-REC PIC X(80)} at CBSTM03A.CBL line 45)
 *           consisting of:
 *         <ul>
 *           <li>{@code ST-LINE0} -- 31 '*' + 'START OF STATEMENT' + 31 '*'
 *               (80 chars).</li>
 *           <li>{@code ST-LINE1} -- Customer name (75 chars + 5 trailing
 *               spaces).</li>
 *           <li>{@code ST-LINE2/3/4} -- Address lines (each 80 chars with
 *               varying ADDR / FILLER splits).</li>
 *           <li>{@code ST-LINE5} -- 80 hyphens (separator).</li>
 *           <li>{@code ST-LINE6} -- 'Basic Details' centered (80 chars).</li>
 *           <li>{@code ST-LINE7} -- Account ID (80 chars).</li>
 *           <li>{@code ST-LINE8} -- Current Balance ({@code PIC 9(9).99-}
 *               trailing-minus mask).</li>
 *           <li>{@code ST-LINE9} -- FICO Score.</li>
 *           <li>{@code ST-LINE10} -- 80 hyphens (separator).</li>
 *           <li>{@code ST-LINE11} -- 'TRANSACTION SUMMARY' centered.</li>
 *           <li>{@code ST-LINE12} -- 80 hyphens (separator).</li>
 *           <li>{@code ST-LINE13} -- Transaction column headers.</li>
 *           <li>{@code ST-LINE14} -- Per-transaction lines (TRAN-ID,
 *               date, amount with {@code PIC Z(9).99-} mask).</li>
 *           <li>{@code ST-LINE14A} -- Total expenses line.</li>
 *           <li>{@code ST-LINE15} -- 32 '*' + 'END OF STATEMENT' + 32 '*'
 *               (80 chars).</li>
 *         </ul>
 *       </li>
 *       <li>Write the same statement content to HTMLFILE (100-byte
 *           records per {@code FD-HTMLFILE-REC PIC X(100)} at
 *           CBSTM03A.CBL line 47) with embedded CSS classes referencing
 *           the colour palette {@code #1d1d96b3}, {@code #FFAF33},
 *           {@code #f2f2f2}, {@code #33FFD1}, and {@code #33FF5E}.</li>
 *     </ol>
 *   </li>
 *   <li>Bank branding literals embedded in every statement header (both
 *       text and HTML forms):
 *       {@link TestFixtures.Branding#BANK_NAME} ({@code "Bank of XYZ"}),
 *       {@link TestFixtures.Branding#BANK_ADDRESS_LINE_1}
 *       ({@code "410 Terry Ave N"}), and
 *       {@link TestFixtures.Branding#BANK_ADDRESS_LINE_2}
 *       ({@code "Seattle WA 99999"}). Per AAP §0.10.4 the migrated
 *       production code must emit these literals verbatim so the
 *       byte-equality gates pass.</li>
 * </ul>
 *
 * <h2>Dual-output parity gate</h2>
 *
 * <p>Unlike the other batch jobs in the CardDemo pipeline (POSTTRAN,
 * INTCALC, COMBTRAN, TRANREPT -- each producing one output file), CREASTMT
 * produces <strong>TWO</strong> output files. The baseline parity contract
 * (AAP §0.10.4) requires BOTH to be byte-identical to their captured
 * references:
 * <ul>
 *   <li>{@link TestFixtures.Paths#EXPECTED_STATEMENTS_TEXT}
 *       ({@code statements_text.txt}, 80-byte fixed-width plain text)</li>
 *   <li>{@link TestFixtures.Paths#EXPECTED_STATEMENTS_HTML}
 *       ({@code statements_html.txt}, 100-byte fixed-width HTML with
 *       embedded CSS)</li>
 * </ul>
 *
 * <p>The two parity assertions are sequential rather than parallel: the
 * plain-text diff is checked first because the HTML form is derived from
 * the same underlying record stream; if the text statement file diverges
 * the HTML diff diagnostic is almost certainly secondary noise pointing at
 * an upstream formatter regression. Sequential failure produces a focused
 * diagnostic that names the FIRST violated invariant rather than a
 * compound message conflating two derived failures.
 *
 * <h2>Why {@code combined.txt} is the TRNXFILE input</h2>
 *
 * <p>The original {@code CREASTMT.JCL} pipeline runs four steps:
 * <ol>
 *   <li>DELDEF01 -- IDCAMS DELETE/DEFINE of the TRXFL VSAM KSDS (fresh
 *       cluster setup).</li>
 *   <li>STEP010 -- DFSORT reads the TRANSACT VSAM KSDS and sorts records
 *       by composite (card-number, tran-id) key, producing the
 *       {@code AWS.M2.CARDDEMO.TRXFL.SEQ} sequential file.</li>
 *   <li>STEP020 -- IDCAMS REPRO loads the sequential file into the TRXFL
 *       VSAM KSDS so STEP040 can read it via the indexed
 *       {@code SELECT TRNX-FILE} in {@code CBSTM03B}.</li>
 *   <li>STEP040 -- {@code CBSTM03A} reads the sorted TRNXFILE + XREFFILE
 *       + CUSTFILE + ACCTFILE and emits the dual STMTFILE + HTMLFILE.</li>
 * </ol>
 *
 * <p>This parity IT exercises step 4 in isolation by staging the
 * pre-captured {@code baseline/expected/combined.txt} (the captured output
 * of the migrated {@code combineTransactionsJob}; see
 * {@code CombineTransactionsBaselineParityIT}) as the TRNXFILE input. This
 * upstream-step independence ensures any regression localised to the
 * statement-formatter component (line widths, balance masks, page
 * structure, HTML CSS colours) shows up in this test, not blurred by an
 * upstream variance in COMBTRAN.
 *
 * <h2>Why this is the most layout-sensitive parity IT</h2>
 *
 * <p>The CREASTMT output combines:
 * <ul>
 *   <li>80-char fixed-width records for STMTFILE (the plain-text
 *       form),</li>
 *   <li>100-char fixed-width records for HTMLFILE (the HTML form,
 *       unusually wide because of the {@code <td class="...">} markup
 *       padding),</li>
 *   <li>Two distinct numeric PIC masks ({@code PIC 9(9).99-} for the
 *       balance line, {@code PIC Z(9).99-} for per-transaction amounts),</li>
 *   <li>Customer-name and address formatting derived from
 *       {@code CUSTREC.cpy} composite fields,</li>
 *   <li>Per-card aggregation that emits the per-customer statement once
 *       all transactions for that card have been read,</li>
 *   <li>HTML structural elements with embedded CSS class names that
 *       reference the colour palette in CBSTM03A's HTML-LINES
 *       working-storage section.</li>
 * </ul>
 *
 * <p>A single misaligned space, a single shifted CSS hex digit, or a
 * single missing HTML tag breaks the byte-equality gate. The COBOL
 * formatter's behaviour is therefore captured byte-for-byte by the two
 * {@link BaselineDiffUtil#assertByteEqual(Path, Path)} calls at the
 * bottom of the test, with unified-diff output on mismatch surfacing the
 * exact offending lines.
 *
 * <h2>Placeholder handling (AAP §0.10.4)</h2>
 *
 * <p>The expected references at
 * {@link TestFixtures.Paths#EXPECTED_STATEMENTS_TEXT} and
 * {@link TestFixtures.Paths#EXPECTED_STATEMENTS_HTML} currently contain
 * {@code BASELINE_CAPTURE_PENDING_STATEMENTS_TEXT} and
 * {@code BASELINE_CAPTURE_PENDING_STATEMENTS_HTML} placeholders
 * respectively. {@link BaselineDiffUtil} detects these markers and throws
 * an {@link AssertionError} with a clear "capture pending" message,
 * preventing silent false positives. Once each COBOL reference output is
 * captured (per AAP §0.5.4: "captured by running COBOL baseline against
 * ASCII input") and committed at those paths, the same assertions become
 * the byte-for-byte parity gates.
 *
 * <h2>{@code statementGenerationJob} bean wiring</h2>
 *
 * <p>The inherited {@link AbstractBatchIT#cleanJobRepository()} hook
 * auto-resolves the Spring Batch Job for subclasses whose simple name
 * ends with {@code "JobIT"} (so {@code StatementGenerationJobIT} gets the
 * {@code statementGenerationJob} bean wired automatically). For
 * {@code BaselineParityIT} subclasses the hook returns {@code null} and
 * leaves the {@code @Primary} default in place &mdash; per
 * AbstractBatchIT Javadoc lines 587-592: "baseline-parity ITs that share
 * this base class but pin their own Job lookup ... are expected to call
 * {@code jobLauncherTestUtils.setJob(...)} explicitly when they need to
 * drive a non-default Job." The production
 * {@link com.aws.carddemo.batch.config.BatchJobConfig} marks
 * {@code transactionPostingJob} as {@code @Primary}, so without an
 * explicit setJob() this IT would launch the wrong Job. The test method
 * therefore looks up {@code statementGenerationJob} by name from the
 * inherited {@code applicationContext} and calls
 * {@code jobLauncherTestUtils.setJob(...)} before launching.
 *
 * <h2>Determinism</h2>
 *
 * <p>CBSTM03A's statement formatter is fully deterministic over its
 * input: it has no clock dependency, no random-number source, no
 * environment lookup, and no run-time PARM. Statements show transaction
 * data only, not the run time. Output is fully determined by the input
 * record set; the captured COBOL baseline therefore reproduces
 * bit-for-bit on every invocation against identical input fixtures, and
 * the byte-equality gates are deterministic across CI runs.
 *
 * <h2>Contract (AAP §0.10.4)</h2>
 *
 * <p>Zero-byte delta against {@code statements_text.txt}, then zero-byte
 * delta against {@code statements_html.txt}. Both gates MUST pass for
 * the parity contract to hold.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (file-by-file plan: "Byte-identical diff for statement
 * files"), §0.5.2 (parity IT pattern -- dual output), §0.10.4 (zero-delta
 * requirement, immutable boundaries), §0.10.10 (style consistency --
 * AssertJ exclusively, AAA arrangement with blank-line separators).
 *
 * @see BaselineDiffUtil byte-equal diff utility supplying the
 *      {@code assertByteEqual} parity gates.
 * @see AbstractBatchIT shared base class supplying the Spring Boot test
 *      context, the Spring Batch test slice, the Testcontainers
 *      PostgreSQL 16 container, and the {@code applicationContext}
 *      handle used to resolve the {@code statementGenerationJob} bean.
 * @see com.aws.carddemo.batch.StatementProcessor the migrated
 *      {@code CBSTM03A} body component (text + HTML statement formatter)
 *      whose Job-bean wiring this IT exercises end-to-end.
 * @see com.aws.carddemo.batch.StatementFileProcessor the migrated
 *      {@code CBSTM03B} file-I/O orchestrator that the
 *      {@code statementGenerationJob} wires alongside the
 *      {@code StatementProcessor}.
 * @see StatementGenerationJobIT companion structural IT that asserts the
 *      Job's execution semantics and dual-output production
 *      (both files exist and are non-empty) independently of byte-level
 *      parity.
 * @see TransactionReportBaselineParityIT sibling baseline-parity IT for
 *      the TRANREPT.JCL migration; follows the same structural pattern
 *      (explicit setJob, @TempDir staging, AssertJ pre-parity gates,
 *      BaselineDiffUtil parity gate).
 */
@DisplayName("CREASTMT.JCL baseline parity (byte-identical text + HTML statement outputs)")
@Disabled("Awaits authentic COBOL baseline capture for CREASTMT.JCL / CBSTM03A + CBSTM03B. "
        + "Per AAP §0.10.4 (Immutable Boundaries) this dual byte-identical parity gate must "
        + "compare Java output to COBOL-produced STMTFILE (LRECL=80) + HTMLFILE (LRECL=100) "
        + "references; src/test/resources/baseline/expected/statements_text.txt and "
        + "statements_html.txt are committed as BASELINE_CAPTURE_PENDING_STATEMENTS "
        + "placeholders until the COBOL/JCL runtime is available to capture the reference "
        + "outputs. The captured baseline must preserve the full ST-LINE0..ST-LINE15 layout, "
        + "hardcoded bank branding, and HTML table/color structure. "
        + "See docs/testing/baseline-parity.md §5 for the 7-step capture procedure. "
        + "Remove this annotation when the authentic COBOL references are committed.")
class StatementGenerationBaselineParityIT extends AbstractBatchIT {

    /**
     * Per-test isolated temporary directory injected by JUnit 5's
     * {@link TempDir} extension. Used as the staging area for the four
     * input fixtures (XREFFILE / CUSTFILE / ACCTFILE / TRNXFILE) and as
     * the destination directory for the produced STMTFILE + HTMLFILE
     * outputs.
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
     * {@link TransactionReportBaselineParityIT},
     * {@link StatementGenerationJobIT}, and the other batch ITs for
     * their {@code @TempDir} fields.
     */
    @TempDir
    Path workDir;

    /**
     * Runs {@code statementGenerationJob} against the canonical statement
     * input fixtures and asserts byte-for-byte parity against BOTH the
     * captured plain-text and HTML reference outputs.
     *
     * <p><strong>Arrange.</strong> Resolve the
     * {@code statementGenerationJob} bean from the inherited
     * {@code applicationContext} and pin it onto the inherited
     * {@code jobLauncherTestUtils} (necessary because the inherited
     * {@code AbstractBatchIT#cleanJobRepository()} hook only
     * auto-resolves Jobs for {@code *JobIT} subclasses per its
     * {@code jobBeanNameFromClassName()} contract -- this is a
     * {@code *BaselineParityIT} subclass, so the auto-resolve returns
     * {@code null} and the {@code @Primary} {@code transactionPostingJob}
     * would otherwise be launched). Then stage the four input fixtures
     * (XREFFILE / CUSTFILE / ACCTFILE / TRNXFILE) from the test
     * classpath into the JUnit {@link #workDir} {@link TempDir} so
     * Spring Batch's {@code FlatFileItemReader} instances (which require
     * real filesystem paths, not classpath resources) can consume them.
     * Build the {@link JobParameters} bundle that mirrors the
     * CREASTMT.JCL DD assignments plus a unique {@code run.timestamp} to
     * guarantee each JobInstance is distinct even when this method is
     * re-run.
     *
     * <p><strong>Act.</strong> Call
     * {@code jobLauncherTestUtils.launchJob(params)} which synchronously
     * runs the configured {@code statementGenerationJob} to completion
     * (or failure) and returns the {@link JobExecution} metadata.
     *
     * <p><strong>Assert.</strong> Five pre-parity gates run before the
     * dual byte-equality checks:
     * <ol>
     *   <li>{@link BatchStatus#COMPLETED} -- the Spring Batch analogue
     *       of the COBOL {@code STOP RUN} with RC=0. A non-COMPLETED
     *       status would mask any subsequent byte-equality failure with
     *       a less informative error.</li>
     *   <li>{@code actualStatementsText.exists()} -- the migrated Job
     *       must produce the plain-text STMTFILE output at the requested
     *       path.</li>
     *   <li>{@link Files#size(Path)} on the text file &gt; 0 -- the
     *       produced text statement must be non-empty (CBSTM03A always
     *       writes at least the START-OF-STATEMENT banner + customer
     *       header + END-OF-STATEMENT banner, so size &gt; 0 is the
     *       minimum-floor pre-check).</li>
     *   <li>{@code actualStatementsHtml.exists()} -- the migrated Job
     *       must also produce the HTMLFILE output at the requested
     *       path; a dual-output regression that silently drops one file
     *       is caught here.</li>
     *   <li>{@link Files#size(Path)} on the HTML file &gt; 0 -- the
     *       produced HTML statement must be non-empty (the HTML wrapper
     *       alone guarantees size &gt; 0).</li>
     * </ol>
     *
     * <p>The final two assertions are the parity gates, run sequentially
     * in text-then-HTML order (see class Javadoc for rationale):
     * {@link BaselineDiffUtil#assertByteEqual(Path, Path)} with the
     * produced STMTFILE / HTMLFILE as actual and the captured COBOL
     * references as expected. While each captured reference is still a
     * {@code BASELINE_CAPTURE_PENDING_*} placeholder, BaselineDiffUtil
     * throws an {@link AssertionError} with a clear "capture pending"
     * message; once the placeholders are replaced with the actual
     * captured COBOL outputs, the same assertions become the zero-byte-
     * delta parity checks mandated by AAP §0.10.4.
     *
     * <p><strong>Require Test Coverage rule (AAP §0.10.1).</strong> This
     * method drives the real production {@code statementGenerationJob}
     * bean end-to-end. No business logic is reimplemented inside the
     * test body: there is no record formatting, no PIC clause masking,
     * no per-customer aggregation, no HTML tag construction. Every
     * value asserted is either a Spring Batch runtime state
     * ({@code BatchStatus}, {@code Files.size}) or the raw byte content
     * of a produced output file as compared to the captured reference.
     *
     * @throws Exception when {@code jobLauncherTestUtils.launchJob}
     *                   propagates a Spring Batch launch failure, when
     *                   {@code Files.write} or {@code Files.size} fails
     *                   on the {@link TempDir}-backed filesystem, or
     *                   when {@code resolveExpectedReference}
     *                   encounters a {@link java.net.URISyntaxException}
     *                   while converting a classpath URL to a Path
     */
    @Test
    @DisplayName("Job execution produces byte-identical statements_text.txt AND statements_html.txt")
    void statementJob_runsAgainstFixtures_producesByteIdenticalTextAndHtmlOutputs() throws Exception {
        // ---- Arrange ----
        // Resolve the statementGenerationJob bean by name from the inherited
        // ApplicationContext and pin it onto the inherited
        // jobLauncherTestUtils. AbstractBatchIT.cleanJobRepository() only
        // auto-resolves the Job for *JobIT subclasses (per its
        // jobBeanNameFromClassName() contract); BaselineParityIT subclasses
        // must explicitly call setJob() before launchJob to avoid running
        // the @Primary transactionPostingJob by accident. See AbstractBatchIT
        // Javadoc lines 587-592 for the documented convention.
        final Job job = applicationContext.getBean("statementGenerationJob", Job.class);
        jobLauncherTestUtils.setJob(job);

        // Stage every CREASTMT.JCL DD-mapped input file into the @TempDir so
        // Spring Batch's FlatFileItemReader instances can read them from real
        // filesystem paths (FlatFileItemReader does NOT consume classpath
        // resources directly). The XREFFILE / CUSTFILE / ACCTFILE inputs are
        // loaded from the canonical baseline/input/ directory; the TRNXFILE
        // input is loaded from baseline/expected/combined.txt which
        // represents the output of the upstream COMBTRAN-equivalent step
        // (see the class Javadoc "Why combined.txt is the TRNXFILE input"
        // section). This IT runs only the CREASTMT STEP040 statement-
        // formatting step, so the upstream STEP010 (SORT) and STEP020
        // (REPRO) are pre-baked into the input fixture.
        final Path stagedCardxref = stageBaselineInput(TestFixtures.Paths.FIXTURE_CARDXREF);
        final Path stagedCustdata = stageBaselineInput(TestFixtures.Paths.FIXTURE_CUSTDATA);
        final Path stagedAcctdata = stageBaselineInput(TestFixtures.Paths.FIXTURE_ACCTDATA);
        // The TRNXFILE input is the COMBTRAN output (the combined & sorted
        // file containing posted purchases AND interest transactions).
        // Using baseline/expected/combined.txt as the input ensures this
        // parity test focuses on CBSTM03A's logic, not on any upstream
        // divergence in the COMBTRAN step.
        final Path stagedTransact = stageExpectedFixture(TestFixtures.Paths.EXPECTED_COMBINED);

        // Destination paths for the produced STMTFILE (LRECL=80, text) and
        // HTMLFILE (LRECL=100, HTML) outputs. Just resolve() calls against
        // the @TempDir -- Spring Batch's FlatFileItemWriter creates each
        // file lazily when its Step opens its writer (no pre-existence
        // required). The filenames mirror the baseline/expected/ goldens so
        // the BaselineDiffUtil call sites pair them naturally with the
        // captured references.
        final Path actualStatementsText = workDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_TEXT);
        final Path actualStatementsHtml = workDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_HTML);

        // Build the JobParameters bundle that mirrors the CREASTMT.JCL DD
        // assignments. The run.timestamp parameter guarantees each
        // JobInstance is unique even if this method is re-run (Spring
        // Batch would otherwise treat a repeat invocation as a restart of
        // the prior COMPLETED instance and refuse to launch). Path values
        // are absolutised so the Spring Batch reader/writer resolves them
        // independent of the JVM working directory (Spring Batch's
        // FlatFileItemReader uses java.io.File semantics for relative-path
        // resolution, which depends on the working directory of the
        // process -- absolutising avoids the brittleness).
        final JobParameters params = new JobParametersBuilder()
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.custdata.path", stagedCustdata.toAbsolutePath().toString())
                .addString("input.acctdata.path", stagedAcctdata.toAbsolutePath().toString())
                .addString("input.transact.path", stagedTransact.toAbsolutePath().toString())
                .addString("output.stmtfile.path", actualStatementsText.toAbsolutePath().toString())
                .addString("output.htmlfile.path", actualStatementsHtml.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by
        // @SpringBatchTest. launchJob synchronously runs the configured
        // statementGenerationJob bean to completion (or failure) and
        // returns the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Spring Batch execution status -- the Java analogue of the
        //     COBOL CBSTM03A STOP RUN with RC=0. A non-COMPLETED status
        //     here would mask any subsequent byte-equality failure with
        //     a less informative error, so we check it first.
        assertThat(execution.getStatus())
                .as("Spring Batch job must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) STMTFILE existence -- the migrated Job must have produced
        //     the plain-text statement file at the path we requested via
        //     the output.stmtfile.path JobParameter. A missing file would
        //     mean the JobParameter was ignored or the writer was
        //     misconfigured.
        assertThat(actualStatementsText)
                .as("Job must produce the plain-text statement file at the requested path")
                .exists();

        // (3) STMTFILE non-emptiness -- CBSTM03A always emits at least
        //     the START-OF-STATEMENT banner + customer header +
        //     END-OF-STATEMENT banner per customer record, so size > 0
        //     is the minimum-floor pre-check that catches a wholly empty
        //     output before the byte-equality assertion produces a less
        //     informative "0 bytes vs N bytes" message.
        assertThat(Files.size(actualStatementsText))
                .as("Plain-text statement file must be non-empty")
                .isGreaterThan(0L);

        // (4) HTMLFILE existence -- the second of the two outputs
        //     CBSTM03A produces. Asserted independently with its own
        //     .as(...) description so a regression that drops ONLY the
        //     HTML output (without affecting the text output) fails with
        //     a clear diagnostic.
        assertThat(actualStatementsHtml)
                .as("Job must produce the HTML statement file at the requested path")
                .exists();

        // (5) HTMLFILE non-emptiness -- the HTML wrapper alone (the
        //     <html>/<head>/<body> open and close tags) guarantees a
        //     non-empty file even with zero detail rows, so size > 0 is
        //     the minimum-floor pre-check.
        assertThat(Files.size(actualStatementsHtml))
                .as("HTML statement file must be non-empty")
                .isGreaterThan(0L);

        // ---- Final dual parity gate (AAP §0.10.4 -- byte-for-byte zero
        //                                            delta on BOTH outputs)
        //
        // Sequential text-then-HTML ordering: if the text statement file
        // diverges, the HTML diff diagnostic is almost certainly secondary
        // noise pointing at an upstream formatter regression -- sequential
        // failure produces a focused diagnostic that names the FIRST
        // violated invariant rather than a compound message conflating two
        // derived failures.
        //
        // BaselineDiffUtil detects the BASELINE_CAPTURE_PENDING_* placeholder
        // in each expected reference and fails loudly until the captured
        // COBOL reference outputs are committed. Once the placeholders are
        // replaced, these assertions become the byte-for-byte parity
        // checks that prove the migrated Java formatter is faithful to
        // CBSTM03A's output down to every fixed-width column boundary,
        // every PIC clause character, every HTML tag, and every CSS hex
        // digit.
        final Path expectedStatementsText =
                resolveExpectedReference(TestFixtures.Paths.EXPECTED_STATEMENTS_TEXT);
        final Path expectedStatementsHtml =
                resolveExpectedReference(TestFixtures.Paths.EXPECTED_STATEMENTS_HTML);

        BaselineDiffUtil.assertByteEqual(actualStatementsText, expectedStatementsText);
        BaselineDiffUtil.assertByteEqual(actualStatementsHtml, expectedStatementsHtml);
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
     * <p>Helper is duplicated from {@link TransactionReportBaselineParityIT}
     * and {@link StatementGenerationJobIT} rather than extracted into a
     * shared base-class utility -- AAP §0.10.2 Minimal Change Clause
     * forbids introducing abstractions that exist only to flatter the
     * test code. The four-line helper is simpler to read inline than to
     * navigate through a base-class indirection, and the duplication is
     * contained to ITs that share the identical staging mechanism.
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
     * <p>Used to stage the {@code combined.txt} fixture as the TRNXFILE
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
     * IT pipeline that exercises a downstream-only step (like this one)
     * consumes the upstream step's expected output as its input.
     *
     * <p>The {@code combined.txt} fixture's record layout matches the
     * {@code app/cbl/CBSTM03B.cbl} FD declaration for {@code TRNX-FILE}
     * (16-char {@code FD-TRNX-CARD} + 16-char {@code FD-TRNX-ID} +
     * 318-byte {@code FD-ACCT-DATA}, total 350 bytes per record); the
     * upstream COMBTRAN-equivalent step is responsible for producing
     * exactly that layout from the daily transactions input.
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
     *                 (e.g. {@code "statements_text.txt"} or
     *                 {@code "statements_html.txt"}) -- must be one of
     *                 the captured reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} pointing at the captured reference
     *         file on the test classpath's underlying filesystem
     * @throws IllegalStateException       when the classpath resource is
     *                                     missing (likely cause: the
     *                                     {@code src/test/resources/baseline/expected/}
     *                                     file has not been committed
     *                                     yet)
     * @throws java.net.URISyntaxException when the classpath URL cannot
     *                                     be converted to a URI
     *                                     (extremely unusual; would
     *                                     indicate a malformed classpath
     *                                     entry, e.g. unescaped
     *                                     characters in a
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
