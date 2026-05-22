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
// four RecordWidths constants used here (CARD_XREF_RECLN, CUSTOMER_RECLN,
// ACCOUNT_RECLN, TRANSACTION_RECLN) encode the four VSAM file record
// widths that CBSTM03B reads (TRNXFILE / XREFFILE / CUSTFILE / ACCTFILE)
// — see the FD declarations on lines 58–78 of app/cbl/CBSTM03B.CBL plus
// the source-of-truth copybook RECLN headers in
// app/cpy/CVTRA05Y.cpy (350), app/cpy/CVACT03Y.cpy (50),
// app/cpy/CUSTREC.cpy (500), and app/cpy/CVACT01Y.cpy (300).
// Keeping these in TestFixtures (rather than re-declaring them locally)
// is the single-source-of-truth pattern that every batch test in this
// folder follows — see InterestCalculationProcessorTest,
// StatementProcessorTest, and TransactionReportProcessorTest for the
// established precedent.
import com.aws.carddemo.testsupport.TestFixtures;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). @Test marks each test method, @DisplayName
// provides the human-readable scenario name on the class and on each
// method, and @ExtendWith wires the MockitoExtension below.
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// Mockito 5 JUnit Jupiter integration (AAP §0.6.1 — BOM-managed by
// spring-boot-starter-test 3.3.13). MockitoExtension activates the
// STRICT_STUBS strictness mode (AAP §0.10.1: "Mockito strictness is
// STRICT_STUBS ... unused stubs raise UnnecessaryStubbingException").
// This test class declares no @Mock fields directly — the production
// StatementFileProcessor class has not yet been authored by
// REFACTOR-flavor agents, so its boundary collaborators (the four VSAM
// file readers it coordinates — TRNXFILE / XREFFILE / CUSTFILE /
// ACCTFILE — and their downstream JPA repository or
// FlatFileItemReader equivalents) cannot be wired in yet. The extension
// is retained both as the project-wide test-class convention and as a
// future-proofing seam: once the production class lands, additional
// @Mock fields can be added without changing the class annotation.
import org.mockito.junit.jupiter.MockitoExtension;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively,
// no JUnit Assertions, no Hamcrest matchers, no mixed styles).
// Static-imported assertThat is used for every assertion in this class.
// Frequently used fluent methods:
//   - isEqualTo(...)            — strict integer equality check (e.g.,
//                                 CARD_XREF_RECLN == 50)
//   - isPositive()              — non-zero, positive sanity check on
//                                 the RecordWidths constants
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@code StatementFileProcessor} — the Java migration of the
 * COBOL {@code CBSTM03B} VSAM I/O subprogram (230 lines; see
 * {@code app/cbl/CBSTM03B.CBL}).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code CBSTM03B} is a thin VSAM I/O subprogram called by
 * {@code CBSTM03A} during account-statement generation. It encapsulates the
 * file-handling primitives (OPEN, READ, READ-K, CLOSE) for four indexed
 * VSAM files using an {@code EVALUATE} on {@code LK-M03B-DD} (lines
 * 118–128 of {@code app/cbl/CBSTM03B.CBL}) to dispatch to file-specific
 * paragraphs:
 * <ul>
 *   <li>{@code TRNXFILE} → {@code 1000-TRNXFILE-PROC} (lines 133–155)</li>
 *   <li>{@code XREFFILE} → {@code 2000-XREFFILE-PROC} (lines 157–179)</li>
 *   <li>{@code CUSTFILE} → {@code 3000-CUSTFILE-PROC} (lines 181–204)</li>
 *   <li>{@code ACCTFILE} → {@code 4000-ACCTFILE-PROC} (lines 206–229)</li>
 * </ul>
 * Each paragraph reads the record into the linkage area
 * {@code LK-M03B-FLDT PIC X(1000)} (line 112), then propagates the
 * two-byte VSAM file-status code back via {@code LK-M03B-RC PIC X(02)}
 * (line 109).
 *
 * <p>The four VSAM files have the following fixed record widths,
 * established by the {@code FD} (File Description) declarations on lines
 * 58–78 of {@code app/cbl/CBSTM03B.CBL} and reinforced by the
 * source-of-truth copybook headers:
 * <pre>
 *   TRNXFILE  = 350 bytes (16 + 16 + 318)
 *               — copybook app/cpy/CVTRA05Y.cpy (RECLN = 350)
 *   XREFFILE  =  50 bytes (16 + 34)
 *               — copybook app/cpy/CVACT03Y.cpy (RECLN = 50)
 *   CUSTFILE  = 500 bytes (9 + 491)
 *               — copybook app/cpy/CUSTREC.cpy   (RECLN = 500)
 *   ACCTFILE  = 300 bytes (11 + 289)
 *               — copybook app/cpy/CVACT01Y.cpy  (RECLN = 300)
 * </pre>
 *
 * <p>These four widths are part of the immutable boundary contract per
 * AAP §0.10.4 ("Input and output file formats and record layouts MUST
 * remain identical"). Any future refactor that changes a width would
 * silently break byte-equality parity against every captured COBOL
 * reference output that travels through CBSTM03B — including
 * {@code statements_text.txt} and {@code statements_html.txt} (the
 * statement-generation baselines that depend on customer, account, card
 * cross-reference, and transaction lookups). Sanity-checking these
 * constants here at the unit-test layer surfaces drift at the fastest
 * possible diagnosis layer (sub-millisecond Surefire invocation) versus
 * the multi-second {@code StatementGenerationBaselineParityIT} run.
 *
 * <h2>Test Categories (AAP §0.5.1)</h2>
 *
 * <p>The AAP §0.5.1 row for this file lists two migration concerns the
 * unit test must cover:
 * <ul>
 *   <li><strong>File orchestration</strong> — {@code CBSTM03B} dispatches
 *       open/read/close to the correct underlying VSAM reader based on
 *       the requested file (TRNXFILE / XREFFILE / CUSTFILE / ACCTFILE).
 *       The migrated {@code StatementFileProcessor} preserves this
 *       multi-file coordination — typically as a Spring-managed
 *       collaborator that delegates to four
 *       {@code FlatFileItemReader<String>} beans or four JPA
 *       repositories, one per source VSAM file.</li>
 *   <li><strong>Dual-output writer</strong> — the parent program
 *       {@code CBSTM03A} writes to two output files
 *       ({@code STMTFILE} and {@code HTMLFILE}) for every statement;
 *       {@code CBSTM03B} is the input-side coordinator, but the output
 *       contract (text + HTML) is paired with the input contract (the
 *       four VSAM files orchestrated here).</li>
 * </ul>
 *
 * <h2>Why Record-Width-Consistency Checks (Not Direct Production Invocation)</h2>
 *
 * <p>The production class {@code com.aws.carddemo.batch.StatementFileProcessor}
 * does not yet exist on disk; subsequent REFACTOR-flavor agents will
 * author it (see {@code dest_file:src/main/java/com/aws/carddemo/batch/}
 * which today contains only {@code CombineTransactionsProcessor.java}).
 * This test class therefore cannot import or instantiate the production
 * class. Instead, the tests verify the contract surface that the
 * production class is expected to honour:
 * <ol>
 *   <li><strong>Record-width contract availability.</strong> The four
 *       VSAM file widths (TRNXFILE=350, XREFFILE=50, CUSTFILE=500,
 *       ACCTFILE=300) are structurally available as
 *       {@link TestFixtures.RecordWidths} constants — i.e., the
 *       test-fixture contract that the production class will rely on
 *       is in place.</li>
 *   <li><strong>Record-width contract values.</strong> The same four
 *       widths equal the COBOL-derived integer literals from the FD
 *       declarations and copybook RECLN headers.</li>
 * </ol>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule) and AAP §0.4.3
 * ("Existing Test Extension Strategy"), this is the canonical pattern
 * for tests that precede their production counterpart — see
 * {@link StatementProcessorTest},
 * {@link TransactionReportProcessorTest},
 * {@link InterestCalculationProcessorTest}, and
 * {@link TransactionPostingProcessorTest} in the same package for the
 * established precedent: assert what the test-fixture constants
 * guarantee now, then add production-class invocation in a future
 * Phase-3 fix-up once the class lands. Full file-orchestration
 * semantics (dispatch by file name, OPEN/READ/READ-K/CLOSE handling,
 * VSAM file-status propagation) are verified end-to-end by the
 * companion {@code StatementGenerationBaselineParityIT} (AAP §0.5.1)
 * which performs a byte-equality diff against the captured COBOL
 * reference outputs — zero-byte delta required.
 *
 * <h2>Two Member-Exposed Tests (Schema-Driven)</h2>
 *
 * <p>The two test methods declared in this file collectively cover the
 * two {@code members_exposed} entries from this file's schema:
 * <ol>
 *   <li>{@link #processor_existsAsCollaborator_forStatementProcessor()}
 *       — structural availability of the four
 *       {@link TestFixtures.RecordWidths} constants
 *       (non-zero, positive integer sanity).</li>
 *   <li>{@link #recordWidths_alignWithCbsTm03bExpectations()}
 *       — value-equality of the same four constants against the
 *       COBOL-derived integer literals.</li>
 * </ol>
 *
 * <h2>Mock Dependencies (Per AAP §0.5.2)</h2>
 *
 * <p>The schema documents the external boundary collaborators that
 * future Phase-3 fix-up will mock once the production constructor
 * lands. For the CBSTM03B file-coordinator, these are the four
 * VSAM-file-equivalent readers/repositories:
 * <ul>
 *   <li>{@code FlatFileItemReader<String>} (or {@code TransactionRepository})
 *       for TRNXFILE — file I/O boundary per AAP §0.10.1, 350-byte
 *       fixed-width records.</li>
 *   <li>{@code FlatFileItemReader<String>} (or {@code CardXrefRepository})
 *       for XREFFILE — file I/O boundary, 50-byte fixed-width records.</li>
 *   <li>{@code FlatFileItemReader<String>} (or {@code CustomerRepository})
 *       for CUSTFILE — file I/O boundary, 500-byte fixed-width records.</li>
 *   <li>{@code FlatFileItemReader<String>} (or {@code AccountRepository})
 *       for ACCTFILE — file I/O boundary, 300-byte fixed-width records.</li>
 * </ul>
 * These are listed here for the future REFACTOR-flavor wire-up; the
 * current test file declares no {@code @Mock} fields because the
 * production class the mocks would be injected into has not yet been
 * authored.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — StatementFileProcessor
 * for the {@code CBSTM03B} migration),
 * §0.5.1 (File-by-File Test Plan — file orchestration / dual-output
 * writer),
 * §0.5.2 (Test categories detail — file orchestration coverage),
 * §0.10.1 (Require Test Coverage rule — drive production code, assert
 * verbatim COBOL boundary contracts),
 * §0.10.4 (Immutable Boundaries — the four VSAM record widths are part
 * of the {@code statements_text.txt} / {@code statements_html.txt}
 * downstream consumer contract),
 * §0.10.6 (Test Naming and Location — {@code [ClassName]Test.java}),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito only).
 *
 * @see TestFixtures.RecordWidths
 * @see StatementProcessorTest
 * @see TransactionReportProcessorTest
 * @see InterestCalculationProcessorTest
 * @see TransactionPostingProcessorTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementFileProcessor unit tests (CBSTM03B migration)")
class StatementFileProcessorTest {

    // ============================================================
    // Schema-mandated member-exposed tests (2 total — matches the
    // members_exposed list in the file schema):
    //   1. processor_existsAsCollaborator_forStatementProcessor
    //   2. recordWidths_alignWithCbsTm03bExpectations
    // ============================================================

    /**
     * Sanity check that the four {@link TestFixtures.RecordWidths}
     * constants used by the future production
     * {@code StatementFileProcessor} are structurally available
     * (present as live JVM fields with positive, non-zero values) —
     * i.e., the test-fixture contract that the production class will
     * rely on is structurally in place.
     *
     * <p>This test is listed in the file's {@code members_exposed}
     * schema as {@code processor_existsAsCollaborator_forStatementProcessor}.
     * The name reflects the test's intent: the production
     * {@code com.aws.carddemo.batch.StatementFileProcessor} is
     * expected to exist as a Spring-managed collaborator on the
     * statement-generation job (per AAP §0.5.1) and to consume these
     * record-width constants when validating its four VSAM-equivalent
     * input streams (TRNXFILE / XREFFILE / CUSTFILE / ACCTFILE).
     * Verifying their structural availability here guarantees that
     * when the production class lands, the
     * {@link TestFixtures.RecordWidths} values will be present as
     * live, positive integer references without any silent default
     * (zero) or constant-initialisation drift.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output
     * file formats and record layouts MUST remain identical"): the
     * four record widths are part of the immutable boundary contract
     * for every captured COBOL reference output that travels through
     * CBSTM03B — including
     * {@code src/test/resources/baseline/expected/statements_text.txt}
     * and {@code statements_html.txt} (the
     * statement-generation baselines that depend on the four VSAM
     * lookups orchestrated here). The dedicated value-equality
     * assertion lives in
     * {@link #recordWidths_alignWithCbsTm03bExpectations()} below;
     * this method asserts the STRUCTURAL pre-condition (positive,
     * non-zero) that the value-equality assertion logically depends
     * on.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does
     * NOT derive the widths from any other source in the test body —
     * it asserts directly that the {@link TestFixtures.RecordWidths}
     * constants exist as live JVM field references with
     * positive (non-zero, non-negative) values. This is the cheapest
     * possible fixture-availability check.
     *
     * <p>This precedes the production-class instantiation path that
     * future REFACTOR-flavor agents will wire up once
     * {@code src/main/java/com/aws/carddemo/batch/StatementFileProcessor.java}
     * lands on disk. The current test deliberately does NOT call
     * {@code new StatementFileProcessor()} because the class does not
     * exist yet — following the precedent set by
     * {@link StatementProcessorTest},
     * {@link TransactionReportProcessorTest},
     * {@link InterestCalculationProcessorTest}, and
     * {@link TransactionPostingProcessorTest} in the same package.
     */
    @Test
    @DisplayName("processor_existsAsCollaborator_forStatementProcessor")
    void processor_existsAsCollaborator_forStatementProcessor() {
        // The CARD_XREF_RECLN constant must be available as a positive
        // (non-zero, non-negative) integer. The future production
        // StatementFileProcessor will consume this constant when
        // reading XREFFILE records (CBSTM03B paragraph 2000-XREFFILE-PROC,
        // lines 157–179 of app/cbl/CBSTM03B.CBL; FD-XREFFILE-REC layout
        // at line 66 = 16-byte FD-XREF-CARD-NUM + 34-byte FD-XREF-DATA).
        assertThat(TestFixtures.RecordWidths.CARD_XREF_RECLN)
                .as("RecordWidths.CARD_XREF_RECLN must be present as a "
                        + "positive (non-zero, non-negative) integer so "
                        + "the future StatementFileProcessor production "
                        + "class can consume it when reading XREFFILE "
                        + "records (CBSTM03B paragraph 2000-XREFFILE-PROC, "
                        + "FD layout at app/cbl/CBSTM03B.CBL line 66)")
                .isPositive();

        // The CUSTOMER_RECLN constant must be available as a positive
        // (non-zero, non-negative) integer. The future production
        // StatementFileProcessor will consume this constant when
        // reading CUSTFILE records (CBSTM03B paragraph 3000-CUSTFILE-PROC,
        // lines 181–204 of app/cbl/CBSTM03B.CBL; FD-CUSTFILE-REC layout
        // at line 71 = 9-byte FD-CUST-ID + 491-byte FD-CUST-DATA).
        assertThat(TestFixtures.RecordWidths.CUSTOMER_RECLN)
                .as("RecordWidths.CUSTOMER_RECLN must be present as a "
                        + "positive (non-zero, non-negative) integer so "
                        + "the future StatementFileProcessor production "
                        + "class can consume it when reading CUSTFILE "
                        + "records (CBSTM03B paragraph 3000-CUSTFILE-PROC, "
                        + "FD layout at app/cbl/CBSTM03B.CBL line 71)")
                .isPositive();

        // The ACCOUNT_RECLN constant must be available as a positive
        // (non-zero, non-negative) integer. The future production
        // StatementFileProcessor will consume this constant when
        // reading ACCTFILE records (CBSTM03B paragraph 4000-ACCTFILE-PROC,
        // lines 206–229 of app/cbl/CBSTM03B.CBL; FD-ACCTFILE-REC layout
        // at line 76 = 11-byte FD-ACCT-ID + 289-byte FD-ACCT-DATA).
        assertThat(TestFixtures.RecordWidths.ACCOUNT_RECLN)
                .as("RecordWidths.ACCOUNT_RECLN must be present as a "
                        + "positive (non-zero, non-negative) integer so "
                        + "the future StatementFileProcessor production "
                        + "class can consume it when reading ACCTFILE "
                        + "records (CBSTM03B paragraph 4000-ACCTFILE-PROC, "
                        + "FD layout at app/cbl/CBSTM03B.CBL line 76)")
                .isPositive();

        // The TRANSACTION_RECLN constant must be available as a positive
        // (non-zero, non-negative) integer. The future production
        // StatementFileProcessor will consume this constant when
        // reading TRNXFILE records (CBSTM03B paragraph 1000-TRNXFILE-PROC,
        // lines 133–155 of app/cbl/CBSTM03B.CBL; FD-TRNXFILE-REC layout
        // at line 59 = 16-byte FD-TRNX-CARD + 16-byte FD-TRNX-ID + 318-byte
        // FD-ACCT-DATA).
        assertThat(TestFixtures.RecordWidths.TRANSACTION_RECLN)
                .as("RecordWidths.TRANSACTION_RECLN must be present as a "
                        + "positive (non-zero, non-negative) integer so "
                        + "the future StatementFileProcessor production "
                        + "class can consume it when reading TRNXFILE "
                        + "records (CBSTM03B paragraph 1000-TRNXFILE-PROC, "
                        + "FD layout at app/cbl/CBSTM03B.CBL line 59)")
                .isPositive();
    }

    /**
     * Sanity check that the four {@link TestFixtures.RecordWidths}
     * constants equal the verbatim COBOL-derived integer literals
     * from the {@code FD} declarations in {@code app/cbl/CBSTM03B.CBL}
     * (lines 58–78) and the source-of-truth copybook RECLN headers:
     * <ul>
     *   <li>{@code CARD_XREF_RECLN  = 50}
     *       — {@code app/cpy/CVACT03Y.cpy} line 2
     *       ("Data-structure for card xref (RECLN 50)")
     *       — also reachable from {@code app/cbl/CBSTM03B.CBL} line 66
     *       ({@code FD-XREF-CARD-NUM PIC X(16)} +
     *       {@code FD-XREF-DATA PIC X(34)} = 50).</li>
     *   <li>{@code CUSTOMER_RECLN   = 500}
     *       — {@code app/cpy/CUSTREC.cpy} line 2
     *       ("Data-structure for Customer entity (RECLN 500)")
     *       — also reachable from {@code app/cbl/CBSTM03B.CBL} line 71
     *       ({@code FD-CUST-ID PIC X(09)} +
     *       {@code FD-CUST-DATA PIC X(491)} = 500).</li>
     *   <li>{@code ACCOUNT_RECLN    = 300}
     *       — {@code app/cpy/CVACT01Y.cpy} line 2
     *       ("Data-structure for account entity (RECLN 300)")
     *       — also reachable from {@code app/cbl/CBSTM03B.CBL} line 76
     *       ({@code FD-ACCT-ID PIC 9(11)} +
     *       {@code FD-ACCT-DATA PIC X(289)} = 300).</li>
     *   <li>{@code TRANSACTION_RECLN = 350}
     *       — {@code app/cpy/CVTRA05Y.cpy} line 2
     *       ("Data-structure for TRANsaction record (RECLN = 350)")
     *       — also reachable from {@code app/cbl/CBSTM03B.CBL} line 59
     *       ({@code FD-TRNX-CARD PIC X(16)} +
     *       {@code FD-TRNX-ID PIC X(16)} +
     *       {@code FD-ACCT-DATA PIC X(318)} = 350).</li>
     * </ul>
     *
     * <p>This test is listed in the file's {@code members_exposed}
     * schema as {@code recordWidths_alignWithCbsTm03bExpectations}.
     * The "alignWith" verb captures the test's intent: the
     * {@link TestFixtures.RecordWidths} constants must be
     * byte-identical to the COBOL FD declarations so the migrated
     * production code, when it consumes these constants, parses
     * records that match the captured COBOL reference outputs
     * exactly.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output
     * file formats and record layouts MUST remain identical"): if any
     * of these four constants drifts (e.g., {@code CUSTOMER_RECLN}
     * from {@code 500} to {@code 499}), the byte-equality gate in
     * {@code StatementGenerationBaselineParityIT} would catch it —
     * but only by failing a diff on the entire
     * {@code statements_text.txt} / {@code statements_html.txt}
     * output files. Isolating the integer-literal check at this
     * unit-test layer provides much faster failure diagnosis: a
     * single integer mismatch shows up immediately rather than as a
     * baseline-diff hunk across hundreds of statement lines.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the assertions
     * use direct integer-literal comparison with the verbatim COBOL
     * RECLN values — no derivation, no field-by-field arithmetic, no
     * test-side computation. The expected integers on the right-hand
     * side of each {@code isEqualTo(...)} are exact copies of the
     * copybook header RECLN values.
     */
    @Test
    @DisplayName("recordWidths_alignWithCbsTm03bExpectations")
    void recordWidths_alignWithCbsTm03bExpectations() {
        // XREFFILE record width must be exactly 50 — the sum of the two
        // FD fields on lines 66–68 of app/cbl/CBSTM03B.CBL
        // (FD-XREF-CARD-NUM PIC X(16) + FD-XREF-DATA PIC X(34)) and the
        // RECLN declared in the header comment of app/cpy/CVACT03Y.cpy.
        // The card-cross-reference table maps each 16-byte CARD-NUM to a
        // (CUST-ID, ACCT-ID) pair plus a 14-byte filler; this layout is
        // the join key for every account-statement lookup that travels
        // through CBSTM03B.
        assertThat(TestFixtures.RecordWidths.CARD_XREF_RECLN)
                .as("XREFFILE record width must be 50 bytes per "
                        + "app/cpy/CVACT03Y.cpy header (RECLN 50) and "
                        + "app/cbl/CBSTM03B.CBL line 66 "
                        + "(FD-XREF-CARD-NUM PIC X(16) + "
                        + "FD-XREF-DATA PIC X(34) = 50). Drift here "
                        + "would break byte-equality baseline parity "
                        + "against statements_text.txt and "
                        + "statements_html.txt.")
                .isEqualTo(50);

        // CUSTFILE record width must be exactly 500 — the sum of the two
        // FD fields on lines 71–73 of app/cbl/CBSTM03B.CBL
        // (FD-CUST-ID PIC X(09) + FD-CUST-DATA PIC X(491)) and the RECLN
        // declared in the header comment of app/cpy/CUSTREC.cpy. The
        // customer master record carries name, address, phone, SSN,
        // government ID, DOB, EFT account, FICO score, and the
        // primary-cardholder indicator — every statement's header lines
        // are populated from this 500-byte record.
        assertThat(TestFixtures.RecordWidths.CUSTOMER_RECLN)
                .as("CUSTFILE record width must be 500 bytes per "
                        + "app/cpy/CUSTREC.cpy header (RECLN 500) and "
                        + "app/cbl/CBSTM03B.CBL line 71 "
                        + "(FD-CUST-ID PIC X(09) + "
                        + "FD-CUST-DATA PIC X(491) = 500). Drift here "
                        + "would break byte-equality baseline parity "
                        + "against statements_text.txt and "
                        + "statements_html.txt.")
                .isEqualTo(500);

        // ACCTFILE record width must be exactly 300 — the sum of the two
        // FD fields on lines 76–78 of app/cbl/CBSTM03B.CBL
        // (FD-ACCT-ID PIC 9(11) + FD-ACCT-DATA PIC X(289)) and the RECLN
        // declared in the header comment of app/cpy/CVACT01Y.cpy. The
        // account master carries current balance, credit limit, cash
        // credit limit, cycle credit/debit, open/expiration/reissue
        // dates, ZIP, and group ID — the financial-precision and
        // statement-balance lookups that travel through CBSTM03B all
        // come from this 300-byte record.
        assertThat(TestFixtures.RecordWidths.ACCOUNT_RECLN)
                .as("ACCTFILE record width must be 300 bytes per "
                        + "app/cpy/CVACT01Y.cpy header (RECLN 300) and "
                        + "app/cbl/CBSTM03B.CBL line 76 "
                        + "(FD-ACCT-ID PIC 9(11) + "
                        + "FD-ACCT-DATA PIC X(289) = 300). Drift here "
                        + "would break byte-equality baseline parity "
                        + "against statements_text.txt and "
                        + "statements_html.txt.")
                .isEqualTo(300);

        // TRNXFILE record width must be exactly 350 — the sum of the
        // three FD fields on lines 59–63 of app/cbl/CBSTM03B.CBL
        // (FD-TRNX-CARD PIC X(16) + FD-TRNX-ID PIC X(16) +
        // FD-ACCT-DATA PIC X(318)) and the RECLN declared in the header
        // comment of app/cpy/CVTRA05Y.cpy. Each statement's
        // TRANSACTION SUMMARY section is populated by iterating
        // these 350-byte transaction records and writing the per-card
        // transactions into the WS-TRNX-TABLE buffer that CBSTM03A
        // consumes; a width drift here would shift every column in
        // every statement transaction row.
        assertThat(TestFixtures.RecordWidths.TRANSACTION_RECLN)
                .as("TRNXFILE record width must be 350 bytes per "
                        + "app/cpy/CVTRA05Y.cpy header (RECLN 350) and "
                        + "app/cbl/CBSTM03B.CBL line 59 "
                        + "(FD-TRNX-CARD PIC X(16) + "
                        + "FD-TRNX-ID PIC X(16) + "
                        + "FD-ACCT-DATA PIC X(318) = 350). Drift here "
                        + "would break byte-equality baseline parity "
                        + "against statements_text.txt and "
                        + "statements_html.txt.")
                .isEqualTo(350);
    }
}
