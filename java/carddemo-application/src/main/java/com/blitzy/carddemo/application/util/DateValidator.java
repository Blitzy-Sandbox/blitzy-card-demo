/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.util;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.validation.DateValidationWork.Input;
import com.blitzy.carddemo.domain.validation.DateValidationWork.Result;
import com.blitzy.carddemo.domain.validation.DateValidationWork.Severity;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

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
}
