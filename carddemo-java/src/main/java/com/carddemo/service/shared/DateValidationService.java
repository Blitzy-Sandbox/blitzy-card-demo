package com.carddemo.service.shared;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Validates a date string against a supplied picture format, replacing the COBOL
 * date-validation subprogram {@code CSUTLDTC} and its IBM Language Environment
 * {@code CEEDAYS} call (source commit {@code 27d6c6f}) with {@link java.time.LocalDate}
 * strict parsing.
 *
 * <p>The service mirrors the {@code CSUTLDTC} LINKAGE contract (date + format in,
 * structured result out) and returns a {@link DateValidationResult} rather than
 * throwing for ordinary invalid-date outcomes, preserving the COBOL
 * feedback-return contract consumed by {@code COTRN02C} and {@code CORPT00C}.
 *
 * <p>The result exposes the {@code severity} and {@code messageNumber} so callers
 * can reproduce the COBOL tolerance rule
 * ({@code SEV-CD = '0000'} OR {@code MSG-NUM = '2513'}); {@link DateValidationResult#isAcceptable()}
 * encapsulates that rule.
 */
@Service
public class DateValidationService {

    /** Severity reported for a valid date (CEEDAYS all-zero feedback token). */
    public static final int SEVERITY_VALID = 0;

    /** Severity reported for every invalid-date feedback condition. */
    public static final int SEVERITY_INVALID = 3;

    /** Message number for a valid date. */
    public static final int MSG_OK = 0;

    /** FC-INSUFFICIENT-DATA: the date value is empty or not the format width. */
    public static final int MSG_INSUFFICIENT_DATA = 2507;

    /** FC-BAD-DATE-VALUE: a syntactically formed but impossible calendar date. */
    public static final int MSG_BAD_DATE_VALUE = 2508;

    /** FC-INVALID-ERA. */
    public static final int MSG_INVALID_ERA = 2509;

    /** FC-UNSUPP-RANGE: a valid date earlier than the CEEDAYS Lillian epoch. */
    public static final int MSG_UNSUPPORTED_RANGE = 2513;

    /** FC-INVALID-MONTH. */
    public static final int MSG_INVALID_MONTH = 2517;

    /** FC-BAD-PIC-STRING: the supplied format argument is itself invalid. */
    public static final int MSG_BAD_PIC_STRING = 2518;

    /** FC-NON-NUMERIC-DATA: a numeric position of the date holds a non-digit. */
    public static final int MSG_NON_NUMERIC_DATA = 2520;

    /** FC-YEAR-IN-ERA-ZERO. */
    public static final int MSG_YEAR_IN_ERA_ZERO = 2521;

    /** Result text for a valid date. */
    public static final String RESULT_VALID = "Date is valid";

    /** Result text for FC-INSUFFICIENT-DATA. */
    public static final String RESULT_INSUFFICIENT = "Insufficient";

    /** Result text for FC-BAD-DATE-VALUE. */
    public static final String RESULT_BAD_DATE_VALUE = "Datevalue error";

    /** Result text for FC-UNSUPP-RANGE. */
    public static final String RESULT_UNSUPPORTED_RANGE = "Unsupp. Range";

    /** Result text for FC-BAD-PIC-STRING. */
    public static final String RESULT_BAD_PIC = "Bad Pic String";

    /** Result text for FC-NON-NUMERIC-DATA. */
    public static final String RESULT_NON_NUMERIC = "Nonnumeric data";

    /** Result text for the generic WHEN OTHER invalid-date condition. */
    public static final String RESULT_INVALID = "Date is invalid";

    /** Start of the CEEDAYS-supported Lillian range; dates before it yield FC-UNSUPP-RANGE. */
    private static final LocalDate LILLIAN_EPOCH = LocalDate.of(1582, 10, 15);

    /**
     * Validates {@code date} against {@code cobolFormat}, mirroring {@code CSUTLDTC}.
     *
     * <p>Conditions are evaluated in the same order CEEDAYS applies them: format
     * validity, then value presence and width, then strict calendar parsing, then
     * the Lillian-range check.
     *
     * @param date        the date value to validate (the original is echoed back in the result)
     * @param cobolFormat the COBOL picture format, e.g. {@code YYYY-MM-DD} or {@code YYYYMMDD}
     * @return a structured result carrying validity, severity, message number, and text
     */
    public DateValidationResult validateDate(String date, String cobolFormat) {
        String fmt = cobolFormat == null ? "" : cobolFormat.trim();
        String javaPattern = toJavaPattern(fmt);
        if (javaPattern == null) {
            return new DateValidationResult(false, SEVERITY_INVALID, MSG_BAD_PIC_STRING,
                    RESULT_BAD_PIC, date, cobolFormat);
        }
        String value = date == null ? "" : date.trim();
        if (value.isEmpty() || value.length() != fmt.length()) {
            return new DateValidationResult(false, SEVERITY_INVALID, MSG_INSUFFICIENT_DATA,
                    RESULT_INSUFFICIENT, date, cobolFormat);
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(javaPattern, Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
        try {
            LocalDate parsed = LocalDate.parse(value, formatter);
            if (parsed.isBefore(LILLIAN_EPOCH)) {
                return new DateValidationResult(false, SEVERITY_INVALID, MSG_UNSUPPORTED_RANGE,
                        RESULT_UNSUPPORTED_RANGE, date, cobolFormat);
            }
            return new DateValidationResult(true, SEVERITY_VALID, MSG_OK,
                    RESULT_VALID, date, cobolFormat);
        } catch (DateTimeParseException ex) {
            if (hasNonDigitInNumericPositions(value, fmt)) {
                return new DateValidationResult(false, SEVERITY_INVALID, MSG_NON_NUMERIC_DATA,
                        RESULT_NON_NUMERIC, date, cobolFormat);
            }
            return new DateValidationResult(false, SEVERITY_INVALID, MSG_BAD_DATE_VALUE,
                    RESULT_BAD_DATE_VALUE, date, cobolFormat);
        }
    }

    /**
     * Convenience predicate for strict validity.
     *
     * @param date        the date value to validate
     * @param cobolFormat the COBOL picture format
     * @return {@code true} only when the date is strictly valid (severity 0)
     */
    public boolean isValidDate(String date, String cobolFormat) {
        return validateDate(date, cobolFormat).valid();
    }

    /**
     * Translates a COBOL picture format into a {@link DateTimeFormatter} pattern,
     * using the year letter {@code u} (year) rather than {@code y} (year-of-era)
     * so strict parsing succeeds without an era.
     *
     * @param cobolFormat the trimmed COBOL format
     * @return the equivalent java.time pattern, or {@code null} if the format is invalid
     */
    private String toJavaPattern(String cobolFormat) {
        if (cobolFormat.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = cobolFormat.length();
        while (i < n) {
            char c = Character.toUpperCase(cobolFormat.charAt(i));
            if (c == 'Y') {
                int run = runLength(cobolFormat, i, 'Y');
                if (run == 4) {
                    sb.append("uuuu");
                } else if (run == 2) {
                    sb.append("uu");
                } else {
                    return null;
                }
                i += run;
            } else if (c == 'M') {
                int run = runLength(cobolFormat, i, 'M');
                if (run == 2) {
                    sb.append("MM");
                } else {
                    return null;
                }
                i += run;
            } else if (c == 'D') {
                int run = runLength(cobolFormat, i, 'D');
                if (run == 2) {
                    sb.append("dd");
                } else {
                    return null;
                }
                i += run;
            } else if (c == '-' || c == '/' || c == '.') {
                sb.append(c);
                i++;
            } else {
                return null;
            }
        }
        return sb.toString();
    }

    /**
     * Counts the consecutive run of {@code letter} (case-insensitive) starting at
     * {@code start}.
     *
     * @param s      the format string
     * @param start  the index at which the run begins
     * @param letter the pattern letter to count
     * @return the length of the run
     */
    private int runLength(String s, int start, char letter) {
        char up = Character.toUpperCase(letter);
        int i = start;
        while (i < s.length() && Character.toUpperCase(s.charAt(i)) == up) {
            i++;
        }
        return i - start;
    }

    /**
     * Reports whether any numeric position (a {@code Y}, {@code M}, or {@code D} slot
     * of the format) of {@code value} holds a non-digit. Safe because the caller
     * guarantees {@code value.length() == fmt.length()}.
     *
     * @param value the date value
     * @param fmt   the trimmed COBOL format of equal length
     * @return {@code true} if a numeric slot contains a non-digit
     */
    private boolean hasNonDigitInNumericPositions(String value, String fmt) {
        for (int i = 0; i < fmt.length(); i++) {
            char f = Character.toUpperCase(fmt.charAt(i));
            if ((f == 'Y' || f == 'M' || f == 'D') && !Character.isDigit(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Structured outcome of a date validation, mirroring the {@code CSUTLDTC}
     * result fields (severity, message number, and text) plus the echoed inputs.
     *
     * @param valid         {@code true} when the date is strictly valid
     * @param severity      0 for valid, otherwise 3
     * @param messageNumber the CEEDAYS feedback message number (0 when valid)
     * @param resultText    the human-readable result text
     * @param testDate      the original date argument
     * @param formatUsed    the original format argument
     */
    public record DateValidationResult(boolean valid, int severity, int messageNumber,
                                       String resultText, String testDate, String formatUsed) {

        /**
         * Encapsulates the COBOL consumer tolerance: a date is acceptable when it is
         * strictly valid, or when it failed only because it precedes the CEEDAYS
         * Lillian range (message {@code 2513}).
         *
         * @return {@code true} when valid or tolerated (message {@code 2513})
         */
        public boolean isAcceptable() {
            return valid || messageNumber == MSG_UNSUPPORTED_RANGE;
        }
    }
}
