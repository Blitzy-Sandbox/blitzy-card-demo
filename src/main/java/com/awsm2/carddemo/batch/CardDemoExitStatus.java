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
package com.awsm2.carddemo.batch;

import org.springframework.batch.core.ExitStatus;

/**
 * Centralized mapping of COBOL {@code RETURN-CODE} values to Spring
 * Batch {@link ExitStatus} constants used across all CardDemo Spring
 * Batch jobs.
 *
 * <p><b>// Replaces: COBOL RETURN-CODE values + JCL COND= condition
 * codes</b> — the mainframe JES2 step-level condition codes are
 * reproduced here as {@link ExitStatus} values that Spring Batch
 * propagates to the AWS Batch container exit code, which Step Functions
 * reads as the Task state outcome per AAP &sect;0.6.3.</p>
 *
 * <h2>RETURN-CODE Parity (AAP &sect;0.7.1 / F-CP6-Combine-04)</h2>
 *
 * <table>
 *   <caption>COBOL RETURN-CODE → Spring Batch ExitStatus mapping</caption>
 *   <tr><th>COBOL RC</th>
 *       <th>Spring Batch {@link ExitStatus}</th>
 *       <th>AWS Batch container exit</th>
 *       <th>Step Functions outcome</th></tr>
 *   <tr><td>0</td>
 *       <td>{@link ExitStatus#COMPLETED} (exit code "COMPLETED")</td>
 *       <td>0</td>
 *       <td>Task succeeds</td></tr>
 *   <tr><td>4</td>
 *       <td>{@link #COMPLETED_WITH_REJECTS} (exit code
 *           "COMPLETED_WITH_REJECTS")</td>
 *       <td>0 (job completed but with rejects)</td>
 *       <td>Task succeeds; downstream Choice state may branch on the
 *           non-zero reject count surfaced via SNS/SQS</td></tr>
 *   <tr><td>8</td>
 *       <td>{@link ExitStatus#FAILED} (exit code "FAILED")</td>
 *       <td>non-zero</td>
 *       <td>Task fails; Step Functions Catch / Retry policy triggers</td></tr>
 * </table>
 *
 * <p>COBOL programs in the CardDemo source emit {@code RETURN-CODE = 0}
 * for clean completion, {@code RETURN-CODE = 4} when any records were
 * rejected (e.g., {@code CBTRN02C.cbl} L229-L230 "MOVE 4 TO RETURN-CODE"
 * when {@code WS-REJECT-COUNT > 0}), and {@code RETURN-CODE = 8}
 * (typically via {@code GOBACK} after an explicit
 * {@code MOVE 8 TO RETURN-CODE} or via CICS {@code ABEND}). The Java
 * targets preserve this convention so the AWS Batch / Step Functions
 * runtime model behaves identically to the JCL / JES2 step-condition
 * model.</p>
 *
 * <h2>Centralization Rationale</h2>
 *
 * <p>Every Spring Batch job in {@code com.awsm2.carddemo.batch} that
 * supports a non-zero reject count (POSTTRAN, INTCALC, CREASTMT, COMBTRAN,
 * TRANREPT) wires the same {@code afterStep} / {@code JobExecutionListener}
 * logic that consults the step's reject count and selects the
 * appropriate {@link ExitStatus}. Centralizing the constants here
 * ensures all jobs emit the same exit-code strings to AWS Batch and Step
 * Functions, which is required for Step Functions Choice states to
 * branch deterministically on the upstream Task outcome.</p>
 *
 * <h2>Class Style</h2>
 *
 * <p>This is a {@code final} utility class with a {@code private}
 * constructor — the class is never instantiated; consumers reference
 * the {@code public static final} constants directly. Adding new
 * exit-status constants in the future should be done here to maintain
 * the single source of truth.</p>
 *
 * @see ExitStatus
 * @see com.awsm2.carddemo.batch.CombineTransactionsJob
 */
public final class CardDemoExitStatus {

    /**
     * Spring Batch {@link ExitStatus} representing a job that completed
     * its main pipeline successfully but with one or more rejects /
     * skipped items.
     *
     * <p><b>// Replaces: COBOL RETURN-CODE = 4 (e.g., CBTRN02C.cbl L229-L230
     * "MOVE 4 TO RETURN-CODE")</b> — the JES2 condition code that
     * indicated "completed with warnings" or "completed with rejected
     * records".</p>
     *
     * <p>The exit code string {@code "COMPLETED_WITH_REJECTS"} is
     * preserved verbatim from the COBOL audit-trail emission convention.
     * Step Functions Choice states branch on this string via the
     * {@code $.Cause} or {@code $.Output.exitCode} payload (depending
     * on the integration pattern selected in the state machine
     * definition).</p>
     */
    public static final ExitStatus COMPLETED_WITH_REJECTS =
            new ExitStatus("COMPLETED_WITH_REJECTS",
                    "Job completed but with one or more rejected records (COBOL RETURN-CODE = 4)");

    /**
     * The COBOL {@code RETURN-CODE} value equivalent to
     * {@link ExitStatus#COMPLETED} (clean success).
     *
     * <p>Provided as a named constant so service-layer code that builds
     * a {@code PostingResult} or equivalent record can reference the
     * value symbolically rather than embedding the literal {@code 0}.</p>
     */
    public static final int RETURN_CODE_COMPLETED = 0;

    /**
     * The COBOL {@code RETURN-CODE} value equivalent to
     * {@link #COMPLETED_WITH_REJECTS}.
     *
     * <p>Provided as a named constant so service-layer code that
     * computes the return code can reference the value symbolically.</p>
     */
    public static final int RETURN_CODE_WITH_REJECTS = 4;

    /**
     * The COBOL {@code RETURN-CODE} value equivalent to
     * {@link ExitStatus#FAILED}.
     */
    public static final int RETURN_CODE_FAILED = 8;

    /**
     * Maps a COBOL {@code RETURN-CODE} integer to the corresponding
     * Spring Batch {@link ExitStatus}.
     *
     * @param returnCode the COBOL RETURN-CODE value (0, 4, or 8)
     * @return {@link ExitStatus#COMPLETED} for 0;
     *         {@link #COMPLETED_WITH_REJECTS} for 4;
     *         {@link ExitStatus#FAILED} for 8;
     *         {@link ExitStatus#UNKNOWN} for any other value
     */
    public static ExitStatus fromReturnCode(int returnCode) {
        return switch (returnCode) {
            case RETURN_CODE_COMPLETED -> ExitStatus.COMPLETED;
            case RETURN_CODE_WITH_REJECTS -> COMPLETED_WITH_REJECTS;
            case RETURN_CODE_FAILED -> ExitStatus.FAILED;
            default -> ExitStatus.UNKNOWN;
        };
    }

    /**
     * Private constructor — utility class, never instantiated.
     */
    private CardDemoExitStatus() {
        // No instantiation.
    }
}
