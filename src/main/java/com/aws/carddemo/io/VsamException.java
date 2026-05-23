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
 * Base class for all checked-style VSAM I/O exceptions in the CardDemo migration.
 *
 * <p>Extends {@link RuntimeException} (rather than {@code java.io.IOException})
 * so callers do not have to declare it in method signatures of Spring Batch
 * {@code ItemReader} / {@code ItemProcessor} / {@code ItemWriter} implementations.
 * The Spring Batch framework wraps {@code RuntimeException} subclasses into
 * {@code StepExecutionException} as part of its normal error-handling pipeline,
 * preserving the COBOL {@code PERFORM 9999-ABEND-PROGRAM} semantics: any
 * unrecoverable I/O error terminates the current step and bubbles up.
 *
 * <p>Subclasses correspond to specific VSAM file-status codes:
 * <ul>
 *   <li>{@link EndOfFileException} — status {@code '10'}</li>
 *   <li>{@link RecordNotFoundException} — status {@code '23'}</li>
 *   <li>{@link DuplicateKeyException} — status {@code '22'}</li>
 *   <li>{@link FileNotOpenException} — status {@code '35'}</li>
 *   <li>and so on (see {@link FileStatusMapper} for the full mapping table)</li>
 * </ul>
 *
 * <p>Generic {@code VsamException} is used directly for status codes that map
 * to an ABEND but for which no more specific subclass exists ({@code '90'},
 * {@code '95'}, {@code '96'}, {@code '98'}, {@code '99'}).
 *
 * @see FileStatusMapper
 */
public class VsamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String statusCode;

    /**
     * Creates a {@code VsamException} with the given message and originating
     * VSAM status code.
     *
     * @param statusCode the 2-character VSAM file-status code that triggered
     *                   this exception (e.g., {@code "90"}, {@code "99"})
     * @param message    a human-readable diagnostic message describing the
     *                   failure category
     */
    public VsamException(String statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Returns the originating VSAM file-status code that triggered this
     * exception. Useful for diagnostic logging and for differentiating between
     * sibling status codes that map to the same exception class
     * (e.g., {@code '41'}, {@code '42'}, {@code '43'}, {@code '46'} all map
     * to {@link InvalidOperationException}).
     *
     * @return the 2-character status code
     */
    public String getStatusCode() {
        return statusCode;
    }
}
