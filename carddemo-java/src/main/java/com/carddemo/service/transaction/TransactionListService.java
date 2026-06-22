package com.carddemo.service.transaction;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionListResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.shared.PaginationSupport;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service backing the paginated transaction-list (browse) screen.
 *
 * <p>Returns a single page of transactions ordered ascending by transaction id,
 * fixed at ten rows per page. An optional transaction-id filter, when supplied
 * and non-blank, must contain digits only; a non-numeric filter is rejected with
 * a {@link ValidationException}. Each returned row carries the transaction id, a
 * display date in {@code MM/DD/YY} form derived from the transaction's original
 * timestamp, the description, and the amount as an exact fixed-point decimal
 * value (never a binary floating-point type).
 *
 * <p>Row selection (drill-down to a single transaction's detail) is performed by
 * the caller, so the selection flag on every returned row is empty. Forward and
 * backward navigation is achieved by the caller supplying the desired zero-based
 * {@code page} index; the page number echoed in the response is one-based.
 */
@Service
public class TransactionListService {

    /** Number of transaction rows returned per browse page. */
    private static final int PAGE_SIZE = 10;

    /** Stored width of a transaction id (fixed-width, zero-padded numeric); used to normalize the start-at filter. */
    private static final int TRAN_ID_LENGTH = 16;

    /** Validation message when a zero-based page index is negative (Issue 5). */
    private static final String MSG_PAGE_NEGATIVE = "Page number must be zero or greater";

    private final TransactionRepository transactionRepository;

    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns one page of transactions for the browse screen.
     *
     * @param page                the requested zero-based page index; must be zero or
     *                            greater (a negative value is rejected with HTTP 400)
     * @param transactionIdFilter an optional "start-at" transaction-id filter; when present
     *                            and non-blank it must contain digits only and the browse
     *                            begins at the first transaction id {@code >=} this value
     *                            (zero-padded to the stored width) and reads forward
     * @return the page of transactions, the echoed one-based page number, the
     *         echoed filter, and a {@code null} error message on success
     * @throws ValidationException if {@code transactionIdFilter} is present, non-blank, and
     *                             not composed solely of digits, or if {@code page} is negative
     */
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(int page, String transactionIdFilter) {
        boolean filterActive = transactionIdFilter != null && !transactionIdFilter.isBlank();
        if (filterActive && !transactionIdFilter.matches("\\d+")) {
            throw new ValidationException("Tran ID must be Numeric ...");
        }

        // Issue 5 (invalid pagination bounds): a zero-based page index below zero is not a valid
        // page. The CICS browse tracked a small page counter in the COMMAREA and could not express a
        // negative page; the REST "page" parameter makes one expressible, so reject it explicitly
        // (HTTP 400) instead of silently coercing it to the first page. A huge but non-negative page
        // still yields a graceful empty page (HTTP 200) via the offset clamp below.
        if (page < 0) {
            throw new ValidationException(MSG_PAGE_NEGATIVE);
        }

        // Clamp the zero-based page so the resulting SQL offset (safePage * PAGE_SIZE) cannot
        // exceed Integer.MAX_VALUE, yielding a graceful empty page (HTTP 200) instead of an
        // offset-overflow InvalidDataAccessApiUsageException surfacing as HTTP 500.
        int safePage = PaginationSupport.clampPageToMaxOffset(page, PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));

        // Issue 4 (filters not applied): COTRN00C's STARTBR-TRANSACT-FILE positions the browse on
        // the entered transaction id with GTEQ and reads forward, so the filter is a "start-at"
        // lower bound applied BEFORE paging (the earlier implementation validated and echoed the
        // filter but queried the whole table). Ids are fixed-width zero-padded numeric strings, so a
        // shorter filter is left-padded to the stored width to make the lexicographic >= comparison
        // equal a numeric >= comparison.
        Page<Transaction> result = filterActive
                ? transactionRepository.findByTranIdGreaterThanEqual(normalizeStartAt(transactionIdFilter), pageable)
                : transactionRepository.findAll(pageable);

        List<TransactionListResponse.TransactionListItem> items = result.getContent().stream()
                .map(tx -> new TransactionListResponse.TransactionListItem(
                        "",
                        tx.getTranId(),
                        formatListDate(tx.getTranOrigTs()),
                        tx.getTranDesc(),
                        tx.getTranAmt()))
                .toList();

        return new TransactionListResponse(
                String.valueOf(safePage + 1),
                transactionIdFilter == null ? "" : transactionIdFilter,
                items,
                null);
    }

    /**
     * Formats a transaction's original timestamp (of the form
     * {@code YYYY-MM-DD...}) into the {@code MM/DD/YY} display date used on the
     * browse screen, where the year is rendered as its final two digits.
     *
     * @param origTs the original transaction timestamp
     * @return the {@code MM/DD/YY} display date, or an empty string when the
     *         timestamp is {@code null} or shorter than ten characters
     */
    private static String formatListDate(String origTs) {
        if (origTs == null || origTs.length() < 10) {
            return "";
        }
        String mm = origTs.substring(5, 7);
        String dd = origTs.substring(8, 10);
        String yy = origTs.substring(2, 4);
        return mm + "/" + dd + "/" + yy;
    }

    /**
     * Normalizes the numeric "start-at" transaction-id filter to the stored fixed width by
     * left-padding it with zeros, so the lexicographic {@code >=} comparison performed by
     * {@link TransactionRepository#findByTranIdGreaterThanEqual(String, org.springframework.data.domain.Pageable)}
     * is identical to a numeric {@code >=} comparison against the zero-padded ids (e.g. a filter of
     * {@code "15"} becomes {@code "0000000000000015"}). A filter already at or beyond the stored
     * width is returned unchanged. The caller guarantees the input is non-blank and all digits.
     *
     * @param filter the validated all-digit transaction-id filter
     * @return the filter left-padded with zeros to the stored transaction-id width
     */
    private static String normalizeStartAt(String filter) {
        int deficit = TRAN_ID_LENGTH - filter.length();
        return deficit > 0 ? "0".repeat(deficit) + filter : filter;
    }
}
