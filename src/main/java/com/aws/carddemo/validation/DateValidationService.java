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
package com.aws.carddemo.validation;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;

/**
 * Date-format and semantic-validation service replacing the COBOL program
 * {@code app/cbl/CSUTLDTC.cbl} (157 lines, TRANID none — utility called via
 * {@code CALL "CSUTLDTC"}).
 *
 * <p>The COBOL implementation called the IBM LE {@code CEEDAYS} intrinsic to
 * parse a date string against a format mask, capturing the API's
 * {@code FEEDBACK-CODE} byte string into one of nine {@code WS-RESULT}
 * tokens (see {@link DateValidationResult} for the contractual mapping). The
 * Java migration replaces {@code CEEDAYS} with {@link DateTimeFormatter}
 * configured in {@link ResolverStyle#STRICT STRICT} mode plus a small
 * cascade of pre-parse format checks that map cleanly to the historical
 * {@code FEEDBACK-CODE} verdicts.
 *
 * <h2>Supported masks (AAP §0.10.2 Minimal Change Clause)</h2>
 *
 * <p>The two masks used in production COBOL paths and asserted in
 * {@code date_validation_variants.csv} are:
 *
 * <ul>
 *   <li>{@code "YYYY-MM-DD"} — ISO-8601 calendar date (the only mask used
 *       by {@code COACTUPC}, {@code COCRDUPC}, and the batch programs)</li>
 *   <li>{@code "MM/DD/YYYY"} — US slash-separated calendar date (used by
 *       {@code CORPT00C} report start/end-date entry)</li>
 * </ul>
 *
 * <p>Any other mask value yields {@link DateValidationResult#REASON_BAD_PIC_STRING}
 * — preserving the COBOL {@code FC-BAD-PIC-STRING} feedback for inputs the
 * CEEDAYS intrinsic could not parse.
 *
 * <h2>Validation cascade</h2>
 *
 * <p>The cascade matches the order in which {@code CEEDAYS} surfaces its
 * feedback codes (mirroring the COBOL {@code EVALUATE TRUE/WHEN FC-*}
 * paragraph at lines 122–147 of {@code CSUTLDTC.cbl}):
 *
 * <ol>
 *   <li>{@code mask} is {@code null}, empty, or not a supported value →
 *       {@link DateValidationResult#REASON_BAD_PIC_STRING}
 *       (COBOL: {@code FC-BAD-PIC-STRING})</li>
 *   <li>{@code date} is {@code null} or empty →
 *       {@link DateValidationResult#REASON_INSUFFICIENT}
 *       (COBOL: {@code FC-INSUFFICIENT-DATA})</li>
 *   <li>Separator characters at IN-BOUNDS positions of the input do not
 *       match the mask's separator character. Verdict depends on length:
 *       <ul>
 *         <li>length matches required → {@link DateValidationResult#REASON_BAD_PIC_STRING}
 *             (the mask itself is the wrong pic for this input, e.g.
 *             {@code "2024-02-29"} given mask {@code "MM/DD/YYYY"})</li>
 *         <li>length is short → {@link DateValidationResult#REASON_DATEVALUE_ERROR}
 *             (the input is a different format altogether, e.g.
 *             {@code "20241215"} given mask {@code "YYYY-MM-DD"})</li>
 *       </ul></li>
 *   <li>{@code date.length()} less than required length for the mask AND
 *       in-bounds separators are correct →
 *       {@link DateValidationResult#REASON_INSUFFICIENT}
 *       (COBOL: {@code FC-INSUFFICIENT-DATA} — e.g. {@code "2024-01"}
 *       given mask {@code "YYYY-MM-DD"})</li>
 *   <li>{@code date.length()} not equal to the mask's required length →
 *       {@link DateValidationResult#REASON_DATEVALUE_ERROR}
 *       (CEEDAYS surfaces an excess-length input as a date-value error)</li>
 *   <li>{@code date} contains non-digit characters in numeric positions →
 *       {@link DateValidationResult#REASON_NONNUMERIC_DATA}
 *       (COBOL: {@code FC-NON-NUMERIC-DATA})</li>
 *   <li>The year component is {@code 0000} →
 *       {@link DateValidationResult#REASON_YEAR_IN_ERA_IS_ZERO}
 *       (COBOL: {@code FC-YEAR-IN-ERA-ZERO})</li>
 *   <li>The month component is outside {@code 01–12} →
 *       {@link DateValidationResult#REASON_INVALID_MONTH}
 *       (COBOL: {@code FC-INVALID-MONTH})</li>
 *   <li>{@link DateTimeFormatter} in STRICT mode fails to resolve the date
 *       (e.g., {@code 2024-02-30}, {@code 1900-02-29}) →
 *       {@link DateValidationResult#REASON_DATEVALUE_ERROR}
 *       (COBOL: {@code FC-BAD-DATE-VALUE})</li>
 *   <li>Otherwise → {@link DateValidationResult#valid()}
 *       (COBOL: {@code FC-INVALID-DATE} — note the COBOL idiom: this
 *       condition fires when the date is NOT invalid)</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * <p>The service holds only an immutable {@link Map} of supported mask
 * descriptors as a {@code static final} field. Instances carry no
 * per-call state; a single instance can safely serve any number of
 * concurrent callers, including JUnit 5 parallel tests.
 *
 * @see DateValidationResult
 * @see ResolverStyle#STRICT
 */
public final class DateValidationService {

    // ===================================================================
    // Supported masks — each entry carries the mask literal, the required
    // string length, and the {@link DateTimeFormatter} configured in STRICT
    // resolver mode (so 2024-02-30 fails rather than rolling forward to
    // 2024-03-01).
    // ===================================================================

    /**
     * Internal descriptor for a supported date-format mask. Carries the
     * literal mask value (e.g., {@code "YYYY-MM-DD"}), the exact required
     * string length (e.g., {@code 10}), the regular-expression positions of
     * the numeric components (year, month, day), and the
     * {@link DateTimeFormatter} pre-built for that mask in STRICT mode.
     */
    private static final class MaskDescriptor {

        /** Required string length (e.g., 10 for "YYYY-MM-DD"). */
        final int requiredLength;
        /** Index of the start of the year component (e.g., 0 for "YYYY-MM-DD"). */
        final int yearStart;
        /** Index of the start of the month component (e.g., 5 for "YYYY-MM-DD"). */
        final int monthStart;
        /** Index of the start of the day component (e.g., 8 for "YYYY-MM-DD"). */
        final int dayStart;
        /** Width of the year component (e.g., 4). */
        final int yearWidth;
        /** Width of the month component (e.g., 2). */
        final int monthWidth;
        /** Width of the day component (e.g., 2). */
        final int dayWidth;
        /** Indexes of the literal separator characters (e.g., 4 and 7 for "-" in "YYYY-MM-DD"). */
        final int[] separatorIndexes;
        /** Expected literal separator character (e.g., '-' for "YYYY-MM-DD"). */
        final char separatorChar;
        /** STRICT-mode formatter for the supported mask. */
        final DateTimeFormatter formatter;

        MaskDescriptor(int requiredLength,
                       int yearStart, int yearWidth,
                       int monthStart, int monthWidth,
                       int dayStart, int dayWidth,
                       int[] separatorIndexes,
                       char separatorChar,
                       DateTimeFormatter formatter) {
            this.requiredLength = requiredLength;
            this.yearStart = yearStart;
            this.yearWidth = yearWidth;
            this.monthStart = monthStart;
            this.monthWidth = monthWidth;
            this.dayStart = dayStart;
            this.dayWidth = dayWidth;
            this.separatorIndexes = separatorIndexes;
            this.separatorChar = separatorChar;
            this.formatter = formatter;
        }
    }

    private static final Map<String, MaskDescriptor> SUPPORTED_MASKS = buildSupportedMasks();

    private static Map<String, MaskDescriptor> buildSupportedMasks() {
        MaskDescriptor isoDescriptor = new MaskDescriptor(
                /* requiredLength */ 10,
                /* yearStart    */ 0, /* yearWidth   */ 4,
                /* monthStart   */ 5, /* monthWidth  */ 2,
                /* dayStart     */ 8, /* dayWidth    */ 2,
                /* separators   */ new int[] {4, 7},
                /* separatorChar*/ '-',
                DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT));
        MaskDescriptor usDescriptor = new MaskDescriptor(
                /* requiredLength */ 10,
                /* yearStart    */ 6, /* yearWidth   */ 4,
                /* monthStart   */ 0, /* monthWidth  */ 2,
                /* dayStart     */ 3, /* dayWidth    */ 2,
                /* separators   */ new int[] {2, 5},
                /* separatorChar*/ '/',
                DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT));
        return Map.of(
                "YYYY-MM-DD", isoDescriptor,
                "MM/DD/YYYY", usDescriptor);
    }

    /**
     * Creates a stateless {@code DateValidationService} instance. The service
     * carries no per-instance state, so a single instance can safely serve
     * any number of concurrent callers.
     */
    public DateValidationService() {
        // No instance state; the supported-masks map is static-final.
    }

    /**
     * Validates a date string against a supported format mask.
     *
     * <p>The full validation cascade is documented at the class level; in
     * brief: the method rejects unsupported masks with
     * {@link DateValidationResult#REASON_BAD_PIC_STRING}, short/empty inputs
     * with {@link DateValidationResult#REASON_INSUFFICIENT}, length mismatch
     * with {@link DateValidationResult#REASON_DATEVALUE_ERROR}, non-digit
     * components with {@link DateValidationResult#REASON_NONNUMERIC_DATA},
     * year=0000 with {@link DateValidationResult#REASON_YEAR_IN_ERA_IS_ZERO},
     * out-of-range months with {@link DateValidationResult#REASON_INVALID_MONTH},
     * semantically-invalid dates (e.g., {@code 2024-02-30}) with
     * {@link DateValidationResult#REASON_DATEVALUE_ERROR}, and accepts every
     * other input with {@link DateValidationResult#valid()}.
     *
     * @param date the candidate date string to validate; may be {@code null}
     *             (treated as "no date" → REASON_INSUFFICIENT)
     * @param mask the format mask the date should conform to; must equal one
     *             of the supported mask literals (currently
     *             {@code "YYYY-MM-DD"} or {@code "MM/DD/YYYY"})
     * @return a {@link DateValidationResult} carrying the verdict and the
     *         contractual reason string
     */
    public DateValidationResult validate(String date, String mask) {
        // Step 1 — mask validation (COBOL FC-BAD-PIC-STRING).
        if (mask == null || mask.isEmpty()) {
            return DateValidationResult.invalid(DateValidationResult.REASON_BAD_PIC_STRING);
        }
        MaskDescriptor descriptor = SUPPORTED_MASKS.get(mask);
        if (descriptor == null) {
            return DateValidationResult.invalid(DateValidationResult.REASON_BAD_PIC_STRING);
        }

        // Step 2 — input null / empty (COBOL FC-INSUFFICIENT-DATA).
        if (date == null || date.isEmpty()) {
            return DateValidationResult.invalid(DateValidationResult.REASON_INSUFFICIENT);
        }

        // Step 3 — separator characters at IN-BOUNDS positions must match
        // the mask literal. This check fires BEFORE the length check because
        // CEEDAYS surfaces three distinct conditions depending on whether
        // the input has the mask's separator chars in the right places:
        //
        //   * "20241215" given "YYYY-MM-DD": length 8, position 4 is '1'
        //     (not '-'). The input is in a completely different format from
        //     the mask, so CEEDAYS surfaces FC-BAD-DATE-VALUE → DATEVALUE_ERROR.
        //
        //   * "2024-02-29" given "MM/DD/YYYY": length 10, position 2 is '2'
        //     (not '/'). The input matches the mask's length but uses
        //     different separators — i.e. the supplied pic does not fit
        //     the data — so CEEDAYS surfaces FC-BAD-PIC-STRING → BAD_PIC_STRING.
        //
        //   * "2024-01" given "YYYY-MM-DD": length 7, position 4 is '-'
        //     (correct). Position 7 is out of bounds (no separator to check
        //     there because the input is truncated). All in-bounds
        //     separators correct → fall through to the length check, which
        //     emits FC-INSUFFICIENT-DATA → INSUFFICIENT.
        boolean separatorsCorrect = true;
        for (int idx : descriptor.separatorIndexes) {
            if (idx < date.length()
                    && date.charAt(idx) != descriptor.separatorChar) {
                separatorsCorrect = false;
                break;
            }
        }
        if (!separatorsCorrect) {
            // Length matches → mask is the wrong pic for the input.
            // Length does not match → input is in a different format
            // altogether (e.g. yyyymmdd given yyyy-mm-dd).
            if (date.length() == descriptor.requiredLength) {
                return DateValidationResult.invalid(DateValidationResult.REASON_BAD_PIC_STRING);
            }
            return DateValidationResult.invalid(DateValidationResult.REASON_DATEVALUE_ERROR);
        }

        // Step 4 — length: less than required is INSUFFICIENT (the input is
        // a truncated valid format because Step 3 confirmed the in-bounds
        // separators line up with the mask); greater is DATEVALUE-ERROR.
        if (date.length() < descriptor.requiredLength) {
            return DateValidationResult.invalid(DateValidationResult.REASON_INSUFFICIENT);
        }
        if (date.length() != descriptor.requiredLength) {
            return DateValidationResult.invalid(DateValidationResult.REASON_DATEVALUE_ERROR);
        }

        // Step 5 — numeric-component validation (COBOL FC-NON-NUMERIC-DATA).
        // Check every position in the year, month, and day fields for a
        // digit. If any non-digit is found, the COBOL FC-NON-NUMERIC-DATA
        // condition fires.
        String yearText = date.substring(descriptor.yearStart,
                descriptor.yearStart + descriptor.yearWidth);
        String monthText = date.substring(descriptor.monthStart,
                descriptor.monthStart + descriptor.monthWidth);
        String dayText = date.substring(descriptor.dayStart,
                descriptor.dayStart + descriptor.dayWidth);
        if (!isAllDigits(yearText) || !isAllDigits(monthText) || !isAllDigits(dayText)) {
            return DateValidationResult.invalid(DateValidationResult.REASON_NONNUMERIC_DATA);
        }

        // Step 6 — year-zero check (COBOL FC-YEAR-IN-ERA-ZERO).
        // CEEDAYS rejects year 0000 because the Gregorian era has no year 0
        // (1 BC immediately precedes AD 1). This check fires BEFORE the
        // month check because COBOL emits FC-YEAR-IN-ERA-ZERO with higher
        // priority than FC-INVALID-MONTH.
        if (parseIntSafe(yearText) == 0) {
            return DateValidationResult.invalid(DateValidationResult.REASON_YEAR_IN_ERA_IS_ZERO);
        }

        // Step 7 — month-range check (COBOL FC-INVALID-MONTH).
        // Months must be in 01–12. CEEDAYS fires FC-INVALID-MONTH for any
        // value outside this range.
        int month = parseIntSafe(monthText);
        if (month < 1 || month > 12) {
            return DateValidationResult.invalid(DateValidationResult.REASON_INVALID_MONTH);
        }

        // Step 8 — semantic date validation via STRICT formatter
        // (COBOL FC-BAD-DATE-VALUE).
        // The formatter is configured in STRICT resolver mode so impossible
        // dates (2024-02-30, 1900-02-29, 2024-04-31) fail rather than
        // rolling forward to the next valid date.
        // DateTimeException is the supertype of DateTimeParseException, so a
        // single catch covers both ParseException and STRICT-resolver
        // semantic failures.
        try {
            LocalDate.parse(date, descriptor.formatter);
            return DateValidationResult.valid();
        } catch (DateTimeException ex) {
            return DateValidationResult.invalid(DateValidationResult.REASON_DATEVALUE_ERROR);
        }
    }

    /**
     * Returns {@code true} if every character of the supplied string is a
     * decimal digit (ASCII {@code '0'}–{@code '9'}). The check is
     * intentionally specific to ASCII digits — the COBOL {@code IS NUMERIC}
     * test that this method replaces accepts only the EBCDIC digit codepoints
     * F0–F9, which translate to ASCII 30–39 in any UTF-8 / ASCII transport.
     *
     * @param value the string to test; assumed non-null by the caller
     * @return {@code true} if every character is in {@code [0-9]};
     *         {@code false} otherwise
     */
    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses the supplied numeric text into an {@code int} without throwing.
     * Callers must have verified via {@link #isAllDigits(String)} that the
     * text consists entirely of decimal digits before calling this method;
     * otherwise the behaviour is undefined (and the broken pre-condition
     * indicates a bug in the calling code).
     *
     * @param value the digit-only string to parse
     * @return the parsed integer value
     */
    private static int parseIntSafe(String value) {
        return Integer.parseInt(value);
    }
}
