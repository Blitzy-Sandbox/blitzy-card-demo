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

/**
 * The action a Spring Batch step (or any caller of a VSAM-backed I/O routine)
 * must take after receiving a translated {@link FileStatusResult} for a given
 * VSAM file-status code.
 *
 * <p>Each enum literal maps a category of VSAM status code semantics to an
 * actionable instruction that the migrated Java code can follow without
 * re-implementing the original COBOL {@code PERFORM 9999-ABEND-PROGRAM} /
 * {@code MOVE 'Y' TO END-OF-FILE} branching.
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>The four-state action enum captures the dispatch logic that every
 * VSAM-reading COBOL paragraph in the CardDemo source repository embeds inline.
 * See for example {@code CBACT01C} lines 92-115 which, on each {@code READ}, examines
 * {@code ACCTFILE-STATUS} and branches:
 * <ul>
 *   <li>{@code '00'} (success) — continue processing -> {@link #CONTINUE}</li>
 *   <li>{@code '10'} (EOF) — set {@code END-OF-FILE = 'Y'} -> {@link #END_OF_FILE}</li>
 *   <li>any other — invoke {@code 9999-ABEND-PROGRAM} -> {@link #ABEND}</li>
 * </ul>
 *
 * <p>{@code CBACT04C} lines 422-440 adds a fourth semantic:
 * <ul>
 *   <li>{@code '23'} (record not found on keyed read) — log the missing key, fall
 *       back to the {@code DEFAULT} disclosure group, and continue -> {@link #LOG_AND_CONTINUE}</li>
 * </ul>
 */
public enum FileStatusAction {

    /**
     * The operation succeeded. The caller proceeds with normal processing
     * (use the data that was read, persist the row that was written, etc.).
     *
     * <p>Maps to the COBOL pattern {@code IF STATUS = '00' -> MOVE 0 TO APPL-RESULT}.
     */
    CONTINUE,

    /**
     * The end-of-file marker was reached on a sequential read. The caller must
     * stop iterating and close the file. No exception is thrown — EOF is a
     * normal, expected terminal condition.
     *
     * <p>Maps to the COBOL pattern {@code IF STATUS = '10' -> MOVE 'Y' TO END-OF-FILE}
     * used across {@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C},
     * {@code CBCUS01C}, and {@code CBTRN02C} sequential read loops.
     */
    END_OF_FILE,

    /**
     * The operation did not succeed cleanly but processing may continue with
     * a compensating action: the caller should log the condition and either
     * skip the record, retry with default data, or otherwise recover.
     *
     * <p>Maps to the COBOL pattern in {@code CBACT04C} lines 422-440 where a
     * {@code '23'} (record not found) status triggers
     * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} and a retry against the
     * {@code DEFAULT} disclosure group entry — recoverable rather than fatal.
     */
    LOG_AND_CONTINUE,

    /**
     * The operation indicated a fatal error condition (file corruption, I/O
     * failure, structural violation, security denial, etc.). The caller must
     * abort processing immediately by raising the carried exception.
     *
     * <p>Maps to the COBOL pattern {@code PERFORM 9999-ABEND-PROGRAM} which
     * invokes the LE {@code CEE3ABD} service to terminate the program with a
     * user-supplied completion code. In the Java migration, this corresponds
     * to throwing the {@link FileStatusResult#getException() exception} carried
     * inside the result.
     */
    ABEND
}
