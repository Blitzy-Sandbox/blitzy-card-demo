package com.cardemo.unit.validation;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.service.shared.ValidationLookupService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JVM behavioural-parity unit test for {@link ValidationLookupService}.
 *
 * <p>This test proves that the migrated Java reference-data lookup service reproduces
 * <em>exactly</em> the observable behaviour of the legacy COBOL lookup estate, the parity target
 * being the frozen AWS CardDemo sources at commit SHA {@code 27d6c6f}:</p>
 * <ul>
 *   <li>{@code app/cpy/CSLKPCDY.cpy} &mdash; the high-fan-in lookup-code copybook that centralised the
 *       level-{@code 88} {@code VALUES} sets {@code VALID-GENERAL-PURP-CODE} (the NANPA
 *       <em>general-purpose</em> area codes), {@code VALID-US-STATE-CODE} (the 56 state/territory
 *       codes) and {@code VALID-US-STATE-ZIP-CD2-COMBO} (the state&harr;ZIP-prefix combinations);</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; the canonical consumer whose edit paragraphs define the
 *       exact semantics reproduced here: {@code EDIT-AREA-CODE} (~L2298, tests
 *       {@code IF VALID-GENERAL-PURP-CODE}), {@code 1270-EDIT-US-STATE-CD} (~L2495, tests
 *       {@code IF VALID-US-STATE-CODE}) and {@code 1280-EDIT-US-STATE-ZIP-CD} (~L2537, builds
 *       {@code STRING state, zip(1:2) INTO US-STATE-AND-FIRST-ZIP2} then tests
 *       {@code IF VALID-US-STATE-ZIP-CD2-COMBO}).</li>
 * </ul>
 *
 * <p>The COBOL source is read-only reference and is <strong>never copied</strong> into this
 * repository (AAP &sect;0.7.2); only its behaviour &mdash; the same valid/invalid membership outcomes
 * &mdash; is asserted here. Every assertion encodes COBOL-identical membership with no relaxation and
 * no added strictness (AAP &sect;0.7.1, 100% behavioural-parity gate).</p>
 *
 * <h2>Decisive parity nuances locked by this test</h2>
 * <ol>
 *   <li><strong>410 general-purpose, not 490 phone.</strong> {@code COACTUPC EDIT-AREA-CODE} validates
 *       against {@code IF VALID-GENERAL-PURP-CODE} &mdash; the ~410-code general-purpose set &mdash;
 *       <em>not</em> the broader ~490-code {@code VALID-PHONE-AREA-CODE} set (which is dead for this
 *       path). The externalised {@code nanpa-area-codes.json} therefore carries exactly 410 codes and
 *       membership is asserted against that set, never 490.</li>
 *   <li><strong>Optional phone &rArr; blank is VALID; blank state is INVALID.</strong> The telephone
 *       area code is an optional input (the COBOL edit is skipped when blank), so {@code null}, empty
 *       and all-whitespace area codes are <em>valid</em>. A state code, by contrast, is a pure
 *       membership probe: a blank/{@code null} state simply is not a member and is <em>invalid</em>.</li>
 *   <li><strong>State&harr;ZIP key = state + first-two-of-ZIP.</strong> The key is built as the state
 *       code concatenated with the first two characters of the ZIP, mirroring the COBOL
 *       {@code STRING ... INTO US-STATE-AND-FIRST-ZIP2}.</li>
 * </ol>
 *
 * <h2>Why this is a plain JUnit&nbsp;5 test (no Spring)</h2>
 * <p>{@link ValidationLookupService} loads its three lookup tables in its no-argument constructor from
 * {@code classpath:validation/*.json}; those resources sit on the test classpath because Maven places
 * {@code src/main/resources} there. Instantiating the service is therefore a pure-JVM classpath read:
 * <em>no</em> {@code @SpringBootTest}, application context, database, AWS, Testcontainers, Mockito or
 * reflection is used. The independent JSON-size guard ({@code LoadedTableSizes}) re-reads the same
 * resources to prove the externalised tables are not truncated, without touching the service's private
 * fields (AAP &sect;0.7.8 unsafe-code audit forbids reflection).</p>
 *
 * @see ValidationLookupService#isValidAreaCode(String)
 * @see ValidationLookupService#isValidStateCode(String)
 * @see ValidationLookupService#isValidStateZip(String, String)
 */
class ValidationLookupServiceTest {

    /** Classpath location of the externalised NANPA general-purpose area-code array (410 codes). */
    private static final String AREA_CODES_RESOURCE = "/validation/nanpa-area-codes.json";

    /** Classpath location of the externalised US state-code array (56 codes). */
    private static final String STATE_CODES_RESOURCE = "/validation/us-state-codes.json";

    /** Classpath location of the externalised state&harr;ZIP-prefix object (62 keys, 240 prefixes). */
    private static final String STATE_ZIP_RESOURCE = "/validation/state-zip-prefixes.json";

    /**
     * The system under test. The service's no-argument constructor loads {@code validation/*.json}
     * from the (test) classpath exactly once, so a direct {@code new} is sufficient &mdash; no Spring
     * container is required to initialise it.
     */
    private final ValidationLookupService service = new ValidationLookupService();

    /** Shared Jackson mapper for the JSON-direct truncation guard (mirrors the service's own reader). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------------------------
    // JSON-direct read helpers for the LoadedTableSizes guard. These re-read the SAME externalised
    // resources the service consumes (a pure-JVM classpath read), proving completeness without
    // reflection on the service's private final collections.
    // -------------------------------------------------------------------------------------------

    /**
     * Reads a classpath JSON array of strings, failing clearly if the resource is missing.
     *
     * @param resourcePath the absolute classpath location (leading {@code /}) of the JSON array
     * @return the deserialized list, preserving order and duplicates as stored
     * @throws IOException if the resource cannot be parsed
     */
    private static List<String> readJsonArray(final String resourcePath) throws IOException {
        try (InputStream in = ValidationLookupServiceTest.class.getResourceAsStream(resourcePath)) {
            assertThat(in).as("classpath resource %s must exist", resourcePath).isNotNull();
            return MAPPER.readValue(in, new TypeReference<List<String>>() { });
        }
    }

    /**
     * Reads a classpath JSON object of {@code state -> [prefix, ...]}, failing clearly if missing.
     *
     * @param resourcePath the absolute classpath location (leading {@code /}) of the JSON object
     * @return the deserialized map of state code to its list of ZIP prefixes
     * @throws IOException if the resource cannot be parsed
     */
    private static Map<String, List<String>> readJsonObject(final String resourcePath)
            throws IOException {
        try (InputStream in = ValidationLookupServiceTest.class.getResourceAsStream(resourcePath)) {
            assertThat(in).as("classpath resource %s must exist", resourcePath).isNotNull();
            return MAPPER.readValue(in, new TypeReference<Map<String, List<String>>>() { });
        }
    }

    // ===========================================================================================
    // isValidAreaCode — COACTUPC EDIT-AREA-CODE (~L2298): the optional telephone area code.
    // The COBOL trimmed WS-EDIT-US-PHONE-NUMA and tested IF VALID-GENERAL-PURP-CODE; the edit is
    // SKIPPED entirely when the field is blank, so a blank/absent value is treated as VALID.
    // ===========================================================================================

    @Nested
    @DisplayName("isValidAreaCode — optional phone, general-purpose set (COACTUPC EDIT-AREA-CODE)")
    class IsValidAreaCode {

        @Test
        @DisplayName("null area code is valid — phone is optional (edit skipped when absent)")
        void nullAreaCodeIsValid() {
            assertThat(service.isValidAreaCode(null)).isTrue();
        }

        @ParameterizedTest(name = "isValidAreaCode(\"{0}\") is blank -> valid (phone optional)")
        @ValueSource(strings = {"", "   "})
        @DisplayName("Empty and all-whitespace area codes are valid — phone is optional")
        void blankAreaCodeIsValid(String areaCode) {
            assertThat(service.isValidAreaCode(areaCode)).isTrue();
        }

        @ParameterizedTest(name = "isValidAreaCode(\"{0}\") -> valid (member of 410-code set)")
        @ValueSource(strings = {"201", "989", "212", "202", "213", "310"})
        @DisplayName("Members of the 410-code general-purpose set are valid (incl. first 201 / last 989)")
        void generalPurposeCodesAreValid(String areaCode) {
            assertThat(service.isValidAreaCode(areaCode)).isTrue();
        }

        @ParameterizedTest(name = "isValidAreaCode(\"{0}\") -> invalid (not in general-purpose set)")
        @ValueSource(strings = {"000", "211", "911", "800", "999", "111"})
        @DisplayName("Non-members are invalid: N11 service (211/911), toll-free (800), and 000/999/111")
        void nonGeneralPurposeCodesAreInvalid(String areaCode) {
            assertThat(service.isValidAreaCode(areaCode)).isFalse();
        }
    }

    // ===========================================================================================
    // isValidStateCode — COACTUPC 1270-EDIT-US-STATE-CD (~L2495): pure 88-level membership against
    // VALID-US-STATE-CODE. The COBOL performed no trim and no case folding (the table is uppercase),
    // so the check is exact; a blank/null state simply is not a member and is therefore INVALID.
    // ===========================================================================================

    @Nested
    @DisplayName("isValidStateCode — pure membership, blank invalid (COACTUPC 1270-EDIT-US-STATE-CD)")
    class IsValidStateCode {

        @ParameterizedTest(name = "isValidStateCode(\"{0}\") -> valid (member of 56-code set)")
        @ValueSource(strings = {"CA", "NY", "TX", "DC", "AS", "GU", "MP", "PR", "VI"})
        @DisplayName("States, DC and the five territories (AS/GU/MP/PR/VI) are valid members")
        void knownStateCodesAreValid(String stateCode) {
            assertThat(service.isValidStateCode(stateCode)).isTrue();
        }

        @ParameterizedTest(name = "isValidStateCode(\"{0}\") -> invalid (not a member)")
        @ValueSource(strings = {"ZZ", "XX"})
        @DisplayName("Unknown two-letter codes are invalid")
        void unknownStateCodesAreInvalid(String stateCode) {
            assertThat(service.isValidStateCode(stateCode)).isFalse();
        }

        @Test
        @DisplayName("null state code is invalid — pure membership (cannot match any of the 56)")
        void nullStateCodeIsInvalid() {
            assertThat(service.isValidStateCode(null)).isFalse();
        }

        @Test
        @DisplayName("empty state code is invalid — pure membership (blank is not a member)")
        void emptyStateCodeIsInvalid() {
            assertThat(service.isValidStateCode("")).isFalse();
        }

        @Test
        @DisplayName("lowercase \"ca\" is invalid — COBOL table is uppercase, comparison is exact (no case folding)")
        void lowercaseStateCodeIsInvalid() {
            // Parity exact-match expectation: VALID-US-STATE-CODE stores uppercase codes only and the
            // COBOL membership test is byte-exact, so the lowercase form must NOT match.
            assertThat(service.isValidStateCode("ca")).isFalse();
        }
    }

    // ===========================================================================================
    // isValidStateZip — COACTUPC 1280-EDIT-US-STATE-ZIP-CD (~L2537): the COBOL built a 4-byte key
    // with STRING state, zip(1:2) DELIMITED BY SIZE INTO US-STATE-AND-FIRST-ZIP2 and tested
    // IF VALID-US-STATE-ZIP-CD2-COMBO. The key is reproduced as state + first-two-of-ZIP; if the key
    // cannot be formed (blank/null state, or ZIP shorter than two characters) the result is INVALID.
    // ===========================================================================================

    @Nested
    @DisplayName("isValidStateZip — state + first-2-of-ZIP key (COACTUPC 1280-EDIT-US-STATE-ZIP-CD)")
    class IsValidStateZip {

        @ParameterizedTest(name = "isValidStateZip(\"{0}\",\"{1}\") -> valid combination")
        @CsvSource({
            "CA,90210",
            "CA,91234",
            "CA,96150",
            "AK,99501",
            "WY,82001",
            "WY,83414"
        })
        @DisplayName("Valid state+ZIP-prefix combinations (CA 90/91/96, AK 99, WY 82/83)")
        void validStateZipCombinationsAreValid(String stateCode, String zipCode) {
            assertThat(service.isValidStateZip(stateCode, zipCode)).isTrue();
        }

        @ParameterizedTest(name = "isValidStateZip(\"{0}\",\"{1}\") -> invalid combination")
        @CsvSource({
            "AK,90210",
            "CA,00001",
            "ZZ,90210"
        })
        @DisplayName("Wrong prefix-for-state (AK 90, CA 00) and unknown state (ZZ) are invalid")
        void wrongOrUnknownCombinationsAreInvalid(String stateCode, String zipCode) {
            assertThat(service.isValidStateZip(stateCode, zipCode)).isFalse();
        }

        @Test
        @DisplayName("ZIP shorter than two characters is invalid — the 4-byte key cannot be formed")
        void zipShorterThanTwoIsInvalid() {
            assertThat(service.isValidStateZip("CA", "9")).isFalse();
        }

        @Test
        @DisplayName("empty ZIP is invalid — the 4-byte key cannot be formed")
        void emptyZipIsInvalid() {
            assertThat(service.isValidStateZip("CA", "")).isFalse();
        }

        @Test
        @DisplayName("null ZIP is invalid — the 4-byte key cannot be formed")
        void nullZipIsInvalid() {
            assertThat(service.isValidStateZip("CA", null)).isFalse();
        }

        @Test
        @DisplayName("null state is invalid — the 4-byte key cannot be formed")
        void nullStateIsInvalid() {
            assertThat(service.isValidStateZip(null, "90210")).isFalse();
        }

        @Test
        @DisplayName("blank state is invalid — the 4-byte key cannot be formed")
        void blankStateIsInvalid() {
            assertThat(service.isValidStateZip("", "90210")).isFalse();
        }
    }

    // ===========================================================================================
    // LoadedTableSizes — guard against truncated externalised tables (folder-spec requirement).
    // Re-reads the SAME classpath JSON the service consumes and asserts the exact, COBOL-derived
    // cardinalities: VALID-GENERAL-PURP-CODE = 410, VALID-US-STATE-CODE = 56, and
    // VALID-US-STATE-ZIP-CD2-COMBO = 62 distinct state keys / 240 total prefixes. First/last and
    // representative-membership spot-checks confirm the full set loaded. No reflection on the
    // service's private fields is used (AAP §0.7.8 unsafe-code audit).
    // ===========================================================================================

    @Nested
    @DisplayName("LoadedTableSizes — externalised tables are complete, not truncated (410 / 56 / 62)")
    class LoadedTableSizes {

        @Test
        @DisplayName("nanpa-area-codes.json holds the full 410-code set (first 201, last 989)")
        void areaCodeTableIsComplete() throws IOException {
            List<String> codes = readJsonArray(AREA_CODES_RESOURCE);
            assertThat(codes).hasSize(410);
            assertThat(codes).first().isEqualTo("201");
            assertThat(codes).last().isEqualTo("989");
        }

        @Test
        @DisplayName("us-state-codes.json holds the full 56-code set (incl. AL, DC, VI, CA, NY)")
        void stateCodeTableIsComplete() throws IOException {
            List<String> states = readJsonArray(STATE_CODES_RESOURCE);
            assertThat(states).hasSize(56);
            assertThat(states).contains("AL", "DC", "VI", "CA", "NY");
        }

        @Test
        @DisplayName("state-zip-prefixes.json holds 62 state keys and 240 total prefixes (CA incl. 90, 96)")
        void stateZipTableIsComplete() throws IOException {
            Map<String, List<String>> stateZip = readJsonObject(STATE_ZIP_RESOURCE);
            assertThat(stateZip).hasSize(62);
            assertThat(stateZip.values().stream().mapToInt(List::size).sum()).isEqualTo(240);
            assertThat(stateZip.get("CA")).contains("90", "96");
        }
    }
}
