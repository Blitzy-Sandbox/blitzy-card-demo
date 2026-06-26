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
package com.carddemo.batch.processor;

import com.carddemo.entity.Transaction;
import com.carddemo.enums.RejectReasonCode;

/**
 * Outcome of posting a single staged daily transaction in the {@code POSTTRAN}
 * step (migration of COBOL {@code CBTRN02C} at source commit {@code 27d6c6f}).
 *
 * <p>The legacy posting engine produced <em>two</em> independent outputs per
 * record: a posted transaction written to the transaction master
 * ({@code 2900-WRITE-TRANSACTION}) <strong>or</strong> a 430-byte reject record
 * written to the {@code DALYREJS} rejects dataset ({@code 2500-WRITE-REJECT-REC}).
 * A Spring Batch {@code ItemProcessor} that returned {@code null} to skip a
 * rejected item would <em>drop</em> the reject output entirely; this sealed type
 * preserves both outputs by making the processor emit a {@code PostingResult} for
 * every record (never {@code null}). A downstream classifying writer then routes
 * each result to the correct sink, reproducing the legacy dual-output behavior
 * without losing the {@code DALYREJS} side-channel.</p>
 *
 * <p>The hierarchy is sealed so the routing writer can switch exhaustively over
 * the two cases with no default branch:</p>
 * <ul>
 *   <li>{@link Posted} &mdash; a valid record; carries the {@link Transaction}
 *       to persist to the transaction master.</li>
 *   <li>{@link Rejected} &mdash; a failed record; carries the exact 350-byte
 *       {@code CVTRA06Y DALYTRAN-RECORD} image and the {@link RejectReasonCode}
 *       so the rejects writer can assemble the byte-exact 430-byte reject
 *       record.</li>
 * </ul>
 *
 * <p>{@link Rejected} intentionally carries the reconstructed record image as a
 * plain {@link String} rather than a writer-specific type, so this processor-side
 * abstraction has no dependency on the writer layer.</p>
 */
public sealed interface PostingResult permits PostingResult.Posted, PostingResult.Rejected {

    /**
     * A successfully posted daily transaction.
     *
     * @param transaction the transaction to persist to the transaction master
     *                    ({@code CVTRA05Y TRAN-RECORD}); never {@code null}
     */
    record Posted(Transaction transaction) implements PostingResult {
    }

    /**
     * A rejected daily transaction destined for the {@code DALYREJS} rejects
     * dataset.
     *
     * @param originalRecordImage the exact 350-byte {@code CVTRA06Y
     *                            DALYTRAN-RECORD} image of the rejected input
     *                            record; never {@code null}
     * @param reason              the validation-failure reason that classifies
     *                            the rejection; never {@code null} and never
     *                            {@link RejectReasonCode#NONE}
     */
    record Rejected(String originalRecordImage, RejectReasonCode reason) implements PostingResult {
    }
}
