package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.exception.DateValidationException;

/**
 * Pure, dependency-free unit test for {@link DateValidationService}.
 *
 * <p>{@code DateValidationService} is the Java replacement for the COBOL
 * Language-Environment {@code CEEDAYS} date check (program {@code CSUTLDTC} and
 * its edit copybooks {@code CSUTLDPY}/{@code CSUTLDWY}, frozen reference SHA
 * {@code 27d6c6f}). It validates a fixed-width {@code CCYYMMDD} string using
 * {@link java.time.LocalDate} plus custom edits, reporting the <em>first</em>
 * failed edit in a fixed order (year &rarr; month &rarr; day &rarr; combined
 * day-in-month / leap-year rules) &mdash; exactly matching the legacy
 * first-error semantics.
 *
 * <p>The human-readable failure text is an external, byte-parity contract
 * (migration goal G4): the exact visible characters travel out over the
 * REST/JSON boundary, so any accidental edit to a production literal (an added
 * or dropped space, changed punctuation, altered casing) is a behavioral
 * regression against the legacy system. Every message assertion below therefore
 * pins the text <em>verbatim</em> against a local oracle copied
 * character-for-character from the production {@code CSUTLDPY} literals; if a
 * production literal drifts, the {@code isEqualTo}/{@code hasMessage} assertions
 * fail and name exactly which migrated edit regressed. Note that the legacy
 * literals are intentionally non-uniform (some begin {@code " : "}, some
 * {@code ": "}, some {@code ":"}, one is all lower-case {@code ":day ..."}); the
 * oracle preserves those inconsistencies exactly.
 *
 * <p>The class under test has no collaborators, so this test loads no Spring
 * context and uses no Testcontainers, database, file, network, or mocks &mdash;
 * every assertion drives a plain {@code new DateValidationService()} in memory.
 * The suite runs in milliseconds and contributes fast, deterministic branch
 * coverage toward the Gate&nbsp;8 (&ge;80%) JaCoCo threshold, exercising every
 * edit branch of the service and every calendar rule (including the century
 * leap-year rule where a year divisible by 100 must also be divisible by 400).
 * No monetary arithmetic is involved, so no {@code float}/{@code double} appears
 * here. The design rationale for the migration is documented in
 * {@code docs/decision-log.md}, not in these comments; no COBOL source is
 * reproduced here beyond the migrated visible message text the production class
 * already exposes.
 */
@DisplayName("DateValidationService — CCYYMMDD edits + verbatim CSUTLDPY message parity")
class DateValidationServiceTest {

    // ---------------------------------------------------------------------
    // Byte-parity oracle — the message SUFFIXES below are copied
    // character-for-character from the production DateValidationService
    // constants (which in turn mirror copybook CSUTLDPY). Each production
    // message is the trimmed field-name prefix followed by one of these
    // literals, so the expected full message is (prefix + suffix). These local
    // copies are the oracle: if a production literal is edited, the assertions
    // that build (DEFAULT_PREFIX + suffix) fail and pinpoint the regression.
    // The inconsistent leading spacing/casing is intentional and preserved.
    // ---------------------------------------------------------------------

    /** Default field label used by the single-argument overload ({@code DEFAULT_FIELD_NAME}). */
    private static final String DEFAULT_PREFIX = "Date";

    /** Verbatim {@code CSUTLDPY} year-not-supplied / wrong-length literal. */
    private static final String MSG_YEAR_NOT_SUPPLIED = " : Year must be supplied.";

    /** Verbatim {@code CSUTLDPY} year-not-numeric literal. */
    private static final String MSG_YEAR_NOT_NUMERIC = " must be 4 digit number.";

    /** Verbatim {@code CSUTLDPY} century-not-valid literal. */
    private static final String MSG_CENTURY_INVALID = " : Century is not valid.";

    /** Verbatim {@code CSUTLDPY} month-not-supplied literal. */
    private static final String MSG_MONTH_NOT_SUPPLIED = " : Month must be supplied.";

    /** Verbatim {@code CSUTLDPY} month-out-of-range / non-numeric literal. */
    private static final String MSG_MONTH_RANGE = ": Month must be a number between 1 and 12.";

    /** Verbatim {@code CSUTLDPY} day-not-supplied literal. */
    private static final String MSG_DAY_NOT_SUPPLIED = " : Day must be supplied.";

    /** Verbatim {@code CSUTLDPY} day-out-of-range / non-numeric literal (note lower-case "day"). */
    private static final String MSG_DAY_RANGE = ":day must be a number between 1 and 31.";

    /** Verbatim {@code CSUTLDPY} thirty-one-days-in-month literal. */
    private static final String MSG_31_DAYS = ":Cannot have 31 days in this month.";

    /** Verbatim {@code CSUTLDPY} thirty-days-in-February literal. */
    private static final String MSG_30_DAYS = ":Cannot have 30 days in this month.";

    /** Verbatim {@code CSUTLDPY} leap-year literal for February 29 in a non-leap year. */
    private static final String MSG_LEAP_YEAR = ":Not a leap year.Cannot have 29 days in this month.";

    /** Fresh, Spring-free instance exercising the plain {@code new DateValidationService()} path. */
    private final DateValidationService service = new DateValidationService();

    // =====================================================================
    // Section A — valid dates clear every edit and return the parsed value.
    // =====================================================================

    @Test
    @DisplayName("validateDateCcyyMmDd(\"20241231\") returns 2024-12-31")
    void validateDateCcyyMmDd_validDate_returnsLocalDate() {
        assertThat(service.validateDateCcyyMmDd("20241231"))
                .isEqualTo(LocalDate.of(2024, 12, 31));
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"20240229\") accepts Feb 29 in a leap year")
    void validateDateCcyyMmDd_leapFeb29Valid_returnsDate() {
        // 2024 IS a leap year (divisible by 4, not a century year), so Feb 29 is
        // valid. Guards against an over-eager leap-year rejection.
        assertThat(service.validateDateCcyyMmDd("20240229"))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}-{2}-{3}")
    @DisplayName("validateDateCcyyMmDd accepts assorted valid dates across both centuries and calendar rules")
    @CsvSource({
        // Century leap rule: 2000 is divisible by 400 -> IS a leap year.
        "20000229, 2000, 2, 29",
        // 1900 is divisible by 100 but not 400 -> NOT a leap year; Feb 28 is still valid.
        "19000228, 1900, 2, 28",
        // 19xx century (last century) with a 31-day month.
        "19991231, 1999, 12, 31",
        // Lower 19xx boundary.
        "19010101, 1901, 1, 1",
        // 30-day month, last valid day.
        "20240430, 2024, 4, 30",
        // Another 30-day month (November).
        "20241130, 2024, 11, 30",
        // First day of a 31-day month.
        "20240101, 2024, 1, 1",
        // Upper 20xx boundary.
        "20991231, 2099, 12, 31"
    })
    void validateDateCcyyMmDd_additionalValidDates_returnLocalDate(String input, int year, int month, int day) {
        assertThat(service.validateDateCcyyMmDd(input))
                .as("input <%s> must parse to %d-%02d-%02d", input, year, month, day)
                .isEqualTo(LocalDate.of(year, month, day));
    }

    // =====================================================================
    // Section B — YEAR edits (EDIT-YEAR-CCYY): supplied, wrong length,
    // numeric, and century (only 19xx / 20xx accepted).
    // =====================================================================

    @Test
    @DisplayName("validateDateCcyyMmDd(\"\") reports \"Year must be supplied.\" and carries the offending value")
    void validateDateCcyyMmDd_blankOrWrongLength_throws() {
        // An empty string has no CCYYMMDD field at all: the length guard fires
        // first and the legacy edit reports the year as not supplied.
        DateValidationException ex = assertThrows(
                DateValidationException.class,
                () -> service.validateDateCcyyMmDd(""));

        assertThat(ex.getMessage()).isEqualTo(DEFAULT_PREFIX + MSG_YEAR_NOT_SUPPLIED);
        // The production failure(...) ctor sets the offending input on the exception.
        assertThat(ex.getInvalidValue()).isEqualTo("");
    }

    @ParameterizedTest(name = "[{index}] wrong length \"{0}\"")
    @DisplayName("validateDateCcyyMmDd rejects any non-8-character input with the year-supplied message")
    @ValueSource(strings = {"   ", "1231", "2024123", "202412311", "202412310000"})
    void validateDateCcyyMmDd_wrongLength_throwsYearMessage(String input) {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd(input))
                .as("wrong-length input <%s> must be rejected", input)
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_YEAR_NOT_SUPPLIED);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(null) reports \"Year must be supplied.\" and carries a null value")
    void validateDateCcyyMmDd_nullInput_throwsYearMessage() {
        DateValidationException ex = assertThrows(
                DateValidationException.class,
                () -> service.validateDateCcyyMmDd(null));

        assertThat(ex.getMessage()).isEqualTo(DEFAULT_PREFIX + MSG_YEAR_NOT_SUPPLIED);
        assertThat(ex.getInvalidValue()).isNull();
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"    1231\") treats an all-blank year as not supplied")
    void validateDateCcyyMmDd_yearAllSpaces_throwsYearMessage() {
        // Correct overall length (8) but the CCYY field is blank -> the
        // isFieldSupplied edit (not the length guard) reports "not supplied".
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("    1231"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_YEAR_NOT_SUPPLIED);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"20X41231\") reports the 4-digit-number message for a non-numeric year")
    void validateDateCcyyMmDd_yearNonNumeric_throwsFourDigitMessage() {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20X41231"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_YEAR_NOT_NUMERIC);
    }

    @ParameterizedTest(name = "[{index}] invalid century \"{0}\"")
    @DisplayName("validateDateCcyyMmDd accepts only the 19xx/20xx centuries")
    @ValueSource(strings = {"18991231", "21001231", "00010101", "99991231"})
    void validateDateCcyyMmDd_invalidCentury_throwsCenturyMessage(String input) {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd(input))
                .as("century of <%s> must be rejected", input)
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_CENTURY_INVALID);
    }

    // =====================================================================
    // Section C — MONTH edits (EDIT-MONTH): supplied, numeric, range 1..12.
    // Non-numeric and out-of-range share one CSUTLDPY message.
    // =====================================================================

    @Test
    @DisplayName("validateDateCcyyMmDd(\"20241301\") reports the month-range message (month 13)")
    void validateDateCcyyMmDd_invalidMonth_throwsWithMonthMessage() {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20241301"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_MONTH_RANGE);
    }

    @ParameterizedTest(name = "[{index}] bad month \"{0}\"")
    @DisplayName("validateDateCcyyMmDd rejects month 0, > 12, and non-numeric with the month-range message")
    @ValueSource(strings = {"20240015", "20241501", "20249915", "2024XX15"})
    void validateDateCcyyMmDd_monthOutOfRangeOrNonNumeric_throwsMonthMessage(String input) {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd(input))
                .as("month of <%s> must be rejected", input)
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_MONTH_RANGE);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"2024  31\") treats an all-blank month as not supplied")
    void validateDateCcyyMmDd_monthAllSpaces_throwsMonthNotSupplied() {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("2024  31"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_MONTH_NOT_SUPPLIED);
    }

    // =====================================================================
    // Section D — DAY edits (EDIT-DAY + combined EDIT-DAY-MONTH-YEAR):
    // supplied, numeric, range 1..31, then 31-in-a-30-day-month and Feb 30.
    // =====================================================================

    @ParameterizedTest(name = "[{index}] bad day \"{0}\"")
    @DisplayName("validateDateCcyyMmDd rejects day 0, > 31, and non-numeric with the day-range message")
    @ValueSource(strings = {"20241200", "20241232", "20241299", "202412XX"})
    void validateDateCcyyMmDd_dayOutOfRangeOrNonNumeric_throwsDayMessage(String input) {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd(input))
                .as("day of <%s> must be rejected", input)
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_DAY_RANGE);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"202412  \") treats an all-blank day as not supplied")
    void validateDateCcyyMmDd_dayAllSpaces_throwsDayNotSupplied() {
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("202412  "))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_DAY_NOT_SUPPLIED);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"20240431\") rejects day 31 in a 30-day month (April)")
    void validateDateCcyyMmDd_day31InThirtyDayMonth_throws31DaysMessage() {
        // April is not one of the WS-31-DAY-MONTH values, so day 31 is invalid.
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20240431"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_31_DAYS);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"20240230\") rejects February 30 with the 30-days message")
    void validateDateCcyyMmDd_feb30_throws30DaysMessage() {
        // February can never have 30 days, independent of the leap-year rule.
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20240230"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_30_DAYS);
    }

    // =====================================================================
    // Section E — leap-year rule (EDIT-DAY-MONTH-YEAR). February 29 is only
    // valid in a leap year; a century year must also be divisible by 400.
    // =====================================================================

    @Test
    @DisplayName("validateDateCcyyMmDd(\"20230229\") rejects Feb 29 in a non-leap year with the leap message")
    void validateDateCcyyMmDd_nonLeapFeb29_throwsWithLeapMessage() {
        // 2023 is not a leap year (not divisible by 4), so Feb 29 is invalid and
        // the verbatim leap-year literal is reported.
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20230229"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_LEAP_YEAR);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(\"19000229\") rejects Feb 29 in 1900 (century year not divisible by 400)")
    void validateDateCcyyMmDd_centuryNonLeapFeb29_throwsWithLeapMessage() {
        // 1900 is divisible by 100 but not 400 -> NOT a leap year under the COBOL
        // rule (divide by 400 when the year-within-century is 00). This also
        // exercises the valid 19xx-century path before the leap check.
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("19000229"))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(DEFAULT_PREFIX + MSG_LEAP_YEAR);
    }

    // =====================================================================
    // Section F — two-argument overload: the caller's field label is trimmed
    // (FUNCTION TRIM) and prefixed onto the message; a null label yields no
    // prefix. The valid path is identical to the single-argument overload.
    // =====================================================================

    @Test
    @DisplayName("validateDateCcyyMmDd(date, fieldName) prefixes the trimmed field name onto the message")
    void validateDateCcyyMmDd_withFieldName_messageIncludesField() {
        final String fieldName = "Date of birth";

        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20230229", fieldName))
                .isInstanceOf(DateValidationException.class)
                .hasMessageContaining(fieldName)
                .hasMessage(fieldName + MSG_LEAP_YEAR);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(date, null) emits the message with no field-name prefix")
    void validateDateCcyyMmDd_nullFieldName_noPrefix() {
        // A null field name maps to an empty prefix, so the raw CSUTLDPY literal
        // is emitted unchanged.
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20241301", null))
                .isInstanceOf(DateValidationException.class)
                .hasMessage(MSG_MONTH_RANGE);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(date, \"  padded  \") trims the field name before prefixing")
    void validateDateCcyyMmDd_fieldNameTrimmed_messageUsesTrimmed() {
        // The surrounding whitespace must be trimmed (FUNCTION TRIM) so the
        // message begins with the visible label, not spaces.
        assertThatThrownBy(() -> service.validateDateCcyyMmDd("20241301", "  Statement Date  "))
                .isInstanceOf(DateValidationException.class)
                .hasMessage("Statement Date" + MSG_MONTH_RANGE);
    }

    @Test
    @DisplayName("validateDateCcyyMmDd(date, fieldName) returns the parsed date on the valid path")
    void validateDateCcyyMmDd_withFieldNameValid_returnsDate() {
        assertThat(service.validateDateCcyyMmDd("20240229", "Date of birth"))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    // =====================================================================
    // Section G — non-throwing predicate wrapper isValidDateCcyyMmDd.
    // =====================================================================

    @Test
    @DisplayName("isValidDateCcyyMmDd returns true for valid, false for invalid, and is null-safe")
    void isValidDateCcyyMmDd_validAndInvalid() {
        assertThat(service.isValidDateCcyyMmDd("20241231")).isTrue();
        assertThat(service.isValidDateCcyyMmDd("20230229")).isFalse();
        // Null must be handled as invalid, never propagated as a NullPointerException.
        assertThat(service.isValidDateCcyyMmDd(null)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] isValid(\"{0}\") == {1}")
    @DisplayName("isValidDateCcyyMmDd mirrors the throwing validator across assorted inputs")
    @CsvSource({
        "20241231, true",
        "20240229, true",
        "20000229, true",
        "19991231, true",
        "20230229, false",
        "20241301, false",
        "20240431, false",
        "20240230, false",
        "18991231, false"
    })
    void isValidDateCcyyMmDd_matchesValidator(String input, boolean expectedValid) {
        assertThat(service.isValidDateCcyyMmDd(input))
                .as("isValidDateCcyyMmDd(<%s>) should be %s", input, expectedValid)
                .isEqualTo(expectedValid);
    }

    // =====================================================================
    // Section H — DateValidationException contract: HTTP 400, the offending
    // value is carried, no expected-format mask is set by this service, and
    // the exception is unchecked (RuntimeException).
    // =====================================================================

    @Test
    @DisplayName("A validation failure is an unchecked HTTP 400 that carries the offending value and no format mask")
    void validateDateCcyyMmDd_exceptionContract_isBadRequest() {
        final String badInput = "20241301";

        DateValidationException ex = assertThrows(
                DateValidationException.class,
                () -> service.validateDateCcyyMmDd(badInput));

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getHttpStatus().value()).isEqualTo(400);
        assertThat(ex.getInvalidValue()).isEqualTo(badInput);
        // This service uses the (message, invalidValue) constructor, so no
        // expected-format mask is attached.
        assertThat(ex.getExpectedFormat()).isNull();
    }
}
