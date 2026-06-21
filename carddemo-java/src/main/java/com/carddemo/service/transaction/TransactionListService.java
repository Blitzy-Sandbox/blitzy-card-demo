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

    private final TransactionRepository transactionRepository;

    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns one page of transactions for the browse screen.
     *
     * @param page                the requested zero-based page index; negative
     *                            values are clamped to the first page
     * @param transactionIdFilter an optional transaction-id filter; when present
     *                            and non-blank it must contain digits only
     * @return the page of transactions, the echoed one-based page number, the
     *         echoed filter, and a {@code null} error message on success
     * @throws ValidationException if {@code transactionIdFilter} is present,
     *                             non-blank, and not composed solely of digits
     */
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(int page, String transactionIdFilter) {
        if (transactionIdFilter != null && !transactionIdFilter.isBlank()
                && !transactionIdFilter.matches("\\d+")) {
            throw new ValidationException("Tran ID must be Numeric ...");
        }

        // Clamp the zero-based page so the resulting SQL offset (safePage * PAGE_SIZE) cannot
        // exceed Integer.MAX_VALUE. clampPageToMaxOffset also floors negatives at 0, so an absurd
        // page number now yields a graceful empty page (HTTP 200) instead of an offset-overflow
        // InvalidDataAccessApiUsageException surfacing as HTTP 500.
        int safePage = PaginationSupport.clampPageToMaxOffset(page, PAGE_SIZE);

        Page<Transaction> result = transactionRepository.findAll(
                PageRequest.of(safePage, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId")));

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
}
