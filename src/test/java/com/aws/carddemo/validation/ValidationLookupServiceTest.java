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
//     edge-case fixture lookup_invalid_keys.csv via loadEdgeCases(String).
//     Used by the fixture-integrity coverage assertion test to verify the
//     CSV's category and happy/reject-path distribution without
//     re-implementing validation logic.
//
//   * TestFixtures — shared test constants holder exposing the nested Paths
//     class providing the EDGE_LOOKUP_INVALID_KEYS filename constant used by
//     the fixture-integrity assertion to load lookup_invalid_keys.csv via
//     FixtureLoader.loadEdgeCases(...) without hardcoding the filename in
//     the test body.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.FixtureLoader;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 imports — JUnit Jupiter API + Params (no JUnit 4, no Vintage engine).
//
//   * @DisplayName / @Test — JUnit 5 core test API for plain unit tests
//     (per-category sanity tests covering AREA_CODE / STATE_CODE / ZIP_PREFIX
//     happy and reject paths, plus the null-input defensive tests and the
//     fixture-integrity assertion); @DisplayName at the class and method
//     level for human-readable test reports surfacing the CSLKPCDY.cpy
//     provenance.
//
//   * @ParameterizedTest / @CsvFileSource / @ValueSource — JUnit 5
//     parameterized testing API. @CsvFileSource drives the 32-row primary
//     test (one invocation per CSV row across 3 lookup categories);
//     @ValueSource drives the empty/whitespace defensive parameterized tests
//     for each category-specific service method.
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
//     to perform stream-based per-category filtering and counting of CSV
//     rows.
// ---------------------------------------------------------------------------
import java.util.List;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent assertion DSL.
//
//   * assertThat — primary entry point; supports chained .as(...) messages
//     for unambiguous failure diagnostics that include both lookupCategory
//     and inputKey.
//
//   * catchThrowable — captures a Throwable from a lambda so the test can
//     branch on whether the production class returns a reject result OR
//     throws on null input (both are acceptable per the defensive-null
//     contract documented on each null-input test method).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit tests for {@link ValidationLookupService}, the migrated Java equivalent of the
 * COBOL copybook {@code app/cpy/CSLKPCDY.cpy} that holds large literal validation sets
 * for NANPA area codes, US state codes, and US state+ZIP-prefix combinations.
 *
 * <h2>COBOL Provenance — CSLKPCDY.cpy</h2>
 *
 * <p>{@code CSLKPCDY} defines three 88-level conditions that COBOL programs use to
 * validate user-supplied input on customer-onboarding and address-update screens:
 *
 * <ul>
 *   <li>{@code VALID-PHONE-AREA-CODE} — TRUE when the 3-digit input matches an
 *       enumerated NANPA area code. Excludes codes starting with 0 or 1 (per NANPA
 *       convention), the reserved fictional-use code {@code 555}, and the
 *       emergency-services code {@code 911}.</li>
 *   <li>{@code VALID-US-STATE-CODE} — TRUE when the 2-character input matches one of
 *       the 56 valid codes covering 50 states + DC + 5 US territories (PR, VI, GU,
 *       AS, MP).</li>
 *   <li>{@code VALID-US-STATE-ZIP-CD2-COMBO} — TRUE when the 4-character input is
 *       a valid pair of 2-char state code + 2-char ZIP prefix (e.g., {@code "AA34"}
 *       for military APO/FPO Americas).</li>
 * </ul>
 *
 * <p>The Java migration replaces these 88-level conditions with three pure-function
 * methods on {@link ValidationLookupService}:
 * <ul>
 *   <li>{@link ValidationLookupService#validateAreaCode(String)}</li>
 *   <li>{@link ValidationLookupService#validateStateCode(String)}</li>
 *   <li>{@link ValidationLookupService#validateZipPrefix(String)}</li>
 * </ul>
 *
 * <p>Each method returns a {@link LookupValidationResult} carrying an {@link
 * LookupValidationResult#isValid()} flag and a {@link LookupValidationResult#reason()}
 * string. The positive-control reason is the literal {@code "VALID"}; negative-control
 * reasons are specific human-readable messages (e.g.,
 * {@code "Area code starts with 0"}, {@code "State code must be 2 characters"}).
 *
 * <h2>Test Contract Source</h2>
 *
 * <p>The authoritative test contract lives in
 * {@code src/test/resources/fixtures/edge/lookup_invalid_keys.csv}, which enumerates
 * 32 input variants across the three categories. Every row produces one invocation
 * of {@link #validate_categoryAndKey_returnsExpectedResult(String, String, boolean,
 * String)}.
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>This test class contains NO parallel set of valid area codes, state codes, or
 * ZIP prefixes. The CSV is the canonical table; tests read it and assert on the real
 * production {@link ValidationLookupService}'s output. The dispatch {@code switch}
 * in the primary test method routes input to the correct category-specific service
 * method — this is call-site routing, not validation logic, because the test does
 * not decide whether an input is valid; it merely chooses which service method to ask.
 *
 * <h2>Coverage Target (AAP §0.7.1)</h2>
 *
 * <p>The {@code com.aws.carddemo.validation.**} package carries a {@code ≥90% line /
 * ≥85% branch} coverage floor. The 32 parameterized rows plus the per-category sanity
 * tests and defensive null/empty/whitespace tests achieve full coverage of the
 * service's branches:
 *
 * <ul>
 *   <li>Length-check branches (3 categories × 2 directions = 6 branches)</li>
 *   <li>Format-check branches (numeric / alphabetic / case sensitivity)</li>
 *   <li>NANPA leading-digit branches (starts-with-0, starts-with-1)</li>
 *   <li>NANPA reserved-code branches (555, 911)</li>
 *   <li>Set-membership branches (in-set vs not-in-set)</li>
 *   <li>ZIP format branches (4-char combo vs 5-digit all-numeric)</li>
 *   <li>Null-input defensive branches (3 categories)</li>
 * </ul>
 *
 * @see ValidationLookupService
 * @see LookupValidationResult
 */
@DisplayName("ValidationLookupService — CSLKPCDY.cpy migration parity")
final class ValidationLookupServiceTest {

    /**
     * Real production {@link ValidationLookupService} instance under test. Stateless
     * and final; safe to share across parallel test invocations per AAP §0.10.9
     * Test Independence and Parallelism.
     *
     * <p>The service requires no constructor arguments — the underlying lookup
     * sets (NANPA area codes, US state codes, state+ZIP combos) are static-final
     * tables derived directly from {@code app/cpy/CSLKPCDY.cpy} at class-load
     * time. Per AAP §0.10.1 Require Test Coverage rule, this is the REAL
     * production class, not a mock; no Mockito {@code @Mock} fields appear in
     * this test class.
     */
    private final ValidationLookupService service = new ValidationLookupService();

    // =====================================================================
    // PRIMARY CSV-driven parameterized test (32 invocations)
    // =====================================================================

    /**
     * Asserts that {@link ValidationLookupService}'s category-specific validation
     * methods return a result whose {@code isValid()} flag and {@code reason()} string
     * match the canonical mapping in
     * {@code src/test/resources/fixtures/edge/lookup_invalid_keys.csv}.
     *
     * <p>This is the primary correctness gate for {@link ValidationLookupService}.
     * Every documented validation branch (NANPA area codes, US state codes,
     * ZIP prefixes, each with happy paths and per-rejection-reason paths) is
     * exercised by at least one row from the CSV.
     *
     * <h3>Category dispatch</h3>
     *
     * <p>The test routes each input row to the correct service method via a small
     * {@code switch} expression on {@code lookupCategory}. This is call-site
     * routing, not validation logic — it merely chooses which method to invoke.
     * The actual decision of which keys are valid is made inside the production
     * service.
     *
     * @param lookupCategory one of {@code "AREA_CODE"}, {@code "STATE_CODE"},
     *                       {@code "ZIP_PREFIX"} — controls which service method
     *                       to invoke
     * @param inputKey       the key to validate (may be {@code null} if the CSV
     *                       cell is empty)
     * @param expectedValid  {@code true} for the happy path; {@code false} for any
     *                       reject
     * @param expectedReason verbatim reason string returned by the production
     *                       service ({@code "VALID"} for happy paths; specific
     *                       text for rejects)
     */
    @ParameterizedTest(name = "[{index}] category={0} key=''{1}'' -> valid={2}, reason=''{3}''")
    @CsvFileSource(
        resources = "/fixtures/edge/lookup_invalid_keys.csv",
        numLinesToSkip = 1)
    @DisplayName("validateXxx(key) returns expected validity and reason per lookup_invalid_keys.csv")
    void validate_categoryAndKey_returnsExpectedResult(
            String lookupCategory,
            String inputKey,
            boolean expectedValid,
            String expectedReason) {
        // Arrange — service is the field-level final instance; nothing to set up.

        // Act — dispatch to the appropriate category-specific service method.
        // NOTE: This switch is call-site ROUTING, not validation logic. The test
        // does not decide whether the input is valid; it merely chooses which
        // method to ask. The actual validation logic lives inside the production
        // service (AAP §0.10.1 Require Test Coverage rule compliance).
        LookupValidationResult result = switch (lookupCategory) {
            case "AREA_CODE"  -> service.validateAreaCode(inputKey);
            case "STATE_CODE" -> service.validateStateCode(inputKey);
            case "ZIP_PREFIX" -> service.validateZipPrefix(inputKey);
            default -> throw new IllegalArgumentException(
                "Unknown lookupCategory in CSV: '" + lookupCategory
                    + "'. Expected AREA_CODE, STATE_CODE, or ZIP_PREFIX.");
        };

        // Assert — result must not be null
        assertThat(result)
            .as("LookupValidationResult must not be null for category=%s key='%s'",
                lookupCategory, inputKey)
            .isNotNull();

        // Assert — isValid flag matches the CSV expectation exactly
        assertThat(result.isValid())
            .as("isValid() for category=%s key='%s'", lookupCategory, inputKey)
            .isEqualTo(expectedValid);

        // Assert — reason string (verbatim, no trim, no case change). Per
        // AAP §0.10.4 Immutable Boundaries, the production code's reason strings
        // are a contract that must match the CSV byte-for-byte.
        assertThat(result.reason())
            .as("reason() for category=%s key='%s'", lookupCategory, inputKey)
            .isEqualTo(expectedReason);
    }

    // =====================================================================
    // Defensive null tests per category (3 tests)
    // =====================================================================

    /**
     * Asserts that {@link ValidationLookupService#validateAreaCode(String)}
     * handles {@code null} input defensively — either by returning an
     * {@code isValid()=false} result with a non-empty reason or by throwing an
     * unchecked exception ({@link NullPointerException} or
     * {@link IllegalArgumentException}). Both behaviours preserve the same
     * "reject invalid input" semantics inherited from the COBOL paragraph that
     * rejects unset {@code WS-US-PHONE-AREA-CODE-TO-EDIT} values.
     */
    @Test
    @DisplayName("validateAreaCode(null) returns invalid result or throws (defensive)")
    void validateAreaCode_nullInput_returnsRejectResultOrThrows() {
        // Act — capture any throwable to support both defensive contracts.
        Throwable thrown = catchThrowable(() -> {
            LookupValidationResult result = service.validateAreaCode(null);
            // If no exception: result must be non-null, invalid, with non-empty reason.
            assertThat(result)
                .as("validateAreaCode(null) must return a non-null result if it does not throw")
                .isNotNull();
            assertThat(result.isValid())
                .as("validateAreaCode(null) must NOT be valid")
                .isFalse();
            assertThat(result.reason())
                .as("validateAreaCode(null) reason must be non-null and non-empty")
                .isNotNull()
                .isNotEmpty();
        });

        // If the production code threw, accept NPE/IAE — both indicate
        // defensive null rejection equivalent to the COBOL semantics.
        if (thrown != null) {
            assertThat(thrown)
                .as("If validateAreaCode(null) throws, it must be NullPointerException or IllegalArgumentException")
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }
    }

    /**
     * Asserts that {@link ValidationLookupService#validateStateCode(String)}
     * handles {@code null} input defensively. See
     * {@link #validateAreaCode_nullInput_returnsRejectResultOrThrows} for the
     * contract rationale.
     */
    @Test
    @DisplayName("validateStateCode(null) returns invalid result or throws (defensive)")
    void validateStateCode_nullInput_returnsRejectResultOrThrows() {
        Throwable thrown = catchThrowable(() -> {
            LookupValidationResult result = service.validateStateCode(null);
            assertThat(result)
                .as("validateStateCode(null) must return a non-null result if it does not throw")
                .isNotNull();
            assertThat(result.isValid())
                .as("validateStateCode(null) must NOT be valid")
                .isFalse();
            assertThat(result.reason())
                .as("validateStateCode(null) reason must be non-null and non-empty")
                .isNotNull()
                .isNotEmpty();
        });

        if (thrown != null) {
            assertThat(thrown)
                .as("If validateStateCode(null) throws, it must be NullPointerException or IllegalArgumentException")
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }
    }

    /**
     * Asserts that {@link ValidationLookupService#validateZipPrefix(String)}
     * handles {@code null} input defensively. See
     * {@link #validateAreaCode_nullInput_returnsRejectResultOrThrows} for the
     * contract rationale.
     */
    @Test
    @DisplayName("validateZipPrefix(null) returns invalid result or throws (defensive)")
    void validateZipPrefix_nullInput_returnsRejectResultOrThrows() {
        Throwable thrown = catchThrowable(() -> {
            LookupValidationResult result = service.validateZipPrefix(null);
            assertThat(result)
                .as("validateZipPrefix(null) must return a non-null result if it does not throw")
                .isNotNull();
            assertThat(result.isValid())
                .as("validateZipPrefix(null) must NOT be valid")
                .isFalse();
            assertThat(result.reason())
                .as("validateZipPrefix(null) reason must be non-null and non-empty")
                .isNotNull()
                .isNotEmpty();
        });

        if (thrown != null) {
            assertThat(thrown)
                .as("If validateZipPrefix(null) throws, it must be NullPointerException or IllegalArgumentException")
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }
    }

    // =====================================================================
    // Defensive empty / whitespace input tests per category (3 tests × 5 inputs)
    // =====================================================================

    /**
     * Asserts that {@link ValidationLookupService#validateAreaCode(String)} returns
     * an {@code isValid()=false} result with a non-empty reason for every empty
     * or whitespace-only input. The specific reason text varies (could be wrong-length
     * for short inputs or wrong-character-class for whitespace-shaped inputs); the
     * contract is only that the result is invalid with diagnostic text.
     *
     * @param input one of {@code ""}, {@code " "}, {@code "  "}, {@code "\t"},
     *              or {@code "   "} (5 variants)
     */
    @ParameterizedTest(name = "[{index}] empty/whitespace area code ''{0}''")
    @ValueSource(strings = {"", " ", "  ", "\t", "   "})
    @DisplayName("validateAreaCode(empty or whitespace) returns invalid result")
    void validateAreaCode_emptyOrWhitespace_returnsRejectResult(String input) {
        LookupValidationResult result = service.validateAreaCode(input);

        assertThat(result)
            .as("validateAreaCode('%s') must return a non-null result", input)
            .isNotNull();
        assertThat(result.isValid())
            .as("validateAreaCode('%s') must NOT be valid", input)
            .isFalse();
        assertThat(result.reason())
            .as("validateAreaCode('%s') reason must be non-null and non-empty", input)
            .isNotNull()
            .isNotEmpty();
    }

    /**
     * Asserts that {@link ValidationLookupService#validateStateCode(String)} returns
     * an {@code isValid()=false} result with a non-empty reason for every empty
     * or whitespace-only input.
     *
     * @param input one of {@code ""}, {@code " "}, {@code "  "}, {@code "\t"},
     *              or {@code "   "} (5 variants)
     */
    @ParameterizedTest(name = "[{index}] empty/whitespace state code ''{0}''")
    @ValueSource(strings = {"", " ", "  ", "\t", "   "})
    @DisplayName("validateStateCode(empty or whitespace) returns invalid result")
    void validateStateCode_emptyOrWhitespace_returnsRejectResult(String input) {
        LookupValidationResult result = service.validateStateCode(input);

        assertThat(result)
            .as("validateStateCode('%s') must return a non-null result", input)
            .isNotNull();
        assertThat(result.isValid())
            .as("validateStateCode('%s') must NOT be valid", input)
            .isFalse();
        assertThat(result.reason())
            .as("validateStateCode('%s') reason must be non-null and non-empty", input)
            .isNotNull()
            .isNotEmpty();
    }

    /**
     * Asserts that {@link ValidationLookupService#validateZipPrefix(String)} returns
     * an {@code isValid()=false} result with a non-empty reason for every empty
     * or whitespace-only input. The 4-character whitespace variant is intentional —
     * it exercises the 4-char length branch with non-letter/non-digit content.
     *
     * @param input one of {@code ""}, {@code " "}, {@code "  "}, {@code "\t"},
     *              or {@code "    "} (5 variants — note the last is 4 spaces)
     */
    @ParameterizedTest(name = "[{index}] empty/whitespace ZIP prefix ''{0}''")
    @ValueSource(strings = {"", " ", "  ", "\t", "    "})
    @DisplayName("validateZipPrefix(empty or whitespace) returns invalid result")
    void validateZipPrefix_emptyOrWhitespace_returnsRejectResult(String input) {
        LookupValidationResult result = service.validateZipPrefix(input);

        assertThat(result)
            .as("validateZipPrefix('%s') must return a non-null result", input)
            .isNotNull();
        assertThat(result.isValid())
            .as("validateZipPrefix('%s') must NOT be valid", input)
            .isFalse();
        assertThat(result.reason())
            .as("validateZipPrefix('%s') reason must be non-null and non-empty", input)
            .isNotNull()
            .isNotEmpty();
    }

    // =====================================================================
    // Per-category sanity tests — AREA_CODE (8 tests)
    // Living documentation of every NANPA-rejection branch from CSLKPCDY.cpy
    // =====================================================================

    /**
     * Asserts that the canonical NANPA area code {@code 201} (New Jersey) is
     * accepted. {@code 201} is the first entry in the COBOL
     * {@code VALID-PHONE-AREA-CODE} 88-level condition.
     */
    @Test
    @DisplayName("validateAreaCode('201') — valid NANPA area code (New Jersey) is accepted")
    void validateAreaCode_validNanpaCode_returnsValid() {
        // CSLKPCDY.cpy: VALID-PHONE-AREA-CODE is TRUE for 201.
        LookupValidationResult result = service.validateAreaCode("201");

        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo("VALID");
    }

    /**
     * Asserts that area codes starting with {@code 0} are rejected per NANPA
     * convention (digit 0 is reserved for operator-assistance signalling).
     */
    @Test
    @DisplayName("validateAreaCode('000') — area codes starting with 0 are rejected per NANPA convention")
    void validateAreaCode_leadingZero_returnsRejectResult() {
        // NANPA convention: area codes cannot start with 0 (digit 0 is reserved
        // for operator-assistance signalling).
        LookupValidationResult result = service.validateAreaCode("000");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("Area code starts with 0");
    }

    /**
     * Asserts that area codes starting with {@code 1} are rejected per NANPA
     * convention (digit 1 is reserved for the long-distance signalling prefix).
     */
    @Test
    @DisplayName("validateAreaCode('100') — area codes starting with 1 are rejected per NANPA convention")
    void validateAreaCode_leadingOne_returnsRejectResult() {
        // NANPA convention: area codes cannot start with 1 (digit 1 is reserved
        // for the long-distance signalling prefix).
        LookupValidationResult result = service.validateAreaCode("100");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("Area code starts with 1");
    }

    /**
     * Asserts that the fictional-use area code {@code 555} is rejected. Note
     * that {@code 555} appears in the COBOL {@code VALID-PHONE-AREA-CODE} set;
     * the Java migration explicitly rejects it BEFORE consulting the set
     * (the documented departure from a literal COBOL translation noted in
     * {@link ValidationLookupService}).
     */
    @Test
    @DisplayName("validateAreaCode('555') — area code 555 is reserved for fictional use and rejected")
    void validateAreaCode_fictional555_returnsRejectResult() {
        // NANPA reserves 555 for fictional / directory-assistance use; the
        // Java migration overrides the COBOL set to reject this value.
        LookupValidationResult result = service.validateAreaCode("555");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("Area code not assigned");
    }

    /**
     * Asserts that the emergency-services area code {@code 911} is rejected.
     * Note that {@code 911} appears in the COBOL {@code VALID-PHONE-AREA-CODE}
     * set; the Java migration explicitly rejects it BEFORE consulting the set.
     */
    @Test
    @DisplayName("validateAreaCode('911') — area code 911 is reserved for emergency services and rejected")
    void validateAreaCode_emergency911_returnsRejectResult() {
        // NANPA reserves 911 for emergency services; the Java migration
        // overrides the COBOL set to reject this value.
        LookupValidationResult result = service.validateAreaCode("911");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("Area code is reserved emergency code");
    }

    /**
     * Asserts that non-numeric area codes (e.g., {@code "XXX"}) are rejected
     * with the "must be numeric" reason. The length check passes (3 chars) so
     * the cascade falls through to the numeric check.
     */
    @Test
    @DisplayName("validateAreaCode('XXX') — non-numeric area code is rejected")
    void validateAreaCode_nonNumeric_returnsRejectResult() {
        LookupValidationResult result = service.validateAreaCode("XXX");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("Area code must be numeric");
    }

    /**
     * Asserts that a 2-digit input is rejected as wrong length.
     */
    @Test
    @DisplayName("validateAreaCode('12') — wrong-length (too short) area code is rejected")
    void validateAreaCode_tooShort_returnsRejectResult() {
        LookupValidationResult result = service.validateAreaCode("12");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("Area code must be 3 digits");
    }

    /**
     * Asserts that a 4-digit input is rejected as wrong length.
     */
    @Test
    @DisplayName("validateAreaCode('1234') — wrong-length (too long) area code is rejected")
    void validateAreaCode_tooLong_returnsRejectResult() {
        LookupValidationResult result = service.validateAreaCode("1234");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("Area code must be 3 digits");
    }

    // =====================================================================
    // Per-category sanity tests — STATE_CODE (8 tests)
    // Living documentation of every CSLKPCDY.cpy VALID-US-STATE-CODE branch
    // =====================================================================

    /**
     * Asserts that the canonical state code {@code CA} (California) is accepted.
     * {@code CA} is one of the 50 US state entries in the COBOL
     * {@code VALID-US-STATE-CODE} 88-level condition.
     */
    @Test
    @DisplayName("validateStateCode('CA') — valid US state code is accepted")
    void validateStateCode_validStateCa_returnsValid() {
        // CSLKPCDY.cpy: VALID-US-STATE-CODE is TRUE for CA.
        LookupValidationResult result = service.validateStateCode("CA");

        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo("VALID");
    }

    /**
     * Asserts that {@code DC} (District of Columbia) is accepted. DC is included
     * alongside the 50 states in the COBOL valid set.
     */
    @Test
    @DisplayName("validateStateCode('DC') — District of Columbia is accepted")
    void validateStateCode_dcAccepted_returnsValid() {
        // DC is included in the valid set alongside 50 states.
        LookupValidationResult result = service.validateStateCode("DC");

        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo("VALID");
    }

    /**
     * Asserts that {@code PR} (Puerto Rico) is accepted. Per CSLKPCDY.cpy the
     * 5 US territories (PR, VI, GU, AS, MP) are in the valid set.
     */
    @Test
    @DisplayName("validateStateCode('PR') — Puerto Rico (US territory) is accepted")
    void validateStateCode_puertoRicoAccepted_returnsValid() {
        // Per CSLKPCDY.cpy: 5 territories (PR, VI, GU, AS, MP) are in the valid set.
        LookupValidationResult result = service.validateStateCode("PR");

        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo("VALID");
    }

    /**
     * Asserts that an unknown 2-character uppercase code (passing length /
     * alphabetic / case checks) is rejected with the "not in valid set" reason.
     */
    @Test
    @DisplayName("validateStateCode('XX') — unknown 2-char code is rejected")
    void validateStateCode_unknownCode_returnsRejectResult() {
        LookupValidationResult result = service.validateStateCode("XX");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("State code not in valid set");
    }

    /**
     * Asserts that a 1-character input is rejected as wrong length.
     */
    @Test
    @DisplayName("validateStateCode('A') — wrong-length (too short) state code is rejected")
    void validateStateCode_tooShort_returnsRejectResult() {
        LookupValidationResult result = service.validateStateCode("A");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("State code must be 2 characters");
    }

    /**
     * Asserts that a 3-character input is rejected as wrong length.
     */
    @Test
    @DisplayName("validateStateCode('ABC') — wrong-length (too long) state code is rejected")
    void validateStateCode_tooLong_returnsRejectResult() {
        LookupValidationResult result = service.validateStateCode("ABC");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("State code must be 2 characters");
    }

    /**
     * Asserts that a 2-digit numeric input (failing the alphabetic check) is
     * rejected with the "must be alphabetic" reason.
     */
    @Test
    @DisplayName("validateStateCode('12') — non-alphabetic state code is rejected")
    void validateStateCode_nonAlphabetic_returnsRejectResult() {
        LookupValidationResult result = service.validateStateCode("12");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("State code must be alphabetic");
    }

    /**
     * Asserts that a lowercase 2-character input (passing the alphabetic check
     * but failing the uppercase check) is rejected with the "must be uppercase"
     * reason. CSLKPCDY.cpy stores state codes as uppercase 2-char strings;
     * lowercase does not match the valid set.
     */
    @Test
    @DisplayName("validateStateCode('ca') — lowercase state code is rejected (case-sensitive validation)")
    void validateStateCode_lowercase_returnsRejectResult() {
        // CSLKPCDY.cpy stores state codes as uppercase 2-char strings; lowercase
        // does not match the valid set.
        LookupValidationResult result = service.validateStateCode("ca");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("State code must be uppercase");
    }

    // =====================================================================
    // Per-category sanity tests — ZIP_PREFIX (6 tests)
    // Living documentation of every CSLKPCDY.cpy VALID-US-STATE-ZIP-CD2-COMBO branch
    // =====================================================================

    /**
     * Asserts that the military APO/FPO Americas prefix {@code AA34} is accepted.
     * {@code AA34} is the first entry in the COBOL
     * {@code VALID-US-STATE-ZIP-CD2-COMBO} 88-level condition.
     */
    @Test
    @DisplayName("validateZipPrefix('AA34') — valid military APO/FPO Americas prefix is accepted")
    void validateZipPrefix_validApoCode_returnsValid() {
        // CSLKPCDY.cpy: VALID-US-STATE-ZIP-CD2-COMBO is TRUE for AA34 (military APO Americas).
        LookupValidationResult result = service.validateZipPrefix("AA34");

        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo("VALID");
    }

    /**
     * Asserts that the military Europe prefix {@code AE90} is accepted.
     */
    @Test
    @DisplayName("validateZipPrefix('AE90') — valid military Europe prefix is accepted")
    void validateZipPrefix_validApoEuropeCode_returnsValid() {
        // AE90 is in the valid set for military APO Europe.
        LookupValidationResult result = service.validateZipPrefix("AE90");

        assertThat(result.isValid()).isTrue();
        assertThat(result.reason()).isEqualTo("VALID");
    }

    /**
     * Asserts that an unknown 4-character state+digit combo (passing the
     * format check but missing from the lookup set) is rejected with the
     * "combo not in valid set" reason.
     */
    @Test
    @DisplayName("validateZipPrefix('XX12') — unknown state+ZIP combination is rejected")
    void validateZipPrefix_invalidCombo_returnsRejectResult() {
        LookupValidationResult result = service.validateZipPrefix("XX12");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("ZIP prefix combo not in valid set");
    }

    /**
     * Asserts that a 5-character all-alphabetic input (failing the numeric
     * check on the 5-digit branch) is rejected with the "must be numeric"
     * reason.
     */
    @Test
    @DisplayName("validateZipPrefix('ABCDE') — non-numeric 5-character ZIP prefix is rejected")
    void validateZipPrefix_nonNumeric_returnsRejectResult() {
        LookupValidationResult result = service.validateZipPrefix("ABCDE");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("ZIP prefix must be numeric");
    }

    /**
     * Asserts that a 4-digit all-numeric input (failing the state+digit
     * 4-char combo format check because the first 2 chars are not letters)
     * is rejected with the "must be 5 characters" reason.
     */
    @Test
    @DisplayName("validateZipPrefix('1234') — wrong-length (4 chars all numeric) ZIP prefix is rejected")
    void validateZipPrefix_tooShort_returnsRejectResult() {
        LookupValidationResult result = service.validateZipPrefix("1234");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("ZIP prefix must be 5 characters");
    }

    /**
     * Asserts that a 6-character input is rejected as wrong length.
     */
    @Test
    @DisplayName("validateZipPrefix('123456') — wrong-length (too long) ZIP prefix is rejected")
    void validateZipPrefix_tooLong_returnsRejectResult() {
        LookupValidationResult result = service.validateZipPrefix("123456");

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).isEqualTo("ZIP prefix must be 5 characters");
    }

    // =====================================================================
    // Fixture-integrity coverage assertion (1 test)
    // =====================================================================

    /**
     * Asserts that the CSV fixture covers all three lookup categories
     * (AREA_CODE, STATE_CODE, ZIP_PREFIX), each with at least one
     * happy-path row ({@code expectedReason = "VALID"}) and several
     * reject-path rows. Guards against accidental row deletions that would
     * silently reduce test coverage of specific categories.
     *
     * <p>This test does NOT exercise {@link ValidationLookupService}; it is a
     * fixture-integrity smoke check. Per AAP §0.10.1 it does not reimplement
     * business logic — it merely asserts on the CSV's row distribution.
     */
    @Test
    @DisplayName("lookup_invalid_keys.csv covers all 3 CSLKPCDY.cpy categories with both happy and reject paths")
    void fixture_lookupInvalidKeys_coversAllCategories() {
        // Arrange — load the CSV via the shared FixtureLoader utility.
        // TestFixtures.Paths.EDGE_LOOKUP_INVALID_KEYS is the canonical filename
        // constant; the loader resolves it under /fixtures/edge/ on the classpath.
        List<String[]> rows = FixtureLoader.loadEdgeCases(TestFixtures.Paths.EDGE_LOOKUP_INVALID_KEYS);

        // Assert — header + at least 32 data rows = at least 33 total rows
        assertThat(rows)
            .as("CSV must contain at least 1 header + 32 data rows = 33 total rows")
            .hasSizeGreaterThanOrEqualTo(33);

        // Header check — column names must match the contractual schema
        assertThat(rows.get(0))
            .as("CSV header columns must match the expected contract")
            .containsExactly("lookupCategory", "inputKey", "expectedValid", "expectedReason");

        // Extract the category column (index 0) across all data rows for
        // per-category distribution assertions.
        List<String> categories = rows.subList(1, rows.size()).stream()
            .map(row -> row[0])
            .toList();

        // Assert that all three categories are present in the data rows
        assertThat(categories)
            .as("CSV must cover all three CSLKPCDY.cpy categories (AREA_CODE, STATE_CODE, ZIP_PREFIX)")
            .contains("AREA_CODE", "STATE_CODE", "ZIP_PREFIX");

        // Assert per-category presence of both happy and reject paths
        for (String category : new String[] {"AREA_CODE", "STATE_CODE", "ZIP_PREFIX"}) {
            List<String[]> categoryRows = rows.subList(1, rows.size()).stream()
                .filter(row -> category.equals(row[0]))
                .toList();
            assertThat(categoryRows)
                .as("CSV must have at least 2 rows for category %s", category)
                .hasSizeGreaterThanOrEqualTo(2);

            long happyPathCount = categoryRows.stream()
                .filter(row -> "true".equalsIgnoreCase(row[2]))
                .count();
            long rejectPathCount = categoryRows.stream()
                .filter(row -> "false".equalsIgnoreCase(row[2]))
                .count();

            assertThat(happyPathCount)
                .as("CSV must have at least 1 happy-path row (expectedValid=true) for category %s", category)
                .isGreaterThanOrEqualTo(1);
            assertThat(rejectPathCount)
                .as("CSV must have at least 1 reject-path row (expectedValid=false) for category %s", category)
                .isGreaterThanOrEqualTo(1);
        }
    }
}
