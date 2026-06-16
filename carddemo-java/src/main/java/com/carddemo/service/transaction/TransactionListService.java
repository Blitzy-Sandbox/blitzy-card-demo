package com.carddemo.service.transaction;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionListResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Application service that produces a single page of the transaction browse
 * (list) view. Each page contains up to {@value #PAGE_SIZE} transaction rows,
 * ordered ascending by transaction id so that the response reproduces the
 * keyed-ascending browse order of the underlying store.
 *
 * <p>Callers supply a zero-based {@code page} index and an optional
 * transaction-id start key. When present, the start key must be all-numeric; it
 * positions the browse at the first transaction whose id is greater than or equal
 * to the key (mirroring the COBOL keyed {@code STARTBR}/{@code READNEXT} forward
 * browse) and is echoed back on the response. When absent, the browse begins at
 * the lowest transaction id. Navigation between pages is driven by the
 * {@code page} argument supplied by the caller. The result is an immutable
 * {@link TransactionListResponse} whose rows expose the transaction id, an
 * {@code MM/DD/YY} display date, the description, and the monetary amount as a
 * fixed-point {@link java.math.BigDecimal}.</p>
 *
 * <p>Row selection (drilling into a single transaction) is not handled here; the
 * caller invokes the transaction-detail endpoint directly, so every row is
 * emitted with an empty selection flag.</p>
 */
@Service
public class TransactionListService {

    /** Fixed number of transaction rows returned per browse page. */
    private static final int PAGE_SIZE = 10;

    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required repository collaborator.
     *
     * @param transactionRepository the transaction repository (constructor-injected)
     */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns a single page of transactions for the browse view.
     *
     * @param page                the zero-based page index; negative values are
     *                            clamped to the first page
     * @param transactionIdFilter an optional transaction-id start key; when non-blank
     *                            it must contain only digits and positions the browse
     *                            at the first transaction whose id is greater than or
     *                            equal to the key. The value is echoed back unchanged
     *                            on the response
     * @return an immutable response carrying the one-based page number, the echoed
     *         filter, up to {@value #PAGE_SIZE} rows, and a {@code null} error message
     * @throws ValidationException if {@code transactionIdFilter} is non-blank and
     *                             contains any non-digit character
     */
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(int page, String transactionIdFilter) {
        if (transactionIdFilter != null && !transactionIdFilter.isBlank()
                && !transactionIdFilter.matches("\\d+")) {
            throw new ValidationException("Tran ID must be Numeric ...");
        }

        int safePage = Math.max(page, 0);
        PageRequest pageRequest =
                PageRequest.of(safePage, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));

        // COTRN00C PROCESS-ENTER-KEY: a supplied (numeric) tran id is the browse
        // start key (STARTBR GTEQ + READNEXT forward); a blank key browses from the
        // lowest id. The filter is therefore applied, not merely echoed.
        boolean hasStartKey = transactionIdFilter != null && !transactionIdFilter.isBlank();
        Page<Transaction> result = hasStartKey
                ? transactionRepository.findByTranIdGreaterThanEqual(transactionIdFilter, pageRequest)
                : transactionRepository.findAll(pageRequest);

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
     * Formats an origination timestamp of the form {@code YYYY-MM-DD...} into an
     * {@code MM/DD/YY} display date, where {@code YY} is the last two digits of the
     * year. Inputs that are {@code null} or shorter than a full {@code YYYY-MM-DD}
     * date prefix yield an empty string rather than raising an exception.
     *
     * @param origTs the origination timestamp, or {@code null}
     * @return the {@code MM/DD/YY} display date, or an empty string when the input
     *         is {@code null} or too short to contain a complete date prefix
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
