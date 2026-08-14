/*
 * ******************************************************************
 * Program     : UserUpdateResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for the user-update operation: the
 *               four business fields, the source's own message and
 *               whether the rewrite actually happened - with every
 *               terminal, attribute, transfer and navigation field
 *               withheld and no credential component of any kind.
 * Source      : app/cbl/COUSR02C.cbl (414 lines) over mapset COUSR02,
 *               :L176 (UPDATE-USER-INFO),
 *               :L219-L233 (the four change predicates that set
 *               WS-USR-MODIFIED), :L236 (the write it gates),
 *               :L322-L331 (READ ... UPDATE), :L360-L366 (REWRITE),
 *               :L241/:L338/:L371 (DFHRED / DFHNEUTR / DFHGREEN)
 *               @ 7756d89
 * Source      : app/cpy-bms/COUSR02.CPY (12 input fields: USRIDINI
 *               PIC X(8), FNAMEI PIC X(20), LNAMEI PIC X(20), PASSWDI
 *               PIC X(8) - inbound only, USRTYPEI PIC X(1), ERRMSGI
 *               PIC X(78)) @ 7756d89
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

import java.util.List;

/**
 * The answer to the user-update operation, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It is the API-owned projection of what the user-update service assembles. That service returns a record
 * faithful to the 3270 turn - the six recurring header fields, the attribute byte moved to {@code ERRMSGC}
 * ({@code DFHRED} at {@code app/cbl/COUSR02C.cbl:L241}, {@code DFHNEUTR} at {@code :L338},
 * {@code DFHGREEN} at {@code :L371}), the cursor field, the advisory navigation target and whether the turn
 * reached {@code RETURN-TO-PREV-SCREEN} - and that record is an <strong>in-process contract</strong>.
 * Publishing it directly made colour, cursor, transfer and navigation state part of the REST contract, which
 * was reported as a High-severity API-contract defect with a CWE-200 aspect. This type is the boundary that
 * stops it.</p>
 *
 * <h2>Why {@link #updateApplied} is a business field and not terminal state</h2>
 *
 * <p>{@code WS-USR-MODIFIED} is set by the four change predicates at {@code app/cbl/COUSR02C.cbl:L219},
 * {@code :L223}, {@code :L229} and {@code :L233}, and it is what decides the rewrite at {@code :L236}: when no
 * field differs the source writes nothing at all and answers with its "please modify" advisory instead. That is
 * an outcome of the operation rather than a description of a screen, and a caller that cannot tell "saved" from
 * "nothing to save" has lost behaviour the legacy screen conveyed. It is therefore published, under a name that
 * says what it means to a client rather than what the working-storage switch was called.</p>
 *
 * <h2>No credential and no digest can travel back</h2>
 *
 * <p>{@code PASSWDI PIC X(8)} is an input field of the map. The source additionally moved the <em>stored</em>
 * credential onto that screen field at {@code app/cbl/COUSR02C.cbl:L169}; reproducing that echo is a labelled
 * deviation the service documents, and this type honours it by shape rather than by remembering to blank a
 * member: no component exists that could carry a credential, a digest, a placeholder resembling one, or a
 * masked-but-present value.</p>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <ul>
 *   <li><b>The message colour.</b> Three attribute bytes told a 3270 how to paint one line. The status code
 *       and {@link #updateApplied} carry the same distinction to a client.</li>
 *   <li><b>The cursor field, the navigation target and the transfer flag.</b> Terminal and routing state; a
 *       client navigates by URL, which is also why the two attention identifiers that both save - PF3 at
 *       {@code app/cbl/COUSR02C.cbl:L112} and PF5 at {@code :L122} - collapse into one operation.</li>
 *   <li><b>The six recurring header fields.</b> Screen chrome, two of which are the server's own clock
 *       reading.</li>
 *   <li><b>Any credential or digest.</b> See above.</li>
 * </ul>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> Built by {@link #of}. <b>Outputs.</b> Serialised by Jackson. <b>Side effects.</b> None -
 * this type compares nothing and decides nothing; the change detection happened in the service, under the
 * exclusive hold its read acquired. <b>Failure modes.</b> Nothing can fail: every text component may
 * legitimately be null.</p>
 *
 * @param userId the eight-character identifier of the updated user, {@code USRIDINI PIC X(8)}
 * @param firstName the given name as it now stands, {@code FNAMEI PIC X(20)}, projected from
 *     {@code SEC-USR-FNAME PIC X(20)}
 * @param lastName the family name as it now stands, {@code LNAMEI PIC X(20)}, from
 *     {@code SEC-USR-LNAME PIC X(20)}
 * @param userType the raw one-character type code, {@code USRTYPEI PIC X(1)}, from
 *     {@code SEC-USR-TYPE PIC X(01)}, carried verbatim
 * @param errorMessage {@code ERRMSGO PIC X(78)}, the working message copied at
 *     {@code app/cbl/COUSR02C.cbl:L270} and relayed byte for byte. The source uses this one line for every
 *     outcome, so it carries the updated caption, the "please modify" advisory or a refusal indifferently;
 *     null or blank when the turn set none
 * @param updateApplied whether the record was actually rewritten - the transcription of
 *     {@code WS-USR-MODIFIED} as it stood at the write decision of {@code app/cbl/COUSR02C.cbl:L236}. False
 *     means every submitted field equalled the stored one and the source deliberately wrote nothing
 */
public record UserUpdateResponse(
        String userId,
        String firstName,
        String lastName,
        String userType,
        String errorMessage,
        boolean updateApplied) {

    /**
     * The component names of the service's own screen record that this type must never carry.
     *
     * <p>Published so the contract is machine-checkable rather than merely documented: a test asserts that no
     * component of this record bears any of these names. {@code password} and {@code passwordHash} are named
     * explicitly even though the service's record declares neither, because they are the two members whose
     * appearance here would matter most.</p>
     */
    public static final List<String> WITHHELD_COMPONENTS = List.of(
            "messageColour",
            "cursorField",
            "navigationTarget",
            "transferRequested",
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "password",
            "passwordHash");

    /**
     * Builds the response from the four business values, the turn's message and the write decision.
     *
     * <p>Every text argument may be null, and none is substituted: absence is a legitimate state of a screen
     * field and is preserved rather than collapsed into a blank.</p>
     *
     * @param userId the identifier of the updated user, or null when the turn carried none
     * @param firstName the given name as it now stands, or null
     * @param lastName the family name as it now stands, or null
     * @param userType the one-character type code, or null
     * @param errorMessage the turn's message, or null when it set none
     * @param updateApplied whether the record was rewritten
     * @return the response; never null
     */
    public static UserUpdateResponse of(final String userId, final String firstName, final String lastName,
                                        final String userType, final String errorMessage,
                                        final boolean updateApplied) {
        return new UserUpdateResponse(userId, firstName, lastName, userType, errorMessage, updateApplied);
    }

    /**
     * Returns a diagnostic rendering that identifies no user.
     *
     * <p>Three components identify a person directly, so only the write decision is emitted - it names no
     * person and is the one member worth having in a log line. There is no numeric or date formatting in the
     * result, so the output cannot vary with the platform locale.</p>
     *
     * @return the type name and the write decision only, never any user data
     */
    @Override
    public String toString() {
        return "UserUpdateResponse[updateApplied=" + this.updateApplied + "]";
    }
}
