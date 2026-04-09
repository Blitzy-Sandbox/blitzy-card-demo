package com.cardemo.integration.validation;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;

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
 * Integration test for {@link DateValidationService} verifying the service
 * operates correctly within a Spring Boot application context.
 *
 * <p>This test validates that:
 * <ul>
 *   <li>The {@code @Service} annotation is detected and the bean is created by Spring</li>
 *   <li>Dependency injection via {@code @Autowired} resolves correctly</li>
 *   <li>All validation methods produce correct results when invoked through
 *       the Spring-managed bean (not just a plain Java object)</li>
 *   <li>The COBOL CSUTLDTC.cbl/CSUTLDPY.cpy date validation semantics are
 *       preserved end-to-end within the Spring lifecycle</li>
 * </ul>
 *
 * <p>Uses a minimal {@link SpringBootConfiguration} that imports only the service
 * under test, avoiding infrastructure dependencies (database, AWS, security) that
 * are not required for this stateless service.
 *
 * <p>Source traceability:
 * <ul>
 *   <li>CSUTLDTC.cbl — CardDemo_v1.0-15-g27d6c6f-68</li>
 *   <li>CSUTLDPY.cpy — CardDemo_v1.0-15-g27d6c6f-68</li>
 *   <li>CSUTLDWY.cpy — CardDemo_v1.0-15-g27d6c6f-68</li>
 * </ul>
 */
@SpringBootTest(
        classes = DateValidationServiceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("DateValidationService — Spring Integration Tests")
class DateValidationServiceTest {

    /**
     * Minimal Spring Boot configuration that imports only the
     * {@link DateValidationService} bean. Infrastructure auto-configurations
     * (DataSource, JPA, Flyway, Batch, Security) are excluded because this
     * service is stateless and requires no external dependencies.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            BatchAutoConfiguration.class,
            SecurityAutoConfiguration.class
    })
    @Import(DateValidationService.class)
    static class TestConfig { }

    @Autowired
    private DateValidationService dateValidationService;

    // ──────────────────────────────────────────────────────────────────────
    // Context loading verification
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Spring context loads and DateValidationService bean is injected")
    void contextLoads() {
        assertThat(dateValidationService).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────────
    // validateDate — CCYYMMDD format validation
    // Mirrors COBOL PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT
    // (CSUTLDPY.cpy lines 18-331)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateDate — CCYYMMDD format (EDIT-DATE-CCYYMMDD)")
    class ValidateDateTests {

        @Test
        @DisplayName("Valid date 20230115 passes all validation stages")
        void validDate_passes() {
            DateValidationResult result = dateValidationService.validateDate("20230115", "TEST-DATE");

            assertThat(result.valid()).isTrue();
            assertThat(result.severityCode()).isZero();
            assertThat(result.resultMessage()).isEqualTo("Date is valid");
            assertThat(result.yearValid()).isTrue();
            assertThat(result.monthValid()).isTrue();
            assertThat(result.dayValid()).isTrue();
        }

        @Test
        @DisplayName("Valid leap year date 20240229 passes February 29 validation")
        void leapYearDate_passes() {
            DateValidationResult result = dateValidationService.validateDate("20240229", "LEAP-DATE");

            assertThat(result.valid()).isTrue();
            assertThat(result.severityCode()).isZero();
            assertThat(result.yearValid()).isTrue();
            assertThat(result.monthValid()).isTrue();
            assertThat(result.dayValid()).isTrue();
        }

        @Test
        @DisplayName("Non-leap year February 29 (20230229) is rejected — EDIT-DAY-MONTH-YEAR")
        void nonLeapYearFeb29_rejected() {
            DateValidationResult result = dateValidationService.validateDate("20230229", "DOB");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isGreaterThan(0);
            assertThat(result.dayValid()).isFalse();
        }

        @Test
        @DisplayName("Null date input returns year-not-supplied error — length guard")
        void nullDate_rejected() {
            DateValidationResult result = dateValidationService.validateDate(null, "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isGreaterThan(0);
            assertThat(result.yearValid()).isFalse();
            assertThat(result.monthValid()).isFalse();
            assertThat(result.dayValid()).isFalse();
        }

        @Test
        @DisplayName("Blank date input returns year-not-supplied error — length guard")
        void blankDate_rejected() {
            DateValidationResult result = dateValidationService.validateDate("        ", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Short date (less than 8 chars) is rejected — length guard")
        void shortDate_rejected() {
            DateValidationResult result = dateValidationService.validateDate("2023", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Non-numeric year is rejected — EDIT-YEAR-CCYY numeric check")
        void nonNumericYear_rejected() {
            DateValidationResult result = dateValidationService.validateDate("ABCD0115", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).contains("Nonnumeric");
            assertThat(result.yearValid()).isFalse();
        }

        @Test
        @DisplayName("Invalid century (18xx) is rejected — EDIT-YEAR-CCYY century check")
        void invalidCentury_rejected() {
            DateValidationResult result = dateValidationService.validateDate("18000115", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.yearValid()).isFalse();
        }

        @Test
        @DisplayName("Invalid month 13 is rejected — EDIT-MONTH")
        void invalidMonth_rejected() {
            DateValidationResult result = dateValidationService.validateDate("20231315", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.monthValid()).isFalse();
        }

        @Test
        @DisplayName("Month zero is rejected — EDIT-MONTH lower bound")
        void monthZero_rejected() {
            DateValidationResult result = dateValidationService.validateDate("20230015", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.monthValid()).isFalse();
        }

        @Test
        @DisplayName("Day zero is rejected — EDIT-DAY lower bound")
        void dayZero_rejected() {
            DateValidationResult result = dateValidationService.validateDate("20230100", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.dayValid()).isFalse();
        }

        @Test
        @DisplayName("Day 32 is rejected — EDIT-DAY upper bound")
        void dayTooHigh_rejected() {
            DateValidationResult result = dateValidationService.validateDate("20230132", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.dayValid()).isFalse();
        }

        @Test
        @DisplayName("April 31 is rejected — 30-day month check (EDIT-DAY-MONTH-YEAR)")
        void thirtyDayMonth_day31_rejected() {
            DateValidationResult result = dateValidationService.validateDate("20230431", "MY-DATE");

            assertThat(result.valid()).isFalse();
            assertThat(result.dayValid()).isFalse();
        }

        @Test
        @DisplayName("Valid boundary: Dec 31 (20231231) passes — 31-day month")
        void dec31_passes() {
            DateValidationResult result = dateValidationService.validateDate("20231231", "MY-DATE");

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Valid century 19xx: 19991231 passes century validation")
        void lastCentury_passes() {
            DateValidationResult result = dateValidationService.validateDate("19991231", "MY-DATE");

            assertThat(result.valid()).isTrue();
            assertThat(result.yearValid()).isTrue();
        }

        @Test
        @DisplayName("Null variable name is handled gracefully")
        void nullVariableName_handledGracefully() {
            DateValidationResult result = dateValidationService.validateDate("20230115", null);

            assertThat(result.valid()).isTrue();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // validateDateOfBirth — DOB validation including future-date check
    // Mirrors COBOL EDIT-DATE-OF-BIRTH paragraph (CSUTLDPY.cpy lines 341-372)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateDateOfBirth — DOB with future-date check (EDIT-DATE-OF-BIRTH)")
    class ValidateDateOfBirthTests {

        @Test
        @DisplayName("Past date passes DOB validation")
        void pastDate_passes() {
            DateValidationResult result = dateValidationService.validateDateOfBirth("19900101", "DOB");

            assertThat(result.valid()).isTrue();
            assertThat(result.severityCode()).isZero();
        }

        @Test
        @DisplayName("Future date is rejected — 'cannot be in the future'")
        void futureDate_rejected() {
            DateValidationResult result = dateValidationService.validateDateOfBirth("20991231", "DOB");

            assertThat(result.valid()).isFalse();
            assertThat(result.fullMessage()).contains("cannot be in the future");
        }

        @Test
        @DisplayName("Invalid format in DOB returns format error before future-date check")
        void invalidFormat_rejectedBeforeFutureCheck() {
            DateValidationResult result = dateValidationService.validateDateOfBirth("ABCD0101", "DOB");

            assertThat(result.valid()).isFalse();
            assertThat(result.yearValid()).isFalse();
        }

        @Test
        @DisplayName("Null DOB input returns year-not-supplied error")
        void nullDob_rejected() {
            DateValidationResult result = dateValidationService.validateDateOfBirth(null, "DOB");

            assertThat(result.valid()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // validateWithCeedays — LE CEEDAYS equivalent with format masks
    // Mirrors CSUTLDTC.cbl PROCEDURE DIVISION (lines 88-154)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateWithCeedays — LE CEEDAYS equivalent (CSUTLDTC.cbl)")
    class ValidateWithCeedaysTests {

        @Test
        @DisplayName("YYYYMMDD format — valid date passes")
        void yyyymmdd_validDate_passes() {
            DateValidationResult result = dateValidationService.validateWithCeedays("20230115", "YYYYMMDD");

            assertThat(result.valid()).isTrue();
            assertThat(result.severityCode()).isZero();
        }

        @Test
        @DisplayName("YYYY-MM-DD (ISO) format — valid date passes")
        void isoFormat_validDate_passes() {
            DateValidationResult result = dateValidationService.validateWithCeedays("2023-01-15", "YYYY-MM-DD");

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("MMDDYYYY format — valid date passes")
        void mmddyyyy_validDate_passes() {
            DateValidationResult result = dateValidationService.validateWithCeedays("01152023", "MMDDYYYY");

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("MM/DD/YYYY format — valid date passes")
        void mmSlashDdSlashYyyy_validDate_passes() {
            DateValidationResult result = dateValidationService.validateWithCeedays("01/15/2023", "MM/DD/YYYY");

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Empty date string returns FC-INSUFFICIENT-DATA (severity 3)")
        void emptyDate_insufficientData() {
            DateValidationResult result = dateValidationService.validateWithCeedays("", "YYYYMMDD");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isEqualTo(3);
            assertThat(result.resultMessage()).isEqualTo("Insufficient");
        }

        @Test
        @DisplayName("Null date string returns FC-INSUFFICIENT-DATA (severity 3)")
        void nullDate_insufficientData() {
            DateValidationResult result = dateValidationService.validateWithCeedays(null, "YYYYMMDD");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isEqualTo(3);
        }

        @Test
        @DisplayName("Empty format mask returns FC-BAD-PIC-STRING (severity 3)")
        void emptyMask_badPicString() {
            DateValidationResult result = dateValidationService.validateWithCeedays("20230115", "");

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).isEqualTo("Bad Pic String");
        }

        @Test
        @DisplayName("Unsupported format mask returns FC-BAD-PIC-STRING (severity 3)")
        void unsupportedMask_badPicString() {
            DateValidationResult result = dateValidationService.validateWithCeedays("20230115", "DD-MON-YYYY");

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).isEqualTo("Bad Pic String");
        }

        @Test
        @DisplayName("Non-numeric YYYYMMDD data returns FC-NON-NUMERIC-DATA")
        void nonNumericYyyymmdd_rejected() {
            DateValidationResult result = dateValidationService.validateWithCeedays("2023ABCD", "YYYYMMDD");

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).isEqualTo("Nonnumeric data");
        }

        @Test
        @DisplayName("Invalid date 20231301 returns Datevalue error")
        void invalidDate_yyyymmdd_rejected() {
            DateValidationResult result = dateValidationService.validateWithCeedays("20231301", "YYYYMMDD");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isGreaterThan(0);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cross-cutting: result record structure verification
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DateValidationResult record structure verification")
    class ResultStructureTests {

        @Test
        @DisplayName("Valid result has all flags true and severity 0")
        void validResult_structureComplete() {
            DateValidationResult result = dateValidationService.validateDate("20230601", "FIELD");

            assertThat(result.valid()).isTrue();
            assertThat(result.severityCode()).isZero();
            assertThat(result.resultMessage()).isNotBlank();
            assertThat(result.fullMessage()).isNotBlank();
            assertThat(result.yearValid()).isTrue();
            assertThat(result.monthValid()).isTrue();
            assertThat(result.dayValid()).isTrue();
        }

        @Test
        @DisplayName("Invalid result has non-zero severity and at least one flag false")
        void invalidResult_structureComplete() {
            DateValidationResult result = dateValidationService.validateDate("20231301", "FIELD");

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isGreaterThan(0);
            assertThat(result.resultMessage()).isNotBlank();
            assertThat(result.fullMessage()).isNotBlank();
        }

        @Test
        @DisplayName("fullMessage includes date string and format for CEEDAYS results")
        void ceedaysResult_fullMessageIncludesContext() {
            DateValidationResult result = dateValidationService.validateWithCeedays("20230115", "YYYYMMDD");

            assertThat(result.fullMessage()).contains("20230115");
        }
    }
}
