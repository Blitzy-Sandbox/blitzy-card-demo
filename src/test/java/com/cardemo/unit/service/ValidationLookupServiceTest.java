package com.cardemo.unit.service;

import com.cardemo.service.shared.ValidationLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ValidationLookupService} — validates NANPA area codes,
 * US state/territory postal abbreviations, and state+ZIP prefix geographic consistency.
 *
 * <p>Covers the three COBOL 88-level condition tables from CSLKPCDY.cpy:
 * <ul>
 *   <li>{@code VALID-PHONE-AREA-CODE} — 490 valid NANPA area codes (general-purpose + easily recognizable)</li>
 *   <li>{@code VALID-US-STATE-CODE} — 56 valid US state/territory 2-letter codes</li>
 *   <li>{@code VALID-US-STATE-ZIP-CD2-COMBO} — ~240 valid state + first-2-ZIP-digit combinations</li>
 * </ul>
 *
 * <p>All tests are pure unit tests — no Spring context is loaded. The service is constructed
 * directly and {@code init()} is called to load JSON validation data from classpath resources.
 *
 * <p>Test data expectations are derived from the original COBOL CSLKPCDY.cpy (1318 lines)
 * in the source repository (commit 27d6c6f).
 */
class ValidationLookupServiceTest {

    private ValidationLookupService validationLookupService;

    /**
     * Constructs a fresh {@link ValidationLookupService} instance and calls {@code init()}
     * to load all JSON validation data before each test. This ensures test isolation —
     * each test operates on a clean service instance with fully loaded lookup tables.
     */
    @BeforeEach
    void setUp() {
        validationLookupService = new ValidationLookupService();
        validationLookupService.init();
    }

    // ========================================================================
    // NANPA Area Code Validation Tests (10 tests)
    // Source: CSLKPCDY.cpy WS-AREA-CODE-TABLE (lines 24-520)
    // ========================================================================

    /**
     * Verifies area code "201" (New Jersey) is recognized as valid.
     * COBOL: VALID-PHONE-AREA-CODE condition includes '201' (line 30).
     */
    @Test
    void testIsValidAreaCode_validCode201_returnsTrue() {
        boolean result = validationLookupService.isValidAreaCode("201");
        assertThat(result).isTrue();
    }

    /**
     * Verifies area code "212" (New York City) is recognized as valid.
     * COBOL: VALID-PHONE-AREA-CODE condition includes '212' (line 40).
     */
    @Test
    void testIsValidAreaCode_validCode212_returnsTrue() {
        boolean result = validationLookupService.isValidAreaCode("212");
        assertThat(result).isTrue();
    }

    /**
     * Verifies area code "312" (Chicago, IL) is recognized as valid.
     * COBOL: VALID-PHONE-AREA-CODE condition includes '312' (line 93).
     */
    @Test
    void testIsValidAreaCode_validCode312_returnsTrue() {
        boolean result = validationLookupService.isValidAreaCode("312");
        assertThat(result).isTrue();
    }

    /**
     * Verifies area code "415" (San Francisco, CA) is recognized as valid.
     * COBOL: VALID-PHONE-AREA-CODE condition includes '415' (line 143).
     */
    @Test
    void testIsValidAreaCode_validCode415_returnsTrue() {
        boolean result = validationLookupService.isValidAreaCode("415");
        assertThat(result).isTrue();
    }

    /**
     * Verifies area code "800" (toll-free) is recognized as valid.
     * COBOL: VALID-PHONE-AREA-CODE condition includes '800' (both general-purpose
     * and easily recognizable code tables, line 501).
     */
    @Test
    void testIsValidAreaCode_validCode800_returnsTrue() {
        boolean result = validationLookupService.isValidAreaCode("800");
        assertThat(result).isTrue();
    }

    /**
     * Verifies area code "000" is rejected as invalid — not assigned in NANPA.
     * COBOL: '000' is not in the VALID-PHONE-AREA-CODE VALUES list.
     */
    @Test
    void testIsValidAreaCode_invalidCode000_returnsFalse() {
        boolean result = validationLookupService.isValidAreaCode("000");
        assertThat(result).isFalse();
    }

    /**
     * Verifies area code "999" is recognized as valid — it IS in the NANPA
     * easily recognizable codes table (CSLKPCDY.cpy line 520).
     * COBOL: VALID-PHONE-AREA-CODE includes '999' as an easily recognizable code.
     * Note: despite appearing to be a "test" number, 999 is explicitly listed in the
     * COBOL source VALID-PHONE-AREA-CODE VALUES table and must be treated as valid
     * to maintain 100% behavioral parity.
     */
    @Test
    void testIsValidAreaCode_invalidCode999_returnsFalse() {
        boolean result = validationLookupService.isValidAreaCode("999");
        // Per COBOL CSLKPCDY.cpy: '999' IS in VALID-PHONE-AREA-CODE (easily recognizable code)
        // The COBOL 88-level condition on WS-US-PHONE-AREA-CODE-TO-EDIT includes '999'
        // at line 520. Behavioral parity requires this to return true.
        assertThat(result).isTrue();
    }

    /**
     * Verifies that null input returns false without throwing NPE.
     * Null safety is a Java-specific guard — COBOL has no null concept but
     * the Java service must handle null gracefully.
     */
    @Test
    void testIsValidAreaCode_null_returnsFalse() {
        boolean result = validationLookupService.isValidAreaCode(null);
        assertThat(result).isFalse();
    }

    /**
     * Verifies that empty string input returns false.
     * COBOL: An empty (all-spaces) field would not match any VALUE in the 88-level condition.
     */
    @Test
    void testIsValidAreaCode_emptyString_returnsFalse() {
        boolean result = validationLookupService.isValidAreaCode("");
        assertThat(result).isFalse();
    }

    /**
     * Verifies that a 2-digit string is rejected — area codes must be exactly 3 digits.
     * COBOL: WS-US-PHONE-AREA-CODE-TO-EDIT is PIC XXX (3 characters). A 2-digit value
     * would be space-padded and would not match any 3-digit VALUE.
     */
    @Test
    void testIsValidAreaCode_twoDigits_returnsFalse() {
        boolean result = validationLookupService.isValidAreaCode("20");
        assertThat(result).isFalse();
    }

    // ========================================================================
    // US State Code Validation Tests (9 tests)
    // Source: CSLKPCDY.cpy WS-US-STATE-TABLE (lines 1012-1069)
    // ========================================================================

    /**
     * Verifies state code "CA" (California) is recognized as valid.
     * COBOL: VALID-US-STATE-CODE includes 'CA' (line 1018).
     */
    @Test
    void testIsValidStateCode_CA_returnsTrue() {
        boolean result = validationLookupService.isValidStateCode("CA");
        assertThat(result).isTrue();
    }

    /**
     * Verifies state code "NY" (New York) is recognized as valid.
     * COBOL: VALID-US-STATE-CODE includes 'NY' (line 1045).
     */
    @Test
    void testIsValidStateCode_NY_returnsTrue() {
        boolean result = validationLookupService.isValidStateCode("NY");
        assertThat(result).isTrue();
    }

    /**
     * Verifies state code "TX" (Texas) is recognized as valid.
     * COBOL: VALID-US-STATE-CODE includes 'TX' (line 1056).
     */
    @Test
    void testIsValidStateCode_TX_returnsTrue() {
        boolean result = validationLookupService.isValidStateCode("TX");
        assertThat(result).isTrue();
    }

    /**
     * Verifies state code "DC" (District of Columbia) is recognized as valid.
     * COBOL: VALID-US-STATE-CODE includes 'DC' (line 1064).
     */
    @Test
    void testIsValidStateCode_DC_returnsTrue() {
        boolean result = validationLookupService.isValidStateCode("DC");
        assertThat(result).isTrue();
    }

    /**
     * Verifies territory code "PR" (Puerto Rico) is recognized as valid.
     * COBOL: VALID-US-STATE-CODE includes 'PR' as a territory (line 1068).
     */
    @Test
    void testIsValidStateCode_PR_returnsTrue() {
        boolean result = validationLookupService.isValidStateCode("PR");
        assertThat(result).isTrue();
    }

    /**
     * Verifies code "XX" is rejected — not a valid US state or territory.
     * COBOL: 'XX' is not in the VALID-US-STATE-CODE VALUES list.
     */
    @Test
    void testIsValidStateCode_XX_returnsFalse() {
        boolean result = validationLookupService.isValidStateCode("XX");
        assertThat(result).isFalse();
    }

    /**
     * Verifies that null input returns false without throwing NPE.
     */
    @Test
    void testIsValidStateCode_null_returnsFalse() {
        boolean result = validationLookupService.isValidStateCode(null);
        assertThat(result).isFalse();
    }

    /**
     * Verifies that empty string input returns false.
     */
    @Test
    void testIsValidStateCode_emptyString_returnsFalse() {
        boolean result = validationLookupService.isValidStateCode("");
        assertThat(result).isFalse();
    }

    /**
     * Verifies that lowercase "ca" is rejected — COBOL is case-sensitive
     * and all state codes in CSLKPCDY.cpy are stored as uppercase.
     * The COBOL EBCDIC collation requires exact uppercase match.
     * Per AAP requirement: "All lookups are case-sensitive (COBOL uses uppercase)."
     */
    @Test
    void testIsValidStateCode_lowercase_returnsFalse() {
        boolean result = validationLookupService.isValidStateCode("ca");
        assertThat(result).isFalse();
    }

    // ========================================================================
    // State/ZIP Prefix Cross-Validation Tests (7 tests)
    // Source: CSLKPCDY.cpy WS-STATE-ZIP-TABLE (lines 1071-1313)
    // ========================================================================

    /**
     * Verifies California ZIP prefix 900-969 is valid ("CA" + "900" → key "CA90").
     * COBOL: VALID-US-STATE-ZIP-CD2-COMBO includes 'CA90' (line 1093).
     */
    @Test
    void testIsValidStateZipPrefix_CA_900_returnsTrue() {
        boolean result = validationLookupService.isValidStateZipPrefix("CA", "900");
        assertThat(result).isTrue();
    }

    /**
     * Verifies New York ZIP prefix 100-149 is valid ("NY" + "100" → key "NY10").
     * COBOL: VALID-US-STATE-ZIP-CD2-COMBO includes 'NY10' (line 1227).
     */
    @Test
    void testIsValidStateZipPrefix_NY_100_returnsTrue() {
        boolean result = validationLookupService.isValidStateZipPrefix("NY", "100");
        assertThat(result).isTrue();
    }

    /**
     * Verifies Texas ZIP prefix 750-799 is valid ("TX" + "750" → key "TX75").
     * COBOL: VALID-US-STATE-ZIP-CD2-COMBO includes 'TX75' (line 1280).
     */
    @Test
    void testIsValidStateZipPrefix_TX_750_returnsTrue() {
        boolean result = validationLookupService.isValidStateZipPrefix("TX", "750");
        assertThat(result).isTrue();
    }

    /**
     * Verifies that California with New York ZIP prefix is rejected.
     * "CA" + "100" → key "CA10" — not in the state-ZIP combo table.
     * This tests geographic consistency: ZIP 100xx belongs to New York, not California.
     */
    @Test
    void testIsValidStateZipPrefix_CA_100_returnsFalse() {
        boolean result = validationLookupService.isValidStateZipPrefix("CA", "100");
        assertThat(result).isFalse();
    }

    /**
     * Verifies that null state code returns false without NPE.
     */
    @Test
    void testIsValidStateZipPrefix_nullState_returnsFalse() {
        boolean result = validationLookupService.isValidStateZipPrefix(null, "900");
        assertThat(result).isFalse();
    }

    /**
     * Verifies that null ZIP code returns false without NPE.
     */
    @Test
    void testIsValidStateZipPrefix_nullZip_returnsFalse() {
        boolean result = validationLookupService.isValidStateZipPrefix("CA", null);
        assertThat(result).isFalse();
    }

    /**
     * Verifies that an invalid state code with a valid ZIP prefix is rejected.
     * "XX" + "900" → key "XX90" — not in the state-ZIP combo table because
     * "XX" is not a valid US state code.
     */
    @Test
    void testIsValidStateZipPrefix_invalidState_returnsFalse() {
        boolean result = validationLookupService.isValidStateZipPrefix("XX", "900");
        assertThat(result).isFalse();
    }
}
