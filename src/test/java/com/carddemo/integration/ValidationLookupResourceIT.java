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
package com.carddemo.integration;

import com.carddemo.service.ValidationLookupService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real-Spring-context integration test for {@link ValidationLookupService}, the Java
 * realization of the COBOL lookup repository {@code app/cpy/CSLKPCDY.cpy}
 * (read-only @ SHA {@code 27d6c6f}, AAP &sect;0.4.1.4).
 *
 * <h2>What this IT proves (and the sibling unit test cannot)</h2>
 * The pure unit test ({@code com.carddemo.unit.service.ValidationLookupServiceTest})
 * constructs the service by hand and invokes the package-private {@code load()} method
 * reflectively, because no container fires {@code @PostConstruct}. This IT instead drives
 * the <strong>production</strong> initialization path: by extending
 * {@link AbstractIntegrationIT} it boots a full {@code @SpringBootTest} context, so Spring
 * constructs the {@code @Service} with the auto-configured Jackson {@link
 * com.fasterxml.jackson.databind.ObjectMapper ObjectMapper} and runs its
 * {@code @PostConstruct} loader. That loader reads the three classpath resources
 * ({@code validation/nanpa-area-codes.json}, {@code validation/us-state-codes.json},
 * {@code validation/state-zip-prefixes.json}) and <em>fails fast</em> if any is missing,
 * empty, or malformed. Therefore the mere fact that the context starts (asserted in
 * {@link #contextLoadsAndServiceInitialized()}) is itself the core guarantee that the
 * real {@code src/main/resources/validation/*.json} files are present on the classpath and
 * parse cleanly — a guarantee no mock or in-memory stub can give.
 *
 * <h2>No Mockito, no {@code @MockBean}</h2>
 * The whole point is to exercise the genuine bean against the genuine data, so the service
 * is obtained via plain field {@link Autowired autowiring}. Every expected value below was
 * verified directly against the generated JSON resources (which are extracted from the
 * legacy {@code 88}-level tables {@code VALID-PHONE-AREA-CODE} /
 * {@code VALID-US-STATE-CODE} / {@code VALID-US-STATE-ZIP-CD2-COMBO} in {@code CSLKPCDY}),
 * so the assertions are deterministic rather than aspirational.
 *
 * <h2>Scope</h2>
 * Although the {@link AbstractIntegrationIT} base provisions a real PostgreSQL and a real
 * LocalStack (shared singleton containers), this IT validates only the classpath JSON
 * lookup layer (utility parity for {@code CSLKPCDY}); it performs no database or AWS
 * interaction of its own.
 */
@DisplayName("ValidationLookupService classpath-resource IT — real @PostConstruct load of CSLKPCDY lookups @ 27d6c6f")
public class ValidationLookupResourceIT extends AbstractIntegrationIT {

    /**
     * The real, Spring-managed service under test. Field injection is used deliberately:
     * resolving this bean requires the {@code @PostConstruct} classpath load to have already
     * succeeded, so a non-null injection is the first half of the "real context loads the
     * JSON" proof.
     */
    @Autowired
    private ValidationLookupService validationLookupService;

    @Test
    @DisplayName("context starts and the service is initialized: the @PostConstruct fail-fast classpath load of all three validation/*.json resources succeeded")
    void contextLoadsAndServiceInitialized() {
        // Reaching this assertion means the Spring context started, which in turn means the
        // service's @PostConstruct load() read and parsed nanpa-area-codes.json,
        // us-state-codes.json and state-zip-prefixes.json from the classpath without
        // tripping its fail-fast guards (missing / empty / malformed resource).
        assertThat(validationLookupService)
                .as("ValidationLookupService must be autowired from the real Spring context")
                .isNotNull();
    }

    @Test
    @DisplayName("isValidAreaCode: real general-purpose NANPA codes (201, 212, 312, 415) are accepted, including padded input")
    void validNanpaAreaCodesAccepted() {
        // All four are present in the validGeneralPurposeCodes array of nanpa-area-codes.json.
        assertThat(validationLookupService.isValidAreaCode("201")).isTrue();
        assertThat(validationLookupService.isValidAreaCode("212")).isTrue();
        assertThat(validationLookupService.isValidAreaCode("312")).isTrue();
        assertThat(validationLookupService.isValidAreaCode("415")).isTrue();

        // The lookup trims surrounding whitespace before matching (COBOL fixed-width parity).
        assertThat(validationLookupService.isValidAreaCode("  212  ")).isTrue();
    }

    @Test
    @DisplayName("isValidAreaCode: absent and easy-recognizable NANPA codes are rejected (COBOL VALID-GENERAL-PURP-CODE parity)")
    void invalidAreaCodesRejected() {
        // 000 is in neither NANPA list.
        assertThat(validationLookupService.isValidAreaCode("000")).isFalse();

        // 999, 555 and 211 are valid NANPA easy-recognizable / special assignments that the
        // service deliberately does NOT load (only validGeneralPurposeCodes is consumed), so
        // phone validation rejects them exactly as the COBOL 88-level VALID-GENERAL-PURP-CODE
        // condition does in COACTUPC.
        assertThat(validationLookupService.isValidAreaCode("999")).isFalse();
        assertThat(validationLookupService.isValidAreaCode("555")).isFalse();
        assertThat(validationLookupService.isValidAreaCode("211")).isFalse();

        // Structurally impossible / non-numeric inputs are rejected without throwing.
        assertThat(validationLookupService.isValidAreaCode("1")).isFalse();
        assertThat(validationLookupService.isValidAreaCode("abc")).isFalse();
    }

    @Test
    @DisplayName("isValidStateCode: real USPS codes (CA, NY, TX, FL) are accepted; matching is case-insensitive and trims")
    void validUsStateCodesAccepted() {
        // Present in the validStateCodes array of us-state-codes.json.
        assertThat(validationLookupService.isValidStateCode("CA")).isTrue();
        assertThat(validationLookupService.isValidStateCode("NY")).isTrue();
        assertThat(validationLookupService.isValidStateCode("TX")).isTrue();
        assertThat(validationLookupService.isValidStateCode("FL")).isTrue();

        // The service upper-cases and trims, so a lower-case / padded valid code still matches.
        assertThat(validationLookupService.isValidStateCode("ny")).isTrue();
        assertThat(validationLookupService.isValidStateCode("  ca  ")).isTrue();
    }

    @Test
    @DisplayName("isValidStateCode: non-existent codes (ZZ, XX, blank, lower-case zz) are rejected")
    void invalidStateCodesRejected() {
        // Genuinely absent codes — false regardless of casing (the service normalizes to
        // upper-case, so "zz" -> "ZZ", which is still not a real state).
        assertThat(validationLookupService.isValidStateCode("ZZ")).isFalse();
        assertThat(validationLookupService.isValidStateCode("XX")).isFalse();
        assertThat(validationLookupService.isValidStateCode("zz")).isFalse();
        assertThat(validationLookupService.isValidStateCode("")).isFalse();
    }

    @Test
    @DisplayName("isValidStateZipCombo: correct state + ZIP-prefix pairs are accepted and deliberate mismatches are rejected")
    void validStateZipCombosAccepted_andMismatchRejected() {
        // Valid combinations: the first two ZIP digits form an SSNN entry present in
        // state-zip-prefixes.json (NY10, CA90, TX75, FL32). Only the leading two ZIP digits
        // matter, so full five-digit ZIPs resolve correctly.
        assertThat(validationLookupService.isValidStateZipCombo("NY", "10001")).isTrue();
        assertThat(validationLookupService.isValidStateZipCombo("CA", "90210")).isTrue();
        assertThat(validationLookupService.isValidStateZipCombo("TX", "75001")).isTrue();
        assertThat(validationLookupService.isValidStateZipCombo("FL", "32118")).isTrue();

        // Deliberate mismatches: a valid state paired with a ZIP prefix that belongs to a
        // different state yields an SSNN key (CA10, NY90) that is absent from the table.
        assertThat(validationLookupService.isValidStateZipCombo("CA", "10001")).isFalse();
        assertThat(validationLookupService.isValidStateZipCombo("NY", "90001")).isFalse();
    }

    @Test
    @DisplayName("null and blank inputs are null-safe: every lookup returns false and never throws")
    void nullAndBlankInputsAreSafe() {
        // The null-safety contract: each method returns false for null/blank/partial input
        // and must never throw. Wrapping the assertions in assertThatCode proves both
        // halves (no exception AND every result false) in a single, explicit guarantee.
        assertThatCode(() -> {
            assertThat(validationLookupService.isValidAreaCode(null)).isFalse();
            assertThat(validationLookupService.isValidAreaCode("")).isFalse();
            assertThat(validationLookupService.isValidAreaCode("   ")).isFalse();

            assertThat(validationLookupService.isValidStateCode(null)).isFalse();
            assertThat(validationLookupService.isValidStateCode("")).isFalse();

            assertThat(validationLookupService.isValidStateZipCombo(null, null)).isFalse();
            assertThat(validationLookupService.isValidStateZipCombo("CA", null)).isFalse();
            assertThat(validationLookupService.isValidStateZipCombo(null, "90210")).isFalse();
        }).doesNotThrowAnyException();
    }
}
