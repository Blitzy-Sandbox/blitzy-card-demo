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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Objects;

/**
 * Domain constants and sealed REDEFINES translations of COBOL working-storage
 * copybook {@code app/cpy/CSDAT01Y.cpy} ({@code 01 WS-DATE-TIME}). This class
 * is the canonical, single source of truth for every date / time / timestamp
 * format that the COBOL system reads or writes; every translated paragraph
 * that touches a COBOL date/time field MUST go through one of the
 * {@link DateTimeFormatter} constants or helper methods declared here.
 *
 * <h2>COBOL byte layout (verbatim from {@code app/cpy/CSDAT01Y.cpy} lines
 * 17&ndash;55)</h2>
 * <pre>{@code
 * 01 WS-DATE-TIME.
 *    05 WS-CURDATE-DATA.
 *       10 WS-CURDATE.                                  (8 bytes)
 *          15 WS-CURDATE-YEAR    PIC 9(04).
 *          15 WS-CURDATE-MONTH   PIC 9(02).
 *          15 WS-CURDATE-DAY     PIC 9(02).
 *       10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08).
 *       10 WS-CURTIME.                                  (8 bytes)
 *          15 WS-CURTIME-HOURS   PIC 9(02).
 *          15 WS-CURTIME-MINUTE  PIC 9(02).
 *          15 WS-CURTIME-SECOND  PIC 9(02).
 *          15 WS-CURTIME-MILSEC  PIC 9(02).
 *       10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08).
 *    05 WS-CURDATE-MM-DD-YY.                            (8 bytes - MM/DD/YY)
 *       10 WS-CURDATE-MM         PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE '/'.
 *       10 WS-CURDATE-DD         PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE '/'.
 *       10 WS-CURDATE-YY         PIC 9(02).
 *    05 WS-CURTIME-HH-MM-SS.                            (8 bytes - HH:MM:SS)
 *       10 WS-CURTIME-HH         PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE ':'.
 *       10 WS-CURTIME-MM         PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE ':'.
 *       10 WS-CURTIME-SS         PIC 9(02).
 *    05 WS-TIMESTAMP.                                   (26 bytes)
 *       10 WS-TIMESTAMP-DT-YYYY  PIC 9(04).
 *       10 FILLER                PIC X(01) VALUE '-'.
 *       10 WS-TIMESTAMP-DT-MM    PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE '-'.
 *       10 WS-TIMESTAMP-DT-DD    PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE ' '.
 *       10 WS-TIMESTAMP-TM-HH    PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE ':'.
 *       10 WS-TIMESTAMP-TM-MM    PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE ':'.
 *       10 WS-TIMESTAMP-TM-SS    PIC 9(02).
 *       10 FILLER                PIC X(01) VALUE '.'.
 *       10 WS-TIMESTAMP-TM-MS6   PIC 9(06).
 * }</pre>
 *
 * <h2>Exposed Java surface</h2>
 * <ul>
 *   <li>{@link WsCurDate}: sealed {@code (Components | Numeric)} REDEFINES on
 *       the 8-byte {@code WS-CURDATE} / {@code WS-CURDATE-N} memory region
 *       per AAP &sect;0.6.10.</li>
 *   <li>{@link WsCurTime}: sealed {@code (Components | Numeric)} REDEFINES on
 *       the 8-byte {@code WS-CURTIME} / {@code WS-CURTIME-N} memory region
 *       per AAP &sect;0.6.10.</li>
 *   <li>Seven {@link DateTimeFormatter} constants &mdash; {@link #YYYYMMDD},
 *       {@link #YYYY_MM_DD_COMPACT}, {@link #MM_DD_YY}, {@link #HHMMSSMS},
 *       {@link #HH_MM_SS}, {@link #TIMESTAMP}, {@link #DATE_YYYY_MM_DD} &mdash;
 *       all configured with {@link ResolverStyle#STRICT} per AAP &sect;0.6.4
 *       to reject invalid Gregorian dates (e.g., Feb&nbsp;30, Apr&nbsp;31) at
 *       parse time rather than silently coerce them to a different date.</li>
 *   <li>Character / width constants reproducing the COBOL FILLER literals and
 *       {@code PIC} clause widths byte-for-byte for downstream parsers and
 *       encoders.</li>
 *   <li>Helper methods {@link #formatMmDdYy(LocalDate)},
 *       {@link #parseCurdateMmDdYy(String, int)},
 *       {@link #formatHhMmSs(LocalTime)},
 *       {@link #parseCurtimeHhMmSs(String)},
 *       {@link #formatTimestamp(LocalDateTime)}, and
 *       {@link #parseTimestamp(String)} that convert between
 *       {@code java.time} types and the COBOL fixed-width string
 *       representations.</li>
 * </ul>
 *
 * <h2>STRICT resolver and the {@code uuuu} pattern character</h2>
 * Every {@link DateTimeFormatter} constant declared here uses
 * {@link ResolverStyle#STRICT}. Per the {@link DateTimeFormatter} contract,
 * the year-of-era pattern character {@code 'yyyy'} requires an explicit era
 * (BC/AD) under STRICT resolution and consequently <em>cannot</em> parse a
 * bare 4-digit year. To preserve STRICT behavior while still accepting bare
 * 4-digit years (the COBOL {@code PIC 9(04)} convention), the underlying
 * patterns use the proleptic-year character {@code 'uuuu'} (and the
 * {@code 'uu'} variant for the 2-digit MM/DD/YY view). The Java identifier
 * names {@code YYYYMMDD}, {@code MM_DD_YY}, etc. are preserved because they
 * read naturally for COBOL programmers and match the schema-required
 * {@code members_exposed} list. The pattern character choice is purely an
 * internal implementation detail; the rendered output is byte-identical to
 * the COBOL convention.
 *
 * <h2>Byte-for-byte fidelity (AAP &sect;0.6.5)</h2>
 * For every supported view, the encode / parse round-trip MUST equal the
 * original byte buffer. Specifically:
 * <pre>{@code
 *   WsCurDate.parse(buf, 0).encode()    equals buf[0..8]
 *   WsCurTime.parse(buf, 0).encode()    equals buf[0..8]
 * }</pre>
 * The encoders use US-ASCII (per AAP &sect;0.6.5: the 9 ASCII fixtures under
 * {@code app/data/ASCII} are pre-transcoded; the {@code IBM-1047} EBCDIC
 * codepage is the responsibility of the {@code adapter-file} module's
 * {@code EbcdicTranscoder}, not this domain class).
 *
 * <h2>Pattern-matching switch over sealed types (AAP &sect;0.7.3)</h2>
 * Downstream sites that switch over a {@link WsCurDate} or {@link WsCurTime}
 * instance MUST rely on the compiler's exhaustiveness check &mdash; do NOT
 * add a {@code default} branch that hides a missing permit:
 * <pre>{@code
 *   switch (curDate) {
 *       case WsCurDate.Components c -> handleComponents(c);
 *       case WsCurDate.Numeric n    -> handleNumeric(n);
 *   }
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * All declared state is immutable. The {@link DateTimeFormatter} instances
 * are documented as thread-safe and immutable in the JDK; the constants
 * declared here may be safely shared across virtual threads (per AAP
 * &sect;0.6.6) and {@link java.util.concurrent.ScopedValue} scopes without
 * synchronization.
 *
 * <h2>Forbidden references (AAP &sect;0.6.4)</h2>
 * This class never references {@link java.util.Date} or
 * {@link java.util.Calendar}; the {@code java.time} types are the sole
 * temporal vocabulary in the migrated tree.
 *
 * @see <a href="../../../../../../../../../app/cpy/CSDAT01Y.cpy">CSDAT01Y.cpy</a>
 * @see WsCurDate
 * @see WsCurTime
 */
@CobolProgram(
        value = "CSDAT01Y",
        sourcePath = "app/cpy/CSDAT01Y.cpy",
        notes = "WS-DATE-TIME working storage. Provides DateTimeFormatter constants "
                + "(all ResolverStyle.STRICT per AAP §0.6.4) and sealed REDEFINES "
                + "translations for WS-CURDATE and WS-CURTIME per AAP §0.6.10."
)
public final class DateConstants {

    // =========================================================================
    // DateTimeFormatter constants — all use ResolverStyle.STRICT per AAP §0.6.4.
    // Patterns use 'uuuu' / 'uu' (proleptic year) instead of 'yyyy' / 'yy'
    // (year-of-era) so STRICT resolution can parse a bare 4-digit (or 2-digit)
    // year without an explicit era specifier. See class Javadoc for details.
    // =========================================================================

    /**
     * Format for {@code WS-CURDATE} ({@code PIC 9(8)} view): {@code YYYYMMDD}
     * (8 ASCII digits, no separators).
     *
     * <p>Used throughout the codebase wherever a COBOL {@code PIC 9(8)} date
     * is read or written; the canonical view of {@code WS-EDIT-DATE-CCYYMMDD}
     * and {@code WS-CURRENT-DATE-YYYYMMDD}.
     *
     * <p>Example: parsing {@code "20240315"} yields
     * {@code LocalDate.of(2024, 3, 15)}; formatting that same value yields
     * {@code "20240315"}. STRICT mode rejects {@code "20240230"} (Feb&nbsp;30)
     * and {@code "20240431"} (Apr&nbsp;31).
     */
    public static final DateTimeFormatter YYYYMMDD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Alias for {@link #YYYYMMDD}. Provided as a named constant for call sites
     * that derive their input from the COBOL group view
     * ({@code WS-CURDATE-YEAR} + {@code WS-CURDATE-MONTH} + {@code WS-CURDATE-DAY},
     * 4+2+2 character encoding) rather than from the
     * {@code WS-CURDATE-N PIC 9(8)} REDEFINES.
     *
     * <p>Functionally identical to {@link #YYYYMMDD}; only the named binding
     * differs to improve readability at the call site.
     */
    public static final DateTimeFormatter YYYY_MM_DD_COMPACT = YYYYMMDD;

    /**
     * Format for {@code WS-CURDATE-MM-DD-YY}: {@code MM/DD/YY} (8 bytes total,
     * including the two literal {@code '/'} separator bytes).
     *
     * <p><strong>WARNING &mdash; 2-digit year semantics:</strong> This is a
     * 2-digit year format. {@link ResolverStyle#STRICT} does NOT impose the
     * COBOL century rule (only {@code 19} and {@code 20} are valid centuries
     * per {@code CSUTLDPY}); under STRICT, {@link LocalDate#parse(CharSequence,
     * DateTimeFormatter)} interprets a bare 2-digit year via the formatter's
     * default base date, which is platform-dependent. For unambiguous COBOL
     * semantics use {@link #parseCurdateMmDdYy(String, int)} with an explicit
     * century hint, OR prefer {@link #DATE_YYYY_MM_DD} for 4-digit-year
     * input.
     *
     * <p>Example: formatting {@code LocalDate.of(2024, 3, 15)} yields
     * {@code "03/15/24"}.
     */
    public static final DateTimeFormatter MM_DD_YY =
            DateTimeFormatter.ofPattern("MM/dd/uu").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Format for {@code WS-CURTIME} ({@code PIC 9(8)} view): {@code HHMMSSMS}
     * (8 ASCII digits, no separators).
     *
     * <p>Byte breakdown: {@code HH} hours (00..23), {@code MM} minutes
     * (00..59), {@code SS} seconds (00..59), {@code MS} hundredths-of-second
     * (00..99).
     *
     * <p><strong>COBOL MILSEC semantics:</strong> {@code WS-CURTIME-MILSEC}
     * is {@code PIC 9(02)} &mdash; 2 ASCII digits representing
     * <em>hundredths-of-a-second</em> (centiseconds), NOT 3-digit
     * milliseconds. The Java pattern character {@code 'SS'} (capital S, two
     * positions) reads the first 2 digits of the nanosecond fraction; for
     * a value of {@code "50"} this yields a nano-of-second value of
     * 500&nbsp;000&nbsp;000 (500&nbsp;ms = 50&nbsp;centiseconds).
     *
     * <p>Example: parsing {@code "13452350"} yields
     * {@code LocalTime.of(13, 45, 23, 500_000_000)}.
     */
    public static final DateTimeFormatter HHMMSSMS =
            DateTimeFormatter.ofPattern("HHmmssSS").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Format for {@code WS-CURTIME-HH-MM-SS}: {@code HH:MM:SS} (8 bytes total,
     * including the two literal {@code ':'} separator bytes).
     *
     * <p>Sub-second precision is dropped on format and set to zero on parse.
     *
     * <p>Example: formatting {@code LocalTime.of(13, 45, 23)} yields
     * {@code "13:45:23"}.
     */
    public static final DateTimeFormatter HH_MM_SS =
            DateTimeFormatter.ofPattern("HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Format for {@code WS-TIMESTAMP}: {@code YYYY-MM-DD HH:MM:SS.SSSSSS}
     * (26 bytes total).
     *
     * <p>This is the canonical timestamp format used by
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} in
     * {@code app/cpy/CVTRA05Y.cpy} (TranRecord) and by the
     * {@code WS-TIMESTAMP} working-storage field in this copybook.
     *
     * <p>The fractional second is 6 digits (microseconds, {@code PIC 9(06)}).
     * <strong>Note:</strong> this is independent of and unrelated to the
     * 2-digit {@code WS-CURTIME-MILSEC} field on {@link #HHMMSSMS}; the two
     * COBOL fields use different precisions.
     *
     * <p>Example: parsing {@code "2024-03-15 13:45:23.123456"} yields a
     * {@link LocalDateTime} with nano-of-second 123&nbsp;456&nbsp;000.
     */
    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Format for ISO-8601 date strings with hyphens: {@code YYYY-MM-DD}
     * (10 bytes total).
     *
     * <p>Used by {@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE}
     * (sic &mdash; COBOL spelling is preserved verbatim), and
     * {@code ACCT-REISSUE-DATE} in {@code app/cpy/CVACT01Y.cpy} (AccountRecord).
     *
     * <p>This is the preferred unambiguous date format and should be used
     * whenever a 4-digit year is available, in preference to
     * {@link #MM_DD_YY} (which carries 2-digit-year ambiguity).
     */
    public static final DateTimeFormatter DATE_YYYY_MM_DD =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    // =========================================================================
    // String / character constants — verbatim COBOL FILLER literals
    // =========================================================================

    /**
     * Literal value of {@code WS-DATE-FORMAT PIC X(08)} from
     * {@code app/cpy/CSUTLDWY.cpy} (date validation working storage),
     * re-exposed here for use by call sites that need the COBOL pattern
     * string itself (e.g., when echoing a validation error back to the user).
     * The 8-character ASCII value is {@code "YYYYMMDD"}.
     */
    public static final String COBOL_DATE_FORMAT = "YYYYMMDD";

    /**
     * Date separator literal from {@code WS-CURDATE-MM-DD-YY}
     * ({@code FILLER PIC X(01) VALUE '/'}). Used at positions 2 and 5 of
     * the 8-byte {@code MM/DD/YY} encoding.
     */
    public static final char DATE_SEPARATOR_SLASH = '/';

    /**
     * Time separator literal from {@code WS-CURTIME-HH-MM-SS}
     * ({@code FILLER PIC X(01) VALUE ':'}). Used at positions 2 and 5 of
     * the 8-byte {@code HH:MM:SS} encoding and at positions 13 and 16 of
     * the 26-byte timestamp encoding.
     */
    public static final char TIME_SEPARATOR_COLON = ':';

    /**
     * Date-component separator literal from {@code WS-TIMESTAMP}
     * ({@code FILLER PIC X(01) VALUE '-'}). Used at positions 4 and 7 of
     * the 26-byte {@code YYYY-MM-DD HH:MM:SS.SSSSSS} encoding.
     */
    public static final char TIMESTAMP_DATE_SEPARATOR = '-';

    /**
     * Date / time separator literal from {@code WS-TIMESTAMP}
     * ({@code FILLER PIC X(01) VALUE ' '}). Used at position 10 of the
     * 26-byte timestamp encoding (between the date and time components).
     */
    public static final char TIMESTAMP_DATETIME_SEPARATOR = ' ';

    /**
     * Sub-second separator literal from {@code WS-TIMESTAMP}
     * ({@code FILLER PIC X(01) VALUE '.'}). Used at position 19 of the
     * 26-byte timestamp encoding (between the seconds and microseconds
     * components).
     */
    public static final char TIMESTAMP_SUBSECOND_SEPARATOR = '.';

    // =========================================================================
    // Field-width constants — verbatim COBOL PIC widths
    // =========================================================================

    /**
     * Width of {@code WS-CURDATE-YEAR PIC 9(04)} and
     * {@code WS-TIMESTAMP-DT-YYYY PIC 9(04)}: 4 ASCII digits.
     */
    public static final int WIDTH_YEAR_4 = 4;

    /**
     * Width of every COBOL {@code PIC 9(02)} subfield in this copybook
     * ({@code WS-CURDATE-MONTH}, {@code WS-CURDATE-DAY},
     * {@code WS-CURTIME-HOURS}, {@code WS-CURTIME-MINUTE},
     * {@code WS-CURTIME-SECOND}, {@code WS-CURTIME-MILSEC}, and all the
     * 2-digit components of {@code WS-CURDATE-MM-DD-YY},
     * {@code WS-CURTIME-HH-MM-SS}, and {@code WS-TIMESTAMP}): 2 ASCII digits.
     */
    public static final int WIDTH_2 = 2;

    /**
     * Width of {@code WS-TIMESTAMP-TM-MS6 PIC 9(06)}: 6 ASCII digits
     * (microseconds). Distinct from the 2-digit {@code WS-CURTIME-MILSEC}
     * (hundredths-of-second) on the {@link #HHMMSSMS} formatter.
     */
    public static final int WIDTH_MICROSECONDS_6 = 6;

    /**
     * Width of the 8-byte numeric views {@code WS-CURDATE-N PIC 9(08)} and
     * {@code WS-CURTIME-N PIC 9(08)}: 8 ASCII digits, no separators.
     * Also the width of the {@code WS-CURDATE} group view (4+2+2 = 8) and
     * the {@code WS-CURTIME} group view (2+2+2+2 = 8).
     */
    public static final int WIDTH_CCYYMMDD = 8;

    /**
     * Width of {@code WS-CURDATE-MM-DD-YY} ({@code MM/DD/YY}, 2+1+2+1+2 = 8)
     * and {@code WS-CURTIME-HH-MM-SS} ({@code HH:MM:SS}, 2+1+2+1+2 = 8):
     * 8 bytes total, including 2 literal separator bytes.
     */
    public static final int WIDTH_FORMATTED_8 = 8;

    /**
     * Width of {@code WS-TIMESTAMP}
     * ({@code YYYY-MM-DD HH:MM:SS.SSSSSS}, 4+1+2+1+2+1+2+1+2+1+2+1+6 = 26):
     * 26 bytes total, including 5 literal separator bytes.
     */
    public static final int WIDTH_TIMESTAMP_26 = 26;

    // =========================================================================
    // Class plumbing
    // =========================================================================

    /**
     * Prevents instantiation. This class is a holder for constants, sealed
     * type definitions, and static helper methods only; there is no instance
     * state to construct.
     *
     * @throws UnsupportedOperationException always
     */
    private DateConstants() {
        throw new UnsupportedOperationException(
                "DateConstants is a constants holder; instances are not permitted.");
    }

    // =========================================================================
    // Sealed REDEFINES — WsCurDate (WS-CURDATE / WS-CURDATE-N)
    // =========================================================================

    /**
     * Sealed REDEFINES translation of the 8-byte COBOL {@code WS-CURDATE}
     * memory region. Per AAP &sect;0.6.10, COBOL {@code REDEFINES} translates
     * to a {@code sealed interface} with one {@code record} permit per
     * alternative view of the same bytes.
     *
     * <p>The COBOL source defines two interpretations of the same 8-byte
     * region:
     * <ul>
     *   <li>{@link Components} &mdash; the group view, with separate
     *       {@code year} ({@code PIC 9(04)}), {@code month}
     *       ({@code PIC 9(02)}), and {@code day} ({@code PIC 9(02)}) fields.</li>
     *   <li>{@link Numeric} &mdash; the {@code WS-CURDATE-N REDEFINES
     *       WS-CURDATE PIC 9(08)} view, with a single 8-digit unsigned long.</li>
     * </ul>
     *
     * <p>Both permits encode to byte-identical 8-byte ASCII output for the
     * same calendar date, satisfying the AAP &sect;0.6.5 byte-for-byte
     * fidelity contract.
     *
     * <p>Per AAP &sect;0.6.10 and &sect;0.7.3, downstream {@code switch}
     * expressions over a {@code WsCurDate} instance MUST be exhaustive and
     * MUST NOT include a {@code default} branch &mdash; the compiler is the
     * safety guarantee that every permit is handled.
     *
     * <p>Example:
     * <pre>{@code
     *   WsCurDate today = WsCurDate.from(LocalDate.now());
     *   LocalDate date  = today.toLocalDate();
     *   byte[]    bytes = today.encode();
     *
     *   String view = switch (today) {
     *       case WsCurDate.Components c -> "Y=" + c.year() + " M=" + c.month();
     *       case WsCurDate.Numeric    n -> "N=" + n.value();
     *   };
     * }</pre>
     */
    public sealed interface WsCurDate permits WsCurDate.Components, WsCurDate.Numeric {

        /**
         * Returns the 8-byte fixed-width ASCII representation of this date in
         * {@code YYYYMMDD} order. The returned bytes are byte-identical
         * for both permits when they represent the same calendar date.
         *
         * <p>Per AAP &sect;0.6.5: this method together with
         * {@link #parse(byte[], int)} forms the formal byte-level contract
         * with any external file consumer. The round-trip invariant
         * {@code parse(buf, 0).encode()} MUST equal {@code buf[0..8]} for
         * every valid 8-byte buffer.
         *
         * @return a fresh 8-byte ASCII array containing the YYYYMMDD encoding;
         *         each call returns a new array so callers may safely mutate
         *         the result without affecting the receiver
         */
        byte[] encode();

        /**
         * Returns the {@link LocalDate} equivalent of this date, validating
         * that the underlying value represents an actual Gregorian calendar
         * date.
         *
         * <p>This method uses {@link ResolverStyle#STRICT} via the
         * {@link #YYYYMMDD} formatter (per AAP &sect;0.6.4), so invalid
         * dates such as {@code 20240230} (Feb&nbsp;30) and
         * {@code 20240431} (Apr&nbsp;31) cause a parse failure rather than
         * silent coercion. Note that the compact-constructor range checks
         * on the individual fields ({@code year} 0..9999, {@code month}
         * 1..12, {@code day} 1..31) are necessary but not sufficient
         * &mdash; the strict calendar validation runs on this call.
         *
         * @return the LocalDate corresponding to this date
         * @throws java.time.format.DateTimeParseException if the underlying
         *         value does not represent a valid Gregorian date
         */
        LocalDate toLocalDate();

        /**
         * Components view of {@code WS-CURDATE}: the 8 bytes interpreted as
         * three separate integer fields per the COBOL group structure.
         *
         * <p>Range validation runs in the compact constructor (per JEP 513
         * Flexible Constructor Bodies, finalized in Java 25) BEFORE field
         * binding, so any out-of-range value is rejected immediately with
         * {@link IllegalArgumentException}. Calendar-level validation
         * (rejecting Feb 30, etc.) runs separately in
         * {@link #toLocalDate()}.
         *
         * @param year  {@code WS-CURDATE-YEAR PIC 9(04)} &mdash; 4-digit
         *              proleptic year (0..9999)
         * @param month {@code WS-CURDATE-MONTH PIC 9(02)} &mdash;
         *              month-of-year (1..12)
         * @param day   {@code WS-CURDATE-DAY PIC 9(02)} &mdash;
         *              day-of-month (1..31, subject to per-month limits
         *              enforced in {@link #toLocalDate()})
         */
        record Components(int year, int month, int day) implements WsCurDate {

            /**
             * Compact canonical constructor with range validation. The COBOL
             * {@code PIC 9(04)} / {@code PIC 9(02)} declarations limit each
             * field to its respective digit width; values outside those
             * ranges cannot exist in the underlying byte buffer and indicate
             * a programming error.
             *
             * @throws IllegalArgumentException if any field is outside the
             *         range implied by its COBOL PIC declaration
             */
            public Components {
                if (year < 0 || year > 9999) {
                    throw new IllegalArgumentException(
                            "WS-CURDATE-YEAR PIC 9(04) must be 0..9999, got " + year);
                }
                if (month < 1 || month > 12) {
                    throw new IllegalArgumentException(
                            "WS-CURDATE-MONTH PIC 9(02) must be 1..12, got " + month);
                }
                if (day < 1 || day > 31) {
                    throw new IllegalArgumentException(
                            "WS-CURDATE-DAY PIC 9(02) must be 1..31, got " + day);
                }
            }

            /**
             * {@inheritDoc}
             *
             * <p>Encodes the YYYY+MM+DD components as zero-padded ASCII
             * digits, producing an 8-byte buffer in {@code YYYYMMDD} order
             * that is byte-identical to what
             * {@link Numeric#encode() Numeric.encode()} produces for the
             * same calendar date.
             */
            @Override
            public byte[] encode() {
                String s = String.format("%04d%02d%02d", year, month, day);
                return s.getBytes(StandardCharsets.US_ASCII);
            }

            /**
             * {@inheritDoc}
             *
             * <p>Performs strict Gregorian validation: rejects calendar-
             * impossible dates such as Feb&nbsp;30, Apr&nbsp;31, or
             * Feb&nbsp;29 of a non-leap year, even though those values
             * passed the broader range checks in the compact constructor.
             */
            @Override
            public LocalDate toLocalDate() {
                String s = String.format("%04d%02d%02d", year, month, day);
                return LocalDate.parse(s, YYYYMMDD);
            }
        }

        /**
         * Numeric view of {@code WS-CURDATE}: the 8 bytes interpreted as a
         * single 8-digit unsigned integer per the COBOL
         * {@code WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)} declaration.
         *
         * <p>The {@code value} is the YYYYMMDD digit representation as a
         * single {@code long} (e.g., March 15, 2024 = 20240315).
         *
         * @param value 8-digit YYYYMMDD value (0..99&nbsp;999&nbsp;999)
         */
        record Numeric(long value) implements WsCurDate {

            /**
             * Compact canonical constructor with range validation. The COBOL
             * {@code PIC 9(08)} declaration limits the value to exactly 8
             * digits; values outside 0..99&nbsp;999&nbsp;999 indicate a
             * programming error.
             *
             * @throws IllegalArgumentException if {@code value} is outside
             *         {@code 0..99_999_999}
             */
            public Numeric {
                if (value < 0L || value > 99_999_999L) {
                    throw new IllegalArgumentException(
                            "WS-CURDATE-N PIC 9(08) must be 0..99999999, got " + value);
                }
            }

            /**
             * {@inheritDoc}
             *
             * <p>Encodes the 8-digit value as zero-padded ASCII digits,
             * producing a buffer that is byte-identical to what
             * {@link Components#encode()} produces for the same calendar date.
             */
            @Override
            public byte[] encode() {
                String s = String.format("%08d", value);
                return s.getBytes(StandardCharsets.US_ASCII);
            }

            /**
             * {@inheritDoc}
             *
             * <p>Decomposes the 8-digit value into year/month/day and
             * performs strict Gregorian validation.
             */
            @Override
            public LocalDate toLocalDate() {
                String s = String.format("%08d", value);
                return LocalDate.parse(s, YYYYMMDD);
            }
        }

        /**
         * Parses an 8-byte buffer at the given offset as a {@code WS-CURDATE}
         * value and returns the {@link Components} permit. The returned
         * permit's {@link #encode()} method is guaranteed to reproduce the
         * input bytes byte-for-byte (the round-trip invariant from AAP
         * &sect;0.6.5).
         *
         * <p>Defaulting to the {@link Components} permit is a deliberate
         * design choice: the parser cannot infer from the bytes alone which
         * alternative interpretation the caller intends, so the canonical
         * group view is returned. Callers convert to {@link Numeric}
         * explicitly via {@code new Numeric(Long.parseLong(...))} when they
         * specifically need that view.
         *
         * <p>This method does <strong>not</strong> validate that the digits
         * form a calendar-valid date (e.g., Feb&nbsp;30 will produce a
         * valid {@code Components} record). Calendar validation runs on
         * {@link #toLocalDate()}. This deliberate split lets callers reject
         * malformed bytes (range failures) separately from invalid dates
         * (calendar failures).
         *
         * @param buffer source buffer of ASCII digits; must not be {@code null}
         *               and must contain at least {@code offset + 8} bytes
         * @param offset byte offset into {@code buffer} at which the
         *               8-byte date begins (must be non-negative)
         * @return a {@link Components} permit decoded from the 8 bytes
         *         starting at {@code offset}
         * @throws NullPointerException     if {@code buffer} is {@code null}
         * @throws IllegalArgumentException if {@code offset} is negative,
         *         the buffer is too short, or any of the 8 bytes is not an
         *         ASCII digit
         */
        static WsCurDate parse(byte[] buffer, int offset) {
            Objects.requireNonNull(buffer, "buffer");
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "offset must be non-negative, got " + offset);
            }
            if (offset + WIDTH_CCYYMMDD > buffer.length) {
                throw new IllegalArgumentException(
                        "buffer too short: need offset + " + WIDTH_CCYYMMDD
                                + " bytes, have offset=" + offset
                                + " buffer.length=" + buffer.length);
            }
            String s = new String(buffer, offset, WIDTH_CCYYMMDD,
                    StandardCharsets.US_ASCII);
            int year;
            int month;
            int day;
            try {
                year  = Integer.parseInt(s.substring(0, 4));
                month = Integer.parseInt(s.substring(4, 6));
                day   = Integer.parseInt(s.substring(6, 8));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "WS-CURDATE bytes not numeric: '" + s + "'", e);
            }
            return new Components(year, month, day);
        }

        /**
         * Constructs a {@link Components} permit from a {@link LocalDate}.
         * This is the canonical factory for converting a domain
         * {@code LocalDate} into the COBOL representation prior to encoding
         * or screen display.
         *
         * @param date the source date; must not be {@code null}
         * @return a {@link Components} permit equivalent to {@code date}
         * @throws NullPointerException if {@code date} is {@code null}
         */
        static WsCurDate from(LocalDate date) {
            Objects.requireNonNull(date, "date");
            return new Components(date.getYear(), date.getMonthValue(),
                    date.getDayOfMonth());
        }
    }

    // =========================================================================
    // Sealed REDEFINES — WsCurTime (WS-CURTIME / WS-CURTIME-N)
    // =========================================================================

    /**
     * Sealed REDEFINES translation of the 8-byte COBOL {@code WS-CURTIME}
     * memory region. Per AAP &sect;0.6.10, COBOL {@code REDEFINES} translates
     * to a {@code sealed interface} with one {@code record} permit per
     * alternative view of the same bytes.
     *
     * <p>The COBOL source defines two interpretations of the same 8-byte
     * region:
     * <ul>
     *   <li>{@link Components} &mdash; the group view, with separate
     *       {@code hours}, {@code minute}, {@code second}, and {@code milsec}
     *       fields (each {@code PIC 9(02)}).</li>
     *   <li>{@link Numeric} &mdash; the {@code WS-CURTIME-N REDEFINES
     *       WS-CURTIME PIC 9(08)} view, with a single 8-digit unsigned long.</li>
     * </ul>
     *
     * <p><strong>MILSEC semantics &mdash; READ THIS CAREFULLY:</strong>
     * {@code WS-CURTIME-MILSEC} is {@code PIC 9(02)} (2 digits), representing
     * hundredths-of-a-second (centiseconds) in the range 0..99. It is
     * <em>not</em> 3-digit milliseconds. The Java {@link LocalTime}
     * representation stores sub-second precision as nano-of-second, so the
     * conversion is:
     * <pre>{@code
     *   nano-of-second = milsec * 10_000_000
     *                  = milsec * 10ms * 1_000_000ns/ms
     * }</pre>
     * For example, a {@code milsec} value of 50 corresponds to 500 ms,
     * i.e., a nano-of-second value of 500&nbsp;000&nbsp;000. This is
     * distinct from {@code WS-TIMESTAMP-TM-MS6} ({@code PIC 9(06)} on
     * {@link #TIMESTAMP}), which is a 6-digit microsecond field.
     *
     * <p>Per AAP &sect;0.6.10 and &sect;0.7.3, downstream {@code switch}
     * expressions over a {@code WsCurTime} instance MUST be exhaustive and
     * MUST NOT include a {@code default} branch.
     */
    public sealed interface WsCurTime permits WsCurTime.Components, WsCurTime.Numeric {

        /**
         * Returns the 8-byte fixed-width ASCII representation of this time
         * in {@code HHMMSSMS} order (hours, minutes, seconds, centiseconds).
         *
         * <p>Per AAP &sect;0.6.5: this method together with
         * {@link #parse(byte[], int)} forms the formal byte-level contract.
         * The round-trip invariant {@code parse(buf, 0).encode()} MUST equal
         * {@code buf[0..8]} for every valid 8-byte buffer.
         *
         * @return a fresh 8-byte ASCII array containing the HHMMSSMS encoding;
         *         each call returns a new array so callers may safely mutate
         *         the result without affecting the receiver
         */
        byte[] encode();

        /**
         * Returns the {@link LocalTime} equivalent of this time. The COBOL
         * {@code milsec} value (0..99) is converted to nano-of-second by
         * multiplying by 10&nbsp;000&nbsp;000 (1 centisecond = 10&nbsp;ms
         * = 10&nbsp;000&nbsp;000&nbsp;ns).
         *
         * @return the LocalTime corresponding to this time
         * @throws java.time.DateTimeException if the underlying value does
         *         not represent a valid time of day (e.g., hours&gt;23)
         */
        LocalTime toLocalTime();

        /**
         * Components view of {@code WS-CURTIME}: the 8 bytes interpreted as
         * four separate integer fields per the COBOL group structure.
         *
         * <p>Range validation runs in the compact constructor (per JEP 513
         * Flexible Constructor Bodies, finalized in Java 25) BEFORE field
         * binding.
         *
         * @param hours  {@code WS-CURTIME-HOURS PIC 9(02)} &mdash;
         *               hour-of-day (0..23)
         * @param minute {@code WS-CURTIME-MINUTE PIC 9(02)} &mdash;
         *               minute-of-hour (0..59)
         * @param second {@code WS-CURTIME-SECOND PIC 9(02)} &mdash;
         *               second-of-minute (0..59)
         * @param milsec {@code WS-CURTIME-MILSEC PIC 9(02)} &mdash;
         *               centiseconds-of-second (0..99); NOT milliseconds.
         *               See the class Javadoc for the conversion to
         *               nano-of-second.
         */
        record Components(int hours, int minute, int second, int milsec)
                implements WsCurTime {

            /**
             * Compact canonical constructor with range validation. The COBOL
             * {@code PIC 9(02)} declarations limit each field to its
             * respective range; values outside those ranges indicate a
             * programming error.
             *
             * @throws IllegalArgumentException if any field is outside its
             *         valid range
             */
            public Components {
                if (hours < 0 || hours > 23) {
                    throw new IllegalArgumentException(
                            "WS-CURTIME-HOURS PIC 9(02) must be 0..23, got " + hours);
                }
                if (minute < 0 || minute > 59) {
                    throw new IllegalArgumentException(
                            "WS-CURTIME-MINUTE PIC 9(02) must be 0..59, got " + minute);
                }
                if (second < 0 || second > 59) {
                    throw new IllegalArgumentException(
                            "WS-CURTIME-SECOND PIC 9(02) must be 0..59, got " + second);
                }
                if (milsec < 0 || milsec > 99) {
                    throw new IllegalArgumentException(
                            "WS-CURTIME-MILSEC PIC 9(02) must be 0..99 "
                                    + "(centiseconds), got " + milsec);
                }
            }

            /**
             * {@inheritDoc}
             *
             * <p>Encodes the HH+MM+SS+MS components as zero-padded ASCII
             * digits, producing an 8-byte buffer in {@code HHMMSSMS} order
             * that is byte-identical to what {@link Numeric#encode()}
             * produces for the same time.
             */
            @Override
            public byte[] encode() {
                String s = String.format("%02d%02d%02d%02d",
                        hours, minute, second, milsec);
                return s.getBytes(StandardCharsets.US_ASCII);
            }

            /**
             * {@inheritDoc}
             *
             * <p>Conversion of {@code milsec} (centiseconds) to nano-of-second:
             * {@code nano = milsec * 10_000_000}. For example,
             * {@code milsec = 50} maps to {@code nano = 500_000_000}
             * (i.e., 500 ms).
             */
            @Override
            public LocalTime toLocalTime() {
                int nanos = milsec * 10_000_000;
                return LocalTime.of(hours, minute, second, nanos);
            }
        }

        /**
         * Numeric view of {@code WS-CURTIME}: the 8 bytes interpreted as a
         * single 8-digit unsigned integer per the COBOL
         * {@code WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08)} declaration.
         *
         * <p>The {@code value} is the HHMMSSMS digit representation as a
         * single {@code long} (e.g., 13:45:23 with 50 centiseconds =
         * 13452350).
         *
         * @param value 8-digit HHMMSSMS value (0..99&nbsp;999&nbsp;999)
         */
        record Numeric(long value) implements WsCurTime {

            /**
             * Compact canonical constructor with range validation.
             *
             * @throws IllegalArgumentException if {@code value} is outside
             *         {@code 0..99_999_999}
             */
            public Numeric {
                if (value < 0L || value > 99_999_999L) {
                    throw new IllegalArgumentException(
                            "WS-CURTIME-N PIC 9(08) must be 0..99999999, got " + value);
                }
            }

            /**
             * {@inheritDoc}
             *
             * <p>Encodes the 8-digit value as zero-padded ASCII digits.
             */
            @Override
            public byte[] encode() {
                String s = String.format("%08d", value);
                return s.getBytes(StandardCharsets.US_ASCII);
            }

            /**
             * {@inheritDoc}
             *
             * <p>Decomposes the 8-digit value into hours / minute / second /
             * milsec and delegates to {@link Components#toLocalTime()} for
             * the centiseconds-to-nanoseconds conversion. The individual
             * field range checks happen in the {@link Components}
             * canonical constructor; an out-of-range numeric value (e.g.,
             * a logical hour-of-day of 24) is caught by the
             * {@code Components} compact constructor and surfaces as an
             * {@link IllegalArgumentException} here.
             */
            @Override
            public LocalTime toLocalTime() {
                String s = String.format("%08d", value);
                int h = Integer.parseInt(s.substring(0, 2));
                int m = Integer.parseInt(s.substring(2, 4));
                int sec = Integer.parseInt(s.substring(4, 6));
                int ms = Integer.parseInt(s.substring(6, 8));
                return new Components(h, m, sec, ms).toLocalTime();
            }
        }

        /**
         * Parses an 8-byte buffer at the given offset as a {@code WS-CURTIME}
         * value and returns the {@link Components} permit. The returned
         * permit's {@link #encode()} method is guaranteed to reproduce the
         * input bytes byte-for-byte.
         *
         * <p>As with {@link WsCurDate#parse(byte[], int)}, the
         * {@link Components} permit is the default return type; callers
         * convert to {@link Numeric} explicitly when they need that view.
         *
         * @param buffer source buffer of ASCII digits; must not be {@code null}
         *               and must contain at least {@code offset + 8} bytes
         * @param offset byte offset into {@code buffer} at which the
         *               8-byte time begins (must be non-negative)
         * @return a {@link Components} permit decoded from the 8 bytes
         *         starting at {@code offset}
         * @throws NullPointerException     if {@code buffer} is {@code null}
         * @throws IllegalArgumentException if {@code offset} is negative,
         *         the buffer is too short, any of the 8 bytes is not an
         *         ASCII digit, or any decoded component is outside its
         *         valid range
         */
        static WsCurTime parse(byte[] buffer, int offset) {
            Objects.requireNonNull(buffer, "buffer");
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "offset must be non-negative, got " + offset);
            }
            if (offset + WIDTH_CCYYMMDD > buffer.length) {
                throw new IllegalArgumentException(
                        "buffer too short: need offset + " + WIDTH_CCYYMMDD
                                + " bytes, have offset=" + offset
                                + " buffer.length=" + buffer.length);
            }
            String s = new String(buffer, offset, WIDTH_CCYYMMDD,
                    StandardCharsets.US_ASCII);
            int h;
            int m;
            int sec;
            int ms;
            try {
                h   = Integer.parseInt(s.substring(0, 2));
                m   = Integer.parseInt(s.substring(2, 4));
                sec = Integer.parseInt(s.substring(4, 6));
                ms  = Integer.parseInt(s.substring(6, 8));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "WS-CURTIME bytes not numeric: '" + s + "'", e);
            }
            return new Components(h, m, sec, ms);
        }

        /**
         * Constructs a {@link Components} permit from a {@link LocalTime}.
         * This is the canonical factory for converting a domain
         * {@code LocalTime} into the COBOL representation prior to
         * encoding or screen display.
         *
         * <p>The sub-second precision is reduced from {@code LocalTime}'s
         * nano-of-second to the COBOL 2-digit centisecond field via integer
         * division by 10&nbsp;000&nbsp;000. Any sub-centisecond precision
         * is truncated (not rounded) &mdash; this matches the COBOL
         * behavior of dropping any precision finer than what the
         * destination field can store.
         *
         * @param time the source time; must not be {@code null}
         * @return a {@link Components} permit equivalent to {@code time} at
         *         centisecond precision
         * @throws NullPointerException if {@code time} is {@code null}
         */
        static WsCurTime from(LocalTime time) {
            Objects.requireNonNull(time, "time");
            int milsec = time.getNano() / 10_000_000;
            return new Components(time.getHour(), time.getMinute(),
                    time.getSecond(), milsec);
        }
    }

    // =========================================================================
    // Helper methods — formatted COBOL views (MM/DD/YY, HH:MM:SS, full TIMESTAMP)
    // =========================================================================

    /**
     * Formats a {@link LocalDate} as the COBOL {@code WS-CURDATE-MM-DD-YY}
     * string: {@code MM/DD/YY} (8 characters, including two literal
     * {@code '/'} separators).
     *
     * <p>The century is dropped on output; callers needing unambiguous
     * 4-digit year representation should use {@link #DATE_YYYY_MM_DD}
     * directly.
     *
     * <p>Example: {@code formatMmDdYy(LocalDate.of(2024, 3, 15))} returns
     * {@code "03/15/24"}.
     *
     * @param date the date to format; must not be {@code null}
     * @return an 8-character {@code MM/DD/YY} string
     * @throws NullPointerException if {@code date} is {@code null}
     */
    public static String formatMmDdYy(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return date.format(MM_DD_YY);
    }

    /**
     * Parses a COBOL {@code WS-CURDATE-MM-DD-YY} string ({@code MM/DD/YY},
     * 8 characters) into a {@link LocalDate}, using an explicit century
     * hint to disambiguate the 2-digit year.
     *
     * <p>The COBOL century constraint (only {@code 19} and {@code 20} are
     * valid centuries per {@code app/cpy/CSUTLDPY.cpy}) is enforced here;
     * any other century argument is rejected with
     * {@link IllegalArgumentException}.
     *
     * <p>This method is the strict-and-explicit alternative to using
     * {@link #MM_DD_YY} directly on a {@link LocalDate#parse(CharSequence,
     * java.time.format.DateTimeFormatter) LocalDate.parse} call &mdash; that
     * call's behavior under STRICT resolution is platform-dependent for
     * bare 2-digit years and is best avoided.
     *
     * <p>Example: {@code parseCurdateMmDdYy("03/15/24", 20)} returns
     * {@code LocalDate.of(2024, 3, 15)}.
     *
     * @param mmDdYy  8-character {@code MM/DD/YY} string; must not be {@code null}
     * @param century the century to apply (must be 19 or 20 per the COBOL
     *                {@code CSUTLDPY} constraint)
     * @return the parsed {@link LocalDate}
     * @throws NullPointerException     if {@code mmDdYy} is {@code null}
     * @throws IllegalArgumentException if {@code mmDdYy} is not 8 characters,
     *                                  the separators are not {@code '/'},
     *                                  any digit positions are non-numeric,
     *                                  the {@code century} argument is not
     *                                  19 or 20, or the resulting calendar
     *                                  date is invalid
     */
    public static LocalDate parseCurdateMmDdYy(String mmDdYy, int century) {
        Objects.requireNonNull(mmDdYy, "mmDdYy");
        if (mmDdYy.length() != WIDTH_FORMATTED_8) {
            throw new IllegalArgumentException(
                    "WS-CURDATE-MM-DD-YY must be " + WIDTH_FORMATTED_8
                            + " characters, got " + mmDdYy.length());
        }
        if (century != 19 && century != 20) {
            throw new IllegalArgumentException(
                    "century must be 19 or 20 (CSUTLDPY constraint), got " + century);
        }
        if (mmDdYy.charAt(2) != DATE_SEPARATOR_SLASH
                || mmDdYy.charAt(5) != DATE_SEPARATOR_SLASH) {
            throw new IllegalArgumentException(
                    "WS-CURDATE-MM-DD-YY must have '" + DATE_SEPARATOR_SLASH
                            + "' separators at positions 2 and 5, got '" + mmDdYy + "'");
        }
        int month;
        int day;
        int yy;
        try {
            month = Integer.parseInt(mmDdYy.substring(0, 2));
            day   = Integer.parseInt(mmDdYy.substring(3, 5));
            yy    = Integer.parseInt(mmDdYy.substring(6, 8));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "WS-CURDATE-MM-DD-YY contains non-numeric digit positions: '"
                            + mmDdYy + "'", e);
        }
        int year = century * 100 + yy;
        String yyyymmdd = String.format("%04d%02d%02d", year, month, day);
        return LocalDate.parse(yyyymmdd, YYYYMMDD);
    }

    /**
     * Formats a {@link LocalTime} as the COBOL {@code WS-CURTIME-HH-MM-SS}
     * string: {@code HH:MM:SS} (8 characters, including two literal
     * {@code ':'} separators).
     *
     * <p>Sub-second precision is dropped on output; if you need centisecond
     * precision, use {@link WsCurTime#from(LocalTime)} together with
     * {@link WsCurTime#encode()} to emit the 8-byte
     * {@link #HHMMSSMS} encoding instead.
     *
     * <p>Example: {@code formatHhMmSs(LocalTime.of(13, 45, 23))} returns
     * {@code "13:45:23"}.
     *
     * @param time the time to format; must not be {@code null}
     * @return an 8-character {@code HH:MM:SS} string
     * @throws NullPointerException if {@code time} is {@code null}
     */
    public static String formatHhMmSs(LocalTime time) {
        Objects.requireNonNull(time, "time");
        return time.format(HH_MM_SS);
    }

    /**
     * Parses a COBOL {@code WS-CURTIME-HH-MM-SS} string ({@code HH:MM:SS},
     * 8 characters) into a {@link LocalTime}.
     *
     * <p>The resulting {@link LocalTime}'s nano-of-second is zero
     * (sub-second precision is not encoded in this format).
     *
     * <p>Example: {@code parseCurtimeHhMmSs("13:45:23")} returns
     * {@code LocalTime.of(13, 45, 23)}.
     *
     * @param hhMmSs 8-character {@code HH:MM:SS} string; must not be {@code null}
     * @return the parsed {@link LocalTime}
     * @throws NullPointerException     if {@code hhMmSs} is {@code null}
     * @throws IllegalArgumentException if {@code hhMmSs} is not 8 characters
     * @throws java.time.format.DateTimeParseException if the string cannot
     *         be parsed as a strict {@code HH:MM:SS} time
     */
    public static LocalTime parseCurtimeHhMmSs(String hhMmSs) {
        Objects.requireNonNull(hhMmSs, "hhMmSs");
        if (hhMmSs.length() != WIDTH_FORMATTED_8) {
            throw new IllegalArgumentException(
                    "WS-CURTIME-HH-MM-SS must be " + WIDTH_FORMATTED_8
                            + " characters, got " + hhMmSs.length());
        }
        return LocalTime.parse(hhMmSs, HH_MM_SS);
    }

    /**
     * Formats a {@link LocalDateTime} as the COBOL {@code WS-TIMESTAMP}
     * string: {@code YYYY-MM-DD HH:MM:SS.SSSSSS} (26 characters).
     *
     * <p>The fractional second is formatted as 6 digits (microsecond
     * precision, per {@code WS-TIMESTAMP-TM-MS6 PIC 9(06)}). The first 6
     * digits of the {@link LocalDateTime#getNano() nano-of-second} are
     * emitted; sub-microsecond precision is truncated (not rounded), which
     * matches the COBOL behavior of dropping precision finer than the
     * destination field can store.
     *
     * <p>This is distinct from the 2-digit {@code WS-CURTIME-MILSEC}
     * (centiseconds) field on {@link #HHMMSSMS} &mdash; the two COBOL
     * fields use different sub-second precisions and must not be confused.
     *
     * <p>Example: {@code formatTimestamp(LocalDateTime.of(2024, 3, 15, 13,
     * 45, 23, 123_456_000))} returns {@code "2024-03-15 13:45:23.123456"}.
     *
     * @param ts the timestamp to format; must not be {@code null}
     * @return a 26-character {@code YYYY-MM-DD HH:MM:SS.SSSSSS} string
     * @throws NullPointerException if {@code ts} is {@code null}
     */
    public static String formatTimestamp(LocalDateTime ts) {
        Objects.requireNonNull(ts, "ts");
        return ts.format(TIMESTAMP);
    }

    /**
     * Parses a COBOL {@code WS-TIMESTAMP} string ({@code YYYY-MM-DD
     * HH:MM:SS.SSSSSS}, 26 characters) into a {@link LocalDateTime}.
     *
     * <p>Uses {@link ResolverStyle#STRICT} so invalid calendar dates are
     * rejected at parse time. The 6-digit microsecond field is parsed
     * into {@link LocalDateTime#getNano() nano-of-second} as
     * {@code microseconds * 1000}.
     *
     * <p>Example: {@code parseTimestamp("2024-03-15 13:45:23.123456")}
     * returns {@code LocalDateTime.of(2024, 3, 15, 13, 45, 23, 123_456_000)}.
     *
     * @param ts 26-character timestamp string; must not be {@code null}
     * @return the parsed {@link LocalDateTime}
     * @throws NullPointerException     if {@code ts} is {@code null}
     * @throws IllegalArgumentException if {@code ts} is not 26 characters
     * @throws java.time.format.DateTimeParseException if the string cannot
     *         be parsed as a strict {@code YYYY-MM-DD HH:MM:SS.SSSSSS}
     *         timestamp
     */
    public static LocalDateTime parseTimestamp(String ts) {
        Objects.requireNonNull(ts, "ts");
        if (ts.length() != WIDTH_TIMESTAMP_26) {
            throw new IllegalArgumentException(
                    "WS-TIMESTAMP must be " + WIDTH_TIMESTAMP_26
                            + " characters, got " + ts.length());
        }
        return LocalDateTime.parse(ts, TIMESTAMP);
    }
}
