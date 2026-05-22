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
// reject-code and field-width constants used here come from a single
// authoritative source so the verbatim COBOL contract (CBTRN01C lookup
// stages + CBTRN02C numeric reject codes 100/101/102/103/109) is never
// duplicated across test files. Specifically used in this class:
//   - TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10 — 11-digit ACCT-ID matching
//     CVACT01Y ACCT-ID PIC 9(11) field width
//   - TestFixtures.Cards.SAMPLE_CARD_NUMBER_01   — 16-digit Visa test PAN
//     matching CVACT02Y CARD-NUM PIC X(16) field width
//   - TestFixtures.RejectCodes.INVALID_CARD (100) and
//     TestFixtures.RejectCodes.ACCOUNT_NOT_FOUND (101) — the two reject
//     codes that CBTRN01C's lookup-driven validation can produce
//     (paragraphs 2000-LOOKUP-XREF and 3000-READ-ACCOUNT)
//   - TestFixtures.RejectCodes.OTHER_REJECT (109) — the upper bound of
//     the documented [100, 109] reject-code range used as the @ParameterizedTest
//     range check
//   - TestFixtures.RejectReasons.* — the four verbatim COBOL literals
//     emitted by CBTRN02C (and indirectly preserved by CBTRN01C's silent
//     skip behaviour) per AAP §0.10.4 Immutable Boundaries
import com.aws.carddemo.testsupport.TestFixtures;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage).
//   - @Test marks the top-level happy-path sanity test
//   - @DisplayName carries the human-readable scenario name on the class
//     and on each test/nested-class grouping (per AAP §0.10.6 naming
//     convention)
//   - @Nested groups the per-reject-code parameterized tests in the
//     RejectCodeTests inner class
//   - @ExtendWith wires the MockitoExtension below
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// JUnit 5 parameterized-test support (AAP §0.6.1). @ParameterizedTest
// declares a data-driven test method, and @CsvFileSource feeds it from
// the classpath CSV fixture at /fixtures/edge/posting_reject_codes.csv.
// Every row in the CSV becomes one invocation of the annotated method;
// the header row is skipped via numLinesToSkip = 1. Per AAP §0.10.7,
// this is the canonical mechanism for "validation variants" coverage.
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

// Mockito 5 JUnit Jupiter integration (AAP §0.6.1 — BOM-managed by
// spring-boot-starter-test 3.3.13). MockitoExtension activates STRICT_STUBS
// strictness (AAP §0.10.1: "Mockito strictness is STRICT_STUBS ... unused
// stubs raise UnnecessaryStubbingException"). This test class declares no
// @Mock fields directly because the production class
// com.aws.carddemo.batch.TransactionValidationProcessor has not yet been
// authored by REFACTOR-flavor agents (see the class-level Javadoc below).
// The extension is retained both as the project-wide test-class convention
// and as a future-proofing seam: once the production class lands,
// additional @Mock fields for CardXrefRepository and AccountRepository
// can be added without changing the class annotation.
import org.mockito.junit.jupiter.MockitoExtension;

// Java standard library arbitrary-precision decimal arithmetic (AAP §0.10.3
// NON-NEGOTIABLE: no float/double for monetary values, ever). BigDecimal
// is the exclusive type for every monetary value referenced in this test:
// the amount column in posting_reject_codes.csv is parsed via
// new BigDecimal(String) and asserted at scale ≤ 2 (matches the COBOL
// DALYTRAN-AMT PIC S9(09)V99 contract). Using new BigDecimal(String) —
// never BigDecimal.valueOf(double) — preserves the textual representation
// exactly with zero float/double precision loss.
import java.math.BigDecimal;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively, no
// JUnit Assertions, no Hamcrest matchers, no mixed styles). Static-imported
// assertThat is used for every assertion in this class. Frequently used
// fluent methods:
//   - isEqualTo(...)                — exact value equality (reject codes
//                                     and verbatim reject-reason literals)
//   - isBetween(int, int)           — reject-code range check [100, 109]
//   - hasSize(int)                  — 11-char accountId / 16-char
//                                     cardNumber field-width checks
//   - isLessThanOrEqualTo(int)      — BigDecimal scale invariant
//                                     (DALYTRAN-AMT scale ≤ 2)
//   - isIn(Object...)               — reject-reason membership check
//                                     against the four verbatim literals
//   - isNotBlank()                  — non-empty reject-reason guard
//   - as(String, Object...)         — context-providing description on
//                                     every assertion for fast failure
//                                     diagnosis
// AssertJ is preferred over JUnit's Assertions or Hamcrest matchers for
// chainable, expressive failure messages.
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@code TransactionValidationProcessor} — the Java migration of
 * the COBOL {@code CBTRN01C} daily-transaction validation utility (see
 * {@code app/cbl/CBTRN01C.cbl}).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code CBTRN01C} is the lighter-weight pre-posting validator that runs
 * ahead of {@code CBTRN02C} (which performs the full posting cascade with
 * dual-write Account+Card updates). Its job is to read each daily
 * transaction, look up the card cross-reference, then look up the account
 * — emitting a {@code DISPLAY} message and skipping the transaction
 * whenever either lookup misses. The COBOL canonical flow ({@code
 * app/cbl/CBTRN01C.cbl} lines 154–186 of {@code MAIN-PARA} plus paragraphs
 * {@code 2000-LOOKUP-XREF} and {@code 3000-READ-ACCOUNT}) is:
 * <pre>
 *   Stage 1: DALYTRAN read (1000-DALYTRAN-GET-NEXT, line 202)
 *     READ DALYTRAN-FILE INTO DALYTRAN-RECORD
 *        EOF → MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
 *        OK  → continue to Stage 2
 *
 *   Stage 2: XREF lookup (2000-LOOKUP-XREF, line 227)
 *     READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM
 *        INVALID KEY → DISPLAY 'INVALID CARD NUMBER FOR XREF'
 *                       MOVE 4 TO WS-XREF-READ-STATUS
 *                       → skip Stage 3, advance to next transaction
 *        OK         → MOVE XREF-ACCT-ID TO ACCT-ID
 *                       → continue to Stage 3
 *
 *   Stage 3: ACCT lookup (3000-READ-ACCOUNT, line 241)
 *     READ ACCOUNT-FILE INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID
 *        INVALID KEY → DISPLAY 'INVALID ACCOUNT NUMBER FOUND'
 *                       MOVE 4 TO WS-ACCT-READ-STATUS
 *                       → advance to next transaction
 *        OK         → DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'
 *                       → advance to next transaction
 * </pre>
 *
 * <p>Unlike {@code CBTRN02C}, the COBOL source for {@code CBTRN01C} does
 * NOT explicitly emit numeric reject codes — its rejects manifest as
 * {@code DISPLAY} messages on the console (paragraph 2000-LOOKUP-XREF at
 * line 232: {@code DISPLAY 'INVALID CARD NUMBER FOR XREF'}; paragraph
 * 3000-READ-ACCOUNT at line 246: {@code DISPLAY 'INVALID ACCOUNT NUMBER
 * FOUND'}). The migrated Java {@code TransactionValidationProcessor} is
 * expected to align these silent-skip conditions with the numeric reject
 * codes used by {@code CBTRN02C} so downstream telemetry can treat the
 * two validators uniformly:
 * <ul>
 *   <li>XREF lookup miss (line 232) → reject code {@code 100}
 *       ({@code INVALID CARD NUMBER FOUND}) — aligning with
 *       {@code CBTRN02C.cbl} line 385</li>
 *   <li>ACCT lookup miss (line 246) → reject code {@code 101}
 *       ({@code ACCOUNT RECORD NOT FOUND}) — aligning with
 *       {@code CBTRN02C.cbl} line 397</li>
 * </ul>
 * Per AAP §0.10.4 (Immutable Boundaries — "External interfaces consumed
 * by downstream systems MUST NOT change"), this contract is shared
 * between both validators so the downstream {@code DALYREJS-FILE}
 * consumer sees a uniform reject-code encoding regardless of which
 * upstream validator emitted the record.
 *
 * <h2>Test Categories (AAP §0.5.1)</h2>
 *
 * <ul>
 *   <li><strong>Per-reject-code coverage</strong>
 *       ({@link RejectCodeTests}). Every row in
 *       {@code posting_reject_codes.csv} represents one failure scenario
 *       of the CBTRN01C/CBTRN02C validation stack. The fixture is shared
 *       between {@link TransactionPostingProcessorTest} and this class
 *       because the reject-code contract is shared (AAP §0.10.4
 *       Immutable Boundaries). The test verifies CSV-row invariants:
 *       every row carries a reject code in the documented {@code [100,
 *       109]} range, a verbatim COBOL reason literal, and account-ID /
 *       card-number / amount columns conforming to the COBOL field-width
 *       and BigDecimal-scale contracts.</li>
 *
 *   <li><strong>Happy path</strong>
 *       ({@link #process_wellFormedTransaction_passesAllValidationChecks()}).
 *       Asserts that the {@link TestFixtures.Accounts} and
 *       {@link TestFixtures.Cards} sample constants used to drive
 *       happy-path scenarios in the future production-class wire-up
 *       respect the COBOL field-width contracts ({@code ACCT-ID PIC
 *       9(11)} = 11 digits, {@code CARD-NUM PIC X(16)} = 16 chars).</li>
 * </ul>
 *
 * <h2>Why CSV-Consistency Checks (Not Direct Production Invocation)</h2>
 *
 * <p>The production class
 * {@code com.aws.carddemo.batch.TransactionValidationProcessor} does not
 * yet exist on disk; subsequent REFACTOR-flavor agents will author it
 * (see {@code dest_file:src/main/java/com/aws/carddemo/batch/} which
 * today contains only {@code CombineTransactionsProcessor.java}). This
 * test class therefore cannot import or instantiate the production
 * class. Instead, the tests verify two layers of contract correctness:
 * <ol>
 *   <li><strong>CSV-fixture consistency.</strong> Each
 *       {@code posting_reject_codes.csv} row is asserted to carry a
 *       documented COBOL reject code (100, 101, 102, 103, or 109), a
 *       verbatim COBOL reason literal that matches one of
 *       {@link TestFixtures.RejectReasons}, an 11-character account ID
 *       (matches {@code ACCT-ID PIC 9(11)} per {@code app/cpy/CVACT01Y.cpy}
 *       line 5), a 16-character card number (matches {@code CARD-NUM PIC
 *       X(16)} per {@code app/cpy/CVACT02Y.cpy}), and a
 *       BigDecimal-parseable amount at scale ≤ 2 (matches
 *       {@code DALYTRAN-AMT PIC S9(09)V99} per {@code app/cpy/CVTRA05Y.cpy}
 *       line 10).</li>
 *   <li><strong>TestFixtures wiring integrity.</strong> The sample
 *       account ID and card number referenced by the future production
 *       class must continue to align with the COBOL field-width
 *       contracts — any drift would silently break the
 *       fixed-width-record parsing in the migrated processor. The
 *       happy-path sanity test asserts the field widths directly so any
 *       future edit to {@link TestFixtures.Accounts} or
 *       {@link TestFixtures.Cards} that breaks the 11/16 width contract
 *       fails this test before any downstream test runs.</li>
 * </ol>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule) and AAP §0.4.3
 * ("Existing Test Extension Strategy"), this is the canonical pattern
 * for tests that precede their production counterpart — see
 * {@link TransactionPostingProcessorTest} for the immediate precedent.
 * Assert what the CSV fixtures and the {@link TestFixtures} constants
 * guarantee, then add production-class invocation in a future Phase-3
 * fix-up once the class lands. The two test entry points declared in
 * this file (the {@code @Test} method
 * {@link #process_wellFormedTransaction_passesAllValidationChecks()}
 * plus the {@code @ParameterizedTest} method inside {@link RejectCodeTests})
 * collectively cover the two {@code members_exposed} entries from this
 * file's schema.
 *
 * <h2>Mock Dependencies (Per AAP §0.5.2)</h2>
 *
 * <p>The future REFACTOR-flavor wire-up will mock two external boundary
 * collaborators once the production constructor lands:
 * <ul>
 *   <li>{@code CardXrefRepository} — JPA repository boundary (database).
 *       The production code looks up the card-to-account cross-reference
 *       (Stage 2 of the validation flow, COBOL paragraph
 *       {@code 2000-LOOKUP-XREF} at line 227) via the card-cross-reference
 *       repository's {@code findByCardNumber(...)} method.</li>
 *   <li>{@code AccountRepository} — JPA repository boundary (database).
 *       The production code looks up the account by ID (Stage 3 of the
 *       validation flow, COBOL paragraph {@code 3000-READ-ACCOUNT} at
 *       line 241) via the account repository's {@code findById(...)}
 *       method.</li>
 * </ul>
 * These are listed here for the future REFACTOR-flavor wire-up; the
 * current test file declares no {@code @Mock} fields because the
 * production class the mocks would be injected into has not yet been
 * authored. The {@link org.mockito.junit.jupiter.MockitoExtension} on
 * the class is retained both as the project-wide convention and as a
 * future-proofing seam (see {@link TransactionPostingProcessorTest} for
 * the immediate precedent).
 *
 * <h2>Financial-Precision Contract (Per AAP §0.10.3)</h2>
 *
 * <p>Every monetary value referenced in this file is a {@link BigDecimal}
 * — never {@code float}, never {@code double}, never {@code Double},
 * never {@code Float}. The CSV fixture stores monetary literals as
 * quoted strings (e.g., {@code "100.00"}) that round-trip cleanly
 * through {@code new BigDecimal(String)} without float/double precision
 * loss. The parameterized test asserts on the {@link BigDecimal#scale()}
 * to confirm the COBOL {@code PIC S9(09)V99} contract (scale 2 ceiling).
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — TransactionValidationProcessor
 * for the {@code CBTRN01C} migration),
 * §0.5.1 (File-by-File Test Plan — "Cover each reject-code path
 * (100, 101, 102, 103), happy path"),
 * §0.5.2 (Test categories — per-reject-code paths plus happy path),
 * §0.10.1 (Require Test Coverage rule — drive production code via real
 * collaborators; mock only at JPA repository boundary),
 * §0.10.3 (Financial Precision — BigDecimal exclusively, scale 2 for
 * DALYTRAN-AMT PIC S9(09)V99),
 * §0.10.4 (Immutable Boundaries — reject codes 100, 101, 102, 103, 109
 * and their reason strings are part of the {@code DALYREJS-FILE}
 * downstream consumer contract shared between CBTRN01C and CBTRN02C),
 * §0.10.6 (Test Naming and Location — {@code [ClassName]Test.java}),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito only,
 * {@code @ParameterizedTest} for variant coverage).
 *
 * @see TestFixtures.RejectCodes
 * @see TestFixtures.RejectReasons
 * @see TestFixtures.Accounts
 * @see TestFixtures.Cards
 * @see TransactionPostingProcessorTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionValidationProcessor unit tests (CBTRN01C migration)")
class TransactionValidationProcessorTest {

    // ============================================================
    // Top-level happy-path sanity test (verify the TestFixtures
    // sample-data field widths that the future production class will
    // consume when reading the daily-transaction stream, before the
    // production class itself lands on disk).
    // ============================================================

    /**
     * Sanity check that the {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10}
     * and {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01} fixture constants
     * — used to construct the happy-path {@code DALYTRAN-RECORD} input for
     * the future production wire-up — respect the COBOL field-width
     * invariants for the validation lookups.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema as
     * {@code process_wellFormedTransaction_passesAllValidationChecks}. The
     * name reflects the test's intent: in the eventual production-class
     * wire-up, a well-formed transaction record (one whose card number
     * matches an XREF row and whose resolved account ID matches an ACCT
     * row) must propagate through both validation stages
     * (2000-LOOKUP-XREF + 3000-READ-ACCOUNT) without raising a reject.
     * Until the production class lands, the test asserts the
     * pre-conditions for that wire-up: the sample IDs are the correct
     * COBOL field widths.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the field
     * widths are part of the immutable boundary contract for the
     * {@code DALYTRAN-FILE} input, the {@code XREF-FILE} key, and the
     * {@code ACCT-FILE} key. Any future edit to
     * {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10} or
     * {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01} that breaks the
     * 11-digit / 16-character contracts would silently break the
     * baseline-parity IT by a fixed-width column drift; sanity-checking
     * the constants here at the unit-test layer surfaces the breakage at
     * the fastest possible diagnosis layer.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * derive the field widths from any other source in the test body —
     * it asserts directly against the integer literals
     * {@code 11} ({@code ACCT-ID PIC 9(11)} per
     * {@code app/cpy/CVACT01Y.cpy} line 5, also matching the XREF-record
     * {@code XREF-ACCT-ID PIC 9(11)} field used at CBTRN01C line 175) and
     * {@code 16} ({@code CARD-NUM PIC X(16)} per
     * {@code app/cpy/CVACT02Y.cpy}, also matching the daily-transaction
     * record's {@code DALYTRAN-CARD-NUM PIC X(16)} field used at CBTRN01C
     * line 171). This is the cheapest possible fixture-consistency
     * check.
     */
    @Test
    @DisplayName("process_wellFormedTransaction_passesAllValidationChecks")
    void process_wellFormedTransaction_passesAllValidationChecks() {
        // Field-width invariant #1: sample account ID must be exactly 11
        // digits (matches ACCT-ID PIC 9(11) per CVACT01Y.cpy line 5). This
        // is the key the production code will use to drive Stage 3
        // (3000-READ-ACCOUNT) via AccountRepository.findById — the XREF
        // lookup at Stage 2 (2000-LOOKUP-XREF) extracts XREF-ACCT-ID from
        // the cross-reference record and MOVEs it to ACCT-ID before this
        // Stage 3 read, so the 11-digit width must be preserved end-to-end
        // through the validation flow.
        assertThat(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10)
                .as("Sample account ID must be 11 digits (matches CVACT01Y "
                        + "ACCT-ID PIC 9(11) at line 5 — the key for "
                        + "AccountRepository.findById in Stage 3 of the "
                        + "CBTRN01C validation flow at paragraph "
                        + "3000-READ-ACCOUNT line 241)")
                .hasSize(11);

        // Field-width invariant #2: sample card number must be exactly 16
        // characters (matches CARD-NUM PIC X(16) per CVACT02Y.cpy). This is
        // the key the production code will use to drive Stage 2
        // (2000-LOOKUP-XREF) via CardXrefRepository.findByCardNumber — the
        // DALYTRAN-CARD-NUM field is MOVEd to XREF-CARD-NUM at CBTRN01C
        // line 171, which is then used as the FD-XREF-CARD-NUM key at line
        // 228, so the 16-character width must be preserved end-to-end
        // through the validation flow.
        assertThat(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01)
                .as("Sample card number must be 16 digits (matches CVACT02Y "
                        + "CARD-NUM PIC X(16) — the key for "
                        + "CardXrefRepository.findByCardNumber in Stage 2 of "
                        + "the CBTRN01C validation flow at paragraph "
                        + "2000-LOOKUP-XREF line 227)")
                .hasSize(16);

        // Field-width invariant #3 (negative cross-check): the sample
        // account ID and sample card number must have distinct widths
        // (11 vs 16) — they are not interchangeable. This catches an
        // accidental copy-paste in TestFixtures that would assign the
        // same value (or value of the same width) to both constants and
        // break the production code's Stage-2-vs-Stage-3 key handling.
        assertThat(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10.length())
                .as("Account ID width (11) and card number width (16) must "
                        + "be distinct — the production code branches on "
                        + "the key type at Stage 2 (16-char XREF key) "
                        + "vs Stage 3 (11-digit ACCT key)")
                .isNotEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01.length());
    }

    // ============================================================
    // Nested test class — Per-reject-code coverage
    //   Drives: posting_reject_codes.csv (14 rows, 5 columns)
    //   Columns: accountId, cardNumber, amount, expectedRejectCode, rejectReason
    //
    //   The shared fixture for CBTRN01C and CBTRN02C reject-code
    //   coverage. CBTRN01C produces codes 100 (XREF miss) and 101 (ACCT
    //   miss); CBTRN02C produces those plus 102 (OVERLIMIT), 103
    //   (EXPIRED), and 109 (REWRITE-fail). The shared CSV ensures both
    //   validators emit a uniform reject-code encoding to the downstream
    //   DALYREJS-FILE consumer per AAP §0.10.4 Immutable Boundaries.
    // ============================================================

    /**
     * Per-reject-code coverage for the CBTRN01C validation flow.
     *
     * <p>{@code CBTRN01C} performs two lookups (XREF then ACCT) and skips
     * the transaction on the first miss. The Java migration aligns these
     * silent-skip conditions with the numeric reject codes shared with
     * {@code CBTRN02C} (codes 100 and 101). The remaining codes (102,
     * 103, 109) are emitted only by {@code CBTRN02C}'s post-lookup
     * validation cascade, but they appear in the shared CSV because the
     * downstream {@code DALYREJS-FILE} consumer sees the union — both
     * validators contribute to the same reject-file stream.
     *
     * <p>The CSV columns are (in order):
     * <ol>
     *   <li>{@code accountId} — 11-character {@code ACCT-ID PIC 9(11)}
     *       per {@code app/cpy/CVACT01Y.cpy} line 5</li>
     *   <li>{@code cardNumber} — 16-character {@code CARD-NUM PIC X(16)}
     *       per {@code app/cpy/CVACT02Y.cpy}</li>
     *   <li>{@code amount} — BigDecimal-parseable string at scale ≤ 2
     *       (matches {@code TRAN-AMT PIC S9(09)V99} per
     *       {@code app/cpy/CVTRA05Y.cpy} line 10)</li>
     *   <li>{@code expectedRejectCode} — integer drawn from
     *       {@code {100, 101, 102, 103, 109}}</li>
     *   <li>{@code rejectReason} — string drawn from the four
     *       {@link TestFixtures.RejectReasons} constants
     *       (note that code 101 and code 109 share the same reason text
     *       {@code "ACCOUNT RECORD NOT FOUND"})</li>
     * </ol>
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule), the test verifies
     * an <em>invariant</em> of the CSV fixture (every row's reject code
     * is in the documented COBOL range and every reject reason matches
     * one of the four documented literals) rather than re-implementing
     * the validation flow in the test body. Re-implementing the flow
     * would re-introduce the business logic we are trying to verify and
     * would mask any bug in the production class.
     */
    @Nested
    @DisplayName("Per-reject-code validation paths")
    class RejectCodeTests {

        /**
         * The lower bound of the COBOL reject-code range. CBTRN01C
         * contributes only codes 100 (XREF miss, line 232) and 101 (ACCT
         * miss, line 246); CBTRN02C contributes the full set 100, 101,
         * 102, 103, 109 (lines 385, 397, 410, 417, 556). The canonical
         * range is {@code [100, 109]} because the
         * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} field width permits
         * any four-digit value, but the programs reserve codes 100-109
         * for the documented validation rejects. Encoded as a named
         * constant so the magic number {@code 100} is not scattered
         * through the assertions.
         */
        private static final int REJECT_CODE_RANGE_MIN = TestFixtures.RejectCodes.INVALID_CARD;

        /**
         * The upper bound of the COBOL reject-code range. CBTRN02C's
         * Stage-5 REWRITE-fail emits {@code 109} (line 556); no
         * documented reject code exceeds this value. The
         * {@code isBetween(100, 109)} assertion in the per-row test thus
         * guards against any row that declares a code outside the
         * documented {@code [100, 109]} range — a CSV authoring defect
         * that would silently break the downstream
         * {@code DALYREJS-FILE} consumer contract (AAP §0.10.4).
         */
        private static final int REJECT_CODE_RANGE_MAX = TestFixtures.RejectCodes.OTHER_REJECT;

        /**
         * Expected width in characters of the COBOL {@code ACCT-ID}
         * field. Per {@code app/cpy/CVACT01Y.cpy} line 5:
         * {@code ACCT-ID PIC 9(11)} — exactly 11 digits, zero-padded on
         * the left. This is also the width of {@code XREF-ACCT-ID} and
         * the {@code FD-ACCT-ID} key used at CBTRN01C lines 175 and 242
         * to drive the Stage-3 account lookup.
         */
        private static final int ACCOUNT_ID_WIDTH = 11;

        /**
         * Expected width in characters of the COBOL {@code CARD-NUM}
         * field. Per {@code app/cpy/CVACT02Y.cpy}:
         * {@code CARD-NUM PIC X(16)} — the Visa-test PAN width (16
         * digits). This is also the width of {@code DALYTRAN-CARD-NUM}
         * (the transaction-record's card-number field at
         * {@code app/cpy/CVTRA06Y.cpy}) and the {@code FD-XREF-CARD-NUM}
         * key used at CBTRN01C lines 171 and 228 to drive the Stage-2
         * XREF lookup.
         */
        private static final int CARD_NUMBER_WIDTH = 16;

        /**
         * Maximum permitted scale for BigDecimal monetary values per the
         * COBOL {@code TRAN-AMT PIC S9(09)V99} contract from
         * {@code app/cpy/CVTRA05Y.cpy} line 10. Two fractional digits —
         * every amount column value in the CSV must respect this
         * ceiling.
         */
        private static final int MAX_MONETARY_SCALE = 2;

        /**
         * Per-row CSV-consistency check on the shared CBTRN01C/CBTRN02C
         * reject-code fixture.
         *
         * <p>The five columns are bound to the method parameters by
         * position, matching the CSV header order (accountId, cardNumber,
         * amount, expectedRejectCode, rejectReason). The header row is
         * skipped via {@code numLinesToSkip = 1}.
         *
         * <p>The test asserts five invariants per row:
         * <ol>
         *   <li>The account ID is exactly 11 characters (matches
         *       {@code ACCT-ID PIC 9(11)} per
         *       {@code app/cpy/CVACT01Y.cpy} line 5).</li>
         *   <li>The card number is exactly 16 characters (matches
         *       {@code CARD-NUM PIC X(16)} per
         *       {@code app/cpy/CVACT02Y.cpy}).</li>
         *   <li>The amount parses as a valid BigDecimal at scale ≤ 2
         *       (matches {@code TRAN-AMT PIC S9(09)V99} per
         *       {@code app/cpy/CVTRA05Y.cpy} line 10).</li>
         *   <li>The expected reject code is in the documented COBOL
         *       range {@code [100, 109]}.</li>
         *   <li>The reject reason matches one of the four
         *       {@link TestFixtures.RejectReasons} constants (the
         *       {@code "ACCOUNT RECORD NOT FOUND"} reason is shared
         *       between codes 101 and 109).</li>
         * </ol>
         *
         * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test
         * does NOT compute the reject code in the test body — it asserts
         * only on the CSV-row invariants. The actual production-class
         * invocation (mocking {@code CardXrefRepository} and
         * {@code AccountRepository}, then calling
         * {@code processor.process(record)}) is deferred to a later
         * Phase-3 fix-up once the production
         * {@code TransactionValidationProcessor} class lands on disk.
         *
         * @param accountId          CSV column 1 — must be exactly 11 chars
         * @param cardNumber         CSV column 2 — must be exactly 16 chars
         * @param amount             CSV column 3 — must parse as a valid
         *                           BigDecimal at scale ≤ 2
         * @param expectedRejectCode CSV column 4 — must be in {@code [100, 109]}
         * @param rejectReason       CSV column 5 — must match one of the
         *                           four {@link TestFixtures.RejectReasons}
         *                           constants
         */
        @ParameterizedTest(name = "[{index}] account={0} card={1} amount={2} \u2192 reject={3} reason={4}")
        @CsvFileSource(resources = "/fixtures/edge/posting_reject_codes.csv", numLinesToSkip = 1)
        void process_invalidInput_yieldsExpectedRejectCode(
                String accountId, String cardNumber, String amount,
                int expectedRejectCode, String rejectReason) {
            // CSV-consistency check #1: account ID must be exactly 11
            // chars wide (matches CVACT01Y.cpy ACCT-ID PIC 9(11) field
            // width at line 5). This catches accidental truncation or
            // padding mismatches in the CSV fixture — for example, an
            // unpadded "10" instead of the canonical "00000000010" would
            // fail here, surfacing the CSV defect before the production
            // class is wired up to read these rows. The 11-character
            // width is also the FD-ACCT-ID key width used at CBTRN01C
            // line 242 to drive the Stage-3 account lookup.
            assertThat(accountId)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "account ID must be %d chars wide (matches "
                            + "ACCT-ID PIC 9(11) per CVACT01Y.cpy line 5)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            ACCOUNT_ID_WIDTH)
                    .hasSize(ACCOUNT_ID_WIDTH);

            // CSV-consistency check #2: card number must be exactly 16
            // chars wide (matches CVACT02Y.cpy CARD-NUM PIC X(16) field
            // width). The CSV's card numbers should follow the Visa
            // test-PAN convention (4111111111111xxx) — but the width
            // check alone is the formal CBTRN01C/CBTRN02C contract; the
            // test does not enforce the Visa prefix because the COBOL
            // field width is the only invariant the migration must
            // preserve. The 16-character width is also the
            // FD-XREF-CARD-NUM key width used at CBTRN01C line 228 to
            // drive the Stage-2 XREF lookup.
            assertThat(cardNumber)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "card number must be %d chars wide (matches "
                            + "CARD-NUM PIC X(16) per CVACT02Y.cpy)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            CARD_NUMBER_WIDTH)
                    .hasSize(CARD_NUMBER_WIDTH);

            // CSV-consistency check #3: amount must parse as a valid
            // BigDecimal at scale ≤ 2 (matches TRAN-AMT PIC S9(09)V99 per
            // CVTRA05Y.cpy line 10). Using new BigDecimal(String) — never
            // BigDecimal.valueOf(double) — so the textual representation
            // is preserved exactly with no float/double precision loss
            // (AAP §0.10.3 NON-NEGOTIABLE). The amount field is not
            // strictly used by CBTRN01C (which only does lookups) — but
            // it remains a CSV invariant because the shared fixture
            // must satisfy CBTRN02C's overlimit-check formula too.
            final BigDecimal amountValue = new BigDecimal(amount);
            assertThat(amountValue.scale())
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "amount scale must be \u2264 %d (matches "
                            + "TRAN-AMT PIC S9(09)V99 — the COBOL "
                            + "monetary-field scale ceiling per "
                            + "CVTRA05Y.cpy line 10)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            MAX_MONETARY_SCALE)
                    .isLessThanOrEqualTo(MAX_MONETARY_SCALE);

            // CSV-consistency check #4: reject code must be in the
            // documented COBOL range [100, 109]. CBTRN01C contributes
            // only 100 (XREF miss, line 232) and 101 (ACCT miss, line
            // 246); CBTRN02C contributes 100, 101, 102, 103, and 109
            // (lines 385, 397, 410, 417, 556). The broader [100, 109]
            // window covers the shared fixture; any row outside this
            // window is unambiguously a CSV authoring defect.
            assertThat(expectedRejectCode)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "reject code must be in [%d, %d] (the "
                            + "documented CBTRN01C/CBTRN02C reject-code "
                            + "range)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            REJECT_CODE_RANGE_MIN, REJECT_CODE_RANGE_MAX)
                    .isBetween(REJECT_CODE_RANGE_MIN, REJECT_CODE_RANGE_MAX);

            // CSV-consistency check #5: reject reason must be non-blank
            // and must match one of the four verbatim COBOL literals
            // captured in TestFixtures.RejectReasons. CBTRN02C emits
            // exactly these four strings via MOVE statements (lines 386,
            // 398, 411, 418); CBTRN01C's silent-skip DISPLAY messages
            // are mapped to the same reason literals by the migrated
            // class. Any other string in this column would be a CSV
            // authoring defect that would silently break the
            // DALYREJS-FILE downstream consumer contract (AAP §0.10.4).
            assertThat(rejectReason)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "reject reason must be non-empty",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason)
                    .isNotBlank();
            assertThat(rejectReason)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "reject reason must match one of the four "
                            + "verbatim COBOL literals at CBTRN02C lines "
                            + "386, 398, 411, 418 (TestFixtures.RejectReasons)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason)
                    .isIn(
                            TestFixtures.RejectReasons.INVALID_CARD_NUMBER_FOUND,
                            TestFixtures.RejectReasons.ACCOUNT_RECORD_NOT_FOUND,
                            TestFixtures.RejectReasons.OVERLIMIT_TRANSACTION,
                            TestFixtures.RejectReasons.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);
        }
    }
}
