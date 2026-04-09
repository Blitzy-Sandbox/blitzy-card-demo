package com.cardemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive unit tests for {@link DateValidationService}.
 *
 * <p>Tests exercise all COBOL validation paragraphs ported from
 * CSUTLDPY.cpy (376 lines) with working storage from CSUTLDWY.cpy (90 lines)
 * and CEEDAYS equivalent from CSUTLDTC.cbl (157 lines):
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
 */
class DateValidationServiceTest {

    /** Variable name used in error messages for most tests. */
    private static final String VAR_NAME = "Date";

    /** Variable name for date-of-birth tests. */
    private static final String DOB_VAR_NAME = "DOB";

    private DateValidationService dateValidationService;

    /**
     * Instantiates the service under test directly — no Spring context needed.
     * The service is stateless with zero dependencies.
     */
    @BeforeEach
    void setUp() {
        dateValidationService = new DateValidationService();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Valid Date Tests — EDIT-DATE-CCYYMMDD Success Path
    // Covers the complete validation cascade resulting in a valid date.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDIT-DATE-CCYYMMDD: Standard valid date 2020-01-01")
    void testValidDate_StandardDate_20200101() {
        DateValidationResult result = dateValidationService.validateDate("20200101", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: Leap year Feb 29, 2024 — 2024 % 4 = 0")
    void testValidDate_LeapYearFeb29_2024() {
        DateValidationResult result = dateValidationService.validateDate("20240229", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: Century leap year Feb 29, 2000 — 2000 % 400 = 0")
    void testValidDate_LeapYearFeb29_2000() {
        DateValidationResult result = dateValidationService.validateDate("20000229", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY: January 31 boundary — 31-day month")
    void testValidDate_JanuaryBoundary() {
        DateValidationResult result = dateValidationService.validateDate("20200131", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY: December 31 boundary — last day of year")
    void testValidDate_DecemberBoundary() {
        DateValidationResult result = dateValidationService.validateDate("20201231", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY: February 28 in non-leap year 2023 — last valid Feb day")
    void testValidDate_February28NonLeapYear() {
        DateValidationResult result = dateValidationService.validateDate("20230228", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY: April 30 boundary — 30-day month max")
    void testValidDate_30DayMonthBoundary() {
        DateValidationResult result = dateValidationService.validateDate("20200430", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20200101", "20241231", "19990615", "20000229"})
    @DisplayName("EDIT-DATE-CCYYMMDD: Multiple valid dates (parameterized)")
    void testValidDates_Parameterized(String dateStr) {
        DateValidationResult result = dateValidationService.validateDate(dateStr, VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Invalid Year Tests — EDIT-YEAR-CCYY (CSUTLDPY.cpy lines 25-90)
    // Validates year/century: blank, non-numeric, century must be 19 or 20.
    // CSUTLDWY.cpy: THIS-CENTURY VALUE 20, LAST-CENTURY VALUE 19.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDIT-YEAR-CCYY: Year blank (4 spaces) — 'Year must be supplied.'")
    void testInvalidDate_YearBlank() {
        DateValidationResult result = dateValidationService.validateDate("    0101", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Insufficient");
        assertThat(result.fullMessage()).isEqualTo("Date : Year must be supplied.");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-YEAR-CCYY: Year not numeric — 'must be 4 digit number.'")
    void testInvalidDate_YearNotNumeric() {
        DateValidationResult result = dateValidationService.validateDate("ABCD0101", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Nonnumeric data");
        assertThat(result.fullMessage()).isEqualTo("Date must be 4 digit number.");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-YEAR-CCYY: Century 21 not valid — only 19 and 20 accepted")
    void testInvalidDate_CenturyNotValid_2100() {
        DateValidationResult result = dateValidationService.validateDate("21000101", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Invalid Era");
        assertThat(result.fullMessage()).isEqualTo("Date : Century is not valid.");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-YEAR-CCYY: Century 18 not valid")
    void testInvalidDate_CenturyNotValid_1800() {
        DateValidationResult result = dateValidationService.validateDate("18000101", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Invalid Era");
        assertThat(result.fullMessage()).isEqualTo("Date : Century is not valid.");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-YEAR-CCYY: Century 00 not valid")
    void testInvalidDate_CenturyNotValid_0000() {
        DateValidationResult result = dateValidationService.validateDate("00000101", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Invalid Era");
        assertThat(result.fullMessage()).isEqualTo("Date : Century is not valid.");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Invalid Month Tests — EDIT-MONTH (CSUTLDPY.cpy lines 91-147)
    // Validates month: blank, non-numeric, range 1-12.
    // CSUTLDWY.cpy: WS-VALID-MONTH VALUES 1 THROUGH 12.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDIT-MONTH: Month 00 out of range — not between 1 and 12")
    void testInvalidDate_Month00() {
        DateValidationResult result = dateValidationService.validateDate("20230001", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Invalid month");
        assertThat(result.fullMessage()).isEqualTo("Date: Month must be a number between 1 and 12.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-MONTH: Month 13 out of range")
    void testInvalidDate_Month13() {
        DateValidationResult result = dateValidationService.validateDate("20231301", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Invalid month");
        assertThat(result.fullMessage()).isEqualTo("Date: Month must be a number between 1 and 12.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-MONTH: Month blank — 'Month must be supplied.'")
    void testInvalidDate_MonthBlank() {
        DateValidationResult result = dateValidationService.validateDate("2023  01", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Insufficient");
        assertThat(result.fullMessage()).isEqualTo("Date : Month must be supplied.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-MONTH: Month non-numeric")
    void testInvalidDate_MonthNonNumeric() {
        DateValidationResult result = dateValidationService.validateDate("2023AB01", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Nonnumeric data");
        assertThat(result.fullMessage()).isEqualTo("Date: Month must be a number between 1 and 12.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Invalid Day Tests — EDIT-DAY (CSUTLDPY.cpy lines 150-207)
    // Validates day: blank, non-numeric, range 1-31.
    // CSUTLDWY.cpy: WS-VALID-DAY VALUES 1 THROUGH 31.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDIT-DAY: Day 00 out of range — not between 1 and 31")
    void testInvalidDate_Day00() {
        DateValidationResult result = dateValidationService.validateDate("20230100", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage()).isEqualTo("Date:day must be a number between 1 and 31.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY: Day 32 exceeds maximum")
    void testInvalidDate_Day32() {
        DateValidationResult result = dateValidationService.validateDate("20230132", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage()).isEqualTo("Date:day must be a number between 1 and 31.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY: Day blank — 'Day must be supplied.'")
    void testInvalidDate_DayBlank() {
        DateValidationResult result = dateValidationService.validateDate("202301  ", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Insufficient");
        assertThat(result.fullMessage()).isEqualTo("Date : Day must be supplied.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY: Day non-numeric")
    void testInvalidDate_DayNonNumeric() {
        DateValidationResult result = dateValidationService.validateDate("202301AB", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Nonnumeric data");
        assertThat(result.fullMessage()).isEqualTo("Date:day must be a number between 1 and 31.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Day-Month-Year Combination Tests — EDIT-DAY-MONTH-YEAR
    // (CSUTLDPY.cpy lines 209-282)
    // Validates: 31-day month check, February 30 check, February 29 leap year.
    // CSUTLDWY.cpy: WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12.
    //               WS-FEBRUARY VALUE 2.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: April 31 — 'Cannot have 31 days in this month.'")
    void testInvalidDate_April31() {
        DateValidationResult result = dateValidationService.validateDate("20230431", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage()).isEqualTo("Date:Cannot have 31 days in this month.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: June 31 — 'Cannot have 31 days in this month.'")
    void testInvalidDate_June31() {
        DateValidationResult result = dateValidationService.validateDate("20230631", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage()).isEqualTo("Date:Cannot have 31 days in this month.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: September 31 — 'Cannot have 31 days in this month.'")
    void testInvalidDate_September31() {
        DateValidationResult result = dateValidationService.validateDate("20230931", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage()).isEqualTo("Date:Cannot have 31 days in this month.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: November 31 — 'Cannot have 31 days in this month.'")
    void testInvalidDate_November31() {
        DateValidationResult result = dateValidationService.validateDate("20231131", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage()).isEqualTo("Date:Cannot have 31 days in this month.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: February 30 — 'Cannot have 30 days in this month.'")
    void testInvalidDate_February30() {
        DateValidationResult result = dateValidationService.validateDate("20230230", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage()).isEqualTo("Date:Cannot have 30 days in this month.");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Leap Year Tests — February 29 (CSUTLDPY.cpy lines 243-272)
    // COBOL leap year algorithm:
    //   IF WS-EDIT-DATE-YY-N = 0 (century year) → divisor = 400
    //   ELSE → divisor = 4
    //   DIVIDE year BY divisor → IF remainder ≠ 0 → NOT a leap year
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: Feb 29 2023 not leap year — 2023 % 4 = 3")
    void testInvalidDate_February29_NotLeapYear_2023() {
        DateValidationResult result = dateValidationService.validateDate("20230229", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        // COBOL error has NO space between "year." and "Cannot" (CSUTLDPY.cpy line 266)
        assertThat(result.fullMessage())
                .isEqualTo("Date:Not a leap year.Cannot have 29 days in this month.");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: Feb 29 1900 not leap year — yy=00, 1900 % 400 = 300")
    void testInvalidDate_February29_NotLeapYear_1900() {
        DateValidationResult result = dateValidationService.validateDate("19000229", VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        assertThat(result.fullMessage())
                .isEqualTo("Date:Not a leap year.Cannot have 29 days in this month.");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: Feb 29 2024 IS leap year — 2024 % 4 = 0")
    void testValidDate_February29_LeapYear_2024() {
        DateValidationResult result = dateValidationService.validateDate("20240229", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: Feb 29 2000 IS leap year — yy=00, 2000 % 400 = 0")
    void testValidDate_February29_LeapYear_2000() {
        DateValidationResult result = dateValidationService.validateDate("20000229", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DAY-MONTH-YEAR: Feb 29 1996 IS leap year — 1996 % 4 = 0")
    void testValidDate_February29_LeapYear_1996() {
        DateValidationResult result = dateValidationService.validateDate("19960229", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Century Boundary Tests
    // Verifies dates at the 19xx/20xx transition point.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Century boundary: Dec 31 1999 — last day of 19xx century")
    void testValidDate_CenturyBoundary_1999() {
        DateValidationResult result = dateValidationService.validateDate("19991231", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("Century boundary: Jan 1 2000 — first day of 20xx century")
    void testValidDate_CenturyBoundary_2000() {
        DateValidationResult result = dateValidationService.validateDate("20000101", VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Date-of-Birth Validation — EDIT-DATE-OF-BIRTH
    // (CSUTLDPY.cpy lines 341-372)
    // Validates that date is not in the future using FUNCTION CURRENT-DATE.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDIT-DATE-OF-BIRTH: Past date 1990-01-15 is valid")
    void testDateOfBirth_PastDate_Valid() {
        DateValidationResult result =
                dateValidationService.validateDateOfBirth("19900115", DOB_VAR_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
    }

    @Test
    @DisplayName("EDIT-DATE-OF-BIRTH: Future date 2099-12-31 — 'cannot be in the future'")
    void testDateOfBirth_FutureDate_Invalid() {
        DateValidationResult result =
                dateValidationService.validateDateOfBirth("20991231", DOB_VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(4);
        assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        // COBOL message preserves trailing space (CSUTLDPY.cpy line 363)
        assertThat(result.fullMessage()).isEqualTo("DOB:cannot be in the future ");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("EDIT-DATE-OF-BIRTH: Invalid format rejected before DOB check")
    void testDateOfBirth_InvalidFormat_StillRejected() {
        // Invalid date format (non-numeric year) should be caught by validateDate
        // before the date-of-birth future check executes
        DateValidationResult result =
                dateValidationService.validateDateOfBirth("ABCD1231", DOB_VAR_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.resultMessage()).isEqualTo("Nonnumeric data");
        assertThat(result.fullMessage()).isEqualTo("DOB must be 4 digit number.");
        assertThat(result.yearValid()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // CEEDAYS Equivalent Tests — validateWithCeedays
    // Replaces COBOL CALL 'CSUTLDTC' (CSUTLDTC.cbl lines 88-154).
    // Tests the direct CEEDAYS-like API with format mask parameter.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CEEDAYS: Valid date 2023-01-15 with YYYYMMDD format — severity 0")
    void testCeedays_ValidDate_YYYYMMDD() {
        DateValidationResult result =
                dateValidationService.validateWithCeedays("20230115", "YYYYMMDD");

        assertThat(result.valid()).isTrue();
        assertThat(result.severityCode()).isZero();
        assertThat(result.resultMessage()).isEqualTo("Date is valid");
        assertThat(result.yearValid()).isTrue();
        assertThat(result.monthValid()).isTrue();
        assertThat(result.dayValid()).isTrue();
        // Verify the 80-byte CEEDAYS message format contains expected tokens
        assertThat(result.fullMessage()).contains("Mesg Code:");
        assertThat(result.fullMessage()).contains("TstDate:");
        assertThat(result.fullMessage()).contains("Mask used:");
    }

    @Test
    @DisplayName("CEEDAYS: Invalid date (month 13) with YYYYMMDD format — severity > 0")
    void testCeedays_InvalidDate_YYYYMMDD() {
        DateValidationResult result =
                dateValidationService.validateWithCeedays("20231301", "YYYYMMDD");

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isGreaterThan(0);
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
        // Verify 80-byte message format is preserved even for errors
        assertThat(result.fullMessage()).contains("Mesg Code:");
    }

    @Test
    @DisplayName("CEEDAYS: Non-numeric date — FC-NON-NUMERIC-DATA (CSUTLDTC.cbl line 69)")
    void testCeedays_NonNumericDate() {
        DateValidationResult result =
                dateValidationService.validateWithCeedays("ABCD0101", "YYYYMMDD");

        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isEqualTo(3);
        assertThat(result.resultMessage()).isEqualTo("Nonnumeric data");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();

        // Verify that raw Java date parsing would throw DateTimeParseException
        // for non-numeric input — documents why the service pre-checks this case
        // (the service converts exceptions to structured results, never propagating them)
        assertThatThrownBy(() -> LocalDate.parse("ABCD0101",
                DateTimeFormatter.ofPattern("uuuuMMdd")
                        .withResolverStyle(ResolverStyle.STRICT)))
                .isInstanceOf(DateTimeParseException.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Edge Cases and Boundary Tests
    // Covers null, empty, too-short, and too-long date strings.
    // These inputs are caught by the initial guard clause in validateDate
    // (dateStr == null || dateStr.isBlank() || dateStr.length() != 8).
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Edge case: null date returns error result without NPE")
    void testNullDate() {
        DateValidationResult result = dateValidationService.validateDate(null, VAR_NAME);

        assertThat(result).isNotNull();
        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isGreaterThan(0);
        assertThat(result.fullMessage()).contains("Year must be supplied");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("Edge case: empty date string returns error result")
    void testEmptyDate() {
        DateValidationResult result = dateValidationService.validateDate("", VAR_NAME);

        assertThat(result).isNotNull();
        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isGreaterThan(0);
        assertThat(result.fullMessage()).contains("Year must be supplied");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("Edge case: date too short (6 chars) returns error result")
    void testDateTooShort() {
        DateValidationResult result = dateValidationService.validateDate("202301", VAR_NAME);

        assertThat(result).isNotNull();
        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isGreaterThan(0);
        assertThat(result.fullMessage()).contains("Year must be supplied");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }

    @Test
    @DisplayName("Edge case: date too long (9 chars) returns error result")
    void testDateTooLong() {
        DateValidationResult result = dateValidationService.validateDate("202301011", VAR_NAME);

        assertThat(result).isNotNull();
        assertThat(result.valid()).isFalse();
        assertThat(result.severityCode()).isGreaterThan(0);
        assertThat(result.fullMessage()).contains("Year must be supplied");
        assertThat(result.yearValid()).isFalse();
        assertThat(result.monthValid()).isFalse();
        assertThat(result.dayValid()).isFalse();
    }
}
