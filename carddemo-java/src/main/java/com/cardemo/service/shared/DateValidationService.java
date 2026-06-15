package com.cardemo.service.shared;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

import org.springframework.stereotype.Service;

/**
 * Consolidated CardDemo date-validation service: the Java 25 realization of the COBOL Language
 * Environment {@code CEEDAYS} date API ({@code app/cbl/CSUTLDTC.cbl}) together with the reusable
 * date-edit paragraphs of the procedure copybook {@code app/cpy/CSUTLDPY.cpy} (whose field layout
 * and 88-level value sets are declared in the working-storage copybook
 * {@code app/cpy/CSUTLDWY.cpy}).
 *
 * <p>Every legacy program that performed {@code CALL 'CSUTLDTC'} &mdash; either directly or
 * indirectly through the {@code CSUTLDPY} {@code EDIT-DATE-LE} paragraph &mdash; is migrated to
 * inject this single component instead (AAP &sect;0.4.2). It is the one date-validation collaborator
 * referenced by the account and customer update flows ({@code COACTUPC}, {@code COCRDUPC} and the
 * customer maintenance screens).</p>
 *
 * <h2>Migration provenance</h2>
 * <p>Behaviour is translated from the frozen AWS CardDemo COBOL estate at commit SHA
 * {@code 27d6c6f}. The COBOL source is <em>never copied</em> into this repository; only its
 * observable behaviour &mdash; the same valid/invalid outcomes and the same field-prefixed result
 * messages &mdash; is reproduced (AAP &sect;0.4.1 / tech-spec L638, and the &sect;0.7.2 preservation
 * requirements).</p>
 *
 * <h2>{@code CEEDAYS} &rarr; {@code java.time} substitution</h2>
 * <p>The Language Environment {@code CEEDAYS} call is replaced by {@link java.time.LocalDate}
 * parsing through a {@link java.time.format.DateTimeFormatter} configured with
 * {@link java.time.format.ResolverStyle#STRICT}. Strict resolution rejects impossible calendar
 * dates (for example {@code 2023-02-29}, month {@code 13} or day {@code 32}) exactly as
 * {@code CEEDAYS} did, with no relaxation and no added strictness (AAP &sect;0.7.1 Minimal Change
 * Clause). The proleptic-year pattern symbol {@code u} is used in place of the era-based {@code y}
 * so that a {@code LocalDate} resolves under {@code STRICT} <em>without</em> requiring an explicit
 * era field; for the only year range this system accepts (19xx/20xx) {@code uuuu} and {@code yyyy}
 * are identical.</p>
 *
 * <h2>Century window (intentional restriction &mdash; preserved exactly)</h2>
 * <p>{@code CSUTLDWY} pins the century byte to {@code 20} ({@code THIS-CENTURY}) or {@code 19}
 * ({@code LAST-CENTURY}). A real calendar year such as 1899 or 2100 is therefore <em>invalid</em>
 * in CardDemo. This window is enforced explicitly in {@link #validateYear(String, String)} and is
 * deliberately <em>not</em> delegated to {@link java.time.LocalDate} alone, which would happily
 * accept 1899 or 2100. Widening this range would break parity, so it is reproduced verbatim.</p>
 *
 * <h2>Flag / message fidelity</h2>
 * <p>Faithful to the COBOL flag-plus-message idiom ({@code WS-SEVERITY-N} paired with
 * {@code WS-RESULT}/{@code WS-RETURN-MSG}), this service <em>returns</em> a
 * {@link DateValidationResult} and never throws. Callers decide whether to translate a failed
 * result into a {@code ValidationException} (HTTP 400). Consequently this class imports no
 * {@code com.cardemo.exception} type and no sibling service, repository, entity, controller or
 * batch type; its only dependencies are the JDK and the Spring {@code @Service} stereotype
 * (its depends-on-files set is empty).</p>
 *
 * <h2>Thread-safety</h2>
 * <p>Stateless and singleton-safe: the only instance field is an immutable {@link java.time.Clock},
 * every shared constant is {@code static final}, and {@link java.time.format.DateTimeFormatter}
 * instances are themselves immutable and thread-safe.</p>
 *
 * @see java.time.LocalDate
 * @see java.time.format.ResolverStyle#STRICT
 */
@Service
public class DateValidationService {

    /**
     * The default CardDemo date picture. {@code CSUTLDPY}'s {@code EDIT-DATE-LE} paragraph performed
     * {@code MOVE 'YYYYMMDD' TO WS-DATE-FORMAT} before its {@code CALL 'CSUTLDTC'}, so this is the
     * only picture exercised anywhere in the estate.
     */
    private static final String DEFAULT_FORMAT = "YYYYMMDD";

    /**
     * Strict {@code CCYYMMDD} parser used wherever {@code CEEDAYS} validated a date.
     *
     * <p>COBOL substitution: the {@code CEEDAYS} LE date API is replaced by a strict
     * {@link DateTimeFormatter}. The proleptic-year symbol {@code uuuu} (not the era-based
     * {@code yyyy}) is required so that {@link ResolverStyle#STRICT} can resolve a {@code LocalDate}
     * without an explicit era; for the CardDemo 19xx/20xx window {@code uuuu} equals {@code yyyy}.
     * The instance is immutable and therefore safe to share from this singleton bean.</p>
     */
    private static final DateTimeFormatter YYYYMMDD_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Source of "today" for the date-of-birth reasonableness check. It is injected so unit tests can
     * pin a deterministic date; production uses the JVM's default zone.
     *
     * <p>COBOL substitution: {@code FUNCTION CURRENT-DATE} (used by {@code EDIT-DATE-OF-BIRTH}) is
     * replaced by {@link java.time.LocalDate#now(Clock)} reading from this clock.</p>
     */
    private final Clock clock;

    /**
     * Production constructor selected by Spring component scanning; "today" is read from the JVM's
     * default time zone, mirroring the legacy {@code FUNCTION CURRENT-DATE} behaviour.
     */
    public DateValidationService() {
        this(Clock.systemDefaultZone());
    }

    /**
     * Test-friendly constructor that accepts a fixed {@link Clock} so the date-of-birth check in
     * {@link #validateDateOfBirth(String, String)} is deterministic. A {@code null} clock falls back
     * to the system default zone.
     *
     * @param clock the clock supplying "today"; {@code null} is treated as
     *              {@link Clock#systemDefaultZone()}
     */
    public DateValidationService(Clock clock) {
        this.clock = (clock != null) ? clock : Clock.systemDefaultZone();
    }

    /**
     * Immutable validation outcome mirroring the COBOL {@code WS-SEVERITY-N} severity together with
     * the {@code WS-RESULT}/{@code WS-RETURN-MSG} result text. A severity of {@code 0} means valid,
     * matching {@code CSUTLDTC}, which moved {@code SEVERITY OF FEEDBACK-CODE} into
     * {@code WS-SEVERITY-N} and returned it through {@code RETURN-CODE}.
     *
     * <p>The construction helpers are named {@link #ofValid()} and {@link #ofInvalid(String)} rather
     * than {@code valid()}/{@code invalid()}: because the {@code valid} record component already
     * generates a no-argument {@code valid()} accessor, a static {@code valid()} factory would clash
     * with it. The {@code of}-prefix is the idiomatic factory convention (as in {@code List.of}) and
     * keeps the boolean accessor {@link #valid()} available for callers to test the outcome.</p>
     *
     * @param valid    {@code true} when the date passed validation
     * @param severity {@code 0} when valid; a positive value otherwise (mirrors {@code WS-SEVERITY-N})
     * @param message  human-readable result text already prefixed with the caller's field label
     *                 (mirrors {@code WS-RESULT}/{@code WS-RETURN-MSG})
     */
    public record DateValidationResult(boolean valid, int severity, String message) {

        /** Severity reported when a date is valid (COBOL {@code WS-SEVERITY-N = 0}). */
        private static final int SEVERITY_VALID = 0;

        /**
         * A positive, LE-style "severe" severity. The exact numeric {@code CEEDAYS} feedback code is
         * an IBM Language Environment internal value that cannot be reproduced from {@code java.time};
         * any non-zero value preserves the only contract callers rely on (zero versus non-zero).
         */
        private static final int SEVERITY_INVALID = 12;

        /** Result text for a valid date, matching {@code CSUTLDTC}'s {@code 'Date is valid'}. */
        private static final String VALID_MESSAGE = "Date is valid";

        /**
         * Creates a valid result.
         *
         * @return a result with {@code valid == true}, {@code severity == 0} and the message
         *         {@code "Date is valid"}
         */
        public static DateValidationResult ofValid() {
            return new DateValidationResult(true, SEVERITY_VALID, VALID_MESSAGE);
        }

        /**
         * Creates an invalid result carrying the supplied failure text.
         *
         * @param message the failure text, already prefixed with the caller-supplied field label
         * @return a result with {@code valid == false} and a positive severity
         */
        public static DateValidationResult ofInvalid(String message) {
            return new DateValidationResult(false, SEVERITY_INVALID, message);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (A) CSUTLDTC (CEEDAYS) equivalent: validate a date string against a picture/format.
    // ---------------------------------------------------------------------------------------------

    /**
     * Validates {@code date} against the default CardDemo picture {@code YYYYMMDD}. Convenience
     * overload of {@link #validateDate(String, String)}.
     *
     * @param date the date string, normally {@code CCYYMMDD}
     * @return a {@link DateValidationResult}; severity {@code 0} when valid
     */
    public DateValidationResult validateDate(String date) {
        return validateDate(date, DEFAULT_FORMAT);
    }

    /**
     * Java realization of {@code CSUTLDTC}: validates {@code date} against {@code format} using a
     * strict {@link DateTimeFormatter}. Returns severity {@code 0} ("Date is valid") on success and a
     * positive severity with descriptive text on failure, mirroring the {@code CEEDAYS} feedback-code
     * {@code EVALUATE} in the original sub-program.
     *
     * <p>Only {@code YYYYMMDD} is in real use across the estate (it is the picture set by
     * {@code CSUTLDPY}'s {@code EDIT-DATE-LE}); any unrecognised or empty picture falls back to the
     * {@code YYYYMMDD} formatter, which is documented in {@link #formatterFor(String)}.</p>
     *
     * @param date   the date string to validate; surrounding whitespace padding is ignored
     * @param format the COBOL-style picture; {@code YYYYMMDD} is mapped and any other value defaults
     *               to it
     * @return a {@link DateValidationResult}; severity {@code 0} when valid
     */
    public DateValidationResult validateDate(String date, String format) {
        // COBOL substitution: CEEDAYS feedback FC-INSUFFICIENT-DATA ("Insufficient") for an absent
        // date value. CEEDAYS could not validate a date it was not given.
        if (date == null) {
            return DateValidationResult.ofInvalid("Insufficient");
        }
        String candidate = date.trim();
        if (candidate.isEmpty()) {
            return DateValidationResult.ofInvalid("Insufficient");
        }
        try {
            // COBOL substitution: CALL 'CEEDAYS' USING <date>, <format> -> strict LocalDate parse.
            // A successful parse is the severity-0 "valid" path of the CEEDAYS EVALUATE.
            LocalDate.parse(candidate, formatterFor(format));
            return DateValidationResult.ofValid();
        } catch (DateTimeException ex) {
            // Mirrors the CEEDAYS "WHEN OTHER" branch ("Date is invalid"). The precise LE feedback
            // text (Invalid month / Datevalue error / Bad Pic String / ...) is an IBM-internal code
            // and is intentionally generalized; the valid/invalid outcome is preserved exactly.
            return DateValidationResult.ofInvalid("Date is invalid");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (B) CSUTLDPY edit cascade: component validation of a CCYYMMDD date.
    //     Order and short-circuit behaviour are preserved exactly (each COBOL paragraph performed
    //     GO TO <para>-EXIT on error, and only the first message survived the WS-RETURN-MSG-OFF
    //     guard, so the first failure's message is the one returned).
    // ---------------------------------------------------------------------------------------------

    /**
     * Orchestrates the {@code EDIT-DATE-CCYYMMDD} cascade. The result is set invalid first, then the
     * edits run in the exact COBOL order &mdash; year, month, day, day/month/year combination, and
     * finally the {@code EDIT-DATE-LE} backstop &mdash; short-circuiting on the first failure. The
     * result is valid only when every edit passes.
     *
     * <p>The 8-character {@code CCYYMMDD} is decomposed positionally into {@code CCYY} (0&ndash;4),
     * {@code MM} (4&ndash;6) and {@code DD} (6&ndash;8); shorter input yields blank components, which
     * the individual edits report as "must be supplied", matching the COBOL space-fill of a short
     * {@code MOVE}.</p>
     *
     * @param ccyymmdd   the 8-character {@code CCYYMMDD} date
     * @param fieldLabel the field name used to prefix messages (COBOL {@code WS-EDIT-VARIABLE-NAME})
     * @return a {@link DateValidationResult}; severity {@code 0} when every edit passes
     */
    public DateValidationResult validateCcyymmdd(String ccyymmdd, String fieldLabel) {
        String source = (ccyymmdd == null) ? "" : ccyymmdd;
        String ccyy = safeSubstring(source, 0, 4);
        String mm = safeSubstring(source, 4, 6);
        String dd = safeSubstring(source, 6, 8);

        // EDIT-YEAR-CCYY
        DateValidationResult yearResult = validateYear(ccyy, fieldLabel);
        if (!yearResult.valid()) {
            return yearResult;
        }
        // EDIT-MONTH
        DateValidationResult monthResult = validateMonth(mm, fieldLabel);
        if (!monthResult.valid()) {
            return monthResult;
        }
        // EDIT-DAY
        DateValidationResult dayResult = validateDay(dd, fieldLabel);
        if (!dayResult.valid()) {
            return dayResult;
        }
        // EDIT-DAY-MONTH-YEAR. Components are guaranteed numeric and in range here, so the radix
        // parses cannot overflow or throw.
        DateValidationResult comboResult = validateDayMonthYear(
                Integer.parseInt(ccyy), Integer.parseInt(mm), Integer.parseInt(dd), fieldLabel);
        if (!comboResult.valid()) {
            return comboResult;
        }
        // EDIT-DATE-LE: the final "in case some one managed to enter a bad date that passed all the
        // edits above" backstop, performed in COBOL via CALL 'CSUTLDTC'. The canonical 8 digits are
        // re-assembled so any surrounding padding on the original argument cannot affect the parse.
        DateValidationResult leResult = validateDate(ccyy + mm + dd, DEFAULT_FORMAT);
        if (!leResult.valid()) {
            return DateValidationResult.ofInvalid(trimLabel(fieldLabel)
                    + " validation error Sev code: " + leResult.severity()
                    + " Message code: " + leResult.message());
        }
        // EDIT-DATE-CCYYMMDD-EXIT: all edits cleared.
        return DateValidationResult.ofValid();
    }

    /**
     * {@code EDIT-YEAR-CCYY}: validates the 4-character {@code CCYY}. Checks run in COBOL order:
     * supplied, then numeric (a 4-digit number), then a reasonable century where the {@code CC} byte
     * must be {@code 19} ({@code LAST-CENTURY}) or {@code 20} ({@code THIS-CENTURY}).
     *
     * @param ccyy       the 4-character century-plus-year component
     * @param fieldLabel the message prefix (COBOL {@code WS-EDIT-VARIABLE-NAME})
     * @return a {@link DateValidationResult}
     */
    public DateValidationResult validateYear(String ccyy, String fieldLabel) {
        String label = trimLabel(fieldLabel);
        // Not supplied (COBOL: WS-EDIT-DATE-CCYY EQUAL LOW-VALUES OR SPACES).
        if (isBlank(ccyy)) {
            return DateValidationResult.ofInvalid(label + " : Year must be supplied.");
        }
        // Not numeric (COBOL: WS-EDIT-DATE-CCYY IS NOT NUMERIC); the field is a fixed 4 bytes.
        if (!isDigits(ccyy) || ccyy.length() != 4) {
            return DateValidationResult.ofInvalid(label + " must be 4 digit number.");
        }
        // Century not reasonable. COBOL only coded 19 and 20 as valid century values; a real year
        // such as 1899 or 2100 is rejected here. This is enforced explicitly rather than via
        // LocalDate, which would accept those years.
        String cc = ccyy.substring(0, 2);
        if (!"19".equals(cc) && !"20".equals(cc)) {
            return DateValidationResult.ofInvalid(label + " : Century is not valid.");
        }
        return DateValidationResult.ofValid();
    }

    /**
     * {@code EDIT-MONTH}: validates the 2-character {@code MM}. A blank month is reported as
     * "must be supplied"; otherwise it must be numeric and within {@code 1..12}
     * (COBOL {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}).
     *
     * @param mm         the 2-character month component
     * @param fieldLabel the message prefix
     * @return a {@link DateValidationResult}
     */
    public DateValidationResult validateMonth(String mm, String fieldLabel) {
        String label = trimLabel(fieldLabel);
        if (isBlank(mm)) {
            return DateValidationResult.ofInvalid(label + " : Month must be supplied.");
        }
        // The numeric test and the 1..12 range test both produce the identical COBOL message, so a
        // single combined guard preserves the observable result regardless of which COBOL check
        // (WS-VALID-MONTH or TEST-NUMVAL) would have fired first.
        if (!isDigits(mm) || !inRange(mm, 1, 12)) {
            return DateValidationResult.ofInvalid(label + ": Month must be a number between 1 and 12.");
        }
        return DateValidationResult.ofValid();
    }

    /**
     * {@code EDIT-DAY}: validates the 2-character {@code DD}. A blank day is reported as
     * "must be supplied"; otherwise it must be numeric and within {@code 1..31}
     * (COBOL {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}). Month-specific limits (28/29/30) are
     * applied later by {@link #validateDayMonthYear(int, int, int, String)}.
     *
     * @param dd         the 2-character day component
     * @param fieldLabel the message prefix
     * @return a {@link DateValidationResult}
     */
    public DateValidationResult validateDay(String dd, String fieldLabel) {
        String label = trimLabel(fieldLabel);
        if (isBlank(dd)) {
            return DateValidationResult.ofInvalid(label + " : Day must be supplied.");
        }
        if (!isDigits(dd) || !inRange(dd, 1, 31)) {
            return DateValidationResult.ofInvalid(label + ":day must be a number between 1 and 31.");
        }
        return DateValidationResult.ofValid();
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR}: the cross-field combination checks, evaluated in the exact COBOL
     * order &mdash; a 31st day in a month that does not have 31 days, a 30th of February, then a 29th
     * of February in a non-leap year.
     *
     * @param ccyy       the 4-digit year (already validated as a 19xx/20xx number)
     * @param mm         the month, {@code 1..12}
     * @param dd         the day, {@code 1..31}
     * @param fieldLabel the message prefix
     * @return a {@link DateValidationResult}
     */
    public DateValidationResult validateDayMonthYear(int ccyy, int mm, int dd, String fieldLabel) {
        String label = trimLabel(fieldLabel);
        // COBOL: IF NOT WS-31-DAY-MONTH AND WS-DAY-31 -> cannot have 31 days.
        if (!is31DayMonth(mm) && dd == 31) {
            return DateValidationResult.ofInvalid(label + ":Cannot have 31 days in this month.");
        }
        // COBOL: IF WS-FEBRUARY AND WS-DAY-30 -> cannot have 30 days.
        if (mm == 2 && dd == 30) {
            return DateValidationResult.ofInvalid(label + ":Cannot have 30 days in this month.");
        }
        // COBOL: IF WS-FEBRUARY AND WS-DAY-29 -> leap-year test; a non-leap year cannot have 29 days.
        if (mm == 2 && dd == 29 && !isLeapYear(ccyy)) {
            return DateValidationResult.ofInvalid(
                    label + ":Not a leap year.Cannot have 29 days in this month.");
        }
        return DateValidationResult.ofValid();
    }

    // ---------------------------------------------------------------------------------------------
    // (C) EDIT-DATE-OF-BIRTH: reasonableness check -- a date of birth may not be in the future.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code EDIT-DATE-OF-BIRTH}: first runs the full {@link #validateCcyymmdd(String, String)}
     * cascade (the caller {@code PERFORM}ed {@code EDIT-DATE-CCYYMMDD} before
     * {@code EDIT-DATE-OF-BIRTH}); when that passes it enforces the reasonableness rule that a date
     * of birth cannot lie in the future.
     *
     * <p>COBOL compared {@code WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY} (current strictly after
     * the entered date), so the date of birth must be <em>strictly before</em> today; today itself
     * or any future date is rejected.</p>
     *
     * @param ccyymmdd   the 8-character {@code CCYYMMDD} date of birth
     * @param fieldLabel the message prefix (for example {@code "Date of Birth"})
     * @return a {@link DateValidationResult}
     */
    public DateValidationResult validateDateOfBirth(String ccyymmdd, String fieldLabel) {
        DateValidationResult base = validateCcyymmdd(ccyymmdd, fieldLabel);
        if (!base.valid()) {
            return base;
        }
        // COBOL substitution: FUNCTION CURRENT-DATE -> LocalDate.now(clock).
        LocalDate today = LocalDate.now(clock);
        // validateCcyymmdd has already proven these 8 digits form a real, strictly resolvable date,
        // so this parse cannot throw.
        String source = (ccyymmdd == null) ? "" : ccyymmdd;
        LocalDate birthDate = LocalDate.parse(
                safeSubstring(source, 0, 4) + safeSubstring(source, 4, 6) + safeSubstring(source, 6, 8),
                YYYYMMDD_FORMATTER);
        // COBOL: current > edit-date -> acceptable; otherwise "cannot be in the future".
        if (today.isAfter(birthDate)) {
            return DateValidationResult.ofValid();
        }
        return DateValidationResult.ofInvalid(trimLabel(fieldLabel) + ":cannot be in the future ");
    }

    // ---------------------------------------------------------------------------------------------
    // Internal helpers.
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolves a COBOL picture string to a strict {@link DateTimeFormatter}. Only {@code YYYYMMDD}
     * is mapped; any other value (including {@code null} or an empty/blank string) falls back to the
     * {@code YYYYMMDD} formatter. The estate never used any other picture, so a single supported
     * picture keeps this mapping total while preserving the {@code CSUTLDTC} two-argument contract.
     */
    private static DateTimeFormatter formatterFor(String format) {
        if (format != null && DEFAULT_FORMAT.equalsIgnoreCase(format.trim())) {
            return YYYYMMDD_FORMATTER;
        }
        // Documented fallback: every CardDemo caller uses YYYYMMDD (set by CSUTLDPY EDIT-DATE-LE).
        return YYYYMMDD_FORMATTER;
    }

    /**
     * {@code 88 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12}: the months that have 31 days.
     *
     * @param month the month number
     * @return {@code true} when {@code month} is one of the seven 31-day months
     */
    private static boolean is31DayMonth(int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> true;
            default -> false;
        };
    }

    /**
     * Gregorian leap-year test.
     *
     * <p>COBOL substitution: {@code CSUTLDPY} computed {@code WS-DIV-BY = 400} when the last two
     * digits {@code YY = 00} (century years) and {@code WS-DIV-BY = 4} otherwise, then declared a
     * leap year when {@code CCYY} divided evenly with a zero remainder. Because only century years
     * have {@code YY = 00}, that computation is mathematically identical to the full Gregorian rule
     * (and therefore to {@link java.time.Year#isLeap(long)}); the literal {@code mod 400}/{@code mod
     * 4} computation is reproduced here for traceability.</p>
     *
     * @param ccyy the 4-digit year
     * @return {@code true} when {@code ccyy} is a leap year
     */
    private static boolean isLeapYear(int ccyy) {
        int divisor = (ccyy % 100 == 0) ? 400 : 4;
        return ccyy % divisor == 0;
    }

    /**
     * Reports whether a value is "blank" in the COBOL sense.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is {@code null} or contains only whitespace, mirroring
     *         the COBOL {@code SPACES}/{@code LOW-VALUES} checks
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Reports whether a value is entirely numeric in the COBOL sense.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is non-empty and every character is an ASCII digit
     *         {@code '0'..'9'}, mirroring the COBOL {@code IS NUMERIC} class test
     */
    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether an all-digit value parses into an inclusive integer range. Callers guard this
     * with {@link #isDigits(String)} first; the defensive {@link NumberFormatException} catch keeps
     * the service throw-free even if an over-long digit string is supplied directly.
     *
     * @param value an all-digit string
     * @param min   inclusive lower bound
     * @param max   inclusive upper bound
     * @return {@code true} when {@code min <= value <= max}
     */
    private static boolean inRange(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException ex) {
            // More digits than fit in an int cannot be a valid 1..12 / 1..31 component.
            return false;
        }
    }

    /**
     * COBOL substitution for {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}; tolerates a {@code null}
     * label by treating it as an empty prefix.
     *
     * @param fieldLabel the caller-supplied field name
     * @return the trimmed label, or {@code ""} when {@code fieldLabel} is {@code null}
     */
    private static String trimLabel(String fieldLabel) {
        return (fieldLabel == null) ? "" : fieldLabel.trim();
    }

    /**
     * Fixed-position substring that never throws. Requests beyond the string length yield {@code ""},
     * which mirrors the space-fill a COBOL {@code MOVE} produces for a short source.
     *
     * @param source the source string (may be {@code null})
     * @param start  inclusive start index
     * @param end    exclusive end index
     * @return the requested slice, clamped to the available characters, or {@code ""}
     */
    private static String safeSubstring(String source, int start, int end) {
        if (source == null || start >= source.length()) {
            return "";
        }
        return source.substring(start, Math.min(end, source.length()));
    }
}
