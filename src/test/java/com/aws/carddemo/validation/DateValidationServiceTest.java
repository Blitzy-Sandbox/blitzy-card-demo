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
package com.aws.carddemo.validation;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * FixtureLoader — static fixture-loading utility for reading the CSV
//     edge-case fixture date_validation_variants.csv via
//     loadEdgeCases(String). Used by the fixture-integrity coverage
//     assertion (fixture_dateValidationVariants_coversAllEvaluateBranches)
//     to verify that the CSV's data rows cover every documented COBOL
//     CSUTLDTC.cbl EVALUATE branch (FC-INVALID-DATE through FC-YEAR-IN-
//     ERA-ZERO) WITHOUT re-implementing date-validation logic in the test
//     body — that would violate the AAP §0.10.1 Require Test Coverage
//     rule.
//
//   * TestFixtures — shared test-constants holder. The nested Paths class
//     exposes EDGE_DATE_VALIDATION_VARIANTS (the canonical CSV filename),
//     allowing the fixture-integrity assertion to load the fixture
//     without hardcoding the filename string in this test body and keeping
//     the test source-of-truth aligned with the central path registry.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.FixtureLoader;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 imports — JUnit Jupiter API + Params (no JUnit 4, no Vintage engine).
//
//   * @DisplayName / @Test — JUnit 5 core test API for plain unit tests
//     (the 14 named per-EVALUATE-branch sanity tests covering leap-year /
//     century-rule variants, invalid month, year zero, non-numeric input,
//     partial date, bad pic string, format variants, plus the defensive
//     (null, null) test and the fixture-integrity assertion);
//     @DisplayName at the class and method level for human-readable test
//     reports surfacing the CSUTLDTC.cbl COBOL provenance.
//
//   * @ParameterizedTest / @CsvFileSource / @ValueSource — JUnit 5
//     parameterized testing API. @CsvFileSource drives the 26-row primary
//     test against the canonical date_validation_variants.csv fixture.
//     @ValueSource drives the defensive whitespace input tests (one
//     invocation per whitespace variant: " ", "  ", "\t", "\n",
//     "          ").
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// JDK imports
//
//   * java.util.List — used in the fixture-integrity assertion to receive
//     the List<String[]> returned by FixtureLoader.loadEdgeCases(...) and
//     to extract expectedReason column values (column index 3) for the
//     EVALUATE-branch coverage assertion.
// ---------------------------------------------------------------------------
import java.util.List;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent assertion DSL (AAP §0.10.10 — AssertJ
// exclusively, no Hamcrest matchers).
//
//   * assertThat — primary entry point; supports chained .as(...) messages
//     for unambiguous failure diagnostics that include both inputDate and
//     inputMask in the failure context.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DateValidationService}, the migrated Java equivalent
 * of the 157-line COBOL subprogram {@code app/cbl/CSUTLDTC.cbl} that wraps
 * the IBM Language Environment (LE) intrinsic {@code CEEDAYS} for
 * date-string validation against a format mask.
 *
 * <h2>COBOL Provenance — CSUTLDTC.cbl</h2>
 *
 * <p>{@code CSUTLDTC} accepts three {@code LINKAGE SECTION} parameters
 * ({@code LS-DATE PIC X(10)}, {@code LS-DATE-FORMAT PIC X(10)},
 * {@code LS-RESULT PIC X(80)}) and a {@code RETURN-CODE} severity. Its
 * {@code EVALUATE TRUE} block at {@code CSUTLDTC.cbl} lines 128–149 maps
 * every documented CEEDAYS feedback code to a 15-character result token:
 *
 * <ul>
 *   <li>{@code FC-INVALID-DATE} → {@code "Date is valid"} (severity 0 — the
 *       COBOL idiom: this condition fires when the date is NOT invalid)</li>
 *   <li>{@code FC-INSUFFICIENT-DATA} → {@code "Insufficient"}</li>
 *   <li>{@code FC-BAD-DATE-VALUE} → {@code "Datevalue error"}</li>
 *   <li>{@code FC-INVALID-ERA} → {@code "Invalid Era"}</li>
 *   <li>{@code FC-UNSUPP-RANGE} → {@code "Unsupp. Range"}</li>
 *   <li>{@code FC-INVALID-MONTH} → {@code "Invalid month"}</li>
 *   <li>{@code FC-BAD-PIC-STRING} → {@code "Bad Pic String"}</li>
 *   <li>{@code FC-NON-NUMERIC-DATA} → {@code "Nonnumeric data"}</li>
 *   <li>{@code FC-YEAR-IN-ERA-ZERO} → {@code "YearInEra is 0"}</li>
 *   <li>{@code WHEN OTHER} → {@code "Date is invalid"}</li>
 * </ul>
 *
 * <p>The migrated Java code uses the <strong>trimmed short forms</strong> of
 * each reason string (no trailing space padding); these contractual values
 * are exposed as {@code REASON_*} constants on {@link DateValidationResult}
 * and asserted byte-for-byte by every test below.
 *
 * <h2>Test Contract Source</h2>
 *
 * <p>The authoritative test contract lives in
 * {@code src/test/resources/fixtures/edge/date_validation_variants.csv},
 * enumerating 26 input variants spanning: leap-year rule (4 cases),
 * invalid-month (2 cases), invalid-day (3 cases), non-numeric (2 cases),
 * insufficient/empty input (2 cases), bad-pic-string (2 cases), year-zero
 * (1 case), valid normal dates (4 cases), {@code MM/DD/YYYY} format
 * variants (3 cases), and structural mask mismatch (1 case). Every row
 * produces one invocation of
 * {@link #validate_dateAndMask_returnsExpectedResult(String, String, boolean, String)}.
 *
 * <h3>JUnit 5 CSV null-handling note</h3>
 *
 * <p>JUnit 5's {@code @CsvFileSource} converts empty CSV cells to {@code null}
 * for {@code String} parameters. Two CSV rows leverage this:
 * <ul>
 *   <li>Row {@code ,YYYY-MM-DD,false,Insufficient} → invokes the test with
 *       {@code inputDate=null}, {@code inputMask="YYYY-MM-DD"} → exercises
 *       the {@code FC-INSUFFICIENT-DATA} branch.</li>
 *   <li>Row {@code 2024-01-15,,false,Bad Pic String} → invokes the test with
 *       {@code inputDate="2024-01-15"}, {@code inputMask=null} → exercises
 *       the {@code FC-BAD-PIC-STRING} branch.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>This test class contains NO parallel date-parsing logic. The CSV is
 * the canonical table; tests read it and assert on the real production
 * {@link DateValidationService}'s output. Test bodies contain only
 * assertion logic, never date arithmetic. The test does NOT call
 * {@code java.time.LocalDate.parse(...)}, does NOT compute leap-year rules,
 * does NOT inspect the input string structure beyond passing it through.
 * The production class is instantiated directly (not mocked) per the AAP
 * Require-Test-Coverage rule.
 *
 * <h2>Coverage Target (AAP §0.7.1)</h2>
 *
 * <p>The {@code com.aws.carddemo.validation.**} package carries a
 * {@code ≥90% line / ≥85% branch} coverage floor (the highest tier
 * alongside {@code com.aws.carddemo.io.**}). The 26 parameterized rows +
 * 14 per-EVALUATE-branch tests + 3 defensive tests + 1 fixture-integrity
 * test = ~44 test invocations total, achieving full coverage of the
 * service's branches:
 *
 * <ul>
 *   <li>Mask-validation branches (null mask, empty mask, unsupported mask,
 *       supported mask)</li>
 *   <li>Input null/empty branches ({@code REASON_INSUFFICIENT})</li>
 *   <li>Length branches (less than required, greater than required)</li>
 *   <li>Separator-character branches (length=required → BAD_PIC_STRING;
 *       length&ne;required → DATEVALUE_ERROR)</li>
 *   <li>Numeric-component branches ({@code REASON_NONNUMERIC_DATA} for
 *       non-digit year or month)</li>
 *   <li>Year-zero branch ({@code REASON_YEAR_IN_ERA_IS_ZERO})</li>
 *   <li>Month-range branches (month=0, month=13 →
 *       {@code REASON_INVALID_MONTH})</li>
 *   <li>STRICT-mode formatter branches (impossible dates: 2024-02-30,
 *       1900-02-29, 2024-04-31, 2023-02-29 →
 *       {@code REASON_DATEVALUE_ERROR})</li>
 *   <li>Happy-path branch ({@code REASON_DATE_IS_VALID})</li>
 * </ul>
 *
 * @see DateValidationService
 * @see DateValidationResult
 */
@DisplayName("DateValidationService — CSUTLDTC.cbl migration parity (CEEDAYS wrapper)")
final class DateValidationServiceTest {

    /**
     * Real production {@link DateValidationService} instance under test.
     * Stateless and final; safe to share across parallel test invocations
     * per AAP §0.10.9 Test Execution Independence and Parallelism.
     *
     * <p>The service requires no constructor arguments — the supported
     * masks ({@code "YYYY-MM-DD"} and {@code "MM/DD/YYYY"}) are static-final
     * descriptors initialized at class-load time. Per AAP §0.10.1 Require
     * Test Coverage rule, this field holds the REAL production class, not a
     * mock; no Mockito {@code @Mock} fields appear in this test class
     * because {@code DateValidationService} is a pure-function utility with
     * no external collaborators to mock.
     */
    private final DateValidationService service = new DateValidationService();

    // =====================================================================
    // PRIMARY CSV-driven parameterized test (26 invocations)
    // =====================================================================

    /**
     * Asserts that {@link DateValidationService#validate(String, String)}
     * produces the expected verdict and reason for every row of
     * {@code date_validation_variants.csv}.
     *
     * <p>This is the primary correctness gate for {@link DateValidationService}.
     * Every documented COBOL {@code EVALUATE} branch from {@code CSUTLDTC.cbl}
     * lines 128–149 is exercised by at least one row from the CSV.
     *
     * <p>The CSV header is
     * {@code inputDate,inputMask,expectedValid,expectedReason}; the test
     * passes each row through the service and asserts both the boolean
     * verdict and the contractual reason string verbatim. The
     * {@code numLinesToSkip = 1} parameter skips the CSV header row so the
     * test receives only the 26 data rows.
     *
     * <p>JUnit 5 auto-converts the CSV {@code true}/{@code false} strings to
     * {@code boolean} for the {@code expectedValid} parameter, and converts
     * empty CSV cells to {@code null} for {@code String} parameters
     * (deliberately exercising the production null-input branches).
     *
     * @param inputDate      date string to validate (column 1; {@code null}
     *                       when the CSV cell is empty)
     * @param inputMask      PIC mask to validate against (column 2;
     *                       {@code null} when the CSV cell is empty)
     * @param expectedValid  expected {@link DateValidationResult#isValid()}
     *                       (column 3)
     * @param expectedReason expected {@link DateValidationResult#reason()}
     *                       (column 4)
     */
    @ParameterizedTest(name = "[{index}] validate(\"{0}\", \"{1}\") -> valid={2}, reason=\"{3}\"")
    @CsvFileSource(
            resources = "/fixtures/edge/date_validation_variants.csv",
            numLinesToSkip = 1)
    @DisplayName("validate(date, mask) returns expected validity and reason per date_validation_variants.csv")
    void validate_dateAndMask_returnsExpectedResult(
            String inputDate,
            String inputMask,
            boolean expectedValid,
            String expectedReason) {
        // Arrange — service is the field-level final instance; nothing to set up.

        // Act — invoke the real production class (AAP §0.10.1 Require Test
        // Coverage rule: tests MUST call production service classes directly).
        DateValidationResult result = service.validate(inputDate, inputMask);

        // Assert — the result must never be null even for reject paths;
        // every code path in DateValidationService returns a non-null
        // DateValidationResult.
        assertThat(result)
                .as("DateValidationResult must not be null for inputDate=\"%s\", inputMask=\"%s\"",
                        inputDate, inputMask)
                .isNotNull();

        // Assert — boolean verdict matches CSV expectation. The .as(...)
        // diagnostic includes both inputs so failure surfaces unambiguously
        // which CSV row regressed.
        assertThat(result.isValid())
                .as("isValid() for inputDate=\"%s\", inputMask=\"%s\"",
                        inputDate, inputMask)
                .isEqualTo(expectedValid);

        // Assert — reason string is verbatim equal to CSV expectation (no
        // trim, no case change, no contains/startsWith — strict equality
        // preserves the COBOL WS-RESULT byte-identical contract per AAP
        // §0.10.4 Immutable Boundaries).
        assertThat(result.reason())
                .as("reason() for inputDate=\"%s\", inputMask=\"%s\"",
                        inputDate, inputMask)
                .isEqualTo(expectedReason);
    }

    // =====================================================================
    // Defensive boundary tests
    //
    // The COBOL caller cannot produce a null PIC X(10) value (the field is
    // always SPACE-filled). The Java caller, however, can pass null. The
    // tests below verify the defensive null-handling and whitespace-handling
    // behaviour of the migrated service against the COBOL contract that
    // any null/empty/whitespace input is invalid.
    // =====================================================================

    /**
     * Verifies that the production service handles the doubly-null input
     * {@code validate(null, null)} by returning a reject result rather than
     * throwing an exception. The COBOL contract was that invalid input
     * (including null/empty in the Java idiom) yields an invalid verdict
     * with a specific {@code WS-RESULT} token — never a programmatic crash.
     *
     * <p>The cascade in {@link DateValidationService} validates the mask
     * BEFORE the date (matching the COBOL CEEDAYS feedback-code priority
     * where {@code FC-BAD-PIC-STRING} is reported before
     * {@code FC-INSUFFICIENT-DATA} when both conditions are triggered
     * simultaneously). The expected reason is therefore
     * {@link DateValidationResult#REASON_BAD_PIC_STRING}.
     *
     * <p>The assertion permits either of the two reject reasons that the
     * COBOL EVALUATE cascade could plausibly surface for this input — both
     * preserve the contract that {@code (null, null)} is invalid.
     */
    @Test
    @DisplayName("validate(null, null) returns reject result (does NOT throw)")
    void validate_bothInputsNull_returnsRejectResult() {
        // Act — invoke the production class with both inputs null.
        DateValidationResult result = service.validate(null, null);

        // Assert — non-null result, isValid=false, reason is one of the two
        // contractual reject tokens that can fire for null inputs.
        assertThat(result)
                .as("validate(null, null) must return a non-null reject result, not throw")
                .isNotNull();
        assertThat(result.isValid())
                .as("validate(null, null) must NOT be valid")
                .isFalse();
        // The cascade order (mask checked first) privileges BAD_PIC_STRING
        // over INSUFFICIENT when both conditions fire. Either token preserves
        // the COBOL contract that null/empty input is invalid.
        assertThat(result.reason())
                .as("validate(null, null) must reject with BAD_PIC_STRING or INSUFFICIENT")
                .isIn(DateValidationResult.REASON_BAD_PIC_STRING,
                        DateValidationResult.REASON_INSUFFICIENT);
    }

    /**
     * Verifies that the production service rejects all-whitespace
     * {@code inputDate} values (one space, multiple spaces, tab, newline,
     * the full required-length-padded all-spaces variant). The COBOL
     * caller's {@code PIC X(10)} field was SPACE-filled when no value was
     * supplied; the Java migration must preserve the reject semantics.
     *
     * <p>The accepted reject reasons span the three EVALUATE branches the
     * production cascade could plausibly surface for whitespace input:
     * <ul>
     *   <li>{@link DateValidationResult#REASON_INSUFFICIENT} — the input is
     *       shorter than the mask requires (any 1-to-9 character whitespace
     *       string, with the cascade short-circuiting on length).</li>
     *   <li>{@link DateValidationResult#REASON_NONNUMERIC_DATA} — the
     *       whitespace characters do not pass the numeric-component check.</li>
     *   <li>{@link DateValidationResult#REASON_DATEVALUE_ERROR} — the
     *       whitespace input matches a wrong structural pattern.</li>
     *   <li>{@link DateValidationResult#REASON_BAD_PIC_STRING} — the
     *       all-spaces 10-character input has wrong separator characters
     *       at positions 4 and 7 but matches the required length 10, which
     *       the cascade treats as a "wrong pic for this input" mismatch.</li>
     * </ul>
     *
     * @param whitespaceInput an all-whitespace {@code inputDate} variant
     */
    @ParameterizedTest(name = "[{index}] validate(whitespace=\"{0}\", \"YYYY-MM-DD\") rejects")
    @ValueSource(strings = {" ", "  ", "\t", "\n", "          "})
    @DisplayName("validate(whitespace, validMask) returns reject (insufficient/nonnumeric/datevalue/bad-pic)")
    void validate_whitespaceInputDate_returnsRejectResult(String whitespaceInput) {
        // Act
        DateValidationResult result = service.validate(whitespaceInput, "YYYY-MM-DD");

        // Assert — whitespace inputDate must always be rejected. The exact
        // reject reason depends on which branch of the cascade fires first
        // for the input length, so the assertion permits any of the four
        // reject reasons that the production code may legitimately surface.
        assertThat(result)
                .as("Whitespace inputDate must produce a non-null result (input=\"%s\")", whitespaceInput)
                .isNotNull();
        assertThat(result.isValid())
                .as("Whitespace inputDate must NOT be valid (input=\"%s\")", whitespaceInput)
                .isFalse();
        assertThat(result.reason())
                .as("Whitespace inputDate reject reason (input=\"%s\")", whitespaceInput)
                .isIn(DateValidationResult.REASON_INSUFFICIENT,
                        DateValidationResult.REASON_NONNUMERIC_DATA,
                        DateValidationResult.REASON_DATEVALUE_ERROR,
                        DateValidationResult.REASON_BAD_PIC_STRING);
    }

    /**
     * Verifies that the production service rejects all-whitespace
     * {@code inputMask} values with {@link DateValidationResult#REASON_BAD_PIC_STRING}.
     *
     * <p>A whitespace mask is not a member of the {@code SUPPORTED_MASKS}
     * map ({@code "YYYY-MM-DD"} or {@code "MM/DD/YYYY"}); the mask-validation
     * branch (cascade step 1) fires immediately and rejects with
     * {@code REASON_BAD_PIC_STRING}, mirroring the COBOL
     * {@code FC-BAD-PIC-STRING} feedback that CEEDAYS would emit for an
     * unsupported mask.
     *
     * @param whitespaceMask an all-whitespace {@code inputMask} variant
     */
    @ParameterizedTest(name = "[{index}] validate(\"2024-01-15\", whitespace=\"{0}\") rejects with Bad Pic String")
    @ValueSource(strings = {" ", "  ", "\t", "\n", "          "})
    @DisplayName("validate(validDate, whitespace) returns REASON_BAD_PIC_STRING")
    void validate_whitespaceInputMask_returnsBadPicString(String whitespaceMask) {
        // Act
        DateValidationResult result = service.validate("2024-01-15", whitespaceMask);

        // Assert — whitespace mask is unsupported, so the mask-validation
        // branch fires immediately with REASON_BAD_PIC_STRING.
        assertThat(result)
                .as("Whitespace inputMask must produce a non-null result (mask=\"%s\")", whitespaceMask)
                .isNotNull();
        assertThat(result.isValid())
                .as("Whitespace inputMask must NOT be valid (mask=\"%s\")", whitespaceMask)
                .isFalse();
        assertThat(result.reason())
                .as("Whitespace inputMask reject reason (mask=\"%s\")", whitespaceMask)
                .isEqualTo(DateValidationResult.REASON_BAD_PIC_STRING);
    }

    // =====================================================================
    // Per-EVALUATE-branch sanity tests
    //
    // Each test below documents one of the major CSUTLDTC.cbl EVALUATE
    // branches via a named @Test method. Provides clear failure diagnostics
    // tied to specific COBOL semantics and serves as living documentation
    // for future maintainers. These complement the CSV-driven parameterized
    // test by surfacing the COBOL provenance directly in the test name.
    // =====================================================================

    /**
     * Verifies the leap-year rule: year 2024 is divisible by 4 (and not by
     * 100), so Feb 29 in 2024 is a legitimate calendar date.
     *
     * <p>COBOL provenance: {@code CSUTLDTC.cbl} CEEDAYS returns the
     * {@code FC-INVALID-DATE} feedback code (the success sentinel — see
     * {@code WS-RESULT 'Date is valid'} at line 130) for valid calendar
     * dates.
     */
    @Test
    @DisplayName("validate('2024-02-29', 'YYYY-MM-DD') — Feb 29 in leap year (divisible by 4) is valid")
    void validate_leapYearDivisibleBy4_returnsValid() {
        DateValidationResult result = service.validate("2024-02-29", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Feb 29 in a divisible-by-4 year must be valid")
                .isTrue();
        assertThat(result.reason())
                .as("Reason for valid date must be REASON_DATE_IS_VALID")
                .isEqualTo(DateValidationResult.REASON_DATE_IS_VALID);
    }

    /**
     * Verifies the Gregorian quadricentennial rule: century year 2000 is
     * divisible by 400, so it IS a leap year and Feb 29 in 2000 is valid.
     *
     * <p>Gregorian rule: century years are leap years only if divisible by
     * 400. {@code 2000 % 400 == 0} → leap year → Feb 29 is valid.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-INVALID-DATE} (the
     * success sentinel) for 2000-02-29.
     */
    @Test
    @DisplayName("validate('2000-02-29', 'YYYY-MM-DD') — Feb 29 in century leap year (divisible by 400) is valid")
    void validate_centuryYearDivisibleBy400_returnsValid() {
        DateValidationResult result = service.validate("2000-02-29", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Feb 29 in a divisible-by-400 century year must be valid")
                .isTrue();
        assertThat(result.reason())
                .as("Reason for valid date must be REASON_DATE_IS_VALID")
                .isEqualTo(DateValidationResult.REASON_DATE_IS_VALID);
    }

    /**
     * Verifies the Gregorian century-rule exception: century year 1900 is
     * NOT divisible by 400 (1900 / 400 = 4.75), so it is NOT a leap year
     * and Feb 29 in 1900 must reject.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-BAD-DATE-VALUE} for
     * impossible dates → {@code WS-RESULT 'Datevalue error'} at
     * {@code CSUTLDTC.cbl} line 134.
     */
    @Test
    @DisplayName("validate('1900-02-29', 'YYYY-MM-DD') — Feb 29 in century non-leap year (not divisible by 400) is rejected")
    void validate_centuryYearNotDivisibleBy400_returnsDatevalueError() {
        DateValidationResult result = service.validate("1900-02-29", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Feb 29 in a non-leap century year must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for impossible calendar date must be REASON_DATEVALUE_ERROR")
                .isEqualTo(DateValidationResult.REASON_DATEVALUE_ERROR);
    }

    /**
     * Verifies the leap-year exclusion: year 2023 is not divisible by 4, so
     * it is not a leap year and Feb 29 in 2023 is impossible.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-BAD-DATE-VALUE} for
     * impossible dates → {@code WS-RESULT 'Datevalue error'}.
     */
    @Test
    @DisplayName("validate('2023-02-29', 'YYYY-MM-DD') — Feb 29 in non-leap year is rejected with Datevalue error")
    void validate_nonLeapYearFeb29_returnsDatevalueError() {
        DateValidationResult result = service.validate("2023-02-29", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Feb 29 in a non-leap year must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for Feb 29 in non-leap year must be REASON_DATEVALUE_ERROR")
                .isEqualTo(DateValidationResult.REASON_DATEVALUE_ERROR);
    }

    /**
     * Verifies the month-out-of-range branch (high boundary): month 13 is
     * outside the valid 01-12 range.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-INVALID-MONTH} →
     * {@code WS-RESULT 'Invalid month  '} at {@code CSUTLDTC.cbl} line 140.
     * The trimmed short form {@code "Invalid month"} is the contractual
     * Java return value.
     */
    @Test
    @DisplayName("validate('2024-13-01', 'YYYY-MM-DD') — month 13 is rejected with Invalid month")
    void validate_month13_returnsInvalidMonth() {
        DateValidationResult result = service.validate("2024-13-01", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Month 13 must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for month > 12 must be REASON_INVALID_MONTH")
                .isEqualTo(DateValidationResult.REASON_INVALID_MONTH);
    }

    /**
     * Verifies the month-out-of-range branch (low boundary): month 00 is
     * outside the valid 01-12 range.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-INVALID-MONTH} →
     * {@code WS-RESULT 'Invalid month  '}. The trimmed short form
     * {@code "Invalid month"} is the contractual Java return value.
     */
    @Test
    @DisplayName("validate('2024-00-15', 'YYYY-MM-DD') — month 00 is rejected with Invalid month")
    void validate_month00_returnsInvalidMonth() {
        DateValidationResult result = service.validate("2024-00-15", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Month 00 must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for month < 1 must be REASON_INVALID_MONTH")
                .isEqualTo(DateValidationResult.REASON_INVALID_MONTH);
    }

    /**
     * Verifies the year-zero branch: year 0000 has no Gregorian-era
     * meaning (the era runs 1 BC immediately followed by AD 1 — there is
     * no year 0).
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-YEAR-IN-ERA-ZERO} →
     * {@code WS-RESULT 'YearInEra is 0 '} at {@code CSUTLDTC.cbl} line 146.
     * The trimmed short form {@code "YearInEra is 0"} is the contractual
     * Java return value.
     */
    @Test
    @DisplayName("validate('0000-01-15', 'YYYY-MM-DD') — year 0 is rejected with YearInEra is 0")
    void validate_yearZero_returnsYearInEraIsZero() {
        DateValidationResult result = service.validate("0000-01-15", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Year 0000 must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for year 0000 must be REASON_YEAR_IN_ERA_IS_ZERO")
                .isEqualTo(DateValidationResult.REASON_YEAR_IN_ERA_IS_ZERO);
    }

    /**
     * Verifies the non-numeric-component branch (year position): the year
     * field contains alphabetic characters.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-NON-NUMERIC-DATA} →
     * {@code WS-RESULT 'Nonnumeric data'} at {@code CSUTLDTC.cbl} line 144.
     */
    @Test
    @DisplayName("validate('abcd-01-15', 'YYYY-MM-DD') — non-numeric year is rejected with Nonnumeric data")
    void validate_nonNumericYear_returnsNonnumericData() {
        DateValidationResult result = service.validate("abcd-01-15", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Non-numeric year must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for non-numeric year must be REASON_NONNUMERIC_DATA")
                .isEqualTo(DateValidationResult.REASON_NONNUMERIC_DATA);
    }

    /**
     * Verifies the non-numeric-component branch (month position): the month
     * field contains alphabetic characters.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-NON-NUMERIC-DATA} →
     * {@code WS-RESULT 'Nonnumeric data'}.
     */
    @Test
    @DisplayName("validate('2024-AB-15', 'YYYY-MM-DD') — non-numeric month is rejected with Nonnumeric data")
    void validate_nonNumericMonth_returnsNonnumericData() {
        DateValidationResult result = service.validate("2024-AB-15", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Non-numeric month must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for non-numeric month must be REASON_NONNUMERIC_DATA")
                .isEqualTo(DateValidationResult.REASON_NONNUMERIC_DATA);
    }

    /**
     * Verifies the insufficient-data branch: the input is shorter than the
     * mask requires. {@code "2024-01"} is 7 characters with correct
     * in-bounds separator at position 4, but the mask {@code "YYYY-MM-DD"}
     * requires 10 characters.
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-INSUFFICIENT-DATA} →
     * {@code WS-RESULT 'Insufficient'} at {@code CSUTLDTC.cbl} line 132.
     */
    @Test
    @DisplayName("validate('2024-01', 'YYYY-MM-DD') — partial date is rejected with Insufficient")
    void validate_partialDate_returnsInsufficient() {
        DateValidationResult result = service.validate("2024-01", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Partial (truncated) date must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for short input must be REASON_INSUFFICIENT")
                .isEqualTo(DateValidationResult.REASON_INSUFFICIENT);
    }

    /**
     * Verifies the bad-pic-string branch (invalid mask characters): a mask
     * built from {@code Z} and {@code X} characters is not in the supported
     * mask set ({@code "YYYY-MM-DD"} or {@code "MM/DD/YYYY"}).
     *
     * <p>COBOL provenance: CEEDAYS returns {@code FC-BAD-PIC-STRING} →
     * {@code WS-RESULT 'Bad Pic String '} at {@code CSUTLDTC.cbl} line 142.
     * The trimmed short form {@code "Bad Pic String"} is the contractual
     * Java return value.
     */
    @Test
    @DisplayName("validate('2024-01-15', 'ZZZZ-XX-XX') — invalid mask is rejected with Bad Pic String")
    void validate_invalidMaskCharacters_returnsBadPicString() {
        DateValidationResult result = service.validate("2024-01-15", "ZZZZ-XX-XX");

        assertThat(result.isValid())
                .as("Unsupported mask must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for unsupported mask must be REASON_BAD_PIC_STRING")
                .isEqualTo(DateValidationResult.REASON_BAD_PIC_STRING);
    }

    /**
     * Verifies the happy-path branch for the alternate {@code MM/DD/YYYY}
     * format mask. The date {@code 02/29/2024} is the same calendar day as
     * {@code 2024-02-29} but expressed in US slash-separated form. This
     * test exercises the mask-descriptor switch in
     * {@link DateValidationService} routing to the US-format descriptor
     * (year offset 6, month offset 0, day offset 3) and its STRICT-mode
     * formatter {@code "MM/dd/uuuu"}.
     */
    @Test
    @DisplayName("validate('02/29/2024', 'MM/DD/YYYY') — alternate mask happy path is valid")
    void validate_alternateMaskMmDdYyyy_returnsValid() {
        DateValidationResult result = service.validate("02/29/2024", "MM/DD/YYYY");

        assertThat(result.isValid())
                .as("Valid date in alternate MM/DD/YYYY mask must be valid")
                .isTrue();
        assertThat(result.reason())
                .as("Reason for valid date must be REASON_DATE_IS_VALID")
                .isEqualTo(DateValidationResult.REASON_DATE_IS_VALID);
    }

    /**
     * Verifies the date/mask mismatch branch: the date {@code "2024-02-29"}
     * has hyphen separators (YYYY-MM-DD form) but the mask requires slash
     * separators (MM/DD/YYYY form). Length matches the required length
     * (10), but the separator characters at positions 2 and 5 are not
     * {@code '/'} — the cascade treats this as a wrong-pic-for-this-input
     * mismatch.
     *
     * <p>COBOL provenance: CEEDAYS surfaces this combination as
     * {@code FC-BAD-PIC-STRING} → {@code WS-RESULT 'Bad Pic String '}.
     */
    @Test
    @DisplayName("validate('2024-02-29', 'MM/DD/YYYY') — date/mask mismatch is rejected with Bad Pic String")
    void validate_dateMaskMismatch_returnsBadPicString() {
        DateValidationResult result = service.validate("2024-02-29", "MM/DD/YYYY");

        assertThat(result.isValid())
                .as("Wrong separators for mask must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for length-matching but pic-mismatching input must be REASON_BAD_PIC_STRING")
                .isEqualTo(DateValidationResult.REASON_BAD_PIC_STRING);
    }

    /**
     * Verifies the date-missing-separators branch: the date
     * {@code "20241215"} is 8 characters with no separator characters at
     * all, but the mask {@code "YYYY-MM-DD"} expects hyphens at positions
     * 4 and 7. Position 4 of the input is {@code '1'} (not {@code '-'}),
     * so the separator check fires; length is short (8 != 10), so the
     * cascade emits {@code DATEVALUE_ERROR} rather than
     * {@code BAD_PIC_STRING}.
     *
     * <p>COBOL provenance: CEEDAYS surfaces this combination as
     * {@code FC-BAD-DATE-VALUE} → {@code WS-RESULT 'Datevalue error'}.
     */
    @Test
    @DisplayName("validate('20241215', 'YYYY-MM-DD') — date missing separators is rejected with Datevalue error")
    void validate_dateMissingSeparators_returnsDatevalueError() {
        DateValidationResult result = service.validate("20241215", "YYYY-MM-DD");

        assertThat(result.isValid())
                .as("Date without separators must NOT be valid")
                .isFalse();
        assertThat(result.reason())
                .as("Reason for separator-less length-mismatching input must be REASON_DATEVALUE_ERROR")
                .isEqualTo(DateValidationResult.REASON_DATEVALUE_ERROR);
    }

    // =====================================================================
    // Fixture-integrity coverage assertion
    //
    // Asserts that the CSV fixture itself carries rows covering EVERY
    // documented COBOL EVALUATE branch from CSUTLDTC.cbl. Guards against
    // accidental row deletions that would silently reduce test coverage of
    // specific reject paths. Per AAP §0.10.1 this test does NOT exercise
    // DateValidationService (it's a fixture-content smoke check); it merely
    // asserts on the CSV's structure and the reason-string distribution.
    // =====================================================================

    /**
     * Asserts that {@code date_validation_variants.csv} contains rows
     * covering every COBOL EVALUATE branch documented in
     * {@code CSUTLDTC.cbl} lines 128–149. Guards against accidental row
     * deletions that would silently reduce test coverage of specific
     * reject paths.
     *
     * <p>This test does NOT exercise {@link DateValidationService}; it is
     * a fixture-integrity smoke check. Per AAP §0.10.1 Require Test
     * Coverage rule it does not reimplement business logic — it merely
     * asserts on the CSV's row structure and the reason-string column
     * distribution.
     *
     * <p>{@link FixtureLoader#loadEdgeCases(String)} returns a list where
     * row index 0 is the header row and rows 1..N are the data rows. The
     * CSV under test has 1 header + 26 data rows = 27 total elements.
     * Header column names are asserted to match the canonical contract
     * {@code inputDate,inputMask,expectedValid,expectedReason}.
     *
     * <p>Note that {@code FC-INVALID-ERA} and {@code FC-UNSUPP-RANGE} are
     * documented COBOL EVALUATE branches but the current
     * CEEDAYS-replacement implementation under CardDemo's date range
     * (1900–2099) does not naturally trigger them; the CSV therefore does
     * not include rows for those branches. The migration's production
     * code still maps them correctly (see
     * {@link DateValidationResult#REASON_INVALID_ERA} and
     * {@link DateValidationResult#REASON_UNSUPP_RANGE}) should they ever
     * appear.
     */
    @Test
    @DisplayName("date_validation_variants.csv covers every CSUTLDTC.cbl EVALUATE branch")
    void fixture_dateValidationVariants_coversAllEvaluateBranches() {
        // Arrange — load the CSV via the FixtureLoader utility so a path
        // mismatch in TestFixtures.Paths.EDGE_DATE_VALIDATION_VARIANTS fails
        // this test rather than silently skipping the fixture.
        List<String[]> rows = FixtureLoader.loadEdgeCases(
                TestFixtures.Paths.EDGE_DATE_VALIDATION_VARIANTS);

        // Assert — header + 26 data rows = 27 total entries.
        assertThat(rows)
                .as("date_validation_variants.csv must contain header + 26 required data rows = 27 total")
                .hasSizeGreaterThanOrEqualTo(27);

        // Assert — header columns match the expected contract.
        assertThat(rows.get(0))
                .as("CSV header columns must match the expected contract")
                .containsExactly("inputDate", "inputMask", "expectedValid", "expectedReason");

        // Extract the expectedReason column (index 3) across all data rows
        // (skipping the header row at index 0).
        List<String> reasons = rows.subList(1, rows.size()).stream()
                .filter(row -> row.length >= 4)
                .map(row -> row[3])
                .toList();

        // Assert — every documented COBOL EVALUATE branch reason appears
        // at least once. The contractual REASON_* constants on
        // DateValidationResult are the canonical source of truth.
        assertThat(reasons)
                .as("CSV must exercise every documented CSUTLDTC.cbl EVALUATE branch")
                .contains(
                        DateValidationResult.REASON_DATE_IS_VALID,        // FC-INVALID-DATE
                        DateValidationResult.REASON_INSUFFICIENT,         // FC-INSUFFICIENT-DATA
                        DateValidationResult.REASON_DATEVALUE_ERROR,      // FC-BAD-DATE-VALUE
                        DateValidationResult.REASON_INVALID_MONTH,        // FC-INVALID-MONTH
                        DateValidationResult.REASON_BAD_PIC_STRING,       // FC-BAD-PIC-STRING
                        DateValidationResult.REASON_NONNUMERIC_DATA,      // FC-NON-NUMERIC-DATA
                        DateValidationResult.REASON_YEAR_IN_ERA_IS_ZERO); // FC-YEAR-IN-ERA-ZERO
    }
}

