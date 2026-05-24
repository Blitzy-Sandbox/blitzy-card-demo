/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.util;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Objects;

/**
 * Java translation of the {@code CSUTLDTC} COBOL program at
 * {@code app/cbl/CSUTLDTC.cbl} ("Call to CEEDAYS"). Per AAP &sect;0.6.4
 * the IBM Language Environment CEEDAYS service is replaced by
 * {@link java.time.LocalDate#parse(CharSequence, DateTimeFormatter)} with a
 * {@linkplain ResolverStyle#STRICT strict resolver} so that malformed or
 * out-of-range dates are rejected by Java semantics rather than by an
 * external service.
 *
 * <p>The original COBOL program is a black-box callable utility:
 * <ul>
 *   <li>Input {@code LS-DATE PIC X(10)}: the candidate date string.</li>
 *   <li>Input {@code LS-DATE-FORMAT PIC X(10)}: the COBOL pattern ({@code "YYYYMMDD"}
 *       or {@code "YYYY-MM-DD"}); other patterns are passed through unmodified.</li>
 *   <li>Output {@code LS-RESULT PIC X(80)}: a free-form result string carrying
 *       a 4-character severity, a 4-character message number, and a 15-character
 *       result word per the CEEDAYS feedback codes.</li>
 * </ul>
 *
 * <p>The result words used by the COBOL EVALUATE are reproduced verbatim
 * to satisfy AAP &sect;0.7.1 byte-for-byte parity:
 * <pre>
 *   FC-INVALID-DATE        -&gt; "Date is valid"
 *   FC-INSUFFICIENT-DATA   -&gt; "Insufficient"
 *   FC-BAD-DATE-VALUE      -&gt; "Datevalue error"
 *   FC-INVALID-ERA         -&gt; "Invalid Era    "
 *   FC-UNSUPP-RANGE        -&gt; "Unsupp. Range  "
 *   FC-INVALID-MONTH       -&gt; "Invalid month  "
 *   FC-BAD-PIC-STRING      -&gt; "Bad Pic String "
 *   FC-NON-NUMERIC-DATA    -&gt; "Nonnumeric data"
 *   FC-YEAR-IN-ERA-ZERO    -&gt; "YearInEra is 0 "
 *   WHEN OTHER             -&gt; "Date is invalid"
 * </pre>
 */
@CobolProgram(
        value = "CSUTLDTC",
        sourcePath = "app/cbl/CSUTLDTC.cbl",
        notes = "Date validator; CEEDAYS replaced with LocalDate.parse + ResolverStyle.STRICT per AAP §0.6.4"
)
public final class DateValidator {

    // Result strings exactly mirror the COBOL EVALUATE outputs (15-char width).
    public static final String RESULT_VALID = "Date is valid";
    public static final String RESULT_INSUFFICIENT = "Insufficient";
    public static final String RESULT_BAD_DATE_VALUE = "Datevalue error";
    public static final String RESULT_INVALID_ERA = "Invalid Era    ";
    public static final String RESULT_UNSUPP_RANGE = "Unsupp. Range  ";
    public static final String RESULT_INVALID_MONTH = "Invalid month  ";
    public static final String RESULT_BAD_PIC_STRING = "Bad Pic String ";
    public static final String RESULT_NON_NUMERIC_DATA = "Nonnumeric data";
    public static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";
    public static final String RESULT_OTHER = "Date is invalid";

    public DateValidator() {
        // Public no-arg constructor for constructor injection
    }

    /**
     * Validates a date string against a COBOL date format. Mirrors the
     * COBOL CSUTLDTC entry contract: returns a {@link Result} carrying the
     * severity, message number, and 15-character result word that the
     * COBOL program would have set.
     *
     * @param input the date string and format (e.g., "20250101", "YYYYMMDD")
     * @return a {@link Result} describing the validation outcome
     */
    public Result validate(Input input) {
        if (input == null) {
            return Result.invalid(RESULT_BAD_PIC_STRING, "", "", false, false, false);
        }

        String dateString = input.dateString();
        String dateFormat = input.dateFormat();

        // FC-INSUFFICIENT-DATA / FC-BAD-PIC-STRING analogues
        if (dateString == null || dateString.isBlank()) {
            return Result.invalid(RESULT_INSUFFICIENT, dateFormat, dateString,
                    false, false, false);
        }
        if (dateFormat == null || dateFormat.isBlank()) {
            return Result.invalid(RESULT_BAD_PIC_STRING, dateFormat, dateString,
                    false, false, false);
        }

        DateTimeFormatter formatter;
        try {
            formatter = mapCobolPattern(dateFormat);
        } catch (IllegalArgumentException e) {
            return Result.invalid(RESULT_BAD_PIC_STRING, dateFormat, dateString,
                    false, false, false);
        }

        // FC-NON-NUMERIC-DATA analogue: ensure the digits in the candidate
        // string are numeric where the COBOL pattern expects digits.
        String stripped = stripPunctuation(dateString.trim());
        if (!stripped.chars().allMatch(Character::isDigit)) {
            return Result.invalid(RESULT_NON_NUMERIC_DATA, dateFormat, dateString,
                    false, false, false);
        }

        LocalDate parsed;
        try {
            parsed = LocalDate.parse(dateString.trim(),
                    formatter.withResolverStyle(ResolverStyle.STRICT));
        } catch (DateTimeParseException e) {
            // Distinguish between INVALID-MONTH, BAD-DATE-VALUE, and OTHER
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("MonthOfYear")) {
                return Result.invalid(RESULT_INVALID_MONTH, dateFormat, dateString,
                        true, false, true);
            }
            if (msg.contains("DayOfMonth")) {
                return Result.invalid(RESULT_BAD_DATE_VALUE, dateFormat, dateString,
                        true, true, false);
            }
            return Result.invalid(RESULT_OTHER, dateFormat, dateString,
                    false, false, false);
        } catch (DateTimeException e) {
            return Result.invalid(RESULT_OTHER, dateFormat, dateString,
                    false, false, false);
        }

        // FC-YEAR-IN-ERA-ZERO analogue
        if (parsed.getYear() == 0) {
            return Result.invalid(RESULT_YEAR_IN_ERA_ZERO, dateFormat, dateString,
                    false, true, true);
        }

        return Result.ok(parsed, dateFormat);
    }

    /**
     * Returns {@code true} if the supplied date string parses as a valid
     * date in the given COBOL format. Equivalent to checking
     * {@link Result#isOk()} on the {@link #validate(Input)} outcome.
     */
    public boolean isValid(String dateString, String dateFormat) {
        return validate(new Input(dateString, dateFormat)).isOk();
    }

    /**
     * Returns {@code true} if the date string parses as YYYYMMDD.
     */
    public boolean isValidYyyymmdd(String dateString) {
        return isValid(dateString, "YYYYMMDD");
    }

    /**
     * Maps a COBOL date pattern (e.g., {@code "YYYYMMDD"}, {@code "YYYY-MM-DD"})
     * to a Java {@link DateTimeFormatter}. The COBOL format uses uppercase
     * pattern letters; Java uses lowercase {@code yyyy MM dd}.
     */
    private static DateTimeFormatter mapCobolPattern(String cobolPattern) {
        String trimmed = cobolPattern.trim().toUpperCase();
        // Translate COBOL pattern letters to Java pattern letters
        StringBuilder javaPattern = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            switch (c) {
                case 'Y' -> javaPattern.append('y');
                case 'M' -> javaPattern.append('M');
                case 'D' -> javaPattern.append('d');
                case 'H' -> javaPattern.append('H');
                case 'S' -> javaPattern.append('s');
                case '-', '/', '.', ' ', ':' -> javaPattern.append(c);
                default -> throw new IllegalArgumentException(
                        "Unsupported COBOL pattern char '" + c + "' in '" + cobolPattern + "'");
            }
        }
        return DateTimeFormatter.ofPattern(javaPattern.toString());
    }

    /** Strips non-digit punctuation from a date string to support numeric checks. */
    private static String stripPunctuation(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Maps a result severity to the 4-character WS-SEVERITY representation. */
    public static String formatSeverity(Severity severity) {
        return severity.fourCharCode();
    }

    // ==================================================================
    // NESTED TYPES — CSUTLDTC LINKAGE SECTION translation
    //
    // The original COBOL CSUTLDTC.cbl is a black-box callable utility whose
    // LINKAGE SECTION declares (LS-DATE, LS-DATE-FORMAT, LS-RESULT). The
    // following nested types translate that LINKAGE shape into idiomatic
    // Java records / enum local to the validator service that owns them.
    //
    // Architectural note: these contract types live on the SERVICE class
    // (DateValidator), not on the working-storage record (DateValidationWork).
    // DateValidationWork is the COBOL CSUTLDWY working-storage data
    // structure; DateValidator is the CSUTLDTC service. Per AAP §0.4.1 the
    // executable validation logic translates here, not into the work area
    // record.
    // ==================================================================

    /**
     * Severity code modelling the COBOL {@code WS-SEVERITY-N} values returned by
     * the IBM Language Environment {@code CSUTLDTC / CEEDAYS} service:
     * {@code 0} = OK, {@code 4} = warning, {@code >= 8} = error. The Java
     * translation collapses non-zero severities into a single {@link #ERROR}
     * permit; the precise message number is carried separately on
     * {@link Result#msgNumber()}.
     */
    public enum Severity {

        /** {@code WS-SEVERITY-N = 0}: date is valid (LE service success). */
        OK(0, "0000"),

        /** {@code WS-SEVERITY-N >= 8} (or {@code 12}): date is invalid (LE service error). */
        ERROR(12, "0012");

        private final int code;
        private final String fourCharCode;

        Severity(int code, String fourCharCode) {
            this.code = code;
            this.fourCharCode = fourCharCode;
        }

        /**
         * Returns the integer severity code (0 for OK, 12 for ERROR).
         *
         * @return the integer severity code
         */
        public int code() {
            return code;
        }

        /**
         * Returns the 4-character {@code WS-SEVERITY PIC X(04)} representation
         * (e.g., {@code "0000"} for OK, {@code "0012"} for ERROR).
         *
         * @return the 4-character severity string
         */
        public String fourCharCode() {
            return fourCharCode;
        }
    }

    /**
     * Input to the {@link DateValidator#validate(Input) validate} method. Mirrors the
     * COBOL CSUTLDTC LINKAGE inputs ({@code LS-DATE PIC X(10)},
     * {@code LS-DATE-FORMAT PIC X(10)}).
     *
     * <p>Both fields tolerate {@code null} inputs to match COBOL behavior &mdash; a
     * {@code null} date string is treated as "not supplied" and routed to the
     * {@link DateValidator#RESULT_INSUFFICIENT} branch; a {@code null} format string
     * is replaced with the default {@code "YYYYMMDD"} per the
     * {@code WS-DATE-FORMAT VALUE 'YYYYMMDD'} clause in {@code CSUTLDWY.cpy}.
     *
     * @param dateString the candidate date string to validate (may be {@code null})
     * @param dateFormat the COBOL format pattern (may be {@code null}, defaults to
     *                   {@code "YYYYMMDD"})
     */
    public record Input(String dateString, String dateFormat) {

        /** Default COBOL date format from {@code WS-DATE-FORMAT VALUE 'YYYYMMDD'}. */
        public static final String DEFAULT_DATE_FORMAT = "YYYYMMDD";

        /**
         * Compact canonical constructor (JEP 513 Flexible Constructor Bodies):
         * normalizes {@code null} inputs to safe defaults before binding.
         */
        public Input {
            dateString = dateString == null ? "" : dateString;
            dateFormat = dateFormat == null ? DEFAULT_DATE_FORMAT : dateFormat;
        }

        /**
         * Convenience factory using the default {@code "YYYYMMDD"} format.
         *
         * @param dateString the candidate date string
         * @return an {@link Input} with {@code dateFormat} set to {@code "YYYYMMDD"}
         */
        public static Input yyyymmdd(String dateString) {
            return new Input(dateString, DEFAULT_DATE_FORMAT);
        }
    }

    /**
     * Output of the {@link DateValidator#validate(Input) validate} method, mirroring
     * the COBOL {@code WS-DATE-VALIDATION-RESULT} structure plus the year / month /
     * day validity flags from {@code WS-EDIT-DATE-FLGS}.
     *
     * @param severity      validation outcome severity
     * @param msgNumber     LE message number (e.g., {@code "2513"} for the LE
     *                      "date is valid" success code)
     * @param resultMessage human-readable result word
     *                      (e.g., {@code "Date is valid  "})
     * @param testedDate    the date string that was validated
     * @param maskUsed      the date format mask that was applied
     * @param yearOk        {@code true} if the year sub-edit passed
     * @param monthOk       {@code true} if the month sub-edit passed
     * @param dayOk         {@code true} if the day sub-edit passed
     */
    public record Result(
            Severity severity,
            String msgNumber,
            String resultMessage,
            String testedDate,
            String maskUsed,
            boolean yearOk,
            boolean monthOk,
            boolean dayOk
    ) {

        /**
         * Compact canonical constructor (JEP 513): null-checks the {@code severity}
         * (the only non-nullable field) and normalizes other {@code null} strings
         * to empty strings.
         */
        public Result {
            Objects.requireNonNull(severity, "severity");
            msgNumber = msgNumber == null ? "" : msgNumber;
            resultMessage = resultMessage == null ? "" : resultMessage;
            testedDate = testedDate == null ? "" : testedDate;
            maskUsed = maskUsed == null ? "" : maskUsed;
        }

        /**
         * Factory for the success outcome with all flags set OK.
         *
         * @param date     the validated date
         * @param maskUsed the format mask that was applied
         * @return a successful {@link Result}
         */
        public static Result ok(LocalDate date, String maskUsed) {
            return new Result(Severity.OK, "0000", "Valid",
                    date.toString(), maskUsed,
                    true, true, true);
        }

        /**
         * Factory for a failure outcome carrying the year / month / day flag bytes.
         *
         * @param message    the human-readable failure message
         * @param maskUsed   the format mask that was applied
         * @param testedDate the date string that failed validation
         * @param yearOk     whether the year sub-edit passed
         * @param monthOk    whether the month sub-edit passed
         * @param dayOk      whether the day sub-edit passed
         * @return an unsuccessful {@link Result}
         */
        public static Result invalid(String message, String maskUsed,
                                     String testedDate,
                                     boolean yearOk, boolean monthOk, boolean dayOk) {
            return new Result(Severity.ERROR, "0012", message,
                    testedDate, maskUsed,
                    yearOk, monthOk, dayOk);
        }

        /**
         * Convenience predicate.
         *
         * @return {@code true} iff {@link #severity()} is {@link Severity#OK}
         */
        public boolean isOk() {
            return severity == Severity.OK;
        }
    }
}
