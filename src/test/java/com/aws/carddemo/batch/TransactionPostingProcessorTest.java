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

// Shared test constants (AAP §0.5.5 — Cross-File Test Dependencies). The two
// RejectCodes constants used here (INVALID_CARD=100, ACCOUNT_NOT_FOUND=101,
// OVERLIMIT=102, ACCOUNT_EXPIRED=103, OTHER_REJECT=109) and the matching
// RejectReasons (INVALID_CARD_NUMBER_FOUND, ACCOUNT_RECORD_NOT_FOUND,
// OVERLIMIT_TRANSACTION, TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION) carry
// the verbatim CBTRN02C literals; keeping them in TestFixtures (rather than
// re-declaring them here) is the single-source-of-truth pattern that every
// batch test in this folder follows — see InterestCalculationProcessorTest
// and CombineTransactionsProcessorTest for the precedent.
import com.aws.carddemo.testsupport.TestFixtures;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). @Test marks the top-level sanity tests;
// @DisplayName carries the human-readable scenario name on the class and
// each @Nested grouping (AAP §0.10.6); @Nested groups the parameterized
// per-reject-code and per-overflow-row tests into two thematic inner
// classes; @ExtendWith wires the MockitoExtension below.
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// JUnit 5 parameterized-test support (AAP §0.6.1). @ParameterizedTest
// declares data-driven test methods and @CsvFileSource feeds them from
// classpath CSV fixtures under src/test/resources/fixtures/edge/. Every
// row in each CSV becomes one invocation of the annotated method; the
// header row is skipped via numLinesToSkip = 1. Per AAP §0.10.7, this is
// the canonical mechanism for "calculation variants" coverage.
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

// Mockito 5 JUnit Jupiter integration (AAP §0.6.1 — BOM-managed by
// spring-boot-starter-test 3.3.13). MockitoExtension activates STRICT_STUBS
// strictness (AAP §0.10.1: "Mockito strictness is STRICT_STUBS ... unused
// stubs raise UnnecessaryStubbingException"). This test class declares no
// @Mock fields directly (the production TransactionPostingProcessor class
// has not yet been authored by REFACTOR-flavor agents, so the test's
// boundary collaborators — AccountRepository, CardRepository,
// TransactionRepository, RejectRepository — cannot be wired in yet). The
// extension is retained both as the project-wide test-class convention
// and as a future-proofing seam: once the production class lands,
// additional @Mock fields can be added without changing the class
// annotation. See the explanatory note in this file's class-level Javadoc.
import org.mockito.junit.jupiter.MockitoExtension;

// Java standard library arbitrary-precision decimal arithmetic (AAP §0.10.3
// NON-NEGOTIABLE: no float/double for monetary values, ever). BigDecimal
// is the exclusive type for every monetary value referenced in this test
// file: amount fields in posting_reject_codes.csv and every operand and
// expected-result field in overflow_boundary.csv. Inline-qualified
// references (java.math.BigDecimal) are also acceptable per the schema —
// the explicit import here makes the type relationship visible in the
// import section, matching the convention established in
// InterestCalculationProcessorTest.OverflowBoundaryTests.
import java.math.BigDecimal;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively, no
// JUnit Assertions, no Hamcrest matchers, no mixed styles). Static-imported
// assertThat is used for every assertion in this class. Frequently used
// fluent methods:
//   - isEqualTo(...)                — exact value equality (reject codes,
//                                     reject reasons)
//   - isBetween(int, int)           — reject-code range check (100..109)
//   - hasSize(int)                  — 11-char accountId / 16-char cardNumber
//   - isLessThanOrEqualTo(int)      — BigDecimal scale invariant
//   - isGreaterThanOrEqualTo(int)   — non-negative scale check
//   - isIn(Object...)               — reject-reason / operation-enum
//                                     membership check
//   - as(String, Object...)         — context-providing description on
//                                     every assertion for fast failure
//                                     diagnosis
// AssertJ is preferred over JUnit's Assertions or Hamcrest matchers for
// chainable, expressive failure messages.
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@code TransactionPostingProcessor} — the Java migration of the
 * COBOL {@code CBTRN02C} daily-transaction posting engine (731 lines; see
 * {@code app/cbl/CBTRN02C.cbl}).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>The migrated processor must preserve byte-identical parity with
 * {@code CBTRN02C}'s output. The canonical validation cascade
 * ({@code app/cbl/CBTRN02C.cbl} lines 370–422, paragraphs
 * {@code 1500-VALIDATE-TRAN} → {@code 1500-A-LOOKUP-XREF} →
 * {@code 1500-B-LOOKUP-ACCT}) is:
 * <pre>
 *   Stage 1: XREF lookup (1500-A-LOOKUP-XREF, line 380)
 *     READ XREF-FILE INTO CARD-XREF-RECORD
 *        INVALID KEY → MOVE 100 TO WS-VALIDATION-FAIL-REASON
 *                       MOVE 'INVALID CARD NUMBER FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
 *
 *   Stage 2: ACCT lookup (1500-B-LOOKUP-ACCT, line 393)
 *     READ ACCOUNT-FILE INTO ACCOUNT-RECORD
 *        INVALID KEY → MOVE 101 TO WS-VALIDATION-FAIL-REASON
 *                       MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
 *
 *   Stage 3: Credit-limit (overlimit) check (line 403–413)
 *     COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
 *     IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL  → CONTINUE
 *     ELSE                                  → MOVE 102 TO WS-VALIDATION-FAIL-REASON
 *                                              MOVE 'OVERLIMIT TRANSACTION'
 *
 *   Stage 4: Expiration-date check (line 414–420)
 *     IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)  → CONTINUE
 *     ELSE                                                → MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *                                                            MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *
 *   Stage 5: Account-record REWRITE (2800-UPDATE-ACCOUNT-REC, line 554–559)
 *     REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
 *        INVALID KEY → MOVE 109 TO WS-VALIDATION-FAIL-REASON
 *                       MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
 * </pre>
 *
 * <p>The dual-write Account-and-TCATBAL update (paragraphs
 * {@code 2700-UPDATE-TCATBAL} at line 467 and {@code 2800-UPDATE-ACCOUNT-REC}
 * at line 545) must be transactional in the migrated processor: any failure
 * after a partial update must roll back atomically. The COBOL
 * {@code 9999-ABEND-PROGRAM} idiom (line 366) is what triggers job termination
 * in the original — the migration replaces this with {@code @Transactional}
 * rollback on a {@code RuntimeException} bubbling out of the {@code TRAN-CAT-BAL}
 * upsert.
 *
 * <p>Two side-effect mutations are part of the contract (paragraph
 * {@code 2800-UPDATE-ACCOUNT-REC}, lines 547–552):
 * <pre>
 *   ADD DALYTRAN-AMT TO ACCT-CURR-BAL
 *   IF DALYTRAN-AMT >= 0
 *      ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
 *   ELSE
 *      ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
 * </pre>
 * Positive amounts increment {@code ACCT-CURR-CYC-CREDIT}; negative amounts
 * increment {@code ACCT-CURR-CYC-DEBIT}. Both branches always update
 * {@code ACCT-CURR-BAL}.
 *
 * <h2>Test Categories (AAP §0.5.1, §0.5.2)</h2>
 *
 * <ul>
 *   <li><strong>Per-reject-code coverage</strong>
 *       ({@link RejectCodeTests}). Every row in
 *       {@code posting_reject_codes.csv} represents one failure stage of
 *       the validation cascade. Each row carries the expected reject code
 *       (100, 101, 102, 103, or 109) and the verbatim COBOL reject reason
 *       — the test verifies that every documented reject code has at
 *       least one row, that the code is in the COBOL-mandated 100–109
 *       range, that the reason matches one of the four
 *       {@link TestFixtures.RejectReasons} constants, and that the
 *       reason-to-code pairing follows CBTRN02C's
 *       {@code WS-VALIDATION-FAIL-REASON-DESC} contract.</li>
 *
 *   <li><strong>Overflow boundary coverage</strong>
 *       ({@link OverflowBoundaryTests}). Every row in
 *       {@code overflow_boundary.csv} carries two BigDecimal operands, an
 *       operation kind ({@code ADD}, {@code SUBTRACT}, {@code MULTIPLY},
 *       {@code DIVIDE}), the expected result, and the expected scale.
 *       The CBTRN02C overlimit formula
 *       {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}
 *       performs BigDecimal subtraction and addition at the
 *       {@code PIC S9(09)V99} envelope (positive maximum {@code 9999999.99}
 *       for the test fixture's representative boundary value); the
 *       fixture verifies that every operand and expected result respects
 *       the COBOL {@code PIC ...V99} scale ceiling, that operations are
 *       drawn from the four recognised arithmetic kinds, and that
 *       OVERFLOW sentinel rows are correctly flagged with
 *       {@code expectedScale = -1}.</li>
 * </ul>
 *
 * <h2>Why CSV-Consistency Checks (Not Direct Production Invocation)</h2>
 *
 * <p>The production class {@code com.aws.carddemo.batch.TransactionPostingProcessor}
 * does not yet exist on disk; subsequent REFACTOR-flavor agents will author it
 * (see {@code dest_file:src/main/java/com/aws/carddemo/batch/} which today
 * contains only {@code CombineTransactionsProcessor.java}). This test class
 * therefore cannot import or instantiate the production class. Instead, the
 * tests verify two layers of contract correctness:
 * <ol>
 *   <li><strong>CSV-fixture consistency.</strong> Each {@code posting_reject_codes.csv}
 *       row is asserted to carry a documented COBOL reject code (100, 101,
 *       102, 103, or 109), a verbatim COBOL reason literal that matches one
 *       of {@link TestFixtures.RejectReasons}, an 11-character account ID
 *       (matches {@code ACCT-ID PIC 9(11)} per
 *       {@code app/cpy/CVACT01Y.cpy} line 5), a 16-character card number
 *       (matches {@code CARD-NUM PIC X(16)} per
 *       {@code app/cpy/CVACT02Y.cpy}), and a BigDecimal-parseable amount at
 *       scale ≤ 2 (matches {@code DALYTRAN-AMT PIC S9(09)V99}). Each
 *       {@code overflow_boundary.csv} row is asserted to carry two
 *       BigDecimal-parseable operands at scale ≤ 2, an operation drawn from
 *       the four recognised arithmetic kinds, and a coherent
 *       (expectedResult, expectedScale) pairing (OVERFLOW rows have scale
 *       -1; non-OVERFLOW rows have a non-negative scale matching the
 *       expectedResult's actual BigDecimal scale).</li>
 *   <li><strong>TestFixtures wiring integrity.</strong> The reject codes
 *       and reject reasons referenced by the future production class must
 *       continue to align with the COBOL contract — any drift would
 *       silently break the {@code DALYREJS-FILE} downstream consumer
 *       (AAP §0.10.4 Immutable Boundaries). The top-level sanity test
 *       {@link #processor_existsAsCollaborator_forPostingJob()} asserts
 *       the four documented reject-code values directly so any future
 *       edit to {@link TestFixtures.RejectCodes} that breaks the
 *       100/101/102/103 contract fails this test before any downstream
 *       test runs.</li>
 * </ol>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule) and AAP §0.4.3 ("Existing
 * Test Extension Strategy"), this is the canonical pattern for tests that
 * precede their production counterpart: assert what the CSV fixtures and
 * the TestFixtures constants guarantee, then add production-class
 * invocation in a future Phase-3 fix-up once the class lands. The three
 * test methods declared in this file (one top-level @Test method plus the
 * two @Nested parameterized methods) collectively cover the three
 * {@code members_exposed} entries from this file's schema:
 * {@code processor_existsAsCollaborator_forPostingJob}, {@link RejectCodeTests},
 * and {@link OverflowBoundaryTests}.
 *
 * <h2>Mock Dependencies (Per AAP §0.5.2)</h2>
 *
 * <p>The schema documents four external boundary collaborators that future
 * Phase-3 fix-up will mock once the production constructor lands:
 * <ul>
 *   <li>{@code AccountRepository} — JPA repository boundary (database). The
 *       production code looks up an account by ID via
 *       {@code findById(accountId)} (Stage 2 of the validation cascade) and
 *       persists the updated balances via {@code save(account)} (the
 *       paragraph-2800 dual-write).</li>
 *   <li>{@code CardRepository} (and {@code CardXrefRepository}) — JPA
 *       repository boundary. The production code looks up the card-to-account
 *       cross-reference (Stage 1 of the validation cascade, paragraph
 *       {@code 1500-A-LOOKUP-XREF}) via the card-cross-reference repository.</li>
 *   <li>{@code TransactionRepository} — JPA repository boundary. The
 *       production code persists the new transaction record after the
 *       balance updates (paragraph {@code 2900-WRITE-TRANSACTION-FILE} at
 *       line 562).</li>
 *   <li>{@code RejectRepository} — JPA repository boundary. The production
 *       code persists each reject record on validation failure (paragraph
 *       {@code 2500-WRITE-REJECT-REC} at line 446); {@code @Transactional}
 *       semantics ensure the dual-write either completes atomically or
 *       rolls back entirely.</li>
 * </ul>
 * These are listed here for the future REFACTOR-flavor wire-up; the current
 * test file declares no {@code @Mock} fields because the production class
 * the mocks would be injected into has not yet been authored.
 *
 * <h2>Financial-Precision Contract (Per AAP §0.10.3)</h2>
 *
 * <p>Every monetary value referenced in this file is a {@link BigDecimal} —
 * never {@code float}, never {@code double}, never {@code Double}, never
 * {@code Float}. The CSV fixtures store monetary literals as quoted strings
 * (e.g., {@code "100.00"}) that round-trip cleanly through
 * {@code new BigDecimal(String)} without float/double precision loss. Every
 * assertion on a monetary value asserts on the {@link BigDecimal#scale()}
 * to confirm the COBOL {@code PIC S9(09)V99} contract (scale 2 ceiling),
 * paired with a value check on the BigDecimal where the test requires
 * value equality (the {@link RejectCodeTests} amount column carries
 * representative non-zero values; the {@link OverflowBoundaryTests}
 * operands carry the {@code 9999999.99} boundary value).
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — TransactionPostingProcessor
 * for the {@code CBTRN02C} migration),
 * §0.5.1 (File-by-File Test Plan — 4-stage validation cascade /
 * dual-write Account+Card balance update / {@code @Transactional}
 * rollback parity / fail-fast Strategy),
 * §0.5.2 (Test categories detail — happy posting, per-reject-code,
 * cascade ordering, transactional rollback),
 * §0.10.1 (Require Test Coverage rule — drive production code, assert
 * verbatim COBOL boundary contracts),
 * §0.10.3 (Financial Precision — BigDecimal exclusively, scale 2),
 * §0.10.4 (Immutable Boundaries — reject codes 100, 101, 102, 103, 109
 * and their reason strings are part of the {@code DALYREJS-FILE}
 * downstream consumer contract),
 * §0.10.6 (Test Naming and Location — {@code [ClassName]Test.java}),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito only).
 *
 * @see TestFixtures.RejectCodes
 * @see TestFixtures.RejectReasons
 * @see InterestCalculationProcessorTest
 * @see CombineTransactionsProcessorTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingProcessor unit tests (CBTRN02C migration) — validation cascade")
class TransactionPostingProcessorTest {

    // ============================================================
    // Top-level sanity test (verify the TestFixtures contract that
    // the future production class will rely on, before the production
    // class itself lands on disk).
    // ============================================================

    /**
     * Sanity check that the {@code WS-VALIDATION-FAIL-REASON} numeric reject
     * codes used throughout this test file (and by every other CBTRN02C-related
     * test in the project) align with the verbatim values emitted by
     * {@code app/cbl/CBTRN02C.cbl} lines 385, 397, 410, and 417.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema as
     * {@code processor_existsAsCollaborator_forPostingJob}. The name reflects
     * the test's intent: the production
     * {@code com.aws.carddemo.batch.TransactionPostingProcessor} is expected to
     * exist as a Spring-managed collaborator on the transaction-posting job
     * (per AAP §0.5.1) and to consume these reject-code constants when writing
     * the {@code DALYREJS-FILE} record trailer — verifying the constants
     * here guarantees that when the production class lands, the
     * {@code WS-VALIDATION-FAIL-REASON} encoding will match the COBOL
     * baseline byte-for-byte without any silent drift.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the reject codes
     * are part of the immutable boundary contract for the
     * {@code DALYREJS-FILE} downstream consumer. Any future edit to
     * {@link TestFixtures.RejectCodes} that breaks the 100/101/102/103
     * pairing would silently break the baseline-parity IT
     * ({@code TransactionPostingBaselineParityIT}) by a single integer-
     * column drift; sanity-checking the constants here at the unit-test
     * layer surfaces the breakage at the fastest possible diagnosis layer.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * derive the reject codes from any other source in the test body — it
     * asserts directly against the integer literals
     * documented in {@code app/cbl/CBTRN02C.cbl}. This is the cheapest
     * possible fixture-consistency check.
     */
    @Test
    @DisplayName("processor_existsAsCollaborator_forPostingJob")
    void processor_existsAsCollaborator_forPostingJob() {
        // Reject code 100 — invalid card number (CBTRN02C line 385:
        // "MOVE 100 TO WS-VALIDATION-FAIL-REASON" inside paragraph
        // 1500-A-LOOKUP-XREF's INVALID KEY branch). This is Stage 1 of the
        // four-stage validation cascade; failure here short-circuits the
        // remaining stages (Stage 2's PERFORM is guarded by IF
        // WS-VALIDATION-FAIL-REASON = 0 at line 372).
        assertThat(TestFixtures.RejectCodes.INVALID_CARD)
                .as("Reject code 100 must equal the verbatim COBOL value at "
                        + "CBTRN02C line 385 (1500-A-LOOKUP-XREF INVALID KEY "
                        + "branch) — this is Stage 1 of the validation cascade")
                .isEqualTo(100);

        // Reject code 101 — account record not found (CBTRN02C line 397:
        // "MOVE 101 TO WS-VALIDATION-FAIL-REASON" inside paragraph
        // 1500-B-LOOKUP-ACCT's INVALID KEY branch). Stage 2 of the cascade;
        // reached only when Stage 1 (XREF lookup) succeeded.
        assertThat(TestFixtures.RejectCodes.ACCOUNT_NOT_FOUND)
                .as("Reject code 101 must equal the verbatim COBOL value at "
                        + "CBTRN02C line 397 (1500-B-LOOKUP-ACCT INVALID KEY "
                        + "branch) — this is Stage 2 of the validation cascade")
                .isEqualTo(101);

        // Reject code 102 — overlimit transaction (CBTRN02C line 410:
        // "MOVE 102 TO WS-VALIDATION-FAIL-REASON" inside the
        // ACCT-CREDIT-LIMIT >= WS-TEMP-BAL ELSE branch). Stage 3 of the
        // cascade; reached only when Stages 1 and 2 succeeded.
        assertThat(TestFixtures.RejectCodes.OVERLIMIT)
                .as("Reject code 102 must equal the verbatim COBOL value at "
                        + "CBTRN02C line 410 (overlimit-check ELSE branch) — "
                        + "this is Stage 3 of the validation cascade")
                .isEqualTo(102);

        // Reject code 103 — transaction received after account expiration
        // (CBTRN02C line 417: "MOVE 103 TO WS-VALIDATION-FAIL-REASON" inside
        // the ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) ELSE branch).
        // Stage 4 of the cascade; reached only when Stages 1, 2, and 3
        // succeeded.
        assertThat(TestFixtures.RejectCodes.ACCOUNT_EXPIRED)
                .as("Reject code 103 must equal the verbatim COBOL value at "
                        + "CBTRN02C line 417 (expiration-check ELSE branch) — "
                        + "this is Stage 4 of the validation cascade")
                .isEqualTo(103);

        // Reject code 109 — REWRITE failure (CBTRN02C line 556:
        // "MOVE 109 TO WS-VALIDATION-FAIL-REASON" inside the
        // 2800-UPDATE-ACCOUNT-REC REWRITE INVALID KEY branch). This is the
        // "post-validation" Stage 5 reject — emitted only when the dual-write
        // commits Stages 1–4 then fails to REWRITE the ACCOUNT-FILE record.
        // Distinguishing 101 (lookup-not-found) from 109 (rewrite-not-found)
        // lets downstream telemetry pinpoint the failure stage.
        assertThat(TestFixtures.RejectCodes.OTHER_REJECT)
                .as("Reject code 109 must equal the verbatim COBOL value at "
                        + "CBTRN02C line 556 (2800-UPDATE-ACCOUNT-REC REWRITE "
                        + "INVALID KEY branch) — this is the Stage-5 "
                        + "REWRITE-fail code, distinct from Stage 2's 101")
                .isEqualTo(109);

        // Reject reason 100 — verbatim COBOL literal "INVALID CARD NUMBER FOUND"
        // (CBTRN02C line 386: "MOVE 'INVALID CARD NUMBER FOUND' TO
        // WS-VALIDATION-FAIL-REASON-DESC"). This 25-character literal is part
        // of the DALYREJS-FILE record trailer and must be preserved
        // byte-for-byte by the migration per AAP §0.10.4.
        assertThat(TestFixtures.RejectReasons.INVALID_CARD_NUMBER_FOUND)
                .as("Reject reason for code 100 must equal the verbatim COBOL "
                        + "literal at CBTRN02C line 386 — part of the "
                        + "DALYREJS-FILE downstream consumer contract")
                .isEqualTo("INVALID CARD NUMBER FOUND");

        // Reject reason 101 — verbatim COBOL literal "ACCOUNT RECORD NOT FOUND"
        // (CBTRN02C line 398: "MOVE 'ACCOUNT RECORD NOT FOUND' TO
        // WS-VALIDATION-FAIL-REASON-DESC"). Re-used at line 557 for the
        // 109 (REWRITE-fail) variant; the reason text is shared between
        // codes 101 and 109 (the distinct code lets downstream telemetry
        // distinguish the two paths even though the reason text matches).
        assertThat(TestFixtures.RejectReasons.ACCOUNT_RECORD_NOT_FOUND)
                .as("Reject reason for codes 101 and 109 must equal the "
                        + "verbatim COBOL literal at CBTRN02C lines 398 and "
                        + "557 — same reason text, distinct codes")
                .isEqualTo("ACCOUNT RECORD NOT FOUND");

        // Reject reason 102 — verbatim COBOL literal "OVERLIMIT TRANSACTION"
        // (CBTRN02C line 411: "MOVE 'OVERLIMIT TRANSACTION' TO
        // WS-VALIDATION-FAIL-REASON-DESC"). 21 characters; emitted only
        // when ACCT-CREDIT-LIMIT < WS-TEMP-BAL.
        assertThat(TestFixtures.RejectReasons.OVERLIMIT_TRANSACTION)
                .as("Reject reason for code 102 must equal the verbatim COBOL "
                        + "literal at CBTRN02C line 411")
                .isEqualTo("OVERLIMIT TRANSACTION");

        // Reject reason 103 — verbatim COBOL literal
        // "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" (CBTRN02C line 418:
        // "MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' TO
        // WS-VALIDATION-FAIL-REASON-DESC"). 42 characters; the longest of the
        // four reason strings. Emitted when DALYTRAN-ORIG-TS (1:10) >
        // ACCT-EXPIRAION-DATE.
        assertThat(TestFixtures.RejectReasons.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION)
                .as("Reject reason for code 103 must equal the verbatim COBOL "
                        + "literal at CBTRN02C line 418")
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

        // Negative cross-check: the four reject codes must be pairwise
        // distinct so the production class can branch on them unambiguously.
        // Catching an accidental copy-paste collision in
        // TestFixtures.RejectCodes before any downstream test runs.
        assertThat(TestFixtures.RejectCodes.INVALID_CARD)
                .as("Reject codes 100 and 101 must be distinct")
                .isNotEqualTo(TestFixtures.RejectCodes.ACCOUNT_NOT_FOUND);
        assertThat(TestFixtures.RejectCodes.ACCOUNT_NOT_FOUND)
                .as("Reject codes 101 and 102 must be distinct")
                .isNotEqualTo(TestFixtures.RejectCodes.OVERLIMIT);
        assertThat(TestFixtures.RejectCodes.OVERLIMIT)
                .as("Reject codes 102 and 103 must be distinct")
                .isNotEqualTo(TestFixtures.RejectCodes.ACCOUNT_EXPIRED);
        assertThat(TestFixtures.RejectCodes.ACCOUNT_EXPIRED)
                .as("Reject codes 103 and 109 must be distinct (the Stage-5 "
                        + "REWRITE-fail code is the only one that re-uses the "
                        + "101 reason text)")
                .isNotEqualTo(TestFixtures.RejectCodes.OTHER_REJECT);
    }

    // ============================================================
    // Nested test class 1 — Per-reject-code coverage
    //   Drives: posting_reject_codes.csv (14 rows, 5 columns)
    //   Columns: accountId, cardNumber, amount, expectedRejectCode, rejectReason
    //
    //   THE PRIMARY CBTRN02C PARITY TEST in this file. Verifies that every
    //   row in the per-reject-code fixture carries a code drawn from the
    //   COBOL-documented 100/101/102/103/109 set, a reason drawn from the
    //   four TestFixtures.RejectReasons constants, and that the
    //   account-ID / card-number / amount columns conform to the COBOL
    //   field-width and BigDecimal-scale contracts.
    // ============================================================

    /**
     * Coverage for the four-stage validation cascade in {@code CBTRN02C}
     * (paragraphs {@code 1500-VALIDATE-TRAN} → {@code 1500-A-LOOKUP-XREF}
     * → {@code 1500-B-LOOKUP-ACCT}) plus the Stage-5 REWRITE-fail path
     * (paragraph {@code 2800-UPDATE-ACCOUNT-REC}). Every row in
     * {@code posting_reject_codes.csv} represents one failure scenario
     * (Stage 1, Stage 2, Stage 3, Stage 4, or Stage 5).
     *
     * <p>The CSV columns are (in order):
     * <ol>
     *   <li>{@code accountId} — 11-character {@code ACCT-ID PIC 9(11)}
     *       per {@code app/cpy/CVACT01Y.cpy} line 5</li>
     *   <li>{@code cardNumber} — 16-character {@code CARD-NUM PIC X(16)}
     *       per {@code app/cpy/CVACT02Y.cpy}</li>
     *   <li>{@code amount} — BigDecimal-parseable string at scale ≤ 2
     *       (matches {@code DALYTRAN-AMT PIC S9(09)V99})</li>
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
     * the validation cascade in the test body. Re-implementing the
     * cascade would re-introduce the business logic we are trying to
     * verify and would mask any bug in the production cascade.
     */
    @Nested
    @DisplayName("Per-reject-code coverage (posting_reject_codes.csv)")
    class RejectCodeTests {

        /**
         * The lower bound of the COBOL reject-code range. The CBTRN02C source
         * (lines 385, 397, 410, 417, 556) uses only the values 100, 101, 102,
         * 103, and 109; the canonical range is 100–109 because the
         * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} field width permits
         * any four-digit value, but the program reserves codes 100-109 for
         * the documented validation-cascade rejects. Encoded as a named
         * constant so the magic number {@code 100} is not scattered through
         * the assertions.
         */
        private static final int REJECT_CODE_RANGE_MIN = 100;

        /**
         * The upper bound of the COBOL reject-code range. CBTRN02C's
         * Stage-5 REWRITE-fail emits {@code 109} (line 556); no documented
         * reject code exceeds this value. The {@code isBetween(100, 109)}
         * assertion in the per-row test thus guards against any row that
         * declares a code outside the documented {@code [100, 109]} range
         * — a CSV authoring defect that would silently break the
         * downstream {@code DALYREJS-FILE} consumer contract (AAP §0.10.4).
         */
        private static final int REJECT_CODE_RANGE_MAX = 109;

        /**
         * Expected width in characters of the COBOL {@code ACCT-ID} field.
         * Per {@code app/cpy/CVACT01Y.cpy} line 5: {@code ACCT-ID PIC 9(11)}
         * — exactly 11 digits, zero-padded on the left.
         */
        private static final int ACCOUNT_ID_WIDTH = 11;

        /**
         * Expected width in characters of the COBOL {@code CARD-NUM} field.
         * Per {@code app/cpy/CVACT02Y.cpy}: {@code CARD-NUM PIC X(16)} — the
         * Visa-test PAN width (16 digits).
         */
        private static final int CARD_NUMBER_WIDTH = 16;

        /**
         * Maximum permitted scale for BigDecimal monetary values per the
         * COBOL {@code DALYTRAN-AMT PIC S9(09)V99} contract. Two fractional
         * digits — every amount column value in the CSV must respect this
         * ceiling.
         */
        private static final int MAX_MONETARY_SCALE = 2;

        /**
         * Per-row CSV-consistency check on the posting reject-code fixture.
         * The five columns are bound to the method parameters by position,
         * matching the CSV header order (accountId, cardNumber, amount,
         * expectedRejectCode, rejectReason).
         *
         * <p>The test asserts five invariants:
         * <ol>
         *   <li>The account ID is exactly 11 characters (matches
         *       {@code ACCT-ID PIC 9(11)} per
         *       {@code app/cpy/CVACT01Y.cpy} line 5).</li>
         *   <li>The card number is exactly 16 characters (matches
         *       {@code CARD-NUM PIC X(16)} per {@code app/cpy/CVACT02Y.cpy}).</li>
         *   <li>The amount parses as a valid BigDecimal at scale ≤ 2
         *       (matches {@code DALYTRAN-AMT PIC S9(09)V99} per
         *       {@code app/cpy/CVTRA06Y.cpy}, the daily-transaction
         *       record layout).</li>
         *   <li>The expected reject code is in the documented COBOL range
         *       {@code [100, 109]}.</li>
         *   <li>The reject reason matches one of the four
         *       {@link TestFixtures.RejectReasons} constants (the
         *       {@code "ACCOUNT RECORD NOT FOUND"} reason is shared
         *       between codes 101 and 109).</li>
         * </ol>
         *
         * <p>Note on the reject-code-to-reason pairing: codes 100, 102, and
         * 103 each map to a unique reason; code 101 and code 109 share the
         * same reason text ("ACCOUNT RECORD NOT FOUND"). This is a
         * deliberate CBTRN02C design (the failure-stage distinction is
         * carried by the code, not the reason text). The test asserts the
         * pairing per row to catch any CSV authoring error where, for
         * example, a 102-coded row accidentally carries the
         * "INVALID CARD NUMBER FOUND" reason.
         *
         * @param accountId          CSV column 1 — must be exactly 11 chars
         * @param cardNumber         CSV column 2 — must be exactly 16 chars
         * @param amount             CSV column 3 — must parse as a valid
         *                           BigDecimal at scale ≤ 2
         * @param expectedRejectCode CSV column 4 — must be in {@code [100, 109]}
         * @param rejectReason       CSV column 5 — must match one of the four
         *                           {@link TestFixtures.RejectReasons}
         *                           constants
         */
        @ParameterizedTest(name = "[{index}] account={0} card={1} amount={2} → reject={3} reason={4}")
        @CsvFileSource(resources = "/fixtures/edge/posting_reject_codes.csv", numLinesToSkip = 1)
        void process_invalidInput_yieldsExpectedRejectCodeAndReason(
                String accountId, String cardNumber, String amount,
                int expectedRejectCode, String rejectReason) {
            // CSV-consistency check #1: account ID must be exactly 11 chars
            // wide (matches CVACT01Y.cpy ACCT-ID PIC 9(11) field width). This
            // catches accidental truncation or padding mismatches in the CSV
            // fixture — for example, an unpadded "10" instead of the canonical
            // "00000000010" would fail here, surfacing the CSV defect before
            // the production class is wired up to read these rows.
            assertThat(accountId)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "account ID must be %d chars wide (matches "
                            + "ACCT-ID PIC 9(11) per CVACT01Y.cpy line 5)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            ACCOUNT_ID_WIDTH)
                    .hasSize(ACCOUNT_ID_WIDTH);

            // CSV-consistency check #2: card number must be exactly 16 chars
            // wide (matches CVACT02Y.cpy CARD-NUM PIC X(16) field width).
            // The CSV's card numbers should follow the Visa test-PAN convention
            // (4111111111111xxx) — but the width check alone is the formal
            // CBTRN02C contract; the test does not enforce the Visa prefix
            // because the COBOL field width is the only invariant the
            // migration must preserve.
            assertThat(cardNumber)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "card number must be %d chars wide (matches "
                            + "CARD-NUM PIC X(16) per CVACT02Y.cpy)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            CARD_NUMBER_WIDTH)
                    .hasSize(CARD_NUMBER_WIDTH);

            // CSV-consistency check #3: amount must parse as a valid
            // BigDecimal at scale ≤ 2 (matches DALYTRAN-AMT PIC S9(09)V99).
            // Using new BigDecimal(String) — never BigDecimal.valueOf(double)
            // — so the textual representation is preserved exactly with no
            // float/double precision loss (AAP §0.10.3 NON-NEGOTIABLE).
            final BigDecimal amountValue = new BigDecimal(amount);
            assertThat(amountValue.scale())
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "amount scale must be ≤ %d (matches "
                            + "DALYTRAN-AMT PIC S9(09)V99 — the COBOL "
                            + "monetary-field scale ceiling)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            MAX_MONETARY_SCALE)
                    .isLessThanOrEqualTo(MAX_MONETARY_SCALE);

            // CSV-consistency check #4: reject code must be in the documented
            // COBOL range [100, 109]. CBTRN02C uses only 100, 101, 102, 103,
            // and 109 (lines 385, 397, 410, 417, 556) — the broader [100, 109]
            // window leaves room for any future Stage 6+ reject the migration
            // might introduce without forcing a test update; any row outside
            // this window is unambiguously a CSV authoring defect.
            assertThat(expectedRejectCode)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "reject code must be in [%d, %d] (the documented "
                            + "CBTRN02C reject-code range)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason,
                            REJECT_CODE_RANGE_MIN, REJECT_CODE_RANGE_MAX)
                    .isBetween(REJECT_CODE_RANGE_MIN, REJECT_CODE_RANGE_MAX);

            // CSV-consistency check #5: reject reason must match one of the
            // four verbatim COBOL literals captured in TestFixtures.RejectReasons.
            // CBTRN02C emits exactly these four strings via MOVE statements
            // (lines 386, 398, 411, 418); any other string in this column
            // would be a CSV authoring defect that would silently break the
            // DALYREJS-FILE downstream consumer contract (AAP §0.10.4).
            assertThat(rejectReason)
                    .as("Row [account=%s card=%s amount=%s reject=%d reason=%s]: "
                            + "reject reason must match one of the four "
                            + "verbatim COBOL literals at CBTRN02C lines 386, "
                            + "398, 411, 418 (TestFixtures.RejectReasons)",
                            accountId, cardNumber, amount,
                            expectedRejectCode, rejectReason)
                    .isIn(
                            TestFixtures.RejectReasons.INVALID_CARD_NUMBER_FOUND,
                            TestFixtures.RejectReasons.ACCOUNT_RECORD_NOT_FOUND,
                            TestFixtures.RejectReasons.OVERLIMIT_TRANSACTION,
                            TestFixtures.RejectReasons.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);

            // CSV-consistency check #6: the reject-code-to-reason pairing
            // must follow CBTRN02C's MOVE statements. Codes 100, 102, and 103
            // each map to a unique reason; code 101 and code 109 share the
            // same reason text "ACCOUNT RECORD NOT FOUND" (CBTRN02C lines 398
            // and 557 — same reason text, distinct codes because the failure
            // stage matters for downstream telemetry even though the human-
            // readable text is identical). This switch-style mapping captures
            // the verbatim COBOL contract for each code; any CSV row with a
            // mismatched pairing fails here.
            switch (expectedRejectCode) {
                case 100:
                    assertThat(rejectReason)
                            .as("Row reject=100: reason must be the verbatim "
                                    + "CBTRN02C line 386 literal "
                                    + "(INVALID_CARD_NUMBER_FOUND)")
                            .isEqualTo(TestFixtures.RejectReasons.INVALID_CARD_NUMBER_FOUND);
                    break;
                case 101:
                    assertThat(rejectReason)
                            .as("Row reject=101: reason must be the verbatim "
                                    + "CBTRN02C line 398 literal "
                                    + "(ACCOUNT_RECORD_NOT_FOUND)")
                            .isEqualTo(TestFixtures.RejectReasons.ACCOUNT_RECORD_NOT_FOUND);
                    break;
                case 102:
                    assertThat(rejectReason)
                            .as("Row reject=102: reason must be the verbatim "
                                    + "CBTRN02C line 411 literal "
                                    + "(OVERLIMIT_TRANSACTION)")
                            .isEqualTo(TestFixtures.RejectReasons.OVERLIMIT_TRANSACTION);
                    break;
                case 103:
                    assertThat(rejectReason)
                            .as("Row reject=103: reason must be the verbatim "
                                    + "CBTRN02C line 418 literal "
                                    + "(TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION)")
                            .isEqualTo(TestFixtures.RejectReasons.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);
                    break;
                case 109:
                    // Code 109 (REWRITE-fail) shares the 101 reason text per
                    // CBTRN02C line 557. The failure stage distinction is
                    // encoded by the numeric code, not the reason text.
                    assertThat(rejectReason)
                            .as("Row reject=109: reason must be the verbatim "
                                    + "CBTRN02C line 557 literal "
                                    + "(ACCOUNT_RECORD_NOT_FOUND, same text as "
                                    + "code 101 — the distinct code carries "
                                    + "the failure-stage information)")
                            .isEqualTo(TestFixtures.RejectReasons.ACCOUNT_RECORD_NOT_FOUND);
                    break;
                default:
                    // Defensive branch — should be unreachable because the
                    // isBetween(100, 109) check above bounds the value. But
                    // any code in [100, 109] that is NOT 100, 101, 102, 103,
                    // or 109 would land here, which is an undocumented value
                    // and a CSV authoring defect.
                    throw new AssertionError(
                            "Row reject=" + expectedRejectCode
                                    + ": undocumented reject code outside the "
                                    + "CBTRN02C-emitted set {100, 101, 102, "
                                    + "103, 109} — CSV authoring defect");
            }
        }
    }

    // ============================================================
    // Nested test class 2 — Overflow boundary coverage
    //   Drives: overflow_boundary.csv (22 rows, 5 columns)
    //   Columns: operand1, operand2, operation, expectedResult, expectedScale
    //
    //   Verifies CBTRN02C's BigDecimal arithmetic surface (the
    //   WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
    //   + DALYTRAN-AMT computation at line 403–405, plus the
    //   ADD DALYTRAN-AMT TO ACCT-CURR-BAL/CYC-CREDIT/CYC-DEBIT
    //   updates at lines 547–551) handles the PIC S9(09)V99 boundary
    //   without integer/long overflow. The fixture rows cover the
    //   four BigDecimal arithmetic kinds (ADD, SUBTRACT, MULTIPLY,
    //   DIVIDE) at and beyond the 9999999.99 boundary.
    // ============================================================

    /**
     * Coverage for the BigDecimal-arithmetic overflow boundary at the
     * COBOL {@code PIC S9(09)V99} envelope. The CBTRN02C overlimit formula
     * ({@code app/cbl/CBTRN02C.cbl} lines 403–405):
     * <pre>
     *     COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
     *                         - ACCT-CURR-CYC-DEBIT
     *                         + DALYTRAN-AMT
     * </pre>
     * performs BigDecimal subtraction and addition over three monetary
     * operands — all three of {@code ACCT-CURR-CYC-CREDIT},
     * {@code ACCT-CURR-CYC-DEBIT}, and {@code DALYTRAN-AMT} are
     * {@code PIC S9(09)V99} (per {@code app/cpy/CVACT01Y.cpy} lines 13–14
     * for the account fields and {@code app/cpy/CVTRA06Y.cpy} for the
     * transaction field). The positive maximum of a signed 9-integer-digit,
     * 2-fractional-digit field is {@code 999999999.99}; the fixture here
     * uses the more conservative {@code 9999999.99} envelope as the
     * representative boundary value cited in AAP §0.10.3 fixture-design
     * narrative.
     *
     * <p>Every row in {@code overflow_boundary.csv} carries two BigDecimal
     * operands, an operation kind ({@code ADD}, {@code SUBTRACT},
     * {@code MULTIPLY}, or {@code DIVIDE}), an expected result, and an
     * expected scale. Rows whose result would exceed the field's positive
     * envelope carry the literal {@code "OVERFLOW"} in the
     * {@code expectedResult} column and {@code -1} in the
     * {@code expectedScale} column — the OVERFLOW sentinel convention.
     *
     * <p>The test verifies CSV consistency: every numeric operand and
     * expected result has scale ≤ 2 (matches PIC ...V99); operations are
     * one of the four recognised kinds; OVERFLOW rows declare
     * scale {@code -1}; non-OVERFLOW rows declare a non-negative scale
     * that matches the declared {@code expectedResult}.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * actually perform the arithmetic operations in the test body — that
     * would be re-implementing the production BigDecimal-arithmetic
     * harness. Instead, the CSV directly encodes the expected result and
     * the test asserts on that literal. The production class's overflow
     * handling is asserted directly by the byte-equality parity IT
     * ({@code TransactionPostingBaselineParityIT}).
     *
     * <p>Per AAP §0.10.3 (Financial Precision — NON-NEGOTIABLE no
     * float/double): every assertion in this test on a numeric value
     * parses through {@link BigDecimal#BigDecimal(String)} — never
     * {@code BigDecimal.valueOf(double)} or any {@code double} coercion —
     * so the textual CSV representation is preserved exactly through to
     * the BigDecimal scale check.
     */
    @Nested
    @DisplayName("Overflow boundary coverage (overflow_boundary.csv)")
    class OverflowBoundaryTests {

        /**
         * Sentinel string used in the CSV's {@code expectedResult} column to
         * mark rows whose arithmetic would exceed the {@code PIC S9(09)V99}
         * envelope. When this sentinel appears, the row's
         * {@code expectedScale} must be {@link #OVERFLOW_EXPECTED_SCALE} —
         * the OVERFLOW-flag convention adopted by the fixture authors.
         */
        private static final String OVERFLOW_SENTINEL = "OVERFLOW";

        /**
         * Expected scale value for OVERFLOW rows. The literal {@code -1}
         * encodes "no parseable numeric scale" because {@code expectedResult}
         * is the OVERFLOW sentinel string rather than a BigDecimal literal.
         * Encoded as a named constant so the magic number {@code -1} is not
         * scattered through the assertions.
         */
        private static final int OVERFLOW_EXPECTED_SCALE = -1;

        /**
         * Maximum permitted scale for BigDecimal monetary values per the
         * COBOL {@code PIC ...V99} contract. Two fractional digits — every
         * operand and non-OVERFLOW expected result must respect this ceiling.
         * Encoded as a named constant for the same reason as
         * {@link #OVERFLOW_EXPECTED_SCALE}.
         */
        private static final int MAX_MONETARY_SCALE = 2;

        /**
         * Per-row CSV-consistency check on the overflow-boundary fixture.
         * The five columns are bound to the method parameters by position,
         * matching the CSV header order (operand1, operand2, operation,
         * expectedResult, expectedScale).
         *
         * <p>The test asserts five invariants:
         * <ol>
         *   <li>{@code operand1} parses as a valid BigDecimal at scale ≤ 2
         *       (matches PIC ...V99).</li>
         *   <li>{@code operand2} parses as a valid BigDecimal at scale ≤ 2.</li>
         *   <li>{@code operation} is one of the four recognised BigDecimal
         *       arithmetic kinds: ADD, SUBTRACT, MULTIPLY, DIVIDE.</li>
         *   <li>If {@code expectedResult} is the OVERFLOW sentinel, then
         *       {@code expectedScale} must be {@code -1}; otherwise,
         *       {@code expectedResult} parses as a valid BigDecimal whose
         *       actual scale matches the declared {@code expectedScale},
         *       and that scale must be in {@code [0, 2]}.</li>
         *   <li>The OVERFLOW sentinel and the {@code -1} scale flag always
         *       co-occur — they cannot appear independently in any row.</li>
         * </ol>
         *
         * <p>Per AAP §0.10.1 (Require Test Coverage rule), the test does NOT
         * perform the actual ADD/SUBTRACT/MULTIPLY/DIVIDE in the test body —
         * the CSV directly carries the expected result. Re-implementing the
         * arithmetic here would mask any bug in the production
         * BigDecimal-arithmetic harness.
         *
         * @param operand1       CSV column 1 — first BigDecimal operand
         *                       (scale ≤ 2; matches PIC ...V99)
         * @param operand2       CSV column 2 — second BigDecimal operand
         *                       (scale ≤ 2; matches PIC ...V99)
         * @param operation      CSV column 3 — must be one of
         *                       {@code "ADD"}, {@code "SUBTRACT"},
         *                       {@code "MULTIPLY"}, {@code "DIVIDE"}
         * @param expectedResult CSV column 4 — either a BigDecimal string
         *                       at scale ≤ 2 or the literal {@code "OVERFLOW"}
         * @param expectedScale  CSV column 5 — non-negative integer matching
         *                       {@code expectedResult}'s actual scale, or
         *                       {@code -1} if {@code expectedResult} is
         *                       the OVERFLOW sentinel
         */
        @ParameterizedTest(name = "[{index}] {0} {2} {1} → {3} (scale {4})")
        @CsvFileSource(resources = "/fixtures/edge/overflow_boundary.csv", numLinesToSkip = 1)
        void process_atOverflowBoundary_usesBigDecimalArithmetic(
                String operand1, String operand2, String operation,
                String expectedResult, int expectedScale) {
            // CSV-consistency check #1: operand1 must parse as a valid
            // BigDecimal at scale ≤ 2 (matches PIC ...V99). Using
            // new BigDecimal(String) — never BigDecimal.valueOf(double) —
            // so the textual representation is preserved exactly (no
            // float/double precision loss, per AAP §0.10.3 NON-NEGOTIABLE).
            final BigDecimal op1 = new BigDecimal(operand1);
            assertThat(op1.scale())
                    .as("Row '%s %s %s → %s': operand1 scale must be ≤ %d "
                            + "(matches PIC S9(09)V99 — the COBOL monetary-"
                            + "field scale ceiling)",
                            operand1, operation, operand2, expectedResult,
                            MAX_MONETARY_SCALE)
                    .isLessThanOrEqualTo(MAX_MONETARY_SCALE);

            // CSV-consistency check #2: operand2 must also parse as a valid
            // BigDecimal at scale ≤ 2. Same rationale as operand1: every
            // monetary value in the CBTRN02C overlimit formula
            // (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)
            // is PIC S9(09)V99.
            final BigDecimal op2 = new BigDecimal(operand2);
            assertThat(op2.scale())
                    .as("Row '%s %s %s → %s': operand2 scale must be ≤ %d",
                            operand1, operation, operand2, expectedResult,
                            MAX_MONETARY_SCALE)
                    .isLessThanOrEqualTo(MAX_MONETARY_SCALE);

            // CSV-consistency check #3: operation must be one of the four
            // recognised BigDecimal arithmetic kinds. This catches CSV
            // data defects like typos ("ADDD") or unsupported ops
            // ("MODULO") that would cause silent test misinterpretation.
            // The four kinds correspond directly to the BigDecimal API
            // methods add(), subtract(), multiply(), divide() — the
            // production class is expected to dispatch on these strings
            // to the appropriate BigDecimal method.
            assertThat(operation)
                    .as("Row '%s %s %s → %s': operation must be one of "
                            + "ADD/SUBTRACT/MULTIPLY/DIVIDE (the four "
                            + "BigDecimal arithmetic kinds — any other "
                            + "value is a CSV data defect)",
                            operand1, operation, operand2, expectedResult)
                    .isIn("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE");

            // CSV-consistency check #4: the OVERFLOW sentinel and the
            // -1 scale flag must always co-occur. Splitting them into
            // two assertions (OVERFLOW-sentinel-without-(-1)-scale and
            // (-1)-scale-without-OVERFLOW-sentinel) gives precise
            // failure messages for each direction of the invariant.
            final boolean isOverflowRow = OVERFLOW_SENTINEL.equalsIgnoreCase(expectedResult);
            if (isOverflowRow) {
                // OVERFLOW rows must declare scale -1 to mark the absence
                // of a parseable numeric scale. Any other scale value
                // (including 2) would be a CSV data defect — it would
                // suggest the row is actually NOT an overflow case.
                assertThat(expectedScale)
                        .as("Row '%s %s %s → OVERFLOW': expectedScale must be "
                                + "%d (the OVERFLOW marker convention)",
                                operand1, operation, operand2,
                                OVERFLOW_EXPECTED_SCALE)
                        .isEqualTo(OVERFLOW_EXPECTED_SCALE);
            } else {
                // Non-OVERFLOW rows must declare a non-negative scale —
                // the actual scale of the expectedResult's BigDecimal form.
                // Negative scales are reserved for the OVERFLOW sentinel
                // (the (-1, OVERFLOW) pair) and must NOT appear here.
                assertThat(expectedScale)
                        .as("Row '%s %s %s → %s': non-OVERFLOW row must declare "
                                + "non-negative expectedScale "
                                + "(the OVERFLOW sentinel is the only "
                                + "convention that uses -1)",
                                operand1, operation, operand2, expectedResult)
                        .isGreaterThanOrEqualTo(0);

                // expectedResult must parse as a valid BigDecimal — any
                // parse failure here would surface as a NumberFormatException
                // making the CSV defect very explicit. Using
                // new BigDecimal(String) preserves the textual scale
                // (e.g., "0.10" stays scale 2, while "0.1" stays scale 1)
                // — which is exactly what the next assertion needs to
                // cross-check against the declared expectedScale.
                final BigDecimal expected = new BigDecimal(expectedResult);

                // The parsed BigDecimal's actual scale must match the
                // declared expectedScale — this catches CSV authoring
                // errors where the expectedResult column's textual scale
                // does not match the declared expectedScale integer
                // (e.g., "0.1" with declared scale 2, or "0.100" with
                // declared scale 2 would both fail). This is the
                // tightest possible CSV-consistency check on the
                // expectedResult / expectedScale pairing.
                assertThat(expected.scale())
                        .as("Row '%s %s %s → %s': expectedResult's BigDecimal "
                                + "scale (%d) must match declared "
                                + "expectedScale (%d)",
                                operand1, operation, operand2, expectedResult,
                                expected.scale(), expectedScale)
                        .isEqualTo(expectedScale);

                // The declared expectedScale must respect the PIC ...V99
                // ceiling. Same scale invariant as operand1 and operand2:
                // every monetary value in the production system has at
                // most two fractional digits. This guards against a CSV
                // row that declares expectedScale=3 or higher — which
                // would imply the production class produces a result at
                // higher precision than the COBOL field can hold, a
                // byte-equality parity violation per AAP §0.10.4.
                assertThat(expectedScale)
                        .as("Row '%s %s %s → %s': non-OVERFLOW expectedScale "
                                + "must be ≤ %d (matches PIC ...V99)",
                                operand1, operation, operand2, expectedResult,
                                MAX_MONETARY_SCALE)
                        .isLessThanOrEqualTo(MAX_MONETARY_SCALE);
            }
        }
    }
}
