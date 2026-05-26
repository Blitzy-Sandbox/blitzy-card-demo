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
package com.blitzy.carddemo.domain.validation;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.util.Objects;
import java.util.Set;

/**
 * Java translation of the COBOL date-validation working storage area,
 * consolidated from two COBOL copybooks per Agent Action Plan (AAP) &sect;0.4.1:
 *
 * <ul>
 *   <li>{@code app/cpy/CSUTLDWY.cpy} &mdash; working storage {@code WS-EDIT-DATE-CCYYMMDD},
 *       {@code WS-CURRENT-DATE}, {@code WS-EDIT-DATE-FLGS}, {@code WS-DATE-FORMAT},
 *       and {@code WS-DATE-VALIDATION-RESULT} groups.</li>
 *   <li>{@code app/cpy/CSUTLDPY.cpy} &mdash; the matching procedure-division copybook
 *       (paragraphs {@code EDIT-DATE-CCYYMMDD}, {@code EDIT-YEAR-CCYY}, {@code EDIT-MONTH},
 *       {@code EDIT-DAY}, {@code EDIT-DAY-MONTH-YEAR}, {@code EDIT-DATE-LE},
 *       {@code EDIT-DATE-OF-BIRTH}). Only the contextual constants and value semantics
 *       from the procedure copybook influence this file; the executable logic
 *       <strong>is intentionally translated to a separate file</strong>:
 *       {@code com.blitzy.carddemo.application.util.DateValidator} per AAP &sect;0.4.1.</li>
 * </ul>
 *
 * <h2>Scope of this file</h2>
 * This record holds <strong>only the working-storage data structures</strong>. It contains
 * no parsing, validation, or business logic beyond field-length and null-safety checks
 * enforced by the canonical constructor (JEP 513 Flexible Constructor Bodies). The
 * executable {@code EDIT-*} paragraphs from {@code CSUTLDPY.cpy} are translated separately
 * to {@code com.blitzy.carddemo.application.util.DateValidator}.
 *
 * <h2>Translated taxonomies</h2>
 * Per AAP &sect;0.6.10 the COBOL 88-level conditions that partition a value space are
 * realized as sealed interfaces with compiler-enforced exhaustiveness:
 * <ul>
 *   <li>{@link ValidityFlag} &mdash; the {@code FLG-*-ISVALID / FLG-*-NOT-OK /
 *       FLG-*-BLANK} 3-state flag area (LOW-VALUE {@code 0x00}, {@code '0'} {@code 0x30},
 *       {@code 'B'} {@code 0x42}).</li>
 *   <li>{@link Century} &mdash; the {@code THIS-CENTURY VALUE 20 / LAST-CENTURY VALUE 19}
 *       hierarchy. Per {@code CSUTLDPY.cpy} lines 66-69 (verbatim COBOL comment): <em>"Not
 *       having learnt our lesson from history and Y2K / And being unable to imagine COBOL
 *       in the 2100s / We code only 19 and 20 as valid century values"</em> &mdash;
 *       therefore the {@link Century.Other Other} permit exists to <em>represent</em> any
 *       non-19/20 value but is rejected by the {@code EDIT-YEAR-CCYY} procedure (translated
 *       separately in {@code DateValidator}).</li>
 * </ul>
 *
 * <h2>Numeric range predicates</h2>
 * Per AAP &sect;0.1.3 ("Closed business taxonomies are exhaustive") only value-space
 * partitions become sealed hierarchies. The 88-level conditions that partition value
 * <em>ranges</em> ({@code WS-VALID-MONTH 1-12}, {@code WS-31-DAY-MONTH (1,3,5,7,8,10,12)},
 * {@code WS-FEBRUARY 2}, {@code WS-VALID-DAY 1-31}, {@code WS-DAY-31}, {@code WS-DAY-30},
 * {@code WS-DAY-29}, {@code WS-VALID-FEB-DAY 1-28}) become static boolean predicates on
 * {@link DateRules} instead.
 *
 * <h2>LE-service result format</h2>
 * The COBOL date validator delegates to {@code CSUTLDTC} (a wrapper around IBM Language
 * Environment {@code CEEDAYS}). The LE return structure {@code WS-DATE-VALIDATION-RESULT}
 * is captured in the nested {@link DateValidationResult} record. The LE severity
 * convention is: {@code 0} = success, {@code 4} = warning, {@code 8 / 12 / 16} = error.
 * {@link DateValidationResult#isSuccess()} codifies this rule.
 *
 * <h2>Initial state / {@code VALUE} clauses</h2>
 * COBOL initializes {@code WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'} (CSUTLDWY.cpy
 * lines 58-59) &mdash; this {@code VALUE} clause is preserved by the {@link #empty()}
 * factory. All flag fields initialize to {@code LOW-VALUES} which is the
 * {@code ValidityFlag.Valid} permit per the {@code FLG-*-ISVALID VALUE LOW-VALUES}
 * 88-level conditions in CSUTLDWY.
 *
 * <h2>Immutability</h2>
 * As a record this type is deeply immutable. Field-by-field updates are exposed via
 * hand-written {@code with*} copy methods so the EDIT-* paragraphs in {@code DateValidator}
 * can mutate individual flags or fields without rebuilding the full record from scratch.
 * Records in Java 25 do not have built-in {@code with} syntax per AAP &sect;0.1.2.
 *
 * <h2>Not in this file</h2>
 * Per AAP and the assigned-file folder requirements, this file does <strong>not</strong>
 * contain:
 * <ul>
 *   <li>The executable {@code EDIT-*} paragraphs &mdash; those live in
 *       {@code com.blitzy.carddemo.application.util.DateValidator}.</li>
 *   <li>Byte-level {@code parse(byte[])} or {@code encode()} methods &mdash; this is a
 *       pure data record, not an external-file layout (no byte-for-byte parity requirement
 *       applies to working storage).</li>
 *   <li>Any reference to {@code java.util.Date} or {@code java.util.Calendar} &mdash;
 *       forbidden by AAP &sect;0.6.4.</li>
 * </ul>
 *
 * @param editDateCc           {@code WS-EDIT-DATE-CC PIC X(2)} &mdash; century digits
 *                             (character view).
 * @param editDateYy           {@code WS-EDIT-DATE-YY PIC X(2)} &mdash; year-of-century
 *                             digits (character view).
 * @param editDateMm           {@code WS-EDIT-DATE-MM PIC X(2)} &mdash; month digits
 *                             (character view).
 * @param editDateDd           {@code WS-EDIT-DATE-DD PIC X(2)} &mdash; day digits
 *                             (character view).
 * @param editDateBinary       {@code WS-EDIT-DATE-BINARY PIC S9(9) BINARY} &mdash; date
 *                             converted to integer days (set by {@code EDIT-DATE-LE}).
 * @param currentDateYyyyMmDd  {@code WS-CURRENT-DATE-YYYYMMDD PIC X(8)} &mdash; today's
 *                             date in {@code YYYYMMDD} form.
 * @param currentDateBinary    {@code WS-CURRENT-DATE-BINARY PIC S9(9) BINARY} &mdash;
 *                             today's date as integer days.
 * @param yearFlag             {@code WS-EDIT-YEAR-FLG PIC X(01)} &mdash; year-portion
 *                             validity flag (see {@link ValidityFlag}).
 * @param monthFlag            {@code WS-EDIT-MONTH PIC X(01)} &mdash; month-portion
 *                             validity flag (see {@link ValidityFlag}).
 * @param dayFlag              {@code WS-EDIT-DAY PIC X(01)} &mdash; day-portion
 *                             validity flag (see {@link ValidityFlag}).
 * @param dateFormat           {@code WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'} &mdash;
 *                             format mask passed to {@code CSUTLDTC}.
 * @param validationResult     {@code WS-DATE-VALIDATION-RESULT} &mdash; LE service return
 *                             structure populated by {@code CSUTLDTC} (see
 *                             {@link DateValidationResult}).
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 */
@CobolProgram(
        value = "CSUTLDWY",
        sourcePath = "app/cpy/CSUTLDWY.cpy",
        notes = "Working storage for date validation; consolidated with procedure copybook"
                + " CSUTLDPY.cpy. Executable EDIT-* paragraphs translated separately to"
                + " application/util/DateValidator.java."
)
public record DateValidationWork(
        String editDateCc,
        String editDateYy,
        String editDateMm,
        String editDateDd,
        int editDateBinary,
        String currentDateYyyyMmDd,
        int currentDateBinary,
        ValidityFlag yearFlag,
        ValidityFlag monthFlag,
        ValidityFlag dayFlag,
        String dateFormat,
        DateValidationResult validationResult
) {

    /**
     * Compact canonical constructor employing JEP 513 Flexible Constructor Bodies:
     * validation runs <strong>before</strong> the implicit canonical field bindings.
     * Every reference parameter is checked for nullity and every fixed-width string
     * field is checked for the exact COBOL {@code PIC X(n)} length.
     *
     * @throws NullPointerException     if any reference parameter is {@code null}
     * @throws IllegalArgumentException if any fixed-width string parameter does not
     *                                  match its COBOL {@code PIC X(n)} length
     */
    public DateValidationWork {
        Objects.requireNonNull(editDateCc, "editDateCc");
        Objects.requireNonNull(editDateYy, "editDateYy");
        Objects.requireNonNull(editDateMm, "editDateMm");
        Objects.requireNonNull(editDateDd, "editDateDd");
        Objects.requireNonNull(currentDateYyyyMmDd, "currentDateYyyyMmDd");
        Objects.requireNonNull(yearFlag, "yearFlag");
        Objects.requireNonNull(monthFlag, "monthFlag");
        Objects.requireNonNull(dayFlag, "dayFlag");
        Objects.requireNonNull(dateFormat, "dateFormat");
        Objects.requireNonNull(validationResult, "validationResult");

        if (editDateCc.length() != 2) {
            throw new IllegalArgumentException(
                    "editDateCc must be 2 chars (PIC X(2)), got " + editDateCc.length());
        }
        if (editDateYy.length() != 2) {
            throw new IllegalArgumentException(
                    "editDateYy must be 2 chars (PIC X(2)), got " + editDateYy.length());
        }
        if (editDateMm.length() != 2) {
            throw new IllegalArgumentException(
                    "editDateMm must be 2 chars (PIC X(2)), got " + editDateMm.length());
        }
        if (editDateDd.length() != 2) {
            throw new IllegalArgumentException(
                    "editDateDd must be 2 chars (PIC X(2)), got " + editDateDd.length());
        }
        if (currentDateYyyyMmDd.length() != 8) {
            throw new IllegalArgumentException(
                    "currentDateYyyyMmDd must be 8 chars (PIC X(8)), got "
                            + currentDateYyyyMmDd.length());
        }
        if (dateFormat.length() != 8) {
            throw new IllegalArgumentException(
                    "dateFormat must be 8 chars (PIC X(08)), got " + dateFormat.length());
        }
    }

    // ------------------------------------------------------------------
    // Convenience grouping accessors (COBOL group-move equivalents)
    // ------------------------------------------------------------------

    /**
     * Returns the 4-digit {@code CCYY} year as a string (concatenation of
     * {@link #editDateCc} and {@link #editDateYy}). Translation of COBOL
     * {@code WS-EDIT-DATE-CCYY} (a group of {@code CC + YY}).
     *
     * @return 4-character year (e.g., {@code "2025"})
     */
    public String editDateCcyy() {
        return editDateCc + editDateYy;
    }

    /**
     * Returns the 8-digit {@code CCYYMMDD} date as a string. Translation of COBOL
     * {@code WS-EDIT-DATE-CCYYMMDD} (a group of {@code CC + YY + MM + DD}).
     *
     * @return 8-character date (e.g., {@code "20250115"})
     */
    public String editDateCcyyMmDd() {
        return editDateCc + editDateYy + editDateMm + editDateDd;
    }

    // ------------------------------------------------------------------
    // Numeric REDEFINES views (PIC 9(n) overlay of PIC X(n) character fields)
    // ------------------------------------------------------------------

    /**
     * Numeric integer view of {@code WS-EDIT-DATE-CCYYMMDD-N} ({@code REDEFINES} the
     * 8-byte CCYYMMDD as {@code PIC 9(8)}). Returns {@code 0} if any subfield is not
     * numeric (matching COBOL behavior when a {@code REDEFINES} is read on non-numeric
     * content: technically undefined, but commonly observed as zero).
     *
     * @return 8-digit integer or {@code 0} on non-numeric content
     */
    public int editDateCcyyMmDdNumeric() {
        try {
            return Integer.parseInt(editDateCcyyMmDd());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Numeric integer view of {@code WS-EDIT-DATE-CC-N} ({@code REDEFINES} as
     * {@code PIC 9(2)}).
     *
     * @return 2-digit integer century, or {@code -1} on non-numeric content
     */
    public int editDateCcNumeric() {
        try {
            return Integer.parseInt(editDateCc);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Numeric integer view of {@code WS-EDIT-DATE-YY-N} ({@code REDEFINES} as
     * {@code PIC 9(2)}).
     *
     * @return 2-digit integer year-of-century, or {@code -1} on non-numeric content
     */
    public int editDateYyNumeric() {
        try {
            return Integer.parseInt(editDateYy);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Numeric integer view of {@code WS-EDIT-DATE-MM-N} ({@code REDEFINES} as
     * {@code PIC 9(2)}).
     *
     * @return 2-digit integer month, or {@code -1} on non-numeric content
     */
    public int editDateMmNumeric() {
        try {
            return Integer.parseInt(editDateMm);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Numeric integer view of {@code WS-EDIT-DATE-DD-N} ({@code REDEFINES} as
     * {@code PIC 9(2)}).
     *
     * @return 2-digit integer day, or {@code -1} on non-numeric content
     */
    public int editDateDdNumeric() {
        try {
            return Integer.parseInt(editDateDd);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Sealed-hierarchy and flag-state derivations
    // ------------------------------------------------------------------

    /**
     * Returns the {@link Century} permit for the current {@link #editDateCc} value.
     * Non-numeric content maps to {@code Century.Other(0)}. Per the COBOL
     * {@code EDIT-YEAR-CCYY} procedure (CSUTLDPY.cpy lines 70-84) only
     * {@link Century.ThisCentury} (20) and {@link Century.LastCentury} (19) are accepted;
     * any other permit is rejected.
     *
     * @return the {@link Century} permit corresponding to the current CC value
     */
    public Century century() {
        int cc = editDateCcNumeric();
        return Century.fromValue(cc < 0 ? 0 : cc);
    }

    /**
     * 88-level condition {@code WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES} (CSUTLDWY.cpy
     * line 44). Returns {@code true} iff {@link #yearFlag}, {@link #monthFlag}, and
     * {@link #dayFlag} are all {@link ValidityFlag.Valid} (the LOW-VALUE permit).
     *
     * @return {@code true} if all three sub-flags are {@code Valid}
     */
    public boolean isDateValid() {
        return yearFlag instanceof ValidityFlag.Valid
                && monthFlag instanceof ValidityFlag.Valid
                && dayFlag instanceof ValidityFlag.Valid;
    }

    /**
     * 88-level condition {@code WS-EDIT-DATE-IS-INVALID VALUE '000'} (CSUTLDWY.cpy
     * line 45). Returns {@code true} iff {@link #yearFlag}, {@link #monthFlag}, and
     * {@link #dayFlag} are all {@link ValidityFlag.NotOk} (the {@code '0'} permit).
     *
     * @return {@code true} if all three sub-flags are {@code NotOk}
     */
    public boolean isDateInvalid() {
        return yearFlag instanceof ValidityFlag.NotOk
                && monthFlag instanceof ValidityFlag.NotOk
                && dayFlag instanceof ValidityFlag.NotOk;
    }

    // ------------------------------------------------------------------
    // Hand-written {@code with*} copy methods
    //
    // Records in Java 25 do not have built-in {@code with} syntax. These helpers let the
    // EDIT-* paragraphs in {@code DateValidator} mutate individual flags or fields without
    // rebuilding the full record manually. Each returns a NEW {@link DateValidationWork}
    // instance leaving the original immutable.
    // ------------------------------------------------------------------

    /** Returns a copy with {@link #editDateCc} replaced. */
    public DateValidationWork withEditDateCc(String v) {
        return new DateValidationWork(v, editDateYy, editDateMm, editDateDd, editDateBinary,
                currentDateYyyyMmDd, currentDateBinary, yearFlag, monthFlag, dayFlag,
                dateFormat, validationResult);
    }

    /** Returns a copy with {@link #editDateYy} replaced. */
    public DateValidationWork withEditDateYy(String v) {
        return new DateValidationWork(editDateCc, v, editDateMm, editDateDd, editDateBinary,
                currentDateYyyyMmDd, currentDateBinary, yearFlag, monthFlag, dayFlag,
                dateFormat, validationResult);
    }

    /** Returns a copy with {@link #editDateMm} replaced. */
    public DateValidationWork withEditDateMm(String v) {
        return new DateValidationWork(editDateCc, editDateYy, v, editDateDd, editDateBinary,
                currentDateYyyyMmDd, currentDateBinary, yearFlag, monthFlag, dayFlag,
                dateFormat, validationResult);
    }

    /** Returns a copy with {@link #editDateDd} replaced. */
    public DateValidationWork withEditDateDd(String v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, v, editDateBinary,
                currentDateYyyyMmDd, currentDateBinary, yearFlag, monthFlag, dayFlag,
                dateFormat, validationResult);
    }

    /** Returns a copy with {@link #editDateBinary} replaced. */
    public DateValidationWork withEditDateBinary(int v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, editDateDd, v,
                currentDateYyyyMmDd, currentDateBinary, yearFlag, monthFlag, dayFlag,
                dateFormat, validationResult);
    }

    /** Returns a copy with {@link #currentDateYyyyMmDd} replaced. */
    public DateValidationWork withCurrentDateYyyyMmDd(String v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, editDateDd,
                editDateBinary, v, currentDateBinary, yearFlag, monthFlag, dayFlag,
                dateFormat, validationResult);
    }

    /** Returns a copy with {@link #currentDateBinary} replaced. */
    public DateValidationWork withCurrentDateBinary(int v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, editDateDd,
                editDateBinary, currentDateYyyyMmDd, v, yearFlag, monthFlag, dayFlag,
                dateFormat, validationResult);
    }

    /** Returns a copy with {@link #yearFlag} replaced. */
    public DateValidationWork withYearFlag(ValidityFlag v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, editDateDd,
                editDateBinary, currentDateYyyyMmDd, currentDateBinary, v, monthFlag,
                dayFlag, dateFormat, validationResult);
    }

    /** Returns a copy with {@link #monthFlag} replaced. */
    public DateValidationWork withMonthFlag(ValidityFlag v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, editDateDd,
                editDateBinary, currentDateYyyyMmDd, currentDateBinary, yearFlag, v,
                dayFlag, dateFormat, validationResult);
    }

    /** Returns a copy with {@link #dayFlag} replaced. */
    public DateValidationWork withDayFlag(ValidityFlag v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, editDateDd,
                editDateBinary, currentDateYyyyMmDd, currentDateBinary, yearFlag, monthFlag,
                v, dateFormat, validationResult);
    }

    /** Returns a copy with {@link #validationResult} replaced. */
    public DateValidationWork withValidationResult(DateValidationResult v) {
        return new DateValidationWork(editDateCc, editDateYy, editDateMm, editDateDd,
                editDateBinary, currentDateYyyyMmDd, currentDateBinary, yearFlag, monthFlag,
                dayFlag, dateFormat, v);
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    /**
     * Factory for an empty work-area equivalent to the COBOL initial state:
     * <ul>
     *   <li>All character date subfields populated with {@code SPACES}.</li>
     *   <li>All three flag fields populated with {@link ValidityFlag.Valid} ({@code LOW-VALUES}).
     *       This matches the {@code FLG-*-ISVALID VALUE LOW-VALUES} 88-level conditions
     *       (CSUTLDWY.cpy lines 47-57).</li>
     *   <li>Both binary date fields populated with zero.</li>
     *   <li>{@link #dateFormat} pre-loaded with the COBOL {@code VALUE 'YYYYMMDD'} clause
     *       (CSUTLDWY.cpy lines 58-59).</li>
     *   <li>{@link #validationResult} populated with {@link DateValidationResult#empty()}.</li>
     * </ul>
     *
     * <p>This factory does <strong>not</strong> read the system clock; it produces a
     * blank, deterministic record suitable as the seed input to the EDIT-* paragraphs.
     *
     * @return a newly-allocated, blank work area in the COBOL initial state
     */
    public static DateValidationWork empty() {
        return new DateValidationWork(
                "  ",                            // editDateCc          PIC X(2) SPACES
                "  ",                            // editDateYy          PIC X(2) SPACES
                "  ",                            // editDateMm          PIC X(2) SPACES
                "  ",                            // editDateDd          PIC X(2) SPACES
                0,                               // editDateBinary      PIC S9(9) BINARY 0
                "        ",                      // currentDateYyyyMmDd PIC X(8) SPACES
                0,                               // currentDateBinary   PIC S9(9) BINARY 0
                new ValidityFlag.Valid(),        // yearFlag            LOW-VALUES
                new ValidityFlag.Valid(),        // monthFlag           LOW-VALUES
                new ValidityFlag.Valid(),        // dayFlag             LOW-VALUES
                "YYYYMMDD",                      // dateFormat          VALUE 'YYYYMMDD'
                DateValidationResult.empty()     // validationResult    INITIALIZE
        );
    }

    // ==================================================================
    // NESTED TYPES
    // ==================================================================

    /**
     * Sealed hierarchy translating the 3-state COBOL flag area used for
     * {@code WS-EDIT-YEAR-FLG}, {@code WS-EDIT-MONTH}, and {@code WS-EDIT-DAY} in
     * {@code WS-EDIT-DATE-FLGS} (CSUTLDWY.cpy lines 43-57).
     *
     * <p>The three exclusive COBOL 88-level conditions for each 1-byte flag are:
     * <ul>
     *   <li>{@code FLG-*-ISVALID VALUE LOW-VALUES} &mdash; byte {@code 0x00}.</li>
     *   <li>{@code FLG-*-NOT-OK   VALUE '0'}       &mdash; byte {@code 0x30}.</li>
     *   <li>{@code FLG-*-BLANK    VALUE 'B'}       &mdash; byte {@code 0x42}.</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.6.10 88-level conditions partitioning a value space become sealed
     * hierarchies with compiler-enforced exhaustiveness. Pattern-matching switches over a
     * {@code ValidityFlag} instance MUST NOT have a {@code default} branch.
     *
     * <p>Example exhaustive switch:
     * <pre>{@code
     * String label = switch (flag) {
     *     case ValidityFlag.Valid v -> "OK";
     *     case ValidityFlag.NotOk n -> "INVALID";
     *     case ValidityFlag.Blank b -> "BLANK";
     * };
     * }</pre>
     */
    public sealed interface ValidityFlag
            permits ValidityFlag.Valid, ValidityFlag.NotOk, ValidityFlag.Blank {

        /**
         * Returns the underlying COBOL byte value of this flag state.
         * <ul>
         *   <li>{@code 0x00} for {@link Valid} ({@code LOW-VALUE}).</li>
         *   <li>{@code 0x30} for {@link NotOk} ({@code '0'}).</li>
         *   <li>{@code 0x42} for {@link Blank} ({@code 'B'}).</li>
         * </ul>
         *
         * @return the 1-byte COBOL representation of this flag
         */
        byte cobolValue();

        /**
         * Translation of {@code FLG-*-ISVALID VALUE LOW-VALUES}. The neutral / passed-edits
         * state. This is also the initial state for every flag in {@link #empty()} per the
         * COBOL initialization to {@code LOW-VALUES}.
         */
        record Valid() implements ValidityFlag {
            @Override
            public byte cobolValue() {
                return (byte) 0x00;
            }
        }

        /**
         * Translation of {@code FLG-*-NOT-OK VALUE '0'}. The validation-failed state set by
         * the EDIT-* paragraphs when a date sub-field is supplied but invalid (e.g., a
         * non-numeric month or an out-of-range century).
         */
        record NotOk() implements ValidityFlag {
            @Override
            public byte cobolValue() {
                return (byte) '0';
            }
        }

        /**
         * Translation of {@code FLG-*-BLANK VALUE 'B'}. The not-supplied state set by the
         * EDIT-* paragraphs when a date sub-field equals {@code SPACES} or
         * {@code LOW-VALUES} on entry.
         */
        record Blank() implements ValidityFlag {
            @Override
            public byte cobolValue() {
                return (byte) 'B';
            }
        }

        /**
         * Parses a single COBOL byte into a {@link ValidityFlag} permit. The COBOL flag
         * area has three exclusive values: LOW-VALUE ({@code 0x00}), {@code '0'}
         * ({@code 0x30}), and {@code 'B'} ({@code 0x42}); any other byte indicates a
         * corrupted working-storage area.
         *
         * <p><strong>Note on the {@code default} branch.</strong> This factory uses a
         * {@code default} clause because it parses a <em>foreign</em> {@code byte} input
         * &mdash; not a {@link ValidityFlag} instance. Per AAP &sect;0.7.4 the
         * no-{@code default} rule applies only to switches over sealed-type instances
         * where exhaustiveness is the safety guarantee. A factory that maps foreign input
         * into a sealed permit set is allowed to defend against out-of-set inputs.
         *
         * @param b the COBOL byte to parse
         * @return the {@link ValidityFlag} permit corresponding to the byte
         * @throws IllegalArgumentException if the byte does not match a known flag value
         */
        static ValidityFlag fromByte(byte b) {
            return switch (b) {
                case (byte) 0x00 -> new Valid();
                case (byte) '0'  -> new NotOk();
                case (byte) 'B'  -> new Blank();
                default -> throw new IllegalArgumentException(
                        "Unexpected COBOL ValidityFlag byte: 0x%02X".formatted(b & 0xFF));
            };
        }
    }


    /**
     * Sealed hierarchy translating the {@code THIS-CENTURY} / {@code LAST-CENTURY}
     * 88-level conditions on {@code WS-EDIT-DATE-CC-N} (CSUTLDWY.cpy lines 9-10).
     *
     * <p>Per the verbatim COBOL comment at {@code CSUTLDPY.cpy} lines 66-69:
     * <em>"Not having learnt our lesson from history and Y2K / And being unable to
     * imagine COBOL in the 2100s / We code only 19 and 20 as valid century values"</em>.
     * The {@link Other} permit exists to <em>represent</em> any non-19/20 century value
     * for completeness, but the {@code EDIT-YEAR-CCYY} procedure (translated separately
     * to {@code DateValidator}) rejects any century other than {@link ThisCentury} or
     * {@link LastCentury}.
     *
     * <p>Pattern-matching switches over a {@code Century} instance MUST NOT have a
     * {@code default} branch (AAP &sect;0.7.4); the three permits are exhaustive.
     */
    public sealed interface Century
            permits Century.ThisCentury, Century.LastCentury, Century.Other {

        /**
         * Returns the 2-digit COBOL century value held by this permit.
         *
         * @return the 2-digit century (e.g., {@code 20} for {@link ThisCentury},
         *         {@code 19} for {@link LastCentury}, or the raw value for
         *         {@link Other}).
         */
        int cobolValue();

        /**
         * Translation of {@code 88 THIS-CENTURY VALUE 20}. The 21st-century permit.
         */
        record ThisCentury() implements Century {
            @Override
            public int cobolValue() {
                return 20;
            }
        }

        /**
         * Translation of {@code 88 LAST-CENTURY VALUE 19}. The 20th-century permit.
         */
        record LastCentury() implements Century {
            @Override
            public int cobolValue() {
                return 19;
            }
        }

        /**
         * Any 2-digit century value other than {@code 19} or {@code 20}. Per
         * CSUTLDPY.cpy lines 70-84, these values are rejected by
         * {@code EDIT-YEAR-CCYY}; this permit exists so the rejected value can be
         * carried through the work area without losing its original numeric content.
         *
         * @param value the rejected century value (must be in {@code [0, 99]} and must
         *              not be {@code 19} or {@code 20})
         */
        record Other(int value) implements Century {
            /**
             * Compact canonical constructor (JEP 513 Flexible Constructor Bodies).
             * Validates that the value is a 2-digit century and is not one of the two
             * "accepted" centuries (which must use their dedicated permits).
             */
            public Other {
                if (value < 0 || value > 99) {
                    throw new IllegalArgumentException(
                            "Century must be a 2-digit value 0-99, got: " + value);
                }
                if (value == 19 || value == 20) {
                    throw new IllegalArgumentException(
                            "Century " + value + " must use ThisCentury or LastCentury"
                                    + " permit, not Other");
                }
            }

            @Override
            public int cobolValue() {
                return value;
            }
        }

        /**
         * Parses an integer century value (expected range {@code [0, 99]}) into a
         * {@link Century} permit.
         *
         * <p><strong>Note on the {@code default} branch.</strong> Like
         * {@link ValidityFlag#fromByte(byte)}, this factory uses a {@code default}
         * clause because it parses <em>foreign</em> integer input rather than switching
         * over a {@code Century} instance. The no-{@code default} rule applies only to
         * switches over sealed-type instances.
         *
         * @param value the 2-digit century value to parse
         * @return the {@link Century} permit corresponding to the value
         * @throws IllegalArgumentException via {@link Other Other's} constructor if the
         *                                  value is out of the {@code [0, 99]} range
         *                                  (only thrown for the {@code Other} path; 19
         *                                  and 20 always succeed)
         */
        static Century fromValue(int value) {
            return switch (value) {
                case 20 -> new ThisCentury();
                case 19 -> new LastCentury();
                default -> new Other(value);
            };
        }
    }

    /**
     * Translation of the CSUTLDWY 88-level <em>numeric range</em> conditions on
     * {@code WS-EDIT-DATE-MM-N} and {@code WS-EDIT-DATE-DD-N} (CSUTLDWY.cpy lines 19-34).
     *
     * <p>Per AAP &sect;0.1.3 only 88-level conditions that partition a value <em>space</em>
     * (e.g., {@code THIS-CENTURY / LAST-CENTURY}) become sealed hierarchies. Conditions
     * that partition value <em>ranges</em> (e.g., {@code WS-VALID-MONTH 1 THROUGH 12})
     * remain static boolean predicates, which is what this utility class exposes.
     *
     * <p>This class is non-instantiable; all methods are static.
     */
    public static final class DateRules {

        /**
         * Private constructor that throws to enforce static-only usage.
         *
         * @throws UnsupportedOperationException always
         */
        private DateRules() {
            throw new UnsupportedOperationException("Static rules class — do not instantiate");
        }

        /**
         * Backing immutable set for {@link #is31DayMonth(int)}. The set membership
         * preserves the COBOL {@code WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12}
         * declaration <em>exactly</em> &mdash; this is set membership, not arithmetic.
         * Built once at class load time via {@link Set#of(Object, Object, Object,
         * Object, Object, Object, Object)} which returns an immutable
         * {@code Set<Integer>}.
         */
        private static final Set<Integer> MONTHS_WITH_31_DAYS =
                Set.of(1, 3, 5, 7, 8, 10, 12);

        /**
         * Translation of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}.
         *
         * @param month the 2-digit month value
         * @return {@code true} if {@code 1 <= month <= 12}
         */
        public static boolean isValidMonth(int month) {
            return month >= 1 && month <= 12;
        }

        /**
         * Translation of {@code 88 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12}. Returns
         * whether the supplied month nominally has 31 days, preserving the COBOL
         * {@code VALUES} list exactly (set membership, not arithmetic on month length).
         *
         * @param month the 2-digit month value
         * @return {@code true} if {@code month} is in the COBOL {@code VALUES} set
         */
        public static boolean is31DayMonth(int month) {
            return MONTHS_WITH_31_DAYS.contains(month);
        }

        /**
         * Translation of {@code 88 WS-FEBRUARY VALUE 2}.
         *
         * @param month the 2-digit month value
         * @return {@code true} if {@code month == 2}
         */
        public static boolean isFebruary(int month) {
            return month == 2;
        }

        /**
         * Translation of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}.
         *
         * @param day the 2-digit day value
         * @return {@code true} if {@code 1 <= day <= 31}
         */
        public static boolean isValidDay(int day) {
            return day >= 1 && day <= 31;
        }

        /**
         * Translation of {@code 88 WS-DAY-31 VALUE 31}.
         *
         * @param day the 2-digit day value
         * @return {@code true} if {@code day == 31}
         */
        public static boolean isDay31(int day) {
            return day == 31;
        }

        /**
         * Translation of {@code 88 WS-DAY-30 VALUE 30}.
         *
         * @param day the 2-digit day value
         * @return {@code true} if {@code day == 30}
         */
        public static boolean isDay30(int day) {
            return day == 30;
        }

        /**
         * Translation of {@code 88 WS-DAY-29 VALUE 29}.
         *
         * @param day the 2-digit day value
         * @return {@code true} if {@code day == 29}
         */
        public static boolean isDay29(int day) {
            return day == 29;
        }

        /**
         * Translation of {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28}.
         *
         * @param day the 2-digit day value
         * @return {@code true} if {@code 1 <= day <= 28}
         */
        public static boolean isValidFebDay(int day) {
            return day >= 1 && day <= 28;
        }
    }


    /**
     * Translation of CSUTLDWY {@code WS-DATE-VALIDATION-RESULT} (CSUTLDWY.cpy lines
     * 60-85) &mdash; the IBM Language Environment return structure populated by
     * {@code CSUTLDTC} (a {@code CEEDAYS} wrapper).
     *
     * <h2>Wire-format layout (80 bytes)</h2>
     * The COBOL group has 13 sub-fields totalling 80 bytes:
     * <pre>{@code
     *   WS-SEVERITY        PIC X(04)   ←  4 bytes  (variable)
     *   FILLER             PIC X(11)   ← 11 bytes  VALUE 'Mesg Code:' (padded)
     *   WS-MSG-NO          PIC X(04)   ←  4 bytes  (variable)
     *   FILLER             PIC X(01)   ←  1 byte   VALUE SPACE
     *   WS-RESULT          PIC X(15)   ← 15 bytes  (variable)
     *   FILLER             PIC X(01)   ←  1 byte   VALUE SPACE
     *   FILLER             PIC X(09)   ←  9 bytes  VALUE 'TstDate:'  (padded)
     *   WS-DATE            PIC X(10)   ← 10 bytes  (variable)
     *   FILLER             PIC X(01)   ←  1 byte   VALUE SPACE
     *   FILLER             PIC X(10)   ← 10 bytes  VALUE 'Mask used:'
     *   WS-DATE-FMT        PIC X(10)   ← 10 bytes  (variable)
     *   FILLER             PIC X(01)   ←  1 byte   VALUE SPACE
     *   FILLER             PIC X(03)   ←  3 bytes  VALUE SPACES
     *                                   ─────────
     *                                    80 bytes
     * }</pre>
     *
     * <p>This Java record captures only the 5 variable fields. The 8 fixed FILLER
     * literals are reconstructed by {@link #formatted()} at original byte positions.
     *
     * <h2>Severity semantics</h2>
     * IBM Language Environment severities follow the convention:
     * <ul>
     *   <li>{@code 0} &mdash; success (no diagnostic).</li>
     *   <li>{@code 4} &mdash; warning.</li>
     *   <li>{@code 8 / 12 / 16} &mdash; error.</li>
     * </ul>
     * {@link #isSuccess()} treats only {@code severityValue() == 0} as success.
     *
     * @param severity    {@code WS-SEVERITY  PIC X(04)} &mdash; LE severity code as
     *                    a 4-character numeric string (e.g., {@code "0000"} for
     *                    success).
     * @param msgNo       {@code WS-MSG-NO    PIC X(04)} &mdash; LE message number
     *                    (e.g., {@code "2513"}).
     * @param result      {@code WS-RESULT    PIC X(15)} &mdash; the human-readable
     *                    result word (e.g., {@code "Date is invalid"},
     *                    {@code "Date is valid  "}).
     * @param testDate    {@code WS-DATE      PIC X(10)} &mdash; the date being
     *                    validated.
     * @param dateFormat  {@code WS-DATE-FMT  PIC X(10)} &mdash; the format mask used
     *                    by the LE service (e.g., {@code "YYYYMMDD  "}).
     */
    public record DateValidationResult(
            String severity,
            String msgNo,
            String result,
            String testDate,
            String dateFormat
    ) {

        /**
         * Compact canonical constructor (JEP 513 Flexible Constructor Bodies):
         * validates nullity and exact COBOL {@code PIC X(n)} lengths before binding
         * fields.
         *
         * @throws NullPointerException     if any parameter is {@code null}
         * @throws IllegalArgumentException if any parameter does not match its
         *                                  COBOL {@code PIC X(n)} length
         */
        public DateValidationResult {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(msgNo, "msgNo");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(testDate, "testDate");
            Objects.requireNonNull(dateFormat, "dateFormat");

            if (severity.length() != 4) {
                throw new IllegalArgumentException(
                        "severity must be 4 chars (PIC X(04)), got " + severity.length());
            }
            if (msgNo.length() != 4) {
                throw new IllegalArgumentException(
                        "msgNo must be 4 chars (PIC X(04)), got " + msgNo.length());
            }
            if (result.length() != 15) {
                throw new IllegalArgumentException(
                        "result must be 15 chars (PIC X(15)), got " + result.length());
            }
            if (testDate.length() != 10) {
                throw new IllegalArgumentException(
                        "testDate must be 10 chars (PIC X(10)), got " + testDate.length());
            }
            if (dateFormat.length() != 10) {
                throw new IllegalArgumentException(
                        "dateFormat must be 10 chars (PIC X(10)), got " + dateFormat.length());
            }
        }

        /**
         * Numeric view of {@code WS-SEVERITY} via the {@code WS-SEVERITY-N PIC 9(4)
         * REDEFINES} overlay (CSUTLDWY.cpy lines 62-63). Returns {@code 0} on
         * non-numeric content (matching the typical observed behavior of COBOL
         * {@code REDEFINES} reads on non-numeric data: undefined but commonly zero).
         *
         * @return the integer severity, or {@code 0} if non-numeric
         */
        public int severityValue() {
            String s = severity.trim();
            if (s.isEmpty()) {
                return 0;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * Numeric view of {@code WS-MSG-NO} via the {@code WS-MSG-NO-N PIC 9(4)
         * REDEFINES} overlay (CSUTLDWY.cpy lines 67-68). Returns {@code 0} on
         * non-numeric content.
         *
         * @return the integer message number, or {@code 0} if non-numeric
         */
        public int msgNoValue() {
            String s = msgNo.trim();
            if (s.isEmpty()) {
                return 0;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * Translation of the COBOL convention {@code WS-SEVERITY-N = 0} as "the LE
         * service succeeded". Returns {@code true} iff {@link #severityValue()} is
         * zero.
         *
         * @return {@code true} iff this represents a successful LE service call
         */
        public boolean isSuccess() {
            return severityValue() == 0;
        }

        /**
         * Reconstructs the 80-byte COBOL {@code WS-DATE-VALIDATION-RESULT} display
         * string with all FILLER literals at their original positions.
         *
         * <p>Length breakdown
         * (see CSUTLDWY.cpy lines 60-85 for the source field declarations):
         * <table>
         *   <caption>Position layout (80 bytes total)</caption>
         *   <tr><td>severity</td>          <td style="text-align:right"> 4</td></tr>
         *   <tr><td>"Mesg Code: "</td>     <td style="text-align:right">11</td></tr>
         *   <tr><td>msgNo</td>             <td style="text-align:right"> 4</td></tr>
         *   <tr><td>SPACE</td>             <td style="text-align:right"> 1</td></tr>
         *   <tr><td>result</td>            <td style="text-align:right">15</td></tr>
         *   <tr><td>SPACE</td>             <td style="text-align:right"> 1</td></tr>
         *   <tr><td>"TstDate: "</td>       <td style="text-align:right"> 9</td></tr>
         *   <tr><td>testDate</td>          <td style="text-align:right">10</td></tr>
         *   <tr><td>SPACE</td>             <td style="text-align:right"> 1</td></tr>
         *   <tr><td>"Mask used:"</td>      <td style="text-align:right">10</td></tr>
         *   <tr><td>dateFormat</td>        <td style="text-align:right">10</td></tr>
         *   <tr><td>SPACE</td>             <td style="text-align:right"> 1</td></tr>
         *   <tr><td>SPACES (3)</td>        <td style="text-align:right"> 3</td></tr>
         *   <tr><td><strong>TOTAL</strong></td><td style="text-align:right"><strong>80</strong></td></tr>
         * </table>
         *
         * <p>Note that the COBOL {@code FILLER PIC X(11) VALUE 'Mesg Code:'} declaration
         * pads the 10-character literal {@code "Mesg Code:"} with one trailing space to
         * fill its 11-byte slot. Similarly the 9-byte {@code 'TstDate:'} FILLER pads
         * with one trailing space. The 10-byte {@code 'Mask used:'} FILLER is exact.
         *
         * @return the 80-character display string equivalent to the COBOL group
         */
        public String formatted() {
            // Total: 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3 = 80
            return severity            //  4 chars  (WS-SEVERITY)
                    + "Mesg Code: "    // 11 chars  (FILLER PIC X(11) VALUE 'Mesg Code:')
                    + msgNo            //  4 chars  (WS-MSG-NO)
                    + " "              //  1 char   (FILLER PIC X(01) VALUE SPACE)
                    + result           // 15 chars  (WS-RESULT)
                    + " "              //  1 char   (FILLER PIC X(01) VALUE SPACE)
                    + "TstDate: "      //  9 chars  (FILLER PIC X(09) VALUE 'TstDate:')
                    + testDate         // 10 chars  (WS-DATE)
                    + " "              //  1 char   (FILLER PIC X(01) VALUE SPACE)
                    + "Mask used:"     // 10 chars  (FILLER PIC X(10) VALUE 'Mask used:')
                    + dateFormat       // 10 chars  (WS-DATE-FMT)
                    + " "              //  1 char   (FILLER PIC X(01) VALUE SPACE)
                    + "   ";           //  3 chars  (FILLER PIC X(03) VALUE SPACES)
        }

        /**
         * Factory for an empty/uninitialized result. Returns a record where:
         * <ul>
         *   <li>{@code severity} = {@code "0000"} (success severity in 4-char form).</li>
         *   <li>{@code msgNo}    = {@code "0000"} (no message number).</li>
         *   <li>{@code result}, {@code testDate}, {@code dateFormat} are all populated
         *       with spaces of the proper {@code PIC X(n)} length.</li>
         * </ul>
         *
         * <p>This factory is used by {@link DateValidationWork#empty()} to populate the
         * top-level work area's {@link DateValidationWork#validationResult}
         * field with a deterministic initial value.
         *
         * @return a newly-allocated empty result record
         */
        public static DateValidationResult empty() {
            return new DateValidationResult(
                    "0000",              // severity   PIC X(04) — success severity
                    "0000",              // msgNo      PIC X(04) — no message
                    "               ",   // result     PIC X(15) — 15 spaces
                    "          ",        // testDate   PIC X(10) — 10 spaces
                    "          "         // dateFormat PIC X(10) — 10 spaces
            );
        }
    }
}

