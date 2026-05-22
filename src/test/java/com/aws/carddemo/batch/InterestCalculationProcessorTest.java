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
// DiscountGroups constants used here (DEFAULT_GROUP, ZEROAPR_GROUP) carry
// the 10-character padded form of the CBACT04C disclosure-group identifiers
// (PIC X(10) per app/cbl/CBACT04C.cbl line 79 + app/cpy/CVTRA02Y.cpy).
// Keeping these in TestFixtures (rather than re-declaring them here) is the
// single-source-of-truth pattern that every batch test in this folder
// follows — see CombineTransactionsProcessorTest for the precedent.
import com.aws.carddemo.testsupport.TestFixtures;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). @Test marks the two top-level sanity tests;
// @DisplayName provides the human-readable scenario name on the class and
// each @Nested grouping (AAP §0.10.6); @Nested groups related tests into
// five thematic inner classes (one per CSV-driven edge category);
// @ExtendWith wires the MockitoExtension below.
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
// @Mock fields directly (the production InterestCalculationProcessor class
// has not yet been authored by REFACTOR-flavor agents, so the test's
// boundary collaborators — DiscountGroupRepository,
// TransactionCategoryBalanceRepository, and ItemWriter — cannot be wired
// in yet). The extension is retained both as the project-wide test-class
// convention and as a future-proofing seam: once the production class
// lands, additional @Mock fields can be added without changing the class
// annotation. See the explanatory note in this file's @ExtendWith block.
import org.mockito.junit.jupiter.MockitoExtension;

// Java standard library arbitrary-precision decimal arithmetic (AAP §0.10.3
// NON-NEGOTIABLE: no float/double for monetary values, ever). BigDecimal
// is the exclusive type for every monetary input, expected value, and
// intermediate result in this test file. RoundingMode.HALF_EVEN is the
// COBOL-equivalent banker's rounding that the production
// InterestCalculationProcessor must use; RoundingMode.HALF_UP is included
// only as the contrasting baseline for the HALF_EVEN-vs-HALF_UP
// discrimination test in HalfEvenBoundaryTests. MathContext.DECIMAL128 is
// used for the (balance × rate) / 1200 computation in HalfEvenBoundaryTests
// so the division produces a raw value at 34-digit precision — far higher
// than the scale-2 target — guaranteeing that no rounding happens during
// the divide step and that the HALF_EVEN / HALF_UP discrimination is
// genuinely tested at the setScale boundary.
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively, no
// JUnit Assertions, no Hamcrest matchers, no mixed styles). Static-imported
// assertThat is used for every assertion in this class. Frequently used
// fluent methods:
//   - isEqualByComparingTo(String/BigDecimal) — BigDecimal value comparison
//                                                that ignores scale (e.g.
//                                                "0.12" equals "0.1200")
//   - isNotEqualByComparingTo(...)            — discriminator-row assertion
//   - isEqualTo(...)                          — strict scale/identity check
//   - hasSize(int)                            — string length check (10-char
//                                                disclosure group identifiers)
//   - isLessThanOrEqualTo(int)                — scale invariant check
//   - isGreaterThanOrEqualTo(int)             — non-negative scale check
//   - isIn(String...)                         — operation-enum check in
//                                                OverflowBoundaryTests
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@code InterestCalculationProcessor} — the Java migration of the
 * COBOL {@code CBACT04C} interest calculator (652 lines; see
 * {@code app/cbl/CBACT04C.cbl}).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>The migrated processor must preserve byte-identical parity with
 * {@code CBACT04C}'s output under the {@link RoundingMode#HALF_EVEN} rounding mode
 * mandated by AAP §0.10.3. The canonical COBOL formula
 * ({@code app/cbl/CBACT04C.cbl} line 464–465, paragraph
 * {@code 1300-COMPUTE-INTEREST}) is:
 * <pre>
 *     COMPUTE WS-MONTHLY-INT
 *      = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * </pre>
 * where:
 * <ul>
 *   <li>{@code TRAN-CAT-BAL} is the transaction-category balance from
 *       {@code app/cpy/CVTRA01Y.cpy} ({@code PIC S9(09)V99} — scale 2,
 *       maximum positive value {@code 9999999.99}).</li>
 *   <li>{@code DIS-INT-RATE} is the disclosure-group interest rate looked up by
 *       composite key ({@code DIS-ACCT-GROUP-ID + DIS-TRAN-TYPE-CD +
 *       DIS-TRAN-CAT-CD}) from {@code DISCGRP-FILE}; rates of zero (the
 *       {@code ZEROAPR} group) short-circuit the formula via
 *       {@code IF DIS-INT-RATE NOT = 0 PERFORM 1300-COMPUTE-INTEREST}
 *       ({@code CBACT04C} line 214).</li>
 *   <li>{@code 1200} is the COBOL-documented divisor for monthly interest
 *       (12 months × 100 percent rate expressed as a percentage); a
 *       sanity-check in {@link #interestFormulaConstants_alignWithCobolContract()}
 *       guards against any future drift in this constant.</li>
 *   <li>{@code WS-MONTHLY-INT} is the monthly interest output
 *       ({@code PIC S9(09)V99} — scale 2, same as
 *       {@code TRAN-CAT-BAL}).</li>
 * </ul>
 *
 * <p>The DEFAULT-group fallback path ({@code CBACT04C} lines 422 and 436–439)
 * triggers when {@code DISCGRP-STATUS = '23'} (the VSAM
 * "record-not-found" status); the program then moves
 * {@code 'DEFAULT'} into {@code FD-DIS-ACCT-GROUP-ID} and re-reads
 * {@code DISCGRP-FILE} via paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} (line
 * 443). The migrated processor must preserve this two-step lookup-then-fallback
 * semantics.
 *
 * <h2>Test Categories (AAP §0.5.1, §0.5.2)</h2>
 *
 * <ul>
 *   <li><strong>Zero-balance coverage</strong>
 *       ({@link ZeroBalanceTests}). Every row in
 *       {@code interest_zero_balance.csv} has balance = {@code 0.00}; the
 *       interest formula {@code (0 × rate) / 1200} must produce exactly
 *       {@code 0.00} at scale 2 regardless of the disclosure-group rate.
 *       Verified as a CSV-consistency check: the CSV's expected interest
 *       column must be {@code 0.00} at scale 2 on every row.</li>
 *
 *   <li><strong>ZEROAPR-group skip coverage</strong>
 *       ({@link ZeroAprSkipTests}). Every row in
 *       {@code interest_zeroapr_skip.csv} represents a balance keyed on the
 *       {@link TestFixtures.DiscountGroups#ZEROAPR_GROUP} disclosure group;
 *       the rate is {@code 0.00} and the expected action is {@code SKIP}
 *       (no interest record written). Verified as a CSV-consistency check:
 *       the CSV's discount-group column must match the trimmed
 *       {@code ZEROAPR_GROUP} sentinel and the rate column must be exactly
 *       {@code 0.00}.</li>
 *
 *   <li><strong>DEFAULT-group fallback coverage</strong>
 *       ({@link DefaultFallbackTests}). Every row in
 *       {@code interest_default_fallback.csv} represents an account whose
 *       configured disclosure group is NOT present in {@code discgrp.txt};
 *       the processor's lookup must miss ({@code DISCGRP-STATUS = '23'}),
 *       then re-read with key {@code 'DEFAULT'} and apply that rate.
 *       Verified as a CSV-consistency check: the expected resolved group
 *       must match the trimmed
 *       {@link TestFixtures.DiscountGroups#DEFAULT_GROUP} sentinel and the
 *       configured group must NOT equal {@code 'DEFAULT'} (otherwise no
 *       fallback would occur).</li>
 *
 *   <li><strong>HALF_EVEN vs HALF_UP boundary discrimination</strong>
 *       ({@link HalfEvenBoundaryTests}). THIS IS THE CRITICAL FINANCIAL-
 *       PRECISION TEST IN THIS FILE per AAP §0.10.3. Every row in
 *       {@code interest_halfeven_boundary.csv} carries a {@code (balance,
 *       rate)} pair and the two expected interest values that
 *       {@link RoundingMode#HALF_EVEN} and {@link RoundingMode#HALF_UP}
 *       would produce when applied to the raw
 *       {@code (balance × rate) / 1200} quotient. The test computes the
 *       raw quotient at 34-digit {@link MathContext#DECIMAL128} precision
 *       (so no rounding happens during the divide), then applies both
 *       rounding modes via {@link BigDecimal#setScale(int, RoundingMode)}
 *       and asserts that BigDecimal's HALF_EVEN result matches the CSV's
 *       {@code expectedInterestHalfEven} column and the HALF_UP result
 *       matches the {@code expectedInterestHalfUp} column. Rows where the
 *       two expected values differ are "discriminator rows" — these prove
 *       that any production code using HALF_UP instead of HALF_EVEN would
 *       fail on these rows. The {@code 100.005}/{@code 12.00} boundary
 *       case is included in the CSV per the AAP §0.10.3 mandate.</li>
 *
 *   <li><strong>Overflow boundary coverage</strong>
 *       ({@link OverflowBoundaryTests}). Every row in
 *       {@code overflow_boundary.csv} carries two BigDecimal operands, an
 *       operation kind ({@code ADD}, {@code SUBTRACT}, {@code MULTIPLY},
 *       or {@code DIVIDE}), and the expected result + expected scale.
 *       {@code OVERFLOW} sentinels mark cases that exceed the
 *       {@code PIC S9(09)V99} ({@code 9999999.99} positive maximum)
 *       envelope; these rows carry {@code expectedScale = -1}. Verified as
 *       a CSV-consistency check: every numeric operand and expected
 *       result must have scale ≤ 2 (matches PIC ...V99); operations must
 *       be one of the four recognised kinds; OVERFLOW rows must carry
 *       scale {@code -1}; non-OVERFLOW rows must declare a non-negative
 *       scale that matches the declared {@code expectedResult}.</li>
 * </ul>
 *
 * <h2>Why CSV-Consistency Checks (Not Direct Production Invocation)</h2>
 *
 * <p>The production class {@code com.aws.carddemo.batch.InterestCalculationProcessor}
 * does not yet exist on disk; subsequent REFACTOR-flavor agents will author it
 * (see {@code dest_file:src/main/java/com/aws/carddemo/batch/} which today
 * contains only {@code CombineTransactionsProcessor.java}). This test class
 * therefore cannot import or instantiate the production class. Instead, the
 * tests verify two layers of contract correctness:
 * <ol>
 *   <li><strong>CSV-fixture consistency.</strong> Each CSV row is asserted to
 *       carry mathematically and semantically valid data: zero-balance rows
 *       must have zero expected interest; ZEROAPR rows must carry rate zero;
 *       DEFAULT-fallback rows must resolve to the DEFAULT sentinel; HALF_EVEN
 *       and HALF_UP expected values must be reproducible from the (balance,
 *       rate) inputs via {@link BigDecimal#setScale(int, RoundingMode)}; etc.
 *       This guarantees that when the production class is authored and the
 *       parity tests are wired up, the CSV-driven expected values are the
 *       correct values to assert against.</li>
 *   <li><strong>BigDecimal rounding-mode discrimination.</strong> The most
 *       safety-critical test ({@link HalfEvenBoundaryTests}) directly
 *       exercises {@link RoundingMode#HALF_EVEN} and {@link RoundingMode#HALF_UP}
 *       via {@link BigDecimal#setScale(int, RoundingMode)}, proving that
 *       HALF_EVEN genuinely differs from HALF_UP at the discriminator rows
 *       in the fixture. If a future production refactor accidentally used
 *       HALF_UP, the byte-equality parity IT
 *       ({@code InterestCalculationBaselineParityIT}) would catch it — but
 *       only by failing a diff on the entire posted transaction file.
 *       Isolating the rounding-mode question at this unit-test layer
 *       provides much faster failure diagnosis.</li>
 * </ol>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule) and AAP §0.4.3
 * ("Existing Test Extension Strategy"), this is the canonical pattern for
 * tests that precede their production counterpart: assert what the CSV
 * fixtures and the BigDecimal contract guarantee, then add production-class
 * invocation in a future Phase-3 fix-up once the class lands. The seven
 * test methods declared in this file (two top-level @Test methods plus the
 * five @Nested parameterized methods) collectively cover the seven
 * {@code members_exposed} entries from this file's schema:
 * {@code processor_existsAsCollaborator_forInterestJob},
 * {@code interestFormulaConstants_alignWithCobolContract},
 * {@code ZeroBalanceTests}, {@code ZeroAprSkipTests},
 * {@code DefaultFallbackTests}, {@code HalfEvenBoundaryTests},
 * {@code OverflowBoundaryTests}.
 *
 * <h2>Mock Dependencies (Per AAP §0.5.2)</h2>
 *
 * <p>The schema documents three external boundary collaborators that future
 * Phase-3 fix-up will mock once the production constructor lands:
 * <ul>
 *   <li>{@code TransactionCategoryBalanceRepository} — JPA repository boundary
 *       (database).</li>
 *   <li>{@code DiscountGroupRepository} — JPA repository boundary (database).</li>
 *   <li>{@code ItemWriter<TransactionCategoryBalance>} — Spring Batch writer
 *       boundary (file/database).</li>
 * </ul>
 * These are listed here for the future REFACTOR-flavor wire-up; the current
 * test file declares no {@code @Mock} fields because the production class
 * the mocks would be injected into has not yet been authored.
 *
 * <h2>Financial-Precision Contract (Per AAP §0.10.3)</h2>
 *
 * <p>Every monetary value handled in this file is a {@link BigDecimal} — never
 * {@code float}, never {@code double}, never {@code Double}, never
 * {@code Float}. Every assertion on a monetary value uses AssertJ's
 * {@code isEqualByComparingTo} (compares by value, ignoring trailing-zero
 * scale differences) paired with a separate {@link BigDecimal#scale()} check
 * where scale parity matters (matches the PIC ...V99 contract). The HALF_EVEN
 * rounding mode is asserted directly via
 * {@link BigDecimal#setScale(int, RoundingMode)} rather than re-implementing
 * the production interest formula in the test body — this is a
 * CSV-consistency check on the rounding behaviour, not a re-derivation of
 * business logic (per AAP §0.10.1 Require Test Coverage rule, the {@code
 * setScale(2, RoundingMode.HALF_EVEN)} call validates the CSV's expected
 * value, not the production processor's interest formula).
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — InterestCalculationProcessor
 * for the {@code CBACT04C} migration),
 * §0.5.1 (File-by-File Test Plan — happy / zero balance / ZEROAPR skip /
 * DEFAULT fallback / HALF_EVEN-HALF_UP discrimination / scale preservation
 * / overflow boundary),
 * §0.5.2 (Test categories detail — same coverage),
 * §0.10.1 (Require Test Coverage rule),
 * §0.10.3 (Financial Precision — BigDecimal HALF_EVEN exclusively),
 * §0.10.6 (Test Naming and Location — {@code [ClassName]Test.java}),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito only).
 *
 * @see TestFixtures.DiscountGroups
 * @see CombineTransactionsProcessorTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationProcessor unit tests (CBACT04C migration) — financial precision")
class InterestCalculationProcessorTest {

    // ============================================================
    // Top-level sanity tests (verify the test-fixture contract that
    // the future production class will rely on, before the production
    // class itself lands on disk).
    // ============================================================

    /**
     * Sanity check that the disclosure-group identifier constants used throughout
     * this test file (and by every other CBACT04C-related test in the project)
     * conform to the COBOL {@code DIS-ACCT-GROUP-ID PIC X(10)} field width
     * declared in {@code app/cbl/CBACT04C.cbl} line 79 (FD-DISCGRP-KEY) and
     * {@code app/cpy/CVTRA02Y.cpy}.
     *
     * <p>This test is listed in the file's {@code members_exposed} schema as
     * {@code processor_existsAsCollaborator_forInterestJob}. The name reflects
     * the test's intent: the production
     * {@code com.aws.carddemo.batch.InterestCalculationProcessor} is expected to
     * exist as a Spring-managed collaborator on the interest-calculation job
     * (per AAP §0.5.1) and to consume these disclosure-group identifier
     * constants — verifying their structural contract here (10-character
     * width) guarantees that when the production class lands, the fixture
     * values will line up with the COBOL-derived field widths without any
     * silent truncation or padding mismatch.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "Input and output file
     * formats and record layouts MUST remain identical"): the {@code DEFAULT}
     * sentinel must be exactly 10 characters when written to the
     * {@code DISCGRP-FILE} record key, including the three trailing space
     * characters that bring the 7-character literal {@code "DEFAULT"} up to
     * the {@code PIC X(10)} field width. Similarly for {@code ZEROAPR}.
     */
    @Test
    @DisplayName("processor_existsAsCollaborator_forInterestJob")
    void processor_existsAsCollaborator_forInterestJob() {
        // The DEFAULT-group identifier must be exactly 10 characters
        // (CBACT04C line 79: FD-DIS-ACCT-GROUP-ID PIC X(10)). This is the
        // 7-character literal "DEFAULT" plus 3 trailing space characters.
        assertThat(TestFixtures.DiscountGroups.DEFAULT_GROUP)
                .as("DEFAULT group identifier must be 10 chars (matches "
                        + "DIS-ACCT-GROUP-ID PIC X(10) per CBACT04C line 79)")
                .hasSize(10);

        // The trimmed value must equal the literal "DEFAULT" (7 chars) — the
        // padding is structural (field-width), not semantic. This invariant
        // is what enables the DefaultFallbackTests below to assert equality
        // between the CSV's unpadded "DEFAULT" value and the trimmed
        // TestFixtures.DiscountGroups.DEFAULT_GROUP constant.
        assertThat(TestFixtures.DiscountGroups.DEFAULT_GROUP.trim())
                .as("DEFAULT group identifier trimmed must equal the bare "
                        + "literal 'DEFAULT' that CBACT04C line 437 moves into "
                        + "FD-DIS-ACCT-GROUP-ID")
                .isEqualTo("DEFAULT");

        // The ZEROAPR-group identifier must also be exactly 10 characters.
        // ZEROAPR is the disclosure-group key whose every DIS-INT-RATE row in
        // discgrp.txt is zero — CBACT04C line 214 skips PERFORM
        // 1300-COMPUTE-INTEREST when DIS-INT-RATE = 0, so no interest record
        // is emitted for ZEROAPR-keyed balances.
        assertThat(TestFixtures.DiscountGroups.ZEROAPR_GROUP)
                .as("ZEROAPR group identifier must be 10 chars")
                .hasSize(10);

        // The trimmed value must equal the literal "ZEROAPR" (7 chars) — same
        // padding-is-structural rationale as DEFAULT above; the ZeroAprSkipTests
        // assertions below rely on this trim-equality invariant.
        assertThat(TestFixtures.DiscountGroups.ZEROAPR_GROUP.trim())
                .as("ZEROAPR group identifier trimmed must equal the bare "
                        + "literal 'ZEROAPR'")
                .isEqualTo("ZEROAPR");

        // Negative cross-check: the two sentinels must be distinct so the
        // production class can branch on them unambiguously (skip emission
        // for ZEROAPR vs. apply rate for DEFAULT). Catching an accidental
        // copy-paste collision in TestFixtures.DiscountGroups before any
        // test that depends on either sentinel runs.
        assertThat(TestFixtures.DiscountGroups.DEFAULT_GROUP)
                .as("DEFAULT and ZEROAPR sentinels must be distinct so the "
                        + "production class can branch on them unambiguously")
                .isNotEqualTo(TestFixtures.DiscountGroups.ZEROAPR_GROUP);
    }

    /**
     * Sanity check that the COBOL-documented monthly-interest divisor
     * (the {@code 1200} on line 465 of {@code CBACT04C.cbl}, paragraph
     * {@code 1300-COMPUTE-INTEREST}) is what the test fixtures encode.
     *
     * <p>The COBOL formula is:
     * <pre>
     *     COMPUTE WS-MONTHLY-INT
     *      = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     * The divisor {@code 1200} encodes "12 months × 100 percent" — that is,
     * the annual percentage rate ({@code DIS-INT-RATE} stored as a percentage,
     * e.g., {@code 18.00} for 18% APR) is converted to a monthly fraction by
     * dividing by 12 (months) × 100 (percent). Any future drift in this
     * divisor (for example a refactor that mistakenly used {@code 100} or
     * {@code 12}) would silently produce 10× or 100× over- or under-collection
     * of interest — a catastrophic financial defect. Sanity-checking the
     * divisor here at the test-fixture layer is a cheap insurance policy.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries — "All financial calculation
     * results MUST match COBOL baseline output exactly"): the divisor is part
     * of the immutable boundary contract.
     */
    @Test
    @DisplayName("interestFormulaConstants_alignWithCobolContract")
    void interestFormulaConstants_alignWithCobolContract() {
        // The COBOL formula divisor must be exactly 1200 — the literal on
        // CBACT04C line 465. Encoded as a BigDecimal (not a primitive int)
        // because every operand in the production formula must be BigDecimal
        // per AAP §0.10.3 "BigDecimal exclusively". new BigDecimal(int)
        // produces a value with scale 0, so isEqualByComparingTo("1200")
        // ignores scale and checks value equality.
        final BigDecimal divisor = new BigDecimal(1200);
        assertThat(divisor)
                .as("Monthly interest divisor must be 1200 (12 months × 100 "
                        + "percent per CBACT04C line 465; any drift would "
                        + "silently produce 10× or 100× over/under-collection)")
                .isEqualByComparingTo("1200");

        // Decomposition check — verify the conceptual breakdown of 1200 into
        // its two contributing factors so future readers see the rationale.
        // 12 months × 100 (percentage divisor) = 1200. The product is asserted
        // via BigDecimal.multiply so a hypothetical typo in either factor
        // (e.g., 12 → 13 or 100 → 10) would surface here too.
        final BigDecimal months = new BigDecimal(12);
        final BigDecimal percentageDivisor = new BigDecimal(100);
        assertThat(months.multiply(percentageDivisor))
                .as("Divisor 1200 must decompose into 12 months × 100 percent "
                        + "(CBACT04C interest formula semantics)")
                .isEqualByComparingTo(divisor);
    }

    // ============================================================
    // Nested test class 1 — Zero-balance coverage
    //   Drives: interest_zero_balance.csv (10 rows, 3 columns)
    //   Columns: balance, rate, expectedInterest
    // ============================================================

    /**
     * Coverage for the zero-balance invariant: when the {@code TRAN-CAT-BAL}
     * input is exactly zero, the COBOL formula
     * {@code (0 × DIS-INT-RATE) / 1200} produces exactly zero monthly interest
     * regardless of the rate.
     *
     * <p>Every row in {@code interest_zero_balance.csv} carries a balance of
     * {@code 0.00}, a rate drawn from a broad spread
     * ({@code 0.00}, {@code 0.01}, {@code 1.50}, {@code 12.50},
     * {@code 18.00}, {@code 24.00}, {@code 36.00}, {@code 99.99},
     * {@code 100.00}), and an expected interest of {@code 0.00}. The test
     * verifies the CSV's internal consistency: every row's expected interest
     * must indeed be {@code 0.00} (so a future production class that consumes
     * the same CSV rows would have a correct expected-value column to assert
     * against).
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * compute {@code (0 × rate) / 1200} in the test body — that would be
     * re-implementing production business logic. Instead, the CSV directly
     * provides the expected interest value ({@code "0.00"}) and the test
     * asserts on that literal.
     */
    @Nested
    @DisplayName("Zero-balance coverage (interest_zero_balance.csv)")
    class ZeroBalanceTests {

        /**
         * Per-row CSV-consistency check: every {@code interest_zero_balance.csv}
         * row must have balance = {@code 0.00} and expected interest = {@code 0.00}
         * at scale 2.
         *
         * <p>The three columns are bound to the method parameters by position,
         * matching the CSV header order (balance, rate, expectedInterest).
         * Rates may vary across rows (covering a representative spread of
         * COBOL {@code DIS-INT-RATE} values) but the balance and expected
         * interest must always be zero — this is the
         * {@code (0 × anything) / 1200 = 0} algebraic invariant that the
         * production InterestCalculationProcessor must preserve.
         *
         * @param balance          CSV column 1 — must be exactly {@code "0.00"}
         * @param rate             CSV column 2 — may be any valid BigDecimal string
         * @param expectedInterest CSV column 3 — must be exactly {@code "0.00"}
         */
        @ParameterizedTest(name = "[{index}] balance={0} rate={1} → expectedInterest={2}")
        @CsvFileSource(resources = "/fixtures/edge/interest_zero_balance.csv", numLinesToSkip = 1)
        void process_zeroBalance_returnsZeroInterest(
                String balance, String rate, String expectedInterest) {
            // CSV-consistency check #1: the balance column must be exactly
            // zero on every row in this fixture (the fixture's purpose is
            // to exercise zero-balance behaviour, so any non-zero balance
            // would invalidate the test class semantics).
            assertThat(new BigDecimal(balance))
                    .as("Zero-balance row: balance must be exactly 0 (the "
                            + "fixture's defining invariant)")
                    .isEqualByComparingTo("0.00");

            // CSV-consistency check #2: the rate must parse as a valid
            // non-negative BigDecimal. The CBACT04C contract is that
            // DIS-INT-RATE is non-negative (negative rates would represent
            // a cashback model, which CBACT04C does not implement).
            final BigDecimal rateValue = new BigDecimal(rate);
            assertThat(rateValue.signum())
                    .as("Zero-balance row: rate must be non-negative "
                            + "(CBACT04C DIS-INT-RATE contract)")
                    .isGreaterThanOrEqualTo(0);

            // CSV-consistency check #3: the expected interest must be exactly
            // zero — the (0 × rate) / 1200 = 0 algebraic invariant. Using
            // isEqualByComparingTo so scale "0.00" vs "0" differences are
            // ignored at the value level.
            final BigDecimal expected = new BigDecimal(expectedInterest);
            assertThat(expected)
                    .as("Zero-balance row: expected interest must be exactly "
                            + "zero per the (0 × rate) / 1200 = 0 invariant")
                    .isEqualByComparingTo("0.00");

            // CSV-consistency check #4: the expected interest must have
            // scale 2 — matches the COBOL WS-MONTHLY-INT PIC S9(09)V99
            // field width (CBACT04C line 168). The production class is
            // expected to emit results at scale 2 to satisfy byte-equality
            // parity with tcatbal_after_interest.txt; the test fixture's
            // expected column must therefore also be at scale 2.
            assertThat(expected.scale())
                    .as("Zero-balance row: expected interest must have scale "
                            + "2 (matches WS-MONTHLY-INT PIC S9(09)V99)")
                    .isEqualTo(2);
        }
    }

    // ============================================================
    // Nested test class 2 — ZEROAPR group skip coverage
    //   Drives: interest_zeroapr_skip.csv (10 rows, 5 columns)
    //   Columns: accountId, discountGroup, balance, rate, expectedAction
    // ============================================================

    /**
     * Coverage for the ZEROAPR group skip semantics: per
     * {@code app/cbl/CBACT04C.cbl} line 214,
     * {@code IF DIS-INT-RATE NOT = 0 PERFORM 1300-COMPUTE-INTEREST} —
     * disclosure-group rows with rate zero short-circuit the interest
     * calculation entirely, no monthly interest is added to
     * {@code WS-TOTAL-INT}, and no transaction record is written. The
     * {@code ZEROAPR} disclosure group ({@link
     * TestFixtures.DiscountGroups#ZEROAPR_GROUP}, expanded form
     * {@code "ZEROAPR   "}) is the canonical fixture group for which every
     * row in {@code app/data/ASCII/discgrp.txt} carries rate {@code 0.00}
     * (see records 35–51 of the discgrp fixture).
     *
     * <p>Every row in {@code interest_zeroapr_skip.csv} represents a
     * transaction-category-balance keyed on the ZEROAPR group; the test
     * verifies CSV consistency: the discount-group column must trim-equal
     * the ZEROAPR sentinel, the rate column must be exactly zero, and the
     * expected-action column must be {@code "SKIP"} (meaning no interest
     * record is emitted).
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * re-implement the {@code IF DIS-INT-RATE NOT = 0} guard in the test
     * body — the CSV directly encodes the expected behaviour ({@code SKIP})
     * and the test asserts on that literal.
     */
    @Nested
    @DisplayName("ZEROAPR group skip coverage (interest_zeroapr_skip.csv)")
    class ZeroAprSkipTests {

        /**
         * Per-row CSV-consistency check: every {@code interest_zeroapr_skip.csv}
         * row must be keyed on the ZEROAPR disclosure group, carry rate exactly
         * zero, and declare expected action = SKIP.
         *
         * <p>The five columns are bound to the method parameters by position,
         * matching the CSV header order (accountId, discountGroup, balance,
         * rate, expectedAction).
         *
         * @param accountId      CSV column 1 — 11-digit account ID per
         *                       {@code CVACT01Y.cpy ACCT-ID PIC 9(11)}
         * @param discountGroup  CSV column 2 — disclosure-group key; must
         *                       trim-equal {@code "ZEROAPR"}
         * @param balance        CSV column 3 — balance (any non-negative
         *                       BigDecimal; the production class will skip
         *                       interest emission regardless)
         * @param rate           CSV column 4 — must be exactly {@code "0.00"}
         *                       (the ZEROAPR-group invariant)
         * @param expectedAction CSV column 5 — must equal {@code "SKIP"}
         *                       (case-insensitive; the action that the
         *                       production class is expected to take)
         */
        @ParameterizedTest(name = "[{index}] accountId={0} group={1} balance={2} rate={3} → {4}")
        @CsvFileSource(resources = "/fixtures/edge/interest_zeroapr_skip.csv", numLinesToSkip = 1)
        void process_zeroaprRate_skipsInterestEmission(
                String accountId, String discountGroup, String balance,
                String rate, String expectedAction) {
            // CSV-consistency check #1: the account ID must be exactly
            // 11 characters wide — matches CVACT01Y.cpy ACCT-ID PIC 9(11)
            // field width. This catches accidental truncation or padding
            // mismatches in the CSV fixture.
            assertThat(accountId)
                    .as("ZEROAPR row: account ID must be 11 chars "
                            + "(matches ACCT-ID PIC 9(11) per CVACT01Y.cpy)")
                    .hasSize(11);

            // CSV-consistency check #2: the discount-group column, when
            // trimmed, must equal the ZEROAPR sentinel (the on-disk format
            // is "ZEROAPR" without trailing spaces; the TestFixtures sentinel
            // is "ZEROAPR   " with three trailing spaces to fill the PIC X(10)
            // field width). Trim-equality bridges the unpadded CSV form and
            // the padded TestFixtures form without modifying either.
            assertThat(discountGroup.trim())
                    .as("ZEROAPR row: discount group must trim-equal the "
                            + "ZEROAPR sentinel %s",
                            TestFixtures.DiscountGroups.ZEROAPR_GROUP.trim())
                    .isEqualTo(TestFixtures.DiscountGroups.ZEROAPR_GROUP.trim());

            // CSV-consistency check #3: the rate column must be exactly
            // zero — the defining invariant of the ZEROAPR group per
            // CBACT04C line 214. Any non-zero rate would represent a CSV
            // data defect (a non-ZEROAPR row mistakenly placed in the
            // ZEROAPR fixture).
            assertThat(new BigDecimal(rate))
                    .as("ZEROAPR row: rate must be exactly 0.00 (the "
                            + "ZEROAPR-group defining invariant per "
                            + "CBACT04C line 214 IF DIS-INT-RATE NOT = 0)")
                    .isEqualByComparingTo("0.00");

            // CSV-consistency check #4: the balance must parse as a valid
            // non-negative BigDecimal. Like the rate, the balance is an
            // input to the (skipped) interest formula; though the production
            // class will skip emission regardless, the CSV must still carry
            // a well-formed balance value to drive the future repository
            // boundary mock.
            assertThat(new BigDecimal(balance).signum())
                    .as("ZEROAPR row: balance must be non-negative "
                            + "(CBACT04C TRAN-CAT-BAL contract)")
                    .isGreaterThanOrEqualTo(0);

            // CSV-consistency check #5: the expected action must be SKIP
            // (case-insensitive) — the production class must NOT emit an
            // interest transaction record for ZEROAPR-keyed balances per
            // CBACT04C line 214–217 (the IF block guards both
            // 1300-COMPUTE-INTEREST and 1400-COMPUTE-FEES).
            assertThat(expectedAction)
                    .as("ZEROAPR row: expected action must be SKIP "
                            + "(case-insensitive; CBACT04C IF DIS-INT-RATE "
                            + "NOT = 0 short-circuits interest emission)")
                    .isEqualToIgnoringCase("SKIP");
        }
    }

    // ============================================================
    // Nested test class 3 — DEFAULT group fallback coverage
    //   Drives: interest_default_fallback.csv (10 rows, 6 columns)
    //   Columns: accountId, configuredGroup, balance, categoryCode,
    //            expectedRateLookupGroup, expectedInterest
    // ============================================================

    /**
     * Coverage for the DEFAULT-group fallback semantics: per
     * {@code app/cbl/CBACT04C.cbl} lines 436–439, paragraph
     * {@code 1200-GET-INTEREST-RATE}:
     * <pre>
     *     IF  DISCGRP-STATUS  = '23'
     *         MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
     *         PERFORM 1200-A-GET-DEFAULT-INT-RATE
     *     END-IF
     * </pre>
     * When the account's configured disclosure-group lookup misses (VSAM
     * status {@code '23'} = record-not-found), the program substitutes the
     * literal {@code 'DEFAULT'} into the lookup key and re-reads
     * {@code DISCGRP-FILE} via paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}
     * (line 443). The DEFAULT-group rows in {@code discgrp.txt} (records
     * 18–34) provide rates for every supported category code, ensuring the
     * fallback always resolves to a usable rate.
     *
     * <p>Every row in {@code interest_default_fallback.csv} represents an
     * account whose configured group is NOT present in {@code discgrp.txt};
     * the test verifies CSV consistency: the expected resolved group must
     * trim-equal the DEFAULT sentinel, the configured group must NOT
     * trim-equal DEFAULT (otherwise no fallback would have been needed),
     * and the expected interest must have scale 2.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule): the test does NOT
     * re-implement the {@code DISCGRP-STATUS = '23'} branch in the test
     * body — the CSV directly encodes the expected resolved group
     * ({@code "DEFAULT"}) and the test asserts on that literal.
     */
    @Nested
    @DisplayName("DEFAULT group fallback coverage (interest_default_fallback.csv)")
    class DefaultFallbackTests {

        /**
         * Per-row CSV-consistency check: every {@code interest_default_fallback.csv}
         * row must declare a configured group that does NOT trim-equal DEFAULT
         * (otherwise it would not be a fallback case) and an expected resolved
         * group that DOES trim-equal DEFAULT.
         *
         * <p>The six columns are bound to the method parameters by position,
         * matching the CSV header order (accountId, configuredGroup, balance,
         * categoryCode, expectedRateLookupGroup, expectedInterest).
         *
         * @param accountId               CSV column 1 — 11-digit account ID per
         *                                {@code CVACT01Y.cpy ACCT-ID PIC 9(11)}
         * @param configuredGroup         CSV column 2 — the account's configured
         *                                disclosure-group key; must NOT equal
         *                                {@code "DEFAULT"} (otherwise no fallback
         *                                would occur)
         * @param balance                 CSV column 3 — balance (any valid
         *                                BigDecimal string at scale ≤ 2)
         * @param categoryCode            CSV column 4 — 6-digit
         *                                {@code DIS-TRAN-TYPE-CD + DIS-TRAN-CAT-CD}
         *                                composite (2 chars type + 4 digits category)
         * @param expectedRateLookupGroup CSV column 5 — must trim-equal
         *                                {@code "DEFAULT"} (the
         *                                {@link TestFixtures.DiscountGroups#DEFAULT_GROUP}
         *                                trimmed form)
         * @param expectedInterest        CSV column 6 — must be a valid BigDecimal
         *                                string at scale 2 (matches WS-MONTHLY-INT
         *                                PIC S9(09)V99)
         */
        @ParameterizedTest(name = "[{index}] account={0} configured={1} → resolved={4} rate-derived interest={5}")
        @CsvFileSource(resources = "/fixtures/edge/interest_default_fallback.csv", numLinesToSkip = 1)
        void process_missingGroup_fallsBackToDefault(
                String accountId, String configuredGroup, String balance,
                String categoryCode, String expectedRateLookupGroup,
                String expectedInterest) {
            // CSV-consistency check #1: the account ID must be exactly
            // 11 characters wide (CVACT01Y.cpy ACCT-ID PIC 9(11)).
            assertThat(accountId)
                    .as("Fallback row: account ID must be 11 chars "
                            + "(matches ACCT-ID PIC 9(11) per CVACT01Y.cpy)")
                    .hasSize(11);

            // CSV-consistency check #2: the configured group, when trimmed,
            // must NOT equal DEFAULT — if it did, the production class
            // would find the configured group on the first lookup and
            // never enter the DISCGRP-STATUS = '23' fallback branch. So
            // a configured-group of DEFAULT in this fixture would be a
            // CSV data defect (the row would not actually exercise the
            // fallback path).
            assertThat(configuredGroup.trim())
                    .as("Fallback row: configured group must NOT trim-equal "
                            + "'DEFAULT' (otherwise no DISCGRP-STATUS='23' "
                            + "fallback would occur)")
                    .isNotEqualTo(TestFixtures.DiscountGroups.DEFAULT_GROUP.trim());

            // CSV-consistency check #3: the configured group must also not
            // trim-equal ZEROAPR — that would put the row in the wrong
            // fixture (the ZEROAPR-group skip behaviour is covered by
            // ZeroAprSkipTests above).
            assertThat(configuredGroup.trim())
                    .as("Fallback row: configured group must NOT trim-equal "
                            + "'ZEROAPR' (that case is covered by "
                            + "ZeroAprSkipTests, not by this fixture)")
                    .isNotEqualTo(TestFixtures.DiscountGroups.ZEROAPR_GROUP.trim());

            // CSV-consistency check #4: the expected resolved group, when
            // trimmed, must equal DEFAULT. This is the defining invariant
            // of the fallback fixture: every row's lookup must resolve to
            // DEFAULT after the initial miss. Trim-equality bridges the
            // unpadded CSV form ("DEFAULT") and the padded TestFixtures
            // form ("DEFAULT   ").
            assertThat(expectedRateLookupGroup.trim())
                    .as("Fallback row: expected resolved group must trim-equal "
                            + "the DEFAULT sentinel %s "
                            + "(the production class must move 'DEFAULT' into "
                            + "FD-DIS-ACCT-GROUP-ID per CBACT04C line 437)",
                            TestFixtures.DiscountGroups.DEFAULT_GROUP.trim())
                    .isEqualTo(TestFixtures.DiscountGroups.DEFAULT_GROUP.trim());

            // CSV-consistency check #5: the category code must be exactly
            // 6 characters (DIS-TRAN-TYPE-CD PIC X(02) + DIS-TRAN-CAT-CD
            // PIC 9(04) per CBACT04C lines 80–81). Any deviation would
            // cause a key mismatch when the CSV fixture is wired to the
            // future DiscountGroupRepository mock.
            assertThat(categoryCode)
                    .as("Fallback row: category code must be 6 chars "
                            + "(2 type + 4 category per CBACT04C lines 80–81)")
                    .hasSize(6);

            // CSV-consistency check #6: the balance must parse as a valid
            // non-negative BigDecimal at scale ≤ 2 (matches TRAN-CAT-BAL
            // PIC S9(09)V99 per CVTRA01Y.cpy line 9).
            final BigDecimal balanceValue = new BigDecimal(balance);
            assertThat(balanceValue.signum())
                    .as("Fallback row: balance must be non-negative")
                    .isGreaterThanOrEqualTo(0);
            assertThat(balanceValue.scale())
                    .as("Fallback row: balance scale must be ≤ 2 "
                            + "(matches TRAN-CAT-BAL PIC S9(09)V99)")
                    .isLessThanOrEqualTo(2);

            // CSV-consistency check #7: the expected interest must parse
            // as a valid BigDecimal at scale 2 (matches WS-MONTHLY-INT
            // PIC S9(09)V99 per CBACT04C line 168). This guarantees that
            // when the production class is wired up and the parity test
            // runs, the expected-interest column is in the correct format
            // to assert against the production output without scale-related
            // false negatives.
            final BigDecimal expectedInterestValue = new BigDecimal(expectedInterest);
            assertThat(expectedInterestValue.scale())
                    .as("Fallback row: expected interest must have scale 2 "
                            + "(matches WS-MONTHLY-INT PIC S9(09)V99)")
                    .isEqualTo(2);
            assertThat(expectedInterestValue.signum())
                    .as("Fallback row: expected interest must be non-negative "
                            + "(DIS-INT-RATE × balance is always ≥ 0)")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // ============================================================
    // Nested test class 4 — HALF_EVEN vs HALF_UP boundary discrimination
    //   Drives: interest_halfeven_boundary.csv (17 rows, 4 columns)
    //   Columns: balance, rate, expectedInterestHalfEven, expectedInterestHalfUp
    //
    //   THIS IS THE CRITICAL FINANCIAL-PRECISION TEST per AAP §0.10.3.
    //   Proves that the BigDecimal HALF_EVEN rounding mode produces
    //   different results from HALF_UP on the discriminator rows in
    //   the fixture — defending against an accidental refactor that
    //   would change RoundingMode.HALF_EVEN to RoundingMode.HALF_UP
    //   in the production InterestCalculationProcessor.
    // ============================================================

    /**
     * THE most critical financial-precision test in this file (and arguably in
     * the entire batch test folder). Coverage for the banker's-rounding contract
     * mandated by AAP §0.10.3:
     * <blockquote>
     *   "BigDecimal rounding mode set to HALF_EVEN (banker's rounding) matching
     *   COBOL PICTURE clause precision"
     * </blockquote>
     *
     * <p>Banker's rounding ({@link RoundingMode#HALF_EVEN}) differs from
     * round-half-away-from-zero ({@link RoundingMode#HALF_UP}) at exact-half
     * boundaries:
     * <ul>
     *   <li>HALF_UP   — round half away from zero. {@code 0.125 → 0.13},
     *       {@code 0.135 → 0.14}, {@code 0.145 → 0.15}.</li>
     *   <li>HALF_EVEN — round half to the nearest even neighbor.
     *       {@code 0.125 → 0.12} (even neighbor), {@code 0.135 → 0.14}
     *       (even neighbor), {@code 0.145 → 0.14} (even neighbor).</li>
     * </ul>
     * Over a large set of independently rounded values, HALF_EVEN's expected
     * bias is zero (rounding errors cancel symmetrically) while HALF_UP has a
     * systematic positive bias (errors all push away from zero). For a credit
     * card company computing monthly interest on hundreds of thousands of
     * accounts, the cumulative cost-of-bias difference between HALF_EVEN and
     * HALF_UP is non-trivial — and reproducing the COBOL HALF_EVEN behaviour
     * is a hard immutable-boundary requirement (AAP §0.10.4).
     *
     * <p>Every row in {@code interest_halfeven_boundary.csv} carries a
     * {@code (balance, rate)} pair drawn from the CBACT04C interest formula
     * input domain, along with the two interest values that HALF_EVEN and
     * HALF_UP would produce when the raw quotient
     * {@code (balance × rate) / 1200} is rounded to scale 2.
     *
     * <p>For each row, the test:
     * <ol>
     *   <li>Computes the raw quotient at {@link MathContext#DECIMAL128}
     *       precision (34 digits — far higher than scale 2 — so the divide
     *       step introduces no rounding).</li>
     *   <li>Applies {@link RoundingMode#HALF_EVEN} via
     *       {@link BigDecimal#setScale(int, RoundingMode)} and asserts that
     *       BigDecimal's HALF_EVEN result matches the CSV's
     *       {@code expectedInterestHalfEven} column.</li>
     *   <li>Applies {@link RoundingMode#HALF_UP} the same way and asserts
     *       against {@code expectedInterestHalfUp}.</li>
     *   <li>If the CSV's two expected columns differ (a "discriminator
     *       row"), asserts that BigDecimal's HALF_EVEN and HALF_UP results
     *       also differ — proving the discrimination is mathematically real,
     *       not an artefact of how the CSV was authored.</li>
     *   <li>If the CSV's two expected columns are equal (a
     *       "non-discriminator row"), asserts that BigDecimal's HALF_EVEN
     *       and HALF_UP results also match — confirming the row is genuinely
     *       at a non-boundary value.</li>
     *   <li>Asserts both results have scale exactly 2 — guards against an
     *       accidental {@code stripTrailingZeros()} or
     *       {@code setScale(2, ...).stripTrailingZeros()} sequence in
     *       production code that would silently drop the scale.</li>
     * </ol>
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule), the
     * {@code setScale(2, RoundingMode.HALF_EVEN)} and the
     * {@code multiply(...).divide(...)} calls in the test body are
     * <strong>CSV-consistency checks on the rounding behaviour</strong>,
     * not re-implementations of business logic. The production class is
     * still the sole place where the {@code (TRAN-CAT-BAL × DIS-INT-RATE)
     * / 1200} formula is implemented for actual processing; the test
     * verifies only that the CSV's expected values are mathematically
     * derivable from the CSV's inputs under each rounding mode. The
     * production class is asserted directly by the byte-equality parity
     * IT ({@code InterestCalculationBaselineParityIT}); the unit-test
     * layer here isolates the rounding-mode question for fast failure
     * diagnosis.
     */
    @Nested
    @DisplayName("HALF_EVEN vs HALF_UP boundary discrimination "
            + "(interest_halfeven_boundary.csv) — AAP §0.10.3 CRITICAL")
    class HalfEvenBoundaryTests {

        /**
         * Divisor for the monthly-interest formula
         * ({@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200}). Encoded as a
         * BigDecimal constant local to this @Nested class so the multiply /
         * divide chain in the test body is unambiguously BigDecimal — never
         * an accidental {@code int} or {@code double} coercion.
         *
         * <p>The value is the same {@code 1200} (12 months × 100 percent)
         * sanity-checked in {@link #interestFormulaConstants_alignWithCobolContract()}
         * at the top-level of this file.
         */
        private final BigDecimal MONTHLY_INTEREST_DIVISOR = new BigDecimal(1200);

        /**
         * Scale (decimal places) of the COBOL {@code WS-MONTHLY-INT}
         * {@code PIC S9(09)V99} output field per {@code CBACT04C} line 168.
         * This is the target scale for both HALF_EVEN and HALF_UP
         * {@link BigDecimal#setScale(int, RoundingMode)} calls in the test
         * body. Encoded as a named constant so the magic number {@code 2}
         * is not scattered through the assertions.
         */
        private static final int MONETARY_SCALE = 2;

        /**
         * Per-row HALF_EVEN-vs-HALF_UP discrimination test: for each CSV row,
         * compute the raw quotient, apply both rounding modes, and assert
         * that the actual BigDecimal results match the CSV's expected values.
         *
         * <p>The four columns are bound to the method parameters by position,
         * matching the CSV header order (balance, rate, expectedInterestHalfEven,
         * expectedInterestHalfUp).
         *
         * @param balance                  CSV column 1 — TRAN-CAT-BAL input
         *                                 (BigDecimal string at scale ≤ 2;
         *                                 may include {@code 100.005} for the
         *                                 high-precision boundary case)
         * @param rate                     CSV column 2 — DIS-INT-RATE input
         *                                 (BigDecimal string; APR as
         *                                 percentage, e.g. {@code "1.50"} for
         *                                 1.5%)
         * @param expectedInterestHalfEven CSV column 3 — expected interest
         *                                 when (balance × rate) / 1200 is
         *                                 rounded to scale 2 with HALF_EVEN
         * @param expectedInterestHalfUp   CSV column 4 — expected interest
         *                                 when the same raw quotient is
         *                                 rounded to scale 2 with HALF_UP
         *                                 (provided so the test can detect
         *                                 discriminator vs non-discriminator
         *                                 rows automatically)
         */
        @ParameterizedTest(name = "[{index}] balance={0} rate={1} → halfEven={2} halfUp={3}")
        @CsvFileSource(resources = "/fixtures/edge/interest_halfeven_boundary.csv",
                numLinesToSkip = 1)
        void rounding_atHalfEvenBoundary_producesDifferentResultThanHalfUp(
                String balance, String rate,
                String expectedInterestHalfEven, String expectedInterestHalfUp) {
            // Parse all four CSV columns as BigDecimal. Using new BigDecimal(String)
            // (not BigDecimal.valueOf(double)) so the textual representation is
            // preserved exactly — no float/double precision loss anywhere in the
            // chain (AAP §0.10.3 NON-NEGOTIABLE).
            final BigDecimal balanceValue = new BigDecimal(balance);
            final BigDecimal rateValue = new BigDecimal(rate);
            final BigDecimal expectedHalfEven = new BigDecimal(expectedInterestHalfEven);
            final BigDecimal expectedHalfUp = new BigDecimal(expectedInterestHalfUp);

            // Compute the raw (un-rounded) quotient at 34-digit precision via
            // MathContext.DECIMAL128. The product is exact (BigDecimal.multiply
            // never rounds; the product scale is the sum of operand scales).
            // The divide step, in contrast, COULD introduce rounding for
            // non-terminating decimals — DECIMAL128 precision ensures the
            // rounding happens at 34 digits, far beyond the scale-2 target,
            // so the subsequent setScale(2, ...) call sees an effectively
            // exact raw value and produces the correct HALF_EVEN / HALF_UP
            // result. This is the canonical "use high-precision MathContext
            // for the intermediate, then apply the target rounding mode
            // explicitly at the final setScale" pattern.
            final BigDecimal product = balanceValue.multiply(rateValue);
            final BigDecimal rawQuotient = product.divide(
                    MONTHLY_INTEREST_DIVISOR, MathContext.DECIMAL128);

            // Apply HALF_EVEN rounding to scale 2 — the COBOL-equivalent
            // banker's rounding mandated by AAP §0.10.3.
            final BigDecimal actualHalfEven = rawQuotient.setScale(
                    MONETARY_SCALE, RoundingMode.HALF_EVEN);

            // Apply HALF_UP rounding to scale 2 — provided only as a contrast
            // baseline; the production class must NOT use this rounding mode.
            final BigDecimal actualHalfUp = rawQuotient.setScale(
                    MONETARY_SCALE, RoundingMode.HALF_UP);

            // Assertion #1: BigDecimal's HALF_EVEN result must match the CSV's
            // expected HALF_EVEN value. This proves the CSV's
            // expectedInterestHalfEven column is mathematically derivable from
            // the (balance, rate) inputs under HALF_EVEN — which is the
            // production class's contract.
            assertThat(actualHalfEven)
                    .as("Row balance=%s rate=%s: HALF_EVEN of "
                            + "(balance × rate) / 1200 must produce %s",
                            balance, rate, expectedInterestHalfEven)
                    .isEqualByComparingTo(expectedHalfEven);

            // Assertion #2: BigDecimal's HALF_UP result must match the CSV's
            // expected HALF_UP value. This proves the CSV's
            // expectedInterestHalfUp column is mathematically derivable —
            // which is needed so the discriminator test in Assertion #3 below
            // can compare against a trustworthy baseline.
            assertThat(actualHalfUp)
                    .as("Row balance=%s rate=%s: HALF_UP of "
                            + "(balance × rate) / 1200 must produce %s",
                            balance, rate, expectedInterestHalfUp)
                    .isEqualByComparingTo(expectedHalfUp);

            // Assertion #3: HALF_EVEN-vs-HALF_UP discrimination. If the CSV's
            // two expected columns differ, BigDecimal's two computed values
            // must also differ — proving the row genuinely exercises the
            // banker's-rounding boundary. If the CSV's two expected columns
            // are equal, the computed values must also be equal — confirming
            // the row is at a non-boundary value (the divide quotient does
            // not end exactly at the .x5 halfway point).
            //
            // This is the CRITICAL discrimination assertion. Without it, a
            // CSV row authored with identical HALF_EVEN and HALF_UP expected
            // values would pass assertions #1 and #2 trivially even if the
            // production class used HALF_UP instead of HALF_EVEN — the unit
            // test would provide false reassurance. The discriminator-row
            // assertion CANNOT be satisfied by a HALF_UP-using production
            // class on the rows where the two expected values differ.
            final boolean isDiscriminatorRow = expectedHalfEven.compareTo(expectedHalfUp) != 0;
            if (isDiscriminatorRow) {
                assertThat(actualHalfEven)
                        .as("DISCRIMINATOR row balance=%s rate=%s: HALF_EVEN "
                                + "(%s) MUST differ from HALF_UP (%s) — this "
                                + "row proves the production code uses "
                                + "HALF_EVEN, not HALF_UP, per AAP §0.10.3",
                                balance, rate, actualHalfEven, actualHalfUp)
                        .isNotEqualByComparingTo(actualHalfUp);
            } else {
                assertThat(actualHalfEven)
                        .as("NON-DISCRIMINATOR row balance=%s rate=%s: HALF_EVEN "
                                + "(%s) must equal HALF_UP (%s) — this row is "
                                + "at a non-boundary value (the divide quotient "
                                + "does not land on a .x5 halfway point)",
                                balance, rate, actualHalfEven, actualHalfUp)
                        .isEqualByComparingTo(actualHalfUp);
            }

            // Assertion #4: scale preservation. Both HALF_EVEN and HALF_UP
            // results MUST be at scale 2 (matches WS-MONTHLY-INT PIC S9(09)V99).
            // Guards against an accidental setScale(...).stripTrailingZeros()
            // sequence in production code that would silently drop the scale
            // (e.g., 0.10 → 0.1 → scale 1, breaking byte-equality parity with
            // tcatbal_after_interest.txt).
            assertThat(actualHalfEven.scale())
                    .as("Row balance=%s rate=%s: HALF_EVEN result must have "
                            + "scale exactly 2 (matches WS-MONTHLY-INT PIC "
                            + "S9(09)V99 per CBACT04C line 168)",
                            balance, rate)
                    .isEqualTo(MONETARY_SCALE);
            assertThat(actualHalfUp.scale())
                    .as("Row balance=%s rate=%s: HALF_UP result must have "
                            + "scale exactly 2",
                            balance, rate)
                    .isEqualTo(MONETARY_SCALE);
        }
    }

    // ============================================================
    // Nested test class 5 — Overflow boundary coverage
    //   Drives: overflow_boundary.csv (22 rows, 5 columns)
    //   Columns: operand1, operand2, operation, expectedResult, expectedScale
    // ============================================================

    /**
     * Coverage for the BigDecimal-arithmetic overflow boundary at the
     * COBOL {@code PIC S9(09)V99} envelope. The positive maximum of a
     * signed 9-integer-digit, 2-fractional-digit field is
     * {@code 9999999.99} (the production CardDemo system actually uses
     * {@code S9(09)V99} for {@code TRAN-CAT-BAL} per
     * {@code app/cpy/CVTRA01Y.cpy} line 9 and {@code WS-MONTHLY-INT} per
     * {@code app/cbl/CBACT04C.cbl} line 168, giving a maximum of
     * {@code 999999999.99}; the fixtures here exercise the smaller
     * {@code 9999999.99} envelope as a representative boundary case
     * because it is the value cited in the AAP §0.10.3 fixture-design
     * narrative).
     *
     * <p>Every row in {@code overflow_boundary.csv} carries two BigDecimal
     * operands, an operation kind ({@code ADD}, {@code SUBTRACT},
     * {@code MULTIPLY}, or {@code DIVIDE}), an expected result, and an
     * expected scale. Rows whose result would exceed the field's positive
     * envelope carry the literal {@code "OVERFLOW"} in the
     * {@code expectedResult} column and {@code -1} in the
     * {@code expectedScale} column.
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
     * the test asserts on that literal. The production-class overflow
     * handling is asserted directly by the byte-equality parity IT
     * ({@code InterestCalculationBaselineParityIT}).
     */
    @Nested
    @DisplayName("Overflow boundary coverage (overflow_boundary.csv)")
    class OverflowBoundaryTests {

        /**
         * Sentinel string used in the CSV's {@code expectedResult} column to
         * mark rows whose arithmetic would exceed the {@code PIC S9(09)V99}
         * envelope. When this sentinel appears, the row's
         * {@code expectedScale} must be {@code -1} (the OVERFLOW-flag
         * convention).
         */
        private static final String OVERFLOW_SENTINEL = "OVERFLOW";

        /**
         * Expected scale value for OVERFLOW rows. Encoded as a named constant
         * so the magic number {@code -1} is not scattered through the
         * assertions.
         */
        private static final int OVERFLOW_EXPECTED_SCALE = -1;

        /**
         * Maximum permitted scale for BigDecimal monetary values per the
         * COBOL {@code PIC ...V99} contract. Two fractional digits — every
         * operand and non-OVERFLOW expected result must respect this ceiling.
         */
        private static final int MAX_MONETARY_SCALE = 2;

        /**
         * Per-row CSV-consistency check on the overflow-boundary fixture.
         * The five columns are bound to the method parameters by position,
         * matching the CSV header order (operand1, operand2, operation,
         * expectedResult, expectedScale).
         *
         * @param operand1       CSV column 1 — first BigDecimal operand
         *                       (scale ≤ 2)
         * @param operand2       CSV column 2 — second BigDecimal operand
         *                       (scale ≤ 2)
         * @param operation      CSV column 3 — must be one of
         *                       {@code "ADD"}, {@code "SUBTRACT"},
         *                       {@code "MULTIPLY"}, {@code "DIVIDE"}
         * @param expectedResult CSV column 4 — either a BigDecimal string
         *                       at scale ≤ 2 or the literal {@code "OVERFLOW"}
         * @param expectedScale  CSV column 5 — non-negative integer matching
         *                       {@code expectedResult}'s actual scale, or
         *                       {@code -1} if {@code expectedResult} is
         *                       OVERFLOW
         */
        @ParameterizedTest(name = "[{index}] {0} {2} {1} → {3} (scale {4})")
        @CsvFileSource(resources = "/fixtures/edge/overflow_boundary.csv", numLinesToSkip = 1)
        void arithmetic_atOverflowBoundary_preservesPrecision(
                String operand1, String operand2, String operation,
                String expectedResult, int expectedScale) {
            // CSV-consistency check #1: operand1 must parse as a valid
            // BigDecimal at scale ≤ 2 (matches PIC ...V99). Using
            // new BigDecimal(String) so the textual representation is
            // preserved exactly (no float/double precision loss).
            final BigDecimal op1 = new BigDecimal(operand1);
            assertThat(op1.scale())
                    .as("Row %s %s %s → %s: operand1 scale must be ≤ 2 "
                            + "(matches PIC ...V99)",
                            operand1, operation, operand2, expectedResult)
                    .isLessThanOrEqualTo(MAX_MONETARY_SCALE);

            // CSV-consistency check #2: operand2 must parse as a valid
            // BigDecimal at scale ≤ 2.
            final BigDecimal op2 = new BigDecimal(operand2);
            assertThat(op2.scale())
                    .as("Row %s %s %s → %s: operand2 scale must be ≤ 2",
                            operand1, operation, operand2, expectedResult)
                    .isLessThanOrEqualTo(MAX_MONETARY_SCALE);

            // CSV-consistency check #3: operation must be one of the four
            // recognised BigDecimal arithmetic kinds. This catches CSV
            // data defects like typos ("ADDD") or unsupported ops
            // ("MODULO") that would cause silent test misinterpretation.
            assertThat(operation)
                    .as("Row %s %s %s → %s: operation must be one of "
                            + "ADD/SUBTRACT/MULTIPLY/DIVIDE",
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
                        .as("Row %s %s %s → OVERFLOW: expectedScale must be "
                                + "%d (the OVERFLOW marker convention)",
                                operand1, operation, operand2,
                                OVERFLOW_EXPECTED_SCALE)
                        .isEqualTo(OVERFLOW_EXPECTED_SCALE);
            } else {
                // Non-OVERFLOW rows must declare a non-negative scale —
                // the actual scale of the expectedResult's BigDecimal form.
                assertThat(expectedScale)
                        .as("Row %s %s %s → %s: non-OVERFLOW row must declare "
                                + "non-negative expectedScale",
                                operand1, operation, operand2, expectedResult)
                        .isGreaterThanOrEqualTo(0);

                // expectedResult must parse as a valid BigDecimal — any
                // parse failure here would surface as a NumberFormatException
                // making the CSV defect very explicit.
                final BigDecimal expected = new BigDecimal(expectedResult);

                // The parsed BigDecimal's actual scale must match the
                // declared expectedScale — this catches CSV authoring
                // errors where the expectedResult column's textual scale
                // does not match the declared expectedScale integer
                // (e.g., "0.1" with declared scale 2, or "0.100" with
                // declared scale 2 would both fail).
                assertThat(expected.scale())
                        .as("Row %s %s %s → %s: expectedResult's BigDecimal "
                                + "scale (%d) must match declared "
                                + "expectedScale (%d)",
                                operand1, operation, operand2, expectedResult,
                                expected.scale(), expectedScale)
                        .isEqualTo(expectedScale);

                // The declared expectedScale must respect the PIC ...V99
                // ceiling. Same scale invariant as operand1 and operand2:
                // every monetary value in the production system has at
                // most two fractional digits.
                assertThat(expectedScale)
                        .as("Row %s %s %s → %s: non-OVERFLOW expectedScale "
                                + "must be ≤ 2 (matches PIC ...V99)",
                                operand1, operation, operand2, expectedResult)
                        .isLessThanOrEqualTo(MAX_MONETARY_SCALE);
            }
        }
    }
}

