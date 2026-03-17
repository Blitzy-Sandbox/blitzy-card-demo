package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for {@link DateValidationService}.
 *
 * <p>Exercises all COBOL validation paragraphs ported from CSUTLDTC.cbl (157 lines),
 * CSUTLDPY.cpy (376 lines) and CSUTLDWY.cpy (90 lines):
 * <ul>
 *   <li>EDIT-YEAR-CCYY — year/century validation (only 19xx and 20xx)</li>
 *   <li>EDIT-MONTH — month validation (1-12)</li>
 *   <li>EDIT-DAY — day validation (1-31)</li>
 *   <li>EDIT-DAY-MONTH-YEAR — combination checks (31-day months, Feb 30, leap year)</li>
 *   <li>EDIT-DATE-LE — CEEDAYS equivalent final validation via java.time</li>
 *   <li>EDIT-DATE-OF-BIRTH — future date rejection</li>
 * </ul>
 *
 * <p>Source traceability: CardDemo_v1.0-15-g27d6c6f-68
 *
 * <p>Pure unit tests — NO Spring context loading. DateValidationService
 * is stateless with no dependencies, so it is instantiated directly via
 * {@code new DateValidationService()} in {@link #setUp()}.
 *
 * <p>40 test cases covering: input validation (null/blank/length/numeric),
 * year validation (century 19/20 only), month validation (1-12), day validation
 * (1-31 with month-specific constraints), leap year (divisible by 4, century
 * divisible by 400), date-of-birth future-date rejection, CEEDAYS format mask
 * support, and DateValidationResult field assertions.
 */
class DateValidationServiceTest {

    /** Service under test — instantiated directly, no Spring context required. */
    private DateValidationService dateValidationService;

    /** CCYYMMDD formatter for DOB test date formatting. */
    private static final DateTimeFormatter CCYYMMDD_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd");

    /** Default variable name used in test assertions for error messages. */
    private static final String VAR_NAME = "TestDate";

    @BeforeEach
    void setUp() {
        dateValidationService = new DateValidationService();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Input Validation Tests (1-4)
    // Mirrors CSUTLDPY.cpy initial null/blank/format checks
    // ════════════════════════════════════════════════════════════════════════

    /** Test 1: Null date input returns invalid result. */
    @Test
    void testValidateDate_nullInput_returnsInvalid() {
        DateValidationResult result = dateValidationService.validateDate(null, VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 2: Blank/empty date input returns invalid result. */
    @Test
    void testValidateDate_blankInput_returnsInvalid() {
        DateValidationResult result = dateValidationService.validateDate("", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isNotNull();
    }

    /** Test 3: Wrong-length date (7 chars instead of 8) returns invalid. */
    @Test
    void testValidateDate_wrongLength_returnsInvalid() {
        DateValidationResult result = dateValidationService.validateDate("2024011", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
    }

    /** Test 4: Non-numeric date input returns invalid. */
    @Test
    void testValidateDate_nonNumeric_returnsInvalid() {
        DateValidationResult result = dateValidationService.validateDate("2024AB01", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Year Validation Tests (5-9)
    // Mirrors EDIT-YEAR-CCYY paragraph (CSUTLDPY.cpy lines 25-90)
    // CSUTLDTC only accepts 19xx and 20xx centuries
    // ════════════════════════════════════════════════════════════════════════

    /** Test 5: Year 1900 (century 19) is valid. */
    @Test
    void testValidateDate_year1900_valid() {
        DateValidationResult result = dateValidationService.validateDate("19000101", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.yearValid()).isTrue();
    }

    /** Test 6: Year 2000 (century 20) with day 15 is valid. */
    @Test
    void testValidateDate_year2000_valid() {
        DateValidationResult result = dateValidationService.validateDate("20000115", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.yearValid()).isTrue();
    }

    /** Test 7: Year 2024 (century 20) with valid date is valid. */
    @Test
    void testValidateDate_year2024_valid() {
        DateValidationResult result = dateValidationService.validateDate("20241015", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.yearValid()).isTrue();
    }

    /** Test 8: Year 1800 (century 18) is invalid — outside 19/20 range. */
    @Test
    void testValidateDate_year1800_invalid() {
        DateValidationResult result = dateValidationService.validateDate("18001015", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.yearValid()).isFalse();
        assertThat(result.resultMessage()).isEqualTo("Invalid Era");
    }

    /** Test 9: Year 2100 (century 21) is invalid — outside 19/20 range. */
    @Test
    void testValidateDate_year2100_invalid() {
        DateValidationResult result = dateValidationService.validateDate("21001015", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.yearValid()).isFalse();
        assertThat(result.resultMessage()).isEqualTo("Invalid Era");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Month Validation Tests (10-13)
    // Mirrors EDIT-MONTH paragraph (CSUTLDPY.cpy lines 91-147)
    // ════════════════════════════════════════════════════════════════════════

    /** Test 10: Month 00 is invalid (below range). */
    @Test
    void testValidateDate_month00_invalid() {
        DateValidationResult result = dateValidationService.validateDate("20240001", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.monthValid()).isFalse();
    }

    /** Test 11: Month 01 (January) is valid. */
    @Test
    void testValidateDate_month01_valid() {
        DateValidationResult result = dateValidationService.validateDate("20240115", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.monthValid()).isTrue();
    }

    /** Test 12: Month 12 (December) is valid. */
    @Test
    void testValidateDate_month12_valid() {
        DateValidationResult result = dateValidationService.validateDate("20241215", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.monthValid()).isTrue();
    }

    /** Test 13: Month 13 is invalid (above range). */
    @Test
    void testValidateDate_month13_invalid() {
        DateValidationResult result = dateValidationService.validateDate("20241315", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.monthValid()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Day Validation Tests (14-20)
    // Mirrors EDIT-DAY and EDIT-DAY-MONTH-YEAR paragraphs
    // ════════════════════════════════════════════════════════════════════════

    /** Test 14: Day 00 is invalid (below range). */
    @Test
    void testValidateDate_day00_invalid() {
        DateValidationResult result = dateValidationService.validateDate("20240100", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 15: Day 31 is valid for January (31-day month). */
    @Test
    void testValidateDate_day31_validForJanuary() {
        DateValidationResult result = dateValidationService.validateDate("20240131", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 16: Day 31 is invalid for April (30-day month). */
    @Test
    void testValidateDate_day31_invalidForApril() {
        DateValidationResult result = dateValidationService.validateDate("20240431", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 17: Day 31 is invalid for June (30-day month). */
    @Test
    void testValidateDate_day31_invalidForJune() {
        DateValidationResult result = dateValidationService.validateDate("20240631", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 18: Day 31 is invalid for September (30-day month). */
    @Test
    void testValidateDate_day31_invalidForSeptember() {
        DateValidationResult result = dateValidationService.validateDate("20240931", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 19: Day 31 is invalid for November (30-day month). */
    @Test
    void testValidateDate_day31_invalidForNovember() {
        DateValidationResult result = dateValidationService.validateDate("20241131", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 20: Day 30 is valid for April (30-day month). */
    @Test
    void testValidateDate_day30_validForApril() {
        DateValidationResult result = dateValidationService.validateDate("20240430", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 31-Day Month Coverage Tests (21-26)
    // Ensures all months with 31 days (Jan, Mar, May, Jul, Aug, Oct, Dec)
    // accept day 31. Mirrors WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12.
    // ════════════════════════════════════════════════════════════════════════

    /** Test 21: Day 31 is valid for March (31-day month). */
    @Test
    void testValidateDate_day31_validForMarch() {
        DateValidationResult result = dateValidationService.validateDate("20240331", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 22: Day 31 is valid for May (31-day month). */
    @Test
    void testValidateDate_day31_validForMay() {
        DateValidationResult result = dateValidationService.validateDate("20240531", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 23: Day 31 is valid for July (31-day month). */
    @Test
    void testValidateDate_day31_validForJuly() {
        DateValidationResult result = dateValidationService.validateDate("20240731", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 24: Day 31 is valid for August (31-day month). */
    @Test
    void testValidateDate_day31_validForAugust() {
        DateValidationResult result = dateValidationService.validateDate("20240831", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 25: Day 31 is valid for October (31-day month). */
    @Test
    void testValidateDate_day31_validForOctober() {
        DateValidationResult result = dateValidationService.validateDate("20241031", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 26: Day 31 is valid for December (31-day month). */
    @Test
    void testValidateDate_day31_validForDecember() {
        DateValidationResult result = dateValidationService.validateDate("20241231", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // February Leap Year Tests (27-32) — CRITICAL: LE CEEDAYS compliance
    // Mirrors EDIT-DAY-MONTH-YEAR paragraph (CSUTLDPY.cpy lines 243-272)
    // COBOL leap year: IF YY=0 → divisor=400 ELSE divisor=4
    //                  DIVIDE CCYY BY divisor; IF REMAINDER=0 → leap
    // ════════════════════════════════════════════════════════════════════════

    /** Test 27: Feb 29, 2024 is valid (2024 is leap: 2024 % 4 = 0). */
    @Test
    void testValidateDate_feb29_leapYear2024_valid() {
        DateValidationResult result = dateValidationService.validateDate("20240229", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.yearValid()).isTrue();
    }

    /** Test 28: Feb 29, 2023 is invalid (2023 is NOT leap: 2023 % 4 = 3). */
    @Test
    void testValidateDate_feb29_nonLeapYear2023_invalid() {
        DateValidationResult result = dateValidationService.validateDate("20230229", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 29: Feb 29, 2000 is valid (century year: 2000 % 400 = 0 → leap). */
    @Test
    void testValidateDate_feb29_leapYear2000_valid() {
        DateValidationResult result = dateValidationService.validateDate("20000229", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
        assertThat(result.yearValid()).isTrue();
    }

    /** Test 30: Feb 29, 1900 is invalid (century year: 1900 % 400 = 300 → NOT leap). */
    @Test
    void testValidateDate_feb29_nonLeapYear1900_invalid() {
        DateValidationResult result = dateValidationService.validateDate("19000229", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    /** Test 31: Feb 28, 2023 is valid (Feb 28 is always valid). */
    @Test
    void testValidateDate_feb28_nonLeapYear_valid() {
        DateValidationResult result = dateValidationService.validateDate("20230228", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 32: Feb 30 is invalid (February never has 30 days). */
    @Test
    void testValidateDate_feb30_invalid() {
        DateValidationResult result = dateValidationService.validateDate("20240230", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Date of Birth Validation Tests (33-35)
    // Mirrors EDIT-DATE-OF-BIRTH paragraph (CSUTLDPY.cpy lines 341-372)
    // "At the time of writing this program, Time travel was not possible."
    // ════════════════════════════════════════════════════════════════════════

    /** Test 33: Future date is invalid for date-of-birth. */
    @Test
    void testValidateDateOfBirth_futureDate_invalid() {
        LocalDate futureDate = LocalDate.now().plusYears(1);
        String futureDateStr = futureDate.format(CCYYMMDD_FORMAT);

        DateValidationResult result = dateValidationService.validateDateOfBirth(
                futureDateStr, VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.fullMessage()).isNotNull();
    }

    /** Test 34: Past date is valid for date-of-birth. */
    @Test
    void testValidateDateOfBirth_pastDate_valid() {
        DateValidationResult result = dateValidationService.validateDateOfBirth(
                "19900515", VAR_NAME);

        assertThat(result.valid()).isTrue();
    }

    /** Test 35: Today's date is valid for date-of-birth (not in the future). */
    @Test
    void testValidateDateOfBirth_todayDate_valid() {
        LocalDate today = LocalDate.now();
        String todayStr = today.format(CCYYMMDD_FORMAT);

        DateValidationResult result = dateValidationService.validateDateOfBirth(
                todayStr, VAR_NAME);

        assertThat(result.valid()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // CEEDAYS Format Mask Support Tests (36-38)
    // Mirrors CSUTLDTC.cbl CALL 'CEEDAYS' with WS-DATE-FORMAT parameter
    // ════════════════════════════════════════════════════════════════════════

    /** Test 36: YYYYMMDD mask with valid date returns valid (severity 0). */
    @Test
    void testValidateWithCeedays_YYYYMMDD_valid() {
        DateValidationResult result = dateValidationService.validateWithCeedays(
                "20240101", "YYYYMMDD");

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isEqualTo(0);
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
    }

    /** Test 37: MMDDYYYY mask with valid date returns valid (severity 0). */
    @Test
    void testValidateWithCeedays_MMDDYYYY_valid() {
        DateValidationResult result = dateValidationService.validateWithCeedays(
                "01012024", "MMDDYYYY");

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isEqualTo(0);
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
    }

    /** Test 38: Unsupported format mask returns invalid with "Bad Pic String". */
    @Test
    void testValidateWithCeedays_invalidMask_returnsInvalid() {
        DateValidationResult result = dateValidationService.validateWithCeedays(
                "20240101", "DDMMYY");

        assertThat(result.valid()).isFalse();
        assertThat(result.resultMessage()).isEqualTo("Bad Pic String");
    }

    // ════════════════════════════════════════════════════════════════════════
    // DateValidationResult Field Assertions (39-40)
    // Verifies the record structure returned from validation methods
    // ════════════════════════════════════════════════════════════════════════

    /** Test 39: Valid date result has all correct fields populated. */
    @Test
    void testValidateDate_validDate_resultHasCorrectFields() {
        DateValidationResult result = dateValidationService.validateDate("20240115", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isEqualTo(0);
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.fullMessage()).isNotNull();
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    /** Test 40: Invalid date result has error message populated. */
    @Test
    void testValidateDate_invalidDate_resultHasErrorMessage() {
        DateValidationResult result = dateValidationService.validateDate("20241315", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isNotNull();
        assertThat(result.fullMessage()).isNotNull();
    }
}
