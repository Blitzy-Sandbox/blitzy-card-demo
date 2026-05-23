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
package com.aws.carddemo.io;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Translates legacy 2-character VSAM {@code STATUS} codes (returned by COBOL
 * {@code OPEN}, {@code READ}, {@code WRITE}, {@code REWRITE}, {@code DELETE},
 * and {@code CLOSE} operations) into idiomatic Java {@link FileStatusResult}
 * objects whose action enum and exception type the migrated batch processors
 * can interrogate without literal status-string comparisons.
 *
 * <h2>Provenance</h2>
 *
 * <p>The COBOL programs of the CardDemo source repository inspect VSAM status
 * codes by direct string comparison. Three patterns recur:
 *
 * <ul>
 *   <li>{@code IF FILE-STATUS = '00'} (success) - every program with VSAM I/O,
 *       e.g., {@code CBACT01C} line 94, {@code CBACT04C}, {@code CBTRN02C}.</li>
 *   <li>{@code IF FILE-STATUS = '10'} (EOF) - sequential read loops, e.g.,
 *       {@code CBACT01C} line 98 ({@code MOVE 'Y' TO END-OF-FILE}).</li>
 *   <li>{@code IF FILE-STATUS = '00' OR '23'} (success or record-not-found) -
 *       {@code CBACT04C} lines 422 and 436 trigger the {@code DEFAULT}-group
 *       fallback when status {@code '23'} is returned for a disclosure-group
 *       read. {@code CBTRN02C} line 481 mirrors this pattern for TCATBALF.</li>
 * </ul>
 *
 * <p>Any other status code in COBOL routes to {@code PERFORM 9999-ABEND-PROGRAM},
 * which ultimately invokes {@code CEE3ABD} to abend the program. The Java
 * migration captures that semantic in {@link FileStatusAction#ABEND} so the
 * caller can decide whether to {@code throw} the carried exception or escalate
 * via Spring Batch's skip-and-retry policy.
 *
 * <h2>Action Semantics</h2>
 *
 * <p>Each documented status code is mapped to exactly one
 * {@link FileStatusAction}:
 *
 * <ul>
 *   <li>{@link FileStatusAction#CONTINUE} - the operation succeeded; processing
 *       continues normally with no exception. Codes: {@code 00}, {@code 02},
 *       {@code 05}, {@code 97}.</li>
 *   <li>{@link FileStatusAction#END_OF_FILE} - the sequential read reached
 *       end-of-file; the batch reader should terminate iteration. Code:
 *       {@code 10}.</li>
 *   <li>{@link FileStatusAction#LOG_AND_CONTINUE} - the operation failed in a
 *       recoverable way; the caller logs and retries with a fallback. Codes:
 *       {@code 04}, {@code 23}.</li>
 *   <li>{@link FileStatusAction#ABEND} - the operation failed fatally; the
 *       carried exception is unrecoverable and must be propagated. Codes:
 *       every other documented status.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is stateless and immutable. The internal lookup table is built
 * once in a static initializer and exposed as an unmodifiable map. All instance
 * methods are safe for concurrent invocation across multiple threads.
 *
 * @see FileStatusResult
 * @see FileStatusAction
 * @see VsamException
 */
public final class FileStatusMapper {

    /**
     * Immutable lookup table keyed by VSAM status code. The value is a small
     * factory record that knows how to construct a {@link FileStatusResult} on
     * demand so each {@link #map(String)} call returns a fresh result instance
     * (which is important when the exception carries a contextual message).
     */
    private static final Map<String, Entry> TABLE = buildTable();

    /**
     * Creates a new {@code FileStatusMapper}. The instance carries no state;
     * a single instance can safely serve any number of concurrent callers.
     */
    public FileStatusMapper() {
        // No-op; this constructor exists to make the class straightforwardly
        // testable via {@code new FileStatusMapper()} and to allow Spring or
        // other DI frameworks to instantiate it as a singleton bean.
    }

    /**
     * Translates a 2-character VSAM status code into a {@link FileStatusResult}
     * describing the action the caller should take and the exception (if any)
     * that represents the failure.
     *
     * <p>Input validation contract:
     *
     * <ul>
     *   <li>{@code null} - throws {@link NullPointerException} with a message
     *       containing the word {@code "status"} to aid diagnostics.</li>
     *   <li>Empty or whitespace-only - throws {@link IllegalArgumentException};
     *       the COBOL runtime never returns whitespace status codes, so this
     *       always indicates a caller-side bug.</li>
     *   <li>Unknown 2-character code (e.g., {@code "AB"}, {@code "ZZ"}) -
     *       returns a {@link FileStatusResult} with
     *       {@link FileStatusAction#ABEND} carrying a generic
     *       {@link VsamException}. This preserves COBOL's "any other status
     *       triggers CEE3ABD" semantics: the caller knows to escalate without
     *       seeing the input rejected outright.</li>
     * </ul>
     *
     * @param statusCode the 2-character VSAM status code as returned by the
     *                   COBOL runtime (e.g., {@code "00"}, {@code "10"},
     *                   {@code "23"}). Must not be {@code null}, empty, or
     *                   whitespace-only.
     * @return a non-{@code null} {@link FileStatusResult} whose action and
     *         exception fields describe how the caller should proceed.
     * @throws NullPointerException     if {@code statusCode} is {@code null}
     * @throws IllegalArgumentException if {@code statusCode} is empty or
     *                                  contains only whitespace
     */
    public FileStatusResult map(String statusCode) {
        Objects.requireNonNull(statusCode,
            "status code must not be null (received null from caller)");

        if (statusCode.isEmpty() || statusCode.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "status code must not be empty or whitespace-only "
                    + "(received '" + statusCode + "')");
        }

        Entry entry = TABLE.get(statusCode);
        if (entry == null) {
            // Unknown status code: preserve COBOL's "any other status →
            // CEE3ABD" semantics by returning ABEND with a generic exception.
            String message = "unknown VSAM file-status code: '" + statusCode
                + "' (no entry in mapping table)";
            return new FileStatusResult(
                FileStatusAction.ABEND,
                new VsamException(statusCode, message));
        }

        return entry.toResult(statusCode);
    }

    /**
     * Builds the immutable lookup table. Each entry binds a status code to an
     * action and, optionally, an exception factory. Success codes have a
     * {@code null} exception factory so the resulting
     * {@link FileStatusResult#getException()} returns
     * {@link java.util.Optional#empty()}.
     *
     * <p>The table covers every status code documented in
     * {@code src/test/resources/fixtures/edge/status_code_mappings.csv}:
     * 29 codes in total. The mapping is the authoritative production contract
     * that the test class asserts against.
     */
    private static Map<String, Entry> buildTable() {
        Map<String, Entry> table = new HashMap<>();

        // ----- Success codes (CONTINUE, no exception) ---------------------
        table.put("00", Entry.success());
        table.put("02", Entry.success());
        table.put("05", Entry.success());
        table.put("97", Entry.success());

        // ----- End-of-file -------------------------------------------------
        table.put("10", Entry.of(
            FileStatusAction.END_OF_FILE,
            sc -> new EndOfFileException(
                "end-of-file reached on sequential VSAM read (status '"
                    + sc + "')")));

        // ----- Recoverable: log and continue ------------------------------
        table.put("04", Entry.of(
            FileStatusAction.LOG_AND_CONTINUE,
            sc -> new RecordLengthMismatchException(
                "record length does not match the FD declaration "
                    + "(VSAM status '" + sc + "')")));
        table.put("23", Entry.of(
            FileStatusAction.LOG_AND_CONTINUE,
            sc -> new RecordNotFoundException(
                "keyed read found no record with the requested key "
                    + "(VSAM status '" + sc + "')")));

        // ----- Fatal: ABEND with specific exception types -----------------
        table.put("14", Entry.of(
            FileStatusAction.ABEND,
            sc -> new RecordKeyOutOfRangeException(
                "requested key is outside the file's key range "
                    + "(VSAM status '" + sc + "')")));
        table.put("21", Entry.of(
            FileStatusAction.ABEND,
            sc -> new SequenceException(
                "sequential write violated the file's ascending-key "
                    + "invariant (VSAM status '" + sc + "')")));
        table.put("22", Entry.of(
            FileStatusAction.ABEND,
            sc -> new DuplicateKeyException(
                "write attempted to insert a duplicate key in a unique-key "
                    + "file (VSAM status '" + sc + "')")));
        table.put("24", Entry.of(
            FileStatusAction.ABEND,
            sc -> new BoundaryViolationException(
                "write would exceed the file's allocated extent "
                    + "(VSAM status '" + sc + "')")));
        table.put("30", Entry.of(
            FileStatusAction.ABEND,
            sc -> new IoException(
                "permanent I/O error (VSAM status '" + sc + "')")));
        table.put("34", Entry.of(
            FileStatusAction.ABEND,
            sc -> new FileFullException(
                "file is full and cannot accept additional records "
                    + "(VSAM status '" + sc + "')")));
        table.put("35", Entry.of(
            FileStatusAction.ABEND,
            sc -> new FileNotOpenException(
                "OPEN failed: file does not exist or is inaccessible "
                    + "(VSAM status '" + sc + "')")));
        table.put("37", Entry.of(
            FileStatusAction.ABEND,
            sc -> new IncompatibleFileException(
                "OPEN failed: file attributes are incompatible with the "
                    + "requested OPEN mode (VSAM status '" + sc + "')")));
        table.put("38", Entry.of(
            FileStatusAction.ABEND,
            sc -> new FileLockedException(
                "file is locked by another process "
                    + "(VSAM status '" + sc + "')")));
        table.put("39", Entry.of(
            FileStatusAction.ABEND,
            sc -> new ConflictingAttributesException(
                "runtime attributes conflict with catalog definition "
                    + "(VSAM status '" + sc + "')")));

        // Codes 41, 42, 43, 46 all share InvalidOperationException because they
        // describe variants of the same condition (operation invalid for the
        // current file state). The COBOL source treats them identically.
        Entry invalidOp41 = Entry.of(
            FileStatusAction.ABEND,
            sc -> new InvalidOperationException(
                "operation invalid for current file state "
                    + "(VSAM status '" + sc + "')"));
        table.put("41", invalidOp41);
        table.put("42", invalidOp41);
        table.put("43", invalidOp41);
        table.put("46", invalidOp41);

        // 90, 95, 96, 98, 99 use the base VsamException because the COBOL source
        // does not discriminate further and the CSV fixture confirms.
        Entry generic = Entry.of(
            FileStatusAction.ABEND,
            sc -> new VsamException(sc,
                "VSAM operation failed (status '" + sc + "')"));
        table.put("90", generic);
        table.put("95", generic);
        table.put("96", generic);
        table.put("98", generic);
        table.put("99", generic);

        // 91 is RACF/ACF2 access denial — distinct from java.lang.SecurityException
        table.put("91", Entry.of(
            FileStatusAction.ABEND,
            sc -> new SecurityException(
                "VSAM security profile denied the requested operation "
                    + "(VSAM status '" + sc + "')")));

        // 92 is a logic error — operation incompatible with current state
        table.put("92", Entry.of(
            FileStatusAction.ABEND,
            sc -> new VsamLogicException(
                "logic error: operation incompatible with current file state "
                    + "(VSAM status '" + sc + "')")));

        // 93 is a transient environmental failure (no buffers, no space)
        table.put("93", Entry.of(
            FileStatusAction.ABEND,
            sc -> new ResourceUnavailableException(
                "required system resource is unavailable "
                    + "(VSAM status '" + sc + "')")));

        return Collections.unmodifiableMap(table);
    }

    /**
     * Private lookup-table value. Each entry binds a {@link FileStatusAction}
     * to an optional factory that constructs the exception for a given status
     * code. Success entries have a {@code null} factory so the resulting
     * {@link FileStatusResult} carries no exception.
     */
    private static final class Entry {

        private final FileStatusAction action;
        private final Function<String, VsamException> exceptionFactory;

        private Entry(FileStatusAction action,
                      Function<String, VsamException> exceptionFactory) {
            this.action = action;
            this.exceptionFactory = exceptionFactory;
        }

        static Entry success() {
            return new Entry(FileStatusAction.CONTINUE, null);
        }

        static Entry of(FileStatusAction action,
                        Function<String, VsamException> exceptionFactory) {
            return new Entry(action, exceptionFactory);
        }

        FileStatusResult toResult(String statusCode) {
            if (exceptionFactory == null) {
                return new FileStatusResult(action);
            }
            return new FileStatusResult(action, exceptionFactory.apply(statusCode));
        }
    }
}
