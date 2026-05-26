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
package com.blitzy.carddemo.application.util;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.validation.DateConstants;
import com.blitzy.carddemo.domain.validation.DateValidationWork.Century;
import com.blitzy.carddemo.domain.validation.DateValidationWork.DateRules;
import com.blitzy.carddemo.domain.validation.DateValidationWork.DateValidationResult;
import com.blitzy.carddemo.domain.validation.DateValidationWork.ValidityFlag;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;

/**
 * Date-validation helper translating two COBOL artifacts into a single Java
 * class:
 * <ol>
 *   <li><b>{@code app/cbl/CSUTLDTC.cbl}</b> &mdash; the
 *       {@code PROGRAM-ID CSUTLDTC} subprogram, a wrapper around the IBM
 *       Language Environment service {@code CEEDAYS}. The wrapper accepts a
 *       date String and a format mask String and returns an 80-byte
 *       {@code LS-RESULT} structure with severity, message number, 15-char
 *       result text, the input date, and the format mask. Translated to
 *       {@link #validate(String, String)} which returns a
 *       {@link DateValidationResult}.</li>
 *   <li><b>{@code app/cpy/CSUTLDPY.cpy}</b> &mdash; the procedure-template
 *       copybook with 7 executable paragraphs ({@code EDIT-DATE-CCYYMMDD},
 *       {@code EDIT-YEAR-CCYY}, {@code EDIT-MONTH}, {@code EDIT-DAY},
 *       {@code EDIT-DAY-MONTH-YEAR}, {@code EDIT-DATE-LE},
 *       {@code EDIT-DATE-OF-BIRTH}). Translated to the static methods
 *       {@link #validateCcyymmdd(String, String)},
 *       {@link #editYearCcyy(String, String)},
 *       {@link #editMonth(String, String)},
 *       {@link #editDay(String, String)},
 *       {@link #editDayMonthYear(int, int, int, String)},
 *       {@link #editDateLe(String, String)}, and
 *       {@link #validateDateOfBirth(String, String, LocalDate)}.</li>
 * </ol>
 *
 * <h2>Class rename (AAP-sanctioned exception)</h2>
 * Per AAP &sect;0.6.8, this is the <b>only</b> renamed program in the migration
 * (an AAP-sanctioned exception). The original COBOL {@code PROGRAM-ID CSUTLDTC}
 * would have translated to {@code CsUtlDtC} by the standard one-class-per-program
 * rule; the rename to {@code DateValidator} captures the program's actual purpose
 * and is permitted because:
 * <ul>
 *   <li>The original name {@code CSUTLDTC} is an abbreviation
 *       ("CardDemo Utility Date Type Check") that conveys nothing semantic in
 *       Java context;</li>
 *   <li>This class also hosts the executable logic from
 *       {@code CSUTLDPY.cpy}, so a single-program name would be
 *       misleading; and</li>
 *   <li>The rename is explicitly authorized in AAP &sect;0.6.8.</li>
 * </ul>
 *
 * <h2>CEEDAYS replacement strategy</h2>
 * The LE service {@code CEEDAYS} is replaced by
 * {@link LocalDate#parse(CharSequence, DateTimeFormatter)} with
 * {@link ResolverStyle#STRICT}. The strict resolver ensures that dates such
 * as "February 30" are rejected (matching CEEDAYS strictness). Failure
 * modes are mapped from {@link DateTimeParseException} /
 * {@link DateTimeException} to specific severity/message-number values
 * approximating the CEEDAYS feedback-code conventions. The user-visible
 * 15-character {@code WS-RESULT} text is preserved verbatim from the COBOL
 * source.
 *
 * <p>The mapping from {@link DateTimeParseException} categories to CEEDAYS
 * (severity, msgNo) values is a best-effort approximation; the precise byte
 * values cannot be reproduced for every conceivable edge case because the
 * COBOL implementation calls into an opaque LE service. The 15-character
 * {@code WS-RESULT} text strings, however, are preserved EXACTLY. See
 * {@code java/MIGRATION_NOTES.md} for the explicit deviation note.
 *
 * <h2>COBOL-equivalent leap-year algorithm</h2>
 * {@link #editDayMonthYear(int, int, int, String)} uses a simplified rule
 * (divide by 400 if year ends in 00, else divide by 4). This Java
 * translation mirrors that exact algorithm rather than delegating to
 * {@link LocalDate#isLeapYear()}; the two algorithms agree on all valid
 * year inputs (1..9999), so the observable result is identical.
 *
 * <h2>Data structures consumed</h2>
 * All working-storage data structures (the
 * {@code com.blitzy.carddemo.domain.validation.DateValidationWork} record,
 * {@link ValidityFlag} sealed permits, {@link Century} sealed permits,
 * {@link DateRules} static predicates, {@link DateValidationResult} record)
 * live in {@code carddemo-domain.validation} and are imported. This class
 * hosts only the EXECUTABLE logic.
 *
 * <h2>Utility class</h2>
 * This class is intentionally a utility class: it has a private constructor
 * that throws {@code UnsupportedOperationException} and exposes only static
 * methods.
 *
 * @see com.blitzy.carddemo.domain.validation.DateValidationWork
 * @see DateConstants
 */
@CobolProgram(
        value = "CSUTLDTC",
        sourcePath = "app/cbl/CSUTLDTC.cbl",
        translationDate = "2025-09-16",
        notes = "RENAMED from CsUtlDtC to DateValidator per AAP §0.6.8 (only renamed"
                + " program in migration). Also hosts the EXECUTABLE date-validation"
                + " logic translated from app/cpy/CSUTLDPY.cpy (procedure-template"
                + " paragraphs EDIT-DATE-CCYYMMDD, EDIT-YEAR-CCYY, EDIT-MONTH,"
                + " EDIT-DAY, EDIT-DAY-MONTH-YEAR, EDIT-DATE-LE, EDIT-DATE-OF-BIRTH)."
)
public final class DateValidator {

    // =========================================================================
    // 15-character RESULT text constants (CSUTLDTC EVALUATE WS-RESULT values).
    //
    // Each constant is EXACTLY 15 characters wide — the COBOL WS-RESULT field
    // is PIC X(15). The 13-char success literal "Date is valid" and 12-char
    // "Insufficient" literal in the COBOL source are auto-padded by the
    // MOVE statement to fill PIC X(15); these Java constants pre-pad them
    // to preserve byte-for-byte output identity (AAP §0.1.3 byte-for-byte
    // file fidelity).
    //
    // The static initializer below verifies the 15-char invariant at class
    // load time so any future edit that breaks the invariant fails fast.
    // =========================================================================

    /**
     * Result text for {@code FC-INVALID-DATE} &mdash; the SUCCESS case in
     * CEEDAYS feedback codes. Despite the COBOL constant name, this is the
     * "date is valid" path (the COBOL feedback token {@code X'0000000000000000'}
     * means "no error"). Width: 15 chars (13 source chars + 2 trailing spaces).
     */
    public static final String RESULT_DATE_IS_VALID = "Date is valid  ";

    /**
     * Result text for {@code FC-INSUFFICIENT-DATA}. Width: 15 chars
     * (12 source chars + 3 trailing spaces).
     */
    public static final String RESULT_INSUFFICIENT = "Insufficient   ";

    /**
     * Result text for {@code FC-BAD-DATE-VALUE} (e.g., Feb 30). Width: 15.
     */
    public static final String RESULT_DATEVALUE_ERROR = "Datevalue error";

    /** Result text for {@code FC-INVALID-ERA}. Width: 15. */
    public static final String RESULT_INVALID_ERA = "Invalid Era    ";

    /** Result text for {@code FC-UNSUPP-RANGE}. Width: 15. */
    public static final String RESULT_UNSUPP_RANGE = "Unsupp. Range  ";

    /** Result text for {@code FC-INVALID-MONTH}. Width: 15. */
    public static final String RESULT_INVALID_MONTH = "Invalid month  ";

    /** Result text for {@code FC-BAD-PIC-STRING}. Width: 15. */
    public static final String RESULT_BAD_PIC_STRING = "Bad Pic String ";

    /** Result text for {@code FC-NON-NUMERIC-DATA}. Width: 15. */
    public static final String RESULT_NONNUMERIC_DATA = "Nonnumeric data";

    /** Result text for {@code FC-YEAR-IN-ERA-ZERO}. Width: 15. */
    public static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    /** Result text for {@code WHEN OTHER} (catch-all). Width: 15. */
    public static final String RESULT_DATE_IS_INVALID = "Date is invalid";

    /**
     * Static initializer enforcing the 15-character width invariant on
     * every RESULT_* constant. Fails fast at class load time if any
     * constant is mistyped (e.g., a developer accidentally removes a
     * trailing space). The literal {@code 15} is used rather than the
     * {@link #RESULT_TEXT_LENGTH} constant because Java forbids forward
     * simple-name references from a static initializer to a later
     * declared static field (JLS &sect;8.3.3) and reordering the entire
     * constant block to satisfy that rule would harm readability.
     */
    static {
        final String[] all = {
                RESULT_DATE_IS_VALID, RESULT_INSUFFICIENT, RESULT_DATEVALUE_ERROR,
                RESULT_INVALID_ERA, RESULT_UNSUPP_RANGE, RESULT_INVALID_MONTH,
                RESULT_BAD_PIC_STRING, RESULT_NONNUMERIC_DATA,
                RESULT_YEAR_IN_ERA_ZERO, RESULT_DATE_IS_INVALID
        };
        for (final String s : all) {
            if (s.length() != 15) {
                throw new ExceptionInInitializerError(
                        "RESULT_* constant '" + s + "' has length " + s.length()
                                + "; expected 15");
            }
        }
    }

    // =========================================================================
    // Severity / MsgNo constants — best-effort mapping to CEEDAYS feedback
    // codes. Each non-zero feedback code in CSUTLDTC.cbl embeds a 16-bit
    // severity (bytes 0-1) and a 16-bit message number (bytes 2-3) per the
    // LE convention. The decimal values below match the bytes 2-3 of each
    // FC-* token. Format is always 4-character zero-padded string per
    // COBOL PIC X(04) field width on WS-SEVERITY and WS-MSG-NO.
    // =========================================================================

    /** CEEDAYS severity {@code "0000"} = success ({@code FC-INVALID-DATE}). */
    private static final String SEV_OK = "0000";

    /**
     * CEEDAYS severity for non-OK feedback codes (typical {@code "0003"};
     * see LE manual byte 0-1 of every non-success FC-* token).
     */
    private static final String SEV_ERROR = "0003";

    /** {@code FC-INVALID-DATE} message number ("Date is valid"). */
    private static final String MSG_OK = "0000";

    /** {@code FC-INSUFFICIENT-DATA} message number ({@code X'09CB'} = 2507). */
    private static final String MSG_INSUFFICIENT = "2507";

    /** {@code FC-BAD-DATE-VALUE} message number ({@code X'09CC'} = 2508). */
    private static final String MSG_BAD_DATE = "2508";

    /** {@code FC-INVALID-ERA} message number ({@code X'09CD'} = 2509). */
    private static final String MSG_INVALID_ERA = "2509";

    /** {@code FC-UNSUPP-RANGE} message number ({@code X'09D1'} = 2513). */
    private static final String MSG_UNSUPP_RANGE = "2513";

    // =========================================================================
    // Field-width constants reflecting COBOL PIC clauses.
    // =========================================================================

    /** Width of the COBOL {@code WS-EDIT-DATE-CCYYMMDD} CCYYMMDD field (PIC X(8)). */
    private static final int CCYYMMDD_LENGTH = 8;

    /** Width of the CCYY year subfield (PIC X(4)). */
    private static final int CCYY_LENGTH = 4;

    /** Width of the MM month subfield (PIC X(2)). */
    private static final int MM_LENGTH = 2;

    /** Width of the DD day subfield (PIC X(2)). */
    private static final int DD_LENGTH = 2;

    /** Width of the 80-byte {@code LS-RESULT} block (PIC X(80)). */
    private static final int LS_RESULT_LENGTH = 80;

    /** Width of the {@code WS-RESULT} field (PIC X(15)). */
    private static final int RESULT_TEXT_LENGTH = 15;

    /** Width of the {@code WS-DATE} field (PIC X(10)). */
    private static final int LS_DATE_LENGTH = 10;

    // =========================================================================
    // Nested result records (paragraph-level and pipeline-level outcomes).
    // =========================================================================

    /**
     * Result of a single COBOL paragraph ({@code EDIT-YEAR-CCYY},
     * {@code EDIT-MONTH}, {@code EDIT-DAY}, {@code EDIT-DAY-MONTH-YEAR},
     * {@code EDIT-DATE-LE}). Carries the validity flag for the field, an
     * aggregate {@code isValid} convenience boolean, and the COBOL-style
     * return message that would have been built by the {@code STRING}
     * statement.
     *
     * <p>This record is intentionally simple: a single field has either
     * passed validation or failed for one reason. Multi-field outcomes use
     * {@link ValidationOutcome} instead.
     *
     * @param isValid   {@code true} iff {@code flag} is
     *                  {@link ValidityFlag.Valid}; convenience for callers
     *                  who do not pattern-match on {@code flag}
     * @param flag      the field's validity flag (Valid, NotOk, or Blank)
     * @param returnMsg the human-readable return message, or empty string
     *                  if no error (matches the COBOL {@code WS-RETURN-MSG}
     *                  output of the {@code STRING} statement)
     */
    public record FieldValidationResult(
            boolean isValid,
            ValidityFlag flag,
            String returnMsg
    ) {

        /**
         * Compact canonical constructor (JEP 513 Flexible Constructor
         * Bodies): validates nullity on the two reference parameters.
         *
         * @throws NullPointerException if {@code flag} or {@code returnMsg}
         *                              is {@code null}
         */
        public FieldValidationResult {
            Objects.requireNonNull(flag, "flag");
            Objects.requireNonNull(returnMsg, "returnMsg");
        }
    }

    /**
     * Aggregate result of the full {@code EDIT-DATE-CCYYMMDD} pipeline (and
     * of the DOB future-date check). Includes per-field flags, the overall
     * validity, the assembled return message, and the LE-service result.
     *
     * <p>The {@code WS-RETURN-MSG-OFF} guard in the COBOL source ensures
     * that only the FIRST error message is captured; this translation
     * achieves the same observable behavior by short-circuiting on the
     * first invalid field result.
     *
     * @param isValid    overall validity (true iff all 3 field flags are
     *                   {@link ValidityFlag.Valid} AND the LE check passed)
     * @param yearFlag   per-{@code CSUTLDWY} {@code FLG-YEAR-*}
     * @param monthFlag  per-{@code CSUTLDWY} {@code FLG-MONTH-*}
     * @param dayFlag    per-{@code CSUTLDWY} {@code FLG-DAY-*}
     * @param returnMsg  COBOL-style return message; first error wins
     * @param leResult   the {@link DateValidationResult} from the final
     *                   LE service call ({@code CSUTLDTC}), or {@code null}
     *                   if the LE call was short-circuited by an earlier
     *                   field-level error
     */
    public record ValidationOutcome(
            boolean isValid,
            ValidityFlag yearFlag,
            ValidityFlag monthFlag,
            ValidityFlag dayFlag,
            String returnMsg,
            DateValidationResult leResult
    ) {

        /**
         * Compact canonical constructor (JEP 513): validates nullity on
         * each reference parameter except {@code leResult} which is
         * intentionally nullable.
         *
         * @throws NullPointerException if any non-{@code leResult} reference
         *                              parameter is {@code null}
         */
        public ValidationOutcome {
            Objects.requireNonNull(yearFlag, "yearFlag");
            Objects.requireNonNull(monthFlag, "monthFlag");
            Objects.requireNonNull(dayFlag, "dayFlag");
            Objects.requireNonNull(returnMsg, "returnMsg");
            // leResult MAY be null when field-level errors short-circuit the
            // pipeline before the LE check.
        }
    }

    // =========================================================================
    // Constructor (utility class — instantiation forbidden).
    // =========================================================================

    /**
     * Private constructor &mdash; this is a utility class with only static
     * helpers. Construction is forbidden so that any reflective or
     * accidental instantiation fails loudly.
     *
     * @throws UnsupportedOperationException always
     */
    private DateValidator() {
        throw new UnsupportedOperationException(
                "DateValidator is a utility class and must not be instantiated");
    }

    // =========================================================================
    // validate(String, String) — CSUTLDTC PROGRAM-ID equivalent (PRIMARY API)
    // =========================================================================

    /**
     * CEEDAYS-equivalent strict date validation. Translates the COBOL
     * {@code PROGRAM-ID CSUTLDTC} from {@code app/cbl/CSUTLDTC.cbl}
     * (paragraph {@code A000-MAIN}), replacing the LE service
     * {@code CEEDAYS} call with
     * {@link LocalDate#parse(CharSequence, DateTimeFormatter)} using
     * {@link ResolverStyle#STRICT}.
     *
     * <p>The returned {@link DateValidationResult} mirrors the 80-byte
     * {@code LS-RESULT} structure from the COBOL LINKAGE SECTION. Use
     * {@link #formatAsLsResult(DateValidationResult)} to produce the
     * fixed-width 80-byte text representation if needed.
     *
     * <p><b>Format mask translation:</b> The COBOL mask uses {@code Y} for
     * year digits and {@code D} for day digits; the Java
     * {@link DateTimeFormatter} pattern uses {@code u} for proleptic year
     * (mandatory with {@code ResolverStyle.STRICT}) and {@code d} for day.
     * The substitution is performed automatically by {@link #parseMask}.
     *
     * <p><b>Behavior note:</b> The CEEDAYS feedback-code to Java exception
     * mapping is heuristic; see the class Javadoc for the explicit
     * deviation note. The 15-character {@code WS-RESULT} text strings are
     * preserved verbatim from the COBOL source.
     *
     * @param dateToTest the date String to validate (e.g.,
     *                   {@code "20250916"}); must be non-null
     * @param dateFormat the format mask (e.g., {@code "YYYYMMDD"}); must
     *                   be non-null
     * @return a {@link DateValidationResult} carrying severity, message
     *         number, 15-char result text, the input date, and the format
     *         mask
     * @throws NullPointerException if any parameter is null
     */
    public static DateValidationResult validate(String dateToTest, String dateFormat) {
        Objects.requireNonNull(dateToTest, "dateToTest");
        Objects.requireNonNull(dateFormat, "dateFormat");

        // Preserve the 10-char width semantics of LS-DATE and LS-DATE-FORMAT
        // (COBOL PIC X(10) right-pads with spaces). Trim trailing spaces for
        // parsing while preserving the original string for echo in the result.
        final String trimmedDate = dateToTest.stripTrailing();
        final String trimmedFormat = dateFormat.stripTrailing();

        // Build the formatter from the COBOL mask. An invalid mask is a
        // BAD-PIC-STRING per CEEDAYS feedback-code convention.
        final DateTimeFormatter formatter;
        try {
            formatter = parseMask(trimmedFormat);
        } catch (IllegalArgumentException ex) {
            return buildResult(SEV_ERROR, MSG_BAD_PIC, RESULT_BAD_PIC_STRING,
                    dateToTest, dateFormat);
        }

        // Empty input maps to INSUFFICIENT-DATA per CEEDAYS feedback-code
        // convention.
        if (trimmedDate.isEmpty()) {
            return buildResult(SEV_ERROR, MSG_INSUFFICIENT, RESULT_INSUFFICIENT,
                    dateToTest, dateFormat);
        }

        // Attempt the strict parse. The COBOL CEEDAYS service is replaced
        // here; the FC-* feedback codes are inferred from the
        // DateTimeParseException message categories.
        try {
            LocalDate.parse(trimmedDate, formatter);
            return buildResult(SEV_OK, MSG_OK, RESULT_DATE_IS_VALID,
                    dateToTest, dateFormat);
        } catch (DateTimeParseException ex) {
            return classifyDateTimeParseException(ex, dateToTest, dateFormat);
        } catch (DateTimeException ex) {
            // Unexpected — treat as generic invalid (WHEN OTHER branch in
            // the COBOL EVALUATE).
            return buildResult(SEV_ERROR, MSG_DATE_INVALID, RESULT_DATE_IS_INVALID,
                    dateToTest, dateFormat);
        }
    }

    /**
     * Converts a COBOL date format mask (e.g., {@code "YYYYMMDD"}) into a
     * Java {@link DateTimeFormatter} with {@link ResolverStyle#STRICT}.
     *
     * <p>The conversion substitutes {@code Y} &rarr; {@code u} (proleptic
     * year, required for strict resolution of bare 4-digit years) and
     * {@code D} &rarr; {@code d} (day-of-month). Other format characters
     * (e.g., {@code M} for month, {@code -} / {@code /} / {@code .} for
     * separators) are passed through unchanged.
     *
     * <p>For the special case of {@code "YYYYMMDD"} (the most common mask),
     * the precomputed {@link DateConstants#YYYYMMDD} formatter is returned
     * directly to avoid recompilation.
     *
     * @param cobolMask the COBOL format mask, e.g., {@code "YYYYMMDD"} or
     *                  {@code "YYYY-MM-DD"}
     * @return a Java {@link DateTimeFormatter} configured with
     *         {@link ResolverStyle#STRICT}
     * @throws IllegalArgumentException if the resulting Java pattern is
     *                                  invalid
     */
    private static DateTimeFormatter parseMask(String cobolMask) {
        if ("YYYYMMDD".equals(cobolMask)) {
            return DateConstants.YYYYMMDD;
        }
        final String javaPattern = cobolMask.replace('Y', 'u').replace('D', 'd');
        return DateTimeFormatter.ofPattern(javaPattern)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    /**
     * Inspects a {@link DateTimeParseException} and produces the closest
     * matching CEEDAYS-style {@link DateValidationResult}. The mapping is
     * heuristic &mdash; best-effort approximation of the CEEDAYS feedback
     * codes for the most common error categories. The user-visible
     * {@code WS-RESULT} text is preserved verbatim from the COBOL source.
     */
    private static DateValidationResult classifyDateTimeParseException(
            DateTimeParseException ex, String dateToTest, String dateFormat
    ) {
        final String msg = ex.getMessage();
        if (msg == null) {
            return buildResult(SEV_ERROR, MSG_DATE_INVALID, RESULT_DATE_IS_INVALID,
                    dateToTest, dateFormat);
        }
        final String lower = msg.toLowerCase(Locale.ROOT);
        final String trimmedDate = dateToTest.stripTrailing();

        // Insufficient input — input shorter than the expected mask digit count
        if (lower.contains("could not be parsed")
                && trimmedDate.length() < CCYYMMDD_LENGTH) {
            return buildResult(SEV_ERROR, MSG_INSUFFICIENT, RESULT_INSUFFICIENT,
                    dateToTest, dateFormat);
        }
        // Invalid month (MonthOfYear out of range, e.g., month=13 or month=00)
        if (lower.contains("monthofyear") || lower.contains("month")) {
            return buildResult(SEV_ERROR, MSG_INVALID_MONTH, RESULT_INVALID_MONTH,
                    dateToTest, dateFormat);
        }
        // Day-of-month invalid (e.g., Feb 30, Apr 31)
        if (lower.contains("dayofmonth") || lower.contains("day of month")
                || lower.contains("invalid date")) {
            return buildResult(SEV_ERROR, MSG_BAD_DATE, RESULT_DATEVALUE_ERROR,
                    dateToTest, dateFormat);
        }
        // Year out of supported range
        if (lower.contains("year")
                && (lower.contains("range") || lower.contains("out"))) {
            return buildResult(SEV_ERROR, MSG_UNSUPP_RANGE, RESULT_UNSUPP_RANGE,
                    dateToTest, dateFormat);
        }
        // Non-numeric characters where digits expected
        if (lower.contains("could not be parsed") || lower.contains("text")) {
            final boolean allDigitsOrPunct = trimmedDate.chars()
                    .allMatch(c -> Character.isDigit(c)
                            || c == '-' || c == '/' || c == '.' || c == ' ');
            if (!allDigitsOrPunct) {
                return buildResult(SEV_ERROR, MSG_NONNUMERIC, RESULT_NONNUMERIC_DATA,
                        dateToTest, dateFormat);
            }
            return buildResult(SEV_ERROR, MSG_BAD_DATE, RESULT_DATEVALUE_ERROR,
                    dateToTest, dateFormat);
        }
        // Default — generic invalid (WHEN OTHER in COBOL EVALUATE)
        return buildResult(SEV_ERROR, MSG_DATE_INVALID, RESULT_DATE_IS_INVALID,
                dateToTest, dateFormat);
    }

    /**
     * Builds a {@link DateValidationResult} with the given severity, message
     * number, result text, and echoed input fields. Pads {@code testDate}
     * and {@code dateFormat} to the COBOL PIC X(10) width using
     * {@link #rightPad(String, int)}.
     *
     * @param severity   the 4-character severity (e.g., {@code "0000"})
     * @param msgNo      the 4-character message number
     * @param result     the 15-character result text
     * @param testDate   the original input date (will be padded/truncated
     *                   to 10 chars)
     * @param dateFormat the original input format (will be padded/truncated
     *                   to 10 chars)
     * @return a well-formed {@link DateValidationResult}
     */
    private static DateValidationResult buildResult(
            String severity, String msgNo, String result,
            String testDate, String dateFormat
    ) {
        // DateValidationResult's compact constructor enforces exact widths
        // (4/4/15/10/10). All RESULT_* constants are already 15 chars.
        // The severity/msgNo args are always 4-char string constants here.
        // Only testDate and dateFormat (caller-supplied) need explicit
        // width control via rightPad.
        final String paddedDate = rightPad(testDate, LS_DATE_LENGTH);
        final String paddedFormat = rightPad(dateFormat, LS_DATE_LENGTH);
        return new DateValidationResult(severity, msgNo, result, paddedDate, paddedFormat);
    }


    /** {@code FC-INVALID-MONTH} message number ({@code X'09D5'} = 2517). */
    private static final String MSG_INVALID_MONTH = "2517";

    /** {@code FC-BAD-PIC-STRING} message number ({@code X'09D6'} = 2518). */
    private static final String MSG_BAD_PIC = "2518";

    /** {@code FC-NON-NUMERIC-DATA} message number ({@code X'09D8'} = 2520). */
    private static final String MSG_NONNUMERIC = "2520";

    /** {@code FC-YEAR-IN-ERA-ZERO} message number ({@code X'09D9'} = 2521). */
    private static final String MSG_YEAR_ERA_ZERO = "2521";

    /**
     * Catch-all message number for the {@code WHEN OTHER} EVALUATE branch
     * (CSUTLDTC.cbl line 134), i.e. any failure not matching a known
     * {@code FC-*} 88-level. Value {@code "9999"} is chosen as a sentinel
     * outside the range of real LE message numbers; the COBOL source itself
     * leaves {@code WS-MSG-NO-N} at its initialized value in this case.
     */
    private static final String MSG_DATE_INVALID = "9999";

    // =========================================================================
    // formatAsLsResult — 80-byte LS-RESULT formatter
    // =========================================================================

    /**
     * Formats a {@link DateValidationResult} as the 80-byte
     * {@code LS-RESULT} string per the COBOL CSUTLDTC LINKAGE SECTION
     * layout defined in {@code app/cbl/CSUTLDTC.cbl} lines 42-57.
     *
     * <p>Layout:
     * <pre>
     * Offset Length Field
     * ------ ------ -------------------------
     *      0      4 WS-SEVERITY
     *      4     11 FILLER 'Mesg Code: '   (10 chars + 1 trailing space)
     *     15      4 WS-MSG-NO
     *     19      1 FILLER (space)
     *     20     15 WS-RESULT
     *     35      1 FILLER (space)
     *     36      9 FILLER 'TstDate: '     (8 chars + 1 trailing space)
     *     45     10 WS-DATE
     *     55      1 FILLER (space)
     *     56     10 FILLER 'Mask used:'    (10 chars exactly)
     *     66     10 WS-DATE-FMT
     *     76      1 FILLER (space)
     *     77      3 FILLER (3 spaces)
     * Total:    80 bytes
     * </pre>
     *
     * <p>Note that the COBOL {@code FILLER PIC X(11) VALUE 'Mesg Code:'}
     * declaration pads the 10-character literal {@code "Mesg Code:"} with
     * one trailing space to fill its 11-byte slot. Similarly the 9-byte
     * {@code 'TstDate:'} FILLER pads with one trailing space. The 10-byte
     * {@code 'Mask used:'} FILLER is exact.
     *
     * @param r the validation result; must be non-null
     * @return an exactly-80-character String
     * @throws NullPointerException  if {@code r} is null
     * @throws IllegalStateException if the computed length does not match
     *                               the COBOL 80-byte invariant (indicates
     *                               a malformed {@link DateValidationResult}
     *                               that bypassed the canonical constructor)
     */
    public static String formatAsLsResult(DateValidationResult r) {
        Objects.requireNonNull(r, "r");
        // DateValidationResult's canonical constructor already enforces
        // widths (4/4/15/10/10), but rightPad/substring defensively
        // re-normalizes against any value that might have been built by
        // reflection or a future refactor.
        final StringBuilder sb = new StringBuilder(LS_RESULT_LENGTH);
        sb.append(rightPad(r.severity(), 4));         //  0- 3
        sb.append("Mesg Code: ");                      //  4-14 (11 chars)
        sb.append(rightPad(r.msgNo(), 4));            // 15-18
        sb.append(' ');                                // 19
        sb.append(rightPad(r.result(), RESULT_TEXT_LENGTH)); // 20-34
        sb.append(' ');                                // 35
        sb.append("TstDate: ");                        // 36-44 (9 chars)
        sb.append(rightPad(r.testDate(), LS_DATE_LENGTH));   // 45-54
        sb.append(' ');                                // 55
        sb.append("Mask used:");                       // 56-65 (10 chars)
        sb.append(rightPad(r.dateFormat(), LS_DATE_LENGTH)); // 66-75
        sb.append(' ');                                // 76
        sb.append("   ");                              // 77-79 (3 spaces)
        final String s = sb.toString();
        if (s.length() != LS_RESULT_LENGTH) {
            throw new IllegalStateException(
                    "LS-RESULT formatted length is " + s.length()
                            + "; expected " + LS_RESULT_LENGTH);
        }
        return s;
    }

    // =========================================================================
    // Padding and char-classification helpers (COBOL PIC semantics)
    // =========================================================================

    /**
     * Right-pads a String with spaces to the given width (COBOL
     * {@code PIC X(n)} semantics). If the input exceeds the width, it is
     * truncated from the right. A {@code null} input is treated as the
     * empty string.
     *
     * @param s     the input string (may be {@code null})
     * @param width the target width
     * @return a String of exactly {@code width} characters
     */
    private static String rightPad(String s, int width) {
        final String safe = (s == null) ? "" : s;
        if (safe.length() >= width) {
            return safe.substring(0, width);
        }
        return safe + " ".repeat(width - safe.length());
    }

    /**
     * Left-pads a numeric String with zeros to the given width (COBOL
     * {@code PIC 9(n)} semantics). If the input exceeds the width, it is
     * truncated from the LEFT (keeping the least-significant digits, as
     * COBOL {@code PIC 9(n)} does on a {@code MOVE} of a wider source).
     * A {@code null} input is treated as the empty string.
     *
     * @param s     the input numeric string (may be {@code null})
     * @param width the target width
     * @return a String of exactly {@code width} characters
     */
    private static String leftPadZero(String s, int width) {
        final String safe = (s == null) ? "" : s;
        if (safe.length() >= width) {
            return safe.substring(safe.length() - width);
        }
        return "0".repeat(width - safe.length()) + safe;
    }

    /**
     * Returns {@code true} iff every char in the input is {@code 0x00}
     * (the COBOL {@code LOW-VALUE}). Mirrors COBOL
     * {@code <field> EQUAL LOW-VALUES} test. An empty or {@code null} input
     * returns {@code false} (COBOL semantics: {@code LOW-VALUES} test
     * requires at least one character to compare).
     *
     * @param s the input string
     * @return {@code true} iff every char is {@code 0x00}
     */
    private static boolean allLowValues(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return s.chars().allMatch(c -> c == 0);
    }

    // =========================================================================
    // editYearCcyy — EDIT-YEAR-CCYY paragraph
    // =========================================================================

    /**
     * Validates a 4-digit year string. Translates the {@code EDIT-YEAR-CCYY}
     * paragraph from {@code app/cpy/CSUTLDPY.cpy} lines 25-87.
     *
     * <p>Rules (in order, first failure wins):
     * <ul>
     *   <li>Blank year (empty or all-spaces or LOW-VALUES) &rarr;
     *       {@link ValidityFlag.Blank}; message
     *       "{@code <field> : Year must be supplied.}"</li>
     *   <li>Non-numeric &rarr; {@link ValidityFlag.NotOk}; message
     *       "{@code <field> must be 4 digit number.}"</li>
     *   <li>Century not 19 or 20 &rarr; {@link ValidityFlag.NotOk}; message
     *       "{@code <field> : Century is not valid.}"</li>
     *   <li>Otherwise &rarr; {@link ValidityFlag.Valid}</li>
     * </ul>
     *
     * <p><b>Century restriction</b> is verbatim per the COBOL source comment
     * ({@code CSUTLDPY.cpy} lines 66-69): "Not having learnt our lesson
     * from history and Y2K / And being unable to imagine COBOL in the
     * 2100s / We code only 19 and 20 as valid century values."
     *
     * <p>The {@code Century} sealed hierarchy from {@code DateValidationWork}
     * has three permits: {@link Century.ThisCentury} (20),
     * {@link Century.LastCentury} (19), and {@link Century.Other} (anything
     * else). The pattern-matching switch below is exhaustive over those
     * three permits with NO {@code default} branch, per AAP &sect;0.7.3.
     *
     * @param yearString the 4-character year string (may be padded with
     *                   spaces); must be non-null
     * @param fieldName  the human-readable field name for error messages
     *                   (e.g., "DOB Year"); must be non-null
     * @return a {@link FieldValidationResult}
     * @throws NullPointerException if any parameter is null
     */
    public static FieldValidationResult editYearCcyy(String yearString, String fieldName) {
        Objects.requireNonNull(yearString, "yearString");
        Objects.requireNonNull(fieldName, "fieldName");

        final String trimmed = yearString.strip();
        final String fn = fieldName.strip();

        // Not supplied (LOW-VALUES or SPACES)
        if (trimmed.isEmpty() || allLowValues(yearString)) {
            return new FieldValidationResult(false, new ValidityFlag.Blank(),
                    fn + " : Year must be supplied.");
        }

        // Not numeric / wrong length (must be exactly 4 digits)
        if (trimmed.length() != CCYY_LENGTH
                || !trimmed.chars().allMatch(Character::isDigit)) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + " must be 4 digit number.");
        }

        // Century check: only 19 and 20 are valid (COBOL 88-levels
        // THIS-CENTURY VALUE 20 and LAST-CENTURY VALUE 19).
        final int year = Integer.parseInt(trimmed);
        final int century = year / 100;
        final Century c = Century.fromValue(century);
        final boolean centuryOk = switch (c) {
            case Century.ThisCentury t -> true;
            case Century.LastCentury l -> true;
            case Century.Other o -> false;
        };
        if (!centuryOk) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + " : Century is not valid.");
        }

        return new FieldValidationResult(true, new ValidityFlag.Valid(), "");
    }

    // =========================================================================
    // editMonth — EDIT-MONTH paragraph
    // =========================================================================

    /**
     * Validates a 2-digit month string. Translates the {@code EDIT-MONTH}
     * paragraph from {@code app/cpy/CSUTLDPY.cpy} lines 91-147.
     *
     * <p>Rules (in order, first failure wins):
     * <ul>
     *   <li>Blank month (empty/LOW-VALUES) &rarr; {@link ValidityFlag.Blank};
     *       message "{@code <field> : Month must be supplied.}"</li>
     *   <li>Non-numeric OR not in range 1-12 &rarr;
     *       {@link ValidityFlag.NotOk}; message
     *       "{@code <field>: Month must be a number between 1 and 12.}"</li>
     *   <li>Otherwise &rarr; {@link ValidityFlag.Valid}</li>
     * </ul>
     *
     * <p>Note: The COBOL {@code EDIT-MONTH} paragraph has two separate
     * numeric-check branches (one for {@code WS-VALID-MONTH} range, one for
     * {@code FUNCTION TEST-NUMVAL}). Both produce the same error message;
     * Java's {@link Integer#parseInt(String)} after
     * {@link String#strip()} handles both cases cleanly, so a single
     * compound check suffices.
     *
     * @param monthString the 2-character month string; must be non-null
     * @param fieldName   the human-readable field name; must be non-null
     * @return a {@link FieldValidationResult}
     * @throws NullPointerException if any parameter is null
     */
    public static FieldValidationResult editMonth(String monthString, String fieldName) {
        Objects.requireNonNull(monthString, "monthString");
        Objects.requireNonNull(fieldName, "fieldName");

        final String trimmed = monthString.strip();
        final String fn = fieldName.strip();

        if (trimmed.isEmpty() || allLowValues(monthString)) {
            return new FieldValidationResult(false, new ValidityFlag.Blank(),
                    fn + " : Month must be supplied.");
        }
        if (!trimmed.chars().allMatch(Character::isDigit)) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + ": Month must be a number between 1 and 12.");
        }
        final int month = Integer.parseInt(trimmed);
        if (!DateRules.isValidMonth(month)) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + ": Month must be a number between 1 and 12.");
        }
        return new FieldValidationResult(true, new ValidityFlag.Valid(), "");
    }

    // =========================================================================
    // editDay — EDIT-DAY paragraph
    // =========================================================================

    /**
     * Validates a 2-digit day string (range 1-31, content only, not yet
     * cross-validated with month/year). Translates the {@code EDIT-DAY}
     * paragraph from {@code app/cpy/CSUTLDPY.cpy} lines 150-207.
     *
     * <p>Rules (in order, first failure wins):
     * <ul>
     *   <li>Blank day (empty/LOW-VALUES) &rarr; {@link ValidityFlag.Blank};
     *       message "{@code <field> : Day must be supplied.}"</li>
     *   <li>Non-numeric OR not in range 1-31 &rarr;
     *       {@link ValidityFlag.NotOk}; message
     *       "{@code <field>:day must be a number between 1 and 31.}"</li>
     *   <li>Otherwise &rarr; {@link ValidityFlag.Valid}</li>
     * </ul>
     *
     * <p>Cross-field rules (day vs month, leap-year rules) are NOT applied
     * here; they belong to {@link #editDayMonthYear(int, int, int, String)}.
     *
     * @param dayString the 2-character day string; must be non-null
     * @param fieldName the human-readable field name; must be non-null
     * @return a {@link FieldValidationResult}
     * @throws NullPointerException if any parameter is null
     */
    public static FieldValidationResult editDay(String dayString, String fieldName) {
        Objects.requireNonNull(dayString, "dayString");
        Objects.requireNonNull(fieldName, "fieldName");

        final String trimmed = dayString.strip();
        final String fn = fieldName.strip();

        if (trimmed.isEmpty() || allLowValues(dayString)) {
            return new FieldValidationResult(false, new ValidityFlag.Blank(),
                    fn + " : Day must be supplied.");
        }
        if (!trimmed.chars().allMatch(Character::isDigit)) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + ":day must be a number between 1 and 31.");
        }
        final int day = Integer.parseInt(trimmed);
        if (!DateRules.isValidDay(day)) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + ":day must be a number between 1 and 31.");
        }
        return new FieldValidationResult(true, new ValidityFlag.Valid(), "");
    }

    // =========================================================================
    // editDayMonthYear — EDIT-DAY-MONTH-YEAR paragraph (cross-field validation)
    // =========================================================================

    /**
     * Cross-field date validation: 31-day months, February 30, February 29
     * leap-year rule. Translates the {@code EDIT-DAY-MONTH-YEAR} paragraph
     * from {@code app/cpy/CSUTLDPY.cpy} lines 209-282.
     *
     * <p>Rules (in order, first failure wins; later checks short-circuit):
     * <ol>
     *   <li>NOT a 31-day month AND day=31 &rarr; {@link ValidityFlag.NotOk};
     *       message "{@code <field>:Cannot have 31 days in this month.}"</li>
     *   <li>February AND day=30 &rarr; {@link ValidityFlag.NotOk};
     *       message "{@code <field>:Cannot have 30 days in this month.}"</li>
     *   <li>February AND day=29 AND NOT a COBOL leap year &rarr;
     *       {@link ValidityFlag.NotOk}; message
     *       "{@code <field>:Not a leap year.Cannot have 29 days in this month.}"</li>
     * </ol>
     *
     * <p><b>COBOL-equivalent leap-year algorithm</b> (lines 245-272 of
     * {@code CSUTLDPY.cpy}):
     * <pre>
     *   IF year mod 100 = 0  THEN  divBy = 400
     *   ELSE                       divBy = 4
     *   IF year mod divBy = 0  THEN  leap year
     * </pre>
     * This mirrors the COBOL source verbatim. It is delegated to the
     * {@link #isCobolLeapYear(int)} private helper. The COBOL algorithm
     * produces identical results to {@link LocalDate#isLeapYear()} for all
     * valid year inputs (1..9999); however, AAP &sect;0.7.1 mandates
     * idiom-for-idiom translation of business rules, so this implementation
     * uses the COBOL form rather than the built-in {@code java.time} rule.
     *
     * @param year      the 4-digit year (already validated by
     *                  {@link #editYearCcyy(String, String)})
     * @param month     the 2-digit month (already validated by
     *                  {@link #editMonth(String, String)})
     * @param day       the 2-digit day (already validated by
     *                  {@link #editDay(String, String)})
     * @param fieldName the human-readable field name; must be non-null
     * @return a {@link FieldValidationResult}
     * @throws NullPointerException if {@code fieldName} is null
     */
    public static FieldValidationResult editDayMonthYear(
            int year, int month, int day, String fieldName
    ) {
        Objects.requireNonNull(fieldName, "fieldName");
        final String fn = fieldName.strip();

        // Rule 1: NOT a 31-day month AND day=31
        if (!DateRules.is31DayMonth(month) && DateRules.isDay31(day)) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + ":Cannot have 31 days in this month.");
        }
        // Rule 2: February AND day=30
        if (DateRules.isFebruary(month) && DateRules.isDay30(day)) {
            return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                    fn + ":Cannot have 30 days in this month.");
        }
        // Rule 3: February AND day=29 must be a (COBOL) leap year
        if (DateRules.isFebruary(month) && DateRules.isDay29(day)) {
            if (!isCobolLeapYear(year)) {
                return new FieldValidationResult(false, new ValidityFlag.NotOk(),
                        fn + ":Not a leap year.Cannot have 29 days in this month.");
            }
        }
        return new FieldValidationResult(true, new ValidityFlag.Valid(), "");
    }

    /**
     * COBOL-equivalent leap-year test from the {@code EDIT-DAY-MONTH-YEAR}
     * paragraph (lines 245-272 of {@code app/cpy/CSUTLDPY.cpy}).
     *
     * <p>Algorithm:
     * <ul>
     *   <li>If {@code year mod 100 == 0}: divide year by 400; remainder 0
     *       &rarr; leap year.</li>
     *   <li>Else: divide year by 4; remainder 0 &rarr; leap year.</li>
     * </ul>
     *
     * <p>This is the EXACT COBOL algorithm; do NOT simplify to the
     * Gregorian rule embedded in {@link LocalDate#isLeapYear()}. The two
     * algorithms produce identical results for all valid 4-digit Gregorian
     * years (1..9999); using the COBOL form preserves source-level fidelity
     * per AAP &sect;0.7.1.
     *
     * @param year the 4-digit year
     * @return {@code true} iff the year is a leap year under the COBOL rule
     */
    private static boolean isCobolLeapYear(int year) {
        final int divBy = (year % 100 == 0) ? 400 : 4;
        return (year % divBy) == 0;
    }

    // =========================================================================
    // editDateLe — EDIT-DATE-LE paragraph
    // =========================================================================

    /**
     * Final LE-service date validation. Translates the {@code EDIT-DATE-LE}
     * paragraph from {@code app/cpy/CSUTLDPY.cpy} lines 284-328.
     *
     * <p>Calls {@link #validate(String, String)} with the format
     * {@code "YYYYMMDD"}; if severity is non-zero (any non-{@code "0000"}
     * value), returns a {@link FieldValidationResult} marking the date
     * invalid with the COBOL-style message
     * "{@code <field> validation error Sev code: <sev> Message code: <msg>}".
     *
     * <p>The exact message format is preserved verbatim from
     * {@code CSUTLDPY.cpy} lines 308-311 (a single STRING statement
     * concatenating the field name, the literal " validation error Sev
     * code: ", the severity, the literal " Message code: ", and the
     * message number).
     *
     * @param dateString the 8-character CCYYMMDD date; must be non-null
     * @param fieldName  the human-readable field name; must be non-null
     * @return a {@link FieldValidationResult}
     * @throws NullPointerException if any parameter is null
     */
    public static FieldValidationResult editDateLe(String dateString, String fieldName) {
        Objects.requireNonNull(dateString, "dateString");
        Objects.requireNonNull(fieldName, "fieldName");
        final String fn = fieldName.strip();

        final DateValidationResult lr = validate(dateString, "YYYYMMDD");
        if (SEV_OK.equals(lr.severity())) {
            return new FieldValidationResult(true, new ValidityFlag.Valid(), "");
        }
        // Strip stored padding from severity/msgNo (DateValidationResult stores
        // them as PIC X(4)) so the message reads naturally without trailing
        // whitespace inside the concatenated text.
        final String sev = lr.severity().strip();
        final String msg = lr.msgNo().strip();
        final String returnMsg = fn + " validation error Sev code: " + sev
                + " Message code: " + msg;
        return new FieldValidationResult(false, new ValidityFlag.NotOk(), returnMsg);
    }

    // =========================================================================
    // validateCcyymmdd — Full EDIT-DATE-CCYYMMDD pipeline orchestrator
    // =========================================================================

    /**
     * Orchestrates the full {@code EDIT-DATE-CCYYMMDD} pipeline: year
     * &rarr; month &rarr; day &rarr; cross-field &rarr; LE service.
     * Translates the COBOL caller pattern:
     *
     * <pre>
     *     PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT
     * </pre>
     *
     * <p>The first failure short-circuits the pipeline (matching the
     * COBOL {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} flow). The
     * {@code WS-RETURN-MSG-OFF} guard in the COBOL source ensures only
     * the first error message is captured; this translation does the same
     * by short-circuiting at the first invalid {@link FieldValidationResult}.
     *
     * <p>When a cross-field failure (Rule 3 of
     * {@link #editDayMonthYear}) involves the leap-year rule, the COBOL
     * source sets all three flags (year, month, day) to NOT-OK; the other
     * cross-field rules set day+month to NOT-OK and leave year as Valid.
     * The mapping below preserves that distinction.
     *
     * @param dateString the 8-character CCYYMMDD date; must be non-null
     * @param fieldName  the human-readable field name; must be non-null
     * @return a {@link ValidationOutcome} with per-field flags, the overall
     *         validity boolean, the COBOL-style return message, and the
     *         {@link DateValidationResult} from the final LE service call
     *         (or {@code null} if the LE call was short-circuited)
     * @throws NullPointerException if any parameter is null
     */
    public static ValidationOutcome validateCcyymmdd(String dateString, String fieldName) {
        Objects.requireNonNull(dateString, "dateString");
        Objects.requireNonNull(fieldName, "fieldName");

        // Decompose CCYYMMDD into CCYY / MM / DD using safe substring on
        // a 10-char-padded copy (mirrors COBOL PIC X(10) right-padding).
        final String padded = rightPad(dateString, CCYYMMDD_LENGTH);
        final String yearStr = padded.substring(0, CCYY_LENGTH);
        final String monthStr = padded.substring(CCYY_LENGTH, CCYY_LENGTH + MM_LENGTH);
        final String dayStr = padded.substring(CCYY_LENGTH + MM_LENGTH, CCYYMMDD_LENGTH);

        // EDIT-YEAR-CCYY
        final FieldValidationResult yr = editYearCcyy(yearStr, fieldName);
        if (!yr.isValid()) {
            return new ValidationOutcome(false,
                    yr.flag(),
                    new ValidityFlag.Valid(),
                    new ValidityFlag.Valid(),
                    yr.returnMsg(),
                    null);
        }

        // EDIT-MONTH
        final FieldValidationResult mr = editMonth(monthStr, fieldName);
        if (!mr.isValid()) {
            return new ValidationOutcome(false,
                    yr.flag(),
                    mr.flag(),
                    new ValidityFlag.Valid(),
                    mr.returnMsg(),
                    null);
        }

        // EDIT-DAY (initial range check 1-31)
        final FieldValidationResult dr = editDay(dayStr, fieldName);
        if (!dr.isValid()) {
            return new ValidationOutcome(false,
                    yr.flag(),
                    mr.flag(),
                    dr.flag(),
                    dr.returnMsg(),
                    null);
        }

        // Parse the validated digit triple before cross-field validation
        final int yearN = Integer.parseInt(yearStr.strip());
        final int monthN = Integer.parseInt(monthStr.strip());
        final int dayN = Integer.parseInt(dayStr.strip());

        // EDIT-DAY-MONTH-YEAR (cross-field)
        final FieldValidationResult cr = editDayMonthYear(yearN, monthN, dayN, fieldName);
        if (!cr.isValid()) {
            // Per CSUTLDPY: the leap-year branch sets year+month+day to NOT-OK;
            // the two non-leap-year branches set day+month to NOT-OK and leave
            // year as VALID. Detect by inspecting the assembled return message.
            final boolean isLeapBranch = cr.returnMsg().contains("leap year");
            final ValidityFlag yearFlagOut = isLeapBranch
                    ? cr.flag()
                    : new ValidityFlag.Valid();
            return new ValidationOutcome(false,
                    yearFlagOut,
                    cr.flag(),
                    cr.flag(),
                    cr.returnMsg(),
                    null);
        }

        // EDIT-DATE-LE (final LE service check). Compute the LE result once
        // and reuse it both for the flag-extraction and for the returned
        // ValidationOutcome.
        final DateValidationResult leResult = validate(padded, "YYYYMMDD");
        if (!SEV_OK.equals(leResult.severity())) {
            final String sev = leResult.severity().strip();
            final String msg = leResult.msgNo().strip();
            final String fn = fieldName.strip();
            final String returnMsg = fn + " validation error Sev code: " + sev
                    + " Message code: " + msg;
            // COBOL EDIT-DATE-LE failure path sets year+month+day all NOT-OK
            // (lines 295-300 of CSUTLDPY.cpy).
            return new ValidationOutcome(false,
                    new ValidityFlag.NotOk(),
                    new ValidityFlag.NotOk(),
                    new ValidityFlag.NotOk(),
                    returnMsg,
                    leResult);
        }

        // All checks passed: WS-EDIT-DATE-IS-VALID = TRUE at the COBOL
        // CCYYMMDD-EXIT paragraph.
        return new ValidationOutcome(true,
                new ValidityFlag.Valid(),
                new ValidityFlag.Valid(),
                new ValidityFlag.Valid(),
                "",
                leResult);
    }

    // =========================================================================
    // validateDateOfBirth — EDIT-DATE-OF-BIRTH paragraph
    // =========================================================================

    /**
     * Date-of-birth validation: ensures the supplied date is STRICTLY
     * BEFORE the reference date (typically {@code LocalDate.now()}).
     * Translates the {@code EDIT-DATE-OF-BIRTH} paragraph from
     * {@code app/cpy/CSUTLDPY.cpy} lines 341-372.
     *
     * <p>The COBOL source uses {@code FUNCTION INTEGER-OF-DATE} to compute
     * a day-count from the Gregorian epoch and compares
     * {@code WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY} (strictly
     * greater-than). This means a DOB equal to the reference date is
     * REJECTED. This Java translation uses
     * {@link LocalDate#isBefore(java.time.chrono.ChronoLocalDate)} which
     * has identical strictly-before semantics.
     *
     * <p>If the supplied date is unparseable (caller should have invoked
     * {@link #validateCcyymmdd(String, String)} first, but defensive
     * handling is provided), this method returns a {@link ValidationOutcome}
     * with all three field flags marked {@link ValidityFlag.NotOk} and
     * the message "{@code <field> : invalid date format}".
     *
     * <p>The returned {@link ValidationOutcome#leResult()} is always
     * {@code null} for this method: the EDIT-DATE-OF-BIRTH paragraph does
     * not invoke CSUTLDTC; it is purely a binary date comparison.
     *
     * @param dateString    the 8-character CCYYMMDD date of birth; must be
     *                      non-null
     * @param fieldName     the human-readable field name; must be non-null
     * @param referenceDate the reference date (typically
     *                      {@code LocalDate.now()}); must be non-null
     * @return a {@link ValidationOutcome}
     * @throws NullPointerException if any parameter is null
     */
    public static ValidationOutcome validateDateOfBirth(
            String dateString, String fieldName, LocalDate referenceDate
    ) {
        Objects.requireNonNull(dateString, "dateString");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(referenceDate, "referenceDate");
        final String fn = fieldName.strip();

        final LocalDate dob;
        try {
            dob = LocalDate.parse(dateString.strip(), DateConstants.YYYYMMDD);
        } catch (DateTimeException ex) {
            // Defensive: caller should have validated via validateCcyymmdd
            // first. The COBOL paragraph does not include this guard, but
            // refusing to crash here is safer and produces a clear message.
            return new ValidationOutcome(false,
                    new ValidityFlag.NotOk(),
                    new ValidityFlag.NotOk(),
                    new ValidityFlag.NotOk(),
                    fn + " : invalid date format",
                    null);
        }

        if (dob.isBefore(referenceDate)) {
            // DOB is strictly in the past — valid.
            return new ValidationOutcome(true,
                    new ValidityFlag.Valid(),
                    new ValidityFlag.Valid(),
                    new ValidityFlag.Valid(),
                    "",
                    null);
        }

        // DOB is today or in the future — invalid per COBOL strict comparison.
        // COBOL EDIT-DATE-OF-BIRTH sets all three NOT-OK flags (lines 364-367).
        return new ValidationOutcome(false,
                new ValidityFlag.NotOk(),
                new ValidityFlag.NotOk(),
                new ValidityFlag.NotOk(),
                fn + ":cannot be in the future ",
                null);
    }
}

