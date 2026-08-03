/*
 * ******************************************************************
 * Program     : AccountUpdateResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for the account update: which of
 *               the legacy outcomes the turn reached, whether anything
 *               was written, and the two byte-exact screen messages -
 *               and nothing else.
 * Source      : app/cbl/COACTUPC.cbl (4,236 lines) over mapset COACTUP
 *               :L2606-L2615 (the post-write dispatch and its
 *               ACUP-CHANGE-ACTION markers),
 *               :L3888-L4105 (9600-WRITE-PROCESSING, the seven-step
 *               write sequence and its four outcome flags),
 *               :L517-L523 (the outcome flags and their literals)
 *               @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.dto;

/**
 * The outcome of an account update, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It reports what happened and nothing more. The service's own result type cannot be this response, for
 * three independent reasons: it carries the whole submitted request including both snapshot groups, so
 * returning it would echo the caller's social security number, date of birth, government identifier and
 * electronic-funds identifier straight back; it carries the per-field 3270 presentation instructions - field
 * attribute, colour, marker and cursor - which describe a terminal that no longer exists; and it carries the
 * CICS navigation quadruple naming the transaction and program control would have passed to, which a client
 * addresses by URL instead.</p>
 *
 * <h2>Why the record is not echoed</h2>
 *
 * <p>A write response deliberately does not return the stored account. A client that wants the stored values
 * reads the account, which also yields a fresh sealed snapshot for its next edit - so the read is not an extra
 * round trip so much as the correct next step. Echoing the record here would mean either re-emitting the
 * caller's own submission, which proves nothing about what was stored, or re-reading inside the write, which
 * doubles the work and still cannot issue a snapshot bound to the post-write state without a second read.</p>
 *
 * <h2>{@code changeAction} is the member to branch on</h2>
 *
 * <p>It is the {@code ACUP-CHANGE-ACTION} marker of {@code app/cbl/COACTUPC.cbl:L2606-L2615}, reported by
 * name. Keeping it distinguishable is what preserves the source's outcome vocabulary over HTTP: a lock that
 * could not be taken, a record that changed under the caller, a write that failed after locking and a change
 * that was never confirmed are four different things, and collapsing them into one status would lose
 * information the 3270 screen displayed.</p>
 *
 * <h2>A documented legacy trap survives here</h2>
 *
 * <p>A customer read-for-update failure sets {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} at
 * {@code app/cbl/COACTUPC.cbl:L3939} and returns having written nothing, yet the dispatch at
 * {@code :L2606-L2615} never tests that condition, so the outcome falls through to the success marker. That
 * defect is reproduced because parity is the contract. The consequence for a client is precise and must be
 * stated rather than hidden: {@link #applied} being true does <b>not</b> by itself prove both records were
 * written, and a client needing certainty must read {@link #errorMessage}, which carries the source's own
 * text. The condition is additionally logged at {@code WARN} by the operation.</p>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> The four components below, supplied by the operation. <b>Outputs.</b> Serialised by
 * Jackson; every component is a JSON property and there are no others. <b>Side effects.</b> None; the type is
 * an immutable record. <b>Failure modes.</b> None: every failure that could occur has already been raised as a
 * typed exception before this type is built.</p>
 *
 * @param accountId the account the update addressed, echoed so a response stands on its own; may be null only
 *     when the submitted map carried no identifier, which the service refuses before reaching here
 * @param changeAction the {@code ACUP-CHANGE-ACTION} outcome by name, never null
 * @param applied whether the dispatch reported a completed change. Read the class note above before treating
 *     it as proof that both records were written
 * @param informationMessage {@code WS-INFO-MSG PIC X(40)} as the source left it, relayed byte for byte; may be
 *     null when the turn set none
 * @param errorMessage {@code WS-RETURN-MSG PIC X(75)} as the source left it, on the same terms; may be null
 */
public record AccountUpdateResponse(
        String accountId,
        String changeAction,
        boolean applied,
        String informationMessage,
        String errorMessage) {
}
