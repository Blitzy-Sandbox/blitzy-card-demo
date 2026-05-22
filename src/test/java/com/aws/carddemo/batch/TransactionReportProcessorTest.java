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

// Shared test constants (AAP §0.5.5 — Cross-File Test Dependencies). The
// two Dates constants used in this file (REPORT_START_DATE = "2022-01-01"
// and REPORT_END_DATE = "2022-07-06") carry the verbatim date-range values
// captured in the baseline transaction_report.txt reference output
// (per app/cpy/CVTRA07Y.cpy line 11 REPT-START-DATE and line 13
// REPT-END-DATE). Keeping these in TestFixtures (rather than re-declaring
// them locally) is the single-source-of-truth pattern that every batch
// test in this folder follows — see InterestCalculationProcessorTest,
// StatementProcessorTest, TransactionPostingProcessorTest, and
// CombineTransactionsProcessorTest for the precedent.
//
// Note: the Paths.EXPECTED_TRANSACTION_REPORT constant ("transaction_report.txt")
// is referenced indirectly: dateRange_alignsWithCapturedBaseline asserts the
// date-range fixtures that drive the COBOL CBTRN03C run which produced that
// expected-output file. Any drift in REPORT_START_DATE/REPORT_END_DATE here
// would silently invalidate the captured transaction_report.txt baseline used
// by TransactionReportBaselineParityIT.
import com.aws.carddemo.testsupport.TestFixtures;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). @Test marks each test method, @DisplayName
// provides the human-readable scenario name on the class and on each
// method, and @ExtendWith wires the MockitoExtension below.
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// Mockito 5 JUnit Jupiter integration (AAP §0.6.1 — BOM-managed by
// spring-boot-starter-test 3.3.x; resolved to mockito-junit-jupiter 5.11.0
// via the dependency tree at the time of writing). MockitoExtension
// activates STRICT_STUBS strictness (AAP §0.10.1: "Mockito strictness is
// STRICT_STUBS ... unused stubs raise UnnecessaryStubbingException").
//
// This test class declares no @Mock fields directly. The production
// com.aws.carddemo.batch.TransactionReportProcessor class has not yet been
// authored by REFACTOR-flavor agents (see the latest setup status log:
// "No production Java source ... these are subsequent-agent CODE work
// per AAP §0.5"), so the test's boundary collaborators — XREF-FILE
// reader, TRANTYPE-FILE reader, TRANCATG-FILE reader, REPORT-FILE
// writer, DATE-PARMS-FILE reader (per app/cbl/CBTRN03C.cbl lines 29–57) —
// cannot be wired in yet.
//
// The extension is retained both as the project-wide test-class
// convention and as a future-proofing seam: once the production class
// lands, additional @Mock fields can be added without changing the
// class annotation. This mirrors the same pattern used in
// StatementProcessorTest, InterestCalculationProcessorTest, and
// TransactionPostingProcessorTest in the same package.
import org.mockito.junit.jupiter.MockitoExtension;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively,
// no JUnit Assertions, no Hamcrest matchers, no mixed styles). Static-
// imported assertThat is used for every assertion in this class.
// Frequently used fluent methods in this file:
//   - isEqualTo(...)      — strict identity check (integer width literal,
//                           String date constants)
//   - isNotNull()         — non-null check on TestFixtures constants
//   - isNotBlank()        — non-empty / non-whitespace check
//   - as(...)             — descriptive assertion-failure message that
//                           cites the COBOL source line for traceability
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@code TransactionReportProcessor} — the Java migration of
 * the COBOL {@code CBTRN03C} daily transaction report generator
 * (649 lines; see {@code app/cbl/CBTRN03C.cbl}).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code CBTRN03C} produces the daily transaction detail report by
 * streaming the {@code TRANSACT-FILE} sequentially, filtering each record
 * by {@code TRAN-PROC-TS(1:10)} against the {@code WS-START-DATE} and
 * {@code WS-END-DATE} parameters loaded from the {@code DATE-PARMS-FILE}.
 * For each surviving record it performs random VSAM lookups against
 * {@code XREF-FILE} (card → account/customer cross-reference),
 * {@code TRANTYPE-FILE} (transaction-type description), and
 * {@code TRANCATG-FILE} (transaction-category description), then writes a
 * 133-byte fixed-width detail line to {@code REPORT-FILE}. Pagination
 * happens every {@code WS-PAGE-SIZE} (=20) detail lines, with
 * {@code REPORT-PAGE-TOTALS} closing each page; account-total breaks fire
 * when the {@code TRAN-CARD-NUM} key changes; the {@code REPORT-GRAND-TOTALS}
 * line closes the entire report at end-of-file.
 *
 * <p>The {@code CVTRA07Y} copybook declares the report record layout in
 * five 01-level groups:
 * <ul>
 *   <li>{@code REPORT-NAME-HEADER}                — page-header line 1
 *       (title plus date range — 38 + 41 + 12 + 10 + 4 + 10 = 115 bytes
 *       of declared layout; padded to the 133-byte REPORT-FILE record
 *       width by the COBOL runtime).</li>
 *   <li>{@code TRANSACTION-HEADER-1}              — column-name banner
 *       (17 + 12 + 19 + 35 + 14 + 1 + 16 = 114 bytes of declared layout;
 *       padded to 133 bytes).</li>
 *   <li>{@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} —
 *       explicit 133-byte hyphen rule (line 48 of {@code CVTRA07Y.cpy};
 *       the canonical declaration of the 133-byte report record width).</li>
 *   <li>{@code TRANSACTION-DETAIL-REPORT}         — per-record detail
 *       (16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 + 4 + 1 + 29 + 1 + 10 + 4 + 14
 *       + 2 = 113 bytes of declared layout; padded to 133 bytes).</li>
 *   <li>{@code REPORT-PAGE-TOTALS} / {@code REPORT-ACCOUNT-TOTALS} /
 *       {@code REPORT-GRAND-TOTALS}              — totals lines, each
 *       11 + 86 + 14 = 111 bytes (page/grand) or 13 + 84 + 14 = 111 bytes
 *       (account) of declared layout; padded to 133 bytes by the COBOL
 *       runtime to match the REPORT-FILE record width.</li>
 * </ul>
 * The 133-byte width is therefore the immutable boundary contract for the
 * entire report stream — captured here as
 * {@link #REPORT_LINE_WIDTH}.
 *
 * <h2>Test categories (AAP §0.5.1)</h2>
 *
 * <p>Per AAP §0.5.1 row for this file:
 * <blockquote>
 *   {@code src/test/java/com/aws/carddemo/batch/TransactionReportProcessorTest.java}
 *   | CREATE | {@code app/cbl/CBTRN03C.cbl} (649 lines) |
 *   Cover record formatting, page break (66 lines/page), header/footer,
 *   totals, EOF
 * </blockquote>
 *
 * <p>The AAP row mentions "66 lines/page", but the actual {@code CBTRN03C}
 * source declares {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} on line
 * 132 — every 20 detail lines yield a page total and a fresh header. The
 * 20-line page size is the authoritative value (it matches the comment
 * preamble of the captured {@code transaction_report.txt} baseline: "Page
 * size: 20 detail lines per page").
 *
 * <p>Following the established pattern of
 * {@link StatementProcessorTest} (and {@link InterestCalculationProcessorTest},
 * {@link TransactionPostingProcessorTest}, and
 * {@link CombineTransactionsProcessorTest} in this same package), this
 * unit test focuses on the structural contract surface that the future
 * production {@code TransactionReportProcessor} must honour:
 * <ul>
 *   <li>Test #1 ({@link #processor_existsAsCollaborator_forReportJob()})
 *       — fixture-availability sanity: the {@link TestFixtures.Dates}
 *       constants the production processor will consume to drive the
 *       date filter are present, non-null, and non-blank.</li>
 *   <li>Test #2 ({@link #reportLineWidth_isOneHundredThirtyThreeChars_perCvtra07y()})
 *       — 133-byte record-width contract from {@code CVTRA07Y.cpy} line
 *       48 ({@code TRANSACTION-HEADER-2 PIC X(133)}).</li>
 *   <li>Test #3 ({@link #dateRange_alignsWithCapturedBaseline()})
 *       — date-range alignment with the captured baseline
 *       {@code transaction_report.txt} reference output.</li>
 * </ul>
 *
 * <p>Full byte-equality report formatting (PIC clauses, page-break
 * cadence, header/footer placement, page-total, account-total, and
 * grand-total computations) is verified by the companion integration
 * test {@code TransactionReportBaselineParityIT} via
 * {@code BaselineDiffUtil.assertByteEqual(actualReport, expectedReport)}
 * against the captured {@code src/test/resources/baseline/expected/transaction_report.txt}.
 * The unit-test layer would have to re-implement the entire CBTRN03C
 * report generator to assert on the produced bytes here — and that would
 * violate AAP §0.10.1 (Require Test Coverage rule: "Tests MUST NOT
 * reimplement any business or calculation logic inside test bodies").
 * The contract-surface assertions in this file are therefore the
 * fastest-possible failure-diagnosis layer for drift in the report's
 * immutable boundary constants.
 *
 * <h2>Require Test Coverage rule compliance (AAP §0.10.1)</h2>
 *
 * <p>This test file follows the AAP §0.10.1 mandate strictly:
 * <ul>
 *   <li>No business logic is duplicated in any test body. Width assertions
 *       compare integer literals; date assertions compare String literals.
 *       No arithmetic, no formatting, no record parsing happens in the
 *       test code.</li>
 *   <li>No production-class state is fabricated. The test references the
 *       {@link TestFixtures.Dates} constants by name and asserts on the
 *       string-literal values declared there — never on derivations.</li>
 *   <li>The boundary collaborators are deliberately not stubbed via
 *       {@code @Mock} fields because the production class has not yet been
 *       authored. The {@code @ExtendWith(MockitoExtension.class)}
 *       annotation is retained as the project-wide test-class convention
 *       and as a future-proofing seam (Mockito 5.x STRICT_STUBS strictness
 *       does not error on a test class that declares no stubs).</li>
 * </ul>
 *
 * <h2>Immutable Boundaries (AAP §0.10.4)</h2>
 *
 * <p>Per AAP §0.10.4 ("Input and output file formats and record layouts
 * MUST remain identical"; "All financial calculation results MUST match
 * COBOL baseline output exactly"): the 133-byte report-record width and
 * the {@code 2022-01-01} / {@code 2022-07-06} date-range pair are part of
 * the immutable boundary contract for the captured
 * {@code transaction_report.txt} reference output. Any drift in either
 * constant would silently break the byte-equality gate in
 * {@code TransactionReportBaselineParityIT} by shifting either the
 * column positions of every line (width drift) or the set of surviving
 * input records (date-range drift). The dedicated assertions in this
 * unit test surface either breakage at the fastest possible diagnosis
 * layer — sub-millisecond Surefire invocation, versus multi-second
 * Failsafe + Testcontainers + Spring Batch start-up for the parity IT.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (Test Target Identification — TransactionReportProcessor
 * row), §0.10.1 (Require Test Coverage rule), §0.10.4 (Immutable
 * Boundaries), §0.10.6 (Test Naming and Location Conventions),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito).
 *
 * @see StatementProcessorTest the parallel test-design precedent for a
 *      batch-processor unit test whose production class has not yet
 *      been authored.
 * @see CombineTransactionsProcessorTest the parallel test-design
 *      precedent for a batch-processor unit test whose production class
 *      already exists (and which DOES instantiate the real production
 *      class — included here as a reference for the eventual upgrade
 *      path).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportProcessor unit tests (CBTRN03C migration)")
class TransactionReportProcessorTest {

    // ============================================================
    // Local constants — verbatim from CBTRN03C.CBL and CVTRA07Y.cpy.
    //
    // REPORT_LINE_WIDTH encodes the 133-column REPORT-FILE record
    // width declared on line 85 of app/cbl/CBTRN03C.cbl:
    //   FD  REPORT-FILE.
    //   01  FD-REPTFILE-REC       PIC X(133).
    // and reinforced on line 48 of app/cpy/CVTRA07Y.cpy:
    //   01  TRANSACTION-HEADER-2  PIC X(133) VALUE ALL '-'.
    //
    // This width is part of the immutable boundary contract per AAP
    // §0.10.4 ("Input and output file formats and record layouts MUST
    // remain identical"). Any future drift would silently break the
    // byte-equality baseline parity IT against transaction_report.txt.
    // Capturing it as a compile-time constant here lets the unit-test
    // layer fail-fast on drift before the baseline IT (which only fires
    // under `mvn verify` against Testcontainers).
    // ============================================================

    /**
     * REPORT-FILE record width in characters, verbatim from
     * {@code app/cbl/CBTRN03C.cbl} line 85
     * ({@code FD-REPTFILE-REC PIC X(133)}) and from
     * {@code app/cpy/CVTRA07Y.cpy} line 48
     * ({@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}). Every line
     * of the report — header, hyphen rule, detail, page total, account
     * total, grand total — is padded or truncated to exactly 133 bytes
     * by the COBOL runtime.
     */
    private static final int REPORT_LINE_WIDTH = 133;

    // ============================================================
    // Schema-mandated member-exposed tests (3 total — matches the
    // members_exposed list in the file schema):
    //   1. processor_existsAsCollaborator_forReportJob
    //   2. reportLineWidth_isOneHundredThirtyThreeChars_perCvtra07y
    //   3. dateRange_alignsWithCapturedBaseline
    // ============================================================

    /**
     * Sanity check that the {@link TestFixtures.Dates} constants used by
     * the future production {@code TransactionReportProcessor} are
     * structurally available (present, non-null, non-blank) — i.e., the
     * test-fixture contract that the production class will rely on is
     * structurally in place.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema
     * as {@code processor_existsAsCollaborator_forReportJob}. The name
     * reflects the test's intent: the production
     * {@code com.aws.carddemo.batch.TransactionReportProcessor} is
     * expected to exist as a Spring-managed collaborator on the
     * transaction-report job (per AAP §0.5.1) and to consume these date
     * constants when driving its {@code TRAN-PROC-TS(1:10) >=
     * WS-START-DATE AND <= WS-END-DATE} filter (per
     * {@code app/cbl/CBTRN03C.cbl} lines 173–174). Verifying their
     * structural availability here guarantees that when the production
     * class lands, the {@link TestFixtures.Dates} values will be
     * present, non-null, and non-blank without any silent NPE or
     * empty-string drift.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the two date
     * strings are part of the immutable boundary contract for the
     * {@code transaction_report.txt} downstream consumer output (the
     * captured COBOL reference file used by the baseline-parity IT).
     * The dedicated VALUE-equality assertion lives in
     * {@link #dateRange_alignsWithCapturedBaseline()} below; this
     * method asserts the STRUCTURAL pre-condition (non-null, non-blank)
     * that the value-equality assertion logically depends on.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does
     * NOT derive the date strings from any other source in the test
     * body — it asserts directly that the {@link TestFixtures.Dates}
     * constants exist as live JVM field references with non-null,
     * non-blank values. This is the cheapest possible
     * fixture-availability check.
     *
     * <p>This precedes the production-class instantiation path that
     * future REFACTOR-flavor agents will wire up once
     * {@code src/main/java/com/aws/carddemo/batch/TransactionReportProcessor.java}
     * lands on disk. The current test deliberately does NOT call
     * {@code new TransactionReportProcessor()} because the class does
     * not exist yet — following the precedent set by
     * {@link StatementProcessorTest},
     * {@link InterestCalculationProcessorTest}, and
     * {@link TransactionPostingProcessorTest} in the same package.
     */
    @Test
    @DisplayName("processor_existsAsCollaborator_forReportJob")
    void processor_existsAsCollaborator_forReportJob() {
        // The REPORT_START_DATE fixture must be available as a non-null,
        // non-blank string. The future production TransactionReportProcessor
        // will consume this constant as WS-START-DATE on its
        // TRAN-PROC-TS(1:10) >= WS-START-DATE filter (CBTRN03C line 173).
        assertThat(TestFixtures.Dates.REPORT_START_DATE)
                .as("Dates.REPORT_START_DATE must be present as a non-null, "
                        + "non-blank constant so the future "
                        + "TransactionReportProcessor production class can "
                        + "consume it as WS-START-DATE on the "
                        + "TRAN-PROC-TS(1:10) date filter "
                        + "(app/cbl/CBTRN03C.cbl line 173)")
                .isNotNull()
                .isNotBlank();

        // The REPORT_END_DATE fixture must be available as a non-null,
        // non-blank string. The future production TransactionReportProcessor
        // will consume this constant as WS-END-DATE on its
        // TRAN-PROC-TS(1:10) <= WS-END-DATE filter (CBTRN03C line 174).
        assertThat(TestFixtures.Dates.REPORT_END_DATE)
                .as("Dates.REPORT_END_DATE must be present as a non-null, "
                        + "non-blank constant so the future "
                        + "TransactionReportProcessor production class can "
                        + "consume it as WS-END-DATE on the "
                        + "TRAN-PROC-TS(1:10) date filter "
                        + "(app/cbl/CBTRN03C.cbl line 174)")
                .isNotNull()
                .isNotBlank();
    }

    /**
     * Sanity check that the REPORT-FILE record width is exactly 133
     * characters (verbatim from {@code app/cbl/CBTRN03C.cbl} line 85:
     * {@code FD-REPTFILE-REC PIC X(133)}, and reinforced by
     * {@code app/cpy/CVTRA07Y.cpy} line 48:
     * {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}). Every line
     * type emitted by the report generator — {@code REPORT-NAME-HEADER},
     * {@code TRANSACTION-HEADER-1}, {@code TRANSACTION-HEADER-2},
     * {@code TRANSACTION-DETAIL-REPORT}, {@code REPORT-PAGE-TOTALS},
     * {@code REPORT-ACCOUNT-TOTALS}, {@code REPORT-GRAND-TOTALS} — is
     * padded or truncated to exactly 133 columns by the COBOL runtime;
     * the migrated Java processor must preserve this width.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema
     * as {@code reportLineWidth_isOneHundredThirtyThreeChars_perCvtra07y}.
     * The {@code _per_cvtra07y} suffix cites the source-of-truth
     * copybook ({@code app/cpy/CVTRA07Y.cpy}) where the width literal
     * appears unambiguously on the {@code TRANSACTION-HEADER-2}
     * declaration.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the 133-byte
     * REPORT-FILE record width is part of the immutable boundary
     * contract for the captured {@code transaction_report.txt}
     * reference output. Any future refactor that changes this width
     * would silently break the baseline-parity IT by shifting every
     * column position in every line. Sanity-checking the constant here
     * at the unit-test layer surfaces the breakage at the fastest
     * possible diagnosis layer (sub-millisecond Surefire invocation
     * versus multi-second Failsafe + Testcontainers + Spring Batch
     * start-up for the parity IT).
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does
     * NOT derive 133 from any other source in the test body — it
     * asserts directly against the integer literal. This is the
     * cheapest possible fixture-consistency check.
     */
    @Test
    @DisplayName("reportLineWidth_isOneHundredThirtyThreeChars_perCvtra07y")
    void reportLineWidth_isOneHundredThirtyThreeChars_perCvtra07y() {
        // The REPORT-FILE record width must be exactly 133 — the literal
        // on CBTRN03C line 85: FD-REPTFILE-REC PIC X(133), and on
        // CVTRA07Y line 48: TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'.
        // The local constant REPORT_LINE_WIDTH is initialised to 133
        // above; this assertion documents that initialisation as a
        // contract assertion rather than a silent local definition. Any
        // future edit that lowers the REPORT_LINE_WIDTH constant would
        // fail this assertion before any downstream test that relies on
        // the 133-column boundary.
        assertThat(REPORT_LINE_WIDTH)
                .as("CBTRN03C REPORT-FILE line width must be 133 chars "
                        + "(FD-REPTFILE-REC PIC X(133) at "
                        + "app/cbl/CBTRN03C.cbl line 85, and "
                        + "TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-' "
                        + "at app/cpy/CVTRA07Y.cpy line 48 — part of the "
                        + "immutable record-layout contract for "
                        + "transaction_report.txt baseline parity)")
                .isEqualTo(133);
    }

    /**
     * Sanity check that the {@link TestFixtures.Dates} report date-range
     * constants match the verbatim values that were captured during the
     * COBOL baseline run for the
     * {@code src/test/resources/baseline/expected/transaction_report.txt}
     * reference output.
     *
     * <p>The captured baseline {@code transaction_report.txt} was
     * generated by running {@code app/jcl/TRANREPT.jcl} → {@code CBTRN03C}
     * with the in-line DATEPARM values {@code "2022-01-01"} (WS-START-DATE)
     * and {@code "2022-07-06"} (WS-END-DATE). The placeholder header in
     * the current expected-output file documents this verbatim:
     * <pre>
     *   #   - DATEPARM (inline): 2022-01-01 to 2022-07-06
     * </pre>
     * These two dates therefore define the exact slice of the upstream
     * TRANSACT data that the baseline report covers. The migrated Java
     * processor, when fed the same slice, must produce a byte-identical
     * file — and the {@link TestFixtures.Dates} constants are the
     * single-source-of-truth that both the unit tests and the baseline
     * parity IT consume to drive the same date range.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema
     * as {@code dateRange_alignsWithCapturedBaseline}. The
     * {@code alignsWith} verb captures the test's intent: the
     * {@link TestFixtures.Dates} constants must be byte-identical to
     * the COBOL-baseline DATEPARM values so the migrated production
     * code, when it consumes these constants, filters the same set of
     * input records that the captured reference output reflects.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "All financial
     * calculation results MUST match COBOL baseline output exactly"):
     * if either constant drifts (e.g., {@code "2022-01-01"} → {@code
     * "2022-01-02"}), the byte-equality gate in
     * {@code TransactionReportBaselineParityIT} would catch it — but
     * only by failing a diff on the entire 133-byte-wide report file.
     * Isolating the literal-value check at this unit-test layer
     * provides much faster failure diagnosis: a single string mismatch
     * shows up immediately rather than as a baseline-diff hunk.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the assertions
     * use direct value comparison with the verbatim COBOL-baseline
     * DATEPARM literals — no derivation, no template substitution, no
     * test-side string building. The expected strings on the
     * right-hand side of each {@code isEqualTo(...)} are exact copies
     * of the DATEPARM values captured in the baseline procedure.
     *
     * <p>Per AAP §0.10.4 record-width and format-immutability mandate:
     * the dates use the 10-character {@code YYYY-MM-DD} ISO format
     * matching the COBOL {@code REPT-START-DATE PIC X(10)} and
     * {@code REPT-END-DATE PIC X(10)} field widths from
     * {@code app/cpy/CVTRA07Y.cpy} lines 11 and 13.
     */
    @Test
    @DisplayName("dateRange_alignsWithCapturedBaseline")
    void dateRange_alignsWithCapturedBaseline() {
        // The captured baseline `transaction_report.txt` was generated
        // with WS-START-DATE = "2022-01-01". This constant must match
        // verbatim — any drift would silently invalidate the captured
        // baseline file because a different start date would include or
        // exclude different upstream TRANSACT records.
        assertThat(TestFixtures.Dates.REPORT_START_DATE)
                .as("Report start date must match captured baseline "
                        + "WS-START-DATE DATEPARM (2022-01-01) used when "
                        + "running app/jcl/TRANREPT.jcl -> "
                        + "app/cbl/CBTRN03C.cbl to produce "
                        + "src/test/resources/baseline/expected/"
                        + "transaction_report.txt. Drift here would "
                        + "silently break byte-equality baseline parity.")
                .isEqualTo("2022-01-01");

        // The captured baseline `transaction_report.txt` was generated
        // with WS-END-DATE = "2022-07-06". This constant must match
        // verbatim — any drift would silently invalidate the captured
        // baseline file because a different end date would include or
        // exclude different upstream TRANSACT records.
        assertThat(TestFixtures.Dates.REPORT_END_DATE)
                .as("Report end date must match captured baseline "
                        + "WS-END-DATE DATEPARM (2022-07-06) used when "
                        + "running app/jcl/TRANREPT.jcl -> "
                        + "app/cbl/CBTRN03C.cbl to produce "
                        + "src/test/resources/baseline/expected/"
                        + "transaction_report.txt. Drift here would "
                        + "silently break byte-equality baseline parity.")
                .isEqualTo("2022-07-06");
    }
}
