package com.carddemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.service.shared.ValidationLookupService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Unit tests for {@link ValidationLookupService}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * the COBOL copybook {@code app/cpy/CSLKPCDY.cpy} encodes the NANPA area-code, US state-code
 * and state+ZIP-prefix {@code 88}-level condition tables consumed by {@code COACTUPC}. Those
 * tables are externalised to {@code src/main/resources/validation/*.json} and loaded into
 * immutable sets at construction. These tests use a real {@link ObjectMapper} against the real
 * production classpath resources to prove table fidelity (no expansion beyond the COBOL data)
 * and exercise the normalisation/combination logic, plus the fail-fast contract when a resource
 * cannot be read (AAP sections 0.7/0.8).</p>
 */
@DisplayName("ValidationLookupService - CSLKPCDY NANPA / state / ZIP lookup tables")
class ValidationLookupServiceTest {

    private ValidationLookupService service;

    @BeforeEach
    void setUp() {
        service = new ValidationLookupService(new ObjectMapper());
    }

    @Test
    @DisplayName("valid NANPA area codes accepted; reserved/service codes rejected")
    void areaCodeLookup() {
        assertThat(service.isValidAreaCode("201")).isTrue();
        assertThat(service.isValidAreaCode("989")).isTrue();
        assertThat(service.isValidAreaCode("800")).isFalse();
        assertThat(service.isValidAreaCode("911")).isFalse();
        assertThat(service.isValidAreaCode("000")).isFalse();
        assertThat(service.isValidAreaCode(null)).isFalse();
        assertThat(service.isValidAreaCode("")).isFalse();
    }

    @Test
    @DisplayName("area-code lookup trims surrounding whitespace")
    void areaCodeNormalisation() {
        assertThat(service.isValidAreaCode(" 201 ")).isTrue();
    }

    @Test
    @DisplayName("US state/territory codes accepted; FM is not a state code")
    void stateCodeLookup() {
        assertThat(service.isValidStateCode("CA")).isTrue();
        assertThat(service.isValidStateCode("NY")).isTrue();
        assertThat(service.isValidStateCode("DC")).isTrue();
        assertThat(service.isValidStateCode("PR")).isTrue();
        assertThat(service.isValidStateCode("VI")).isTrue();
        assertThat(service.isValidStateCode("FM")).isFalse();
        assertThat(service.isValidStateCode("ZZ")).isFalse();
        assertThat(service.isValidStateCode(null)).isFalse();
        assertThat(service.isValidStateCode("")).isFalse();
    }

    @Test
    @DisplayName("state-code lookup is case-insensitive")
    void stateCodeCaseInsensitive() {
        assertThat(service.isValidStateCode("ca")).isTrue();
    }

    @Test
    @DisplayName("isValidStateZip accepts matching state + 2-digit ZIP prefix")
    void stateZipValidCombinations() {
        assertThat(service.isValidStateZip("CA", "90210")).isTrue();
        assertThat(service.isValidStateZip("AK", "99501")).isTrue();
        assertThat(service.isValidStateZip("ca", "90210")).isTrue();
    }

    @Test
    @DisplayName("isValidStateZip rejects mismatched prefix, short ZIP and nulls")
    void stateZipInvalidCombinations() {
        assertThat(service.isValidStateZip("CA", "99999")).isFalse();
        assertThat(service.isValidStateZip("CA", "9")).isFalse();
        assertThat(service.isValidStateZip(null, "90210")).isFalse();
        assertThat(service.isValidStateZip("CA", null)).isFalse();
        assertThat(service.isValidStateZip("", "90210")).isFalse();
    }

    @Test
    @DisplayName("parity nuance: FM is not a state code yet FM+96 ZIP prefix is valid")
    void freelyAssociatedStateZipParity() {
        assertThat(service.isValidStateCode("FM")).isFalse();
        assertThat(service.isValidStateZip("FM", "96941")).isTrue();
    }

    @Test
    @DisplayName("isValidStateZipCombo matches the raw combined token, case-insensitive")
    void stateZipComboLookup() {
        assertThat(service.isValidStateZipCombo("CA90")).isTrue();
        assertThat(service.isValidStateZipCombo("ca90")).isTrue();
        assertThat(service.isValidStateZipCombo("AA34")).isTrue();
        assertThat(service.isValidStateZipCombo("WY83")).isTrue();
        assertThat(service.isValidStateZipCombo("ZZ99")).isFalse();
        assertThat(service.isValidStateZipCombo(null)).isFalse();
        assertThat(service.isValidStateZipCombo("")).isFalse();
    }

    @Test
    @DisplayName("constructor fails fast with IllegalStateException when a resource cannot be read")
    void constructorFailsFastOnIoError() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.readValue(any(InputStream.class), ArgumentMatchers.<TypeReference<List<String>>>any()))
                .thenThrow(new IOException("boom"));
        assertThatThrownBy(() -> new ValidationLookupService(failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nanpa-area-codes.json");
    }
}
