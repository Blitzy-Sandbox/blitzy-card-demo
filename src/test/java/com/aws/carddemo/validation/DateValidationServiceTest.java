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
//     assertion test to verify the CSV's reason-text distribution without
//     re-implementing validation logic.
//
//   * TestFixtures — shared test constants holder exposing the nested Paths
//     class providing the EDGE_DATE_VALIDATION_VARIANTS filename constant
//     used by the fixture-integrity assertion to load the CSV via
//     FixtureLoader.loadEdgeCases(...) without hardcoding the filename in
//     the test body.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.FixtureLoader;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 imports — JUnit Jupiter API + Params (no JUnit 4, no Vintage engine).
//
//   * @DisplayName / @Test — JUnit 5 core test API for plain unit tests
//     (per-reason sanity tests covering each contractual reason string,
//     plus the fixture-integrity assertion); @DisplayName at the class and
//     method level for human-readable test reports surfacing the
//     CSUTLDTC.cbl provenance.
//
//   * @ParameterizedTest / @CsvFileSource — JUnit 5 parameterized testing
//     API. @CsvFileSource drives the 26-row primary test (one invocation
//     per CSV row) against the canonical date_validation_variants.csv
//     fixture, asserting the production class's two-fact verdict (isValid
//     + reason) row-by-row.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

// ---------------------------------------------------------------------------
// JDK imports
//
//   * java.util.List — used in the fixture-integrity assertion to receive
//     the List<String[]> returned by FixtureLoader.loadEdgeCases(...) and
//     to perform stream-based per-reason filtering and counting of CSV
//     rows.
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
 * of the COBOL program {@code app/cbl/CSUTLDTC.cbl} that wraps the IBM LE
 * {@code CEEDAYS} intrinsic for date validation against a format mask.
 *
 * <h2>COBOL Provenance — CSUTLDTC.cbl</h2>
 *
 * <p>{@code CSUTLDTC} is a small utility program (157 lines) that takes
 * three {@code LINKAGE SECTION} parameters:
 * {@code LS-DATE PIC X(10)}, {@code LS-DATE-FORMAT PIC X(10)}, and
 * {@code LS-RESULT PIC X(80)}. The program issues {@code CALL "CEEDAYS"
 * USING WS-DATE-TO-TEST, WS-DATE-FORMAT, OUTPUT-LILLIAN, FEEDBACK-CODE}
 * (lines 113–119) and inspects the returned {@code FEEDBACK-CODE} byte
 * string against nine {@code 88}-level conditions (lines 64–72):
 *
 * <ul>
 *   <li>{@code FC-INVALID-DATE} (all-zero feedback code) — historically the
 *       "no error" verdict; the COBOL idiom is that
 *       {@code FC-INVALID-DATE} fires when the date is NOT invalid.
 *       Translates to {@link DateValidationResult#REASON_DATE_IS_VALID}.</li>
 *   <li>{@code FC-INSUFFICIENT-DATA} — input shorter than the mask requires.
 *       Translates to {@link DateValidationResult#REASON_INSUFFICIENT}.</li>
 *   <li>{@code FC-BAD-DATE-VALUE} — input parses structurally but yields an
 *       impossible date. Translates to
 *       {@link DateValidationResult#REASON_DATEVALUE_ERROR}.</li>
 *   <li>{@code FC-INVALID-ERA} — non-Gregorian era. Reserved.</li>
 *   <li>{@code FC-UNSUPP-RANGE} — year outside CEEDAYS-supported range.
 *       Reserved.</li>
 *   <li>{@code FC-INVALID-MONTH} — month outside 01–12. Translates to
 *       {@link DateValidationResult#REASON_INVALID_MONTH}.</li>
 *   <li>{@code FC-BAD-PIC-STRING} — mask malformed or unsupported.
 *       Translates to {@link DateValidationResult#REASON_BAD_PIC_STRING}.</li>
 *   <li>{@code FC-NON-NUMERIC-DATA} — a numeric component contained
 *       non-digits. Translates to
 *       {@link DateValidationResult#REASON_NONNUMERIC_DATA}.</li>
 *   <li>{@code FC-YEAR-IN-ERA-ZERO} — year is 0000.
 *       Translates to
 *       {@link DateValidationResult#REASON_YEAR_IN_ERA_IS_ZERO}.</li>
 * </ul>
 *
 * <h2>Test Contract Source</h2>
 *
 * <p>The authoritative test contract lives in
 * {@code src/test/resources/fixtures/edge/date_validation_variants.csv},
 * which enumerates 26 input variants covering every contractual reason
 * string plus the leap-year rule (2024 ✓, 2000 ✓, 1900 ✗ per the Gregorian
 * century rule), invalid months, format variants ({@code YYYY-MM-DD} vs
 * {@code MM/DD/YYYY}), empty input, and unsupported masks. Every row
 * produces one invocation of
 * {@link #validate_dateAndMask_returnsExpectedResult(String, String, boolean, String)}.
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>This test class contains NO parallel implementation of date
 * validation. The CSV is the canonical table; tests read it and assert on
 * the real production {@link DateValidationService}'s output. The
 * production class is instantiated directly (not mocked) per the AAP
 * Require-Test-Coverage rule.
 *
 * <h2>Coverage Target (AAP §0.7.1)</h2>
 *
 * <p>The {@code com.aws.carddemo.validation.**} package carries a
 * {@code ≥90% line / ≥85% branch} coverage floor. The 26 CSV rows plus the
 * dedicated null-input and per-reason sanity tests achieve full coverage
 * of the service's branches:
 *
 * <ul>
 *   <li>Mask-validation branches (null mask, empty mask, unsupported mask,
 *       supported mask)</li>
 *   <li>Input null/empty branches (REASON_INSUFFICIENT)</li>
 *   <li>Length branches (less than required, greater than required)</li>
 *   <li>Separator-character branch (REASON_DATEVALUE_ERROR for missing
 *       separators)</li>
 *   <li>Numeric-component branches (REASON_NONNUMERIC_DATA)</li>
 *   <li>Year-zero branch (REASON_YEAR_IN_ERA_IS_ZERO)</li>
 *   <li>Month-range branches (month=0, month=13)</li>
 *   <li>STRICT-mode formatter branches (impossible dates,
 *       non-leap-year February 29)</li>
 *   <li>Happy-path branch (REASON_DATE_IS_VALID)</li>
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
     * per AAP §0.10.9 Test Independence and Parallelism.
     *
     * <p>The service requires no constructor arguments — the supported
     * masks ({@code "YYYY-MM-DD"} and {@code "MM/DD/YYYY"}) are static-final
     * descriptors. Per AAP §0.10.1 Require Test Coverage rule, this is the
     * REAL production class, not a mock; no Mockito {@code @Mock} fields
     * appear in this test class.
     */
    private final DateValidationService service = new DateValidationService();

    // =====================================================================
    // PRIMARY CSV-driven parameterized test (26 invocations)
    // =====================================================================

    /**
     * Asserts that {@link DateValidationService#validate(String, String)}
     * produces the expected verdict for every row of
     * {@code date_validation_variants.csv}. The CSV header is
     * {@code inputDate,inputMask,expectedValid,expectedReason}; the test
     * passes each row through the service and asserts both the boolean
     * verdict and the contractual reason string verbatim.
     *
     * <p>Every CSV row exercises exactly one branch of the service's
     * validation cascade, so a failure of this test indicates either:
     *
     * <ul>
     *   <li>a production bug in {@link DateValidationService} (the cascade
     *       returned the wrong verdict or the wrong reason), OR</li>
     *   <li>a contract drift in {@link DateValidationResult} (the
     *       {@code REASON_*} constants changed without coordinating with
     *       the CSV).</li>
     * </ul>
     *
     * <p>The {@code numLinesToSkip = 1} parameter skips the CSV header row.
     *
     * @param inputDate       the date string to validate (column 1)
     * @param inputMask       the format mask to validate against (column 2)
     * @param expectedValid   the expected {@link DateValidationResult#isValid()} (column 3)
     * @param expectedReason  the expected {@link DateValidationResult#reason()} (column 4)
     */
    @ParameterizedTest(name = "[{index}] validate(\"{0}\", \"{1}\") → valid={2}, reason=\"{3}\"")
    @CsvFileSource(
            resources = "/fixtures/edge/date_validation_variants.csv",
            numLinesToSkip = 1)
    @DisplayName("validate — CSV-driven parity for every CEEDAYS feedback-code branch")
    void validate_dateAndMask_returnsExpectedResult(
            String inputDate,
            String inputMask,
            boolean expectedValid,
            String expectedReason) {

        // Act — invoke the real production class (AAP §0.10.1).
        DateValidationResult result = service.validate(inputDate, inputMask);

        // Assert — two-fact verdict: boolean + reason string. The .as(...)
        // diagnostic includes both inputs so a failure surfaces unambiguously
        // which CSV row regressed.
        assertThat(result.isValid())
                .as("isValid() for inputDate=\"%s\", inputMask=\"%s\"",
                        inputDate, inputMask)
                .isEqualTo(expectedValid);
        assertThat(result.reason())
                .as("reason() for inputDate=\"%s\", inputMask=\"%s\"",
                        inputDate, inputMask)
                .isEqualTo(expectedReason);
    }

    // =====================================================================
    // Per-reason sanity tests (defence-in-depth against CSV regression)
    // =====================================================================

    /**
     * Verifies the happy-path branch: a well-formed ISO-8601 date passes
     * every step of the cascade and returns {@link DateValidationResult#valid()}.
     * This provides a defensive cross-check independent of the CSV — if
     * someone deletes every happy-path row from the CSV, this test still
     * fails on a regression.
     */
    @Test
    @DisplayName("validate — valid ISO-8601 date returns REASON_DATE_IS_VALID + isValid=true")
    void validate_validIsoDate_returnsValid() {
        DateValidationResult result = service.validate("2024-01-15", "YYYY-MM-DD");
        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo(DateValidationResult.REASON_DATE_IS_VALID);
    }

    /**
     * Verifies the happy-path branch for the alternate {@code MM/DD/YYYY}
     * mask. This ensures the mask-descriptor switch in
     * {@link DateValidationService} routes correctly to the US-format
     * formatter and the year/month/day positional indexes for that mask.
     */
    @Test
    @DisplayName("validate — valid MM/DD/YYYY date returns REASON_DATE_IS_VALID + isValid=true")
    void validate_validUsFormatDate_returnsValid() {
        DateValidationResult result = service.validate("02/29/2024", "MM/DD/YYYY");
        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo(DateValidationResult.REASON_DATE_IS_VALID);
    }

    /**
     * Verifies that the Gregorian leap-year century rule is preserved (a
     * direct port of the COBOL behaviour, which delegates the rule to
     * CEEDAYS). Year 1900 is NOT a leap year (divisible by 100 but not by
     * 400), so 1900-02-29 must reject with REASON_DATEVALUE_ERROR.
     */
    @Test
    @DisplayName("validate — Gregorian century rule: 1900-02-29 rejects with DATEVALUE_ERROR")
    void validate_nonLeapCenturyYear_returnsDateValueError() {
        DateValidationResult result = service.validate("1900-02-29", "YYYY-MM-DD");
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo(DateValidationResult.REASON_DATEVALUE_ERROR);
    }

    /**
     * Verifies that the Gregorian leap-year quadricentennial rule is
     * preserved: year 2000 IS a leap year (divisible by 400), so
     * 2000-02-29 accepts as valid.
     */
    @Test
    @DisplayName("validate — Gregorian quadricentennial rule: 2000-02-29 accepts as valid")
    void validate_leapQuadricentennialYear_returnsValid() {
        DateValidationResult result = service.validate("2000-02-29", "YYYY-MM-DD");
        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo(DateValidationResult.REASON_DATE_IS_VALID);
    }

    /**
     * Verifies the null-date defensive branch: a {@code null} input maps to
     * {@link DateValidationResult#REASON_INSUFFICIENT}. The COBOL caller
     * cannot produce a {@code null} {@code PIC X(10)} value (the field is
     * always SPACE-filled), but the Java caller can.
     */
    @Test
    @DisplayName("validate — null date returns REASON_INSUFFICIENT (defensive)")
    void validate_nullDate_returnsInsufficient() {
        DateValidationResult result = service.validate(null, "YYYY-MM-DD");
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo(DateValidationResult.REASON_INSUFFICIENT);
    }

    /**
     * Verifies the null-mask defensive branch: a {@code null} mask maps to
     * {@link DateValidationResult#REASON_BAD_PIC_STRING}. The COBOL caller
     * cannot produce a {@code null} mask (the field is always SPACE-filled),
     * but the Java caller can.
     */
    @Test
    @DisplayName("validate — null mask returns REASON_BAD_PIC_STRING (defensive)")
    void validate_nullMask_returnsBadPicString() {
        DateValidationResult result = service.validate("2024-01-15", null);
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo(DateValidationResult.REASON_BAD_PIC_STRING);
    }

    /**
     * Verifies that the production service rejects an obviously-malformed
     * mask (random uppercase letters) with REASON_BAD_PIC_STRING. Together
     * with the empty-mask and null-mask tests, this proves the
     * mask-validation branch covers all three negative input shapes.
     */
    @Test
    @DisplayName("validate — unsupported mask returns REASON_BAD_PIC_STRING")
    void validate_unsupportedMask_returnsBadPicString() {
        DateValidationResult result = service.validate("2024-01-15", "ZZZZ-XX-XX");
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo(DateValidationResult.REASON_BAD_PIC_STRING);
    }

    // =====================================================================
    // Fixture-integrity assertion (AAP §0.5.5 Cross-File Test Dependencies)
    // =====================================================================

    /**
     * Verifies that the canonical CSV fixture
     * {@code date_validation_variants.csv} carries the expected
     * coverage shape: at least one row per contractual reason string. This
     * defends against a CSV-only refactor that deletes or renames a row
     * group; the test would surface the drift even before
     * {@link #validate_dateAndMask_returnsExpectedResult(String, String, boolean, String)}
     * had a chance to exercise the missing branch.
     *
     * <p>The assertion uses the {@link FixtureLoader} utility to read the
     * CSV via the same classpath resource the parameterized test uses, so
     * a path mismatch in
     * {@link TestFixtures.Paths#EDGE_DATE_VALIDATION_VARIANTS} fails this
     * test rather than silently skipping the fixture.
     *
     * @throws Exception when the fixture cannot be loaded (the FixtureLoader
     *                   utility wraps I/O failures into checked exceptions)
     */
    @Test
    @DisplayName("Fixture integrity — every contractual REASON_* value appears in at least one CSV row")
    void csvFixtureCarriesEveryContractualReason() throws Exception {
        // Load via the same resource path the @CsvFileSource uses.
        // FixtureLoader.loadEdgeCases() returns ALL rows including the header
        // row (per its Javadoc: "first row is the header, remaining rows are
        // data rows"). The header row's expectedReason column carries the
        // literal "expectedReason" string which does not match any
        // contractual REASON_* constant, so it is filtered out naturally by
        // assertReasonAppearsAtLeastOnce(...) below.
        List<String[]> rows = FixtureLoader.loadEdgeCases(
                TestFixtures.Paths.EDGE_DATE_VALIDATION_VARIANTS);

        // Sanity — 1 header row + 26 data rows = 27 total entries.
        assertThat(rows)
                .as("date_validation_variants.csv carries 1 header + 26 data rows (CEEDAYS branches + leap variants + format variants)")
                .hasSize(27);

        // Every contractual reason string must appear in at least one row.
        // The reasons currently in the CSV are: REASON_DATE_IS_VALID,
        // REASON_INSUFFICIENT, REASON_DATEVALUE_ERROR, REASON_INVALID_MONTH,
        // REASON_BAD_PIC_STRING, REASON_NONNUMERIC_DATA,
        // REASON_YEAR_IN_ERA_IS_ZERO. The REASON_INVALID_ERA and
        // REASON_UNSUPP_RANGE reasons are reserved for non-Gregorian and
        // out-of-range inputs respectively (not currently covered in the
        // CSV but the constants exist for future fixtures).
        assertReasonAppearsAtLeastOnce(rows, DateValidationResult.REASON_DATE_IS_VALID);
        assertReasonAppearsAtLeastOnce(rows, DateValidationResult.REASON_INSUFFICIENT);
        assertReasonAppearsAtLeastOnce(rows, DateValidationResult.REASON_DATEVALUE_ERROR);
        assertReasonAppearsAtLeastOnce(rows, DateValidationResult.REASON_INVALID_MONTH);
        assertReasonAppearsAtLeastOnce(rows, DateValidationResult.REASON_BAD_PIC_STRING);
        assertReasonAppearsAtLeastOnce(rows, DateValidationResult.REASON_NONNUMERIC_DATA);
        assertReasonAppearsAtLeastOnce(rows, DateValidationResult.REASON_YEAR_IN_ERA_IS_ZERO);
    }

    /**
     * Helper for {@link #csvFixtureCarriesEveryContractualReason()} that
     * asserts the given {@code expectedReason} appears as the
     * {@code expectedReason} column (index 3) of at least one CSV row.
     *
     * @param rows           the loaded CSV rows (header already skipped)
     * @param expectedReason the contractual reason to look for
     */
    private static void assertReasonAppearsAtLeastOnce(List<String[]> rows, String expectedReason) {
        long matchCount = rows.stream()
                .filter(row -> row.length >= 4)
                .filter(row -> expectedReason.equals(row[3]))
                .count();
        assertThat(matchCount)
                .as("expected at least one CSV row carrying reason=\"%s\"", expectedReason)
                .isGreaterThanOrEqualTo(1);
    }
}
