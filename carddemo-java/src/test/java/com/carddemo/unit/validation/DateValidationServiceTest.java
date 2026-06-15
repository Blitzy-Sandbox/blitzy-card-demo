package com.carddemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.shared.DateValidationService.DateValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateValidationService}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * the COBOL date-validation subprogram {@code app/cbl/CSUTLDTC.cbl} calls the Language
 * Environment {@code CEEDAYS} API and maps its feedback code to a severity and a 15-byte
 * result text. {@code DateValidationService} reproduces that behaviour with
 * {@code java.time.LocalDate} parsed under {@link java.time.format.ResolverStyle#STRICT} and a
 * proleptic-year ({@code 'u'}) pattern - no lenient calendar rollover. Each assertion below
 * references the service's public {@code MSG_*}/{@code SEVERITY_*}/{@code RESULT_*} constants
 * (rather than magic values) so the test pins the exact COBOL feedback-code parity (AAP
 * sections 0.7/0.8): valid dates yield {@code MSG_OK}; invalid calendar dates, wrong lengths,
 * non-numeric data, unsupported pattern strings, and the pre-Lillian range (before
 * 1582-10-15) each map to their distinct feedback code.</p>
 */
@DisplayName("DateValidationService - CSUTLDTC/CEEDAYS replaced by java.time STRICT validation")
class DateValidationServiceTest {

    private DateValidationService service;

    @BeforeEach
    void setUp() {
        service = new DateValidationService();
    }

    private void assertResult(DateValidationResult result, boolean valid, int severity, int messageNumber,
            String resultText, boolean acceptable) {
        assertThat(result.valid()).isEqualTo(valid);
        assertThat(result.severity()).isEqualTo(severity);
        assertThat(result.messageNumber()).isEqualTo(messageNumber);
        assertThat(result.resultText()).isEqualTo(resultText);
        assertThat(result.isAcceptable()).isEqualTo(acceptable);
    }

    @Test
    @DisplayName("valid leap day 2024-02-29 -> MSG_OK, severity VALID, acceptable")
    void validLeapDay() {
        assertResult(service.validateDate("2024-02-29", "YYYY-MM-DD"),
                true, DateValidationService.SEVERITY_VALID, DateValidationService.MSG_OK,
                DateValidationService.RESULT_VALID, true);
    }

    @Test
    @DisplayName("non-leap 2023-02-29 -> BAD_DATE_VALUE (STRICT, no rollover)")
    void nonLeapFebruary29() {
        assertResult(service.validateDate("2023-02-29", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_BAD_DATE_VALUE,
                DateValidationService.RESULT_BAD_DATE_VALUE, false);
    }

    @Test
    @DisplayName("impossible day 2024-02-30 -> BAD_DATE_VALUE")
    void impossibleDay() {
        assertResult(service.validateDate("2024-02-30", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_BAD_DATE_VALUE,
                DateValidationService.RESULT_BAD_DATE_VALUE, false);
    }

    @Test
    @DisplayName("invalid month 2024-13-01 -> BAD_DATE_VALUE")
    void invalidMonthThirteen() {
        assertResult(service.validateDate("2024-13-01", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_BAD_DATE_VALUE,
                DateValidationService.RESULT_BAD_DATE_VALUE, false);
    }

    @Test
    @DisplayName("zero month 2024-00-10 -> BAD_DATE_VALUE")
    void zeroMonth() {
        assertResult(service.validateDate("2024-00-10", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_BAD_DATE_VALUE,
                DateValidationService.RESULT_BAD_DATE_VALUE, false);
    }

    @Test
    @DisplayName("zero day 2024-02-00 -> BAD_DATE_VALUE")
    void zeroDay() {
        assertResult(service.validateDate("2024-02-00", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_BAD_DATE_VALUE,
                DateValidationService.RESULT_BAD_DATE_VALUE, false);
    }

    @Test
    @DisplayName("wrong length 2024-1-1 -> INSUFFICIENT_DATA")
    void wrongLengthInsufficient() {
        assertResult(service.validateDate("2024-1-1", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_INSUFFICIENT_DATA,
                DateValidationService.RESULT_INSUFFICIENT, false);
    }

    @Test
    @DisplayName("empty date -> INSUFFICIENT_DATA")
    void emptyDateInsufficient() {
        assertResult(service.validateDate("", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_INSUFFICIENT_DATA,
                DateValidationService.RESULT_INSUFFICIENT, false);
    }

    @Test
    @DisplayName("null date -> INSUFFICIENT_DATA")
    void nullDateInsufficient() {
        assertResult(service.validateDate(null, "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_INSUFFICIENT_DATA,
                DateValidationService.RESULT_INSUFFICIENT, false);
    }

    @Test
    @DisplayName("non-numeric year abcd-01-01 -> NON_NUMERIC_DATA")
    void nonNumericYear() {
        assertResult(service.validateDate("abcd-01-01", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_NON_NUMERIC_DATA,
                DateValidationService.RESULT_NON_NUMERIC, false);
    }

    @Test
    @DisplayName("compact format YYYYMMDD parses 20240229")
    void compactFormatValid() {
        assertResult(service.validateDate("20240229", "YYYYMMDD"),
                true, DateValidationService.SEVERITY_VALID, DateValidationService.MSG_OK,
                DateValidationService.RESULT_VALID, true);
    }

    @Test
    @DisplayName("slash format YYYY/MM/DD parses 2024/02/29")
    void slashFormatValid() {
        assertResult(service.validateDate("2024/02/29", "YYYY/MM/DD"),
                true, DateValidationService.SEVERITY_VALID, DateValidationService.MSG_OK,
                DateValidationService.RESULT_VALID, true);
    }

    @Test
    @DisplayName("unsupported format token -> BAD_PIC_STRING")
    void badPicString() {
        assertResult(service.validateDate("2024-02-29", "BADFMT"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_BAD_PIC_STRING,
                DateValidationService.RESULT_BAD_PIC, false);
    }

    @Test
    @DisplayName("null format -> BAD_PIC_STRING")
    void nullFormatBadPic() {
        assertResult(service.validateDate("2024-02-29", null),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_BAD_PIC_STRING,
                DateValidationService.RESULT_BAD_PIC, false);
    }

    @Test
    @DisplayName("pre-Lillian 1500-01-01 -> UNSUPPORTED_RANGE, invalid but acceptable")
    void preLillianUnsupportedRange() {
        assertResult(service.validateDate("1500-01-01", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_UNSUPPORTED_RANGE,
                DateValidationService.RESULT_UNSUPPORTED_RANGE, true);
    }

    @Test
    @DisplayName("Lillian epoch boundary 1582-10-15 -> valid")
    void lillianEpochValid() {
        assertResult(service.validateDate("1582-10-15", "YYYY-MM-DD"),
                true, DateValidationService.SEVERITY_VALID, DateValidationService.MSG_OK,
                DateValidationService.RESULT_VALID, true);
    }

    @Test
    @DisplayName("day before Lillian epoch 1582-10-14 -> UNSUPPORTED_RANGE, acceptable")
    void dayBeforeLillianUnsupportedRange() {
        assertResult(service.validateDate("1582-10-14", "YYYY-MM-DD"),
                false, DateValidationService.SEVERITY_INVALID, DateValidationService.MSG_UNSUPPORTED_RANGE,
                DateValidationService.RESULT_UNSUPPORTED_RANGE, true);
    }

    @Test
    @DisplayName("isValidDate boolean shortcut matches validateDate.valid()")
    void isValidDateShortcut() {
        assertThat(service.isValidDate("2024-02-29", "YYYY-MM-DD")).isTrue();
        assertThat(service.isValidDate("2023-02-29", "YYYY-MM-DD")).isFalse();
        assertThat(service.isValidDate("1500-01-01", "YYYY-MM-DD")).isFalse();
        assertThat(service.isValidDate("2024-02-29", "BADFMT")).isFalse();
    }

    @Test
    @DisplayName("result echoes the original (untrimmed) inputs in testDate/formatUsed")
    void resultEchoesOriginalInputs() {
        DateValidationResult result = service.validateDate("2024-02-29", "YYYY-MM-DD");
        assertThat(result.testDate()).isEqualTo("2024-02-29");
        assertThat(result.formatUsed()).isEqualTo("YYYY-MM-DD");
    }
}
