/*
 * ******************************************************************
 * Program     : TransactionListResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for the transaction list: ten
 *               rows, the keyset page metadata with opaque cursors, and
 *               the screen status message the source displayed.
 * Source      : app/cbl/COTRN00C.cbl (699 lines) over mapset COTRN00,
 *               :L65-L68 (05 CDEMO-CT00-INFO, the four carried paging
 *               fields), :L290 (UNTIL WS-IDX > 10) @ 7756d89
 * Source      : app/cpy-bms/COTRN00.CPY (59 input fields, a ten-row
 *               table plus ERRMSGI) @ 7756d89
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
 * One page of the transaction list, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>It is an envelope rather than a bare page. The source's turn painted ten rows <em>and</em>
 * {@code ERRMSGI}, and that message is the only place several outcomes are reported at all: reaching the last
 * page, an empty result, and a search key that matched nothing are each a message on a populated or empty
 * screen rather than a distinct status. Returning only the rows would discard them.</p>
 *
 * <h2>Cursors are opaque</h2>
 *
 * <p>The browse pages from the saved first and last transaction identifier of the displayed page. Those are
 * not card numbers, so sealing them discloses nothing that plain text would - but they are sealed anyway, for
 * two reasons that are worth stating rather than leaving implicit. A client that can edit a cursor can
 * reposition the browse to an arbitrary key, which turns page metadata into an unvalidated query parameter;
 * and one cursor convention across both list operations is one convention to reason about rather than two.</p>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <ul>
 *   <li><b>A total element count and a total page count.</b> The source looks ahead exactly one record and
 *       never counts, so either figure would be an invention backed by a query the legacy system never
 *       issued.</li>
 *   <li><b>The screen header and the echoed search key.</b> Terminal furniture and request echo respectively;
 *       a client already holds the parameters it sent.</li>
 *   <li><b>Per-row selection flags.</b> {@code SEL0001..0010 PIC X(1)} exists so a 3270 operator could mark a
 *       row for the detail transaction. A client navigates to the detail operation by URL instead.</li>
 *   </ul>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> Built by {@link #of}. <b>Outputs.</b> Serialised by Jackson; the row list is copied on
 * the way in and exposed unmodifiable. <b>Side effects.</b> None. <b>Failure modes.</b> A null page is a
 * wiring defect and raises {@link NullPointerException}.</p>
 *
 * @param rows the displayed rows, in screen order and never null; empty when the browse found nothing
 * @param pageNumber the page being reported, normalised to at least one
 * @param pageSize the page depth in force, namely ten, from {@code UNTIL WS-IDX > 10} at
 *     {@code app/cbl/COTRN00C.cbl:L290}
 * @param nextPageAvailable whether the one-record lookahead found a further record
 * @param firstCursor the sealed cursor to page backward from, or null when the browse saved no first key
 * @param lastCursor the sealed cursor to page forward from, or null when the browse saved no last key
 * @param statusMessage {@code ERRMSGI}, the byte-exact screen literal the turn produced, relayed unchanged;
 *     null or blank when the turn set none
 */
public record TransactionListResponse(
        List<TransactionListRowResponse> rows,
        int pageNumber,
        int pageSize,
        boolean nextPageAvailable,
        String firstCursor,
        String lastCursor,
        String statusMessage) {

    /**
     * Defensively copies the row list so the response is immutable once built.
     */
    public TransactionListResponse {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * One row of the transaction list.
     *
     * <p>The legacy row carries no card number at all - {@code app/cpy-bms/COTRN00.CPY} declares an
     * identifier, a date, a description and an amount per row - so there is nothing to mask here. That
     * absence is asserted rather than assumed: were a card number ever added to the row projection, the
     * serialisation contract test would fail.</p>
     *
     * @param transactionId the row's sixteen-character identifier, {@code TRNID01..10 PIC X(16)}; may be null
     *     on a blank filler row
     * @param transactionDate the row's date as text, {@code TDATE01..10 PIC X(8)}; may be null on a blank
     *     filler row
     * @param description the row's description, {@code TDESC01..10 PIC X(40)}; may be null on a blank filler
     *     row
     * @param amount the row's amount on the legacy display mask, as text and never as a JSON number, for the
     *     reason given on {@link TransactionResponse}; may be null on a blank filler row
     */
    public record TransactionListRowResponse(
            String transactionId,
            String transactionDate,
            String description,
            String amount) {

        /**
         * Projects one legacy row onto its response form.
         *
         * @param row the legacy row; must not be null
         * @return the row response; never null
         * @throws NullPointerException if {@code row} is null
         */
        public static TransactionListRowResponse of(final TransactionDto.TransactionListRow row) {
            Objects.requireNonNull(row, "row must not be null");
            return new TransactionListRowResponse(
                    row.transactionId(),
                    row.transactionDate(),
                    row.description(),
                    row.amount());
        }
    }

    /**
     * Builds the envelope from a service-produced page, its sealed cursors and the turn's message.
     *
     * @param page the paging payload the service produced; must not be null
     * @param firstCursor the sealed cursor for the previous page, or null
     * @param lastCursor the sealed cursor for the next page, or null
     * @param statusMessage the turn's status message, or null
     * @return the envelope; never null
     * @throws NullPointerException if {@code page} is null
     */
    public static TransactionListResponse of(final PageResponse<TransactionDto.TransactionListRow> page,
                                             final String firstCursor,
                                             final String lastCursor,
                                             final String statusMessage) {
        Objects.requireNonNull(page, "page must not be null");
        return new TransactionListResponse(
                page.getRows().stream().map(TransactionListRowResponse::of).toList(),
                page.getPageNumber(),
                page.getPageSize(),
                page.isNextPageAvailable(),
                firstCursor,
                lastCursor,
                statusMessage);
    }
}
