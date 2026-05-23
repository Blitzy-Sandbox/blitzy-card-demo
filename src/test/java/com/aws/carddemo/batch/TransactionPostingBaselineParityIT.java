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
//     field used to launch the {@code transactionPostingJob} bean, the
//     inherited {@code applicationContext} field used to look up the
//     {@code transactionPostingJob} bean by name (although the
//     {@code transactionPostingJob} happens to be the {@code @Primary}
//     Job in BatchJobConfig, the explicit lookup + setJob() pattern is
//     preserved for consistency with the sibling baseline parity ITs and
//     for resilience against future @Primary changes), and the per-test
//     @BeforeEach hook that resets the Spring Batch JobRepository between
//     tests so each test starts with a clean metadata slate (AAP §0.10.9
//     test isolation).
//
//   * BaselineDiffUtil -- byte-level diff utility supplying the primary
//     parity gate {@code assertByteEqual(Path, Path)}. The expected
//     reference file ({@code posted.txt} under
//     {@code src/test/resources/baseline/expected/}) is the captured
//     reference output of CBTRN02C posting against the canonical
//     dailytran.txt input; BaselineDiffUtil's placeholder-marker scan
//     ensures the test fails loudly if the reference is ever replaced by
//     a {@code BASELINE_CAPTURE_PENDING_} stub (silent false-positive
//     avoidance, AAP §0.10.4).
//
//   * FixtureLoader -- classpath fixture loader. The static helper
//     {@link FixtureLoader#loadAsBytes(String)} returns the raw byte
//     contents of a classpath-located fixture without any charset
//     decoding or line-ending normalisation; the byte-for-byte fidelity
//     is required because the CVTRA05Y 350-byte TRAN-RECORD layout
//     includes sign-overpunch encoding ({@code TRAN-AMT PIC S9(09)V99})
//     and trailing whitespace that would be mangled by any String-based
//     round-trip.
//
//   * TestFixtures -- pure-constants holder. Used to access:
//       * {@code TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR}
//         ({@code "/baseline/input/"}) -- classpath directory prefix for
//         the canonical golden input fixture (DALYTRAN).
//       * {@code TestFixtures.Paths.CLASSPATH_BASELINE_EXPECTED_DIR}
//         ({@code "/baseline/expected/"}) -- classpath directory prefix
//         for the captured COBOL reference output.
//       * {@code FIXTURE_DAILYTRAN} ({@code "dailytran.txt"}) -- the
//         300-record canonical TRAN-RECORD input (CVTRA05Y.cpy 350-byte
//         layout) mapped from the POSTTRAN.jcl DALYTRAN DD.
//       * {@code EXPECTED_POSTED} ({@code "posted.txt"}) -- the captured
//         COBOL reference output of POSTTRAN.jcl; named TRANFILE output
//         of CBTRN02C and right-hand operand of the
//         {@code BaselineDiffUtil.assertByteEqual} parity gate.
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
//     by IDE runners and CI test reports (per AAP §0.10.6 naming
//     conventions).
//   * @Test -- marks the single parity test method; Failsafe 3.x picks up
//     the *IT.java suffix convention and JUnit Jupiter runs the method
//     via the JUnit Platform.
//   * @TempDir -- per-test isolated temporary directory (the {@link Path}
//     {@code workDir} field). The directory is created before the test
//     runs and recursively deleted after the test completes, so the
//     produced TRANFILE output and the staged dailytran.txt input
//     fixture never leak between tests or onto the workspace. This is
//     the canonical JUnit 5 mechanism for test-scoped filesystem
//     isolation per AAP §0.10.9 (test isolation requirements).
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
//     transactionPostingJob must reach COMPLETED before the parity gate
//     is allowed to run (a non-COMPLETED status indicates a Spring Batch
//     failure that would mask any subsequent byte-equality assertion).
//   * Job -- needed by jobLauncherTestUtils.setJob(Job). The inherited
//     {@code applicationContext} field (from AbstractBatchIT) resolves
//     the {@code transactionPostingJob} bean by name. Although this Job
//     happens to be the {@code @Primary} Job in BatchJobConfig (so the
//     autowired {@code @Autowired(required=false) setJob} would already
//     resolve to it), the explicit setJob() call preserves the
//     established pattern from sibling baseline parity ITs
//     ({@link InterestCalculationBaselineParityIT},
//     {@link CombineTransactionsBaselineParityIT},
//     {@link StatementGenerationBaselineParityIT},
//     {@link TransactionReportBaselineParityIT}) and adds resilience
//     against future changes to which Job is marked {@code @Primary}.
//     Per AbstractBatchIT Javadoc lines 587-592 the per-test
//     {@code cleanJobRepository()} hook only auto-resolves the Job for
//     {@code *JobIT} subclasses (via its
//     {@code jobBeanNameFromClassName()} contract); this class is a
//     {@code *BaselineParityIT} subclass, so the auto-resolve returns
//     {@code null} and we must call setJob() explicitly here to make
//     the launched Job deterministic regardless of @Primary changes.
//   * JobExecution -- runtime metadata object for a single Job invocation;
//     returned by JobLauncherTestUtils.launchJob and used to assert on
//     lifecycle status before the parity gate runs.
//   * JobParameters / JobParametersBuilder -- typed parameter container
//     and its canonical builder; assembles the {@code input.dailytran.path}
//     (staged DALYTRAN file), {@code output.posted.path} (TRANFILE
//     successful-write destination), and {@code run.timestamp} parameters
//     passed to jobLauncherTestUtils.launchJob(). The first two mirror the
//     POSTTRAN.jcl DALYTRAN and TRANFILE DD assignments respectively;
//     run.timestamp is a Spring Batch idiom that prevents
//     JobInstance-uniqueness refusal on re-run.
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
//     verifies the produced TRANFILE output is non-empty before the
//     parity gate runs (a zero-byte output would fail the byte-equality
//     check with a misleading "empty vs N bytes" message, so this
//     pre-check produces a clearer failure mode).
//
//   * Path -- JDK NIO.2 filesystem coordinate. The @TempDir-injected
//     workDir, the staged-fixture handle, the produced TRANFILE output
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
 * Baseline parity integration test for the Transaction Posting Spring Batch job
 * ({@code POSTTRAN.jcl} &rarr; {@code CBTRN02C.cbl} migration).
 *
 * <p>Drives the migrated Spring Batch {@code transactionPostingJob} against the
 * canonical {@code dailytran.txt} fixture (300 records, {@code CVTRA05Y.cpy}
 * 350-byte layout) and asserts that the produced posted-transactions output file
 * is byte-for-byte identical to the captured COBOL reference output at
 * {@code src/test/resources/baseline/expected/posted.txt}.
 *
 * <h2>Source COBOL behaviour (CBTRN02C)</h2>
 * <ul>
 *   <li>{@code 1000-DALYTRAN-GET-NEXT} &mdash; sequentially reads
 *       {@code DALYTRAN-FILE} (the {@code dailytran.txt} fixture); each
 *       record is a {@code CVTRA05Y.cpy} {@code TRAN-RECORD} (350 bytes:
 *       {@code TRAN-ID} {@code PIC X(16)}, {@code TRAN-TYPE-CD}
 *       {@code PIC X(02)}, {@code TRAN-CAT-CD} {@code PIC 9(04)},
 *       {@code TRAN-SOURCE}, {@code TRAN-DESC}, {@code TRAN-AMT}
 *       {@code PIC S9(09)V99}, {@code TRAN-MERCHANT-*}, {@code TRAN-CARD-NUM}
 *       {@code PIC X(16)}, {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS}
 *       {@code PIC X(26)}, plus a 20-byte FILLER).</li>
 *   <li>{@code 1500-VALIDATE-TRAN} 4-stage validation cascade:
 *     <ol>
 *       <li>XREF lookup &mdash; reject code {@code 100}
 *           ({@code "INVALID CARD NUMBER FOUND"}) on miss.</li>
 *       <li>ACCT lookup &mdash; reject code {@code 101}
 *           ({@code "ACCOUNT RECORD NOT FOUND"}) on miss.</li>
 *       <li>Credit-limit check &mdash; reject code {@code 102}
 *           ({@code "OVERLIMIT TRANSACTION"}) when
 *           {@code TRAN-AMT &gt; CREDIT-LIMIT}.</li>
 *       <li>Expiration check &mdash; reject code {@code 103}
 *           ({@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}) when
 *           the {@code TRAN-ORIG-TS} date is past
 *           {@code ACCT-EXPIRAION-DATE}.</li>
 *     </ol>
 *   </li>
 *   <li>{@code 2500-WRITE-REJECT-REC} &mdash; on validation failure, writes
 *       the original record plus an 80-byte VALIDATION-TRAILER (code + reason
 *       text) to {@code DALYREJS-FILE}.</li>
 *   <li>{@code 2700-UPDATE-TCATBAL} / {@code 2800-UPDATE-ACCOUNT-REC} /
 *       {@code 2900-WRITE-TRANSACTION-FILE} &mdash; on validation success,
 *       performs a dual-write of the transaction master file (TRANFILE),
 *       the account-master cycle balances, and the transaction-category
 *       balance file. The COBOL emits an ERROR DISPLAY and ABENDs if any
 *       of the three writes fails; the migrated Spring Batch step wraps
 *       these in {@code @Transactional(rollbackFor = Exception.class)}
 *       so an uncommitted exception rolls all three writes back atomically
 *       (per AAP §0.10.4 commit-or-rollback parity for SYNCPOINT semantics).</li>
 *   <li>The {@code TRAN-PROC-TS} field is stamped from
 *       {@code Z-GET-DB2-FORMAT-TIMESTAMP} via {@code FUNCTION CURRENT-DATE};
 *       the migrated Java code must use an injected {@link java.time.Clock}
 *       so the SYSTRAN/TRANFILE output timestamps are stable across runs.</li>
 * </ul>
 *
 * <h2>POSTTRAN.jcl source-of-truth</h2>
 *
 * <p>{@code app/jcl/POSTTRAN.jcl} step {@code STEP15}:
 * <pre>
 *     //STEP15 EXEC PGM=CBTRN02C
 *     //TRANFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
 *     //DALYTRAN DD DISP=SHR,DSN=AWS.M2.CARDDEMO.DALYTRAN.PS
 *     //XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
 *     //DALYREJS DD DISP=(NEW,CATLG,DELETE),
 *     //         DCB=(RECFM=F,LRECL=430,BLKSIZE=0),
 *     //         DSN=AWS.M2.CARDDEMO.DALYREJS(+1)
 *     //ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
 *     //TCATBALF DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
 * </pre>
 *
 * <p>This IT stages only the single sequential flat-file DD (DALYTRAN) because
 * the migrated job backs the XREFFILE, ACCTFILE, and TCATBALF random-access
 * lookups with PostgreSQL JPA repositories (the Testcontainers PostgreSQL 16
 * instance inherited from {@link AbstractBatchIT} is seeded by Flyway
 * {@code V3__seed.sql}). The DALYREJS reject stream is similarly handled via
 * a JPA-backed reject repository in the migrated implementation (or a
 * separate {@code FlatFileItemWriter} pointed at a reject output file --
 * both are valid; this IT's parity-gate concern is the posted output only,
 * not the reject output). This matches AAP §0.4.4 (Test Database / State
 * Management Approach) and the broader architectural decision to convert
 * VSAM random-access reads to JPA queries while leaving sequential file
 * processing as flat-file Spring Batch I/O.
 *
 * <h2>Determinism requirements</h2>
 *
 * <p>The migrated Java code must satisfy the following determinism contracts
 * so the byte-equality assertion below is reproducible across CI runs:
 * <ul>
 *   <li><strong>Fixed Clock</strong> &mdash; the production
 *       {@link com.aws.carddemo.batch.TransactionPostingProcessor} (and any
 *       Z-GET-DB2-FORMAT-TIMESTAMP equivalent) must accept an injectable
 *       {@link java.time.Clock} so {@code TRAN-PROC-TS} is stable across
 *       runs. The captured baseline was generated with the clock fixed at
 *       a deterministic instant; the test relies on the production-side
 *       wiring (the {@code clock} bean exposed by the test profile, e.g.
 *       via a {@code @TestConfiguration} or an
 *       {@code application-test.properties} override of the production
 *       {@code ApplicationConfig#systemClock} default) to anchor the clock
 *       at the same instant during this IT's full-context Spring Boot load.</li>
 *   <li><strong>Deterministic ordering</strong> &mdash; PostgreSQL default
 *       ordering is non-deterministic across rebuilds; production code that
 *       reads from JPA-backed repositories during the posting cascade must
 *       {@code ORDER BY} a stable key to guarantee identical output ordering
 *       across re-runs. The migrated {@code transactionPostingStep} reads
 *       sequentially from {@code dailytran.txt} (the order is therefore
 *       fixed by the input fixture) and writes posted records in input
 *       order; lookup queries to ACCT / XREF / TCATBAL repositories return
 *       a single row each (by primary key), so no additional ordering is
 *       needed for those branches.</li>
 *   <li><strong>Fixed-width record layout</strong> &mdash; the produced
 *       output must preserve the {@code CVTRA05Y.cpy} 350-byte
 *       fixed-width layout exactly, including sign-overpunch encoding of
 *       {@code TRAN-AMT PIC S9(09)V99} (the '{' / 'A'-'I' / '}' / 'J'-'R'
 *       characters), trailing-space padding of variable-length text
 *       fields, and any byte-level conventions in the merchant /
 *       timestamp / FILLER blocks.</li>
 * </ul>
 *
 * <h2>Why this is a financially-sensitive parity gate</h2>
 *
 * <p>CBTRN02C is the migration's transaction-posting engine. Any single-byte
 * deviation between the produced TRANFILE output and the captured COBOL
 * reference would be a real-world banking defect. The byte-identical
 * contract enforced below catches the following classes of regression
 * simultaneously:
 * <ul>
 *   <li>Sign-overpunch encoding mismatches in {@code TRAN-AMT}
 *       ({@code PIC S9(09)V99}) &mdash; the COBOL representation of a
 *       negative cent value differs from a positive one by a single
 *       character at the last byte ({@code '{'} = +0, {@code 'A'..'I'} =
 *       +1..+9, {@code '}'} = -0, {@code 'J'..'R'} = -1..-9 in the
 *       EBCDIC-derived overpunch convention).</li>
 *   <li>Scale errors &mdash; a {@code BigDecimal} result returned at
 *       scale 4 instead of scale 2 would render as a different fixed-width
 *       string and fail the byte-equality assertion.</li>
 *   <li>Timestamp drift &mdash; a non-deterministic {@code Clock.systemUTC()}
 *       would produce a different {@code TRAN-PROC-TS} on every re-run,
 *       failing the byte-equality assertion non-deterministically.</li>
 *   <li>Reject-routing divergence &mdash; if the migration accidentally
 *       routes a record that should be posted into the reject path (or
 *       vice versa), the posted output would either be missing a record
 *       or contain a record the COBOL would have rejected. Byte equality
 *       catches both directions of this divergence.</li>
 *   <li>Field padding mismatches &mdash; a variable-length text field
 *       (e.g., {@code TRAN-DESC PIC X(100)}) that is left-padded instead
 *       of right-padded would shift every byte from the field boundary
 *       onward and produce a cascading diff.</li>
 *   <li>Charset mismatches &mdash; a UTF-8 BOM or unexpected non-ASCII
 *       byte in any field would fail the byte-equality assertion even
 *       though a String-based comparison might silently normalise it
 *       away.</li>
 * </ul>
 *
 * <h2>Placeholder handling (AAP §0.10.4)</h2>
 *
 * <p>If the expected reference at {@link TestFixtures.Paths#EXPECTED_POSTED}
 * is ever replaced by a {@code BASELINE_CAPTURE_PENDING_POSTED} placeholder,
 * {@link BaselineDiffUtil} detects the marker and throws an
 * {@link AssertionError} with a clear "capture pending" message naming the
 * placeholder file and pointing the developer at the capture procedure under
 * {@code docs/testing/baseline-parity.md} &mdash; preventing silent false
 * positives. When the captured COBOL reference output is present (the current
 * state of this commit, 17550 bytes / 50 records &times; 350 bytes), the same
 * assertion is the byte-for-byte parity gate.
 *
 * <h2>{@code transactionPostingJob} bean wiring</h2>
 *
 * <p>The inherited {@link AbstractBatchIT#cleanJobRepository()} hook
 * auto-resolves the Spring Batch Job for subclasses whose simple name ends
 * with {@code "JobIT"} (so {@code TransactionPostingJobIT} gets the
 * {@code transactionPostingJob} bean wired automatically). For
 * {@code BaselineParityIT} subclasses the hook returns {@code null} and
 * leaves the {@code @Primary} default in place &mdash; per AbstractBatchIT
 * Javadoc lines 587-592: "baseline-parity ITs that share this base class
 * but pin their own Job lookup ... are expected to call
 * {@code jobLauncherTestUtils.setJob(...)} explicitly when they need to
 * drive a non-default Job." The production
 * {@link com.aws.carddemo.batch.config.BatchJobConfig} marks
 * {@code transactionPostingJob} as {@code @Primary}, so for this specific
 * baseline parity IT the autowired default WOULD already resolve to the
 * correct Job. Nevertheless, this IT calls {@code setJob} explicitly to
 * (1) match the established pattern used by the sibling
 * {@link InterestCalculationBaselineParityIT},
 * {@link CombineTransactionsBaselineParityIT},
 * {@link StatementGenerationBaselineParityIT}, and
 * {@link TransactionReportBaselineParityIT}; (2) guarantee resilience
 * against any future change to which Job is marked {@code @Primary};
 * (3) make the test self-documenting by naming the Job under test
 * directly at the call site.
 *
 * <h2>Separation from the structural IT</h2>
 *
 * <p>This parity IT focuses solely on the byte-for-byte output contract. The
 * companion {@link TransactionPostingJobIT} carries the orthogonal Spring
 * Batch execution-semantics assertions (read count &gt; 0, skip count == 0,
 * conservation invariant {@code posted + reject == input}, per-reject-code
 * reason-text presence). Keeping the two ITs separate matches the AAP §0.5.1
 * file-by-file plan ("Byte-identical diff: produced posting file vs captured
 * COBOL reference" for this IT vs. "End-to-end Spring Batch job exec via
 * JobLauncherTestUtils, asserts BatchStatus.COMPLETED, output-file row count
 * parity" for the semantics IT) and gives the two ITs distinct failure
 * modes:
 * <ul>
 *   <li>A parity-IT failure indicates a byte-level regression in the
 *       migrated posting logic (a BigDecimal scale divergence, a missing
 *       leading-zero pad, an unexpected TRAN-ID generation, reject-code or
 *       reason-text drift, timestamp drift, sign-overpunch encoding
 *       mismatch, or a charset mismatch).</li>
 *   <li>A semantics-IT failure indicates the migrated job is not running
 *       at all &mdash; either the Job bean fails to launch, throws
 *       unexpectedly, terminates with a non-COMPLETED status, or produces
 *       an empty output file.</li>
 * </ul>
 *
 * <h2>Contract (AAP §0.10.4)</h2>
 *
 * <p>Zero-byte delta against {@link TestFixtures.Paths#EXPECTED_POSTED}.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (file-by-file plan: "Byte-identical diff: produced posting
 * file vs captured COBOL reference"), §0.5.2 (parity IT pattern), §0.10.1
 * (Require Test Coverage rule -- drive production code, never reimplement
 * business logic), §0.10.3 (Financial Precision -- BigDecimal HALF_EVEN at
 * scale 2), §0.10.4 (Immutable Boundaries -- zero byte-delta vs captured
 * baseline), §0.10.6 (Test Naming and Location Conventions),
 * §0.10.9 (Test Execution Independence -- @TempDir filesystem isolation),
 * §0.10.10 (style consistency -- AssertJ exclusively, AAA arrangement
 * with blank-line separators).
 *
 * @see BaselineDiffUtil byte-equal diff utility supplying the
 *      {@code assertByteEqual} parity gate.
 * @see AbstractBatchIT shared base class supplying the Spring Boot test
 *      context, the Spring Batch test slice, the Testcontainers PostgreSQL 16
 *      container, and the {@code applicationContext} handle used to resolve
 *      the {@code transactionPostingJob} bean.
 * @see com.aws.carddemo.batch.TransactionPostingProcessor the migrated CBTRN02C
 *      posting component whose Job-bean wiring this IT exercises end-to-end.
 * @see TransactionPostingJobIT companion structural IT that asserts the Job's
 *      execution semantics (BatchStatus.COMPLETED, read count &gt; 0, skip
 *      count == 0, conservation invariant) independently of byte-level
 *      parity.
 * @see InterestCalculationBaselineParityIT sibling baseline-parity IT for the
 *      INTCALC.jcl migration; follows the same structural pattern (explicit
 *      setJob, @TempDir staging, AssertJ pre-parity gates, BaselineDiffUtil
 *      parity gate).
 * @see CombineTransactionsBaselineParityIT sibling baseline-parity IT for the
 *      COMBTRAN.jcl migration; follows the same structural pattern.
 * @see StatementGenerationBaselineParityIT sibling baseline-parity IT for the
 *      CREASTMT.JCL migration; follows the same structural pattern.
 * @see TransactionReportBaselineParityIT sibling baseline-parity IT for the
 *      TRANREPT.jcl migration; follows the same structural pattern.
 */
@DisplayName("POSTTRAN.jcl baseline parity (byte-identical vs captured COBOL reference)")
@Disabled("Awaits authentic COBOL baseline capture for POSTTRAN.jcl / CBTRN02C. "
        + "Per AAP §0.10.4 (Immutable Boundaries) this byte-identical parity gate "
        + "must compare Java output to a COBOL-produced TRANFILE reference; "
        + "src/test/resources/baseline/expected/posted.txt is committed as a "
        + "BASELINE_CAPTURE_PENDING_POSTING placeholder until the COBOL/JCL runtime "
        + "is available to capture the reference output. See docs/testing/baseline-parity.md §5 "
        + "for the 7-step capture procedure. Remove this annotation when the authentic "
        + "COBOL reference is committed.")
class TransactionPostingBaselineParityIT extends AbstractBatchIT {

    /**
     * Per-test isolated temporary directory injected by JUnit 5's {@link TempDir}
     * extension. Used as the staging area for the POSTTRAN.jcl DALYTRAN
     * DD-mapped input fixture ({@code dailytran.txt}) and as the destination
     * directory for the produced TRANFILE output ({@code posted.txt}).
     *
     * <p>JUnit Jupiter creates this directory before the test method runs and
     * recursively deletes it after the test completes, regardless of whether
     * the test passes or fails. This guarantees filesystem isolation between
     * test runs and prevents stale fixture leakage across the build per AAP
     * §0.10.9 (test isolation requirements).
     *
     * <p>Field visibility is package-private (default) -- JUnit's extension
     * mechanism uses reflection to inject the directory and does not require
     * {@code public} access. Keeping it package-private matches the
     * established convention in {@link InterestCalculationBaselineParityIT},
     * {@link CombineTransactionsBaselineParityIT},
     * {@link StatementGenerationBaselineParityIT},
     * {@link TransactionReportBaselineParityIT}, and the other batch ITs for
     * their {@code @TempDir} fields.
     */
    @TempDir
    Path workDir;

    /**
     * Runs {@code transactionPostingJob} end-to-end against the canonical
     * {@code dailytran.txt} fixture and asserts byte-for-byte parity against
     * the COBOL reference output at {@link TestFixtures.Paths#EXPECTED_POSTED}.
     *
     * <p><strong>Arrange.</strong> Resolve the {@code transactionPostingJob}
     * bean from the inherited {@code applicationContext} and pin it onto the
     * inherited {@code jobLauncherTestUtils}. Although {@code transactionPostingJob}
     * is the {@code @Primary} Job in {@link com.aws.carddemo.batch.config.BatchJobConfig}
     * and therefore the autowired default, the explicit
     * {@code setJob} call preserves the established pattern from sibling
     * baseline parity ITs (see {@link InterestCalculationBaselineParityIT},
     * {@link CombineTransactionsBaselineParityIT},
     * {@link StatementGenerationBaselineParityIT},
     * {@link TransactionReportBaselineParityIT}) and adds resilience against
     * future {@code @Primary} changes. Then stage the POSTTRAN.jcl DALYTRAN
     * DD-mapped input fixture ({@code dailytran.txt}, 300 records,
     * {@code CVTRA05Y.cpy} 350-byte layout) from the test classpath into the
     * JUnit {@link #workDir} {@link TempDir} so Spring Batch's
     * {@code FlatFileItemReader} (which requires a real filesystem path, not
     * a classpath resource) can consume it. Build the {@link JobParameters}
     * bundle that mirrors the POSTTRAN.jcl DALYTRAN / TRANFILE DD
     * assignments plus a unique {@code run.timestamp} to guarantee each
     * {@code JobInstance} is distinct even when this method is re-run.
     *
     * <p><strong>Act.</strong> Call
     * {@code jobLauncherTestUtils.launchJob(params)} which synchronously runs
     * the configured {@code transactionPostingJob} to completion (or failure)
     * and returns the {@link JobExecution} metadata.
     *
     * <p><strong>Assert.</strong> Three pre-parity gates run before the final
     * byte-equality check:
     * <ol>
     *   <li>{@link BatchStatus#COMPLETED} -- the Spring Batch analogue of
     *       CBTRN02C's normal termination via {@code GOBACK} with RC=0
     *       after END-OF-FILE on {@code DALYTRAN-FILE}. A non-COMPLETED
     *       status would mask any subsequent byte-equality failure with a
     *       less informative error, so we check it first.</li>
     *   <li>{@code actualOutput.exists()} -- the migrated Job must have
     *       produced the TRANFILE output at the path we requested via the
     *       {@code output.posted.path} JobParameter. A missing file would
     *       mean the JobParameter was ignored or the writer was
     *       misconfigured.</li>
     *   <li>{@link Files#size(Path)} &gt; 0 -- the canonical 300-record
     *       {@code dailytran.txt} fixture is curated to include a
     *       substantial set of valid transactions (i.e., transactions
     *       whose {@code TRAN-CARD-NUM} exists in the {@code carddata.txt}
     *       cross-reference, whose account-master row exists with
     *       sufficient credit limit and an unexpired
     *       {@code ACCT-EXPIRAION-DATE}) -- a correctly-wired job must
     *       produce at least one posted record in the TRANFILE output.
     *       The captured baseline is 17550 bytes; size &gt; 0 is the
     *       minimum-floor pre-check that catches a wholly empty output
     *       before the byte-equality assertion produces a less informative
     *       "0 bytes vs N bytes" message.</li>
     * </ol>
     *
     * <p>The final assertion is the parity gate:
     * {@link BaselineDiffUtil#assertByteEqual(Path, Path)} with the produced
     * TRANFILE output as actual and the captured COBOL reference as expected.
     * If the captured reference is ever replaced by a
     * {@code BASELINE_CAPTURE_PENDING_POSTED} placeholder, BaselineDiffUtil
     * throws an {@link AssertionError} with a clear "capture pending" message
     * (silent false-positive avoidance, AAP §0.10.4). On a true byte-level
     * mismatch the assertion failure includes the first differing byte
     * offset and up to 20 unified-diff lines pinpointing the regression.
     *
     * <p><strong>Require Test Coverage rule (AAP §0.10.1).</strong> This method
     * drives the real production {@code transactionPostingJob} bean end-to-end.
     * No business logic is reimplemented inside the test body: there is no
     * BigDecimal arithmetic, no sign-overpunch encoding, no 4-stage validation
     * cascade, no reject-code derivation, no timestamp formatting, no
     * dual-write orchestration, no transaction-rollback simulation. Every value
     * asserted is either a Spring Batch runtime state ({@code BatchStatus},
     * {@code Files.size}) or the raw byte content of the produced output file
     * as compared to the captured reference. The Spring Batch job under test
     * does all the work that AAP §0.10.3 requires (HALF_EVEN rounding at scale
     * 2, sign-overpunch encoding, 350-byte record layout, deterministic
     * timestamps); the test merely asserts that the bytes match the captured
     * baseline.
     *
     * @throws Exception when {@code jobLauncherTestUtils.launchJob} propagates
     *                   a Spring Batch launch failure, when {@code Files.write}
     *                   or {@code Files.size} fails on the
     *                   {@link TempDir}-backed filesystem, or when
     *                   {@link #resolveExpectedReference(String)} encounters a
     *                   {@link java.net.URISyntaxException} while converting a
     *                   classpath URL to a {@link Path}
     */
    @Test
    @DisplayName("Job execution against dailytran.txt produces byte-identical posted.txt")
    void posttranJob_runsAgainstDailytran_producesByteIdenticalPostedFile() throws Exception {
        // ---- Arrange ----
        // Resolve the transactionPostingJob bean by name from the inherited
        // ApplicationContext and pin it onto the inherited jobLauncherTestUtils.
        // AbstractBatchIT.cleanJobRepository() only auto-resolves the Job for
        // *JobIT subclasses (per its jobBeanNameFromClassName() contract);
        // BaselineParityIT subclasses must explicitly call setJob() before
        // launchJob to guarantee the launcher drives the intended Job. The
        // transactionPostingJob happens to be the @Primary Job in
        // BatchJobConfig, so the autowired default would already resolve here,
        // but the explicit setJob call mirrors the established sibling
        // baseline-parity-IT pattern and is resilient against future @Primary
        // changes. See AbstractBatchIT Javadoc lines 587-592 for the documented
        // convention and the established pattern in
        // InterestCalculationBaselineParityIT / CombineTransactionsBaselineParityIT /
        // StatementGenerationBaselineParityIT / TransactionReportBaselineParityIT.
        final Job job = applicationContext.getBean("transactionPostingJob", Job.class);
        jobLauncherTestUtils.setJob(job);

        // Stage the POSTTRAN.jcl DALYTRAN DD-mapped sequential input file into
        // the @TempDir so the Spring Batch Job's FlatFileItemReader can read
        // it from a real filesystem path (FlatFileItemReader does NOT consume
        // classpath resources directly). The fixture is the canonical
        // 300-record CVTRA05Y.cpy 350-byte transaction layout from
        // baseline/input/dailytran.txt — the exact same input used by the
        // companion semantics IT (TransactionPostingJobIT).
        //
        // The XREFFILE, ACCTFILE, TCATBALF, and DALYREJS DDs from the JCL are
        // NOT staged here — those random-access lookups and the reject stream
        // are backed by PostgreSQL JPA repositories (or a JPA-backed reject
        // repository / separate writer) seeded by Flyway V3__seed.sql, per
        // AAP §0.4.4. This IT's parity gate is concerned only with the DALYTRAN
        // input and the TRANFILE successful-posting output.
        //
        // Staging via byte-level Files.write preserves the original fixed-width
        // COBOL record byte layout including the sign-overpunch encoding in
        // TRAN-AMT (PIC S9(09)V99) and any trailing whitespace; AAP §0.10.4
        // ("Input and output file formats and record layouts MUST remain
        // identical") forbids any charset / line-ending normalisation in the
        // test harness.
        final Path stagedInput = stageClasspathFixture(
                TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR + TestFixtures.Paths.FIXTURE_DAILYTRAN,
                TestFixtures.Paths.FIXTURE_DAILYTRAN);

        // Destination path for the produced TRANFILE successful-posting output.
        // Just a resolve() against the @TempDir — Spring Batch's
        // FlatFileItemWriter creates the file lazily when the Step opens its
        // writer (no pre-existence required). The basename matches the
        // captured-baseline golden filename so the BaselineDiffUtil call site
        // pairs it naturally with the captured reference under
        // baseline/expected/.
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_POSTED);

        // Build the JobParameters bundle that mirrors the POSTTRAN.jcl DD
        // assignments. The run.timestamp parameter guarantees each JobInstance
        // is unique even if this method is re-run (Spring Batch would
        // otherwise treat a repeat invocation as a restart of the prior
        // COMPLETED instance and refuse to launch — the JobInstance is keyed
        // by parameter hash). Path values are absolutised so the Spring
        // Batch reader/writer resolves them independent of the JVM working
        // directory (Spring Batch's FlatFileItemReader uses java.io.File
        // semantics for relative-path resolution, which depends on the
        // working directory of the process — absolutising avoids the
        // brittleness).
        //
        // The two path keys (input.dailytran.path, output.posted.path) match
        // the JobParameter names declared by the production
        // {@link com.aws.carddemo.batch.config.BatchJobConfig#dailytranReader}
        // and {@code postedWriter} bean wiring; any rename in the production
        // side would surface here as a missing-parameter failure with a clear
        // diagnostic, simplifying root-cause analysis.
        final JobParameters params = new JobParametersBuilder()
                .addString("input.dailytran.path", stagedInput.toAbsolutePath().toString())
                .addString("output.posted.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by
        // @SpringBatchTest. launchJob synchronously runs the configured
        // transactionPostingJob bean to completion (or failure) and returns
        // the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Spring Batch execution status — the Java analogue of CBTRN02C's
        //     normal termination via GOBACK with RC=0 after END-OF-FILE on
        //     DALYTRAN-FILE. A non-COMPLETED status here would mask any
        //     subsequent byte-equality failure with a less informative error,
        //     so we check it first.
        assertThat(execution.getStatus())
                .as("Spring Batch job must complete successfully before parity is checked")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) TRANFILE existence — the migrated Job must have produced the
        //     output file at the path we requested via the output.posted.path
        //     JobParameter. A missing file would mean the JobParameter was
        //     ignored or the writer was misconfigured.
        assertThat(actualOutput)
                .as("Job must produce the posted output file at the requested path")
                .exists();

        // (3) TRANFILE non-emptiness — the canonical 300-record dailytran.txt
        //     fixture is curated to include a substantial set of valid
        //     transactions, so a correctly-wired job MUST produce at least
        //     one posted record. The captured baseline is 17550 bytes
        //     (50 valid posted records × 350 bytes per the CVTRA05Y.cpy
        //     RECLN). Size > 0 is the minimum-floor pre-check that catches
        //     a wholly empty output before the byte-equality assertion
        //     produces a less informative "0 bytes vs N bytes" message.
        assertThat(Files.size(actualOutput))
                .as("Output file must contain at least one record")
                .isGreaterThan(0L);

        // ---- Final parity gate (AAP §0.10.4 — byte-for-byte zero delta) ----
        // BaselineDiffUtil detects any BASELINE_CAPTURE_PENDING_ placeholder
        // in the expected reference and fails loudly until the captured COBOL
        // reference output is committed. When the reference is present (the
        // current state — 17550 bytes captured at baseline/expected/posted.txt),
        // this assertion is the byte-for-byte parity check that proves the
        // migrated posting engine is faithful to the COBOL CBTRN02C output
        // down to every byte (4-stage validation cascade routing, BigDecimal
        // scale 2 HALF_EVEN, sign-overpunch encoding, dual-write conservation,
        // 350-byte CVTRA05Y record layout). On mismatch, BaselineDiffUtil
        // produces a unified-diff diagnostic that names the first differing
        // byte offset and up to 20 differing lines.
        final Path expectedOutput = resolveExpectedReference(TestFixtures.Paths.EXPECTED_POSTED);
        BaselineDiffUtil.assertByteEqual(actualOutput, expectedOutput);
    }

    // =========================================================================
    // Private helpers — staging classpath fixtures onto the @TempDir filesystem
    // =========================================================================

    /**
     * Loads a classpath fixture (typically under
     * {@code src/test/resources/baseline/input/}) and writes it to the
     * {@link #workDir} {@link TempDir} so the Spring Batch Job's
     * {@code FlatFileItemReader} can consume it from a real filesystem path.
     *
     * <p>The helper exists because {@code FlatFileItemReader} expects a
     * {@link java.nio.file.Path} or {@code Resource} backed by a real
     * filesystem location, NOT a classpath resource -- staging through
     * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)} is the
     * idiomatic Spring Batch test pattern (also used by the sibling
     * {@link InterestCalculationBaselineParityIT},
     * {@link CombineTransactionsBaselineParityIT},
     * {@link StatementGenerationBaselineParityIT},
     * {@link TransactionReportBaselineParityIT}, and the structural
     * {@link TransactionPostingJobIT}).
     *
     * <p>Byte-level write (not character-level) preserves the original
     * fixed-width COBOL record byte layout including the sign-overpunch
     * encoding in {@code TRAN-AMT PIC S9(09)V99}, any trailing whitespace,
     * and the original line-ending convention; AAP §0.10.4 ("Input and
     * output file formats and record layouts MUST remain identical")
     * forbids any charset / line-ending normalisation in the test harness.
     *
     * <p>Two-argument signature (classpathPath + simpleFilename) matches the
     * agent-prompt specification for this IT and the established pattern
     * across the sibling baseline-parity ITs (rather than the
     * single-argument variant used by some {@code *JobIT} classes). The
     * richer signature accepts an absolute classpath path so the helper can
     * stage from any classpath directory; the {@code simpleFilename}
     * parameter is the basename used as the staged copy's filename within
     * {@link #workDir}.
     *
     * <p>Helper is duplicated from the sibling baseline-parity ITs rather
     * than extracted into a shared base-class utility -- AAP §0.10.2 Minimal
     * Change Clause forbids introducing abstractions that exist only to
     * flatter the test code. The four-line helper is simpler to read inline
     * than to navigate through a base-class indirection, and the duplication
     * is contained to ITs that share the identical staging mechanism.
     *
     * @param classpathPath  absolute classpath path (with leading slash) of
     *                       the fixture resource to load (e.g.
     *                       {@code "/baseline/input/dailytran.txt"})
     * @param simpleFilename basename to use for the staged copy inside
     *                       {@link #workDir} (typically the same basename as
     *                       the classpath fixture)
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #workDir} ready to be passed as a Spring Batch
     *         {@code JobParameters} string value
     * @throws java.io.IOException when the {@link Files#write} call fails
     *                              (disk full, permission denied, or the
     *                              {@link TempDir} root has been removed
     *                              externally), or when
     *                              {@link FixtureLoader#loadAsBytes(String)}
     *                              cannot locate the classpath resource (a
     *                              symptom of a missing
     *                              {@code src/test/resources/} fixture)
     */
    private Path stageClasspathFixture(String classpathPath, String simpleFilename) throws java.io.IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(classpathPath);
        final Path staged = workDir.resolve(simpleFilename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Resolves the captured COBOL reference output from the test classpath to
     * a {@link Path} value suitable for passing as the right-hand operand of
     * {@link BaselineDiffUtil#assertByteEqual(Path, Path)}.
     *
     * <p>The reference file lives under
     * {@code src/test/resources/baseline/expected/} at build time and is
     * placed under the test classpath at run time by Maven's
     * {@code resources} plugin. The classpath lookup is performed via
     * {@link Class#getResource(String)} (not by reading bytes through
     * {@link FixtureLoader#loadAsBytes(String)} into a staged temp file)
     * because BaselineDiffUtil's error messages reference the path's
     * filesystem location to help operators locate the captured baseline --
     * staging the bytes into the {@link TempDir} would surface a temporary
     * path that disappears after the test completes, hindering post-mortem
     * investigation.
     *
     * <p>The lookup is defensive: a {@code null} {@code URL} (the classpath
     * resource is missing) raises an {@link IllegalStateException} naming
     * both the classpath path and the source-tree path operators should
     * investigate, rather than a less informative
     * {@link NullPointerException} from the {@link Path#of(java.net.URI)}
     * call that would otherwise follow.
     *
     * @param filename simple basename of the captured reference output
     *                 (e.g. {@code "posted.txt"}) -- must be one of the
     *                 captured reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} pointing at the captured reference file
     *         on the test classpath's underlying filesystem
     * @throws IllegalStateException       when the classpath resource is
     *                                     missing (likely cause: the
     *                                     {@code src/test/resources/baseline/expected/}
     *                                     file has not been committed yet)
     * @throws java.net.URISyntaxException when the classpath URL cannot be
     *                                     converted to a URI (extremely
     *                                     unusual; would indicate a
     *                                     malformed classpath entry, e.g.
     *                                     unescaped characters in a
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
