/*
 * ******************************************************************
 * Program     : UserListResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for one page of the user list:
 *               the business columns the 3270 screen displayed, the
 *               paging cursor a stateless caller echoes back, and the
 *               screen's own advisory message - with every terminal,
 *               navigation and selector field withheld.
 * Source      : app/cbl/COUSR00C.cbl (695 lines) over mapset COUSR00,
 *               :L57 (USER-REC OCCURS 10 TIMES),
 *               :L68-L71 (CDEMO-CU00-USRID-FIRST / -USRID-LAST /
 *               -PAGE-NUM / -NEXT-PAGE-FLG),
 *               :L320-L321 (the page increment guarded by WS-IDX > 1)
 *               @ 7756d89
 * Source      : app/cpy-bms/COUSR00.CPY (59 input fields, USRID01I
 *               through USRID10I PIC X(8), ERRMSGI PIC X(78))
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

import java.util.List;
import java.util.Objects;

/**
 * One page of the user list, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It is the API-owned projection of what the user-list service assembles. The service produces a record
 * that is faithful to the 3270 turn - it carries the six recurring header fields, the erase and send counters,
 * the attention-identifier outcome, the cursor field, the advisory navigation target and the row selector,
 * because those are what {@code app/cbl/COUSR00C.cbl} actually did - and that record is an <strong>in-process
 * contract</strong>. Publishing it directly made terminal and navigation state part of the REST contract, which
 * was reported as a High-severity API-contract defect with a CWE-200 aspect: a client would have bound to
 * implementation detail, and every later change to the service's own shape would have been a breaking change
 * to the wire. This type is the boundary that stops that.</p>
 *
 * <p>What survives is what a caller can act on: the four business columns of each row, the paging cursor, and
 * the screen's own message. Nothing here describes how a terminal painted a screen.</p>
 *
 * <h2>The page number published is the one a caller echoes back</h2>
 *
 * <p>{@link #pageNumber} is {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} exactly as the source left it, and it is the
 * value the next request must carry. It can legitimately be zero: the increment at
 * {@code app/cbl/COUSR00C.cbl:L320-L321} is guarded by {@code IF WS-IDX &gt; 1}, so a browse that found nothing
 * leaves zero behind. A floored, display-oriented page number exists inside {@code PageResponse} and is
 * deliberately <strong>not</strong> published beside it: two page numbers on one body is exactly the duplicated
 * paging structure this projection exists to remove, and the one that round-trips correctly is this one.</p>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <ul>
 *   <li><b>Every 3270 and navigation field.</b> The cursor field, the erase flag, the send counter, the
 *       error flag, the control-transferred flag and the advisory navigation target describe a terminal
 *       conversation. A client navigates by URL and paints its own screen.</li>
 *   <li><b>The six recurring header fields.</b> {@code TRNNAMEI}, {@code TITLE01I}, {@code CURDATEI},
 *       {@code PGMNAMEI}, {@code TITLE02I} and {@code CURTIMEI} are screen chrome, and the last two would
 *       additionally publish the server's own clock reading on every page.</li>
 *   <li><b>The row selector and the selected identifier.</b> {@code SEL000nI PIC X(1)} and
 *       {@code CDEMO-CU00-USR-SELECTED} are how an operator marked a row in order to reach the update or
 *       delete screen. The user-administration controller accepts no selector inbound - selecting becomes the
 *       caller naming an identifier on the update or delete operation - so publishing one outbound would be
 *       state a client can neither set nor use.</li>
 *   <li><b>A total element count and a total page count.</b> The browse looks ahead exactly one record and
 *       never counts, so either number would be an invention.</li>
 *   <li><b>Any credential.</b> Neither this type nor its row type declares a password or hash component, so
 *       there is nothing to remember to blank.</li>
 * </ul>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> Built by {@link #of}, from the paging payload and the message the service produced.
 * <b>Outputs.</b> Serialised by Jackson; the row list is copied on the way in and exposed unmodifiable, so a
 * response cannot be altered after it is built. <b>Side effects.</b> None - this type compares, normalises,
 * maps and formats nothing. <b>Failure modes.</b> A null page is a wiring defect and raises
 * {@link NullPointerException}; nothing else can fail.</p>
 *
 * @param rows the displayed rows, in browse order - ascending user identifier, the key order of the
 *     {@code USRSEC} cluster - and never null; empty when the browse found nothing, which is a result rather
 *     than a failure
 * @param pageNumber {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COUSR00C.cbl:L70} as the source
 *     left it, which is the value the next request echoes back; zero after a browse that found nothing
 * @param pageSize the page depth in force, the single resolution of {@code USER-REC OCCURS 10 TIMES} at
 *     {@code app/cbl/COUSR00C.cbl:L57}. Reported so a caller never has to assume it, and never settable by one
 * @param nextPageAvailable whether the one-record lookahead found a further record, the transcription of
 *     {@code CDEMO-CU00-NEXT-PAGE-FLG} at {@code :L71-L73}
 * @param firstUserId {@code CDEMO-CU00-USRID-FIRST PIC X(08)} at {@code :L68}, the first identifier of this
 *     page and the key to page backward from; null on a browse that saved none
 * @param lastUserId {@code CDEMO-CU00-USRID-LAST PIC X(08)} at {@code :L69}, the last identifier of this page
 *     and the key to page forward from; null on a browse that saved none
 * @param errorMessage {@code ERRMSGO PIC X(78)}, relayed byte for byte. It is the only place several outcomes
 *     are reported at all - reaching either boundary, an empty result, or a refused identifier - which is why
 *     it travels rather than being dropped as screen text; null or blank when the turn set none
 */
public record UserListResponse(
        List<UserRowResponse> rows,
        int pageNumber,
        int pageSize,
        boolean nextPageAvailable,
        String firstUserId,
        String lastUserId,
        String errorMessage) {

    /**
     * The component names of the service's own screen record that this type must never carry.
     *
     * <p>Published so the contract is machine-checkable rather than merely documented: a test asserts that no
     * component of this record bears any of these names, so adding one back would fail the build rather than
     * quietly return terminal state to the wire again. The list is the exact set the High-severity finding
     * named, plus the two selector members and the six header fields.</p>
     */
    public static final List<String> WITHHELD_COMPONENTS = List.of(
            "screen",
            "page",
            "legacyPageNumber",
            "selectedUserId",
            "selectionFlag",
            "navigationTarget",
            "cursorField",
            "errorFlagOn",
            "eraseRequested",
            "sendCount",
            "controlTransferred",
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime");

    /**
     * Defensively copies the row list so the response is immutable once built.
     *
     * <p>A null list becomes an empty one rather than a failure, because an empty page is a legitimate result
     * of a browse that matched nothing.</p>
     */
    public UserListResponse {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * One row of the user list: the four business columns the screen displayed.
     *
     * <p>The row group of {@code app/cpy-bms/COUSR00.CPY} declares five fields, and this type carries four of
     * them. The fifth, {@code SEL000nI PIC X(1)}, is the operator's row selector and has no counterpart on a
     * stateless surface; see the enclosing type's absence list.</p>
     *
     * @param userId the eight-character identifier, {@code USRIDnnI PIC X(8)}, projected from
     *     {@code SEC-USR-ID PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy}
     * @param firstName the given name, {@code FNAMEnnI PIC X(20)}, from {@code SEC-USR-FNAME PIC X(20)}
     * @param lastName the family name, {@code LNAMEnnI PIC X(20)}, from {@code SEC-USR-LNAME PIC X(20)}
     * @param userType the raw one-character type code, {@code UTYPEnnI PIC X(1)}, from
     *     {@code SEC-USR-TYPE PIC X(01)}; carried verbatim rather than bound to an enumeration, because the
     *     source stores whatever byte is there
     */
    public record UserRowResponse(
            String userId,
            String firstName,
            String lastName,
            String userType) {

        /**
         * Projects one legacy row onto its response form, dropping the selector.
         *
         * @param row the legacy row; must not be null
         * @return the row response; never null
         * @throws NullPointerException if {@code row} is null
         */
        public static UserRowResponse of(final UserSecurityDto.UserRow row) {
            Objects.requireNonNull(row, "row must not be null");
            return new UserRowResponse(row.userId(), row.firstName(), row.lastName(), row.userType());
        }

        /**
         * Returns a diagnostic rendering that identifies no user.
         *
         * <p>Overridden for the same reason as {@link UserSecurityDto.UserRow#toString()}: the rendering a
         * record generates for itself would publish an identifier and a person's name into any log line,
         * exception message or debugger frame that interpolated it.</p>
         *
         * @return the type name only, never any user data
         */
        @Override
        public String toString() {
            return "UserListResponse.UserRowResponse[withheld]";
        }
    }

    /**
     * Builds the response from the paging payload and the message the service's turn produced.
     *
     * <p>The paging members are read from the payload rather than recomputed, and the page number is supplied
     * separately because the value that round-trips is the source's own - see the class documentation for why
     * the floored one is not published beside it.</p>
     *
     * @param page the paging payload the service produced; must not be null
     * @param legacyPageNumber {@code CDEMO-CU00-PAGE-NUM} as the source left it, which is what a caller echoes
     * @param errorMessage the turn's advisory message, or null when it set none
     * @return the response; never null
     * @throws NullPointerException if {@code page} is null
     */
    public static UserListResponse of(final PageResponse<UserSecurityDto.UserRow> page,
                                      final int legacyPageNumber,
                                      final String errorMessage) {
        Objects.requireNonNull(page, "page must not be null");
        return new UserListResponse(
                page.getRows().stream().map(UserRowResponse::of).toList(),
                legacyPageNumber,
                page.getPageSize(),
                page.isNextPageAvailable(),
                page.getFirstKey(),
                page.getLastKey(),
                errorMessage);
    }

    /**
     * Returns the page's rows in browse order.
     *
     * @return an unmodifiable, order-preserving copy of the rows, never null
     */
    @Override
    public List<UserRowResponse> rows() {
        return List.copyOf(this.rows);
    }

    /**
     * Returns a diagnostic rendering that identifies no user.
     *
     * <p>Every row carries an identifier and two personal names, and the boundary cursors are identifiers in
     * their own right, so the generated rendering would publish the whole page into a single log line. Only the
     * page shape is emitted, which cannot identify a person. There is no numeric or date formatting in the
     * result, so the output cannot vary with the platform locale.</p>
     *
     * @return the type name, the row count and the page number only, never any user data
     */
    @Override
    public String toString() {
        return "UserListResponse[rows=" + this.rows.size() + ", pageNumber=" + this.pageNumber + "]";
    }
}
