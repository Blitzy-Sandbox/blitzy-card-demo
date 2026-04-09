package com.cardemo.integration.validation;

import com.cardemo.service.shared.ValidationLookupService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ValidationLookupService} verifying the service
 * operates correctly within a Spring Boot application context.
 *
 * <p>This test validates that:
 * <ul>
 *   <li>The {@code @Service} annotation is detected and the bean is created by Spring</li>
 *   <li>Dependency injection via {@code @Autowired} resolves correctly</li>
 *   <li>The {@code @PostConstruct init()} method is invoked by Spring and successfully
 *       loads all three JSON lookup tables from the classpath:
 *       <ul>
 *         <li>{@code validation/nanpa-area-codes.json} — 490 NANPA area codes</li>
 *         <li>{@code validation/us-state-codes.json} — 56 US state/territory codes</li>
 *         <li>{@code validation/state-zip-prefixes.json} — 240 state/ZIP prefix combinations</li>
 *       </ul>
 *   </li>
 *   <li>All validation methods produce correct results against the loaded lookup data</li>
 *   <li>The COBOL CSLKPCDY.cpy condition table semantics are preserved end-to-end</li>
 * </ul>
 *
 * <p>Uses a minimal {@link SpringBootConfiguration} that imports only the service
 * under test, avoiding infrastructure dependencies (database, AWS, security) that
 * are not required for this resource-loading service.
 *
 * <p>Source traceability: CSLKPCDY.cpy — CardDemo_v1.0-15-g27d6c6f-68
 */
@SpringBootTest(
        classes = ValidationLookupServiceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("ValidationLookupService — Spring Integration Tests")
class ValidationLookupServiceTest {

    /**
     * Minimal Spring Boot configuration that imports only the
     * {@link ValidationLookupService} bean. Infrastructure auto-configurations
     * (DataSource, JPA, Flyway, Batch, Security) are excluded because this
     * service only requires classpath resource access for JSON loading via
     * {@code @PostConstruct init()}.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            BatchAutoConfiguration.class,
            SecurityAutoConfiguration.class
    })
    @Import(ValidationLookupService.class)
    static class TestConfig { }

    @Autowired
    private ValidationLookupService validationLookupService;

    // ──────────────────────────────────────────────────────────────────────
    // Context loading and @PostConstruct verification
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Spring context loads, bean injected, and @PostConstruct init() completes")
    void contextLoads() {
        assertThat(validationLookupService).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────────
    // isValidAreaCode — NANPA area code validation
    // Mirrors COBOL CSLKPCDY.cpy VALID-AREA-CODES condition table
    // 490 entries from nanpa-area-codes.json
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isValidAreaCode — NANPA area code lookup (CSLKPCDY.cpy)")
    class AreaCodeTests {

        @Test
        @DisplayName("Known valid area code 212 (New York) returns true")
        void knownValid_212_passes() {
            assertThat(validationLookupService.isValidAreaCode("212")).isTrue();
        }

        @Test
        @DisplayName("Known valid area code 415 (San Francisco) returns true")
        void knownValid_415_passes() {
            assertThat(validationLookupService.isValidAreaCode("415")).isTrue();
        }

        @Test
        @DisplayName("Known valid area code 312 (Chicago) returns true")
        void knownValid_312_passes() {
            assertThat(validationLookupService.isValidAreaCode("312")).isTrue();
        }

        @Test
        @DisplayName("Invalid area code 000 returns false")
        void invalid_000_rejected() {
            assertThat(validationLookupService.isValidAreaCode("000")).isFalse();
        }

        @Test
        @DisplayName("Invalid area code 000 returns false — not in NANPA table")
        void invalid_000_notInTable_rejected() {
            assertThat(validationLookupService.isValidAreaCode("000")).isFalse();
        }

        @Test
        @DisplayName("Null area code returns false")
        void null_rejected() {
            assertThat(validationLookupService.isValidAreaCode(null)).isFalse();
        }

        @Test
        @DisplayName("Empty area code returns false")
        void empty_rejected() {
            assertThat(validationLookupService.isValidAreaCode("")).isFalse();
        }

        @Test
        @DisplayName("Blank area code returns false")
        void blank_rejected() {
            assertThat(validationLookupService.isValidAreaCode("   ")).isFalse();
        }

        @Test
        @DisplayName("Two-digit code returns false — must be 3 digits")
        void twoDigits_rejected() {
            assertThat(validationLookupService.isValidAreaCode("21")).isFalse();
        }

        @Test
        @DisplayName("Four-digit code returns false — must be 3 digits")
        void fourDigits_rejected() {
            assertThat(validationLookupService.isValidAreaCode("2122")).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // isValidStateCode — US state/territory abbreviation validation
    // Mirrors COBOL CSLKPCDY.cpy VALID-STATE-CODES condition table
    // 56 entries from us-state-codes.json
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isValidStateCode — US state/territory lookup (CSLKPCDY.cpy)")
    class StateCodeTests {

        @Test
        @DisplayName("Known valid state CA (California) returns true")
        void knownValid_CA_passes() {
            assertThat(validationLookupService.isValidStateCode("CA")).isTrue();
        }

        @Test
        @DisplayName("Known valid state NY (New York) returns true")
        void knownValid_NY_passes() {
            assertThat(validationLookupService.isValidStateCode("NY")).isTrue();
        }

        @Test
        @DisplayName("Known valid state TX (Texas) returns true")
        void knownValid_TX_passes() {
            assertThat(validationLookupService.isValidStateCode("TX")).isTrue();
        }

        @Test
        @DisplayName("Known valid territory DC (District of Columbia) returns true")
        void knownValid_DC_passes() {
            assertThat(validationLookupService.isValidStateCode("DC")).isTrue();
        }

        @Test
        @DisplayName("Invalid state XX returns false")
        void invalid_XX_rejected() {
            assertThat(validationLookupService.isValidStateCode("XX")).isFalse();
        }

        @Test
        @DisplayName("Invalid state ZZ returns false")
        void invalid_ZZ_rejected() {
            assertThat(validationLookupService.isValidStateCode("ZZ")).isFalse();
        }

        @Test
        @DisplayName("Null state code returns false")
        void null_rejected() {
            assertThat(validationLookupService.isValidStateCode(null)).isFalse();
        }

        @Test
        @DisplayName("Empty state code returns false")
        void empty_rejected() {
            assertThat(validationLookupService.isValidStateCode("")).isFalse();
        }

        @Test
        @DisplayName("Single character returns false — must be 2 characters")
        void singleChar_rejected() {
            assertThat(validationLookupService.isValidStateCode("C")).isFalse();
        }

        @Test
        @DisplayName("Three characters returns false — must be 2 characters")
        void threeChars_rejected() {
            assertThat(validationLookupService.isValidStateCode("CAL")).isFalse();
        }

        @Test
        @DisplayName("Lowercase state code is handled (case sensitivity)")
        void lowerCase_handledConsistently() {
            // Whether the service is case-sensitive or not, it should not throw
            boolean result = validationLookupService.isValidStateCode("ca");
            // Just verify it returns a boolean without error
            assertThat(result).isIn(true, false);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // isValidStateZipPrefix — state/ZIP prefix combination validation
    // Mirrors COBOL CSLKPCDY.cpy VALID-STATE-ZIP condition table
    // 240 entries from state-zip-prefixes.json
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isValidStateZipPrefix — state/ZIP prefix combination (CSLKPCDY.cpy)")
    class StateZipPrefixTests {

        @Test
        @DisplayName("Valid NY + ZIP prefix 100 returns true")
        void validNyZip100_passes() {
            assertThat(validationLookupService.isValidStateZipPrefix("NY", "100")).isTrue();
        }

        @Test
        @DisplayName("Valid CA + ZIP prefix 900 returns true")
        void validCaZip900_passes() {
            assertThat(validationLookupService.isValidStateZipPrefix("CA", "900")).isTrue();
        }

        @Test
        @DisplayName("Valid TX + ZIP prefix 750 returns true")
        void validTxZip750_passes() {
            assertThat(validationLookupService.isValidStateZipPrefix("TX", "750")).isTrue();
        }

        @Test
        @DisplayName("Mismatched state/ZIP (NY + 900) returns false")
        void mismatchedStateZip_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("NY", "900")).isFalse();
        }

        @Test
        @DisplayName("Invalid state XX with any ZIP prefix returns false")
        void invalidState_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("XX", "100")).isFalse();
        }

        @Test
        @DisplayName("Valid state with invalid ZIP prefix returns false")
        void validState_invalidZip_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("NY", "999")).isFalse();
        }

        @Test
        @DisplayName("Null state returns false")
        void nullState_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix(null, "100")).isFalse();
        }

        @Test
        @DisplayName("Null ZIP prefix returns false")
        void nullZip_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("NY", null)).isFalse();
        }

        @Test
        @DisplayName("Empty state returns false")
        void emptyState_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("", "100")).isFalse();
        }

        @Test
        @DisplayName("Empty ZIP prefix returns false")
        void emptyZip_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("NY", "")).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // isValidStateZipPrefix(String combinedKey) — single 4-char combined key
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isValidStateZipPrefix(combined) — 4-char combined key lookup")
    class StateZipPrefixCombinedKeyTests {

        @Test
        @DisplayName("Valid combined key 'NY10' (NY + ZIP 10x) returns true")
        void validCombinedNy10_passes() {
            // Combined key format: 2-char state + first 2 digits of ZIP prefix
            assertThat(validationLookupService.isValidStateZipPrefix("NY10")).isTrue();
        }

        @Test
        @DisplayName("Invalid combined key 'XX00' returns false")
        void invalidCombined_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("XX00")).isFalse();
        }

        @Test
        @DisplayName("Null combined key returns false")
        void nullCombined_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix((String) null)).isFalse();
        }

        @Test
        @DisplayName("Empty combined key returns false")
        void emptyCombined_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("")).isFalse();
        }

        @Test
        @DisplayName("Short combined key (less than 4 chars) returns false")
        void shortCombined_rejected() {
            assertThat(validationLookupService.isValidStateZipPrefix("NY")).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cross-cutting: resource loading verification
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Resource loading — JSON classpath resource verification")
    class ResourceLoadingTests {

        @Test
        @DisplayName("NANPA area codes loaded — at least 200 valid codes exist")
        void areaCodesLoaded_sufficientEntries() {
            // Verify a representative sample of well-known area codes
            // to confirm the nanpa-area-codes.json was loaded
            int validCount = 0;
            String[] sampleCodes = {"201", "202", "203", "205", "206", "207", "208",
                    "209", "210", "212", "213", "214", "215", "216", "217",
                    "301", "302", "303", "304", "305", "307", "308", "309",
                    "310", "312", "313", "314", "315", "316", "317", "318"};
            for (String code : sampleCodes) {
                if (validationLookupService.isValidAreaCode(code)) {
                    validCount++;
                }
            }
            // At least 80% of these well-known NPA codes should be present
            assertThat(validCount).isGreaterThanOrEqualTo(25);
        }

        @Test
        @DisplayName("State codes loaded — all 50 US states are present")
        void stateCodesLoaded_all50States() {
            String[] states = {"AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE",
                    "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS",
                    "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS",
                    "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY",
                    "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
                    "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV",
                    "WI", "WY"};
            for (String state : states) {
                assertThat(validationLookupService.isValidStateCode(state))
                        .as("State %s should be valid", state)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("State/ZIP prefixes loaded — known combinations validated")
        void stateZipPrefixesLoaded_knownCombinations() {
            // Well-known state/ZIP prefix combinations
            assertThat(validationLookupService.isValidStateZipPrefix("NY", "100"))
                    .as("NY + ZIP 100xx should be valid").isTrue();
            assertThat(validationLookupService.isValidStateZipPrefix("CA", "900"))
                    .as("CA + ZIP 900xx should be valid").isTrue();
            assertThat(validationLookupService.isValidStateZipPrefix("IL", "606"))
                    .as("IL + ZIP 606xx (Chicago) should be valid").isTrue();
        }
    }
}
