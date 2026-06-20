package com.carddemo.model.dto;

import java.util.List;

/**
 * Response DTO for the paginated card-list (browse) screen.
 *
 * <p>Returned as JSON by {@code controller.CardController} for {@code GET /api/cards}
 * and produced by {@code service.card.CardListService}. The screen displays a fixed
 * window of <strong>7 card rows per page</strong>; the controller and service enforce
 * that page size, and the rows are carried here as {@link #cards()} rather than as
 * flattened, positionally-numbered fields.</p>
 *
 * <p>The three filter/paging inputs ({@link #accountIdFilter()}, {@link #cardNumberFilter()},
 * and {@link #pageNumber()}) are accepted by the controller as query parameters and are
 * <em>echoed</em> back in this response so the client can re-render the current filter state
 * without holding any server-side conversational state.</p>
 *
 * <p>Structural mirror of the {@code COCRDLI} BMS symbolic map (input map {@code CCRDLIAI});
 * screen chrome, PF-key legends, and 3270 attribute fields are intentionally omitted.
 * Behavioral lineage traces to the original COBOL source at commit {@code 27d6c6f}
 * (reference only — no COBOL is copied into this project).</p>
 *
 * <p>This is a plain, immutable, JSON-serializable {@code record}: it carries no JPA,
 * persistence, or bean-validation concerns and is decoupled from the JPA entity layer.</p>
 *
 * @param pageNumber      current page indicator echoed to the client
 *                        (mirrors {@code PAGENO}, {@code PIC X(3)})
 * @param accountIdFilter account-id filter value echoed back for re-rendering
 *                        (mirrors {@code ACCTSID}, {@code PIC X(11)})
 * @param cardNumberFilter card-number filter value echoed back for re-rendering
 *                        (mirrors {@code CARDSID}, {@code PIC X(16)})
 * @param cards           the displayed card rows for this page (at most 7); never more
 *                        than the enforced page size (mirrors the 7&times; repeated row group)
 * @param infoMessage     informational message for the client
 *                        (mirrors {@code INFOMSG}, {@code PIC X(45)})
 * @param errorMessage    error message for the client
 *                        (mirrors {@code ERRMSG}, {@code PIC X(78)})
 */
public record CardListResponse(
        String pageNumber,
        String accountIdFilter,
        String cardNumberFilter,
        List<CardListItem> cards,
        String infoMessage,
        String errorMessage) {

    /**
     * A single displayed card row within the paginated card-list screen.
     *
     * <p>The {@code COCRDLI} map repeats this group seven times (one per visible row);
     * this record models a single occurrence so that the page is represented as a list
     * rather than 28 flattened, positionally-suffixed fields. All members are
     * {@code String} values because the card-list screen carries no monetary fields.</p>
     *
     * @param selectionFlag row selection indicator
     *                      (mirrors {@code CRDSEL}, {@code PIC X(1)})
     * @param accountId     account identifier for the row
     *                      (mirrors {@code ACCTNO}, {@code PIC X(11)})
     * @param cardNumber    card number for the row
     *                      (mirrors {@code CRDNUM}, {@code PIC X(16)})
     * @param cardStatus    card status indicator for the row
     *                      (mirrors {@code CRDSTS}, {@code PIC X(1)})
     */
    public static record CardListItem(
            String selectionFlag,
            String accountId,
            String cardNumber,
            String cardStatus) {
    }
}
