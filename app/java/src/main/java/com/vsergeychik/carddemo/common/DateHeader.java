package com.vsergeychik.carddemo.common;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The COBOL copybook {@code app/cpy/CSDAT01Y.cpy} group item {@code 01 WS-DATE-TIME}: the date and time
 * header every CICS screen in this system is stamped with, rendered from an instant the caller supplies
 * rather than from the wall clock.
 *
 * <p>{@code app/cbl/CBACT04C.cbl:141-149} declares which sums to exactly 21 bytes and is filled by
 * {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS} at {@code app/cbl/CBACT04C.cbl:614}.
 */
public final class DateHeader {
    /**
     * The total width of {@code 01 WS-DATE-TIME} in bytes: {@code 16 + 8 + 8 + 26}.
     */
    public static final int WS_DATE_TIME_LENGTH = 58;

    /**
     * {@code WS-CURDATE-DATA} - 16 bytes, the {@code YYYYMMDDHHMMSSss} span that
     * {@code MOVE FUNCTION CURRENT-DATE} populates.
     */
    public static final int WS_CURDATE_DATA_LENGTH = 16;

    /**
     * {@code WS-CURDATE} - 8 bytes, {@code YYYYMMDD}: 4 + 2 + 2.
     */
    public static final int WS_CURDATE_LENGTH = 8;

    /**
     * {@code WS-CURTIME} - 8 bytes, {@code HHMMSSss}: 2 + 2 + 2 + 2.
     */
    public static final int WS_CURTIME_LENGTH = 8;

    /**
     * {@code WS-CURDATE-MM-DD-YY} - 8 bytes, {@code MM/DD/YY}: 2 + 1 + 2 + 1 + 2.
     */
    public static final int WS_CURDATE_MM_DD_YY_LENGTH = 8;

    /**
     * {@code WS-CURTIME-HH-MM-SS} - 8 bytes, {@code HH:MM:SS}: 2 + 1 + 2 + 1 + 2.
     */
    public static final int WS_CURTIME_HH_MM_SS_LENGTH = 8;

    /**
     * {@code WS-TIMESTAMP} - 26 bytes, {@code YYYY-MM-DD HH:MM:SS.ssssss}:
     * {@code 4 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 6}.
     */
    public static final int WS_TIMESTAMP_LENGTH = 26;

    /**
     * The width of the {@code FUNCTION CURRENT-DATE} intrinsic result: 21 characters, {@code YYYYMMDD} +
     * {@code HHMMSSss} + a five-character GMT offset.
     */
    public static final int FUNCTION_CURRENT_DATE_LENGTH = 21;

    /**
     * The width of the GMT offset that {@code FUNCTION CURRENT-DATE} appends and that
     * {@code WS-CURDATE-DATA} discards: 5 characters in {@code shhmm} form.
     */
    public static final int GMT_OFFSET_LENGTH = 5;

    /**
     * {@code PIC 9(04)} - {@code WS-CURDATE-YEAR} and {@code WS-TIMESTAMP-DT-YYYY}.
     */
    public static final int YEAR_DIGITS = 4;

    /**
     * {@code PIC 9(02)} - {@code WS-CURDATE-MONTH}, {@code WS-CURDATE-MM}, {@code -DT-MM}.
     */
    public static final int MONTH_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURDATE-DAY}, {@code WS-CURDATE-DD}, {@code -DT-DD}.
     */
    public static final int DAY_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURDATE-YY}, the two-digit year.
     */
    public static final int TWO_DIGIT_YEAR_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURTIME-HOURS}, {@code WS-CURTIME-HH}, {@code -TM-HH}.
     */
    public static final int HOURS_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURTIME-MINUTE}, {@code WS-CURTIME-MM}, {@code -TM-MM}.
     */
    public static final int MINUTE_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURTIME-SECOND}, {@code WS-CURTIME-SS}, {@code -TM-SS}.
     */
    public static final int SECOND_DIGITS = 2;

    /**
     * {@code PIC 9(02)} - {@code WS-CURTIME-MILSEC}.
     */
    public static final int MILSEC_DIGITS = 2;

    /**
     * {@code PIC 9(06)} - {@code WS-TIMESTAMP-TM-MS6}.
     */
    public static final int MICROSECOND_DIGITS = 6;

    /**
     * {@code PIC 9(08)} - the width of both {@code REDEFINES} views, {@code WS-CURDATE-N} over
     * {@code WS-CURDATE} and {@code WS-CURTIME-N} over {@code WS-CURTIME}.
     */
    public static final int REDEFINED_VIEW_DIGITS = 8;

    /**
     * {@code PIC X(01)} - the width of every separator {@code FILLER} in this copybook.
     */
    public static final int SEPARATOR_LENGTH = 1;

    /**
     * The width of the date portion of {@code WS-TIMESTAMP}: ten characters, {@code YYYY-MM-DD}.
     */
    public static final int WS_TIMESTAMP_DATE_WIDTH =
            YEAR_DIGITS + SEPARATOR_LENGTH + MONTH_DIGITS + SEPARATOR_LENGTH + DAY_DIGITS;

    /**
     * The width of the time portion of {@code WS-TIMESTAMP}: eight characters, {@code HH:MM:SS}.
     */
    public static final int WS_TIMESTAMP_TIME_WIDTH =
            HOURS_DIGITS + SEPARATOR_LENGTH + MINUTE_DIGITS + SEPARATOR_LENGTH + SECOND_DIGITS;

    /**
     * Absolute 0-based offset of {@code WS-CURDATE-DATA}, and of {@code WS-CURDATE} within it.
     */
    public static final int WS_CURDATE_DATA_OFFSET = 0;

    /**
     * Absolute 0-based offset of {@code WS-CURTIME}, the second half of {@code WS-CURDATE-DATA}.
     */
    public static final int WS_CURTIME_OFFSET = WS_CURDATE_DATA_OFFSET + WS_CURDATE_LENGTH;

    /**
     * Absolute 0-based offset of {@code WS-CURDATE-MM-DD-YY}: byte 16.
     */
    public static final int WS_CURDATE_MM_DD_YY_OFFSET =
            WS_CURDATE_DATA_OFFSET + WS_CURDATE_DATA_LENGTH;

    /**
     * Absolute 0-based offset of {@code WS-CURTIME-HH-MM-SS}: byte 24.
     */
    public static final int WS_CURTIME_HH_MM_SS_OFFSET =
            WS_CURDATE_MM_DD_YY_OFFSET + WS_CURDATE_MM_DD_YY_LENGTH;

    /**
     * Absolute 0-based offset of {@code WS-TIMESTAMP}: byte 32.
     */
    public static final int WS_TIMESTAMP_OFFSET =
            WS_CURTIME_HH_MM_SS_OFFSET + WS_CURTIME_HH_MM_SS_LENGTH;

    /**
     * {@code FILLER PIC X(01) VALUE '/'} - {@code app/cpy/CSDAT01Y.cpy:32} and
     * {@code app/cpy/CSDAT01Y.cpy:34}, the two separators of {@code WS-CURDATE-MM-DD-YY}.
     */
    public static final char DATE_SEPARATOR = '/';

    /**
     * {@code FILLER PIC X(01) VALUE ':'} - {@code app/cpy/CSDAT01Y.cpy:38} and
     * {@code app/cpy/CSDAT01Y.cpy:40} in {@code WS-CURTIME-HH-MM-SS}, and {@code app/cpy/CSDAT01Y.cpy:50}
     * and {@code app/cpy/CSDAT01Y.cpy:52} in {@code WS-TIMESTAMP}.
     */
    public static final char TIME_SEPARATOR = ':';

    /**
     * {@code FILLER PIC X(01) VALUE '-'} - {@code app/cpy/CSDAT01Y.cpy:44} and
     * {@code app/cpy/CSDAT01Y.cpy:46}, the date separators inside {@code WS-TIMESTAMP}.
     */
    public static final char TIMESTAMP_DATE_SEPARATOR = '-';

    /**
     * {@code FILLER PIC X(01) VALUE ' '} - {@code app/cpy/CSDAT01Y.cpy:48}.
     */
    public static final char TIMESTAMP_DATE_TIME_SEPARATOR = ' ';

    /**
     * {@code FILLER PIC X(01) VALUE '.'} - {@code app/cpy/CSDAT01Y.cpy:54}, ahead of the six-digit
     * microsecond field.
     */
    public static final char TIMESTAMP_FRACTION_SEPARATOR = '.';

    private static final char DB2_DATE_TIME_SEPARATOR = '-';

    private static final char DB2_TIME_SEPARATOR = '.';

    private static final String DB2_FRACTION_REMAINDER = "0000";

    private static final char GMT_OFFSET_POSITIVE_SIGN = '+';

    private static final char GMT_OFFSET_NEGATIVE_SIGN = '-';

    private static final int SECONDS_PER_MINUTE = 60;

    private static final int MINUTES_PER_HOUR = 60;

    private static final int NANOS_PER_MICROSECOND = 1_000;

    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    private static final int MICROSECONDS_PER_HUNDREDTH = NANOS_PER_HUNDREDTH / NANOS_PER_MICROSECOND;

    private static final int NO_GMT_OFFSET = 0;

    private static final int MAX_GMT_OFFSET_MINUTES = 23 * MINUTES_PER_HOUR + 59;

    /**
     * {@code 05 WS-CURDATE-DATA} - the 16-byte group, addressable as a whole.
     */
    public static final String WS_CURDATE_DATA = "WS-CURDATE-DATA";

    /**
     * {@code 10 WS-CURDATE} - the 8-byte {@code YYYYMMDD} group.
     */
    public static final String WS_CURDATE = "WS-CURDATE";

    /**
     * {@code 15 WS-CURDATE-YEAR PIC 9(04)}.
     */
    public static final String WS_CURDATE_YEAR = "WS-CURDATE-YEAR";

    /**
     * {@code 15 WS-CURDATE-MONTH PIC 9(02)}.
     */
    public static final String WS_CURDATE_MONTH = "WS-CURDATE-MONTH";

    /**
     * {@code 15 WS-CURDATE-DAY PIC 9(02)}.
     */
    public static final String WS_CURDATE_DAY = "WS-CURDATE-DAY";

    /**
     * {@code 10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)} - a view, not storage of its own.
     */
    public static final String WS_CURDATE_N = "WS-CURDATE-N";

    /**
     * {@code 10 WS-CURTIME} - the 8-byte {@code HHMMSSss} group.
     */
    public static final String WS_CURTIME = "WS-CURTIME";

    /**
     * {@code 15 WS-CURTIME-HOURS PIC 9(02)}.
     */
    public static final String WS_CURTIME_HOURS = "WS-CURTIME-HOURS";

    /**
     * {@code 15 WS-CURTIME-MINUTE PIC 9(02)} - singular in the copybook, preserved as such.
     */
    public static final String WS_CURTIME_MINUTE = "WS-CURTIME-MINUTE";

    /**
     * {@code 15 WS-CURTIME-SECOND PIC 9(02)} - singular in the copybook, preserved as such.
     */
    public static final String WS_CURTIME_SECOND = "WS-CURTIME-SECOND";

    /**
     * {@code 15 WS-CURTIME-MILSEC PIC 9(02)} - hundredths of a second despite the name.
     */
    public static final String WS_CURTIME_MILSEC = "WS-CURTIME-MILSEC";

    /**
     * {@code 10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08)} - a view, not storage of its own.
     */
    public static final String WS_CURTIME_N = "WS-CURTIME-N";

    /**
     * {@code 05 WS-CURDATE-MM-DD-YY} - the 8-byte {@code MM/DD/YY} group.
     */
    public static final String WS_CURDATE_MM_DD_YY = "WS-CURDATE-MM-DD-YY";

    /**
     * {@code 10 WS-CURDATE-MM PIC 9(02)}.
     */
    public static final String WS_CURDATE_MM = "WS-CURDATE-MM";

    /**
     * {@code 10 WS-CURDATE-DD PIC 9(02)}.
     */
    public static final String WS_CURDATE_DD = "WS-CURDATE-DD";

    /**
     * {@code 10 WS-CURDATE-YY PIC 9(02)} - the two-digit year.
     */
    public static final String WS_CURDATE_YY = "WS-CURDATE-YY";

    /**
     * {@code 05 WS-CURTIME-HH-MM-SS} - the 8-byte {@code HH:MM:SS} group.
     */
    public static final String WS_CURTIME_HH_MM_SS = "WS-CURTIME-HH-MM-SS";

    /**
     * {@code 10 WS-CURTIME-HH PIC 9(02)}.
     */
    public static final String WS_CURTIME_HH = "WS-CURTIME-HH";

    /**
     * {@code 10 WS-CURTIME-MM PIC 9(02)} - minutes here, distinct from {@code WS-CURDATE-MM}.
     */
    public static final String WS_CURTIME_MM = "WS-CURTIME-MM";

    /**
     * {@code 10 WS-CURTIME-SS PIC 9(02)}.
     */
    public static final String WS_CURTIME_SS = "WS-CURTIME-SS";

    /**
     * {@code 05 WS-TIMESTAMP} - the 26-byte group, moved whole to and from {@code TRAN-ORIG-TS}.
     */
    public static final String WS_TIMESTAMP = "WS-TIMESTAMP";

    /**
     * {@code 10 WS-TIMESTAMP-DT-YYYY PIC 9(04)}.
     */
    public static final String WS_TIMESTAMP_DT_YYYY = "WS-TIMESTAMP-DT-YYYY";

    /**
     * {@code 10 WS-TIMESTAMP-DT-MM PIC 9(02)}.
     */
    public static final String WS_TIMESTAMP_DT_MM = "WS-TIMESTAMP-DT-MM";

    /**
     * {@code 10 WS-TIMESTAMP-DT-DD PIC 9(02)}.
     */
    public static final String WS_TIMESTAMP_DT_DD = "WS-TIMESTAMP-DT-DD";

    /**
     * {@code 10 WS-TIMESTAMP-TM-HH PIC 9(02)}.
     */
    public static final String WS_TIMESTAMP_TM_HH = "WS-TIMESTAMP-TM-HH";

    /**
     * {@code 10 WS-TIMESTAMP-TM-MM PIC 9(02)}.
     */
    public static final String WS_TIMESTAMP_TM_MM = "WS-TIMESTAMP-TM-MM";

    /**
     * {@code 10 WS-TIMESTAMP-TM-SS PIC 9(02)}.
     */
    public static final String WS_TIMESTAMP_TM_SS = "WS-TIMESTAMP-TM-SS";

    /**
     * {@code 10 WS-TIMESTAMP-TM-MS6 PIC 9(06)} - six digits, microseconds.
     */
    public static final String WS_TIMESTAMP_TM_MS6 = "WS-TIMESTAMP-TM-MS6";

    /**
     * The 58-byte layout of {@code 01 WS-DATE-TIME}, transcribed span by span from
     * {@code app/cpy/CSDAT01Y.cpy}.
     */
    public static final RecordLayout WS_DATE_TIME_LAYOUT = RecordLayout.of(WS_DATE_TIME_LENGTH,
            FieldSpan.unsignedNumeric(WS_CURDATE_YEAR, 0, YEAR_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURDATE_MONTH, 4, MONTH_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURDATE_DAY, 6, DAY_DIGITS),
            FieldSpan.redefining(WS_CURDATE, WS_CURDATE_DATA_OFFSET, WS_CURDATE_LENGTH,
                    PictureKind.ALPHANUMERIC),
            FieldSpan.redefining(WS_CURDATE_N, WS_CURDATE_DATA_OFFSET, REDEFINED_VIEW_DIGITS,
                    PictureKind.UNSIGNED_NUMERIC),
            FieldSpan.unsignedNumeric(WS_CURTIME_HOURS, 8, HOURS_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURTIME_MINUTE, 10, MINUTE_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURTIME_SECOND, 12, SECOND_DIGITS),
            FieldSpan.unsignedNumeric(WS_CURTIME_MILSEC, 14, MILSEC_DIGITS),
            FieldSpan.redefining(WS_CURTIME, WS_CURTIME_OFFSET, WS_CURTIME_LENGTH,
                    PictureKind.ALPHANUMERIC),
            FieldSpan.redefining(WS_CURTIME_N, WS_CURTIME_OFFSET, REDEFINED_VIEW_DIGITS,
                    PictureKind.UNSIGNED_NUMERIC),
            FieldSpan.redefining(WS_CURDATE_DATA, WS_CURDATE_DATA_OFFSET, WS_CURDATE_DATA_LENGTH,
                    PictureKind.ALPHANUMERIC),
            FieldSpan.unsignedNumeric(WS_CURDATE_MM, 16, MONTH_DIGITS),
            FieldSpan.filler(18, SEPARATOR_LENGTH, String.valueOf(DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURDATE_DD, 19, DAY_DIGITS),
            FieldSpan.filler(21, SEPARATOR_LENGTH, String.valueOf(DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURDATE_YY, 22, TWO_DIGIT_YEAR_DIGITS),
            FieldSpan.redefining(WS_CURDATE_MM_DD_YY, WS_CURDATE_MM_DD_YY_OFFSET,
                    WS_CURDATE_MM_DD_YY_LENGTH, PictureKind.ALPHANUMERIC),
            FieldSpan.unsignedNumeric(WS_CURTIME_HH, 24, HOURS_DIGITS),
            FieldSpan.filler(26, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURTIME_MM, 27, MINUTE_DIGITS),
            FieldSpan.filler(29, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_CURTIME_SS, 30, SECOND_DIGITS),
            FieldSpan.redefining(WS_CURTIME_HH_MM_SS, WS_CURTIME_HH_MM_SS_OFFSET,
                    WS_CURTIME_HH_MM_SS_LENGTH, PictureKind.ALPHANUMERIC),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_DT_YYYY, 32, YEAR_DIGITS),
            FieldSpan.filler(36, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_DT_MM, 37, MONTH_DIGITS),
            FieldSpan.filler(39, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_DATE_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_DT_DD, 40, DAY_DIGITS),
            FieldSpan.filler(42, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_DATE_TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_HH, 43, HOURS_DIGITS),
            FieldSpan.filler(45, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_MM, 46, MINUTE_DIGITS),
            FieldSpan.filler(48, SEPARATOR_LENGTH, String.valueOf(TIME_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_SS, 49, SECOND_DIGITS),
            FieldSpan.filler(51, SEPARATOR_LENGTH, String.valueOf(TIMESTAMP_FRACTION_SEPARATOR)),
            FieldSpan.unsignedNumeric(WS_TIMESTAMP_TM_MS6, 52, MICROSECOND_DIGITS),
            FieldSpan.redefining(WS_TIMESTAMP, WS_TIMESTAMP_OFFSET, WS_TIMESTAMP_LENGTH,
                    PictureKind.ALPHANUMERIC));

    /**
     * One instant, decomposed into exactly the components {@code app/cpy/CSDAT01Y.cpy} declares.
     *
     * @param year {@code WS-CURDATE-YEAR}, 0 to 9999
     * @param month {@code WS-CURDATE-MONTH}, 0 to 99; not calendar-checked
     * @param day {@code WS-CURDATE-DAY}, 0 to 99; not calendar-checked
     * @param hours {@code WS-CURTIME-HOURS}, 0 to 99
     * @param minutes {@code WS-CURTIME-MINUTE}, 0 to 99
     * @param seconds {@code WS-CURTIME-SECOND}, 0 to 99
     * @param hundredths {@code WS-CURTIME-MILSEC}, 0 to 99 - hundredths of a second, not milliseconds
     * @param microseconds {@code WS-TIMESTAMP-TM-MS6}, 0 to 999999
     * @param gmtOffsetMinutes the offset from Greenwich in whole minutes, which
     *     {@code FUNCTION CURRENT-DATE} appends as {@code shhmm} and {@code WS-CURDATE-DATA} then discards
     */
    public record CapturedDateTime(int year,
                                   int month,
                                   int day,
                                   int hours,
                                   int minutes,
                                   int seconds,
                                   int hundredths,
                                   int microseconds,
                                   int gmtOffsetMinutes) {
        /**
         * Validates every component against the width its {@code PICTURE} declares, so an out-of-range
         * value fails where it is supplied rather than as a silently truncated digit in a rendered header.
         */
        public CapturedDateTime {
            requireDigitWidth(year, YEAR_DIGITS, WS_CURDATE_YEAR);
            requireDigitWidth(month, MONTH_DIGITS, WS_CURDATE_MONTH);
            requireDigitWidth(day, DAY_DIGITS, WS_CURDATE_DAY);
            requireDigitWidth(hours, HOURS_DIGITS, WS_CURTIME_HOURS);
            requireDigitWidth(minutes, MINUTE_DIGITS, WS_CURTIME_MINUTE);
            requireDigitWidth(seconds, SECOND_DIGITS, WS_CURTIME_SECOND);
            requireDigitWidth(hundredths, MILSEC_DIGITS, WS_CURTIME_MILSEC);
            requireDigitWidth(microseconds, MICROSECOND_DIGITS, WS_TIMESTAMP_TM_MS6);
            if (gmtOffsetMinutes < -MAX_GMT_OFFSET_MINUTES
                    || gmtOffsetMinutes > MAX_GMT_OFFSET_MINUTES) {
                throw new IllegalArgumentException("GMT offset of " + gmtOffsetMinutes
                        + " minute(s) cannot be rendered as the " + GMT_OFFSET_LENGTH
                        + "-character shhmm tail that FUNCTION CURRENT-DATE appends; the hour part "
                        + "would need more than two digits");
            }
        }

        /**
         * Decomposes one local date and time, together with the offset that produced it, into the
         * copybook's components.
         *
         * <p>The nanosecond-of-second is reduced twice, at the two precisions the copybook declares, and
         * truncated toward zero in both cases because COBOL truncates on store and {@code ROUNDED} appears
         * nowhere in this codebase.
         *
         * @param dateTime the local date and time; the sole source of every component
         * @param gmtOffsetMinutes the offset from Greenwich in whole minutes
         * @return the captured components
         * @throws NullPointerException if {@code dateTime} is {@code null}
         * @throws IllegalArgumentException if any resulting component falls outside its declared digit
         *     count
         */
        public static CapturedDateTime of(LocalDateTime dateTime, int gmtOffsetMinutes) {
            Objects.requireNonNull(dateTime, "A local date and time is required to build a date "
                    + "header; this type never reads a clock of its own, so the instant must be "
                    + "supplied by the caller");
            int nanoOfSecond = dateTime.getNano();
            return new CapturedDateTime(dateTime.getYear(),
                    dateTime.getMonthValue(),
                    dateTime.getDayOfMonth(),
                    dateTime.getHour(),
                    dateTime.getMinute(),
                    dateTime.getSecond(),
                    nanoOfSecond / NANOS_PER_HUNDREDTH,
                    nanoOfSecond / NANOS_PER_MICROSECOND,
                    gmtOffsetMinutes);
        }

        // Rejects a component that its PICTURE cannot hold.
    }

    private static void requireDigitWidth(int value, int digits, String fieldName) {
        int exclusiveLimit = 1;
        for (int decade = 0; decade < digits; decade++) {
            exclusiveLimit *= 10;
        }
        if (value < 0 || value >= exclusiveLimit) {
            throw new IllegalArgumentException("Value " + value + " does not fit " + fieldName
                    + " PIC 9(0" + digits + "), which holds 0 to " + (exclusiveLimit - 1)
                    + "; a wider value would be truncated on the left and the loss would be "
                    + "invisible in the rendered header");
        }
    }

    private final FixedWidthCodec codec;

    private final CapturedDateTime captured;

    private final EditedDate curdateMmDdYy;

    private final EditedTime curtimeHhMmSs;

    private final TimestampGroup timestamp;

    private DateHeader(FixedWidthCodec codec,
                       CapturedDateTime captured,
                       EditedDate curdateMmDdYy,
                       EditedTime curtimeHhMmSs,
                       TimestampGroup timestamp) {
        this.codec = codec;
        this.captured = captured;
        this.curdateMmDdYy = curdateMmDdYy;
        this.curtimeHhMmSs = curtimeHhMmSs;
        this.timestamp = timestamp;
    }

    /**
     * Builds the state a program is in immediately after {@code POPULATE-HEADER-INFO}: the captured instant
     * in {@code WS-CURDATE-DATA}, and the three other groups filled from its components, which is exactly
     * the sequence {@code app/cbl/COMEN01C.cbl:214-231} and {@code app/cbl/COSGN00C.cbl:179-196} perform.
     *
     * @param codec the shared fixed-width codec that performs the COBOL move
     * @param captured the captured date and time the header is rendered from
     */
    private static DateHeader afterPopulateHeaderInfo(FixedWidthCodec codec,
                                                      CapturedDateTime captured) {
        return new DateHeader(codec, captured,
                // MOVE WS-CURDATE-MONTH TO WS-CURDATE-MM, and the same for DD; WS-CURDATE-YEAR(3:2)
                // supplies YY - a reference-modified two characters, not a numeric truncation.
                new EditedDate(captured.month(), captured.day(),
                        twoDigitYearOf(captured.year())),
                new EditedTime(captured.hours(), captured.minutes(), captured.seconds()),
                new TimestampGroup(captured.year(), captured.month(), captured.day(),
                        captured.hours(), captured.minutes(), captured.seconds(),
                        captured.microseconds()));
    }

    private static int twoDigitYearOf(int year) {
        return year % 100;
    }

    /**
     * The {@code WS-CURDATE-MM-DD-YY} group: three {@code PIC 9(02)} items with {@code '/'} separators
     * declared as {@code FILLER … VALUE} literals between them.
     *
     * <p>The COBOL performs no calendar test on this group - it is a display edit of whatever was moved in
     * - so a month of {@code 00} is legitimate here rather than corrupt, exactly as it is in
     * {@link CapturedDateTime}.
     *
     * @param mm {@code WS-CURDATE-MM}, 0 to 99
     * @param dd {@code WS-CURDATE-DD}, 0 to 99
     * @param yy {@code WS-CURDATE-YY}, 0 to 99
     */
    private record EditedDate(int mm, int dd, int yy) {
        private EditedDate {
            requireDigitWidth(mm, MONTH_DIGITS, WS_CURDATE_MM);
            requireDigitWidth(dd, DAY_DIGITS, WS_CURDATE_DD);
            requireDigitWidth(yy, TWO_DIGIT_YEAR_DIGITS, WS_CURDATE_YY);
        }
    }

    /**
     * The {@code WS-CURTIME-HH-MM-SS} group: three {@code PIC 9(02)} items with {@code ':'} separators
     * declared as {@code FILLER … VALUE} literals between them.
     *
     * @param hh {@code WS-CURTIME-HH}, 0 to 99
     * @param mm {@code WS-CURTIME-MM}, 0 to 99
     * @param ss {@code WS-CURTIME-SS}, 0 to 99
     */
    private record EditedTime(int hh, int mm, int ss) {
        private EditedTime {
            requireDigitWidth(hh, HOURS_DIGITS, WS_CURTIME_HH);
            requireDigitWidth(mm, MINUTE_DIGITS, WS_CURTIME_MM);
            requireDigitWidth(ss, SECOND_DIGITS, WS_CURTIME_SS);
        }
    }

    /**
     * The {@code WS-TIMESTAMP} group: seven numeric items and five separator {@code FILLER}s, twenty-six
     * bytes in all.
     *
     * @param year {@code WS-TIMESTAMP-DT-YYYY}, 0 to 9999
     * @param month {@code WS-TIMESTAMP-DT-MM}, 0 to 99; not calendar-checked
     * @param day {@code WS-TIMESTAMP-DT-DD}, 0 to 99; not calendar-checked
     * @param hours {@code WS-TIMESTAMP-TM-HH}, 0 to 99
     * @param minutes {@code WS-TIMESTAMP-TM-MM}, 0 to 99
     * @param seconds {@code WS-TIMESTAMP-TM-SS}, 0 to 99
     * @param microseconds {@code WS-TIMESTAMP-TM-MS6}, 0 to 999999
     */
    private record TimestampGroup(int year,
                                  int month,
                                  int day,
                                  int hours,
                                  int minutes,
                                  int seconds,
                                  int microseconds) {
        private TimestampGroup {
            requireDigitWidth(year, YEAR_DIGITS, WS_TIMESTAMP_DT_YYYY);
            requireDigitWidth(month, MONTH_DIGITS, WS_TIMESTAMP_DT_MM);
            requireDigitWidth(day, DAY_DIGITS, WS_TIMESTAMP_DT_DD);
            requireDigitWidth(hours, HOURS_DIGITS, WS_TIMESTAMP_TM_HH);
            requireDigitWidth(minutes, MINUTE_DIGITS, WS_TIMESTAMP_TM_MM);
            requireDigitWidth(seconds, SECOND_DIGITS, WS_TIMESTAMP_TM_SS);
            requireDigitWidth(microseconds, MICROSECOND_DIGITS, WS_TIMESTAMP_TM_MS6);
        }
    }

    /**
     * Captures the instant a {@link Clock} reports, reading it once.
     *
     * @param codec the fixed-width codec supplying zero-fill and the code page
     * @param clock the clock to read; read exactly once, so the renderings cannot straddle a second
     *     boundary
     * @return the header, capturing the clock's current instant
     * @throws NullPointerException if {@code codec} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if the clock reports an instant outside the range a
     *     {@code PIC 9(04)} year can hold
     */
    public static DateHeader from(FixedWidthCodec codec, Clock clock) {
        requireCodec(codec);
        Objects.requireNonNull(clock, "A Clock is required: this type never calls now() of its own, "
                + "because a header that read the wall clock could not be compared byte for byte "
                + "against an expected parity image");
        Instant instant = clock.instant();
        ZoneId zone = clock.getZone();
        ZoneOffset offset = zone.getRules().getOffset(instant);
        return afterPopulateHeaderInfo(codec, CapturedDateTime.of(LocalDateTime.ofInstant(instant, zone),
                offset.getTotalSeconds() / SECONDS_PER_MINUTE));
    }

    /**
     * Captures an explicit local date and time, treating it as having no offset from Greenwich.
     *
     * @param codec the fixed-width codec supplying zero-fill and the code page
     * @param dateTime the local date and time to render
     * @return the header, capturing {@code dateTime}
     * @throws NullPointerException if {@code codec} or {@code dateTime} is {@code null}
     * @throws IllegalArgumentException if any component falls outside its declared digit count
     */
    public static DateHeader of(FixedWidthCodec codec, LocalDateTime dateTime) {
        return of(codec, dateTime, ZoneOffset.UTC);
    }

    /**
     * Captures an explicit local date and time together with an explicit offset from Greenwich.
     *
     * @param codec the fixed-width codec supplying zero-fill and the code page
     * @param dateTime the local date and time to render
     * @param offset the offset {@code FUNCTION CURRENT-DATE} would report alongside it; any residual
     *     seconds are dropped, since {@code shhmm} cannot carry them
     * @return the header, capturing {@code dateTime} at {@code offset}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if any component falls outside its declared digit count, or the
     *     offset exceeds plus or minus 23:59
     */
    public static DateHeader of(FixedWidthCodec codec, LocalDateTime dateTime, ZoneOffset offset) {
        requireCodec(codec);
        Objects.requireNonNull(offset, "A ZoneOffset is required; pass ZoneOffset.UTC for the "
                + "+0000 tail, and note that the offset is discarded by the 16-byte WS-CURDATE-DATA "
                + "receiver in any case");
        return afterPopulateHeaderInfo(codec,
                CapturedDateTime.of(dateTime, offset.getTotalSeconds() / SECONDS_PER_MINUTE));
    }

    /**
     * Rebuilds a header from an already-rendered 26-character {@code WS-TIMESTAMP} image, modelling
     * {@code MOVE TRAN-ORIG-TS TO WS-TIMESTAMP} at {@code app/cbl/COTRN00C.cbl:384}.
     *
     * @param codec the fixed-width codec supplying zero-fill and the code page
     * @param image exactly {@value #WS_TIMESTAMP_LENGTH} characters in {@code YYYY-MM-DD HH:MM:SS.ssssss}
     *     form
     * @return the header the image denotes, carrying a {@code +0000} offset because an image records none
     * @throws NullPointerException if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly 26 characters, does not carry the
     *     declared separators at the declared positions, or holds a non-digit where a digit belongs
     */
    public static DateHeader ofTimestampImage(FixedWidthCodec codec, String image) {
        requireCodec(codec);
        Objects.requireNonNull(image, "A 26-character WS-TIMESTAMP image is required; this factory "
                + "models MOVE TRAN-ORIG-TS TO WS-TIMESTAMP, so its input is the stored image");
        if (image.length() != WS_TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("WS-TIMESTAMP is PIC X(" + WS_TIMESTAMP_LENGTH
                    + ") but the supplied image is " + image.length() + " character(s); a stored "
                    + "TRAN-ORIG-TS moved into this group is always exactly its declared width");
        }

        FixedWidthRecord area = codec.newRecord(WS_DATE_TIME_LAYOUT);
        codec.writePicX(area, WS_DATE_TIME_LAYOUT.span(WS_TIMESTAMP), image);

        int year = readTimestampField(codec, area, WS_TIMESTAMP_DT_YYYY);
        int month = readTimestampField(codec, area, WS_TIMESTAMP_DT_MM);
        int day = readTimestampField(codec, area, WS_TIMESTAMP_DT_DD);
        int hours = readTimestampField(codec, area, WS_TIMESTAMP_TM_HH);
        int minutes = readTimestampField(codec, area, WS_TIMESTAMP_TM_MM);
        int seconds = readTimestampField(codec, area, WS_TIMESTAMP_TM_SS);
        int microseconds = readTimestampField(codec, area, WS_TIMESTAMP_TM_MS6);

        DateHeader header = afterPopulateHeaderInfo(codec, new CapturedDateTime(year, month, day,
                hours, minutes, seconds,
                microseconds / MICROSECONDS_PER_HUNDREDTH,
                microseconds,
                NO_GMT_OFFSET));

        // This is what verifies the separators, and it verifies them all at once: an image whose declared
        // FILLER positions hold anything other than the copybook's '-', ' ', ':' and '.' cannot reproduce
        // itself, and is therefore not a WS-TIMESTAMP image.
        String reRendered = header.wsTimestamp();
        if (!reRendered.equals(image)) {
            throw new IllegalArgumentException("Image '" + image + "' does not round-trip as a "
                    + "WS-TIMESTAMP: re-rendering its fields through the declared layout yields '"
                    + reRendered + "'. WS-TIMESTAMP declares its separators as FILLER ... VALUE "
                    + "literals - '" + TIMESTAMP_DATE_SEPARATOR + "', '"
                    + TIMESTAMP_DATE_TIME_SEPARATOR + "', '" + TIME_SEPARATOR + "' and '"
                    + TIMESTAMP_FRACTION_SEPARATOR + "' - so an image in another convention, such "
                    + "as CBACT04C's DB2-FORMAT-TS form, is a different layout rather than a "
                    + "variant of this one");
        }
        return header;
    }

    private static void requireCodec(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: it owns this module's only "
                + "zero-fill implementation and the code page the 58-byte image is encoded in, "
                + "neither of which this class re-implements or assumes");
    }

    private static int readTimestampField(FixedWidthCodec codec,
                                          FixedWidthRecord area,
                                          String fieldName) {
        FieldSpan span = WS_DATE_TIME_LAYOUT.span(fieldName);
        try {
            return codec.readPic9AsInt(area, span);
        } catch (IllegalArgumentException notDigits) {
            throw new IllegalArgumentException("WS-TIMESTAMP sub-field " + fieldName
                    + " is declared PIC 9(0" + span.length() + ") at byte "
                    + (span.offset() - WS_TIMESTAMP_OFFSET + 1) + " of the 26-character image, but "
                    + "the image holds '" + area.readSpan(span) + "' there", notDigits);
        }
    }

    /**
     * {@code MOVE TRAN-ORIG-TS TO WS-TIMESTAMP} - {@code app/cbl/COTRN00C.cbl:384}.
     *
     * <p>The image is validated exactly as that factory validates it - width, digits and the declared
     * separator positions, proved by re-rendering - so an image in another convention, such as
     * {@code CBACT04C}'s {@code DB2-FORMAT-TS} form, is rejected rather than silently reinterpreted.
     *
     * @param image exactly {@value #WS_TIMESTAMP_LENGTH} characters in {@code YYYY-MM-DD HH:MM:SS.ssssss}
     *     form
     * @return a new header with this group replaced; this instance is unchanged
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not a well-formed {@code WS-TIMESTAMP} image
     */
    public DateHeader withTimestampImage(String image) {
        DateHeader fromImage = ofTimestampImage(codec, image);
        return new DateHeader(codec, captured, curdateMmDdYy, curtimeHhMmSs, fromImage.timestamp);
    }

    /**
     * The three moves at {@code app/cbl/COTRN00C.cbl:385-387}, which refill {@code WS-CURDATE-MM-DD-YY}
     * from {@code WS-TIMESTAMP} rather than from {@code WS-CURDATE}: The same eight bytes
     * {@code POPULATE-HEADER-INFO} filled from the current date are overwritten here with the transaction's
     * date, and {@code WS-CURDATE-DATA} is not touched.
     *
     * @return a new header with {@code WS-CURDATE-MM-DD-YY} taken from {@code WS-TIMESTAMP}; this instance
     *     is unchanged
     */
    public DateHeader withCurdateMmDdYyFromTimestamp() {
        return new DateHeader(codec, captured,
                new EditedDate(timestamp.month(), timestamp.day(),
                        twoDigitYearOf(timestamp.year())),
                curtimeHhMmSs, timestamp);
    }

    /**
     * {@code app/cbl/COBIL00C.cbl:263-266} - the {@code EXEC CICS FORMATTIME} form, whose fractional part
     * is {@code ZEROS} by explicit statement: {@code COBIL00C} obtains the date and time from
     * {@code EXEC CICS ASKTIME} and {@code FORMATTIME} with {@code DATESEP('-')} and {@code TIMESEP(':')} -
     * a service that reports no sub-second component at all - and then zeroes the six microsecond positions
     * outright.
     *
     * <p>Deriving those six digits from a captured instant would put real microseconds into a record the
     * COBOL fills with zeros, and the difference would show up field-for-field in a parity comparison of
     * {@code TRAN-ORIG-TS}.
     *
     * @param date10 the ten characters {@code FORMATTIME}'s {@code DATE} produced, in {@code YYYY-MM-DD}
     *     form with {@code DATESEP('-')}
     * @param time08 the eight characters its {@code TIME} produced, in {@code HH:MM:SS} form with
     *     {@code TIMESEP(':')}
     * @return a new header whose {@code WS-TIMESTAMP} carries that date and time with a zero fractional
     *     part; this instance is unchanged
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is not its declared width, does not carry the
     *     declared separators, or holds a non-digit where a digit belongs
     */
    public DateHeader withTimestampFromFormatTime(String date10, String time08) {
        Objects.requireNonNull(date10, "A 10-character FORMATTIME DATE value is required; "
                + "app/cbl/COBIL00C.cbl:264 moves it into WS-TIMESTAMP(01:10)");
        Objects.requireNonNull(time08, "An 8-character FORMATTIME TIME value is required; "
                + "app/cbl/COBIL00C.cbl:265 moves it into WS-TIMESTAMP(12:08)");

        String composed = date10 + TIMESTAMP_DATE_TIME_SEPARATOR + time08
                + TIMESTAMP_FRACTION_SEPARATOR + pic9(0, MICROSECOND_DIGITS);
        if (composed.length() != WS_TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("WS-TIMESTAMP is PIC X(" + WS_TIMESTAMP_LENGTH
                    + "), so FORMATTIME's DATE must be " + WS_TIMESTAMP_DATE_WIDTH
                    + " characters and its TIME " + WS_TIMESTAMP_TIME_WIDTH + "; the supplied values "
                    + "are " + date10.length() + " and " + time08.length() + ", which compose "
                    + composed.length() + " characters");
        }
        return withTimestampImage(composed);
    }

    /**
     * The {@code WS-CURDATE-DATA} group: the instant {@code MOVE FUNCTION CURRENT-DATE} put there,
     * decomposed into the copybook's components.
     *
     * <p>After a {@code with…} operation it may legitimately carry a different moment from
     * {@link #wsTimestamp()} or {@link #wsCurdateMmDdYy()} - which is exactly what
     * {@code app/cbl/COTRN00C.cbl:384-387} produces.
     *
     * @return the components of this group; an immutable record
     */
    public CapturedDateTime captured() {
        return captured;
    }

    /**
     * The codec this header renders through - the source of its zero-fill and of the code page
     * {@link #toBytes()} encodes in.
     *
     * @return the codec supplied at construction
     */
    public FixedWidthCodec codec() {
        return codec;
    }

    /**
     * {@code WS-CURDATE} - 8 characters, {@code YYYYMMDD}.
     *
     * @return exactly {@value #WS_CURDATE_LENGTH} characters
     */
    public String wsCurdate() {
        return pic9(captured.year(), YEAR_DIGITS)
                + pic9(captured.month(), MONTH_DIGITS)
                + pic9(captured.day(), DAY_DIGITS);
    }

    /**
     * {@code WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08)} - the same eight bytes read as one unsigned
     * number, {@code app/cpy/CSDAT01Y.cpy:23}.
     *
     * <p>Note that the leading zeros of a year below 1000 are lost in the numeric view, exactly as they are
     * in COBOL - the digits are still there in the eight-byte span.
     *
     * @return the value the eight-character {@code YYYYMMDD} span denotes, 0 to 99999999
     */
    public int wsCurdateN() {
        return codec.decodePic9AsInt(wsCurdate());
    }

    /**
     * {@code WS-CURTIME} - 8 characters, {@code HHMMSSss}, where {@code ss} is hundredths of a second from
     * {@code WS-CURTIME-MILSEC}.
     *
     * @return exactly {@value #WS_CURTIME_LENGTH} characters
     */
    public String wsCurtime() {
        return pic9(captured.hours(), HOURS_DIGITS)
                + pic9(captured.minutes(), MINUTE_DIGITS)
                + pic9(captured.seconds(), SECOND_DIGITS)
                + pic9(captured.hundredths(), MILSEC_DIGITS);
    }

    /**
     * {@code WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08)} - the same eight bytes read as one unsigned
     * number, {@code app/cpy/CSDAT01Y.cpy:29}.
     *
     * @return the value the eight-character {@code HHMMSSss} span denotes, 0 to 99999999
     */
    public int wsCurtimeN() {
        return codec.decodePic9AsInt(wsCurtime());
    }

    /**
     * The 21-character result of {@code FUNCTION CURRENT-DATE}: {@code YYYYMMDD} then {@code HHMMSSss} then
     * the {@code shhmm} offset from Greenwich.
     *
     * <p>The width is corroborated inside this repository by {@code 01 COBOL-TS} at
     * {@code app/cbl/CBACT04C.cbl:141-149}, whose eight items sum to exactly 21 bytes and which receives
     * this intrinsic at line 614.
     *
     * @return exactly {@value #FUNCTION_CURRENT_DATE_LENGTH} characters
     */
    public String functionCurrentDate() {
        return wsCurdate() + wsCurtime() + gmtOffsetImage();
    }

    /**
     * The {@code shhmm} offset from Greenwich that {@code FUNCTION CURRENT-DATE} appends -
     * {@code COB-REST PIC X(05)} in {@code app/cbl/CBACT04C.cbl:149}.
     *
     * @return exactly {@value #GMT_OFFSET_LENGTH} characters, for example {@code +0000} or {@code -0600}
     */
    public String gmtOffsetImage() {
        int offsetMinutes = captured.gmtOffsetMinutes();
        char sign = offsetMinutes < 0 ? GMT_OFFSET_NEGATIVE_SIGN : GMT_OFFSET_POSITIVE_SIGN;
        int magnitude = Math.abs(offsetMinutes);
        return sign
                + pic9(magnitude / MINUTES_PER_HOUR, HOURS_DIGITS)
                + pic9(magnitude % MINUTES_PER_HOUR, MINUTE_DIGITS);
    }

    /**
     * {@code WS-CURDATE-DATA} - 16 characters, {@code YYYYMMDDHHMMSSss}.
     *
     * @return exactly {@value #WS_CURDATE_DATA_LENGTH} characters
     */
    public String wsCurdateData() {
        return codec.movePicX(functionCurrentDate(), WS_CURDATE_DATA_LENGTH);
    }

    /**
     * The five characters that {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} throws away: the tail
     * of {@link #functionCurrentDate()} beyond this group's 16 bytes.
     *
     * @return exactly {@value #GMT_OFFSET_LENGTH} characters
     */
    public String discardedGmtOffset() {
        return functionCurrentDate().substring(WS_CURDATE_DATA_LENGTH);
    }

    /**
     * {@code WS-CURDATE-MM-DD-YY} - 8 characters, {@code MM/DD/YY}, the date as every screen's heading line
     * shows it.
     *
     * @return exactly {@value #WS_CURDATE_MM_DD_YY_LENGTH} characters, for example {@code 12/25/24}
     */
    public String wsCurdateMmDdYy() {
        return pic9(curdateMmDdYy.mm(), MONTH_DIGITS)
                + DATE_SEPARATOR
                + pic9(curdateMmDdYy.dd(), DAY_DIGITS)
                + DATE_SEPARATOR
                + wsCurdateYy();
    }

    /**
     * {@code WS-CURDATE-YY} - the two-digit year, {@code app/cpy/CSDAT01Y.cpy:35}.
     *
     * @return exactly {@value #TWO_DIGIT_YEAR_DIGITS} characters
     */
    public String wsCurdateYy() {
        return pic9(curdateMmDdYy.yy(), TWO_DIGIT_YEAR_DIGITS);
    }

    /**
     * {@code WS-CURTIME-HH-MM-SS} - 8 characters, {@code HH:MM:SS}, the time as every screen's heading line
     * shows it.
     *
     * @return exactly {@value #WS_CURTIME_HH_MM_SS_LENGTH} characters, for example {@code 03:04:05}
     */
    public String wsCurtimeHhMmSs() {
        return pic9(curtimeHhMmSs.hh(), HOURS_DIGITS)
                + TIME_SEPARATOR
                + pic9(curtimeHhMmSs.mm(), MINUTE_DIGITS)
                + TIME_SEPARATOR
                + pic9(curtimeHhMmSs.ss(), SECOND_DIGITS);
    }

    /**
     * {@code WS-TIMESTAMP} - 26 characters, {@code YYYY-MM-DD HH:MM:SS.ssssss}.
     *
     * @return exactly {@value #WS_TIMESTAMP_LENGTH} characters
     */
    public String wsTimestamp() {
        return pic9(timestamp.year(), YEAR_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(timestamp.month(), MONTH_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(timestamp.day(), DAY_DIGITS)
                + TIMESTAMP_DATE_TIME_SEPARATOR
                + pic9(timestamp.hours(), HOURS_DIGITS)
                + TIME_SEPARATOR
                + pic9(timestamp.minutes(), MINUTE_DIGITS)
                + TIME_SEPARATOR
                + pic9(timestamp.seconds(), SECOND_DIGITS)
                + TIMESTAMP_FRACTION_SEPARATOR
                + pic9(timestamp.microseconds(), MICROSECOND_DIGITS);
    }

    /**
     * {@code DB2-FORMAT-TS PIC X(26)} as {@code app/cbl/CBACT04C.cbl} builds it - 26 characters in
     * {@code YYYY-MM-DD-HH.MM.SS.ssssss} form.
     *
     * @return exactly {@value #WS_TIMESTAMP_LENGTH} characters
     */
    public String db2FormatTimestamp() {
        return pic9(captured.year(), YEAR_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(captured.month(), MONTH_DIGITS)
                + TIMESTAMP_DATE_SEPARATOR
                + pic9(captured.day(), DAY_DIGITS)
                + DB2_DATE_TIME_SEPARATOR
                + pic9(captured.hours(), HOURS_DIGITS)
                + DB2_TIME_SEPARATOR
                + pic9(captured.minutes(), MINUTE_DIGITS)
                + DB2_TIME_SEPARATOR
                + pic9(captured.seconds(), SECOND_DIGITS)
                + DB2_TIME_SEPARATOR
                + pic9(captured.hundredths(), MILSEC_DIGITS)
                + DB2_FRACTION_REMAINDER;
    }

    /**
     * Every referable elementary field of {@code 01 WS-DATE-TIME} as its raw image, keyed by the copybook's
     * own field name and returned in copybook declaration order.
     *
     * <p>The separator {@code FILLER}s are absent because {@code FILLER} is not a referable COBOL name and
     * this copybook declares ten of them - they cannot be distinct keys.
     *
     * @return an unmodifiable, insertion-ordered map of 20 field names to images
     */
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        images.put(WS_CURDATE_YEAR, pic9(captured.year(), YEAR_DIGITS));
        images.put(WS_CURDATE_MONTH, pic9(captured.month(), MONTH_DIGITS));
        images.put(WS_CURDATE_DAY, pic9(captured.day(), DAY_DIGITS));
        images.put(WS_CURTIME_HOURS, pic9(captured.hours(), HOURS_DIGITS));
        images.put(WS_CURTIME_MINUTE, pic9(captured.minutes(), MINUTE_DIGITS));
        images.put(WS_CURTIME_SECOND, pic9(captured.seconds(), SECOND_DIGITS));
        images.put(WS_CURTIME_MILSEC, pic9(captured.hundredths(), MILSEC_DIGITS));
        images.put(WS_CURDATE_MM, pic9(curdateMmDdYy.mm(), MONTH_DIGITS));
        images.put(WS_CURDATE_DD, pic9(curdateMmDdYy.dd(), DAY_DIGITS));
        images.put(WS_CURDATE_YY, wsCurdateYy());
        images.put(WS_CURTIME_HH, pic9(curtimeHhMmSs.hh(), HOURS_DIGITS));
        images.put(WS_CURTIME_MM, pic9(curtimeHhMmSs.mm(), MINUTE_DIGITS));
        images.put(WS_CURTIME_SS, pic9(curtimeHhMmSs.ss(), SECOND_DIGITS));
        images.put(WS_TIMESTAMP_DT_YYYY, pic9(timestamp.year(), YEAR_DIGITS));
        images.put(WS_TIMESTAMP_DT_MM, pic9(timestamp.month(), MONTH_DIGITS));
        images.put(WS_TIMESTAMP_DT_DD, pic9(timestamp.day(), DAY_DIGITS));
        images.put(WS_TIMESTAMP_TM_HH, pic9(timestamp.hours(), HOURS_DIGITS));
        images.put(WS_TIMESTAMP_TM_MM, pic9(timestamp.minutes(), MINUTE_DIGITS));
        images.put(WS_TIMESTAMP_TM_SS, pic9(timestamp.seconds(), SECOND_DIGITS));
        images.put(WS_TIMESTAMP_TM_MS6, pic9(timestamp.microseconds(), MICROSECOND_DIGITS));
        return Collections.unmodifiableMap(images);
    }

    /**
     * The complete {@code 01 WS-DATE-TIME} area as {@value #WS_DATE_TIME_LENGTH} bytes in the codec's code
     * page.
     *
     * @return a fresh array of exactly {@value #WS_DATE_TIME_LENGTH} bytes
     */
    public byte[] toBytes() {
        return codec.serialise(WS_DATE_TIME_LAYOUT, fieldImages());
    }

    private String pic9(int value, int digits) {
        return codec.movePic9(value, digits);
    }

    /**
     * Two headers are equal when they captured the same instant and render in the same code page.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code DateHeader} with the same captured instant and
     *     code page
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DateHeader that)) {
            return false;
        }
        return captured.equals(that.captured)
                && curdateMmDdYy.equals(that.curdateMmDdYy)
                && curtimeHhMmSs.equals(that.curtimeHhMmSs)
                && timestamp.equals(that.timestamp)
                && codec.charset().equals(that.codec.charset());
    }

    /**
     * Consistent with {@link #equals(Object)}: derived from all four independent groups and the code page.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(captured, curdateMmDdYy, curtimeHhMmSs, timestamp, codec.charset());
    }

    /**
     * A diagnostic rendering naming the captured instant and the code page.
     *
     * <p>Deliberately not one of the copybook's field images: a log line that looked like a
     * {@code WS-TIMESTAMP} could be mistaken for one when a parity difference is being traced.
     *
     * @return for example
     *     {@code DateHeader[WS-TIMESTAMP=2024-12-25 13:45:07.089123, offset=+0000, charset=US-ASCII]}
     */
    @Override
    public String toString() {
        return "DateHeader[" + WS_TIMESTAMP + "=" + wsTimestamp()
                + ", offset=" + gmtOffsetImage()
                + ", charset=" + codec.charset().name() + "]";
    }
}
