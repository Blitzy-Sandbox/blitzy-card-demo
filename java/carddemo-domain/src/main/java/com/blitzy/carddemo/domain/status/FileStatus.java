/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 */
package com.blitzy.carddemo.domain.status;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Sealed hierarchy modelling the COBOL {@code FILE STATUS} return codes
 * encountered across the CardDemo programs (e.g.,
 * {@code app/cbl/CBACT01C.cbl}, {@code app/cbl/CBTRN02C.cbl}).
 *
 * <p>Per AAP &sect;0.6.10 every closed COBOL response-code set translates to a
 * Java sealed interface so the compiler enforces exhaustiveness checking on
 * pattern-matching {@code switch} expressions. No {@code default ->} branch
 * is permitted; every site must enumerate every permit.
 *
 * <h2>Permits</h2>
 * <ul>
 *   <li>{@link Ok}            &mdash; COBOL FILE STATUS "00" / response code 0</li>
 *   <li>{@link EndOfFile}     &mdash; COBOL FILE STATUS "10" / response code 4</li>
 *   <li>{@link NotFound}      &mdash; COBOL FILE STATUS "23" / response code 13</li>
 *   <li>{@link DuplicateKey}  &mdash; COBOL FILE STATUS "22" / response code 12</li>
 *   <li>{@link IoError}       &mdash; All other FILE STATUS codes; carries the
 *       numeric COBOL status and a human-readable description.</li>
 * </ul>
 *
 * <p>This hierarchy is intentionally minimal: each program in {@code app/cbl/}
 * checks only a small subset of FILE STATUS values, and the {@link IoError}
 * record captures the catch-all path that the COBOL code reaches via
 * {@code 9910-DISPLAY-IO-STATUS / 9999-ABEND-PROGRAM}.
 *
 * @since 1.0.0
 */
@CobolProgram(
        value = "FILE-STATUS",
        notes = "Sealed hierarchy of COBOL FILE STATUS return codes; see AAP §0.6.10"
)
public sealed interface FileStatus
        permits FileStatus.Ok,
                FileStatus.EndOfFile,
                FileStatus.NotFound,
                FileStatus.DuplicateKey,
                FileStatus.IoError {

    /**
     * Convenience singleton for the success case. Use in place of allocating
     * a new {@link Ok} every time a successful I/O completes.
     */
    Ok OK = new Ok();

    /**
     * Convenience singleton for the end-of-file case.
     */
    EndOfFile END_OF_FILE = new EndOfFile();

    /**
     * Convenience singleton for the record-not-found case.
     */
    NotFound NOT_FOUND = new NotFound();

    /**
     * Convenience singleton for the duplicate-key-on-write case.
     */
    DuplicateKey DUPLICATE_KEY = new DuplicateKey();

    /**
     * Returns {@code true} if and only if this status represents a successful
     * operation (i.e., {@code this instanceof Ok}). Use as a precondition
     * before reading any record value.
     *
     * @return {@code true} for {@link Ok}, {@code false} for every other
     *         permitted subtype
     */
    default boolean isOk() {
        return this instanceof Ok;
    }

    /**
     * Returns the 2-character COBOL {@code FILE STATUS} code that this permit
     * represents, preserving the byte-fidelity surface used in COBOL
     * {@code MOVE CUSTFILE-STATUS TO IO-STATUS} / {@code DISPLAY} sequences.
     *
     * <p>Pattern-matching dispatch on the sealed hierarchy (no {@code default})
     * keeps the mapping exhaustive: adding a new permit forces every site that
     * calls {@code toCobolCode()} to be updated in lockstep, which is exactly
     * the compile-time guarantee mandated by AAP &sect;0.6.10.
     *
     * @return a non-{@code null} 2-character string matching the COBOL
     *         {@code FILE STATUS} convention; for {@link IoError} the
     *         numeric code is zero-padded to 2 digits, or the literal
     *         {@code "30"} (permanent error) when the embedded code is
     *         out of the standard 0-99 COBOL range
     */
    default String toCobolCode() {
        return switch (this) {
            case Ok ok            -> "00";
            case EndOfFile eof    -> "10";
            case DuplicateKey dk  -> "22";
            case NotFound nf      -> "23";
            case IoError(int code, String description) -> {
                // IBM Enterprise COBOL FILE STATUS is a 2-character display
                // representation. Codes 0-99 zero-pad to "00".."99"; codes
                // outside that range fall back to the synthetic "30"
                // (permanent error) which is what CardDemo COBOL would
                // emit for any unexpected I/O failure.
                if (code >= 0 && code <= 99) {
                    yield String.format("%02d", code);
                } else {
                    yield "30";
                }
            }
        };
    }

    /**
     * Maps a 2-character COBOL {@code FILE STATUS} code (e.g. {@code "00"},
     * {@code "10"}, {@code "22"}, {@code "23"}, {@code "30"}, {@code "35"})
     * to its sealed {@link FileStatus} permit. Used by file-adapter and
     * use-case code that already carries the raw 2-byte status (preserved
     * for byte-fidelity logs) to obtain a typed value suitable for
     * exhaustive pattern matching.
     *
     * <p>Mapping (per IBM Enterprise COBOL FILE STATUS convention):
     * <ul>
     *   <li>{@code "00"} &rarr; {@link #OK} (successful completion)</li>
     *   <li>{@code "10"} &rarr; {@link #END_OF_FILE} (end-of-file on READ)</li>
     *   <li>{@code "22"} &rarr; {@link #DUPLICATE_KEY} (duplicate key on WRITE)</li>
     *   <li>{@code "23"} &rarr; {@link #NOT_FOUND} (record not found on keyed READ)</li>
     *   <li>any other 2-char digit pair (e.g. {@code "30"}, {@code "35"},
     *       {@code "47"}) &rarr; {@code IoError(numericCode, "FILE STATUS " + code)}</li>
     *   <li>{@code null}, empty, or non-numeric &rarr;
     *       {@code IoError(-1, "FILE STATUS <raw>")}</li>
     * </ul>
     *
     * <p>This is the canonical entry point for code that has just observed a
     * COBOL-style file-status string (e.g. as read from a {@code CUSTFILE-STATUS}
     * working-storage variable or returned from an adapter) and wants to
     * dispatch on it via an exhaustive {@code switch} per AAP &sect;0.6.10.
     *
     * @param code the raw 2-character COBOL FILE STATUS string, e.g. as
     *             read from a {@code PIC X(02)} field; may be {@code null}
     *             or of any length (defensive)
     * @return a {@link FileStatus} permit matching the code; never
     *         {@code null}
     */
    static FileStatus fromCobolCode(String code) {
        if (code == null) {
            return new IoError(-1, "FILE STATUS null");
        }
        // Strict-equality compares match the COBOL idiom
        // IF CUSTFILE-STATUS = '00' which uses byte-for-byte equality.
        switch (code) {
            case "00": return OK;
            case "10": return END_OF_FILE;
            case "22": return DUPLICATE_KEY;
            case "23": return NOT_FOUND;
            default:
                // Try to parse as a 2-digit numeric code so IoError carries
                // the numeric form for downstream callers that want to log
                // or compare it against APPL-RESULT-style constants.
                int numeric = -1;
                if (code.length() == 2
                        && Character.isDigit(code.charAt(0))
                        && Character.isDigit(code.charAt(1))) {
                    numeric = (code.charAt(0) - '0') * 10 + (code.charAt(1) - '0');
                }
                return new IoError(numeric, "FILE STATUS " + code);
        }
    }

    /** Successful I/O completion (COBOL FILE STATUS "00"). */
    record Ok() implements FileStatus {}

    /** End-of-file reached during sequential read (COBOL FILE STATUS "10"). */
    record EndOfFile() implements FileStatus {}

    /** Record not found on keyed read (COBOL FILE STATUS "23"). */
    record NotFound() implements FileStatus {}

    /** Duplicate key on indexed write (COBOL FILE STATUS "22"). */
    record DuplicateKey() implements FileStatus {}

    /**
     * Catch-all I/O failure. The {@code code} field carries the numeric COBOL
     * FILE STATUS (or {@code -1} when the originating exception had no
     * COBOL-equivalent status), and {@code description} carries a
     * human-readable message suitable for logs.
     */
    record IoError(int code, String description) implements FileStatus {
        /**
         * Compact canonical constructor: clamps {@code description} to a
         * non-{@code null} string (empty string substituted for {@code null}).
         */
        public IoError {
            if (description == null) {
                description = "";
            }
        }
    }
}
