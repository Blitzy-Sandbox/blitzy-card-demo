package com.carddemo.model.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable response payload for the paginated transaction-list (browse) screen.
 *
 * <p>This DTO is serialized to JSON and returned by {@code TransactionController}
 * for {@code GET /api/transactions}, and is produced by
 * {@code TransactionListService}. It mirrors the {@code COTRN00} BMS symbolic map
 * of the originating CICS program {@code COTRN00C}, which renders a single browse
 * page of up to <strong>ten</strong> transaction rows. Behavioral lineage is
 * preserved by reference to source commit {@code 27d6c6f}; no COBOL is copied.</p>
 *
 * <p>The transaction-id filter and the page number are supplied by the client as
 * query parameters (there is no dedicated request DTO), and are <em>echoed</em>
 * back here so the caller can correlate the response with its request. The fixed
 * page size of ten rows is enforced by the controller/service layer and is
 * represented structurally by {@link #transactions()} rather than by flattened,
 * individually numbered fields.</p>
 *
 * @param pageNumber          the current page number, echoed from the request
 *                            (&larr; {@code COTRN00.PAGENUM}, PIC X(8))
 * @param transactionIdFilter the transaction-id filter value, echoed from the
 *                            request (&larr; {@code COTRN00.TRNIDIN}, PIC X(16))
 * @param transactions        the displayed rows for this page, up to ten items
 *                            (&larr; the {@code COTRN00} row group, repeated ten times)
 * @param errorMessage        the screen-level message, or {@code null}/blank when
 *                            none applies (&larr; {@code COTRN00.ERRMSG}, PIC X(78))
 */
public record TransactionListResponse(
        String pageNumber,
        String transactionIdFilter,
        List<TransactionListItem> transactions,
        String errorMessage) {

    /**
     * A single displayed transaction row within a {@link TransactionListResponse}.
     *
     * <p>The {@code COTRN00} map repeats this field group ten times per page; the
     * rows are represented collectively via
     * {@link TransactionListResponse#transactions()}.</p>
     *
     * <p>In accordance with the migration's decimal-precision rules, the monetary
     * {@code amount} is modeled as {@link BigDecimal} (scale {@code 2}) to preserve
     * the exact fixed-point value of the underlying {@code TRAN-AMT PIC S9(9)V99}
     * field; floating-point types are never used for currency. All other components
     * are {@link String} values mirroring their fixed-width display fields.</p>
     *
     * @param selectionFlag the row selection indicator
     *                      (&larr; {@code COTRN00.SEL}, PIC X(1))
     * @param transactionId the sixteen-character transaction identifier
     *                      (&larr; {@code COTRN00.TRNID}, PIC X(16))
     * @param date          the transaction date as displayed
     *                      (&larr; {@code COTRN00.TDATE}, PIC X(8))
     * @param description   the transaction description
     *                      (&larr; {@code COTRN00.TDESC}, PIC X(26))
     * @param amount        the transaction amount as a fixed-point decimal of scale 2
     *                      (&larr; {@code COTRN00.TAMT}, PIC X(12),
     *                      from {@code TRAN-AMT PIC S9(9)V99})
     */
    public static record TransactionListItem(
            String selectionFlag,
            String transactionId,
            String date,
            String description,
            BigDecimal amount) {
    }
}
