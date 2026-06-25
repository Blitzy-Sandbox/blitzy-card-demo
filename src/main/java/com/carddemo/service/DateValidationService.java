/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import com.carddemo.exception.ValidationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

/**
 * Stateless, thread-safe service that validates calendar dates and renders
 * timestamps for the CardDemo domain.
 *
 * <p>Date validation enforces a four-digit {@code CCYYMMDD} value whose century
 * is {@code 19} or {@code 20}, whose month lies in {@code 1}&ndash;{@code 12},
 * whose day lies in {@code 1}&ndash;{@code 31}, and whose day is valid for the
 * supplied month and year (including the leap-year rule for 29 February). The
 * checks run in the fixed order year, month, day, then day-in-month; the first
 * failing check raises a {@link ValidationException} whose detail message and
 * single per-field entry both carry the field label followed by the exact
 * validation text.
 *
 * <p>Timestamp rendering produces the fixed twenty-six-character
 * {@code uuuu-MM-dd HH:mm:ss.SSSSSS} form with six fractional-second digits.
 *
 * <p>All methods are deterministic with respect to the {@link Clock} supplied
 * at construction time. The no-argument constructor binds the system
 * default-zone clock; an alternate constructor accepts a fixed clock so that
 * "current date" and "current timestamp" behaviour is reproducible under test.
 */
@Service
public class DateValidationService {

    private static final int LAST_CENTURY = 19;
    private static final int THIS_CENTURY = 20;
    private static final int FEBRUARY = 2;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;
    private static final int MIN_DAY = 1;
    private static final int MAX_DAY = 31;
    private static final int DAY_29 = 29;
    private static final int DAY_30 = 30;
    private static final int DAY_31 = 31;
    private static final int YEAR_DIGITS = 4;
    private static final int YEARS_PER_CENTURY = 100;
    private static final int LEAP_CYCLE = 4;
    private static final int CENTURY_LEAP_CYCLE = 400;

    private static final int CCYYMMDD_LENGTH = 8;
    private static final int CCYY_BEGIN = 0;
    private static final int CCYY_END = 4;
    private static final int MM_END = 6;
    private static final int DD_END = 8;

    private static final DateTimeFormatter ISO_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    private final Clock clock;

    /**
     * Creates a service bound to the system default-zone clock.
     */
    public DateValidationService() {
        this(Clock.systemDefaultZone());
    }

    /**
     * Creates a service bound to the supplied clock.
     *
     * @param clock the clock used to resolve the current date and time; must
     *              not be {@code null}
     */
    public DateValidationService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Validates an eight-character {@code CCYYMMDD} date string and returns the
     * corresponding date.
     *
     * <p>The value must be exactly eight characters long and contain only
     * digits; a {@code null}, shorter, longer, or non-numeric value is rejected
     * before the year, month, and day segments are split out. The eight-digit
     * value is then validated through {@link #validateDateParts}.
     *
     * @param ccyymmdd  the candidate date in {@code CCYYMMDD} form
     * @param fieldName the field label echoed in any validation message
     * @return the parsed {@link LocalDate}
     * @throws ValidationException if the value is not exactly eight digits or if
     *                             any ordered date check fails
     */
    public LocalDate validateCcyymmdd(String ccyymmdd, String fieldName) {
        String value = (ccyymmdd == null) ? "" : ccyymmdd;
        if (value.length() != CCYYMMDD_LENGTH || !isAllDigits(value, CCYYMMDD_LENGTH)) {
            throw dateError(fieldName, " must be an 8 digit CCYYMMDD value.");
        }
        String ccyy = value.substring(CCYY_BEGIN, CCYY_END);
        String mm = value.substring(CCYY_END, MM_END);
        String dd = value.substring(MM_END, DD_END);
        return validateDateParts(ccyy, mm, dd, fieldName);
    }

    /**
     * Validates a date supplied as separate year, month, and day segments and
     * returns the corresponding date.
     *
     * @param yyyy      the four-digit year segment
     * @param mm        the month segment
     * @param dd        the day segment
     * @param fieldName the field label echoed in any validation message
     * @return the parsed {@link LocalDate}
     * @throws ValidationException if any ordered check fails
     */
    public LocalDate validateDateParts(String yyyy, String mm, String dd, String fieldName) {
        int year = validateYear(yyyy, fieldName);
        int month = validateMonth(mm, fieldName);
        int day = validateDay(dd, fieldName);
        validateDayInMonth(year, month, day, fieldName);
        return LocalDate.of(year, month, day);
    }

    /**
     * Validates a date of birth supplied as year, month, and day segments and
     * rejects any value that is not strictly before the current date.
     *
     * @param yyyy      the four-digit year segment
     * @param mm        the month segment
     * @param dd        the day segment
     * @param fieldName the field label echoed in any validation message
     * @throws ValidationException if the date is invalid or not in the past
     */
    public void validateDateOfBirth(String yyyy, String mm, String dd, String fieldName) {
        LocalDate dateOfBirth = validateDateParts(yyyy, mm, dd, fieldName);
        rejectFutureDate(dateOfBirth, fieldName);
    }

    /**
     * Rejects a date of birth that is {@code null} or not strictly before the
     * current date.
     *
     * @param dateOfBirth the date of birth to check
     * @param fieldName   the field label echoed in any validation message
     * @throws ValidationException if the date is {@code null} or not in the past
     */
    public void validateDateOfBirth(LocalDate dateOfBirth, String fieldName) {
        if (dateOfBirth == null) {
            throw dateError(fieldName, " : Year must be supplied.");
        }
        rejectFutureDate(dateOfBirth, fieldName);
    }

    /**
     * Reports whether an eight-character {@code CCYYMMDD} string is a valid
     * date without raising an exception.
     *
     * @param ccyymmdd the candidate date in {@code CCYYMMDD} form
     * @return {@code true} if the value is a valid date; {@code false} otherwise
     */
    public boolean isValidDate(String ccyymmdd) {
        try {
            validateCcyymmdd(ccyymmdd, "");
            return true;
        } catch (ValidationException ex) {
            return false;
        }
    }

    /**
     * Strictly parses a hyphenated {@code uuuu-MM-dd} date string.
     *
     * @param yyyyMmDd the candidate date in {@code YYYY-MM-DD} form
     * @return the parsed {@link LocalDate}
     * @throws ValidationException if the value is blank or not a valid date
     */
    public LocalDate parseIsoDate(String yyyyMmDd) {
        if (isBlank(yyyyMmDd)) {
            throw new ValidationException("Date must be supplied.");
        }
        String trimmed = yyyyMmDd.trim();
        try {
            return LocalDate.parse(trimmed, ISO_DATE_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new ValidationException("Invalid date: " + trimmed + ".", ex);
        }
    }

    /**
     * Returns the current instant formatted as
     * {@code uuuu-MM-dd HH:mm:ss.SSSSSS}.
     *
     * @return a twenty-six-character timestamp string
     */
    public String currentTimestamp() {
        return LocalDateTime.now(clock).format(TIMESTAMP_FORMATTER);
    }

    /**
     * Formats the supplied instant as {@code uuuu-MM-dd HH:mm:ss.SSSSSS}.
     *
     * @param timestamp the instant to format; must not be {@code null}
     * @return a twenty-six-character timestamp string
     */
    public String formatTimestamp(LocalDateTime timestamp) {
        return Objects.requireNonNull(timestamp, "timestamp must not be null")
                .format(TIMESTAMP_FORMATTER);
    }

    private int validateYear(String ccyy, String fieldName) {
        if (isBlank(ccyy)) {
            throw dateError(fieldName, " : Year must be supplied.");
        }
        String trimmed = ccyy.trim();
        if (!isAllDigits(trimmed, YEAR_DIGITS)) {
            throw dateError(fieldName, " must be 4 digit number.");
        }
        int century = (trimmed.charAt(0) - '0') * 10 + (trimmed.charAt(1) - '0');
        if (century != THIS_CENTURY && century != LAST_CENTURY) {
            throw dateError(fieldName, " : Century is not valid.");
        }
        return Integer.parseInt(trimmed);
    }

    private int validateMonth(String mm, String fieldName) {
        if (isBlank(mm)) {
            throw dateError(fieldName, " : Month must be supplied.");
        }
        Integer month = parseNumeric(mm);
        if (month == null || month < MIN_MONTH || month > MAX_MONTH) {
            throw dateError(fieldName, ": Month must be a number between 1 and 12.");
        }
        return month;
    }

    private int validateDay(String dd, String fieldName) {
        if (isBlank(dd)) {
            throw dateError(fieldName, " : Day must be supplied.");
        }
        Integer day = parseNumeric(dd);
        if (day == null || day < MIN_DAY || day > MAX_DAY) {
            throw dateError(fieldName, ":day must be a number between 1 and 31.");
        }
        return day;
    }

    private void validateDayInMonth(int year, int month, int day, String fieldName) {
        if (!isThirtyOneDayMonth(month) && day == DAY_31) {
            throw dateError(fieldName, ":Cannot have 31 days in this month.");
        }
        if (month == FEBRUARY && day == DAY_30) {
            throw dateError(fieldName, ":Cannot have 30 days in this month.");
        }
        if (month == FEBRUARY && day == DAY_29 && !isLeapYear(year)) {
            throw dateError(fieldName, ":Not a leap year.Cannot have 29 days in this month.");
        }
    }

    private void rejectFutureDate(LocalDate date, String fieldName) {
        if (!date.isBefore(LocalDate.now(clock))) {
            throw dateError(fieldName, ":cannot be in the future ");
        }
    }

    private static boolean isThirtyOneDayMonth(int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> true;
            default -> false;
        };
    }

    private static boolean isLeapYear(int ccyy) {
        int yearWithinCentury = ccyy % YEARS_PER_CENTURY;
        if (yearWithinCentury == 0) {
            return ccyy % CENTURY_LEAP_CYCLE == 0;
        }
        return ccyy % LEAP_CYCLE == 0;
    }

    private static ValidationException dateError(String fieldName, String message) {
        String label = (fieldName == null) ? "" : fieldName;
        String fullMessage = label.trim() + message;
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put(label, fullMessage);
        return new ValidationException(fullMessage, fieldErrors);
    }

    private static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String value, int requiredLength) {
        if (value.length() != requiredLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static Integer parseNumeric(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
