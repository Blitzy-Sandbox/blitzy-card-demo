package com.carddemo.unit.service;

import com.carddemo.service.ValidationLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure JUnit 5 unit test for {@link com.carddemo.service.ValidationLookupService}, the Java
 * realization of the COBOL lookup copybook {@code app/cpy/CSLKPCDY.cpy} (read-only @ SHA
 * {@code 27d6c6f}) referenced by AAP &sect;0.4.1.4.
 *
 * <p>The service mirrors three {@code 88}-level condition lists used by the account-update field
 * edits in {@code COACTUPC} ({@code 1260-EDIT-US-PHONE-NUM}, {@code 1270-EDIT-US-STATE-CD},
 * {@code 1280-EDIT-US-STATE-ZIP-CD}):</p>
 * <ul>
 *   <li>{@code VALID-GENERAL-PURP-CODE} &mdash; the general-purpose subset of NANPA area codes.
 *       Easy-recognizable / special codes (for example {@code 200}, {@code 211}, {@code 911}) are
 *       valid NANPA assignments yet are deliberately rejected by phone validation, so the service
 *       loads <em>only</em> the {@code validGeneralPurposeCodes} array.</li>
 *   <li>{@code VALID-US-STATE-CODE} &mdash; two-character US state codes.</li>
 *   <li>{@code VALID-US-STATE-ZIP-CD2-COMBO} &mdash; state code plus the leading two ZIP digits.</li>
 * </ul>
 *
 * <p>This is a genuine unit test: a <strong>real</strong> {@link ObjectMapper} parses the
 * <strong>real</strong> {@code validation/*.json} resources from the test classpath. There is no
 * Spring context, database, or AWS interaction. Because no container manages the bean, the
 * {@code @PostConstruct}-annotated loader does <strong>not</strong> auto-fire; the package-private
 * {@code load()} method (in {@code com.carddemo.service}, hence inaccessible from this test package)
 * is invoked explicitly through {@link ReflectionTestUtils#invokeMethod(Object, String, Object...)}.
 * The chosen lookup values were taken directly from the JSON resources so every assertion is
 * deterministic.</p>
 */
@DisplayName("ValidationLookupService - CSLKPCDY lookups (NANPA area codes, US state codes, state/ZIP combos) @ 27d6c6f")
class ValidationLookupServiceTest {

    /** Name of the package-private {@code @PostConstruct} loader on the SUT. */
    private static final String LOADER_METHOD = "load";

    private ValidationLookupService service;

    /**
     * Builds the service with a real Jackson mapper and triggers the classpath-resource load
     * manually, standing in for the Spring {@code @PostConstruct} callback that does not fire in a
     * plain unit test. A load failure surfaces here and fails every dependent test.
     */
    @BeforeEach
    void setUp() {
        service = new ValidationLookupService(new ObjectMapper());
        ReflectionTestUtils.invokeMethod(service, LOADER_METHOD);
    }

    @Test
    @DisplayName("loader reads all three validation/*.json resources without throwing")
    void loaderCompletesWithoutError() {
        ValidationLookupService freshService = new ValidationLookupService(new ObjectMapper());

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(freshService, LOADER_METHOD))
                .doesNotThrowAnyException();
    }

    // --- isValidAreaCode (VALID-GENERAL-PURP-CODE) --------------------------------------------

    @Test
    @DisplayName("isValidAreaCode: \"201\" is a loaded general-purpose code -> true")
    void areaCodeGeneralPurposeIsValid() {
        assertThat(service.isValidAreaCode("201")).isTrue();
    }

    @Test
    @DisplayName("isValidAreaCode: \"000\" is in neither NANPA list -> false")
    void areaCodeAbsentIsInvalid() {
        assertThat(service.isValidAreaCode("000")).isFalse();
    }

    @Test
    @DisplayName("isValidAreaCode: easy-recognizable NANPA codes 200/211/911 are not general-purpose -> false (COBOL parity)")
    void areaCodeEasyRecognizableRejected() {
        assertThat(service.isValidAreaCode("200")).isFalse();
        assertThat(service.isValidAreaCode("211")).isFalse();
        assertThat(service.isValidAreaCode("911")).isFalse();
    }

    @Test
    @DisplayName("isValidAreaCode: padded input is trimmed before lookup -> true")
    void areaCodeTrimsInput() {
        assertThat(service.isValidAreaCode("  201  ")).isTrue();
    }

    @Test
    @DisplayName("isValidAreaCode: null and blank inputs -> false (no exception)")
    void areaCodeNullAndBlank() {
        assertThat(service.isValidAreaCode(null)).isFalse();
        assertThat(service.isValidAreaCode("")).isFalse();
        assertThat(service.isValidAreaCode("   ")).isFalse();
    }

    // --- isValidStateCode (VALID-US-STATE-CODE) -----------------------------------------------

    @Test
    @DisplayName("isValidStateCode: present 2-letter states CA/NY/TX -> true")
    void stateCodeValid() {
        assertThat(service.isValidStateCode("CA")).isTrue();
        assertThat(service.isValidStateCode("NY")).isTrue();
        assertThat(service.isValidStateCode("TX")).isTrue();
    }

    @Test
    @DisplayName("isValidStateCode: lookup is case-insensitive and trims (\" ca \") -> true")
    void stateCodeCaseInsensitiveAndTrimmed() {
        assertThat(service.isValidStateCode(" ca ")).isTrue();
    }

    @Test
    @DisplayName("isValidStateCode: \"ZZ\" is not a state -> false")
    void stateCodeInvalid() {
        assertThat(service.isValidStateCode("ZZ")).isFalse();
    }

    @Test
    @DisplayName("isValidStateCode: null and blank inputs -> false")
    void stateCodeNullAndBlank() {
        assertThat(service.isValidStateCode(null)).isFalse();
        assertThat(service.isValidStateCode("")).isFalse();
    }

    // --- isValidStateZipCombo (VALID-US-STATE-ZIP-CD2-COMBO) -----------------------------------

    @Test
    @DisplayName("isValidStateZipCombo: (CA,90210)->CA90 and (NY,10001)->NY10 are valid combos -> true")
    void stateZipComboValid() {
        assertThat(service.isValidStateZipCombo("CA", "90210")).isTrue();
        assertThat(service.isValidStateZipCombo("NY", "10001")).isTrue();
    }

    @Test
    @DisplayName("isValidStateZipCombo: valid state with wrong ZIP prefix (CA,10001)->CA10 -> false")
    void stateZipComboMismatchedPrefix() {
        assertThat(service.isValidStateZipCombo("CA", "10001")).isFalse();
    }

    @Test
    @DisplayName("isValidStateZipCombo: only the leading two ZIP digits matter; ZIP+4 still matches (CA,90210-1234) -> true")
    void stateZipComboUsesLeadingTwoZipDigits() {
        assertThat(service.isValidStateZipCombo("CA", "90210-1234")).isTrue();
    }

    @Test
    @DisplayName("isValidStateZipCombo: null state, null zip, blank state, and too-short zip -> false")
    void stateZipComboNullBlankAndShort() {
        assertThat(service.isValidStateZipCombo(null, "90210")).isFalse();
        assertThat(service.isValidStateZipCombo("CA", null)).isFalse();
        assertThat(service.isValidStateZipCombo("", "90210")).isFalse();
        assertThat(service.isValidStateZipCombo("CA", "9")).isFalse();
    }
}
