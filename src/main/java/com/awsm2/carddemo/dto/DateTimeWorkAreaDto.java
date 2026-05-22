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
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Date/time working-storage DTO mirroring the COBOL {@code WS-DATE-TIME}
 * 01-level structure defined in {@code app/cpy/CSDAT01Y.cpy}.
 *
 * <p><b>COBOL source layout</b> (preserved verbatim from
 * {@code app/cpy/CSDAT01Y.cpy} lines 17&ndash;55):</p>
 * <pre>{@code
 *   01 WS-DATE-TIME.
 *     05 WS-CURDATE-DATA.
 *       10  WS-CURDATE.
 *         15  WS-CURDATE-YEAR         PIC 9(04).
 *         15  WS-CURDATE-MONTH        PIC 9(02).
 *         15  WS-CURDATE-DAY          PIC 9(02).
 *       10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08).
 *       10  WS-CURTIME.
 *         15  WS-CURTIME-HOURS        PIC 9(02).
 *         15  WS-CURTIME-MINUTE       PIC 9(02).
 *         15  WS-CURTIME-SECOND       PIC 9(02).
 *         15  WS-CURTIME-MILSEC       PIC 9(02).
 *       10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08).
 *     05 WS-CURDATE-MM-DD-YY.            * pre-formatted display variant
 *       10  WS-CURDATE-MM             PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE '/'.
 *       10  WS-CURDATE-DD             PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE '/'.
 *       10  WS-CURDATE-YY             PIC 9(02).
 *     05 WS-CURTIME-HH-MM-SS.            * pre-formatted display variant
 *       10  WS-CURTIME-HH             PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE ':'.
 *       10  WS-CURTIME-MM             PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE ':'.
 *       10  WS-CURTIME-SS             PIC 9(02).
 *     05 WS-TIMESTAMP.                   * combined date+time+microseconds
 *       10  WS-TIMESTAMP-DT-YYYY      PIC 9(04).
 *       10  FILLER                    PIC X(01) VALUE '-'.
 *       10  WS-TIMESTAMP-DT-MM        PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE '-'.
 *       10  WS-TIMESTAMP-DT-DD        PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE ' '.
 *       10  WS-TIMESTAMP-TM-HH        PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE ':'.
 *       10  WS-TIMESTAMP-TM-MM        PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE ':'.
 *       10  WS-TIMESTAMP-TM-SS        PIC 9(02).
 *       10  FILLER                    PIC X(01) VALUE '.'.
 *       10  WS-TIMESTAMP-TM-MS6       PIC 9(06).
 * }</pre>
 *
 * <p>The original COBOL working storage carried four parallel representations
 * of essentially the same instant in time, all populated from a single
 * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} statement:</p>
 * <ul>
 *   <li>Numeric individual components (year, month, day, hours, minutes,
 *       seconds, milliseconds) &mdash; {@code PIC 9} fields used for any
 *       arithmetic or per-component access.</li>
 *   <li>{@code REDEFINES} numeric forms ({@code WS-CURDATE-N}/{@code PIC 9(08)},
 *       {@code WS-CURTIME-N}/{@code PIC 9(08)}) &mdash; concatenated 8-digit
 *       integer views of the same bytes, used where the program needed to
 *       compare or sort dates/times as integers.</li>
 *   <li>Pre-formatted display variants {@code WS-CURDATE-MM-DD-YY} ("MM/DD/YY")
 *       and {@code WS-CURTIME-HH-MM-SS} ("HH:MM:SS") &mdash; assembled with
 *       {@code FILLER} literals and {@code MOVE} statements (e.g.,
 *       {@code MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY}) so that the
 *       BMS header fields {@code CURDATEO} and {@code CURTIMEO} on every
 *       screen could be populated with a single {@code MOVE} per field.</li>
 *   <li>{@code WS-TIMESTAMP} &mdash; the combined "YYYY-MM-DD HH:MM:SS.NNNNNN"
 *       (26-byte) representation used for {@code TRAN-ORIG-TS}/
 *       {@code TRAN-PROC-TS} stamping when posting transactions.</li>
 * </ul>
 *
 * <p>In the Java/Spring Boot target all four representations collapse to three
 * native {@link java.time} types &mdash; per AAP &sect;0.6.1 ("LE
 * {@code CEEDAYS} (date validation) &rarr; native Java date validation via
 * {@code java.time.LocalDate.parse() with DateTimeFormatter}") and
 * &sect;0.5.2 (the LE runtime is fully retired in favor of {@code java.time}):</p>
 * <ul>
 *   <li>{@link LocalDate} for {@link #currentDate()} &mdash; subsumes
 *       {@code WS-CURDATE-YEAR/MONTH/DAY}, {@code WS-CURDATE-N}, and the
 *       {@code WS-CURDATE-MM-DD-YY} display variant.</li>
 *   <li>{@link LocalTime} for {@link #currentTime()} &mdash; subsumes
 *       {@code WS-CURTIME-HOURS/MINUTE/SECOND/MILSEC}, {@code WS-CURTIME-N},
 *       and the {@code WS-CURTIME-HH-MM-SS} display variant.</li>
 *   <li>{@link LocalDateTime} for {@link #currentTimestamp()} &mdash;
 *       subsumes the combined {@code WS-TIMESTAMP} layout (with microsecond
 *       precision available via the {@code nano} field of
 *       {@link LocalDateTime}, satisfying the original
 *       {@code WS-TIMESTAMP-TM-MS6 PIC 9(06)} resolution).</li>
 * </ul>
 *
 * <p>Display-format assembly (the COBOL {@code MOVE WS-CURDATE-YEAR(3:2) TO
 * WS-CURDATE-YY} reformatting cascade) is intentionally <em>not</em>
 * preserved on the server: the JSON wire contract is unconditional ISO-8601
 * ({@code yyyy-MM-dd}, {@code HH:mm:ss}, {@code yyyy-MM-dd'T'HH:mm:ss}) per
 * the {@link JsonFormat} annotations below, and any locale-specific
 * formatting for end-user presentation (including the legacy MM/DD/YY and
 * HH:MM:SS shapes the 3270 screens used) is the responsibility of the
 * downstream client.</p>
 *
 * <p><b>Usage:</b> this DTO is rarely needed by services &mdash; most
 * services consume {@link LocalDate}/{@link LocalDateTime} directly on
 * their domain objects (e.g., {@code Account.openDate},
 * {@code Transaction.originTimestamp}). It is provided for traceability and
 * for the few cases where a service surfaces a "current server clock
 * snapshot" to a client (the analogue of the {@code CURDATEO} and
 * {@code CURTIMEO} header fields every BMS screen rendered). Callers
 * typically construct one of these via
 * {@code new DateTimeWorkAreaDto(LocalDate.now(), LocalTime.now(),
 * LocalDateTime.now())} and embed it in a response envelope.</p>
 *
 * <p><b>Null-handling:</b> the three components are conceptually
 * all-or-none &mdash; they represent a single server-clock snapshot taken
 * at request time. The {@link JsonInclude#NON_NULL} setting at the record
 * level nevertheless allows partial population (e.g., a date-only response
 * that omits the time component) without leaking explicit {@code null}
 * values to the wire, keeping the JSON contract compact and stable for
 * OpenAPI consumers.</p>
 *
 * <p><b>COBOL Provenance:</b></p>
 * <ul>
 *   <li>Copybook: {@code app/cpy/CSDAT01Y.cpy} (structure {@code WS-DATE-TIME})</li>
 *   <li>Source population statement: {@code MOVE FUNCTION CURRENT-DATE TO
 *       WS-CURDATE-DATA} &mdash; replaced by {@link LocalDate#now()},
 *       {@link LocalTime#now()}, and {@link LocalDateTime#now()} (each
 *       called with a {@code Clock} bean in the Java target so tests can
 *       inject a fixed clock).</li>
 *   <li>Used by: every CICS online program (e.g., {@code app/cbl/COSGN00C.cbl},
 *       {@code app/cbl/COMEN01C.cbl}, {@code app/cbl/COACTUPC.cbl},
 *       {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COTRN02C.cbl},
 *       {@code app/cbl/CORPT00C.cbl}, etc.) via
 *       {@code COPY CSDAT01Y.} in WORKING-STORAGE plus a
 *       {@code POPULATE-HEADER-INFO} paragraph that fills the screen
 *       header's {@code CURDATEO}/{@code CURTIMEO} fields.</li>
 *   <li>Retires: the LE runtime services {@code CEEDAYS} and
 *       {@code CEELOCT} that the COBOL date validators previously called
 *       (AAP &sect;0.5.2).</li>
 * </ul>
 *
 * @param currentDate      current system date (server clock at request
 *                         time). Subsumes COBOL {@code WS-CURDATE-YEAR},
 *                         {@code WS-CURDATE-MONTH}, {@code WS-CURDATE-DAY}
 *                         and the {@code WS-CURDATE-MM-DD-YY} display
 *                         variant. May be {@code null} when the caller
 *                         is surfacing only the time or timestamp
 *                         component.
 * @param currentTime      current system time (server clock at request
 *                         time). Subsumes COBOL {@code WS-CURTIME-HOURS},
 *                         {@code WS-CURTIME-MINUTE}, {@code WS-CURTIME-SECOND},
 *                         {@code WS-CURTIME-MILSEC} and the
 *                         {@code WS-CURTIME-HH-MM-SS} display variant.
 *                         May be {@code null} when the caller is
 *                         surfacing only the date or timestamp component.
 * @param currentTimestamp full combined timestamp (date + time, with
 *                         microsecond precision available). Subsumes the
 *                         26-byte COBOL {@code WS-TIMESTAMP}
 *                         {@code YYYY-MM-DD HH:MM:SS.NNNNNN} structure
 *                         used for {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS}
 *                         stamping in {@code app/cpy/CVTRA05Y.cpy}.
 *                         Provided for convenience so callers do not
 *                         have to combine {@link #currentDate()} and
 *                         {@link #currentTime()} on the client side.
 *                         May be {@code null} when only the
 *                         component-wise date or time is being surfaced.
 *
 * @see com.fasterxml.jackson.annotation.JsonFormat
 * @see com.fasterxml.jackson.annotation.JsonInclude
 * @see com.fasterxml.jackson.annotation.JsonProperty
 * @see io.swagger.v3.oas.annotations.media.Schema
 * @see java.time.LocalDate
 * @see java.time.LocalTime
 * @see java.time.LocalDateTime
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "DateTimeWorkArea",
        description = "Server-clock snapshot carrying the current date, "
                + "current time, and a combined timestamp. Mirrors COBOL "
                + "WS-DATE-TIME from app/cpy/CSDAT01Y.cpy, which every CICS "
                + "online program populated via MOVE FUNCTION CURRENT-DATE "
                + "and surfaced on BMS screen headers (CURDATEO / CURTIMEO). "
                + "The four parallel COBOL representations (numeric components, "
                + "REDEFINES integer forms, MM/DD/YY and HH:MM:SS pre-formatted "
                + "variants, and the 26-byte timestamp) collapse to three "
                + "native java.time types here; the JSON wire contract is "
                + "unconditional ISO-8601 and any locale-specific reformatting "
                + "is the client's responsibility (AAP \u00a70.6.1).")
public record DateTimeWorkAreaDto(

        /**
         * Current system date &mdash; mirrors the COBOL
         * {@code WS-CURDATE} sub-structure (year/month/day) plus the
         * pre-formatted {@code WS-CURDATE-MM-DD-YY} display variant.
         *
         * <p>In the COBOL source, this value was obtained by
         * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} (with the
         * intrinsic function returning a 21-character ISO-8601 string from
         * which only the first 8 bytes "YYYYMMDD" mapped into
         * {@code WS-CURDATE}). Per AAP &sect;0.6.1 and &sect;0.5.2 the LE
         * runtime is fully retired; the Java target uses
         * {@link LocalDate#now()} (called via a {@code Clock} bean so
         * tests can inject a fixed clock) and serializes the value as a
         * canonical ISO-8601 {@code yyyy-MM-dd} string on the wire,
         * regardless of any client-side locale preference.
         *
         * <p>The on-wire JSON key is bound to {@code "currentDate"} by
         * {@link JsonProperty} so the contract remains stable across any
         * future component renames in this record.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Current system date (server clock at request "
                + "time, ISO-8601 yyyy-MM-dd). Subsumes COBOL "
                + "WS-CURDATE-YEAR / WS-CURDATE-MONTH / WS-CURDATE-DAY "
                + "(PIC 9(04)/9(02)/9(02)) and the WS-CURDATE-MM-DD-YY "
                + "display variant from app/cpy/CSDAT01Y.cpy. Locale-specific "
                + "reformatting (e.g., the legacy MM/DD/YY shape) is the "
                + "client's responsibility.",
                example = "2026-05-20",
                format = "date",
                type = "string",
                nullable = true)
        @JsonProperty("currentDate")
        LocalDate currentDate,

        /**
         * Current system time &mdash; mirrors the COBOL
         * {@code WS-CURTIME} sub-structure (hours/minutes/seconds/milliseconds)
         * plus the pre-formatted {@code WS-CURTIME-HH-MM-SS} display variant.
         *
         * <p>In the COBOL source, this was populated from the same
         * {@code FUNCTION CURRENT-DATE} call as {@link #currentDate()},
         * with bytes 9&ndash;16 of the 21-character intrinsic-function
         * result mapping to {@code WS-CURTIME}. Per AAP &sect;0.6.1 the
         * Java target uses {@link LocalTime#now()} (via a {@code Clock}
         * bean) and serializes the value as a canonical 24-hour
         * {@code HH:mm:ss} ISO-8601 string. Note that the COBOL
         * {@code WS-CURTIME-MILSEC} (centisecond) field is dropped from
         * the wire contract here &mdash; sub-second precision is carried
         * by {@link #currentTimestamp()} instead.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
        @Schema(description = "Current system time (server clock at request "
                + "time, 24-hour HH:mm:ss). Subsumes COBOL "
                + "WS-CURTIME-HOURS / WS-CURTIME-MINUTE / WS-CURTIME-SECOND "
                + "(PIC 9(02) each) and the WS-CURTIME-HH-MM-SS display "
                + "variant from app/cpy/CSDAT01Y.cpy. Sub-second precision "
                + "lives on currentTimestamp.",
                example = "14:30:45",
                format = "time",
                type = "string",
                nullable = true)
        @JsonProperty("currentTime")
        LocalTime currentTime,

        /**
         * Full combined timestamp &mdash; mirrors the 26-byte COBOL
         * {@code WS-TIMESTAMP} structure
         * ({@code YYYY-MM-DD HH:MM:SS.NNNNNN}, with microsecond precision)
         * from {@code app/cpy/CSDAT01Y.cpy} lines 42&ndash;55.
         *
         * <p>In the COBOL source this layout was used to stamp transaction
         * origination and processing timestamps ({@code TRAN-ORIG-TS} and
         * {@code TRAN-PROC-TS} from {@code app/cpy/CVTRA05Y.cpy}). Per AAP
         * &sect;0.6.1 the Java target uses {@link LocalDateTime#now()}
         * (via a {@code Clock} bean) and serializes the value as a
         * canonical ISO-8601 {@code yyyy-MM-dd'T'HH:mm:ss} string with
         * the 'T' separator (replacing the COBOL space separator) and
         * second-resolution truncation on the wire; sub-second precision
         * remains available on the {@link LocalDateTime} instance itself
         * if a caller deserializes the JSON back into the record using a
         * format that preserves nanoseconds.
         *
         * <p>Although conceptually equivalent to the combination of
         * {@link #currentDate()} and {@link #currentTime()}, this field
         * is surfaced separately so REST consumers receive a single
         * "instant" value without having to combine the date and time
         * client-side &mdash; matching the convenience the COBOL
         * {@code WS-TIMESTAMP} variant provided for downstream record
         * stamping.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Full server-clock timestamp (date + time) at "
                + "request time, ISO-8601 yyyy-MM-dd'T'HH:mm:ss. Subsumes "
                + "the 26-byte COBOL WS-TIMESTAMP "
                + "(YYYY-MM-DD HH:MM:SS.NNNNNN) layout from "
                + "app/cpy/CSDAT01Y.cpy and matches the format used to "
                + "stamp TRAN-ORIG-TS / TRAN-PROC-TS in CVTRA05Y.cpy.",
                example = "2026-05-20T14:30:45",
                format = "date-time",
                type = "string",
                nullable = true)
        @JsonProperty("currentTimestamp")
        LocalDateTime currentTimestamp
) {
}
