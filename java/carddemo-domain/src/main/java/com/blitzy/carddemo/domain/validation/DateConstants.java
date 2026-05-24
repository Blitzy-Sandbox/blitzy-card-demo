/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.validation;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Domain record set translated from {@code app/cpy/CSDAT01Y.cpy}
 * ({@code WS-DATE-TIME}). The COBOL working-storage group hosts the current
 * date / time / timestamp values with multiple REDEFINES (component vs.
 * numeric views). The Java translation exposes:
 * <ul>
 *   <li>{@link WsCurDate}: sealed {@code (Components|Numeric)} REDEFINES on the
 *       {@code YYYYMMDD} date view per AAP &sect;0.6.10.</li>
 *   <li>{@link WsCurTime}: sealed {@code (Components|Numeric)} REDEFINES on the
 *       {@code HHMMSSMS} time view per AAP &sect;0.6.10.</li>
 *   <li>{@code FMT_*} {@link DateTimeFormatter} constants for the formatted
 *       views (MM/DD/YY, HH:MM:SS, full timestamp).</li>
 * </ul>
 *
 * <pre>{@code
 * 01 WS-DATE-TIME.
 *    05 WS-CURDATE-DATA.
 *       10 WS-CURDATE.
 *          15 WS-CURDATE-YEAR    PIC 9(04).
 *          15 WS-CURDATE-MONTH   PIC 9(02).
 *          15 WS-CURDATE-DAY     PIC 9(02).
 *       10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08).
 *       10 WS-CURTIME.
 *          15 WS-CURTIME-HOURS   PIC 9(02).
 *          15 WS-CURTIME-MINUTE  PIC 9(02).
 *          15 WS-CURTIME-SECOND  PIC 9(02).
 *          15 WS-CURTIME-MILSEC  PIC 9(02).
 *       10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08).
 *    05 WS-CURDATE-MM-DD-YY ('MM/DD/YY')
 *    05 WS-CURTIME-HH-MM-SS ('HH:MM:SS')
 *    05 WS-TIMESTAMP        ('YYYY-MM-DD HH:MM:SS.000000')
 * }</pre>
 */
@CobolProgram(
        value = "CSDAT01Y",
        sourcePath = "app/cpy/CSDAT01Y.cpy",
        notes = "WS-DATE-TIME; sealed Components|Numeric REDEFINES for WS-CURDATE and WS-CURTIME per AAP §0.6.10"
)
public final class DateConstants {

    public static final DateTimeFormatter FMT_YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    public static final DateTimeFormatter FMT_MM_DD_YY = DateTimeFormatter.ofPattern("MM/dd/yy");
    public static final DateTimeFormatter FMT_HHMMSSSS = DateTimeFormatter.ofPattern("HHmmssSS");
    public static final DateTimeFormatter FMT_HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final DateTimeFormatter FMT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private DateConstants() {
        // Utility class
    }

    /**
     * Sealed hierarchy modelling the WS-CURDATE / WS-CURDATE-N REDEFINES
     * (8-character YYYYMMDD). Each permit gives a different reading of the
     * same 8-byte region.
     */
    public sealed interface WsCurDate permits WsCurDate.Components, WsCurDate.Numeric {

        /** Returns the 8-character YYYYMMDD string view. */
        String yyyymmdd();

        /** Components view: year + month + day as separate ints. */
        record Components(int year, int month, int day) implements WsCurDate {
            public Components {
                if (year < 0 || year > 9999) {
                    throw new IllegalArgumentException("year must be 0..9999");
                }
                if (month < 1 || month > 12) {
                    throw new IllegalArgumentException("month must be 1..12");
                }
                if (day < 1 || day > 31) {
                    throw new IllegalArgumentException("day must be 1..31");
                }
            }

            @Override
            public String yyyymmdd() {
                return String.format("%04d%02d%02d", year, month, day);
            }

            /** Converts to {@link LocalDate}, validating month/day combinations. */
            public LocalDate toLocalDate() {
                return LocalDate.of(year, month, day);
            }
        }

        /** Numeric view: 8-digit unsigned long. */
        record Numeric(long yyyymmddN) implements WsCurDate {
            public Numeric {
                if (yyyymmddN < 0L || yyyymmddN > 99_999_999L) {
                    throw new IllegalArgumentException(
                            "yyyymmddN must be 0..99,999,999, got " + yyyymmddN);
                }
            }

            @Override
            public String yyyymmdd() {
                return String.format("%08d", yyyymmddN);
            }

            /** Converts to {@link LocalDate} via the YYYYMMDD digit representation. */
            public LocalDate toLocalDate() {
                int yyyy = (int) (yyyymmddN / 10_000L);
                int mm = (int) ((yyyymmddN / 100L) % 100L);
                int dd = (int) (yyyymmddN % 100L);
                return LocalDate.of(yyyy, mm, dd);
            }
        }

        /** Convenience factory that builds the components view from a {@link LocalDate}. */
        static WsCurDate fromLocalDate(LocalDate date) {
            return new Components(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        }
    }

    /**
     * Sealed hierarchy modelling the WS-CURTIME / WS-CURTIME-N REDEFINES
     * (8-character HHMMSSMS — hours, minutes, seconds, hundredths-of-second).
     */
    public sealed interface WsCurTime permits WsCurTime.Components, WsCurTime.Numeric {

        /** Returns the 8-character HHMMSSMS view. */
        String hhmmssss();

        /** Components view: hour + minute + second + milliseconds-as-two-digit. */
        record Components(int hours, int minutes, int seconds, int milsec) implements WsCurTime {
            public Components {
                if (hours < 0 || hours > 23) {
                    throw new IllegalArgumentException("hours must be 0..23");
                }
                if (minutes < 0 || minutes > 59) {
                    throw new IllegalArgumentException("minutes must be 0..59");
                }
                if (seconds < 0 || seconds > 59) {
                    throw new IllegalArgumentException("seconds must be 0..59");
                }
                if (milsec < 0 || milsec > 99) {
                    throw new IllegalArgumentException("milsec must be 0..99 (hundredths)");
                }
            }

            @Override
            public String hhmmssss() {
                return String.format("%02d%02d%02d%02d", hours, minutes, seconds, milsec);
            }

            public LocalTime toLocalTime() {
                // milsec is hundredths of a second → multiply by 10 for ms
                return LocalTime.of(hours, minutes, seconds, milsec * 10_000_000);
            }
        }

        /** Numeric view: 8-digit unsigned long. */
        record Numeric(long hhmmssssN) implements WsCurTime {
            public Numeric {
                if (hhmmssssN < 0L || hhmmssssN > 99_999_999L) {
                    throw new IllegalArgumentException(
                            "hhmmssssN must be 0..99,999,999, got " + hhmmssssN);
                }
            }

            @Override
            public String hhmmssss() {
                return String.format("%08d", hhmmssssN);
            }
        }

        static WsCurTime fromLocalTime(LocalTime time) {
            int hundredths = time.getNano() / 10_000_000;
            return new Components(time.getHour(), time.getMinute(), time.getSecond(), hundredths);
        }
    }

    /** Returns the current date as a sealed Components view per the JVM clock. */
    public static WsCurDate today() {
        return WsCurDate.fromLocalDate(LocalDate.now());
    }

    /** Returns the current time as a sealed Components view per the JVM clock. */
    public static WsCurTime now() {
        return WsCurTime.fromLocalTime(LocalTime.now());
    }

    /** Formats a {@link LocalDateTime} as the canonical CSDAT01Y timestamp string. */
    public static String formatTimestamp(LocalDateTime dt) {
        return FMT_TIMESTAMP.format(dt);
    }

    /** Formats a {@link LocalDate} as the legacy MM/DD/YY view. */
    public static String formatMmDdYy(LocalDate date) {
        return FMT_MM_DD_YY.format(date);
    }
}
