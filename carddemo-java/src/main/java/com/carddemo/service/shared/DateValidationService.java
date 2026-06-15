package com.carddemo.service.shared;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Validates a date string against a supplied COBOL picture format, replacing the
 * mainframe subprogram {@code CSUTLDTC} and its IBM Language Environment
 * {@code CEEDAYS} call (source commit {@code 27d6c6f}) with
 * {@link java.time.LocalDate} strict parsing.
 *
 * <p>The service returns a structured {@link DateValidationResult} (valid flag,
 * severity, message number, and result text) rather than throwing for ordinary
 * invalid-date outcomes, preserving the COBOL feedback-return contract. Only
 * programming misuse (never bad user input) would surface an exception.</p>
 *
 * <p>Severity, message-number, and result-text constants mirror the
 * {@code CEEDAYS} feedback tokens decoded by {@code CSUTLDTC}. Online callers
 * such as {@code COTRN02C} and {@code CORPT00C} treat a result as acceptable when
 * the severity is zero or the message number is the "unsupported range" code
 * ({@value #MSG_UNSUPPORTED_RANGE}); {@link DateValidationResult#isAcceptable()}
 * encapsulates that tolerance.</p>
 */
@Service
public class DateValidationService {

    /** Severity reported for a valid date (LE all-zero feedback token). */
    public static final int SEVERITY_VALID = 0;

    /** Severity reported for any invalid or out-of-range date. */
    public static final int SEVERITY_INVALID = 3;

    /** Message number for a valid date (all-zero feedback token). */
    public static final int MSG_OK = 0;

    /** Message number for insufficient/empty/short date data (FC-INSUFFICIENT-DATA). */
    public static final int MSG_INSUFFICIENT_DATA = 2507;

    /** Message number for an impossible calendar date value (FC-BAD-DATE-VALUE). */
    public static final int MSG_BAD_DATE_VALUE = 2508;

    /** Message number for an invalid era (FC-INVALID-ERA). */
    public static final int MSG_INVALID_ERA = 2509;

    /** Message number for a syntactically valid date outside the supported range (FC-UNSUPP-RANGE). */
    public static final int MSG_UNSUPPORTED_RANGE = 2513;

    /** Message number for an invalid month (FC-INVALID-MONTH). */
    public static final int MSG_INVALID_MONTH = 2517;

    /** Message number for an invalid format/picture string argument (FC-BAD-PIC-STRING). */
    public static final int MSG_BAD_PIC_STRING = 2518;

    /** Message number for non-numeric characters in numeric date positions (FC-NON-NUMERIC-DATA). */
    public static final int MSG_NON_NUMERIC_DATA = 2520;

    /** Message number for a zero year-in-era (FC-YEAR-IN-ERA-ZERO). */
    public static final int MSG_YEAR_IN_ERA_ZERO = 2521;

    /** Result text for a valid date. */
    public static final String RESULT_VALID = "Date is valid";

    /** Result text for insufficient date data. */
    public static final String RESULT_INSUFFICIENT = "Insufficient";

    /** Result text for an impossible calendar date value. */
    public static final String RESULT_BAD_DATE_VALUE = "Datevalue error";

    /** Result text for a date outside the supported range. */
    public static final String RESULT_UNSUPPORTED_RANGE = "Unsupp. Range";

    /** Result text for an invalid format/picture string. */
    public static final String RESULT_BAD_PIC = "Bad Pic String";

    /** Result text for non-numeric data in numeric positions. */
    public static final String RESULT_NON_NUMERIC = "Nonnumeric data";

    /** Result text for the generic invalid-date fallback (CSUTLDTC WHEN OTHER). */
    public static final String RESULT_INVALID = "Date is invalid";

    /**
     * Start of the {@code CEEDAYS}-supported Lillian date range (1582-10-15).
     * Dates that parse correctly but fall before this boundary reproduce the
     * FC-UNSUPP-RANGE ({@value #MSG_UNSUPPORTED_RANGE}) feedback outcome.
     */
    private static final LocalDate LILLIAN_EPOCH = LocalDate.of(1582, 10, 15);

    /**
     * Creates the stateless date-validation service. The component holds no
     * mutable state and is safe for concurrent use.
     */
    public DateValidationService() {
    }

    /**
     * Validates {@code date} against {@code cobolFormat}, mirroring the
     * {@code CSUTLDTC} LINKAGE (date + format in, structured result out).
     * Conditions are evaluated in the same short-circuit order as the COBOL
     * {@code CEEDAYS} flow: invalid format, then insufficient/length, then a
     * strict parse whose success or {@link DateTimeParseException} is classified
     * into the appropriate feedback outcome.
     *
     * @param date        the date string to validate; may be {@code null}
     * @param cobolFormat the COBOL picture format (for example {@code YYYY-MM-DD}
     *                    or {@code YYYYMMDD}); may be {@code null}
     * @return a {@link DateValidationResult} carrying the validity flag, severity,
     *         message number, result text, and the original inputs
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
     * Convenience predicate for strict validity, equivalent to
     * {@code validateDate(date, cobolFormat).valid()}. Callers needing the COBOL
     * consumer tolerance of code {@value #MSG_UNSUPPORTED_RANGE} should use
     * {@link DateValidationResult#isAcceptable()} on the full result instead.
     *
     * @param date        the date string to validate; may be {@code null}
     * @param cobolFormat the COBOL picture format; may be {@code null}
     * @return {@code true} only when the date is strictly valid
     */
    public boolean isValidDate(String date, String cobolFormat) {
        return validateDate(date, cobolFormat).valid();
    }

    /**
     * Translates a COBOL picture format to a {@link DateTimeFormatter} pattern,
     * returning {@code null} when the format is empty or contains an
     * unrecognised token (which maps to FC-BAD-PIC-STRING). Year uses the
     * proleptic year letter {@code u} (not {@code y}) so strict parsing succeeds
     * without an era. Recognised runs: {@code YYYY}&rarr;{@code uuuu},
     * {@code YY}&rarr;{@code uu}, {@code MM}&rarr;{@code MM}, {@code DD}&rarr;{@code dd};
     * the separators {@code -}, {@code /}, and {@code .} pass through as literals.
     *
     * @param cobolFormat the trimmed COBOL picture format
     * @return the equivalent {@link DateTimeFormatter} pattern, or {@code null} if invalid
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
     * Counts the run of a pattern letter (case-insensitive) starting at
     * {@code start} within {@code s}.
     *
     * @param s      the source format string
     * @param start  the index at which the run begins
     * @param letter the pattern letter to count
     * @return the number of consecutive occurrences of {@code letter} from {@code start}
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
     * Reports whether any numeric position (where the format character is
     * {@code Y}, {@code M}, or {@code D}) holds a non-digit in {@code value}.
     * Used to distinguish non-numeric data (FC-NON-NUMERIC-DATA) from impossible
     * calendar values (FC-BAD-DATE-VALUE) after a parse failure. Index access is
     * safe because the caller guarantees {@code value.length() == fmt.length()}.
     *
     * @param value the trimmed candidate date value
     * @param fmt   the trimmed COBOL picture format of equal length
     * @return {@code true} if a numeric position contains a non-digit character
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
     * Immutable outcome of a date validation, mirroring the result string that
     * {@code CSUTLDTC} returns to its callers (severity, message number, and
     * descriptive text) together with the original inputs for diagnostics.
     *
     * @param valid         {@code true} only for a strictly valid date
     * @param severity      the LE severity ({@value #SEVERITY_VALID} or {@value #SEVERITY_INVALID})
     * @param messageNumber the decoded {@code CEEDAYS} feedback message number
     * @param resultText    the human-readable result text
     * @param testDate      the original (untrimmed) date argument
     * @param formatUsed    the original (untrimmed) format argument
     */
    public record DateValidationResult(boolean valid, int severity, int messageNumber,
                                       String resultText, String testDate, String formatUsed) {

        /**
         * Indicates whether online consumers ({@code COTRN02C}, {@code CORPT00C})
         * treat this result as acceptable: a strictly valid date, or the tolerated
         * "unsupported range" outcome ({@value #MSG_UNSUPPORTED_RANGE}).
         *
         * @return {@code true} when the date is valid or the message number is
         *         {@value #MSG_UNSUPPORTED_RANGE}
         */
        public boolean isAcceptable() {
            return valid || messageNumber == MSG_UNSUPPORTED_RANGE;
        }
    }
}
