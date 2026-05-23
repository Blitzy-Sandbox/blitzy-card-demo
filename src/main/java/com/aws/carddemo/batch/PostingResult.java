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
package com.aws.carddemo.batch;

import java.util.Objects;

/**
 * Sealed interface representing the outcome of validating and routing a
 * single DALYTRAN-FILE record through the {@code transactionPostingStep}.
 *
 * <p>This type is the {@link org.springframework.batch.item.ItemProcessor}
 * output type for the Spring Batch posting pipeline, replacing the simple
 * {@link String} previously emitted by the pass-through processor with a
 * structured tagged union that the
 * {@code TransactionPostingDualWriter} chunk writer fans out to two
 * separate output files (the TRANFILE successful-posting stream and the
 * DALYREJS validation-trailer reject stream).
 *
 * <p>The two variants correspond exactly to CBTRN02C's two write paths:
 * <ul>
 *   <li>{@link Posted} — the COBOL {@code 2900-WRITE-TRANSACTION-FILE}
 *       path. The original 350-byte CVTRA05Y-layout record is written
 *       verbatim to the TRANFILE output, preserving byte-for-byte
 *       parity with the {@code DALYTRAN-RECORD} bytes (including the
 *       blank {@code TRAN-PROC-TS} field in the captured baseline; the
 *       Spring Batch pass-through writer does not stamp a processing
 *       timestamp because doing so would break byte-equality with the
 *       captured COBOL reference output).</li>
 *   <li>{@link Reject} — the COBOL {@code 2500-WRITE-REJECT-REC} path.
 *       The original 350-byte transaction data plus the 80-character
 *       {@code VALIDATION-TRAILER} ({@code WS-VALIDATION-FAIL-REASON}
 *       4-digit code + {@code WS-VALIDATION-FAIL-REASON-DESC} 76-char
 *       description) form a 430-byte reject record.</li>
 * </ul>
 *
 * <p>Per AAP §0.10.4 (Immutable Boundaries), the byte layouts are fixed:
 * the {@link Posted} payload is the verbatim 350-byte input line; the
 * {@link Reject} payload is the 350-byte original record concatenated
 * with a {@code String.format("%04d", reasonCode)} + 76-char
 * space-padded {@code description}.
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule), this is a structural
 * tagged-union DTO with no business logic — the {@code reasonCode}
 * value and the {@code description} string are computed by the
 * production {@link TransactionValidationProcessor} (which the unit
 * tests exercise directly via {@code rejectCodeFor} and
 * {@code rejectDescriptionFor}); this type just transports those values
 * from the processor to the writer.
 *
 * @see TransactionValidationProcessor
 * @see TransactionPostingProcessor
 * @see com.aws.carddemo.batch.config.BatchJobConfig#transactionPostingStep
 */
public sealed interface PostingResult permits PostingResult.Posted, PostingResult.Reject {

    /**
     * Returns {@code true} when this result represents a record that
     * passed the 4-stage validation cascade and is destined for the
     * TRANFILE output.
     *
     * @return {@code true} iff this is a {@link Posted} record
     */
    boolean isPosted();

    /**
     * Returns the byte content this record should write to its output
     * file, exclusive of the trailing line separator (which the
     * {@code FlatFileItemWriter} appends per Spring Batch convention).
     *
     * <p>For {@link Posted} records this is the original 350-byte
     * DALYTRAN line bytes; for {@link Reject} records this is the
     * 430-byte concatenation of the original line plus the
     * validation trailer.
     *
     * @return the bytes (as a String — Spring Batch's
     *         {@link org.springframework.batch.item.file.transform.PassThroughLineAggregator}
     *         transports byte content as ISO-8859-1-decoded strings)
     */
    String content();

    /**
     * The "happy path" variant — a daily-transaction record that
     * passed the 4-stage validation cascade.
     *
     * <p>The {@code line} field is the verbatim 350-byte DALYTRAN
     * record read from the FlatFileItemReader. The Spring Batch pass-
     * through writer writes this string back to the TRANFILE output
     * unchanged, preserving byte-for-byte parity with the captured
     * baseline {@code posted.txt} (the captured reference is
     * byte-identical to the first 50 records of {@code dailytran.txt},
     * including blank {@code TRAN-PROC-TS} fields at positions
     * 305-330).
     *
     * @param line the original 350-byte DALYTRAN line (must not be null
     *             or empty)
     */
    record Posted(String line) implements PostingResult {
        /**
         * Compact-canonical constructor that null-checks the line.
         *
         * @throws NullPointerException if {@code line} is {@code null}
         */
        public Posted {
            Objects.requireNonNull(line, "line must not be null");
        }

        @Override
        public boolean isPosted() {
            return true;
        }

        @Override
        public String content() {
            return line;
        }
    }

    /**
     * The "validation reject" variant — a daily-transaction record
     * that failed the 4-stage validation cascade.
     *
     * <p>The {@code line} field is the verbatim 350-byte DALYTRAN
     * record; the {@code reasonCode} is one of the
     * {@code TransactionValidationProcessor.REASON_*} constants
     * (100-103); the {@code description} is the COBOL-equivalent
     * 76-character description string (space-padded on the right).
     *
     * <p>The {@link #content()} method concatenates the three pieces
     * to produce the 430-byte CBTRN02C
     * {@code REJECT-RECORD} layout:
     * <pre>
     *   01 REJECT-RECORD.
     *      05 REJECT-TRAN-DATA              PIC X(350).
     *      05 VALIDATION-TRAILER.
     *         10 VALIDATION-FAIL-REASON     PIC 9(04).
     *         10 VALIDATION-FAIL-REASON-DESC PIC X(76).
     * </pre>
     *
     * @param line        the original 350-byte DALYTRAN line (must not
     *                    be null or empty)
     * @param reasonCode  the {@code WS-VALIDATION-FAIL-REASON} code
     *                    (typically 100, 101, 102, or 103)
     * @param description the 76-character description string (will be
     *                    truncated or space-padded to 76 chars)
     */
    record Reject(String line, int reasonCode, String description) implements PostingResult {
        /**
         * Compact-canonical constructor that null-checks the
         * {@code line} and {@code description}.
         *
         * @throws NullPointerException if {@code line} or
         *                              {@code description} is null
         */
        public Reject {
            Objects.requireNonNull(line, "line must not be null");
            Objects.requireNonNull(description, "description must not be null");
        }

        @Override
        public boolean isPosted() {
            return false;
        }

        @Override
        public String content() {
            // 4-digit reason code zero-padded; 76-char description
            // space-padded on the right (truncated if longer). The
            // concatenation produces the 430-byte CBTRN02C
            // REJECT-RECORD layout exactly.
            final String codeStr = String.format("%04d", reasonCode);
            final String descPadded = padRightTo(description, 76);
            return line + codeStr + descPadded;
        }

        /**
         * Pad {@code value} on the right with spaces until it reaches
         * exactly {@code width} characters, or truncate if longer.
         */
        private static String padRightTo(String value, int width) {
            if (value.length() >= width) {
                return value.substring(0, width);
            }
            final StringBuilder sb = new StringBuilder(width);
            sb.append(value);
            while (sb.length() < width) {
                sb.append(' ');
            }
            return sb.toString();
        }
    }
}
