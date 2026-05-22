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
package com.awsm2.carddemo.validation;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;

/**
 * Stateless date-validation service that ports the COBOL date-validation cascade
 * from {@code app/cpy/CSUTLDPY.cpy} (validation paragraphs),
 * {@code app/cpy/CSUTLDWY.cpy} (working storage layout), and
 * {@code app/cbl/CSUTLDTC.cbl} (CEEDAYS wrapper subprogram) into native
 * {@link java.time.LocalDate}-based validation.
 *
 * <p>Per AAP &sect;0.5.2, the IBM Language Environment {@code CEEDAYS} runtime
 * API is <strong>fully retired</strong>. The Java target uses
 * {@link LocalDate#parse(CharSequence, DateTimeFormatter)} with a strict
 * {@link ResolverStyle#STRICT} resolver, which provides identical semantics
 * for leap-year handling, month length validation, and day-of-month range
 * checking. The native {@code java.time} parser rejects invalid day/month/year
 * combinations such as {@code 19850230} (February 30), {@code 19850431}
 * (April 31), and {@code 19990229} (February 29 in a non-leap year) by
 * throwing {@link DateTimeParseException} &mdash; matching the cumulative
 * effect of the COBOL paragraphs {@code EDIT-MONTH}, {@code EDIT-DAY},
 * {@code EDIT-DAY-MONTH-YEAR}, and {@code EDIT-DATE-LE}.</p>
 *
 * <p>Per AAP &sect;0.7.1, this service is a Spring {@code @Service} that:</p>
 * <ul>
 *   <li>Is stateless and thread-safe (all instance state is immutable static
 *       constants; the only instance method is {@code public} and stateless)</li>
 *   <li>Returns immutable {@link DateValidationResult} records (does not throw
 *       &mdash; the calling service decides whether to throw
 *       {@code com.awsm2.carddemo.exception.ValidationException})</li>
 *   <li>Performs no AWS SDK, JPA, Kafka, or network I/O &mdash; local CPU work
 *       only</li>
 *   <li>Uses {@link Locale#US} explicitly on {@link DateTimeFormatter} so date
 *       parsing/formatting is deterministic across deployment environments
 *       (per AAP &sect;0.7.1 deterministic-behavior requirement)</li>
 * </ul>
 *
 * <p>Default format mask is {@value #DEFAULT_FORMAT_MASK} matching the
 * {@code WS-DATE-FORMAT} default in {@code app/cpy/CSUTLDWY.cpy:L58-L59} and
 * the subprogram convention in {@code app/cbl/CSUTLDTC.cbl:L116-L120}
 * ({@code CALL "CEEDAYS" USING WS-DATE-TO-TEST, WS-DATE-FORMAT,
 * OUTPUT-LILLIAN, FEEDBACK-CODE}).</p>
 *
 * <p><strong>Century restriction:</strong> The COBOL source restricts the
 * year century to either 19 or 20 (per {@code CSUTLDWY.cpy:L9-L10} 88-level
 * conditions {@code THIS-CENTURY VALUE 20.} / {@code LAST-CENTURY VALUE 19.}
 * and per the explicit century reasonableness check at
 * {@code CSUTLDPY.cpy:L63-L84} in paragraph {@code EDIT-YEAR-CCYY}). The Java
 * target preserves this restriction explicitly because
 * {@link LocalDate#parse} alone accepts any 4-digit year. The
 * {@link #validate(String, String)} method enforces the century check
 * <em>after</em> {@link LocalDate#parse} succeeds.</p>
 *
 * <p><strong>COBOL provenance / inline traceability (per AAP &sect;0.7.3):</strong>
 * Each public method carries a {@code // COBOL: ...} comment identifying the
 * source paragraph(s) being replaced. Format mask translation
 * ({@code Y -> u}, {@code D -> d}, {@code M -> M} unchanged &mdash; note the
 * {@code Y -> u} choice rather than {@code Y -> y} is required to satisfy
 * {@link ResolverStyle#STRICT} which rejects year-of-era without an explicit
 * era specifier) is delegated to the package-private
 * {@link #toJavaPattern(String)} helper to keep the primary validation
 * cascade easy to read.</p>
 *
 * @see ValidationLookupService
 * @see com.awsm2.carddemo.exception.ValidationException
 */
@Service
public class DateValidationService {

    // =====================================================================
    // Static constants — exposed as part of the public API per the file
    // schema (members_exposed). All result codes are 4-character strings to
    // match the COBOL WS-SEVERITY PIC X(04) layout in CSUTLDWY.cpy:L61.
    // =====================================================================

    /**
     * Default date format mask matching COBOL {@code WS-DATE-FORMAT} default
     * {@code 'YYYYMMDD'} from {@code app/cpy/CSUTLDWY.cpy:L58-L59} and the
     * subprogram convention in {@code app/cbl/CSUTLDTC.cbl}.
     */
    public static final String DEFAULT_FORMAT_MASK = "YYYYMMDD";

    /**
     * Result code indicating a valid date. Corresponds to COBOL
     * {@code WS-SEVERITY = 0000} (FC-INVALID-DATE feedback code,
     * which counter-intuitively means "no error" per IBM Language Environment
     * conventions). See {@code app/cbl/CSUTLDTC.cbl:L129-L130}.
     */
    public static final String RESULT_CODE_VALID = "0000";

    /**
     * Result code for a generic date parse/format failure. Maps to COBOL
     * {@code CSUTLDTC.cbl:L147-L148} (WHEN OTHER &rarr; 'Date is invalid').
     */
    public static final String RESULT_CODE_INVALID = "0009";

    /**
     * Result code for a null/blank input date. Maps to COBOL
     * {@code FLG-YEAR-BLANK} / {@code FLG-MONTH-BLANK} / {@code FLG-DAY-BLANK}
     * in {@code CSUTLDPY.cpy:L30-L42} (paragraph {@code EDIT-YEAR-CCYY}
     * blank-supplied check at lines 30-42).
     */
    public static final String RESULT_CODE_BLANK = "0010";

    /**
     * Result code for an invalid or null format mask. Maps to COBOL
     * {@code FC-BAD-PIC-STRING} feedback code in
     * {@code app/cbl/CSUTLDTC.cbl:L141-L142}.
     */
    public static final String RESULT_CODE_BAD_FORMAT_MASK = "0006";

    /**
     * Result code for a date-of-birth in the future. Maps to COBOL
     * {@code EDIT-DATE-OF-BIRTH} in {@code app/cpy/CSUTLDPY.cpy:L341-L372}
     * (specifically the future-date rejection at lines 350-368).
     */
    public static final String RESULT_CODE_FUTURE_DATE = "0011";

    /**
     * Result code for an out-of-range century. Maps to COBOL year-of-century
     * check in {@code app/cpy/CSUTLDPY.cpy:L63-L84} (paragraph
     * {@code EDIT-YEAR-CCYY}), which restricts the year to {@code 19xx} or
     * {@code 20xx} per the 88-level conditions in
     * {@code app/cpy/CSUTLDWY.cpy:L9-L10}
     * ({@code THIS-CENTURY VALUE 20.} / {@code LAST-CENTURY VALUE 19.}).
     */
    public static final String RESULT_CODE_BAD_CENTURY = "0012";

    /**
     * Pre-built formatter for the default YYYYMMDD mask. STRICT resolver
     * style ensures that out-of-range day/month/year values fail to parse
     * with {@link DateTimeParseException} instead of being silently coerced
     * (e.g., February 30 &rarr; March 2 under the default SMART resolver).
     * This constant is held as a class-level singleton because
     * {@link DateTimeFormatter} is immutable and thread-safe per the JDK
     * contract.
     *
     * <p><strong>Pattern letter choice ({@code u} vs {@code y}):</strong>
     * The pattern uses {@code uuuu} (proleptic year) rather than
     * {@code yyyy} (year-of-era) because {@link ResolverStyle#STRICT}
     * requires an era to be supplied alongside year-of-era. Without an era
     * specifier, STRICT-mode parsing of {@code "yyyyMMdd"} fails with
     * "Unable to obtain LocalDate from TemporalAccessor:
     * &#123;YearOfEra=..., MonthOfYear=..., DayOfMonth=...&#125;". The
     * proleptic-year letter {@code u} works correctly in STRICT mode and
     * produces identical numeric parsing behavior for AD years. This
     * decision is local to the Java target and does not affect the
     * COBOL-facing mask convention, which still uses {@code Y}.</p>
     */
    private static final DateTimeFormatter CCYYMMDD = DateTimeFormatter
        .ofPattern("uuuuMMdd", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT);

    // =====================================================================
    // Constructor
    // =====================================================================

    /**
     * Default no-arg constructor. This service has no dependencies &mdash;
     * all state is immutable static constants and the methods are pure
     * functions. Constructor injection is not required, but the explicit
     * constructor is preserved for clarity and to satisfy the AAP &sect;0.7
     * architectural rule that all services declare their constructors
     * explicitly.
     */
    public DateValidationService() {
        // no-op
    }

    // =====================================================================
    // Public API — validate(String) and validate(String, String)
    // =====================================================================

    /**
     * Validates a date string using the default {@value #DEFAULT_FORMAT_MASK}
     * format mask.
     *
     * <p>Convenience overload of {@link #validate(String, String)} with the
     * default format mask. Equivalent to
     * {@code validate(dateString, DEFAULT_FORMAT_MASK)}.</p>
     *
     * @param dateString the candidate date string (e.g., {@code "19850315"})
     * @return the validation result; never {@code null}
     */
    // COBOL: CSUTLDPY.cpy EDIT-DATE-CCYYMMDD entry (default YYYYMMDD mask)
    public DateValidationResult validate(String dateString) {
        return validate(dateString, DEFAULT_FORMAT_MASK);
    }

    /**
     * Validates a date string against the supplied format mask.
     *
     * <p>COBOL provenance: replaces the {@code EDIT-DATE-CCYYMMDD} cascade in
     * {@code app/cpy/CSUTLDPY.cpy} (paragraphs {@code EDIT-YEAR-CCYY},
     * {@code EDIT-MONTH}, {@code EDIT-DAY}, {@code EDIT-DAY-MONTH-YEAR},
     * {@code EDIT-DATE-LE}) plus the {@code CSUTLDTC.cbl} CEEDAYS wrapper.
     * The Java implementation uses native {@link LocalDate#parse} with a
     * {@link ResolverStyle#STRICT} resolver, which performs identical
     * leap-year, month-length, and day-range validation. The century check
     * (only {@code 19xx} or {@code 20xx} allowed per
     * {@code CSUTLDWY.cpy:L9-L10}) is performed explicitly after parse
     * because {@link LocalDate#parse} alone accepts any 4-digit year.</p>
     *
     * <p>Validation cascade (in order):</p>
     * <ol>
     *   <li><strong>Blank check</strong> &mdash; {@code dateString == null}
     *       or {@code dateString.isBlank()} returns
     *       {@link #RESULT_CODE_BLANK}. Maps to COBOL
     *       {@code CSUTLDPY.cpy:L30-L42}.</li>
     *   <li><strong>Mask resolution</strong> &mdash; {@code formatMask} is
     *       resolved to the default if null/blank, then translated to a Java
     *       pattern via {@link #toJavaPattern(String)}.</li>
     *   <li><strong>Formatter build</strong> &mdash; failures throw
     *       {@link IllegalArgumentException} and return
     *       {@link #RESULT_CODE_BAD_FORMAT_MASK}. Maps to COBOL
     *       {@code FC-BAD-PIC-STRING} in {@code CSUTLDTC.cbl:L141-L142}.</li>
     *   <li><strong>Parse</strong> &mdash; failures throw
     *       {@link DateTimeParseException} and return
     *       {@link #RESULT_CODE_INVALID}. Maps to COBOL
     *       {@code CSUTLDTC.cbl:L147-L148} WHEN OTHER.</li>
     *   <li><strong>Century check</strong> &mdash; year &divide; 100 must be
     *       19 or 20; otherwise returns
     *       {@link #RESULT_CODE_BAD_CENTURY}. Maps to COBOL
     *       {@code CSUTLDPY.cpy:L63-L84}.</li>
     * </ol>
     *
     * @param dateString the candidate date string (e.g., {@code "19850315"}).
     *                   May be {@code null} or blank, in which case a
     *                   {@link #RESULT_CODE_BLANK} result is returned.
     * @param formatMask the format pattern. Accepts COBOL-style masks such
     *                   as {@code "YYYYMMDD"} (default), {@code "MM/DD/YYYY"},
     *                   {@code "YYYY-MM-DD"}, etc. Internally translated to
     *                   a {@link DateTimeFormatter} pattern via
     *                   {@link #toJavaPattern(String)}. If {@code null} or
     *                   blank, defaults to {@value #DEFAULT_FORMAT_MASK}.
     * @return a {@link DateValidationResult} describing success or failure;
     *         never {@code null}.
     */
    public DateValidationResult validate(String dateString, String formatMask) {
        // COBOL: CSUTLDPY.cpy EDIT-DATE-CCYYMMDD entry; CSUTLDTC.cbl A000-MAIN

        // Step 1: blank check
        // COBOL: CSUTLDPY.cpy:L30-L42 (EDIT-YEAR-CCYY blank-supplied check),
        //        :L94-L108 (EDIT-MONTH blank-supplied check),
        //        :L154-L168 (EDIT-DAY blank-supplied check).
        if (dateString == null || dateString.isBlank()) {
            return DateValidationResult.invalid(RESULT_CODE_BLANK, "Date must be supplied");
        }

        // Step 2: choose formatter — caller-supplied mask or default.
        // Using the pre-built CCYYMMDD singleton when the mask matches the
        // default avoids unnecessary formatter construction.
        String resolvedMask = (formatMask == null || formatMask.isBlank())
            ? DEFAULT_FORMAT_MASK
            : formatMask;

        DateTimeFormatter formatter;
        try {
            formatter = formatterFor(resolvedMask);
        } catch (IllegalArgumentException ex) {
            // COBOL: CSUTLDTC.cbl FC-BAD-PIC-STRING (CSUTLDTC.cbl:L141-L142)
            return DateValidationResult.invalid(RESULT_CODE_BAD_FORMAT_MASK,
                "Bad Pic String: " + ex.getMessage());
        }

        // Step 3: parse with STRICT resolver — replaces COBOL paragraphs
        // EDIT-MONTH (CSUTLDPY.cpy:L91-L147), EDIT-DAY (:L150-L207),
        // EDIT-DAY-MONTH-YEAR (:L209-L282 — no 31 in 30-day months,
        // no 30 in Feb, leap-year Feb 29), and EDIT-DATE-LE (:L284-L331).
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(dateString.trim(), formatter);
        } catch (DateTimeParseException ex) {
            // COBOL: CSUTLDTC.cbl WHEN OTHER → 'Date is invalid' (L147-L148)
            return DateValidationResult.invalid(RESULT_CODE_INVALID,
                "Date is invalid: " + ex.getMessage());
        }

        // Step 4: century check (COBOL: CSUTLDWY.cpy:L9-L10 THIS-CENTURY=20,
        // LAST-CENTURY=19; CSUTLDPY.cpy:L63-L84 EDIT-YEAR-CCYY century
        // reasonableness). LocalDate.parse alone accepts any 4-digit year,
        // so this check is required to preserve COBOL semantics.
        int year = parsed.getYear();
        int century = year / 100;
        if (century != 19 && century != 20) {
            return DateValidationResult.invalid(RESULT_CODE_BAD_CENTURY,
                "Century is not valid (must be 19 or 20): " + year);
        }

        // COBOL: CSUTLDPY.cpy:L327 SET WS-EDIT-DATE-IS-VALID TO TRUE
        return DateValidationResult.VALID;
    }

    // =====================================================================
    // Public API — validateDateOfBirth(...)
    // =====================================================================

    /**
     * Validates a date-of-birth string using the default
     * {@value #DEFAULT_FORMAT_MASK} format mask and the current system date
     * as the "today" reference for future-date rejection.
     *
     * <p>Convenience overload of
     * {@link #validateDateOfBirth(String, String, LocalDate)} for production
     * use. Unit tests should prefer the three-argument overload with an
     * injected {@code today} to ensure deterministic behavior.</p>
     *
     * @param dateString the candidate date-of-birth string
     *                   (e.g., {@code "19850315"})
     * @return the validation result; never {@code null}
     */
    // COBOL: CSUTLDPY.cpy EDIT-DATE-OF-BIRTH (L341-L372) with today = current system date
    public DateValidationResult validateDateOfBirth(String dateString) {
        return validateDateOfBirth(dateString, DEFAULT_FORMAT_MASK, LocalDate.now());
    }

    /**
     * Validates a date-of-birth string and rejects future dates per
     * {@code app/cpy/CSUTLDPY.cpy:L341-L372} (paragraph
     * {@code EDIT-DATE-OF-BIRTH}).
     *
     * <p>COBOL provenance ({@code CSUTLDPY.cpy:L350-L368}):</p>
     * <pre>
     * IF WS-CURRENT-DATE-BINARY &gt; WS-EDIT-DATE-BINARY
     *    CONTINUE
     * ELSE
     *    SET INPUT-ERROR TO TRUE
     *    ...':cannot be in the future '
     * </pre>
     *
     * <p>The COBOL predicate {@code WS-CURRENT-DATE-BINARY &gt; WS-EDIT-DATE-BINARY}
     * means: "the current date strictly exceeds the candidate date" &mdash; i.e.,
     * the candidate must be strictly in the past. Java equivalent:
     * {@code dob.isBefore(today)}. Therefore a candidate date that is
     * <em>equal to</em> today is REJECTED (matches COBOL semantics: when the
     * strict-greater-than predicate is false, the date is treated as in the
     * future / not in the past).</p>
     *
     * <p>The {@code today} parameter is injected (rather than computed via
     * {@link LocalDate#now()} inside the method) to enable deterministic
     * unit testing &mdash; the test harness can pass a fixed reference date.
     * The single-argument convenience overload
     * {@link #validateDateOfBirth(String)} uses {@link LocalDate#now()} for
     * production callers that do not need deterministic dates.</p>
     *
     * <p>Validation cascade (in order):</p>
     * <ol>
     *   <li>Delegate to {@link #validate(String, String)} for structural
     *       validation (blank check, mask check, parse, century check). If
     *       the structural result is invalid, return it unchanged.</li>
     *   <li>Re-parse the (now known-valid) date to obtain a
     *       {@link LocalDate} for comparison.</li>
     *   <li>Reject if {@code dob.isBefore(today) == false}, returning
     *       {@link #RESULT_CODE_FUTURE_DATE}.</li>
     * </ol>
     *
     * @param dateString the candidate date-of-birth string
     *                   (e.g., {@code "19850315"})
     * @param formatMask the format mask (defaults to
     *                   {@value #DEFAULT_FORMAT_MASK} if null/blank)
     * @param today      the reference "today" date; date-of-birth must be
     *                   strictly before this. Must not be {@code null}.
     * @return the validation result; never {@code null}
     * @throws NullPointerException if {@code today} is {@code null}
     */
    public DateValidationResult validateDateOfBirth(String dateString, String formatMask, LocalDate today) {
        // COBOL: CSUTLDPY.cpy EDIT-DATE-OF-BIRTH (L341-L372)
        Objects.requireNonNull(today, "today must not be null");

        // Step 1: structural date validation (delegate to validate).
        // Returns early if the date is malformed, has a bad mask, has an
        // out-of-range century, or is blank/null.
        DateValidationResult structural = validate(dateString, formatMask);
        if (!structural.valid()) {
            return structural;
        }

        // Step 2: re-parse the (now known-valid) date to compare against
        // today. validate(...) does not return the parsed LocalDate to avoid
        // leaking implementation details, so a second parse is required.
        // We know this parse will succeed because validate(...) returned
        // valid above.
        String resolvedMask = (formatMask == null || formatMask.isBlank())
            ? DEFAULT_FORMAT_MASK
            : formatMask;
        DateTimeFormatter formatter = formatterFor(resolvedMask);
        LocalDate dob = LocalDate.parse(dateString.trim(), formatter);

        // Step 3: future-date check.
        // COBOL: CSUTLDPY.cpy:L350 IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY
        //                            CONTINUE   (valid: dob strictly in past)
        //                         ELSE
        //                            SET INPUT-ERROR TO TRUE
        //                            ...':cannot be in the future '
        // Java equivalent: if !(dob.isBefore(today)) → reject as future.
        // Note: dob == today is REJECTED (matches strict-greater-than).
        if (!dob.isBefore(today)) {
            return DateValidationResult.invalid(RESULT_CODE_FUTURE_DATE,
                "Date of birth cannot be in the future");
        }

        return DateValidationResult.VALID;
    }

    // =====================================================================
    // Private/static helpers — format mask translation
    // =====================================================================

    /**
     * Builds a {@link DateTimeFormatter} for the supplied mask. Reuses the
     * pre-built {@link #CCYYMMDD} singleton when the mask is the default
     * {@value #DEFAULT_FORMAT_MASK} to avoid unnecessary formatter
     * construction on the hot path.
     *
     * @param cobolMask the COBOL-style mask (must not be {@code null}; the
     *                  caller is responsible for null/blank handling)
     * @return a {@link DateTimeFormatter} configured with STRICT resolver
     *         and US locale
     * @throws IllegalArgumentException if the mask cannot be translated to
     *         a valid Java pattern (propagated from
     *         {@link DateTimeFormatter#ofPattern(String, Locale)})
     */
    private static DateTimeFormatter formatterFor(String cobolMask) {
        // Fast path: default mask reuses the pre-built singleton.
        if (DEFAULT_FORMAT_MASK.equals(cobolMask)) {
            return CCYYMMDD;
        }
        // Slow path: translate and build a new formatter.
        String javaPattern = toJavaPattern(cobolMask);
        return DateTimeFormatter.ofPattern(javaPattern, Locale.US)
            .withResolverStyle(ResolverStyle.STRICT);
    }

    /**
     * Translates a COBOL-style format mask (e.g., {@code "YYYYMMDD"},
     * {@code "MM/DD/YYYY"}, {@code "YYYY-MM-DD"}) to a Java
     * {@link DateTimeFormatter} pattern.
     *
     * <p>COBOL/CEEDAYS uses uppercase {@code Y}/{@code M}/{@code D}
     * characters. Java {@link DateTimeFormatter} requires:</p>
     * <ul>
     *   <li>{@code u} for year (proleptic year &mdash; works in
     *       {@link ResolverStyle#STRICT} without an era specifier;
     *       note that the more familiar {@code y} is year-of-era and
     *       STRICT mode rejects it without an explicit era field)</li>
     *   <li>{@code M} for month (uppercase &mdash; same as COBOL)</li>
     *   <li>{@code d} for day-of-month (lowercase &mdash; uppercase
     *       {@code D} in Java means day-of-year, which would misparse the
     *       COBOL day-of-month semantic)</li>
     * </ul>
     *
     * <p>This method performs the case translation while preserving any
     * separator characters such as {@code '-'}, {@code '/'}, or {@code ' '}
     * so that formats like {@code "MM/DD/YYYY"} translate to
     * {@code "MM/dd/uuuu"}.</p>
     *
     * <p>The method is package-private (default visibility) to allow direct
     * unit testing while not exposing it on the public API.</p>
     *
     * @param cobolMask the COBOL-style mask (e.g., {@code "YYYYMMDD"});
     *                  must not be {@code null}
     * @return the Java pattern (e.g., {@code "uuuuMMdd"})
     * @throws NullPointerException if {@code cobolMask} is {@code null}
     */
    static String toJavaPattern(String cobolMask) {
        Objects.requireNonNull(cobolMask, "cobolMask must not be null");
        StringBuilder out = new StringBuilder(cobolMask.length());
        for (int i = 0; i < cobolMask.length(); i++) {
            char c = cobolMask.charAt(i);
            switch (c) {
                case 'Y' -> out.append('u'); // year: COBOL Y → Java u (proleptic year, STRICT-safe)
                case 'M' -> out.append('M'); // month: unchanged (both use uppercase M)
                case 'D' -> out.append('d'); // day: COBOL D → Java d (day-of-month)
                default -> out.append(c);     // separators (-, /, space, etc.) pass through
            }
        }
        return out.toString();
    }

    // =====================================================================
    // Nested public record — DateValidationResult
    // =====================================================================

    /**
     * Immutable result of a date validation operation. Mirrors the layout
     * of COBOL {@code WS-DATE-VALIDATION-RESULT} from
     * {@code app/cpy/CSUTLDWY.cpy:L60-L85}:
     *
     * <pre>
     * 10 WS-DATE-VALIDATION-RESULT.
     *    20 WS-SEVERITY  PIC X(04).
     *    20 WS-SEVERITY-N REDEFINES WS-SEVERITY PIC 9(4).
     *    20 FILLER       PIC X(11) VALUE 'Mesg Code:'.
     *    20 WS-MSG-NO    PIC X(04).
     *    20 WS-MSG-NO-N REDEFINES WS-MSG-NO PIC 9(4).
     *    20 FILLER       PIC X(01) VALUE SPACE.
     *    20 WS-RESULT    PIC X(15).
     *    ...
     * </pre>
     *
     * <p>In the Java target, {@code resultCode} carries the 4-character
     * severity/message code (matching COBOL {@code WS-SEVERITY} +
     * {@code WS-MSG-NO} usage from {@code CSUTLDTC.cbl:L43-L47}), and
     * {@code message} carries the human-readable result text (matching
     * COBOL {@code WS-RESULT}). The verbose COBOL fixed-width filler text
     * and trailing date/format display fields are intentionally dropped
     * because they have no semantic value in the Java/JSON representation
     * &mdash; they existed only for fixed-width 3270 terminal output.</p>
     *
     * <p>The {@code valid} flag mirrors COBOL
     * {@code WS-EDIT-DATE-IS-VALID} / {@code WS-EDIT-DATE-IS-INVALID}
     * 88-level conditions in {@code CSUTLDWY.cpy:L44-L45} and the COBOL
     * convention from {@code CSUTLDTC.cbl:L98} that
     * {@code RETURN-CODE = WS-SEVERITY-N} where a value of zero indicates
     * a valid date.</p>
     *
     * <p>Per the JDK record contract, all fields are {@code final}, the
     * accessors {@code valid()}, {@code resultCode()}, and {@code message()}
     * are auto-generated, and {@code equals}/{@code hashCode}/{@code toString}
     * are structural based on the components.</p>
     *
     * @param valid       {@code true} if the date is valid; {@code false}
     *                    otherwise
     * @param resultCode  4-character severity/message code;
     *                    {@value #RESULT_CODE_VALID} when valid
     * @param message     human-readable result text
     */
    public record DateValidationResult(boolean valid, String resultCode, String message) {

        /**
         * Compact canonical constructor performs minimal null-safety
         * normalization so that callers can rely on the accessors never
         * returning {@code null}.
         *
         * <ul>
         *   <li>A {@code null} {@code resultCode} is normalized to
         *       {@value #RESULT_CODE_VALID} when {@code valid == true} and
         *       {@value #RESULT_CODE_INVALID} when {@code valid == false}.</li>
         *   <li>A {@code null} {@code message} is normalized to the empty
         *       string {@code ""} (matching COBOL fixed-width spaces
         *       semantics after trimming).</li>
         * </ul>
         */
        public DateValidationResult {
            if (resultCode == null) {
                resultCode = valid ? RESULT_CODE_VALID : RESULT_CODE_INVALID;
            }
            if (message == null) {
                message = "";
            }
        }

        /**
         * Sentinel for a successful validation result &mdash; a single
         * immutable shared instance reused across the codebase to avoid
         * allocations on the hot path.
         *
         * <p>Equivalent to
         * {@code new DateValidationResult(true,}
         * {@value #RESULT_CODE_VALID}{@code , "Date is valid")} where the
         * literal {@code "Date is valid"} is sourced verbatim from
         * {@code app/cbl/CSUTLDTC.cbl:L130}.</p>
         *
         * <p>This static field replaces what the COBOL-style API would
         * idiomatically express as {@code DateValidationResult.valid()}.
         * Java records forbid declaring a static method with the same
         * signature as an auto-generated component accessor
         * (per JLS &sect;8.10.3 / &sect;8.10.4), so the parallel pattern
         * {@code DateValidationResult.VALID} +
         * {@link #invalid(String, String)} is used instead. The
         * accessor {@link #valid()} continues to return the {@code boolean}
         * record component value.</p>
         */
        public static final DateValidationResult VALID =
            new DateValidationResult(true, RESULT_CODE_VALID, "Date is valid");

        /**
         * Factory for a failed validation result.
         *
         * <p>Note: there is no parallel {@code valid()} static factory
         * method &mdash; Java records reserve that signature for the
         * auto-generated boolean accessor of the {@code valid} component.
         * Use the {@link #VALID} sentinel constant for the success case
         * instead. See JLS &sect;8.10.3 / &sect;8.10.4.</p>
         *
         * @param resultCode 4-character severity/message code
         * @param message    human-readable description of the failure
         * @return a result with {@code valid=false} and the supplied
         *         code/message
         */
        public static DateValidationResult invalid(String resultCode, String message) {
            return new DateValidationResult(false, resultCode, message);
        }

        // =================================================================
        // Compatibility accessor methods (CP3 checkpoint requirement)
        // =================================================================
        //
        // The CP3 checkpoint requires that DateValidationResult expose
        // `isValid()` and `errorMessage()` compatibility methods alongside
        // the auto-generated record accessors `valid()` and `message()`.
        // Service-layer callers and JSON serialisers written to the
        // checkpoint contract use these accessor names; the record
        // accessors above remain available for callers that prefer them.
        //
        // Both methods are pure delegates to the underlying record
        // components and therefore allocate nothing and preserve the
        // null-safety guarantees of the canonical constructor.

        /**
         * Compatibility alias for {@link #valid()} &mdash; returns
         * {@code true} when the validated date is valid. Mirrors the
         * JavaBean-style {@code is&lt;Boolean&gt;()} convention required
         * by the CP3 checkpoint and by consumers that integrate with
         * Spring's standard property-accessor reflection.
         *
         * @return {@code true} if the date is valid; {@code false} otherwise
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Compatibility alias for {@link #message()} &mdash; returns the
         * human-readable failure description when the result represents
         * a validation failure. The CP3 checkpoint mandates this accessor
         * name so consumers can use it interchangeably with
         * {@code BindingResult}-style validation results.
         *
         * <p>When the result represents a successful validation
         * ({@link #valid()} is {@code true}), the value of this method
         * is typically the literal {@code "Date is valid"} from
         * {@link #VALID}; callers should always guard with
         * {@link #isValid()} before treating the value as an error
         * message.</p>
         *
         * @return the human-readable result text (never {@code null};
         *         may be empty for invalid sentinel cases)
         */
        public String errorMessage() {
            return message;
        }
    }
}
