/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.validation;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Date validation work area translated from the CSUTLDPY (procedure copybook)
 * and CSUTLDWY (working storage copybook) pair. Together they describe the
 * input/output for the COBOL date-edit routine and its CEEDAYS-style
 * validation outcome.
 *
 * <pre>{@code
 * 10 WS-EDIT-DATE-CCYYMMDD.
 *    20 WS-EDIT-DATE-CCYY        PIC X(4).
 *    20 WS-EDIT-DATE-MM          PIC X(2).
 *    20 WS-EDIT-DATE-DD          PIC X(2).
 * 10 WS-CURRENT-DATE.
 *    20 WS-CURRENT-DATE-YYYYMMDD PIC X(8).
 * 10 WS-EDIT-DATE-FLGS.
 *    20 WS-EDIT-YEAR-FLG         PIC X(01).
 *    20 WS-EDIT-MONTH            PIC X(01).
 *    20 WS-EDIT-DAY              PIC X(01).
 * 10 WS-DATE-FORMAT              PIC X(08) VALUE 'YYYYMMDD'.
 * 10 WS-DATE-VALIDATION-RESULT
 *    (severity, msg-no, result, date, format)
 * }</pre>
 *
 * <p>The Java translation models the work area as immutable input + output
 * records; date arithmetic uses {@link java.time.LocalDate} per AAP &sect;0.6.4.
 */
@CobolProgram(
        value = "CSUTLDPY/CSUTLDWY",
        sourcePath = "app/cpy/CSUTLDPY.cpy, app/cpy/CSUTLDWY.cpy",
        notes = "Date validation work area for CSUTLDTC; rendered as immutable input + output records"
)
public final class DateValidationWork {

    public static final String DEFAULT_DATE_FORMAT = "YYYYMMDD";

    private DateValidationWork() {
        // Utility class
    }

    /**
     * Severity code modelling the LE service WS-SEVERITY values:
     * {@code 0 OK, 1-3 warnings, &gt;=4 errors}.
     */
    public enum Severity {
        /** WS-SEVERITY-N = 0: date is valid. */
        OK(0, "0000"),
        /** WS-SEVERITY-N = 12 or similar: date is invalid. */
        ERROR(12, "0012");

        private final int code;
        private final String fourCharCode;

        Severity(int code, String fourCharCode) {
            this.code = code;
            this.fourCharCode = fourCharCode;
        }

        public int code() { return code; }
        public String fourCharCode() { return fourCharCode; }
    }

    /**
     * Input to the date validation routine: a date string and the COBOL-style
     * pattern it should follow.
     */
    public record Input(String dateString, String dateFormat) {
        public Input {
            dateString = dateString == null ? "" : dateString;
            dateFormat = dateFormat == null ? DEFAULT_DATE_FORMAT : dateFormat;
        }

        /** Convenience factory using the default {@code 'YYYYMMDD'} format. */
        public static Input yyyymmdd(String dateString) {
            return new Input(dateString, DEFAULT_DATE_FORMAT);
        }
    }

    /**
     * Output of the date validation routine, mirroring WS-DATE-VALIDATION-RESULT
     * plus the day / month / year flag bytes from WS-EDIT-DATE-FLGS.
     */
    public record Result(
            Severity severity,
            String msgNumber,
            String resultMessage,
            String testedDate,
            String maskUsed,
            boolean yearOk,
            boolean monthOk,
            boolean dayOk) {

        public Result {
            Objects.requireNonNull(severity, "severity");
            msgNumber = msgNumber == null ? "" : msgNumber;
            resultMessage = resultMessage == null ? "" : resultMessage;
            testedDate = testedDate == null ? "" : testedDate;
            maskUsed = maskUsed == null ? "" : maskUsed;
        }

        /** Factory for the success outcome with no anomalies. */
        public static Result ok(LocalDate date, String maskUsed) {
            return new Result(Severity.OK, "0000", "Valid",
                    date.toString(), maskUsed,
                    true, true, true);
        }

        /** Factory for a failure outcome carrying flag bytes. */
        public static Result invalid(String message, String maskUsed,
                                     String testedDate,
                                     boolean yearOk, boolean monthOk, boolean dayOk) {
            return new Result(Severity.ERROR, "0012", message,
                    testedDate, maskUsed,
                    yearOk, monthOk, dayOk);
        }

        /** Convenience predicate. */
        public boolean isOk() {
            return severity == Severity.OK;
        }
    }
}
