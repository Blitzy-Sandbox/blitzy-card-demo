package com.carddemo.service;

import java.time.DateTimeException;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.exception.DateValidationException;

/**
 * Validates {@code CCYYMMDD} calendar dates, the Java replacement for the
 * Language Environment {@code CEEDAYS} date-check utility.
 *
 * <p>This service migrates the COBOL date-validation surface made up of program
 * {@code CSUTLDTC} (the {@code CALL "CEEDAYS"} wrapper) and its two companion
 * edit copybooks: {@code CSUTLDPY} (the reusable {@code EDIT-DATE-CCYYMMDD}
 * procedure and its message literals) and {@code CSUTLDWY} (the date work-area
 * definitions, including the {@code THIS-CENTURY}/{@code LAST-CENTURY},
 * {@code WS-VALID-MONTH}, {@code WS-VALID-DAY} and {@code WS-31-DAY-MONTH}
 * condition names). Per the migration mapping, "LE {@code CEEDAYS} date
 * validation" becomes {@link java.time.LocalDate} plus these custom validators.
 * Frozen COBOL reference SHA {@code 27d6c6f}.
 *
 * <h2>Preserved behaviour</h2>
 * The COBOL {@code EDIT-DATE-CCYYMMDD} paragraph range validates a date in a
 * fixed order &mdash; year, then month, then day, then combined day-in-month
 * and leap-year rules &mdash; and reports only the <em>first</em> failure it
 * encounters (later checks are suppressed by the {@code WS-RETURN-MSG-OFF}
 * guard). This service reproduces that exact ordering and first-error semantics
 * by throwing on the first failed check. The human-readable message text is kept
 * byte-for-byte identical to the {@code CSUTLDPY} literals (interface-contract
 * parity, migration goal G4): each message is the trimmed caller-supplied field
 * name followed by the COBOL literal, mirroring
 * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) '<literal>'}.
 *
 * <h2>Century rule</h2>
 * Faithful to {@code CSUTLDWY}, only the 20th ({@code 19xx}) and 21st
 * ({@code 20xx}) centuries are accepted; any other century component is
 * rejected exactly as the legacy program did.
 *
 * <h2>Failure handling</h2>
 * Every validation failure raises a {@link DateValidationException} (HTTP 400)
 * carrying the offending input for diagnostics. No monetary arithmetic is
 * involved, so no {@code float}/{@code double} appears here; all numeric work is
 * integer based. The service is stateless and thread-safe, and it never logs the
 * raw input value beyond what is already surfaced on the exception.
 */
@Service
public class DateValidationService {

    /** Logger; only field names and static message text are logged, never raw date input. */
    private static final Logger log = LoggerFactory.getLogger(DateValidationService.class);

    /** Expected fixed length of the canonical COBOL {@code CCYYMMDD} date string. */
    private static final int CCYYMMDD_LENGTH = 8;

    /** Default field label used by the single-argument overload. */
    private static final String DEFAULT_FIELD_NAME = "Date";

    // ---------------------------------------------------------------------
    // Message literals — reproduced verbatim from copybook CSUTLDPY so the
    // externally visible text remains byte-identical to the legacy system.
    // Each is concatenated after the trimmed field name, exactly as the COBOL
    // STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) '<literal>' does.
    // ---------------------------------------------------------------------

    /** {@code CSUTLDPY} year-not-supplied literal. */
    private static final String MSG_YEAR_NOT_SUPPLIED = " : Year must be supplied.";

    /** {@code CSUTLDPY} year-not-numeric literal. */
    private static final String MSG_YEAR_NOT_NUMERIC = " must be 4 digit number.";

    /** {@code CSUTLDPY} century-not-valid literal. */
    private static final String MSG_CENTURY_INVALID = " : Century is not valid.";

    /** {@code CSUTLDPY} month-not-supplied literal. */
    private static final String MSG_MONTH_NOT_SUPPLIED = " : Month must be supplied.";

    /** {@code CSUTLDPY} month-out-of-range / non-numeric literal. */
    private static final String MSG_MONTH_RANGE = ": Month must be a number between 1 and 12.";

    /** {@code CSUTLDPY} day-not-supplied literal. */
    private static final String MSG_DAY_NOT_SUPPLIED = " : Day must be supplied.";

    /** {@code CSUTLDPY} day-out-of-range / non-numeric literal. */
    private static final String MSG_DAY_RANGE = ":day must be a number between 1 and 31.";

    /** {@code CSUTLDPY} thirty-one-days-in-month literal. */
    private static final String MSG_31_DAYS = ":Cannot have 31 days in this month.";

    /** {@code CSUTLDPY} thirty-days-in-February literal. */
    private static final String MSG_30_DAYS = ":Cannot have 30 days in this month.";

    /** {@code CSUTLDPY} leap-year literal for a February 29 in a non-leap year. */
    private static final String MSG_LEAP_YEAR = ":Not a leap year.Cannot have 29 days in this month.";

    /**
     * Creates the stateless date-validation service. No initialization is
     * required; all validation state is local to each call.
     */
    public DateValidationService() {
        // Intentionally empty: this service holds no mutable state.
    }

    /**
     * Validates a {@code CCYYMMDD} date using the default field label
     * ({@code "Date"}) in any error message.
     *
     * <p>Convenience overload of {@link #validateDateCcyyMmDd(String, String)}.
     *
     * @param dateString the 8-character {@code CCYYMMDD} date to validate; may be
     *                   {@code null}
     * @return the parsed {@link LocalDate} when the input is a valid date
     * @throws DateValidationException if the input is missing, malformed, or not
     *                                 a valid calendar date
     */
    public LocalDate validateDateCcyyMmDd(String dateString) {
        return validateDateCcyyMmDd(dateString, DEFAULT_FIELD_NAME);
    }

    /**
     * Validates a {@code CCYYMMDD} date, reproducing the ordered edits of the
     * COBOL {@code EDIT-DATE-CCYYMMDD} procedure.
     *
     * <p>The checks run in the legacy order &mdash; year (supplied, numeric,
     * century), month (supplied, numeric, 1&ndash;12), day (supplied, numeric,
     * 1&ndash;31), then the combined day-in-month and leap-year rules &mdash; and
     * the first failure is reported, matching the COBOL first-error semantics.
     * The {@code fieldName} is trimmed and prefixed onto the message exactly like
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} in the source.
     *
     * @param dateString the 8-character {@code CCYYMMDD} date to validate; may be
     *                   {@code null}
     * @param fieldName  the caller's label for the field being validated (for
     *                   example {@code "Date of birth"}); may be {@code null},
     *                   in which case no label is prefixed
     * @return the parsed {@link LocalDate} when the input is a valid date
     * @throws DateValidationException if the input is missing, malformed, or not
     *                                 a valid calendar date
     */
    public LocalDate validateDateCcyyMmDd(String dateString, String fieldName) {
        // COBOL FUNCTION TRIM(WS-EDIT-VARIABLE-NAME); guard a null label to "".
        final String prefix = (fieldName == null) ? "" : fieldName.trim();

        // Guard null / wrong length first: without a full CCYYMMDD field the year
        // cannot be present, which the legacy edit reports as "Year must be
        // supplied." (EDIT-YEAR-CCYY, SPACES/LOW-VALUES branch).
        if (dateString == null || dateString.length() != CCYYMMDD_LENGTH) {
            throw failure(prefix, MSG_YEAR_NOT_SUPPLIED, dateString);
        }

        final String ccyy = dateString.substring(0, 4);
        final String mm = dateString.substring(4, 6);
        final String dd = dateString.substring(6, 8);

        // ---- EDIT-YEAR-CCYY -------------------------------------------------
        // Not supplied (SPACES or LOW-VALUES).
        if (!isFieldSupplied(ccyy)) {
            throw failure(prefix, MSG_YEAR_NOT_SUPPLIED, dateString);
        }
        // Not numeric -> "must be 4 digit number."
        if (!isAllDigits(ccyy)) {
            throw failure(prefix, MSG_YEAR_NOT_NUMERIC, dateString);
        }
        final int year = Integer.parseInt(ccyy);
        // Century reasonableness: only 19xx (LAST-CENTURY) and 20xx (THIS-CENTURY).
        final int century = Integer.parseInt(ccyy.substring(0, 2));
        if (century != 19 && century != 20) {
            throw failure(prefix, MSG_CENTURY_INVALID, dateString);
        }

        // ---- EDIT-MONTH -----------------------------------------------------
        if (!isFieldSupplied(mm)) {
            throw failure(prefix, MSG_MONTH_NOT_SUPPLIED, dateString);
        }
        // Numeric check before range check (both report the same COBOL message).
        if (!isAllDigits(mm)) {
            throw failure(prefix, MSG_MONTH_RANGE, dateString);
        }
        final int month = Integer.parseInt(mm);
        if (month < 1 || month > 12) {
            throw failure(prefix, MSG_MONTH_RANGE, dateString);
        }

        // ---- EDIT-DAY -------------------------------------------------------
        if (!isFieldSupplied(dd)) {
            throw failure(prefix, MSG_DAY_NOT_SUPPLIED, dateString);
        }
        if (!isAllDigits(dd)) {
            throw failure(prefix, MSG_DAY_RANGE, dateString);
        }
        final int day = Integer.parseInt(dd);
        if (day < 1 || day > 31) {
            throw failure(prefix, MSG_DAY_RANGE, dateString);
        }

        // ---- EDIT-DAY-MONTH-YEAR (combined day-in-month / leap rules) -------
        // Day 31 in a month that does not have 31 days.
        if (!isThirtyOneDayMonth(month) && day == 31) {
            throw failure(prefix, MSG_31_DAYS, dateString);
        }
        // February can never have 30 days.
        if (month == 2 && day == 30) {
            throw failure(prefix, MSG_30_DAYS, dateString);
        }
        // February 29 is only valid in a leap year.
        if (month == 2 && day == 29 && !isLeapYear(year)) {
            throw failure(prefix, MSG_LEAP_YEAR, dateString);
        }

        // ---- EDIT-DATE-LE (the CEEDAYS call) --------------------------------
        // Final construction stands in for the COBOL "use LE services to verify"
        // safety net. Every value that clears the edits above already forms a
        // valid calendar date, so this branch is not reached in normal
        // operation; it defends against any residual impossible day-in-month
        // combination, mirroring CSUTLDPY's belt-and-suspenders EDIT-DATE-LE.
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException ex) {
            log.debug("Calendar construction failed for field '{}': {}", prefix, ex.getClass().getSimpleName());
            throw failure(prefix, MSG_DAY_RANGE, dateString);
        }
    }

    /**
     * Reports whether a {@code CCYYMMDD} date is valid without throwing.
     *
     * <p>Boolean wrapper over {@link #validateDateCcyyMmDd(String)} for callers
     * (such as Jakarta validators) that need a predicate rather than the parsed
     * value.
     *
     * @param dateString the 8-character {@code CCYYMMDD} date to test; may be
     *                   {@code null}
     * @return {@code true} if the input is a valid date, {@code false} otherwise
     */
    public boolean isValidDateCcyyMmDd(String dateString) {
        try {
            validateDateCcyyMmDd(dateString);
            return true;
        } catch (DateValidationException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    /**
     * Builds (and logs at debug) the {@link DateValidationException} for a failed
     * edit. The caller raises the returned instance with {@code throw}, which
     * keeps the control flow explicit for the compiler.
     *
     * <p>Only the field name and the static message text are logged; the raw
     * input value is carried on the exception (for {@code getInvalidValue()}) but
     * is not logged.
     *
     * @param prefix     the trimmed field label to prepend to the message
     * @param message    the verbatim {@code CSUTLDPY} message literal
     * @param dateString the offending input value carried on the exception
     * @return the exception to throw
     */
    private DateValidationException failure(String prefix, String message, String dateString) {
        log.debug("Date validation failed for field '{}':{}", prefix, message);
        return new DateValidationException(prefix + message, dateString);
    }

    /**
     * Determines whether a fixed-width field is "supplied" in COBOL terms, that
     * is, it contains at least one character that is neither a space nor a
     * {@code LOW-VALUES} ({@code x'00'}) byte.
     *
     * @param field the field text to test; never {@code null} here
     * @return {@code true} if the field carries a meaningful value
     */
    private static boolean isFieldSupplied(String field) {
        for (int i = 0; i < field.length(); i++) {
            final char c = field.charAt(i);
            if (c != ' ' && c != '\u0000') {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether every character of the given text is an ASCII digit,
     * reproducing the COBOL {@code IS NUMERIC} / {@code TEST-NUMVAL} class test
     * for the display-numeric date components.
     *
     * @param text the text to test; never {@code null} here
     * @return {@code true} if {@code text} is non-empty and all digits
     */
    private static boolean isAllDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether the given month is one of the 31-day months, matching the
     * {@code WS-31-DAY-MONTH} condition name ({@code 1, 3, 5, 7, 8, 10, 12}) in
     * copybook {@code CSUTLDWY}.
     *
     * @param month the month number (1&ndash;12)
     * @return {@code true} for January, March, May, July, August, October and December
     */
    private static boolean isThirtyOneDayMonth(int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> true;
            default -> false;
        };
    }

    /**
     * Applies the leap-year rule exactly as coded in {@code CSUTLDPY}
     * {@code EDIT-DAY-MONTH-YEAR}: when the two-digit year-within-century is
     * {@code 00} the year is divided by 400, otherwise by 4, and the year is a
     * leap year when the remainder is zero. This reproduces the proleptic
     * Gregorian rule the legacy program relied on.
     *
     * @param year the full four-digit year
     * @return {@code true} if {@code year} is a leap year under the COBOL rule
     */
    private static boolean isLeapYear(int year) {
        final int yearWithinCentury = year % 100;
        final int divisor = (yearWithinCentury == 0) ? 400 : 4;
        return (year % divisor) == 0;
    }
}
