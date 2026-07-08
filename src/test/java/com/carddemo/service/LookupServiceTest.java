package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure, dependency-free unit test for {@link LookupService}.
 *
 * <p>{@code LookupService} is the Java translation of the COBOL lookup-code
 * copybook {@code CSLKPCDY} (frozen reference SHA {@code 27d6c6f}, read-only —
 * not copied into this repository). The copybook declares three {@code 88}-level
 * condition sets that this test exercises through the service's public API:</p>
 * <ul>
 *   <li>{@code 88 VALID-PHONE-AREA-CODE} on {@code WS-US-PHONE-AREA-CODE-TO-EDIT
 *       PIC XXX} &rarr; {@link LookupService#isValidPhoneAreaCode(String)}
 *       (490 North-American Numbering Plan area codes);</li>
 *   <li>{@code 88 VALID-US-STATE-CODE} on {@code US-STATE-CODE-TO-EDIT PIC X(2)}
 *       &rarr; {@link LookupService#isValidStateCode(String)} (56 state /
 *       territory codes: the 50 states, DC, and the five inhabited
 *       territories);</li>
 *   <li>{@code 88 VALID-US-STATE-ZIP-CD2-COMBO} on
 *       {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} &rarr;
 *       {@link LookupService#isValidStateZipCombo(String, String)}
 *       (240 state{@code +}first-two-of-ZIP combinations).</li>
 * </ul>
 *
 * <p>Every public method is null-safe and normalises its input by trimming and
 * upper-casing before the membership check, reproducing the fixed-width,
 * upper-cased COBOL working-storage fields. Each method is therefore exercised
 * with a <em>valid</em>, an <em>invalid</em>, and a <em>null</em> input so that
 * all branches — including the null guards and the ZIP-length guard — are
 * covered (feeding the JaCoCo line-coverage gate).</p>
 *
 * <p>This is a plain JUnit&nbsp;5 test: it loads no Spring context and uses no
 * Testcontainers, database, file, network I/O, or mocks — the service has no
 * collaborators, so it is simply instantiated with {@code new LookupService()}.
 * The backing lookup tables are {@code private}, so every assertion is
 * behavioural (no reflection into private state). Only {@link String} values are
 * exercised; no {@code float}/{@code double} appears anywhere, consistent with
 * the migration's decimal-fidelity constraints.</p>
 */
@DisplayName("LookupService — COBOL CSLKPCDY (SHA 27d6c6f) lookup-table validation")
class LookupServiceTest {

    /**
     * The class under test. {@code LookupService} has no collaborators, so a
     * fresh instance is created directly. JUnit&nbsp;5's default per-method test
     * lifecycle gives every test its own instance.
     */
    private final LookupService service = new LookupService();

    // ---------------------------------------------------------------------
    // isValidStateCode(String) — VALID-US-STATE-CODE (COACTUPC line 2495)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("isValidStateCode: a lower-case known code is accepted (case-insensitive)")
    void isValidStateCode_lowercaseKnown_returnsTrue() {
        // Proves the input is upper-cased before the membership check: "ca" -> "CA".
        assertThat(service.isValidStateCode("ca")).isTrue();
    }

    @Test
    @DisplayName("isValidStateCode: padded, mixed-case input is trimmed and upper-cased before lookup")
    void isValidStateCode_paddedMixedCase_returnsTrue() {
        // Leading/trailing spaces are trimmed and the value upper-cased, mirroring
        // the fixed-width, upper-cased COBOL field US-STATE-CODE-TO-EDIT: "  nY  " -> "NY".
        assertThat(service.isValidStateCode("  nY  ")).isTrue();
    }

    @Test
    @DisplayName("isValidStateCode: an unknown code returns false")
    void isValidStateCode_unknown_returnsFalse() {
        assertThat(service.isValidStateCode("ZZ")).isFalse();
    }

    @Test
    @DisplayName("isValidStateCode: null is rejected without throwing (null-safe)")
    void isValidStateCode_null_returnsFalse() {
        assertThat(service.isValidStateCode(null)).isFalse();
    }

    @Test
    @DisplayName("isValidStateCode: representative canonical codes (states, DC, territories) all return true")
    void isValidStateCode_allKnownCodes_returnTrue() {
        // A representative spread across the alphabet plus DC and the five inhabited
        // territories, transcribed verbatim from CSLKPCDY (lines 1014-1069). A plain
        // String[] is used (no generics) so the loop compiles clean under -Xlint:all.
        String[] canonicalCodes = {
                "AL", "AK", "AZ", "CA", "FL", "NY", "TX", "WY", // states across the alphabet
                "DC",                                            // District of Columbia
                "AS", "GU", "MP", "PR", "VI"                     // five inhabited territories
        };

        for (String code : canonicalCodes) {
            assertThat(service.isValidStateCode(code))
                    .as("canonical state/territory code %s should be recognised", code)
                    .isTrue();
        }
    }

    // ---------------------------------------------------------------------
    // isValidPhoneAreaCode(String) — VALID-PHONE-AREA-CODE (COACTUPC line 2297)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("isValidPhoneAreaCode: known NANP area codes return true")
    void isValidPhoneAreaCode_valid_returnsTrue() {
        // A spread of North-American Numbering Plan area codes present in CSLKPCDY.
        String[] validAreaCodes = {"201", "212", "305", "999"};

        for (String areaCode : validAreaCodes) {
            assertThat(service.isValidPhoneAreaCode(areaCode))
                    .as("area code %s should be recognised", areaCode)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("isValidPhoneAreaCode: unknown or non-numeric codes return false")
    void isValidPhoneAreaCode_invalid_returnsFalse() {
        // "000" is not an assigned area code; "ABC" is not numeric at all.
        assertThat(service.isValidPhoneAreaCode("000")).isFalse();
        assertThat(service.isValidPhoneAreaCode("ABC")).isFalse();
    }

    @Test
    @DisplayName("isValidPhoneAreaCode: null is rejected without throwing (null-safe)")
    void isValidPhoneAreaCode_null_returnsFalse() {
        assertThat(service.isValidPhoneAreaCode(null)).isFalse();
    }

    // ---------------------------------------------------------------------
    // isValidStateZipCombo(String, String) — VALID-US-STATE-ZIP-CD2-COMBO
    // (COACTUPC line 2542)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("isValidStateZipCombo: state + first-two-of-ZIP that exists returns true (case-insensitive)")
    void isValidStateZipCombo_matching_returnsTrue() {
        // "CA" + first two of "90210" = "CA90", a recognised combination.
        assertThat(service.isValidStateZipCombo("CA", "90210")).isTrue();
        // The state code is trimmed and upper-cased before the key is built, so a
        // lower-case state still matches: "ca" + "90" -> "CA90".
        assertThat(service.isValidStateZipCombo("ca", "90210")).isTrue();
        // Further recognised pairings across different states.
        assertThat(service.isValidStateZipCombo("NY", "10001")).isTrue();
        assertThat(service.isValidStateZipCombo("TX", "75001")).isTrue();
    }

    @Test
    @DisplayName("isValidStateZipCombo: a valid state with a non-matching ZIP prefix returns false")
    void isValidStateZipCombo_mismatch_returnsFalse() {
        // "CA" + "00" = "CA00", which is not a recognised combination.
        assertThat(service.isValidStateZipCombo("CA", "00000")).isFalse();
        // "NY" + "99" = "NY99", also unrecognised.
        assertThat(service.isValidStateZipCombo("NY", "99999")).isFalse();
    }

    @Test
    @DisplayName("isValidStateZipCombo: a null state code returns false (null-safe)")
    void isValidStateZipCombo_nullState_returnsFalse() {
        assertThat(service.isValidStateZipCombo(null, "90210")).isFalse();
    }

    @Test
    @DisplayName("isValidStateZipCombo: a null ZIP returns false (null-safe)")
    void isValidStateZipCombo_nullZip_returnsFalse() {
        assertThat(service.isValidStateZipCombo("CA", null)).isFalse();
    }

    @Test
    @DisplayName("isValidStateZipCombo: a ZIP shorter than two characters returns false")
    void isValidStateZipCombo_shortZip_returnsFalse() {
        // The key needs the first two ZIP characters; a single-character ZIP cannot
        // form one, so the length guard rejects it.
        assertThat(service.isValidStateZipCombo("CA", "9")).isFalse();
        // A blank ZIP trims to an empty string (length 0) and is likewise rejected.
        assertThat(service.isValidStateZipCombo("CA", "   ")).isFalse();
    }
}
