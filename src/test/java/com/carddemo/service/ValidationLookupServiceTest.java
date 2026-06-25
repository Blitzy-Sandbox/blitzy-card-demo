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
package com.carddemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ValidationLookupService}.
 *
 * <p>The lookup loader ({@link ValidationLookupService#load()}) is package-private
 * and reads its data from the {@code validation/*.json} classpath resources, so the
 * service is exercised here as a plain POJO with the loader invoked directly &mdash;
 * no Spring context is required.</p>
 *
 * <p>The most important behavior under test is COBOL phone-area-code parity: the
 * legacy {@code COACTUPC} account-update edit tests
 * {@code IF VALID-GENERAL-PURP-CODE}, so phone validation must accept only the
 * general-purpose NANPA codes and must reject easy-recognizable / special codes
 * (for example {@code 200}, {@code 211}, {@code 900}, {@code 911}) that the union
 * {@code VALID-PHONE-AREA-CODE} list would otherwise have allowed.</p>
 */
@DisplayName("ValidationLookupService — COBOL CSLKPCDY lookup parity")
class ValidationLookupServiceTest {

    private ValidationLookupService service;

    @BeforeEach
    void setUp() {
        service = new ValidationLookupService(new ObjectMapper());
        service.load();
    }

    @ParameterizedTest(name = "general-purpose code {0} is accepted")
    @ValueSource(strings = {"201", "202", "212", "213", "989"})
    @DisplayName("accepts general-purpose NANPA area codes (VALID-GENERAL-PURP-CODE)")
    void acceptsGeneralPurposeAreaCodes(String areaCode) {
        assertThat(service.isValidAreaCode(areaCode))
                .as("general-purpose code %s must be accepted", areaCode)
                .isTrue();
    }

    @ParameterizedTest(name = "easy-recognizable/special code {0} is rejected")
    @ValueSource(strings = {"200", "211", "311", "411", "555", "800", "900", "911"})
    @DisplayName("rejects easy-recognizable / special codes excluded from VALID-GENERAL-PURP-CODE")
    void rejectsEasyRecognizableAreaCodes(String areaCode) {
        assertThat(service.isValidAreaCode(areaCode))
                .as("easy-recognizable/special code %s must be rejected for COBOL parity", areaCode)
                .isFalse();
    }

    @Test
    @DisplayName("area-code validation trims padding and null-guards")
    void areaCodeTrimAndNullHandling() {
        assertThat(service.isValidAreaCode(" 201 ")).isTrue();
        assertThat(service.isValidAreaCode(null)).isFalse();
        assertThat(service.isValidAreaCode("")).isFalse();
        assertThat(service.isValidAreaCode("999")).isFalse();
    }

    @Test
    @DisplayName("validates two-character US state codes case-insensitively")
    void stateCodeValidation() {
        assertThat(service.isValidStateCode("NY")).isTrue();
        assertThat(service.isValidStateCode("CA")).isTrue();
        assertThat(service.isValidStateCode("ny")).isTrue();
        assertThat(service.isValidStateCode(" tx ")).isTrue();
        assertThat(service.isValidStateCode("ZZ")).isFalse();
        assertThat(service.isValidStateCode(null)).isFalse();
    }

    @Test
    @DisplayName("validates state + first-two-ZIP-digit combinations")
    void stateZipComboValidation() {
        assertThat(service.isValidStateZipCombo("NY", "10001")).isTrue();
        assertThat(service.isValidStateZipCombo("CA", "90210")).isTrue();
        assertThat(service.isValidStateZipCombo("NY", "99999")).isFalse();
        assertThat(service.isValidStateZipCombo("ZZ", "00000")).isFalse();
        assertThat(service.isValidStateZipCombo(null, "10001")).isFalse();
        assertThat(service.isValidStateZipCombo("NY", null)).isFalse();
        assertThat(service.isValidStateZipCombo("N", "1")).isFalse();
    }
}
