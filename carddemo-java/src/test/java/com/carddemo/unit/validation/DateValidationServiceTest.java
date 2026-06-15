package com.carddemo.unit.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.service.shared.DateValidationService;
import com.carddemo.service.shared.DateValidationService.DateValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateValidationService}, verifying behavioral parity with
 * the COBOL {@code CSUTLDTC} / {@code CEEDAYS} feedback contract (source commit
 * {@code 27d6c6f}). Covers the documented validation rows plus boundary and
 * diagnostic edge cases.
 */
class DateValidationServiceTest {

    private final DateValidationService service = new DateValidationService();

    @Test
    @DisplayName("Leap-year Feb 29 is valid")
    void leapYearFebruary29IsValid() {
        DateValidationResult result = service.validateDate("2024-02-29", "YYYY-MM-DD");
        assertTrue(result.valid());
        assertEquals(DateValidationService.SEVERITY_VALID, result.severity());
        assertEquals(DateValidationService.MSG_OK, result.messageNumber());
        assertEquals(DateValidationService.RESULT_VALID, result.resultText());
        assertTrue(result.isAcceptable());
    }

    @Test
    @DisplayName("Non-leap Feb 29 is an invalid calendar value (2508)")
    void nonLeapYearFebruary29IsBadDateValue() {
        DateValidationResult result = service.validateDate("2023-02-29", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.SEVERITY_INVALID, result.severity());
        assertEquals(DateValidationService.MSG_BAD_DATE_VALUE, result.messageNumber());
        assertEquals(DateValidationService.RESULT_BAD_DATE_VALUE, result.resultText());
        assertFalse(result.isAcceptable());
    }

    @Test
    @DisplayName("Month 13 is an invalid month (2517)")
    void month13IsInvalidMonth() {
        DateValidationResult result = service.validateDate("2024-13-01", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_INVALID_MONTH, result.messageNumber());
        assertEquals(DateValidationService.RESULT_INVALID_MONTH, result.resultText());
    }

    @Test
    @DisplayName("Day 32 is an invalid calendar value (2508)")
    void day32IsBadDateValue() {
        DateValidationResult result = service.validateDate("2024-01-32", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_BAD_DATE_VALUE, result.messageNumber());
    }

    @Test
    @DisplayName("April 31 is an invalid calendar value (2508)")
    void april31IsBadDateValue() {
        DateValidationResult result = service.validateDate("2024-04-31", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_BAD_DATE_VALUE, result.messageNumber());
    }

    @Test
    @DisplayName("Month 00 is an invalid month (2517)")
    void month00IsInvalidMonth() {
        DateValidationResult result = service.validateDate("2024-00-01", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_INVALID_MONTH, result.messageNumber());
        assertEquals(DateValidationService.RESULT_INVALID_MONTH, result.resultText());
    }

    @Test
    @DisplayName("Invalid month outranks a bad day when both are wrong (2517)")
    void invalidMonthOutranksBadDay() {
        DateValidationResult result = service.validateDate("2024-13-32", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_INVALID_MONTH, result.messageNumber());
    }

    @Test
    @DisplayName("Zero year with an accompanying failure is year-in-era-zero (2521)")
    void zeroYearIsYearInEraZero() {
        DateValidationResult result = service.validateDate("0000-13-01", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_YEAR_IN_ERA_ZERO, result.messageNumber());
        assertEquals(DateValidationService.RESULT_YEAR_IN_ERA_ZERO, result.resultText());
    }

    @Test
    @DisplayName("Length shorter than the format is insufficient (2507)")
    void lengthMismatchIsInsufficient() {
        DateValidationResult result = service.validateDate("2024-1-1", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_INSUFFICIENT_DATA, result.messageNumber());
        assertEquals(DateValidationService.RESULT_INSUFFICIENT, result.resultText());
    }

    @Test
    @DisplayName("Empty date is insufficient (2507)")
    void emptyDateIsInsufficient() {
        DateValidationResult result = service.validateDate("", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_INSUFFICIENT_DATA, result.messageNumber());
    }

    @Test
    @DisplayName("Blank (whitespace-only) date is insufficient (2507)")
    void blankDateIsInsufficient() {
        DateValidationResult result = service.validateDate("          ", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_INSUFFICIENT_DATA, result.messageNumber());
    }

    @Test
    @DisplayName("Null date is insufficient (2507)")
    void nullDateIsInsufficient() {
        DateValidationResult result = service.validateDate(null, "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_INSUFFICIENT_DATA, result.messageNumber());
    }

    @Test
    @DisplayName("Non-numeric characters in numeric positions yield 2520")
    void nonNumericDataIsDetected() {
        DateValidationResult result = service.validateDate("abcd-01-01", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_NON_NUMERIC_DATA, result.messageNumber());
        assertEquals(DateValidationService.RESULT_NON_NUMERIC, result.resultText());
    }

    @Test
    @DisplayName("Non-numeric data in compact YYYYMMDD positions yields 2520")
    void nonNumericDataCompactFormat() {
        DateValidationResult result = service.validateDate("2024XX29", "YYYYMMDD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_NON_NUMERIC_DATA, result.messageNumber());
    }

    @Test
    @DisplayName("Compact YYYYMMDD format parses a valid date")
    void compactFormatIsValid() {
        DateValidationResult result = service.validateDate("20240229", "YYYYMMDD");
        assertTrue(result.valid());
        assertEquals(DateValidationService.MSG_OK, result.messageNumber());
    }

    @Test
    @DisplayName("Slash separators are honored")
    void slashSeparatorsAreValid() {
        assertTrue(service.validateDate("2024/02/29", "YYYY/MM/DD").valid());
    }

    @Test
    @DisplayName("Dot separators are honored")
    void dotSeparatorsAreValid() {
        assertTrue(service.validateDate("2024.02.29", "YYYY.MM.DD").valid());
    }

    @Test
    @DisplayName("Two-digit year format (YY) parses a valid date")
    void twoDigitYearFormatIsValid() {
        assertTrue(service.validateDate("24-02-29", "YY-MM-DD").valid());
    }

    @Test
    @DisplayName("Lowercase format letters are accepted")
    void lowercaseFormatIsAccepted() {
        assertTrue(service.validateDate("2024-02-29", "yyyy-mm-dd").valid());
    }

    @Test
    @DisplayName("Unrecognized format token yields bad picture string (2518)")
    void badFormatIsBadPicString() {
        DateValidationResult result = service.validateDate("2024-02-29", "BADFMT");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_BAD_PIC_STRING, result.messageNumber());
        assertEquals(DateValidationService.RESULT_BAD_PIC, result.resultText());
    }

    @Test
    @DisplayName("Null format is treated as a bad picture string (2518)")
    void nullFormatIsBadPicString() {
        DateValidationResult result = service.validateDate("2024-02-29", null);
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_BAD_PIC_STRING, result.messageNumber());
    }

    @Test
    @DisplayName("Date before the Lillian epoch is unsupported (2513) but tolerated by consumers")
    void dateBeforeLillianEpochIsUnsupportedButAcceptable() {
        DateValidationResult result = service.validateDate("1500-01-01", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_UNSUPPORTED_RANGE, result.messageNumber());
        assertEquals(DateValidationService.RESULT_UNSUPPORTED_RANGE, result.resultText());
        assertTrue(result.isAcceptable());
    }

    @Test
    @DisplayName("The day before the Lillian epoch is unsupported (2513)")
    void dayBeforeLillianEpochIsUnsupported() {
        DateValidationResult result = service.validateDate("1582-10-14", "YYYY-MM-DD");
        assertFalse(result.valid());
        assertEquals(DateValidationService.MSG_UNSUPPORTED_RANGE, result.messageNumber());
        assertTrue(result.isAcceptable());
    }

    @Test
    @DisplayName("The Lillian epoch boundary date itself is valid")
    void lillianEpochBoundaryIsValid() {
        DateValidationResult result = service.validateDate("1582-10-15", "YYYY-MM-DD");
        assertTrue(result.valid());
        assertEquals(DateValidationService.MSG_OK, result.messageNumber());
    }

    @Test
    @DisplayName("Original (untrimmed) inputs are preserved for diagnostics")
    void originalInputsArePreservedForDiagnostics() {
        DateValidationResult result = service.validateDate("  2024-02-29 ", "YYYY-MM-DD");
        assertTrue(result.valid());
        assertEquals("  2024-02-29 ", result.testDate());
        assertEquals("YYYY-MM-DD", result.formatUsed());
    }

    @Test
    @DisplayName("isValidDate returns true for a strictly valid date")
    void isValidDateTrue() {
        assertTrue(service.isValidDate("2024-02-29", "YYYY-MM-DD"));
    }

    @Test
    @DisplayName("isValidDate returns false for an invalid date")
    void isValidDateFalse() {
        assertFalse(service.isValidDate("2023-02-29", "YYYY-MM-DD"));
    }

    @Test
    @DisplayName("Public feedback-code constants match the CEEDAYS contract values")
    void feedbackConstantsMatchContract() {
        assertEquals(0, DateValidationService.MSG_OK);
        assertEquals(2507, DateValidationService.MSG_INSUFFICIENT_DATA);
        assertEquals(2508, DateValidationService.MSG_BAD_DATE_VALUE);
        assertEquals(2509, DateValidationService.MSG_INVALID_ERA);
        assertEquals(2513, DateValidationService.MSG_UNSUPPORTED_RANGE);
        assertEquals(2517, DateValidationService.MSG_INVALID_MONTH);
        assertEquals(2518, DateValidationService.MSG_BAD_PIC_STRING);
        assertEquals(2520, DateValidationService.MSG_NON_NUMERIC_DATA);
        assertEquals(2521, DateValidationService.MSG_YEAR_IN_ERA_ZERO);
        assertEquals(0, DateValidationService.SEVERITY_VALID);
        assertEquals(3, DateValidationService.SEVERITY_INVALID);
    }
}
