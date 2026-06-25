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

import com.carddemo.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DateValidationService}.
 *
 * <p>The service is exercised as a plain POJO bound to a fixed clock at
 * {@code 2022-07-18} so that current-date and current-timestamp behavior is
 * reproducible. The {@code CCYYMMDD} contract is fixed at exactly eight digits,
 * mirroring the legacy {@code CSUTLDPY} edit routine whose
 * {@code WS-EDIT-DATE-CCYYMMDD} field is a fixed eight-byte structure; the tests
 * therefore assert that too-short, too-long, and non-numeric values are rejected
 * before the year, month, and day segments are split out.
 */
@DisplayName("DateValidationService — CCYYMMDD edit, leap-year, and future-date parity")
class DateValidationServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T00:00:00Z"), ZoneOffset.UTC);

    private DateValidationService service;

    @BeforeEach
    void setUp() {
        service = new DateValidationService(FIXED_CLOCK);
    }

    @Nested
    @DisplayName("validateCcyymmdd / isValidDate — eight-digit contract")
    class EightDigitContract {

        @Test
        @DisplayName("accepts a valid eight-digit date and returns the parsed value")
        void acceptsValidDate() {
            assertThat(service.validateCcyymmdd("20240229", "Date"))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
            assertThat(service.isValidDate("20240229")).isTrue();
        }

        @Test
        @DisplayName("accepts a twentieth-century date")
        void acceptsTwentiethCenturyDate() {
            assertThat(service.validateCcyymmdd("19991231", "Date"))
                    .isEqualTo(LocalDate.of(1999, 12, 31));
            assertThat(service.isValidDate("19991231")).isTrue();
        }

        @Test
        @DisplayName("rejects a too-short value")
        void rejectsTooShort() {
            assertThatThrownBy(() -> service.validateCcyymmdd("2024022", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("8 digit CCYYMMDD");
            assertThat(service.isValidDate("2024022")).isFalse();
        }

        @ParameterizedTest(name = "rejects over-length value \"{0}\"")
        @ValueSource(strings = {"20240229JUNK", "202402290", "20240229 "})
        @DisplayName("rejects over-length values (the prefix-slice defect)")
        void rejectsTooLong(String value) {
            assertThatThrownBy(() -> service.validateCcyymmdd(value, "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("8 digit CCYYMMDD");
            assertThat(service.isValidDate(value)).isFalse();
        }

        @ParameterizedTest(name = "rejects non-numeric value \"{0}\"")
        @ValueSource(strings = {"2024XX29", "20240A29", "2024 229", "abcdefgh"})
        @DisplayName("rejects eight-character values that are not all digits")
        void rejectsNonNumeric(String value) {
            assertThatThrownBy(() -> service.validateCcyymmdd(value, "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("8 digit CCYYMMDD");
            assertThat(service.isValidDate(value)).isFalse();
        }

        @Test
        @DisplayName("rejects null and empty input")
        void rejectsNullAndEmpty() {
            assertThatThrownBy(() -> service.validateCcyymmdd(null, "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("8 digit CCYYMMDD");
            assertThatThrownBy(() -> service.validateCcyymmdd("", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("8 digit CCYYMMDD");
            assertThat(service.isValidDate(null)).isFalse();
            assertThat(service.isValidDate("")).isFalse();
        }

        @Test
        @DisplayName("populates a single per-field error entry on failure")
        void populatesFieldError() {
            assertThatThrownBy(() -> service.validateCcyymmdd("20240229JUNK", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> assertThat(((ValidationException) ex).getFieldErrors())
                            .containsKey("Date"));
        }
    }

    @Nested
    @DisplayName("validateCcyymmdd — calendar semantics (CSUTLDPY parity)")
    class CalendarSemantics {

        @Test
        @DisplayName("rejects an invalid century")
        void rejectsInvalidCentury() {
            assertThatThrownBy(() -> service.validateCcyymmdd("18240101", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Century is not valid");
        }

        @ParameterizedTest(name = "rejects out-of-range month in \"{0}\"")
        @ValueSource(strings = {"20241301", "20240001"})
        @DisplayName("rejects a month outside 1..12")
        void rejectsInvalidMonth(String value) {
            assertThatThrownBy(() -> service.validateCcyymmdd(value, "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Month must be a number between 1 and 12");
        }

        @ParameterizedTest(name = "rejects out-of-range day in \"{0}\"")
        @ValueSource(strings = {"20240132", "20240100"})
        @DisplayName("rejects a day outside 1..31")
        void rejectsInvalidDay(String value) {
            assertThatThrownBy(() -> service.validateCcyymmdd(value, "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("day must be a number between 1 and 31");
        }

        @Test
        @DisplayName("rejects 31 days in a 30-day month")
        void rejectsThirtyFirstInThirtyDayMonth() {
            assertThatThrownBy(() -> service.validateCcyymmdd("20240431", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Cannot have 31 days in this month");
        }

        @Test
        @DisplayName("rejects 30 days in February")
        void rejectsThirtyInFebruary() {
            assertThatThrownBy(() -> service.validateCcyymmdd("20240230", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Cannot have 30 days in this month");
        }

        @Test
        @DisplayName("rejects 29 February in a non-leap year")
        void rejectsLeapDayInNonLeapYear() {
            assertThatThrownBy(() -> service.validateCcyymmdd("20230229", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Not a leap year");
            assertThat(service.isValidDate("20230229")).isFalse();
        }

        @Test
        @DisplayName("accepts 28 February in a non-leap year")
        void acceptsLastFebruaryDayInNonLeapYear() {
            assertThat(service.validateCcyymmdd("20230228", "Date"))
                    .isEqualTo(LocalDate.of(2023, 2, 28));
        }
    }

    @Nested
    @DisplayName("validateDateParts — segment overload")
    class SegmentOverload {

        @Test
        @DisplayName("validates separate year, month, and day segments")
        void validatesSeparateSegments() {
            assertThat(service.validateDateParts("2024", "02", "29", "Date"))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("rejects a non-numeric month segment")
        void rejectsNonNumericMonthSegment() {
            assertThatThrownBy(() -> service.validateDateParts("2024", "XX", "01", "Date"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Month must be a number between 1 and 12");
        }
    }

    @Nested
    @DisplayName("validateDateOfBirth — future-date rejection")
    class FutureDateRejection {

        @Test
        @DisplayName("accepts a date strictly before the current date")
        void acceptsPastDate() {
            assertThatCode(() -> service.validateDateOfBirth("2000", "01", "01", "DOB"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> service.validateDateOfBirth(LocalDate.of(2022, 7, 17), "DOB"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects the current date (not strictly in the past)")
        void rejectsCurrentDate() {
            assertThatThrownBy(() -> service.validateDateOfBirth("2022", "07", "18", "DOB"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("cannot be in the future");
        }

        @Test
        @DisplayName("rejects a future date")
        void rejectsFutureDate() {
            assertThatThrownBy(() -> service.validateDateOfBirth("2030", "01", "01", "DOB"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("cannot be in the future");
            assertThatThrownBy(() -> service.validateDateOfBirth(LocalDate.of(2030, 1, 1), "DOB"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("cannot be in the future");
        }

        @Test
        @DisplayName("rejects a null date-of-birth value")
        void rejectsNullDateOfBirth() {
            assertThatThrownBy(() -> service.validateDateOfBirth(null, "DOB"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Year must be supplied");
        }
    }

    @Nested
    @DisplayName("parseIsoDate — strict uuuu-MM-dd parsing")
    class IsoDateParsing {

        @Test
        @DisplayName("parses a valid hyphenated date")
        void parsesValidIsoDate() {
            assertThat(service.parseIsoDate("2024-02-29")).isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("trims surrounding whitespace before parsing")
        void trimsWhitespace() {
            assertThat(service.parseIsoDate("  2024-02-29  ")).isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("rejects blank and null input")
        void rejectsBlank() {
            assertThatThrownBy(() -> service.parseIsoDate(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Date must be supplied");
            assertThatThrownBy(() -> service.parseIsoDate("   "))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Date must be supplied");
        }

        @ParameterizedTest(name = "rejects invalid ISO date \"{0}\"")
        @ValueSource(strings = {"2023-02-29", "2024/02/29", "not-a-date"})
        @DisplayName("rejects strictly invalid or malformed ISO dates")
        void rejectsInvalidIsoDate(String value) {
            assertThatThrownBy(() -> service.parseIsoDate(value))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Invalid date");
        }
    }

    @Nested
    @DisplayName("timestamp rendering — fixed 26-character format")
    class TimestampRendering {

        @Test
        @DisplayName("currentTimestamp renders the clock instant with six fractional digits")
        void currentTimestampFormat() {
            String timestamp = service.currentTimestamp();
            assertThat(timestamp).hasSize(26);
            assertThat(timestamp).isEqualTo("2022-07-18 00:00:00.000000");
        }

        @Test
        @DisplayName("formatTimestamp renders microsecond precision")
        void formatTimestampFormat() {
            LocalDateTime moment = LocalDateTime.of(2022, 7, 18, 13, 45, 30, 123_456_000);
            assertThat(service.formatTimestamp(moment)).isEqualTo("2022-07-18 13:45:30.123456");
        }

        @Test
        @DisplayName("formatTimestamp rejects a null instant")
        void formatTimestampRejectsNull() {
            assertThatThrownBy(() -> service.formatTimestamp(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("the no-argument constructor binds a usable clock")
        void noArgConstructor() {
            assertThatCode(() -> new DateValidationService().currentTimestamp())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a null clock")
        void rejectsNullClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateValidationService(null))
                    .withMessageContaining("clock must not be null");
        }
    }
}
