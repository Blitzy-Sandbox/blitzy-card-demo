package com.carddemo.model.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response payload for the paginated transaction-list (browse) screen.
 *
 * <p>Serialized to JSON and returned by {@code TransactionController} for
 * {@code GET /api/transactions}; produced by {@code TransactionListService},
 * which migrates the online CICS program {@code COTRN00C} (a browse that shows
 * <strong>10 transaction rows per page</strong>). The structure mirrors the
 * {@code COTRN00} BMS symbolic map, excluding terminal screen chrome, attribute
 * bytes, and PF-key legends.</p>
 *
 * <p>The transaction-id filter and the page number are supplied by the client as
 * request query parameters (there is no separate request DTO); both are
 * <em>echoed</em> back here so the caller can correlate the page that was
 * rendered. The exact 10-row page size is preserved through {@link #transactions}
 * (the controller/service layer enforces the page-size limit).</p>
 *
 * <p>Lineage: AWS CardDemo COBOL source, commit {@code 27d6c6f}
 * ({@code app/cpy-bms/COTRN00.CPY}). Reference only - no COBOL is copied.</p>
 *
 * @param pageNumber          the current page number, echoed from the request
 *                            (COTRN00 {@code PAGENUM}); {@code null} when not set
 * @param transactionIdFilter the transaction-id filter echoed from the request
 *                            (COTRN00 {@code TRNIDIN}); {@code null} when no
 *                            filter was supplied
 * @param transactions        the displayed transaction rows for this page (up to
 *                            10; COTRN00 row group); never contains screen-chrome
 *                            fields
 * @param errorMessage        a human-readable error/status message for the screen
 *                            (COTRN00 {@code ERRMSG}); {@code null} or blank when
 *                            the page rendered without error
 */
public record TransactionListResponse(
        String pageNumber,
        String transactionIdFilter,
        List<TransactionListItem> transactions,
        String errorMessage) {

    /**
     * A single displayed transaction row within a {@link TransactionListResponse}.
     *
     * <p>The {@code COTRN00} map repeats this field group ten times (one per row
     * on the browse screen); the rows are represented here as elements of
     * {@link TransactionListResponse#transactions} rather than flattened into
     * positional fields.</p>
     *
     * @param selectionFlag the single-character row selection indicator
     *                      (COTRN00 {@code SEL}); used by the client to mark a row
     * @param transactionId the 16-character transaction identifier
     *                      (COTRN00 {@code TRNID})
     * @param date          the transaction date as displayed, 8 characters
     *                      (COTRN00 {@code TDATE})
     * @param description   the transaction description, up to 26 characters
     *                      (COTRN00 {@code TDESC})
     * @param amount        the transaction amount as an exact fixed-point value
     *                      (COTRN00 {@code TAMT}, derived from
     *                      {@code TRAN-AMT PIC S9(9)V99}); a {@link BigDecimal}
     *                      with scale 2 to preserve COBOL decimal precision; never
     *                      a binary floating-point type
     */
    public static record TransactionListItem(
            String selectionFlag,
            String transactionId,
            String date,
            String description,
            BigDecimal amount) {
    }
}
