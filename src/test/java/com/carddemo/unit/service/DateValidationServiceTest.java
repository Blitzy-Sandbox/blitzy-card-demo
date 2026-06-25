/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit test for {@link com.carddemo.service.DateValidationService},
 * the Java realization of the legacy COBOL date utility {@code app/cbl/CSUTLDTC.cbl} and the
 * validation copybook {@code app/cpy/CSUTLDPY.cpy} (consulted as read-only parity references at
 * source commit {@code 27d6c6f}; no COBOL is reproduced here).
 *
 * <p>The behaviour exercised here is the ordered {@code CCYYMMDD} edit cascade
 * (year &rarr; month &rarr; day &rarr; day-in-month) that the copybook performs through its
 * {@code EDIT-YEAR-CCYY}, {@code EDIT-MONTH}, {@code EDIT-DAY} and {@code EDIT-DAY-MONTH-YEAR}
 * paragraphs, plus the {@code EDIT-DATE-OF-BIRTH} not-in-the-future guard. Every rejection
 * message is asserted <strong>verbatim</strong> (including leading/trailing spaces and colon
 * placement) because byte-exact error text is a Gate&nbsp;1 / Gate&nbsp;4 parity requirement.
 *
 * <p>The only intentional deviation from the prose checklist is the {@code 2100-02-29} case:
 * the compiled service validates the century ({@code 19} or {@code 20} only) <em>before</em> the
 * leap-year day-in-month rule, so {@code 2100} is rejected with the century message rather than
 * the leap-year message. Compiled behaviour is authoritative, and this suite asserts it as such.
 *
 * <p>This suite touches no Spring context, Mockito mock, database, AWS service or network; the
 * service has no required collaborators. A {@link Clock#fixed(Instant, java.time.ZoneId) fixed
 * clock} is injected through the service's {@code Clock} constructor so that the future-date and
 * timestamp behaviours are fully deterministic.
 */
@DisplayName("DateValidationService: CCYYMMDD / leap-year / future-date parity (CSUTLDTC + CSUTLDPY)")
class DateValidationServiceTest {

    /** Deterministic "now" for the future-date guard and timestamp rendering: 2022-07-18 12:00:00 UTC. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T12:00:00Z");

    /** UTC-anchored fixed clock built from {@link #FIXED_INSTANT}. */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /** System-default-clock instance used for all date-shape validation (clock-independent). */
    private final DateValidationService service = new DateValidationService();

    /** Fixed-clock instance used where "current date/time" must be deterministic. */
    private final DateValidationService fixedClockService = new DateValidationService(FIXED_CLOCK);

    // ------------------------------------------------------------------------
    // Phase 1 — valid dates
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("validateCcyymmdd accepts the leap day 2024-02-29 and returns the parsed date")
    void validateCcyymmddAcceptsLeapDay() {
        assertThat(service.validateCcyymmdd("20240229", "Test Date"))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    @DisplayName("validateCcyymmdd accepts an ordinary date 2022-12-31 and returns the parsed date")
    void validateCcyymmddAcceptsOrdinaryDate() {
        assertThat(service.validateCcyymmdd("20221231", "Test Date"))
                .isEqualTo(LocalDate.of(2022, 12, 31));
    }

    @Test
    @DisplayName("validateDateParts accepts segmented year/month/day and returns the parsed date")
    void validateDatePartsAcceptsSegments() {
        assertThat(service.validateDateParts("1995", "06", "15", "Test Date"))
                .isEqualTo(LocalDate.of(1995, 6, 15));
    }

    // ------------------------------------------------------------------------
    // Phase 2 — leap-year matrix (critical)
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} -> valid={1}")
    @CsvSource({
            "20000229, true",   // divisible by 400 -> leap -> valid
            "20040229, true",   // divisible by 4   -> leap -> valid
            "20240229, true",   // divisible by 4   -> leap -> valid
            "19000229, false",  // divisible by 100 not 400 -> not leap -> invalid
            "21000229, false"   // century 21 rejected before the leap rule -> invalid
    })
    @DisplayName("Feb 29 is accepted only for a leap year within the accepted centuries (19/20)")
    void februaryTwentyNineClassification(String ccyymmdd, boolean expectedValid) {
        if (expectedValid) {
            int year = Integer.parseInt(ccyymmdd.substring(0, 4));
            assertThat(service.validateCcyymmdd(ccyymmdd, "Test Date"))
                    .isEqualTo(LocalDate.of(year, 2, 29));
        } else {
            assertThatThrownBy(() -> service.validateCcyymmdd(ccyymmdd, "Test Date"))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    @DisplayName("1900-02-29 is rejected with the leap-year message (century 19 valid; 1900 not a leap year)")
    void year1900Feb29YieldsLeapYearMessage() {
        assertThatThrownBy(() -> service.validateCcyymmdd("19000229", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date:Not a leap year.Cannot have 29 days in this month.");
    }

    @Test
    @DisplayName("2100-02-29 is rejected by the century check (compiled behavior: 19/20 only, before the leap rule)")
    void year2100Feb29YieldsCenturyMessage() {
        assertThatThrownBy(() -> service.validateCcyymmdd("21000229", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date : Century is not valid.");
    }

    // ------------------------------------------------------------------------
    // Phase 3 — field / range violations (verbatim messages)
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] year=\"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank year -> ' : Year must be supplied.'")
    void blankYearYieldsYearSuppliedMessage(String year) {
        assertThatThrownBy(() -> service.validateDateParts(year, "12", "31", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date : Year must be supplied.");
    }

    @ParameterizedTest(name = "[{index}] year=\"{0}\"")
    @ValueSource(strings = {"abcd", "123", "20A4"})
    @DisplayName("non-4-digit / non-numeric year -> ' must be 4 digit number.'")
    void nonFourDigitYearYieldsFourDigitMessage(String year) {
        assertThatThrownBy(() -> service.validateDateParts(year, "12", "31", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date must be 4 digit number.");
    }

    @ParameterizedTest(name = "[{index}] year={0}")
    @ValueSource(strings = {"1799", "2199"})
    @DisplayName("century outside 19/20 -> ' : Century is not valid.'")
    void invalidCenturyYieldsCenturyMessage(String year) {
        assertThatThrownBy(() -> service.validateDateParts(year, "06", "15", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date : Century is not valid.");
    }

    @ParameterizedTest(name = "[{index}] month=\"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank month -> ' : Month must be supplied.'")
    void blankMonthYieldsMonthSuppliedMessage(String month) {
        assertThatThrownBy(() -> service.validateDateParts("2024", month, "15", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date : Month must be supplied.");
    }

    @ParameterizedTest(name = "[{index}] month={0}")
    @ValueSource(strings = {"00", "13"})
    @DisplayName("month outside 1-12 -> ': Month must be a number between 1 and 12.'")
    void outOfRangeMonthYieldsMonthRangeMessage(String month) {
        assertThatThrownBy(() -> service.validateDateParts("2024", month, "15", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date: Month must be a number between 1 and 12.");
    }

    @ParameterizedTest(name = "[{index}] day=\"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank day -> ' : Day must be supplied.'")
    void blankDayYieldsDaySuppliedMessage(String day) {
        assertThatThrownBy(() -> service.validateDateParts("2024", "06", day, "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date : Day must be supplied.");
    }

    @ParameterizedTest(name = "[{index}] day={0}")
    @ValueSource(strings = {"00", "32"})
    @DisplayName("day outside 1-31 -> ':day must be a number between 1 and 31.'")
    void outOfRangeDayYieldsDayRangeMessage(String day) {
        assertThatThrownBy(() -> service.validateDateParts("2024", "06", day, "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date:day must be a number between 1 and 31.");
    }

    @Test
    @DisplayName("day 31 in a 30-day month (April 20240431) -> ':Cannot have 31 days in this month.'")
    void day31InThirtyDayMonthYieldsThirtyOneMessage() {
        assertThatThrownBy(() -> service.validateCcyymmdd("20240431", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date:Cannot have 31 days in this month.");
    }

    @Test
    @DisplayName("day 30 in February (20240230) -> ':Cannot have 30 days in this month.'")
    void day30InFebruaryYieldsThirtyMessage() {
        assertThatThrownBy(() -> service.validateCcyymmdd("20240230", "Test Date"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Test Date:Cannot have 30 days in this month.");
    }

    @Test
    @DisplayName("a validation failure carries a single per-field entry (fieldName -> full message)")
    void validationFailurePopulatesFieldErrorMap() {
        ValidationException ex =
                (ValidationException) catchThrowable(
                        () -> service.validateDateParts("", "12", "31", "Test Date"));

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("Test Date : Year must be supplied.");
        assertThat(ex.getFieldErrors())
                .containsEntry("Test Date", "Test Date : Year must be supplied.")
                .hasSize(1);
    }

    // ------------------------------------------------------------------------
    // Phase 4 — date-of-birth future check
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("validateDateOfBirth rejects a future date with ':cannot be in the future ' (trailing space)")
    void futureDateOfBirthIsRejected() {
        assertThatThrownBy(() -> fixedClockService.validateDateOfBirth("2030", "01", "01", "Date of Birth"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Date of Birth:cannot be in the future ");
    }

    @Test
    @DisplayName("validateDateOfBirth accepts a past date of birth (1990-01-01)")
    void pastDateOfBirthIsAccepted() {
        assertThatCode(() -> fixedClockService.validateDateOfBirth("1990", "01", "01", "Date of Birth"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateDateOfBirth(LocalDate) rejects a null value with ' : Year must be supplied.'")
    void nullDateOfBirthOverloadIsRejected() {
        LocalDate missing = null;
        assertThatThrownBy(() -> fixedClockService.validateDateOfBirth(missing, "Date of Birth"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Date of Birth : Year must be supplied.");
    }

    // ------------------------------------------------------------------------
    // Phase 5 — predicate, ISO parse, timestamp rendering
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] isValidDate(\"{0}\") == {1}")
    @CsvSource({
            "20240229, true",   // 2024 leap -> valid
            "20230229, false",  // 2023 not leap -> Feb 29 invalid
            "abcd, false"       // non-numeric -> invalid, never throws
    })
    @DisplayName("isValidDate is a non-throwing predicate")
    void isValidDatePredicate(String input, boolean expected) {
        assertThat(service.isValidDate(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("parseIsoDate strictly parses a YYYY-MM-DD value (2024-02-29 leap day)")
    void parseIsoDateParsesStrictIsoValue() {
        assertThat(service.parseIsoDate("2024-02-29"))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    @DisplayName("currentTimestamp() is 26 chars matching uuuu-MM-dd HH:mm:ss.SSSSSS")
    void currentTimestampHasFixedShapeAndLength() {
        String timestamp = service.currentTimestamp();

        assertThat(timestamp)
                .hasSize(26)
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}");
    }

    @Test
    @DisplayName("currentTimestamp() renders the injected fixed-clock instant exactly")
    void currentTimestampRendersFixedInstant() {
        assertThat(fixedClockService.currentTimestamp())
                .isEqualTo("2022-07-18 12:00:00.000000");
    }

    @Test
    @DisplayName("formatTimestamp renders a LocalDateTime as uuuu-MM-dd HH:mm:ss.SSSSSS with six fractional digits")
    void formatTimestampRendersSixFractionalDigits() {
        assertThat(service.formatTimestamp(LocalDateTime.of(2022, 7, 18, 12, 0, 0)))
                .isEqualTo("2022-07-18 12:00:00.000000");
    }
}
