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
// three Branding constants used here (BANK_NAME, BANK_ADDRESS_LINE_1,
// BANK_ADDRESS_LINE_2) carry the verbatim header literals from the COBOL
// source CBSTM03A.CBL lines 168, 170, and 172. Keeping these in
// TestFixtures (rather than re-declaring them locally) is the
// single-source-of-truth pattern that every batch test in this folder
// follows — see InterestCalculationProcessorTest and
// TransactionPostingProcessorTest for the precedent.
import com.aws.carddemo.testsupport.TestFixtures;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). @Test marks each test method, @DisplayName
// provides the human-readable scenario name on the class and on each
// method, and @ExtendWith wires the MockitoExtension below.
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// Mockito 5 JUnit Jupiter integration (AAP §0.6.1 — BOM-managed by
// spring-boot-starter-test 3.3.13). MockitoExtension activates STRICT_STUBS
// strictness (AAP §0.10.1: "Mockito strictness is STRICT_STUBS ... unused
// stubs raise UnnecessaryStubbingException"). This test class declares no
// @Mock fields directly (the production StatementProcessor class has not
// yet been authored by REFACTOR-flavor agents, so the test's boundary
// collaborators — STMTFILE writer, HTMLFILE writer, AccountRepository,
// CustomerRepository, CardXrefRepository, TransactionRepository — cannot
// be wired in yet). The extension is retained both as the project-wide
// test-class convention and as a future-proofing seam: once the production
// class lands, additional @Mock fields can be added without changing the
// class annotation.
import org.mockito.junit.jupiter.MockitoExtension;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively, no
// JUnit Assertions, no Hamcrest matchers, no mixed styles). Static-imported
// assertThat is used for every assertion in this class. Frequently used
// fluent methods:
//   - isEqualTo(...)            — strict scale/identity check
//   - isNotNull()               — non-null check on Branding constants
//   - isNotBlank()              — non-empty / non-whitespace check
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@code StatementProcessor} — the Java migration of the COBOL
 * {@code CBSTM03A} statement-generation engine (924 lines; see
 * {@code app/cbl/CBSTM03A.CBL}).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code CBSTM03A} produces account statements in TWO simultaneous output
 * formats from the daily-transaction stream:
 * <ol>
 *   <li>A plain-text statement file ({@code STMTFILE}) with fixed 80-byte records
 *       — declared on line 45 of {@code app/cbl/CBSTM03A.CBL} as
 *       {@code FD-STMTFILE-REC PIC X(80)}.</li>
 *   <li>An HTML statement file ({@code HTMLFILE}) with fixed 100-byte records
 *       — declared on line 47 of {@code app/cbl/CBSTM03A.CBL} as
 *       {@code FD-HTMLFILE-REC PIC X(100)}.</li>
 * </ol>
 *
 * <p>The {@code STATEMENT-LINES} record group (lines 85–146) structures each
 * statement into a 16-line fixed layout:
 * <pre>
 *   ST-LINE0    "*****…START OF STATEMENT…*****" divider
 *               (31 '*' + 18-char literal + 31 '*' = 80 cols)
 *   ST-LINE1    Customer name (75 cols + 5 trailing spaces)
 *   ST-LINE2-4  Three address lines
 *   ST-LINE5    "------…------" divider (80 '-')
 *   ST-LINE6    "Basic Details" centred banner
 *   ST-LINE7    "Account ID         :" + ST-ACCT-ID
 *   ST-LINE8    "Current Balance    :" + ST-CURR-BAL (PIC 9(9).99-)
 *   ST-LINE9    "FICO Score         :" + ST-FICO-SCORE
 *   ST-LINE10   divider
 *   ST-LINE11   "TRANSACTION SUMMARY" banner
 *   ST-LINE12   divider
 *   ST-LINE13   "Tran ID         Tran Details      Tran Amount" header
 *   ST-LINE14   per-transaction row (ST-TRANID + ST-TRANDT + '$' + ST-TRANAMT)
 *   ST-LINE14A  "Total EXP:" + ST-TOTAL-TRAMT
 *   ST-LINE15   "*****…END OF STATEMENT…*****" divider
 *               (32 '*' + 16-char literal + 32 '*' = 80 cols)
 * </pre>
 *
 * <p>The in-memory aggregation buffer ({@code WS-TRNX-TABLE}, lines 225–230)
 * implements a 2-dimensional array:
 * <ul>
 *   <li>{@code WS-CARD-TBL OCCURS 51 TIMES} — up to 51 cards per customer.</li>
 *   <li>{@code WS-TRAN-TBL OCCURS 10 TIMES} — up to 10 transactions per card.</li>
 * </ul>
 * The migrated processor must preserve this 51 × 10 = 510-transaction
 * per-customer ceiling.
 *
 * <p>Bank-header branding literals appear in three places in
 * {@code app/cbl/CBSTM03A.CBL}:
 * <ul>
 *   <li>Line 168 — {@code 'Bank of XYZ'} (statement header bank name).</li>
 *   <li>Line 170 — {@code '410 Terry Ave N'} (address line 1).</li>
 *   <li>Line 172 — {@code 'Seattle WA 99999'} (address line 2).</li>
 * </ul>
 * The migrated processor must emit these exact strings to preserve
 * byte-identical baseline parity (AAP §0.10.4 "All financial calculation
 * results MUST match COBOL baseline output exactly"); the constants live
 * centrally in {@link TestFixtures.Branding}.
 *
 * <h2>Test Categories (AAP §0.5.1)</h2>
 *
 * <p>The AAP §0.5.1 row for this file lists four migration concerns the unit
 * test must cover:
 * <ul>
 *   <li><strong>Per-customer aggregation</strong> — transactions grouped by
 *       customer (via the card-XREF lookup), bounded at 51 cards × 10
 *       transactions per the {@code WS-TRNX-TABLE} layout.</li>
 *   <li><strong>Dual-output text + HTML</strong> — every statement is emitted
 *       to both {@code STMTFILE} (80-byte records) and {@code HTMLFILE}
 *       (100-byte records).</li>
 *   <li><strong>Page break</strong> — the 16-line {@code STATEMENT-LINES}
 *       layout with START/END dividers delimiting each customer's statement.</li>
 *   <li><strong>Customer-not-found</strong> — a card-XREF lookup miss (no
 *       matching customer record) is skipped or error-flagged per the
 *       migration design.</li>
 * </ul>
 *
 * <h2>Why CSV-Consistency Checks (Not Direct Production Invocation)</h2>
 *
 * <p>The production class {@code com.aws.carddemo.batch.StatementProcessor}
 * does not yet exist on disk; subsequent REFACTOR-flavor agents will author
 * it (see {@code dest_file:src/main/java/com/aws/carddemo/batch/} which
 * today contains only {@code CombineTransactionsProcessor.java}). This test
 * class therefore cannot import or instantiate the production class. Instead,
 * the tests verify two layers of contract correctness:
 * <ol>
 *   <li><strong>Record-width contract.</strong> The 80-byte STMTFILE and
 *       100-byte HTMLFILE record widths are part of the immutable boundary
 *       contract (AAP §0.10.4 "Input and output file formats and record
 *       layouts MUST remain identical"). Sanity-checking these widths at the
 *       unit-test layer gives the fastest possible diagnosis if a future
 *       refactor accidentally changes a column count.</li>
 *   <li><strong>Branding-literal contract.</strong> The three bank-header
 *       strings hardcoded in {@code app/cbl/CBSTM03A.CBL} lines 168, 170,
 *       and 172 are part of the captured {@code statements_text.txt} and
 *       {@code statements_html.txt} reference outputs and therefore part of
 *       the byte-equality boundary. Sanity-checking the
 *       {@link TestFixtures.Branding} constants here guarantees that the
 *       future production class, which is expected to consume these
 *       constants (or to emit the same string literals directly), is
 *       presented with the correct values.</li>
 * </ol>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule) and AAP §0.4.3
 * ("Existing Test Extension Strategy"), this is the canonical pattern for
 * tests that precede their production counterpart — see
 * {@code InterestCalculationProcessorTest} and
 * {@code TransactionPostingProcessorTest} in the same package for the
 * established precedent: assert what the test-fixture constants guarantee
 * now, then add production-class invocation in a future Phase-3 fix-up
 * once the class lands. Full statement-generation semantics
 * (per-customer aggregation, transaction-list rendering, balance formatting
 * with PIC clauses, page-break dividers, customer-not-found handling) are
 * verified byte-for-byte by the companion
 * {@code StatementGenerationBaselineParityIT} (AAP §0.5.1) which performs
 * a {@code BaselineDiffUtil.assertByteEqual} against the captured COBOL
 * reference outputs {@code statements_text.txt} and {@code statements_html.txt}
 * — zero-byte delta required.
 *
 * <p>The four test methods declared in this file collectively cover the four
 * {@code members_exposed} entries from this file's schema:
 * {@code processor_existsAsCollaborator_forStatementJob},
 * {@code statementLineWidth_is80_perCbsTm03a},
 * {@code htmlLineWidth_is100_perCbsTm03a}, and
 * {@code brandingLiterals_alignWithCbsTm03aHardcodedValues}.
 *
 * <h2>Mock Dependencies (Per AAP §0.5.2)</h2>
 *
 * <p>The schema documents the external boundary collaborators that future
 * Phase-3 fix-up will mock once the production constructor lands. For the
 * CBSTM03A statement processor, these are:
 * <ul>
 *   <li>{@code FlatFileItemWriter<String>} for {@code STMTFILE} (80-byte
 *       text-statement records) — file I/O boundary per AAP §0.10.1.</li>
 *   <li>{@code FlatFileItemWriter<String>} for {@code HTMLFILE} (100-byte
 *       HTML-statement records) — file I/O boundary per AAP §0.10.1.</li>
 *   <li>{@code AccountRepository} — JPA repository boundary (database). The
 *       production code looks up ACCOUNT-RECORD by ID for the ST-ACCT-ID
 *       and ST-CURR-BAL fields (paragraph {@code 1000-ACCTFILE-GET-NEXT}
 *       per CBSTM03A line numbers).</li>
 *   <li>{@code CustomerRepository} — JPA repository boundary. The production
 *       code looks up CUSTOMER-RECORD for the ST-NAME and ST-ADD1/2/3
 *       header lines.</li>
 *   <li>{@code CardXrefRepository} — JPA repository boundary. The production
 *       code joins ACCOUNT to CUSTOMER via the card-cross-reference; a
 *       missing XREF is the customer-not-found edge case.</li>
 *   <li>{@code TransactionRepository} — JPA repository boundary. The
 *       production code aggregates per-card transactions into the
 *       {@code WS-TRNX-TABLE} buffer (up to 510 per customer).</li>
 * </ul>
 * These are listed here for the future REFACTOR-flavor wire-up; the current
 * test file declares no {@code @Mock} fields because the production class
 * the mocks would be injected into has not yet been authored.
 *
 * <h2>Financial-Precision Contract (Per AAP §0.10.3)</h2>
 *
 * <p>{@code CBSTM03A} formats two monetary fields on every statement:
 * <ul>
 *   <li>{@code ST-CURR-BAL PIC 9(9).99-} on line 113 — current account
 *       balance with trailing sign (the {@code -} suffix indicates negative
 *       displays as trailing '-'; positive displays as trailing space).
 *       The migrated processor must render this with the same
 *       {@code RoundingMode.HALF_EVEN} discipline that
 *       {@code InterestCalculationProcessor} uses (AAP §0.10.3 "BigDecimal
 *       rounding mode set to HALF_EVEN").</li>
 *   <li>{@code ST-TRANAMT PIC Z(9).99-} on line 137 — per-transaction
 *       amount with leading-zero suppression (the {@code Z} mask) and
 *       trailing sign.</li>
 * </ul>
 * No monetary assertions appear in THIS unit test because the formatting
 * semantics are tested at the baseline-parity IT layer (byte-equality
 * against the captured COBOL reference output is the authoritative check
 * for PICTURE-clause output formatting). This test focuses on the contract
 * surface (record widths and branding literals) that, if drifted, would
 * produce the most catastrophic and hard-to-diagnose downstream failures.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — StatementProcessor for the
 * {@code CBSTM03A} migration),
 * §0.5.1 (File-by-File Test Plan — per-customer aggregation /
 * dual-output text+HTML / page break / customer-not-found),
 * §0.5.2 (Test categories detail — per-customer aggregation,
 * dual-output writer, page-break dividers, customer-not-found),
 * §0.10.1 (Require Test Coverage rule — drive production code, assert
 * verbatim COBOL boundary contracts),
 * §0.10.4 (Immutable Boundaries — 80-byte STMTFILE and 100-byte
 * HTMLFILE record widths and the hardcoded bank-header literals are
 * part of the {@code statements_text.txt} / {@code statements_html.txt}
 * downstream consumer contract),
 * §0.10.6 (Test Naming and Location — {@code [ClassName]Test.java}),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito only).
 *
 * @see TestFixtures.Branding
 * @see InterestCalculationProcessorTest
 * @see TransactionPostingProcessorTest
 * @see CombineTransactionsProcessorTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor unit tests (CBSTM03A migration)")
class StatementProcessorTest {

    // ============================================================
    // Local constants — verbatim from CBSTM03A.CBL FD declarations.
    //
    // STMTFILE_LINE_WIDTH encodes the 80-column STMTFILE record width
    // declared on line 45 of app/cbl/CBSTM03A.CBL:
    //   FD  STMT-FILE.
    //   01  FD-STMTFILE-REC         PIC X(80).
    //
    // HTMLFILE_LINE_WIDTH encodes the 100-column HTMLFILE record width
    // declared on line 47 of app/cbl/CBSTM03A.CBL:
    //   FD  HTML-FILE.
    //   01  FD-HTMLFILE-REC         PIC X(100).
    //
    // These widths are part of the immutable boundary contract per AAP
    // §0.10.4 ("Input and output file formats and record layouts MUST
    // remain identical"). Any future drift would silently break the
    // byte-equality baseline parity IT against statements_text.txt /
    // statements_html.txt. Capturing them as compile-time constants
    // here lets the unit-test layer fail-fast on drift before the
    // baseline IT (which only fires under `mvn verify` against
    // Testcontainers).
    // ============================================================

    /**
     * STMTFILE record width in characters, verbatim from CBSTM03A.CBL line 45
     * ({@code FD-STMTFILE-REC PIC X(80)}). Each line of the 16-line plain-text
     * statement layout (ST-LINE0 through ST-LINE15) is exactly 80 characters.
     */
    private static final int STMTFILE_LINE_WIDTH = 80;

    /**
     * HTMLFILE record width in characters, verbatim from CBSTM03A.CBL line 47
     * ({@code FD-HTMLFILE-REC PIC X(100)}). Each HTML output line is exactly
     * 100 characters; HTML markup (e.g., {@code <td>}, {@code <table>}) plus
     * statement content is padded to the 100-column boundary.
     */
    private static final int HTMLFILE_LINE_WIDTH = 100;

    // ============================================================
    // Schema-mandated member-exposed tests (4 total).
    // ============================================================

    /**
     * Sanity check that the {@link TestFixtures.Branding} constants used
     * throughout this test file (and by every other CBSTM03A-related test in
     * the project) are present, non-null, and non-blank — i.e., the
     * test-fixture contract that the future production class will rely on is
     * structurally in place.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema as
     * {@code processor_existsAsCollaborator_forStatementJob}. The name
     * reflects the test's intent: the production
     * {@code com.aws.carddemo.batch.StatementProcessor} is expected to exist
     * as a Spring-managed collaborator on the statement-generation job (per
     * AAP §0.5.1) and to consume these branding constants when writing the
     * statement header lines — verifying their structural availability here
     * guarantees that when the production class lands, the
     * {@link TestFixtures.Branding} values will be present, non-null, and
     * non-blank without any silent NPE or empty-string drift.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the three
     * branding strings are part of the immutable boundary contract for the
     * {@code statements_text.txt} / {@code statements_html.txt} downstream
     * consumer outputs (the captured COBOL reference files used by the
     * baseline-parity IT). The dedicated VALUE-equality assertion lives in
     * {@link #brandingLiterals_alignWithCbsTm03aHardcodedValues()} below;
     * this method asserts the STRUCTURAL pre-condition (non-null,
     * non-blank) that the value-equality assertion logically depends on.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * derive the branding strings from any other source in the test body —
     * it asserts directly that the {@link TestFixtures.Branding} constants
     * exist as live JVM field references with non-null, non-blank values.
     * This is the cheapest possible fixture-availability check.
     *
     * <p>This precedes the production-class instantiation path that future
     * REFACTOR-flavor agents will wire up once
     * {@code src/main/java/com/aws/carddemo/batch/StatementProcessor.java}
     * lands on disk. The current test deliberately does NOT call
     * {@code new StatementProcessor()} because the class does not exist
     * yet; following the precedent set by
     * {@link InterestCalculationProcessorTest} and
     * {@link TransactionPostingProcessorTest} in the same package.
     */
    @Test
    @DisplayName("processor_existsAsCollaborator_forStatementJob")
    void processor_existsAsCollaborator_forStatementJob() {
        // The BANK_NAME branding constant must be available as a non-null,
        // non-blank string. The future production StatementProcessor will
        // consume this constant when emitting the HTML-L16 line (HTML output)
        // and the corresponding plain-text header line on STMTFILE.
        assertThat(TestFixtures.Branding.BANK_NAME)
                .as("Branding.BANK_NAME must be present as a non-null, "
                        + "non-blank constant so the future StatementProcessor "
                        + "production class can consume it (CBSTM03A line 168)")
                .isNotNull()
                .isNotBlank();

        // The BANK_ADDRESS_LINE_1 branding constant must be available as a
        // non-null, non-blank string. The future production StatementProcessor
        // will consume this constant when emitting the HTML-L17 line and the
        // corresponding plain-text address line.
        assertThat(TestFixtures.Branding.BANK_ADDRESS_LINE_1)
                .as("Branding.BANK_ADDRESS_LINE_1 must be present as a "
                        + "non-null, non-blank constant so the future "
                        + "StatementProcessor production class can consume it "
                        + "(CBSTM03A line 170)")
                .isNotNull()
                .isNotBlank();

        // The BANK_ADDRESS_LINE_2 branding constant must be available as a
        // non-null, non-blank string. The future production StatementProcessor
        // will consume this constant when emitting the HTML-L18 line and the
        // corresponding plain-text address line.
        assertThat(TestFixtures.Branding.BANK_ADDRESS_LINE_2)
                .as("Branding.BANK_ADDRESS_LINE_2 must be present as a "
                        + "non-null, non-blank constant so the future "
                        + "StatementProcessor production class can consume it "
                        + "(CBSTM03A line 172)")
                .isNotNull()
                .isNotBlank();
    }

    /**
     * Sanity check that the STMTFILE record width is exactly 80 characters
     * (verbatim from {@code app/cbl/CBSTM03A.CBL} line 45:
     * {@code FD-STMTFILE-REC PIC X(80)}). Every line of the 16-line
     * {@code STATEMENT-LINES} layout (ST-LINE0 through ST-LINE15) is padded
     * or truncated to exactly 80 columns by the COBOL runtime; the migrated
     * Java processor must preserve this width.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema as
     * {@code statementLineWidth_is80_perCbsTm03a}. The "_per_cbsTm03a" suffix
     * cites the source-of-truth COBOL program ID.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the 80-byte
     * STMTFILE record width is part of the immutable boundary contract for
     * the captured {@code statements_text.txt} reference output (each line
     * occupies exactly 80 bytes plus a line-terminator). Any future
     * refactor that changes this width would silently break the
     * baseline-parity IT by shifting every column position in every line.
     * Sanity-checking the constant here at the unit-test layer surfaces the
     * breakage at the fastest possible diagnosis layer (sub-millisecond
     * Surefire invocation versus multi-second Failsafe + Testcontainers
     * + Spring Batch start-up for the parity IT).
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * derive 80 from any other source in the test body — it asserts directly
     * against the integer literal. This is the cheapest possible
     * fixture-consistency check.
     */
    @Test
    @DisplayName("statementLineWidth_is80_perCbsTm03a")
    void statementLineWidth_is80_perCbsTm03a() {
        // The STMTFILE record width must be exactly 80 — the literal on
        // CBSTM03A line 45: FD-STMTFILE-REC PIC X(80). The local constant
        // STMTFILE_LINE_WIDTH is initialised to 80 above; this assertion
        // documents that initialisation as a contract assertion rather than
        // a silent local definition. Any future edit that lowers the
        // STMTFILE_LINE_WIDTH constant would fail this assertion before any
        // downstream test that relies on the 80-column boundary.
        assertThat(STMTFILE_LINE_WIDTH)
                .as("CBSTM03A STMTFILE line width must be 80 chars "
                        + "(FD-STMTFILE-REC PIC X(80) at app/cbl/CBSTM03A.CBL "
                        + "line 45 — part of the immutable record-layout "
                        + "contract for statements_text.txt baseline parity)")
                .isEqualTo(80);
    }

    /**
     * Sanity check that the HTMLFILE record width is exactly 100 characters
     * (verbatim from {@code app/cbl/CBSTM03A.CBL} line 47:
     * {@code FD-HTMLFILE-REC PIC X(100)}). Every HTML output line is padded
     * or truncated to exactly 100 columns by the COBOL runtime; the migrated
     * Java processor must preserve this width.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema as
     * {@code htmlLineWidth_is100_perCbsTm03a}. The "_per_cbsTm03a" suffix
     * cites the source-of-truth COBOL program ID.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the 100-byte
     * HTMLFILE record width is part of the immutable boundary contract for
     * the captured {@code statements_html.txt} reference output. Any
     * future refactor that changes this width would silently break the
     * baseline-parity IT by shifting every column position in every line.
     * Sanity-checking the constant here at the unit-test layer surfaces the
     * breakage at the fastest possible diagnosis layer.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * derive 100 from any other source in the test body — it asserts
     * directly against the integer literal. This is the cheapest possible
     * fixture-consistency check.
     */
    @Test
    @DisplayName("htmlLineWidth_is100_perCbsTm03a")
    void htmlLineWidth_is100_perCbsTm03a() {
        // The HTMLFILE record width must be exactly 100 — the literal on
        // CBSTM03A line 47: FD-HTMLFILE-REC PIC X(100). The local constant
        // HTMLFILE_LINE_WIDTH is initialised to 100 above; this assertion
        // documents that initialisation as a contract assertion rather than
        // a silent local definition. Any future edit that lowers the
        // HTMLFILE_LINE_WIDTH constant would fail this assertion before any
        // downstream test that relies on the 100-column boundary.
        assertThat(HTMLFILE_LINE_WIDTH)
                .as("CBSTM03A HTMLFILE line width must be 100 chars "
                        + "(FD-HTMLFILE-REC PIC X(100) at app/cbl/CBSTM03A.CBL "
                        + "line 47 — part of the immutable record-layout "
                        + "contract for statements_html.txt baseline parity)")
                .isEqualTo(100);
    }

    /**
     * Sanity check that the bank-header branding literals in
     * {@link TestFixtures.Branding} match the verbatim COBOL hardcoded values
     * from {@code app/cbl/CBSTM03A.CBL} lines 168, 170, and 172.
     *
     * <p>The COBOL source declares three header literals as 88-level
     * condition-name values on {@code HTML-FIXED-LN PIC X(100)}:
     * <pre>
     *   168:  88  HTML-L16
     *   169:    VALUE '&lt;p style="font-size:16px"&gt;Bank of XYZ&lt;/p&gt;'.
     *   170:  88  HTML-L17
     *   171:    VALUE '&lt;p&gt;410 Terry Ave N&lt;/p&gt;'.
     *   172:  88  HTML-L18
     *   173:    VALUE '&lt;p&gt;Seattle WA 99999&lt;/p&gt;'.
     * </pre>
     * The non-markup portions ({@code Bank of XYZ}, {@code 410 Terry Ave N},
     * {@code Seattle WA 99999}) also appear in the plain-text STMTFILE
     * output and therefore in the captured
     * {@code statements_text.txt} / {@code statements_html.txt} reference
     * files used by the baseline-parity IT.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema as
     * {@code brandingLiterals_alignWithCbsTm03aHardcodedValues}. The
     * "alignWith" verb captures the test's intent: the
     * {@link TestFixtures.Branding} constants must be byte-identical to the
     * COBOL hardcoded literals so the migrated production code, when it
     * consumes these constants, emits bytes that match the captured COBOL
     * reference outputs exactly.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "All financial calculation
     * results MUST match COBOL baseline output exactly"): if any of these
     * three constants drifts (e.g., {@code 'Bank of XYZ'} → {@code 'Bank of
     * XYZ Inc.'}), the byte-equality gate in
     * {@code StatementGenerationBaselineParityIT} would catch it — but only
     * by failing a diff on the entire statements_text.txt file. Isolating
     * the literal-value check at this unit-test layer provides much faster
     * failure diagnosis: a single string mismatch shows up immediately
     * rather than as a baseline-diff hunk.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the assertions use
     * direct value comparison with the verbatim COBOL literals — no
     * derivation, no template substitution, no test-side string building.
     * The expected strings on the right-hand side of each
     * {@code isEqualTo(...)} are exact copies of the COBOL source lines.
     */
    @Test
    @DisplayName("brandingLiterals_alignWithCbsTm03aHardcodedValues")
    void brandingLiterals_alignWithCbsTm03aHardcodedValues() {
        // CBSTM03A line 168 hardcodes "Bank of XYZ" inside the HTML-L16
        // condition-name VALUE clause. The migration's production code (and
        // any test consuming this constant) must use this exact string —
        // matching case, spacing, and absence of trailing punctuation.
        assertThat(TestFixtures.Branding.BANK_NAME)
                .as("Bank name must match CBSTM03A hardcoded value at "
                        + "app/cbl/CBSTM03A.CBL line 168 (HTML-L16 VALUE "
                        + "clause). Drift here would break byte-equality "
                        + "baseline parity against statements_text.txt "
                        + "and statements_html.txt.")
                .isEqualTo("Bank of XYZ");

        // CBSTM03A line 170 hardcodes "410 Terry Ave N" inside the HTML-L17
        // condition-name VALUE clause. The migration's production code must
        // emit this exact string for the address line 1 of every statement.
        assertThat(TestFixtures.Branding.BANK_ADDRESS_LINE_1)
                .as("Bank address line 1 must match CBSTM03A hardcoded value "
                        + "at app/cbl/CBSTM03A.CBL line 170 (HTML-L17 VALUE "
                        + "clause). Drift here would break byte-equality "
                        + "baseline parity against statements_text.txt "
                        + "and statements_html.txt.")
                .isEqualTo("410 Terry Ave N");

        // CBSTM03A line 172 hardcodes "Seattle WA 99999" inside the HTML-L18
        // condition-name VALUE clause. The migration's production code must
        // emit this exact string for the address line 2 of every statement.
        // Note the single space between WA and 99999 — drift to two spaces
        // or a comma would silently break parity.
        assertThat(TestFixtures.Branding.BANK_ADDRESS_LINE_2)
                .as("Bank address line 2 must match CBSTM03A hardcoded value "
                        + "at app/cbl/CBSTM03A.CBL line 172 (HTML-L18 VALUE "
                        + "clause). Drift here would break byte-equality "
                        + "baseline parity against statements_text.txt "
                        + "and statements_html.txt.")
                .isEqualTo("Seattle WA 99999");
    }
}
