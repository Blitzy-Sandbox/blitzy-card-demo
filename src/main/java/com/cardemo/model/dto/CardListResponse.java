/*
 * ******************************************************************
 * Program     : CardListResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for the card list: seven masked
 *               rows, the keyset page metadata with opaque cursors, and
 *               the two screen status messages the source displayed.
 * Source      : app/cbl/COCRDLIC.cbl (1,459 lines) over mapset COCRDLI
 *               :L177-L178 (WS-MAX-SCREEN-LINES VALUE 7),
 *               :L237 (WS-CA-SCREEN-NUM PIC 9(1)),
 *               :L1197-L1205 (the one-record lookahead and the saved
 *               first and last card number of the page),
 *               :L112 (WS-INFO-MSG), :L117 (WS-ERROR-MSG) @ 7756d89
 * Source      : app/cpy-bms/COCRDLI.CPY (45 input fields, CRDNUM01
 *               through CRDNUM07 PIC X(16)) @ 7756d89
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
import java.util.function.BiFunction;

/**
 * One page of the card list, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It is an envelope rather than a bare page, and that is the point. The source's turn produced a page
 * <em>and</em> a status: {@code WS-INFO-MSG} at {@code app/cbl/COCRDLIC.cbl:L112} carries outcomes such as
 * having reached the last page, and {@code WS-ERROR-MSG} at {@code :L117} carries a rejected row selection -
 * and {@code :L431-L435} deliberately re-reads the list when the failure is not a filter failure, so an
 * error-bearing turn still has rows to show. Returning only the rows would silently discard everything the
 * screen told the operator, which is a loss of behaviour rather than a simplification.</p>
 *
 * <h2>Cursors are opaque, and must be</h2>
 *
 * <p>The browse pages from saved keys, and on this screen the saved keys <em>are</em> card numbers:
 * {@code :L1197-L1205} records the first and last card number of the displayed page so the next turn can
 * reposition from them. Emitting them as page cursors would publish two primary account numbers per page in
 * exactly the place a client is most likely to log, cache or bookmark. {@link #firstCursor} and
 * {@link #lastCursor} therefore carry sealed values: the browse works exactly as the source's saved keys did,
 * while the wire value is meaningless to anyone but the server that issued it.</p>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <ul>
 *   <li><b>A total element count and a total page count.</b> The source never computes either - it looks
 *       ahead exactly one record - so supplying them would require a counting query the legacy system never
 *       issued, and any number reported here would be an invention.</li>
 *   <li><b>Every 3270 attribute.</b> The seven per-row selectable and highlight flags, the four filter-field
 *       attribute flags and the cursor field describe how a terminal painted a screen.</li>
 *   <li><b>The navigation triple.</b> {@code CCARD-NEXT-PROG}, {@code -MAPSET} and {@code -MAP} named the
 *       program a transfer of control would reach; a client navigates by URL.</li>
 *   <li><b>Full card numbers, anywhere.</b> Each row carries a masked reference only.</li>
 * </ul>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> Built by {@link #of}, from a service-produced page and the two message fields.
 * <b>Outputs.</b> Serialised by Jackson; the row list is copied on the way in and exposed unmodifiable, so a
 * response cannot be altered after it is built. <b>Side effects.</b> None. <b>Failure modes.</b> A null page
 * is a wiring defect and raises {@link NullPointerException}; nothing else can fail.</p>
 *
 * @param rows the displayed rows, in screen order and never null; empty when the browse found nothing, which
 *     is a result rather than a failure
 * @param pageNumber the page being reported, the transcription of {@code WS-CA-SCREEN-NUM PIC 9(1)} at
 *     {@code app/cbl/COCRDLIC.cbl:L237}, normalised to at least one
 * @param pageSize the page depth in force, the single resolution of {@code WS-MAX-SCREEN-LINES VALUE 7} at
 *     {@code :L177-L178}
 * @param nextPageAvailable whether the one-record lookahead at {@code :L1197-L1205} found a further record.
 *     Reported as a boolean because the corpus has two mutually incompatible sentinels for it and a JSON
 *     contract can carry neither meaningfully
 * @param firstCursor the sealed cursor to page backward from, or null on a browse that saved no first key
 * @param lastCursor the sealed cursor to page forward from, or null on a browse that saved no last key
 * @param informationMessage {@code WS-INFO-MSG} at {@code :L112}, at most 45 characters, relayed byte for
 *     byte; null or blank when the turn set none
 * @param errorMessage {@code WS-ERROR-MSG} at {@code :L117}, at most 75 characters, on the same terms
 * @param rowSelectionAvailable whether a row may be acted upon, the inverse of
 *     {@code FLG-PROTECT-SELECT-ROWS-YES} at {@code app/cbl/COCRDLIC.cbl:L107}. It is business state and not
 *     a 3270 attribute: when an invalid filter has suppressed selection, a client that offers a per-row
 *     action needs to know that correcting the filter comes first
 */
public record CardListResponse(
        List<CardListRowResponse> rows,
        int pageNumber,
        int pageSize,
        boolean nextPageAvailable,
        String firstCursor,
        String lastCursor,
        String informationMessage,
        String errorMessage,
        boolean rowSelectionAvailable) {

    /**
     * Defensively copies the row list so the response is immutable once built.
     */
    public CardListResponse {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * One row of the card list.
     *
     * @param rowNumber the one-based position on the screen, from the {@code OCCURS 7 TIMES} table at
     *     {@code app/cbl/COCRDLIC.cbl:L255}
     * @param accountNumber the row's account identifier, {@code ACCTNO01..07 PIC X(11)}; may be null on a
     *     blank filler row
     * @param maskedCardNumber the row's card number reduced by {@link ApiMasking#maskCardNumber(String)};
     *     may be null on a blank filler row, and never the full number
     * @param statusCode the row's active status as a raw one-character code, {@code CRDSTS01..07 PIC X(1)};
     *     may be null on a blank filler row
     * @param cardKey an opaque, sealed, expiring reference to this row's card, or null on a blank filler
     *     row. <strong>Finding, severity High - remediated by this member.</strong> The source's list map
     *     carries the full account number and the full card number in every row
     *     ({@code ACCTNO01..07 PIC X(11)} and {@code CRDNUM01..07 PIC X(16)}), and a terminal operator
     *     typed {@code S} or {@code U} beside a row to reach the detail or update screen. This projection
     *     masks the card number - which is the right call for an HTTP surface, and it is now a labelled
     *     deviation rather than a silent one - but masking alone left the documented
     *     list-to-detail-to-update path <em>impossible</em>: both of those operations require the full
     *     sixteen digits, and no operation ever disclosed them. This member restores the navigation
     *     without restoring the disclosure. It is the Rule 7 substitution for row selection: the
     *     {@code SELn} flag was screen state, and a stateless client needs a value it can send instead.
     *     It is sealed with authenticated encryption, so a client cannot read a card number out of it,
     *     cannot alter it and cannot manufacture one
     */
    public record CardListRowResponse(
            int rowNumber,
            String accountNumber,
            String maskedCardNumber,
            String statusCode,
            String cardKey) {

        /**
         * Projects one legacy row onto its response form, masking the card number.
         *
         * @param row the legacy row; must not be null
         * @param cardKey the sealed reference the detail and update operations accept in place of the
         *     undisclosed card number; may be null when the row carries no card, in which case there is
         *     nothing to navigate to
         * @return the row response; never null
         * @throws NullPointerException if {@code row} is null
         */
        public static CardListRowResponse of(final CardDto.CardListRow row, final String cardKey) {
            Objects.requireNonNull(row, "row must not be null");
            return new CardListRowResponse(
                    row.getRowNumber(),
                    row.getAccountNumber(),
                    ApiMasking.maskCardNumber(row.getCardNumber()),
                    row.getStatusCode(),
                    cardKey);
        }
    }

    /**
     * Builds the envelope from a service-produced page, its sealed cursors and the turn's two messages.
     *
     * <p>The row selection flag is supplied by the caller rather than derived here, because only the service
     * knows whether an invalid filter protected the selection fields.</p>
     *
     * @param page the paging payload the service produced; must not be null. Its own first and last keys are
     *     <b>not</b> read, precisely because they are card numbers; the sealed cursors are passed separately
     * @param firstCursor the sealed cursor for the previous page, or null
     * @param lastCursor the sealed cursor for the next page, or null
     * @param informationMessage the turn's information message, or null
     * @param errorMessage the turn's error message, or null
     * @param rowSelectionAvailable whether a row may be acted upon
     * @param cardKeyMinter seals one row's opaque card reference from its account number and its card
     *     number, in that order, returning null when the row carries no card. It is supplied rather than
     *     performed here because sealing needs a key this package must not hold: a response type is a
     *     projection, and giving it a cipher would put the signing key one import away from every DTO
     * @return the envelope; never null
     * @throws NullPointerException if {@code page} or {@code cardKeyMinter} is null
     */
    public static CardListResponse of(final PageResponse<CardDto.CardListRow> page,
                                      final String firstCursor,
                                      final String lastCursor,
                                      final String informationMessage,
                                      final String errorMessage,
                                      final boolean rowSelectionAvailable,
                                      final BiFunction<String, String, String> cardKeyMinter) {
        Objects.requireNonNull(page, "page must not be null");
        Objects.requireNonNull(cardKeyMinter, "cardKeyMinter must not be null");
        return new CardListResponse(
                page.getRows().stream()
                        .map(row -> CardListRowResponse.of(row,
                                cardKeyMinter.apply(row.getAccountNumber(), row.getCardNumber())))
                        .toList(),
                page.getPageNumber(),
                page.getPageSize(),
                page.isNextPageAvailable(),
                firstCursor,
                lastCursor,
                informationMessage,
                errorMessage,
                rowSelectionAvailable);
    }
}
