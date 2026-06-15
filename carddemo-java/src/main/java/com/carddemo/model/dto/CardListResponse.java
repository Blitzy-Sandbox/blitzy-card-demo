package com.carddemo.model.dto;

import java.util.List;

/**
 * Response payload for the paginated card-list (browse) screen.
 *
 * <p>Returned as JSON by {@code CardController} for {@code GET /api/cards} and produced by
 * {@code CardListService}. The browse window has a fixed page size of <strong>7 rows</strong>,
 * one {@link CardListItem} per displayed card; the controller and service enforce that count.</p>
 *
 * <p>The filter inputs (account-id and card-number) and the page number are supplied by the
 * caller as query parameters rather than via a dedicated request DTO; they are <em>echoed</em>
 * back in this response so the client can re-render the current filter state.</p>
 *
 * <p>Migrated from the COBOL BMS symbolic map {@code COCRDLI} (online program {@code COCRDLIC}).
 * Screen chrome (titles, program name, date/time) and PF-key legends are intentionally omitted.
 * Source lineage: commit {@code 27d6c6f}.</p>
 *
 * @param pageNumber       current page indicator echoed from the request (COCRDLI {@code PAGENO}, PIC X(3))
 * @param accountIdFilter  account-id filter echoed from the request (COCRDLI {@code ACCTSID}, PIC X(11))
 * @param cardNumberFilter card-number filter echoed from the request (COCRDLI {@code CARDSID}, PIC X(16))
 * @param cards            up to 7 card rows for the current page (COCRDLI 7-row repeating group)
 * @param infoMessage      informational message for the user (COCRDLI {@code INFOMSG}, PIC X(45))
 * @param errorMessage     error message for the user (COCRDLI {@code ERRMSG}, PIC X(78))
 */
public record CardListResponse(
        String pageNumber,
        String accountIdFilter,
        String cardNumberFilter,
        List<CardListItem> cards,
        String infoMessage,
        String errorMessage) {

    /**
     * A single displayed card row within {@link CardListResponse#cards()}.
     *
     * <p>The {@code COCRDLI} map repeats this field group 7 times (one occurrence per row on the
     * browse screen). It is modelled once here and collected into {@link CardListResponse#cards()}
     * rather than being flattened into 28 individual fields.</p>
     *
     * @param selectionFlag row selection indicator (COCRDLI {@code CRDSEL}, PIC X(1))
     * @param accountId     account number associated with the card (COCRDLI {@code ACCTNO}, PIC X(11))
     * @param cardNumber    card number (COCRDLI {@code CRDNUM}, PIC X(16))
     * @param cardStatus    card status code (COCRDLI {@code CRDSTS}, PIC X(1))
     */
    public static record CardListItem(
            String selectionFlag,
            String accountId,
            String cardNumber,
            String cardStatus) {
    }
}
