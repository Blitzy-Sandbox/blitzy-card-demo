/*
 * ******************************************************************
 * Program     : UserCreateResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for the user-add operation: the
 *               four business fields the screen displayed after the
 *               write, plus the source's own message - with every
 *               terminal, attribute and navigation field withheld and
 *               no credential component of any kind.
 * Source      : app/cbl/COUSR01C.cbl (299 lines) over mapset COUSR01,
 *               :L240-L248 (WRITE-USER-SEC-FILE),
 *               :L254 (DFHGREEN on the success arm),
 *               :L255-L257 (the ' has been added ...' STRING),
 *               :L263 ('User ID already exist...', sic) @ 7756d89
 * Source      : app/cpy-bms/COUSR01.CPY (12 input fields: FNAMEI
 *               PIC X(20), LNAMEI PIC X(20), USERIDI PIC X(8),
 *               PASSWDI PIC X(8) - inbound only, USRTYPEI PIC X(1),
 *               ERRMSGI PIC X(78)) @ 7756d89
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
 * The answer to the user-add operation, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It is the API-owned projection of what the user-add service assembles. That service returns a record
 * faithful to the 3270 turn: the six recurring header fields, the message-attribute byte the source moved to
 * {@code ERRMSGC} on the success arm at {@code app/cbl/COUSR01C.cbl:L254}, the field the cursor was parked on,
 * and the advisory target an {@code EXEC CICS XCTL} would have transferred to. All of that is an
 * <strong>in-process contract</strong>. Publishing it directly made colour, cursor and navigation state part of
 * the REST contract, which was reported as a High-severity API-contract defect with a CWE-200 aspect. This type
 * is the boundary that stops it: four business fields and the source's own message, and nothing else.</p>
 *
 * <h2>No credential and no digest can travel back</h2>
 *
 * <p>{@code PASSWDI PIC X(8)} at {@code app/cpy-bms/COUSR01.CPY:78} is an <em>input</em> field of the map and
 * the source never sent it back. This type declares no component for it and none for a hash, so there is
 * nothing to remember to blank and no accessor a reflective renderer could find. The eight-character legacy
 * field becomes a sixty-character BCrypt column in the store and never a response member.</p>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <ul>
 *   <li><b>The message colour.</b> {@code ERRMSGC} set to {@code DFHGREEN} at
 *       {@code app/cbl/COUSR01C.cbl:L254} told a 3270 to paint the line green. A client decides its own
 *       presentation; the status code already distinguishes success from refusal.</li>
 *   <li><b>The cursor field and the navigation target.</b> {@code MOVE -1 TO} a length item parked a cursor,
 *       and {@code CDEMO-TO-PROGRAM} named the program a transfer of control would reach. A client fills its
 *       own form and navigates by URL.</li>
 *   <li><b>The six recurring header fields.</b> Screen chrome, two of which are the server's own clock
 *       reading.</li>
 *   <li><b>Any credential or digest.</b> See above.</li>
 * </ul>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> Built by {@link #of}, from the four business values and the message the service produced.
 * <b>Outputs.</b> Serialised by Jackson. <b>Side effects.</b> None - this type validates, normalises, maps and
 * hashes nothing. <b>Failure modes.</b> Nothing can fail: every component may legitimately be null, because
 * the source blanks its input fields on the success arm.</p>
 *
 * @param userId the eight-character identifier of the created user, {@code USERIDI PIC X(8)} at
 *     {@code app/cpy-bms/COUSR01.CPY:72}. Note the divergence across the package: this map spells the field
 *     {@code USERIDI} where {@code COUSR02.CPY} and {@code COUSR03.CPY} spell it {@code USRIDINI}
 * @param firstName the given name, {@code FNAMEI PIC X(20)} at {@code :60}; blank on the success arm, because
 *     the source clears its input fields once the record is written
 * @param lastName the family name, {@code LNAMEI PIC X(20)} at {@code :66}, on the same terms
 * @param userType the raw one-character type code, {@code USRTYPEI PIC X(1)} at {@code :84}, carried verbatim
 * @param errorMessage {@code ERRMSGI PIC X(78)} at {@code :90}, relayed byte for byte. The source uses this one
 *     line for both outcomes, so on success it carries the {@code ' has been added ...'} result of
 *     {@code app/cbl/COUSR01C.cbl:L255-L257} rather than an error; null or blank when the turn set none
 */
public record UserCreateResponse(
        String userId,
        String firstName,
        String lastName,
        String userType,
        String errorMessage) {

    /**
     * The component names of the service's own screen record that this type must never carry.
     *
     * <p>Published so the contract is machine-checkable rather than merely documented: a test asserts that no
     * component of this record bears any of these names, so returning terminal state to the wire would fail
     * the build. {@code password} and {@code passwordHash} are listed although the service's record does not
     * declare them either, because the one member that must never appear here is worth naming explicitly.</p>
     */
    public static final List<String> WITHHELD_COMPONENTS = List.of(
            "messageColour",
            "cursorField",
            "navigationTarget",
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "password",
            "passwordHash");

    /**
     * Builds the response from the four business values and the turn's message.
     *
     * <p>Every argument may be null, because the source blanks its input fields on the success arm and sets no
     * message on some refusal arms. Nothing is substituted for an absent value: absence is a legitimate state
     * of a screen field and is preserved rather than collapsed into a blank.</p>
     *
     * @param userId the identifier of the created user, or null when the turn carried none
     * @param firstName the given name as the screen was left, or null
     * @param lastName the family name as the screen was left, or null
     * @param userType the one-character type code, or null
     * @param errorMessage the turn's message, or null when it set none
     * @return the response; never null
     */
    public static UserCreateResponse of(final String userId, final String firstName, final String lastName,
                                        final String userType, final String errorMessage) {
        return new UserCreateResponse(userId, firstName, lastName, userType, errorMessage);
    }

    /**
     * Returns a diagnostic rendering that identifies no user.
     *
     * <p>Three components identify a person directly, so the rendering a record generates for itself would
     * publish an identifier and a name into any log line, exception message or debugger frame that
     * interpolated it. Nothing but the type name is emitted, so the output cannot vary with the platform
     * locale either.</p>
     *
     * @return the type name only, never any user data
     */
    @Override
    public String toString() {
        return "UserCreateResponse[withheld]";
    }
}
