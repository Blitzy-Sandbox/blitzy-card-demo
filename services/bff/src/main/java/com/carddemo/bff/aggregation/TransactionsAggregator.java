package com.carddemo.bff.aggregation;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.TransactionListResponse;

/**
 * [DEFERRED] Transaction List aggregator (typed stub).
 *
 * <p>Backend-for-frontend aggregator that backs the placeholder Transaction
 * List screen. In the CardDemo walking skeleton this is a deliberate typed
 * placeholder: it returns an empty, well-formed paged
 * {@link TransactionListResponse} and performs <strong>no downstream call, no
 * persistence, and no business logic</strong>. Only the Card Detail path is
 * the single live vertical tracer slice; every other aggregation surface —
 * including this one — is intentionally stubbed. A typed placeholder is the
 * <em>correct</em> outcome here; fabricating transaction rows would be a
 * failure.</p>
 *
 * <p>Provenance: {@code [SRC: COTRN00C | TRANSACT]} — the legacy CICS
 * Transaction List program {@code COTRN00C} reading the {@code TRANSACT} VSAM
 * dataset (record layout {@code app/cpy/CVTRA05Y.cpy}). The BFF fronts this
 * behind the frozen contract {@code GET /api/transactions}, but the skeleton
 * wires no real aggregation.</p>
 *
 * <p>The {@link TransactionListResponse} DTO is generated at build time from
 * the frozen OpenAPI 3.1 single source of truth
 * ({@code contracts/bff.openapi.yaml}) into {@code com.carddemo.bff.model} and
 * is never hand-edited.</p>
 */
@Service
public class TransactionsAggregator {

    /**
     * Returns a typed, empty page of transaction summaries for the placeholder
     * Transaction List screen.
     *
     * <p>This is a {@code [DEFERRED]} stub: it constructs a well-formed,
     * contract-shaped {@link TransactionListResponse} with an empty
     * {@code items} collection and zeroed totals. No downstream domain service
     * is invoked and no data store is read. The {@code cardNumber} filter is
     * accepted to mirror the frozen {@code GET /api/transactions} contract but
     * is intentionally ignored in the skeleton.</p>
     *
     * @param cardNumber optional card-number filter from the contract query
     *                   ({@code CARD-NUM X(16)}); ignored by this stub
     * @param page       zero-based page index; echoed back, defaulting to
     *                   {@code 0} when not supplied
     * @param size       page size (rows per page); echoed back, defaulting to
     *                   {@code 20} when not supplied
     * @return an empty, well-formed {@link TransactionListResponse}
     *         (empty {@code items}, echoed/defaulted {@code page} and
     *         {@code size}, {@code totalItems} and {@code totalPages} of zero)
     */
    public TransactionListResponse listTransactions(String cardNumber, Integer page, Integer size) {
        TransactionListResponse response = new TransactionListResponse();
        response.setItems(List.of());
        response.setPage(page != null ? page : 0);
        response.setSize(size != null ? size : 20);
        response.setTotalItems(0L);
        response.setTotalPages(0);
        return response;
    }
}
