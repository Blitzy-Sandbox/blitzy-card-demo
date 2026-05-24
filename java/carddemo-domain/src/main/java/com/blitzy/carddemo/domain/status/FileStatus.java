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
