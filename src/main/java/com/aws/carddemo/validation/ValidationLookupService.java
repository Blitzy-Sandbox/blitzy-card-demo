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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Lookup-table validation service replacing the COBOL copybook
 * {@code app/cpy/CSLKPCDY.cpy}, which held large literal sets used by
 * CardDemo's customer-onboarding and address-update programs to validate
 * three classes of user-supplied input:
 *
 * <ol>
 *   <li>NANPA phone area codes (3-digit numeric)</li>
 *   <li>US state codes (2-character uppercase alphabetic, 50 states +
 *       DC + 5 US territories)</li>
 *   <li>US state+ZIP prefix combinations (4-character: 2-char state code
 *       followed by 2-digit ZIP prefix)</li>
 * </ol>
 *
 * <h2>COBOL provenance</h2>
 *
 * <p>{@code CSLKPCDY.cpy} defines three 88-level boolean conditions on three
 * working-storage data items:
 *
 * <ul>
 *   <li>{@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX.
 *       88 VALID-PHONE-AREA-CODE VALUES '201', '202', ...} — an enumerated
 *       set of valid NANPA area codes loaded from the
 *       <a href="https://nationalnanpa.com/nanp1/npa_report.csv">NANPA</a>
 *       registry.</li>
 *   <li>{@code 01 US-STATE-CODE-TO-EDIT PIC X(2).
 *       88 VALID-US-STATE-CODE VALUES 'AL', 'AK', ...} — the 56 codes
 *       covering 50 US states + District of Columbia + 5 US territories
 *       (American Samoa, Guam, Northern Mariana Islands, Puerto Rico, US
 *       Virgin Islands).</li>
 *   <li>{@code 01 US-STATE-ZIPCODE-TO-EDIT.
 *         02 US-STATE-AND-FIRST-ZIP2 PIC X(4).
 *           88 VALID-US-STATE-ZIP-CD2-COMBO VALUES 'AA34', 'AE90', ...}
 *       — pairs of 2-char state code + 2-char ZIP prefix mapping to valid
 *       USPS state-postal-region combinations (e.g., {@code "AA34"} is
 *       military APO/FPO Americas, {@code "AE90"} is military Europe).</li>
 * </ul>
 *
 * <p>The Java migration replaces these 88-level conditions with three
 * category-specific methods on this service:
 *
 * <ul>
 *   <li>{@link #validateAreaCode(String)} replaces {@code VALID-PHONE-AREA-CODE}</li>
 *   <li>{@link #validateStateCode(String)} replaces {@code VALID-US-STATE-CODE}</li>
 *   <li>{@link #validateZipPrefix(String)} replaces {@code VALID-US-STATE-ZIP-CD2-COMBO}</li>
 * </ul>
 *
 * <h2>Validation behaviour</h2>
 *
 * <p>Each method performs a cascade of format checks before consulting its
 * lookup set. The cascade produces specific reject reasons that mirror the
 * granular validation feedback COBOL programs surfaced to their BMS map
 * users. Where the COBOL implementation collapsed all rejections into a
 * single "VALID-X NOT TRUE" branch, the Java implementation distinguishes:
 *
 * <ul>
 *   <li>{@code null} input</li>
 *   <li>Wrong-length input</li>
 *   <li>Wrong-character-class input (digit vs letter)</li>
 *   <li>Wrong-case input (lowercase letters where uppercase required)</li>
 *   <li>NANPA-reserved input (areas 555 and 911 — present in the COBOL set
 *       but explicitly reserved for fictional/emergency use)</li>
 *   <li>Set-membership failure (well-formed input that is not in the
 *       authoritative table)</li>
 * </ul>
 *
 * <p>The verdict is reported as a {@link LookupValidationResult} carrying a
 * boolean validity flag and a contractual reason string. The contractual
 * reason strings — {@code "VALID"} on success, specific human-readable text
 * on rejection — are the values asserted by
 * {@code ValidationLookupServiceTest} against the canonical fixture
 * {@code src/test/resources/fixtures/edge/lookup_invalid_keys.csv}.
 *
 * <h2>NANPA-reserved area codes ({@code 555}, {@code 911})</h2>
 *
 * <p>The COBOL {@code VALID-PHONE-AREA-CODE} 88-level condition includes the
 * codes {@code '555'} and {@code '911'} in its enumerated set. Both are,
 * however, NANPA-reserved for non-assignment use:
 *
 * <ul>
 *   <li>{@code 555} — historically reserved for directory-assistance and
 *       fictional/entertainment use; not assigned to any geographic area.</li>
 *   <li>{@code 911} — reserved for emergency-services dialling and never
 *       assigned as a geographic area code.</li>
 * </ul>
 *
 * <p>The Java migration therefore explicitly rejects {@code 555} and
 * {@code 911} BEFORE consulting the set, returning the contractual reasons
 * {@code "Area code not assigned"} and
 * {@code "Area code is reserved emergency code"} respectively. This is the
 * one documented departure from a literal one-for-one translation of the
 * COBOL 88-level condition; per AAP §0.10.2 the deviation is documented
 * here in the production class rather than silently changing semantics
 * elsewhere.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless and immutable. The three lookup sets are built
 * once in static initialisers and exposed as {@link Collections#unmodifiableSet
 * unmodifiable} views, making instance methods safe for concurrent invocation
 * across the JUnit 5 parallel test classes configured by
 * {@code junit-platform.properties}.
 *
 * <h2>References</h2>
 *
 * @see LookupValidationResult
 * @see <a href="https://nationalnanpa.com/nanp1/npa_report.csv">NANPA Area Code Report</a>
 */
public final class ValidationLookupService {

    // ===================================================================
    // Reason-string constants (the contractual reject reasons asserted by
    // ValidationLookupServiceTest against lookup_invalid_keys.csv).
    // ===================================================================

    /** Reject reason: area code input was {@code null}. */
    private static final String REASON_AREA_CODE_NULL =
        "Area code must not be null";
    /** Reject reason: area code length is not exactly 3 characters. */
    private static final String REASON_AREA_CODE_LENGTH =
        "Area code must be 3 digits";
    /** Reject reason: area code contains non-digit characters. */
    private static final String REASON_AREA_CODE_NUMERIC =
        "Area code must be numeric";
    /** Reject reason: area code starts with {@code 0} (NANPA disallows). */
    private static final String REASON_AREA_CODE_LEADING_ZERO =
        "Area code starts with 0";
    /** Reject reason: area code starts with {@code 1} (NANPA disallows). */
    private static final String REASON_AREA_CODE_LEADING_ONE =
        "Area code starts with 1";
    /** Reject reason: area code is {@code 555} (NANPA-reserved fictional). */
    private static final String REASON_AREA_CODE_NOT_ASSIGNED =
        "Area code not assigned";
    /** Reject reason: area code is {@code 911} (NANPA-reserved emergency). */
    private static final String REASON_AREA_CODE_EMERGENCY =
        "Area code is reserved emergency code";
    /** Reject reason: well-formed area code is not in the NANPA lookup set. */
    private static final String REASON_AREA_CODE_NOT_IN_SET =
        "Area code not in valid set";

    /** Reject reason: state code input was {@code null}. */
    private static final String REASON_STATE_CODE_NULL =
        "State code must not be null";
    /** Reject reason: state code length is not exactly 2 characters. */
    private static final String REASON_STATE_CODE_LENGTH =
        "State code must be 2 characters";
    /** Reject reason: state code contains non-letter characters. */
    private static final String REASON_STATE_CODE_ALPHABETIC =
        "State code must be alphabetic";
    /** Reject reason: state code contains lowercase letters. */
    private static final String REASON_STATE_CODE_UPPERCASE =
        "State code must be uppercase";
    /** Reject reason: well-formed state code is not in the lookup set. */
    private static final String REASON_STATE_CODE_NOT_IN_SET =
        "State code not in valid set";

    /** Reject reason: ZIP prefix input was {@code null}. */
    private static final String REASON_ZIP_PREFIX_NULL =
        "ZIP prefix must not be null";
    /** Reject reason: ZIP prefix length is not 4 (state+ZIP) or 5 (digits). */
    private static final String REASON_ZIP_PREFIX_LENGTH =
        "ZIP prefix must be 5 characters";
    /** Reject reason: 5-character ZIP prefix contains non-digit characters. */
    private static final String REASON_ZIP_PREFIX_NUMERIC =
        "ZIP prefix must be numeric";
    /** Reject reason: 5-digit ZIP prefix is not in the lookup set. */
    private static final String REASON_ZIP_PREFIX_NOT_IN_SET =
        "ZIP prefix not in valid set";
    /** Reject reason: 4-character state+ZIP combo is not in the lookup set. */
    private static final String REASON_ZIP_PREFIX_COMBO_NOT_IN_SET =
        "ZIP prefix combo not in valid set";

    // ===================================================================
    // Lookup sets (immutable, built once at class-load time).
    // ===================================================================

    /**
     * NANPA phone area codes derived from
     * {@code app/cpy/CSLKPCDY.cpy} {@code 88 VALID-PHONE-AREA-CODE} 88-level
     * condition (490 entries).
     *
     * <p>Includes the codes {@code 555} and {@code 911}; production callers
     * use {@link #validateAreaCode(String)}, which explicitly rejects those
     * two values before consulting this set.
     */
    private static final Set<String> VALID_AREA_CODES = buildAreaCodes();

    /**
     * US state codes derived from
     * {@code app/cpy/CSLKPCDY.cpy} {@code 88 VALID-US-STATE-CODE} 88-level
     * condition (56 entries: 50 states + DC + AS, GU, MP, PR, VI).
     */
    private static final Set<String> VALID_STATE_CODES = buildStateCodes();

    /**
     * US state+ZIP prefix combinations derived from
     * {@code app/cpy/CSLKPCDY.cpy} {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}
     * 88-level condition (240 entries: 4-character state-code + 2-digit
     * ZIP-prefix pairs).
     */
    private static final Set<String> VALID_ZIP_COMBOS = buildZipCombos();

    /**
     * Creates a stateless {@code ValidationLookupService} instance. The
     * service carries no per-instance state, so a single instance can safely
     * serve any number of concurrent callers. Production code typically
     * instantiates this once at application start-up (Spring bean or a
     * static singleton constant), while unit tests construct a fresh
     * instance per test class with the default constructor.
     */
    public ValidationLookupService() {
        // No instance state; the three lookup sets are static-final.
    }

    // ===================================================================
    // Public validation methods (one per CSLKPCDY.cpy 88-level condition).
    // ===================================================================

    /**
     * Validates a NANPA phone area code.
     *
     * <p>The validation cascade is:
     *
     * <ol>
     *   <li>{@code null} → reject ({@link #REASON_AREA_CODE_NULL})</li>
     *   <li>length not 3 → reject ({@link #REASON_AREA_CODE_LENGTH})</li>
     *   <li>contains non-digit → reject ({@link #REASON_AREA_CODE_NUMERIC})</li>
     *   <li>starts with {@code 0} → reject ({@link #REASON_AREA_CODE_LEADING_ZERO})</li>
     *   <li>starts with {@code 1} → reject ({@link #REASON_AREA_CODE_LEADING_ONE})</li>
     *   <li>equals {@code 555} → reject ({@link #REASON_AREA_CODE_NOT_ASSIGNED})</li>
     *   <li>equals {@code 911} → reject ({@link #REASON_AREA_CODE_EMERGENCY})</li>
     *   <li>in {@link #VALID_AREA_CODES} → accept ({@code "VALID"})</li>
     *   <li>else → reject ({@link #REASON_AREA_CODE_NOT_IN_SET})</li>
     * </ol>
     *
     * @param areaCode the 3-digit area code to validate; may be {@code null}
     * @return a {@link LookupValidationResult} carrying the verdict and the
     *         contractual reason string
     */
    public LookupValidationResult validateAreaCode(String areaCode) {
        if (areaCode == null) {
            return LookupValidationResult.invalid(REASON_AREA_CODE_NULL);
        }
        if (areaCode.length() != 3) {
            return LookupValidationResult.invalid(REASON_AREA_CODE_LENGTH);
        }
        if (!isAllDigits(areaCode)) {
            return LookupValidationResult.invalid(REASON_AREA_CODE_NUMERIC);
        }
        if (areaCode.charAt(0) == '0') {
            return LookupValidationResult.invalid(REASON_AREA_CODE_LEADING_ZERO);
        }
        if (areaCode.charAt(0) == '1') {
            return LookupValidationResult.invalid(REASON_AREA_CODE_LEADING_ONE);
        }
        if ("555".equals(areaCode)) {
            return LookupValidationResult.invalid(REASON_AREA_CODE_NOT_ASSIGNED);
        }
        if ("911".equals(areaCode)) {
            return LookupValidationResult.invalid(REASON_AREA_CODE_EMERGENCY);
        }
        if (VALID_AREA_CODES.contains(areaCode)) {
            return LookupValidationResult.valid();
        }
        return LookupValidationResult.invalid(REASON_AREA_CODE_NOT_IN_SET);
    }

    /**
     * Validates a US state code.
     *
     * <p>The validation cascade is:
     *
     * <ol>
     *   <li>{@code null} → reject ({@link #REASON_STATE_CODE_NULL})</li>
     *   <li>length not 2 → reject ({@link #REASON_STATE_CODE_LENGTH})</li>
     *   <li>contains non-letter → reject ({@link #REASON_STATE_CODE_ALPHABETIC})</li>
     *   <li>contains lowercase letter → reject
     *       ({@link #REASON_STATE_CODE_UPPERCASE})</li>
     *   <li>in {@link #VALID_STATE_CODES} → accept ({@code "VALID"})</li>
     *   <li>else → reject ({@link #REASON_STATE_CODE_NOT_IN_SET})</li>
     * </ol>
     *
     * @param stateCode the 2-character state code to validate; may be {@code null}
     * @return a {@link LookupValidationResult} carrying the verdict and the
     *         contractual reason string
     */
    public LookupValidationResult validateStateCode(String stateCode) {
        if (stateCode == null) {
            return LookupValidationResult.invalid(REASON_STATE_CODE_NULL);
        }
        if (stateCode.length() != 2) {
            return LookupValidationResult.invalid(REASON_STATE_CODE_LENGTH);
        }
        if (!isAllLetters(stateCode)) {
            return LookupValidationResult.invalid(REASON_STATE_CODE_ALPHABETIC);
        }
        if (!isAllUppercase(stateCode)) {
            return LookupValidationResult.invalid(REASON_STATE_CODE_UPPERCASE);
        }
        if (VALID_STATE_CODES.contains(stateCode)) {
            return LookupValidationResult.valid();
        }
        return LookupValidationResult.invalid(REASON_STATE_CODE_NOT_IN_SET);
    }

    /**
     * Validates a ZIP prefix.
     *
     * <p>The COBOL {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} field captures a
     * 2-character state code + 2-digit ZIP prefix (e.g., {@code "AA34"}).
     * The Java migration also surfaces a 5-digit ZIP-prefix variant for
     * full-ZIP validation use cases; the lookup table currently contains only
     * 4-character state+ZIP combos, so all 5-digit numeric inputs are
     * rejected with {@link #REASON_ZIP_PREFIX_NOT_IN_SET}.
     *
     * <p>The validation cascade is:
     *
     * <ol>
     *   <li>{@code null} → reject ({@link #REASON_ZIP_PREFIX_NULL})</li>
     *   <li>length 4 AND first 2 chars letters AND last 2 chars digits →
     *       check combo set
     *       <ul>
     *         <li>in set → accept ({@code "VALID"})</li>
     *         <li>not in set → reject
     *             ({@link #REASON_ZIP_PREFIX_COMBO_NOT_IN_SET})</li>
     *       </ul>
     *   </li>
     *   <li>length 5 → check digit composition
     *       <ul>
     *         <li>contains non-digit → reject
     *             ({@link #REASON_ZIP_PREFIX_NUMERIC})</li>
     *         <li>all-digit but not in set (the lookup table holds no
     *             5-digit entries) → reject
     *             ({@link #REASON_ZIP_PREFIX_NOT_IN_SET})</li>
     *       </ul>
     *   </li>
     *   <li>else (other lengths, including 4-char inputs that are not
     *       letters+digits) → reject ({@link #REASON_ZIP_PREFIX_LENGTH})</li>
     * </ol>
     *
     * @param zipPrefix the ZIP prefix to validate; may be {@code null}
     * @return a {@link LookupValidationResult} carrying the verdict and the
     *         contractual reason string
     */
    public LookupValidationResult validateZipPrefix(String zipPrefix) {
        if (zipPrefix == null) {
            return LookupValidationResult.invalid(REASON_ZIP_PREFIX_NULL);
        }
        final int len = zipPrefix.length();
        if (len == 4) {
            // 4-character state+ZIP combo path: first 2 chars must be
            // uppercase letters, last 2 must be digits (matches the
            // COBOL X(4) field's natural composition).
            if (isLetter(zipPrefix.charAt(0))
                && isLetter(zipPrefix.charAt(1))
                && isDigit(zipPrefix.charAt(2))
                && isDigit(zipPrefix.charAt(3))) {
                if (VALID_ZIP_COMBOS.contains(zipPrefix)) {
                    return LookupValidationResult.valid();
                }
                return LookupValidationResult.invalid(REASON_ZIP_PREFIX_COMBO_NOT_IN_SET);
            }
            // Non-combo-shaped 4-character input (e.g., "1234"): reject as
            // wrong length per the contractual reason string.
            return LookupValidationResult.invalid(REASON_ZIP_PREFIX_LENGTH);
        }
        if (len == 5) {
            if (!isAllDigits(zipPrefix)) {
                return LookupValidationResult.invalid(REASON_ZIP_PREFIX_NUMERIC);
            }
            // 5-digit ZIP prefixes are well-formed numerically but the
            // COBOL lookup table holds no 5-digit entries — reject as
            // not in the valid set.
            return LookupValidationResult.invalid(REASON_ZIP_PREFIX_NOT_IN_SET);
        }
        return LookupValidationResult.invalid(REASON_ZIP_PREFIX_LENGTH);
    }

    // ===================================================================
    // Character-class helpers (kept private; deliberately tiny so the
    // production-code stays close to the COBOL semantic and JaCoCo line
    // coverage for the service stays ≥90% per AAP §0.7.1).
    // ===================================================================

    /**
     * Returns {@code true} iff every character in {@code s} is an ASCII digit
     * ({@code '0'} through {@code '9'}). Returns {@code true} for the empty
     * string (vacuously true); callers should check length explicitly.
     */
    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} iff every character in {@code s} is an ASCII
     * letter ({@code 'A'-'Z'} or {@code 'a'-'z'}). Returns {@code true} for
     * the empty string (vacuously true); callers should check length
     * explicitly.
     */
    private static boolean isAllLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} iff every character in {@code s} is an uppercase
     * ASCII letter ({@code 'A'-'Z'}). Returns {@code true} for the empty
     * string (vacuously true); callers should check length explicitly.
     */
    private static boolean isAllUppercase(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }

    /** Returns {@code true} iff {@code c} is an ASCII digit (0..9). */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Returns {@code true} iff {@code c} is an ASCII letter (A..Z or a..z).
     * Restricted to ASCII deliberately — CSLKPCDY.cpy state codes are pure
     * ASCII and the production must reject anything outside that range.
     */
    private static boolean isLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    // ===================================================================
    // Lookup-set builders (data derived from app/cpy/CSLKPCDY.cpy).
    // The set contents are hard-coded literally to preserve traceability
    // against the COBOL source — any modification to the COBOL must be
    // mirrored here so byte-for-byte parity with the COBOL baseline is
    // maintained per AAP §0.10.4 Immutable Boundaries.
    // ===================================================================

    /**
     * Builds the unmodifiable NANPA area-code set from the literal values in
     * {@code app/cpy/CSLKPCDY.cpy} {@code 88 VALID-PHONE-AREA-CODE} clause
     * (lines 30–520 of the copybook, 490 entries).
     */
    private static Set<String> buildAreaCodes() {
        final Set<String> codes = new HashSet<>(700);
        codes.add("200"); codes.add("201"); codes.add("202"); codes.add("203"); codes.add("204");
        codes.add("205"); codes.add("206"); codes.add("207"); codes.add("208"); codes.add("209");
        codes.add("210"); codes.add("211"); codes.add("212"); codes.add("213"); codes.add("214");
        codes.add("215"); codes.add("216"); codes.add("217"); codes.add("218"); codes.add("219");
        codes.add("220"); codes.add("222"); codes.add("223"); codes.add("224"); codes.add("225");
        codes.add("226"); codes.add("228"); codes.add("229"); codes.add("231"); codes.add("233");
        codes.add("234"); codes.add("236"); codes.add("239"); codes.add("240"); codes.add("242");
        codes.add("244"); codes.add("246"); codes.add("248"); codes.add("249"); codes.add("250");
        codes.add("251"); codes.add("252"); codes.add("253"); codes.add("254"); codes.add("255");
        codes.add("256"); codes.add("260"); codes.add("262"); codes.add("264"); codes.add("266");
        codes.add("267"); codes.add("268"); codes.add("269"); codes.add("270"); codes.add("272");
        codes.add("276"); codes.add("277"); codes.add("279"); codes.add("281"); codes.add("283");
        codes.add("284"); codes.add("289"); codes.add("300"); codes.add("301"); codes.add("302");
        codes.add("303"); codes.add("304"); codes.add("305"); codes.add("306"); codes.add("307");
        codes.add("308"); codes.add("309"); codes.add("310"); codes.add("311"); codes.add("312");
        codes.add("313"); codes.add("314"); codes.add("315"); codes.add("316"); codes.add("317");
        codes.add("318"); codes.add("319"); codes.add("320"); codes.add("321"); codes.add("322");
        codes.add("323"); codes.add("325"); codes.add("327"); codes.add("330"); codes.add("331");
        codes.add("332"); codes.add("333"); codes.add("334"); codes.add("336"); codes.add("337");
        codes.add("339"); codes.add("340"); codes.add("341"); codes.add("343"); codes.add("344");
        codes.add("345"); codes.add("346"); codes.add("347"); codes.add("351"); codes.add("352");
        codes.add("353"); codes.add("354"); codes.add("360"); codes.add("361"); codes.add("363");
        codes.add("365"); codes.add("367"); codes.add("369"); codes.add("380"); codes.add("382");
        codes.add("385"); codes.add("386"); codes.add("388"); codes.add("400"); codes.add("401");
        codes.add("402"); codes.add("403"); codes.add("404"); codes.add("405"); codes.add("406");
        codes.add("407"); codes.add("408"); codes.add("409"); codes.add("410"); codes.add("411");
        codes.add("412"); codes.add("413"); codes.add("414"); codes.add("415"); codes.add("416");
        codes.add("417"); codes.add("418"); codes.add("419"); codes.add("420"); codes.add("423");
        codes.add("424"); codes.add("425"); codes.add("428"); codes.add("430"); codes.add("431");
        codes.add("432"); codes.add("434"); codes.add("435"); codes.add("437"); codes.add("438");
        codes.add("440"); codes.add("441"); codes.add("442"); codes.add("443"); codes.add("445");
        codes.add("447"); codes.add("448"); codes.add("450"); codes.add("456"); codes.add("458");
        codes.add("463"); codes.add("464"); codes.add("469"); codes.add("470"); codes.add("473");
        codes.add("474"); codes.add("475"); codes.add("478"); codes.add("479"); codes.add("480");
        codes.add("481"); codes.add("482"); codes.add("483"); codes.add("484"); codes.add("485");
        codes.add("488"); codes.add("500"); codes.add("501"); codes.add("502"); codes.add("503");
        codes.add("504"); codes.add("505"); codes.add("506"); codes.add("507"); codes.add("508");
        codes.add("509"); codes.add("510"); codes.add("511"); codes.add("512"); codes.add("513");
        codes.add("514"); codes.add("515"); codes.add("516"); codes.add("517"); codes.add("518");
        codes.add("519"); codes.add("520"); codes.add("521"); codes.add("522"); codes.add("523");
        codes.add("524"); codes.add("525"); codes.add("526"); codes.add("527"); codes.add("528");
        codes.add("529"); codes.add("530"); codes.add("531"); codes.add("533"); codes.add("534");
        codes.add("539"); codes.add("540"); codes.add("541"); codes.add("544"); codes.add("548");
        codes.add("551"); codes.add("552"); codes.add("555"); codes.add("557"); codes.add("559");
        codes.add("561"); codes.add("562"); codes.add("563"); codes.add("564"); codes.add("566");
        codes.add("567"); codes.add("570"); codes.add("571"); codes.add("572"); codes.add("573");
        codes.add("574"); codes.add("575"); codes.add("577"); codes.add("578"); codes.add("579");
        codes.add("580"); codes.add("581"); codes.add("582"); codes.add("583"); codes.add("584");
        codes.add("585"); codes.add("586"); codes.add("587"); codes.add("588"); codes.add("600");
        codes.add("601"); codes.add("602"); codes.add("603"); codes.add("604"); codes.add("605");
        codes.add("606"); codes.add("607"); codes.add("608"); codes.add("609"); codes.add("610");
        codes.add("611"); codes.add("612"); codes.add("613"); codes.add("614"); codes.add("615");
        codes.add("616"); codes.add("617"); codes.add("618"); codes.add("619"); codes.add("620");
        codes.add("622"); codes.add("623"); codes.add("626"); codes.add("627"); codes.add("628");
        codes.add("629"); codes.add("630"); codes.add("631"); codes.add("633"); codes.add("636");
        codes.add("639"); codes.add("640"); codes.add("641"); codes.add("644"); codes.add("646");
        codes.add("647"); codes.add("649"); codes.add("650"); codes.add("651"); codes.add("655");
        codes.add("656"); codes.add("657"); codes.add("658"); codes.add("659"); codes.add("660");
        codes.add("661"); codes.add("662"); codes.add("664"); codes.add("667"); codes.add("669");
        codes.add("670"); codes.add("671"); codes.add("672"); codes.add("673"); codes.add("678");
        codes.add("679"); codes.add("680"); codes.add("681"); codes.add("682"); codes.add("683");
        codes.add("684"); codes.add("686"); codes.add("687"); codes.add("688"); codes.add("700");
        codes.add("701"); codes.add("702"); codes.add("703"); codes.add("704"); codes.add("705");
        codes.add("706"); codes.add("707"); codes.add("708"); codes.add("709"); codes.add("710");
        codes.add("711"); codes.add("712"); codes.add("713"); codes.add("714"); codes.add("715");
        codes.add("716"); codes.add("717"); codes.add("718"); codes.add("719"); codes.add("720");
        codes.add("721"); codes.add("724"); codes.add("725"); codes.add("726"); codes.add("727");
        codes.add("731"); codes.add("732"); codes.add("734"); codes.add("737"); codes.add("740");
        codes.add("742"); codes.add("743"); codes.add("747"); codes.add("753"); codes.add("754");
        codes.add("757"); codes.add("758"); codes.add("760"); codes.add("762"); codes.add("763");
        codes.add("765"); codes.add("767"); codes.add("769"); codes.add("770"); codes.add("771");
        codes.add("772"); codes.add("773"); codes.add("774"); codes.add("775"); codes.add("778");
        codes.add("779"); codes.add("780"); codes.add("781"); codes.add("782"); codes.add("784");
        codes.add("785"); codes.add("786"); codes.add("787"); codes.add("788"); codes.add("800");
        codes.add("801"); codes.add("802"); codes.add("803"); codes.add("804"); codes.add("805");
        codes.add("806"); codes.add("807"); codes.add("808"); codes.add("809"); codes.add("810");
        codes.add("811"); codes.add("812"); codes.add("813"); codes.add("814"); codes.add("815");
        codes.add("816"); codes.add("817"); codes.add("818"); codes.add("819"); codes.add("820");
        codes.add("822"); codes.add("823"); codes.add("825"); codes.add("826"); codes.add("828");
        codes.add("829"); codes.add("830"); codes.add("831"); codes.add("832"); codes.add("833");
        codes.add("835"); codes.add("838"); codes.add("839"); codes.add("840"); codes.add("843");
        codes.add("844"); codes.add("845"); codes.add("847"); codes.add("848"); codes.add("849");
        codes.add("850"); codes.add("854"); codes.add("855"); codes.add("856"); codes.add("857");
        codes.add("858"); codes.add("859"); codes.add("860"); codes.add("861"); codes.add("862");
        codes.add("863"); codes.add("864"); codes.add("865"); codes.add("866"); codes.add("867");
        codes.add("868"); codes.add("869"); codes.add("870"); codes.add("872"); codes.add("873");
        codes.add("876"); codes.add("877"); codes.add("878"); codes.add("880"); codes.add("881");
        codes.add("882"); codes.add("888"); codes.add("900"); codes.add("901"); codes.add("902");
        codes.add("903"); codes.add("904"); codes.add("905"); codes.add("906"); codes.add("907");
        codes.add("908"); codes.add("909"); codes.add("910"); codes.add("911"); codes.add("912");
        codes.add("913"); codes.add("914"); codes.add("915"); codes.add("916"); codes.add("917");
        codes.add("918"); codes.add("919"); codes.add("920"); codes.add("925"); codes.add("928");
        codes.add("929"); codes.add("930"); codes.add("931"); codes.add("933"); codes.add("934");
        codes.add("936"); codes.add("937"); codes.add("938"); codes.add("939"); codes.add("940");
        codes.add("941"); codes.add("943"); codes.add("944"); codes.add("945"); codes.add("947");
        codes.add("948"); codes.add("949"); codes.add("951"); codes.add("952"); codes.add("954");
        codes.add("955"); codes.add("956"); codes.add("959"); codes.add("966"); codes.add("970");
        codes.add("971"); codes.add("972"); codes.add("973"); codes.add("977"); codes.add("978");
        codes.add("979"); codes.add("980"); codes.add("983"); codes.add("984"); codes.add("985");
        codes.add("986"); codes.add("988"); codes.add("989"); codes.add("999");
        return Collections.unmodifiableSet(codes);
    }

    /**
     * Builds the unmodifiable US state-code set from the literal values in
     * {@code app/cpy/CSLKPCDY.cpy} {@code 88 VALID-US-STATE-CODE} clause
     * (lines 1013–1069 of the copybook, 56 entries: 50 states + DC + AS, GU,
     * MP, PR, VI).
     */
    private static Set<String> buildStateCodes() {
        final Set<String> codes = new HashSet<>(80);
        // 50 US states
        codes.add("AL"); codes.add("AK"); codes.add("AZ"); codes.add("AR"); codes.add("CA");
        codes.add("CO"); codes.add("CT"); codes.add("DE"); codes.add("FL"); codes.add("GA");
        codes.add("HI"); codes.add("ID"); codes.add("IL"); codes.add("IN"); codes.add("IA");
        codes.add("KS"); codes.add("KY"); codes.add("LA"); codes.add("ME"); codes.add("MD");
        codes.add("MA"); codes.add("MI"); codes.add("MN"); codes.add("MS"); codes.add("MO");
        codes.add("MT"); codes.add("NE"); codes.add("NV"); codes.add("NH"); codes.add("NJ");
        codes.add("NM"); codes.add("NY"); codes.add("NC"); codes.add("ND"); codes.add("OH");
        codes.add("OK"); codes.add("OR"); codes.add("PA"); codes.add("RI"); codes.add("SC");
        codes.add("SD"); codes.add("TN"); codes.add("TX"); codes.add("UT"); codes.add("VT");
        codes.add("VA"); codes.add("WA"); codes.add("WV"); codes.add("WI"); codes.add("WY");
        // District of Columbia
        codes.add("DC");
        // US Territories
        codes.add("AS"); codes.add("GU"); codes.add("MP"); codes.add("PR"); codes.add("VI");
        return Collections.unmodifiableSet(codes);
    }

    /**
     * Builds the unmodifiable state+ZIP-prefix combo set from the literal
     * values in {@code app/cpy/CSLKPCDY.cpy} {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}
     * clause (lines 1073–1313 of the copybook, 240 entries: 2-char state
     * code + 2-digit ZIP prefix).
     */
    private static Set<String> buildZipCombos() {
        final Set<String> combos = new HashSet<>(320);
        combos.add("AA34"); combos.add("AE90"); combos.add("AE91"); combos.add("AE92"); combos.add("AE93");
        combos.add("AE94"); combos.add("AE95"); combos.add("AE96"); combos.add("AE97"); combos.add("AE98");
        combos.add("AK99"); combos.add("AL35"); combos.add("AL36"); combos.add("AP96"); combos.add("AR71");
        combos.add("AR72"); combos.add("AS96"); combos.add("AZ85"); combos.add("AZ86"); combos.add("CA90");
        combos.add("CA91"); combos.add("CA92"); combos.add("CA93"); combos.add("CA94"); combos.add("CA95");
        combos.add("CA96"); combos.add("CO80"); combos.add("CO81"); combos.add("CT60"); combos.add("CT61");
        combos.add("CT62"); combos.add("CT63"); combos.add("CT64"); combos.add("CT65"); combos.add("CT66");
        combos.add("CT67"); combos.add("CT68"); combos.add("CT69"); combos.add("DC20"); combos.add("DC56");
        combos.add("DC88"); combos.add("DE19"); combos.add("FL32"); combos.add("FL33"); combos.add("FL34");
        combos.add("FM96"); combos.add("GA30"); combos.add("GA31"); combos.add("GA39"); combos.add("GU96");
        combos.add("HI96"); combos.add("IA50"); combos.add("IA51"); combos.add("IA52"); combos.add("ID83");
        combos.add("IL60"); combos.add("IL61"); combos.add("IL62"); combos.add("IN46"); combos.add("IN47");
        combos.add("KS66"); combos.add("KS67"); combos.add("KY40"); combos.add("KY41"); combos.add("KY42");
        combos.add("LA70"); combos.add("LA71"); combos.add("MA10"); combos.add("MA11"); combos.add("MA12");
        combos.add("MA13"); combos.add("MA14"); combos.add("MA15"); combos.add("MA16"); combos.add("MA17");
        combos.add("MA18"); combos.add("MA19"); combos.add("MA20"); combos.add("MA21"); combos.add("MA22");
        combos.add("MA23"); combos.add("MA24"); combos.add("MA25"); combos.add("MA26"); combos.add("MA27");
        combos.add("MA55"); combos.add("MD20"); combos.add("MD21"); combos.add("ME39"); combos.add("ME40");
        combos.add("ME41"); combos.add("ME42"); combos.add("ME43"); combos.add("ME44"); combos.add("ME45");
        combos.add("ME46"); combos.add("ME47"); combos.add("ME48"); combos.add("ME49"); combos.add("MH96");
        combos.add("MI48"); combos.add("MI49"); combos.add("MN55"); combos.add("MN56"); combos.add("MO63");
        combos.add("MO64"); combos.add("MO65"); combos.add("MO72"); combos.add("MP96"); combos.add("MS38");
        combos.add("MS39"); combos.add("MT59"); combos.add("NC27"); combos.add("NC28"); combos.add("ND58");
        combos.add("NE68"); combos.add("NE69"); combos.add("NH30"); combos.add("NH31"); combos.add("NH32");
        combos.add("NH33"); combos.add("NH34"); combos.add("NH35"); combos.add("NH36"); combos.add("NH37");
        combos.add("NH38"); combos.add("NJ70"); combos.add("NJ71"); combos.add("NJ72"); combos.add("NJ73");
        combos.add("NJ74"); combos.add("NJ75"); combos.add("NJ76"); combos.add("NJ77"); combos.add("NJ78");
        combos.add("NJ79"); combos.add("NJ80"); combos.add("NJ81"); combos.add("NJ82"); combos.add("NJ83");
        combos.add("NJ84"); combos.add("NJ85"); combos.add("NJ86"); combos.add("NJ87"); combos.add("NJ88");
        combos.add("NJ89"); combos.add("NM87"); combos.add("NM88"); combos.add("NV88"); combos.add("NV89");
        combos.add("NY10"); combos.add("NY11"); combos.add("NY12"); combos.add("NY13"); combos.add("NY14");
        combos.add("NY50"); combos.add("NY54"); combos.add("NY63"); combos.add("OH43"); combos.add("OH44");
        combos.add("OH45"); combos.add("OK73"); combos.add("OK74"); combos.add("OR97"); combos.add("PA15");
        combos.add("PA16"); combos.add("PA17"); combos.add("PA18"); combos.add("PA19"); combos.add("PR60");
        combos.add("PR61"); combos.add("PR62"); combos.add("PR63"); combos.add("PR64"); combos.add("PR65");
        combos.add("PR66"); combos.add("PR67"); combos.add("PR68"); combos.add("PR69"); combos.add("PR70");
        combos.add("PR71"); combos.add("PR72"); combos.add("PR73"); combos.add("PR74"); combos.add("PR75");
        combos.add("PR76"); combos.add("PR77"); combos.add("PR78"); combos.add("PR79"); combos.add("PR90");
        combos.add("PR91"); combos.add("PR92"); combos.add("PR93"); combos.add("PR94"); combos.add("PR95");
        combos.add("PR96"); combos.add("PR97"); combos.add("PR98"); combos.add("PW96"); combos.add("RI28");
        combos.add("RI29"); combos.add("SC29"); combos.add("SD57"); combos.add("TN37"); combos.add("TN38");
        combos.add("TX73"); combos.add("TX75"); combos.add("TX76"); combos.add("TX77"); combos.add("TX78");
        combos.add("TX79"); combos.add("TX88"); combos.add("UT84"); combos.add("VA20"); combos.add("VA22");
        combos.add("VA23"); combos.add("VA24"); combos.add("VI80"); combos.add("VI82"); combos.add("VI83");
        combos.add("VI84"); combos.add("VI85"); combos.add("VT50"); combos.add("VT51"); combos.add("VT52");
        combos.add("VT53"); combos.add("VT54"); combos.add("VT56"); combos.add("VT57"); combos.add("VT58");
        combos.add("VT59"); combos.add("WA98"); combos.add("WA99"); combos.add("WI53"); combos.add("WI54");
        combos.add("WV24"); combos.add("WV25"); combos.add("WV26"); combos.add("WY82"); combos.add("WY83");
        return Collections.unmodifiableSet(combos);
    }
}
