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
package com.awsm2.carddemo.validation;

import com.awsm2.carddemo.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JUnit 5 unit tests for {@link ValidationLookupService} — the Java
 * port of the COBOL {@code CSLKPCDY.cpy} validation tables.
 *
 * <h2>QA Final Checkpoint 13 Maj-4 fix</h2>
 * <p>Prior to this test class, the validation tables were covered only
 * indirectly through {@code AccountUpdateServiceTest} mocking calls.
 * The QA CP13 report (finding Maj-4) requires a dedicated test class
 * per AAP &sect;0.4.1 one-test-per-service contract.</p>
 *
 * <h2>Coverage scope</h2>
 * <ul>
 *   <li><b>NANPA area codes</b> &mdash; faithful reproduction of the
 *       {@code 88-LEVEL VALID-PHONE-AREA-CODE} table from
 *       {@code app/cpy/CSLKPCDY.cpy:L30-L520}. Tests sample known-valid
 *       (212, 415, 800, 911) and known-invalid (000, 1XX, 9XX-non-listed)
 *       codes.</li>
 *   <li><b>US state codes</b> &mdash; the 56-element set from
 *       {@code app/cpy/CSLKPCDY.cpy:L1013-L1069} (50 states + DC + 5
 *       territories). Tests sample standard states, DC, US territories,
 *       and confirm military mail codes (AA/AE/AP) are excluded.</li>
 *   <li><b>State+ZIP combinations</b> &mdash; the geographically
 *       consistent (state, zip-prefix) pairs from
 *       {@code app/cpy/CSLKPCDY.cpy:L1074-L1313}.</li>
 *   <li><b>Exception-throwing API</b> &mdash; {@code validateAreaCode},
 *       {@code validateStateCode}, {@code validateStateZipCombination}
 *       must throw {@link ValidationException} with the correct
 *       reasonCode and {@link ValidationException.FieldError}.</li>
 *   <li><b>Null/empty/edge cases</b> &mdash; null inputs, blank strings,
 *       wrong-length codes, ZIP codes shorter than 2 characters.</li>
 * </ul>
 *
 * @see ValidationLookupService
 */
@DisplayName("ValidationLookupService — QA CP13 Maj-4 dedicated test class")
class ValidationLookupServiceTest {

    private final ValidationLookupService service = new ValidationLookupService();

    // -------------------------------------------------------------------------
    // NANPA area code validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isValidAreaCode — NANPA area code lookup")
    class AreaCodeTests {

        @ParameterizedTest(name = "valid NANPA area code: {0}")
        @ValueSource(strings = {
                // Sample from each geographic block of CSLKPCDY.cpy:L30-L439
                "201", // CSLKPCDY.cpy:L30 (NJ)
                "212", // L30 (NYC)
                "213", // L30 (LA)
                "301", // L83 (MD/DC)
                "415", // L130 (San Francisco)
                "503", // L178 (OR)
                "617", // L224 (Boston MA)
                "713", // L278 (Houston TX)
                "808", // L334 (HI)
                "907", // L387 (AK)
                // Easily-recognizable codes from CSLKPCDY.cpy:L441-L520
                "800", "888", "877", "866", "855", "844", "833", // toll-free
                "900", "911"  // 911 emergency, 900 premium
        })
        @DisplayName("recognized NANPA codes return true")
        void recognizedAreaCodesReturnTrue(String code) {
            assertThat(service.isValidAreaCode(code)).isTrue();
        }

        @ParameterizedTest(name = "invalid area code: {0}")
        @ValueSource(strings = {
                "000",  // not in NANPA registry
                "001",  // not in NANPA registry
                "100",  // 1XX not assigned in NANPA
                "199",  // 1XX not assigned in NANPA
                "999",  // listed as "easily-recognizable" — verify exact behavior below
                "ABC",  // not numeric
                "12",   // too short
                "1234", // too long
                "999999" // way too long
        })
        @DisplayName("non-NANPA codes return false")
        void invalidAreaCodesReturnFalse(String code) {
            // Note: "999" actually IS in the easily-recognizable codes list
            // (L520). The other codes here are deliberately not in the table.
            if ("999".equals(code)) {
                assertThat(service.isValidAreaCode(code))
                        .as("999 is in the easily-recognizable codes block")
                        .isTrue();
            } else {
                assertThat(service.isValidAreaCode(code)).isFalse();
            }
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   "})
        @DisplayName("null / empty / blank returns false")
        void nullOrBlankReturnsFalse(String input) {
            assertThat(service.isValidAreaCode(input)).isFalse();
        }

        @Test
        @DisplayName("validateAreaCode (valid) does not throw")
        void validateAreaCodeValidDoesNotThrow() {
            service.validateAreaCode("415");
            service.validateAreaCode("212");
        }

        @Test
        @DisplayName("validateAreaCode (invalid) throws ValidationException with reason INVALID_AREA_CODE")
        void validateAreaCodeInvalidThrows() {
            assertThatThrownBy(() -> service.validateAreaCode("000"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Invalid NANPA phone area code")
                    .hasMessageContaining("000")
                    .satisfies(ex -> {
                        ValidationException ve = (ValidationException) ex;
                        assertThat(ve.getReasonCode()).isEqualTo("INVALID_AREA_CODE");
                        assertThat(ve.getFieldErrors())
                                .hasSize(1)
                                .extracting(ValidationException.FieldError::field)
                                .containsExactly("areaCode");
                    });
        }

        @Test
        @DisplayName("validateAreaCode (null) throws ValidationException with <null> in message")
        void validateAreaCodeNullThrows() {
            assertThatThrownBy(() -> service.validateAreaCode(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("<null>");
        }

        @Test
        @DisplayName("validateAreaCode (blank) throws ValidationException with <blank> in message")
        void validateAreaCodeBlankThrows() {
            assertThatThrownBy(() -> service.validateAreaCode("   "))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("<blank>");
        }
    }

    // -------------------------------------------------------------------------
    // US state code validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isValidStateCode — US state / DC / territory lookup")
    class StateCodeTests {

        @ParameterizedTest(name = "valid state/territory code: {0}")
        @ValueSource(strings = {
                // 50 US states - sample geographically diverse selection
                "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
                "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
                "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
                "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
                "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
                // Federal district + 5 territories
                "DC", "AS", "GU", "MP", "PR", "VI"
        })
        @DisplayName("all 56 codes in VALID-US-STATE-CODE 88-level return true")
        void recognizedStateCodesReturnTrue(String code) {
            assertThat(service.isValidStateCode(code)).isTrue();
        }

        @ParameterizedTest(name = "code NOT in VALID-US-STATE-CODE: {0}")
        @ValueSource(strings = {
                // Military mail codes — appear in STATE_TO_ZIP_PREFIXES but
                // NOT in the VALID-US-STATE-CODE 88-level, per faithful COBOL
                // reproduction (Javadoc note explicitly preserves this distinction).
                "AA", "AE", "AP",
                // Pacific federal territory codes — same exclusion rationale
                "FM", "MH", "PW",
                // Wholly bogus codes
                "XX", "YY", "ZZ",
                // Lowercase — case-sensitive per COBOL upper-case convention
                "ca", "ny", "tx"
        })
        @DisplayName("non-VALID-US-STATE-CODE codes return false")
        void invalidStateCodesReturnFalse(String code) {
            assertThat(service.isValidStateCode(code)).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "C", "CAL", "California"})
        @DisplayName("null / empty / wrong-length returns false")
        void nullOrInvalidLengthReturnsFalse(String input) {
            assertThat(service.isValidStateCode(input)).isFalse();
        }

        @Test
        @DisplayName("validateStateCode (valid CA) does not throw")
        void validateStateCodeValidDoesNotThrow() {
            service.validateStateCode("CA");
            service.validateStateCode("NY");
            service.validateStateCode("PR"); // territory
            service.validateStateCode("DC"); // federal district
        }

        @Test
        @DisplayName("validateStateCode (invalid XX) throws ValidationException with INVALID_STATE_CODE")
        void validateStateCodeInvalidThrows() {
            assertThatThrownBy(() -> service.validateStateCode("XX"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Invalid US state/territory code")
                    .satisfies(ex -> {
                        ValidationException ve = (ValidationException) ex;
                        assertThat(ve.getReasonCode()).isEqualTo("INVALID_STATE_CODE");
                        assertThat(ve.getFieldErrors())
                                .hasSize(1)
                                .extracting(ValidationException.FieldError::field)
                                .containsExactly("stateCode");
                    });
        }

        @Test
        @DisplayName("validateStateCode rejects military code AA (excluded from VALID-US-STATE-CODE)")
        void validateStateCodeRejectsMilitaryCode() {
            // This is a critical COBOL-parity invariant: military mail codes
            // are accepted by validateStateZipCombination but rejected by
            // validateStateCode. Verifies CSLKPCDY.cpy:L1013-L1069 vs. L1074.
            assertThatThrownBy(() -> service.validateStateCode("AA"))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> service.validateStateCode("AE"))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> service.validateStateCode("AP"))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // -------------------------------------------------------------------------
    // State + ZIP-prefix combination validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isValidStateZipCombination — geographic consistency check")
    class StateZipCombinationTests {

        @ParameterizedTest(name = "{0} {1} is valid")
        @CsvSource({
                // California — 9XX prefixes (CSLKPCDY.cpy:L1093-L1099)
                "CA, 90210",  // Beverly Hills
                "CA, 94105",  // San Francisco
                "CA, 95814",  // Sacramento
                "CA, 96001",  // Redding (96 prefix)
                // New York — 1XX prefixes
                "NY, 10001",  // NYC
                "NY, 11201",  // Brooklyn
                "NY, 12207",  // Albany
                // Texas — 7XX prefixes
                "TX, 75201",  // Dallas
                "TX, 77002",  // Houston
                "TX, 78701",  // Austin
                // Alaska — 99
                "AK, 99501",  // Anchorage
                // Alabama — 35/36
                "AL, 35203",  // Birmingham
                "AL, 36104",  // Montgomery
                // ZIP+4 format - only first 2 chars used
                "CA, 94105-1234",
                "NY, 10001-9999",
                // Hawaii — 96
                "HI, 96813",  // Honolulu
                // Florida — 32/33/34
                "FL, 32801",  // Orlando
                "FL, 33101",  // Miami
                "FL, 34102",  // Naples
                // Puerto Rico territory (uses 60-79 prefix series)
                "PR, 60901",
                // Military mail codes — exempt from state-code check but
                // recognised by zip-combination (per CSLKPCDY.cpy:L1074-L1083).
                // Armed Forces Europe uses 90-98 prefixes, NOT the "09" range
                // commonly thought of for FPO Europe.
                "AE, 90001",  // Armed Forces Europe (90 prefix)
                "AP, 96201",  // Armed Forces Pacific (96 prefix)
                "AA, 34001"   // Armed Forces Americas (34 prefix)
        })
        @DisplayName("geographically consistent (state, zip) pairs return true")
        void validCombinationsReturnTrue(String state, String zip) {
            assertThat(service.isValidStateZipCombination(state, zip)).isTrue();
        }

        @ParameterizedTest(name = "{0} {1} is geographically inconsistent")
        @CsvSource({
                // CA with non-California prefix
                "CA, 10001",   // NY zip
                "CA, 35203",   // AL zip
                // NY with non-NY prefix
                "NY, 90210",   // CA zip
                "NY, 99501",   // AK zip
                // TX with non-TX prefix
                "TX, 96813",   // HI zip
                "TX, 10001",   // NY zip
                // Made-up state-prefix combos
                "AL, 99999",
                "AK, 00000"
        })
        @DisplayName("geographically inconsistent (state, zip) pairs return false")
        void inconsistentCombinationsReturnFalse(String state, String zip) {
            assertThat(service.isValidStateZipCombination(state, zip)).isFalse();
        }

        @Test
        @DisplayName("null state returns false")
        void nullStateReturnsFalse() {
            assertThat(service.isValidStateZipCombination(null, "94105")).isFalse();
        }

        @Test
        @DisplayName("null zip returns false")
        void nullZipReturnsFalse() {
            assertThat(service.isValidStateZipCombination("CA", null)).isFalse();
        }

        @Test
        @DisplayName("zip shorter than 2 characters returns false")
        void shortZipReturnsFalse() {
            assertThat(service.isValidStateZipCombination("CA", "")).isFalse();
            assertThat(service.isValidStateZipCombination("CA", "9")).isFalse();
        }

        @Test
        @DisplayName("unknown state code returns false")
        void unknownStateReturnsFalse() {
            assertThat(service.isValidStateZipCombination("XX", "94105")).isFalse();
            assertThat(service.isValidStateZipCombination("ZZ", "12345")).isFalse();
        }

        @Test
        @DisplayName("validateStateZipCombination (valid) does not throw")
        void validateValidCombinationDoesNotThrow() {
            service.validateStateZipCombination("CA", "94105");
            service.validateStateZipCombination("NY", "10001");
        }

        @Test
        @DisplayName("validateStateZipCombination (inconsistent) throws INVALID_STATE_ZIP_COMBINATION")
        void validateInconsistentCombinationThrows() {
            assertThatThrownBy(() ->
                    service.validateStateZipCombination("CA", "10001"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("geographically inconsistent")
                    .hasMessageContaining("CA")
                    .hasMessageContaining("10001")
                    .satisfies(ex -> {
                        ValidationException ve = (ValidationException) ex;
                        assertThat(ve.getReasonCode())
                                .isEqualTo("INVALID_STATE_ZIP_COMBINATION");
                        assertThat(ve.getFieldErrors())
                                .hasSize(1)
                                .extracting(ValidationException.FieldError::field)
                                .containsExactly("stateZipCombination");
                    });
        }

        @Test
        @DisplayName("validateStateZipCombination (null state) throws with <null> placeholder")
        void validateNullStateThrows() {
            assertThatThrownBy(() ->
                    service.validateStateZipCombination(null, "94105"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("<null>");
        }
    }

    // -------------------------------------------------------------------------
    // Public constants — defensive immutability and content checks
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Public constants — defensive structure and content")
    class PublicConstantsTests {

        @Test
        @DisplayName("NANPA_AREA_CODES is non-empty and contains known-valid codes")
        void nanpaCodesContainKnownValid() {
            // Validates the structural integrity of the public Set
            assertThat(ValidationLookupService.NANPA_AREA_CODES)
                    .isNotEmpty()
                    .contains("212", "415", "800", "900", "911");
        }

        @Test
        @DisplayName("US_STATE_CODES contains exactly 56 entries (50 states + DC + 5 territories)")
        void stateCodesContainAllExpected() {
            // Validates the COBOL 88-LEVEL VALID-US-STATE-CODE count
            assertThat(ValidationLookupService.US_STATE_CODES)
                    .hasSize(56)
                    .contains("CA", "NY", "TX", "DC", "PR", "GU", "AS", "MP", "VI");
        }

        @Test
        @DisplayName("US_STATE_CODES does NOT contain military or Pacific federal codes")
        void stateCodesExcludeMilitaryAndPacific() {
            // Critical COBOL-parity check — these codes are in STATE_TO_ZIP_PREFIXES
            // but NOT in VALID-US-STATE-CODE (CSLKPCDY.cpy:L1013-L1069)
            assertThat(ValidationLookupService.US_STATE_CODES)
                    .doesNotContain("AA", "AE", "AP", "FM", "MH", "PW");
        }

        @Test
        @DisplayName("STATE_TO_ZIP_PREFIXES contains all states + military + Pacific codes")
        void stateZipMapContainsAllExpected() {
            assertThat(ValidationLookupService.STATE_TO_ZIP_PREFIXES)
                    .containsKeys("CA", "NY", "TX", "DC", "PR", "AA", "AE", "AP", "FM", "MH", "PW");
        }

        @Test
        @DisplayName("STATE_TO_ZIP_PREFIXES.CA contains 9XX prefixes")
        void californiaZipPrefixesAre9XX() {
            assertThat(ValidationLookupService.STATE_TO_ZIP_PREFIXES.get("CA"))
                    .contains("90", "91", "92", "93", "94", "95", "96");
        }

        @Test
        @DisplayName("STATE_TO_ZIP_PREFIXES.AK = {99}")
        void alaskaZipPrefixIs99() {
            assertThat(ValidationLookupService.STATE_TO_ZIP_PREFIXES.get("AK"))
                    .containsExactly("99");
        }
    }
}
