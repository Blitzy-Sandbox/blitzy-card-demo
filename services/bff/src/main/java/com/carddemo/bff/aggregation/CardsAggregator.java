package com.carddemo.bff.aggregation;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.CardListResponse;

/**
 * [DEFERRED] Credit Card List aggregator (typed stub).
 *
 * <p>Backend-for-frontend aggregation collaborator for the Credit Card List
 * screen. In the CardDemo walking skeleton the card <em>list</em> path is
 * deliberately deferred: only the Card <em>Detail</em> path is the one live,
 * fully-wired vertical tracer slice. Consequently this aggregator performs
 * <strong>no downstream service call, no persistence access, and no business
 * logic</strong>; it returns an empty, well-formed paged placeholder. A typed
 * placeholder is the intended, correct outcome for this deferred surface.</p>
 *
 * <p>The response type {@link com.carddemo.bff.model.CardListResponse} is
 * generated at build time from the frozen OpenAPI 3.1 contract
 * ({@code contracts/bff.openapi.yaml}) and is never hand-edited. This class
 * only assembles a valid, empty instance of that generated shape.</p>
 *
 * <p>Provenance: [SRC: COCRDLIC | CARDDAT] &mdash; the legacy Credit Card List
 * transaction {@code CCLI} runs program {@code COCRDLIC}, reading the
 * {@code CARDDAT} VSAM dataset.</p>
 */
@Service
public class CardsAggregator {

    /**
     * Return an empty, well-formed page of card summaries.
     *
     * <p>This is a typed {@code [DEFERRED]} stub: it does not call
     * {@code card-svc} or any datastore and applies no filtering or pagination
     * logic. It simply echoes the requested paging window back to the caller
     * with an empty result set, so the sibling controller (which implements the
     * generated {@code CardsApi} for {@code GET /api/cards}) can wrap the result
     * in {@code ResponseEntity.ok(...)} and the UI receives a contract-valid,
     * empty page.</p>
     *
     * <p>The {@code accountId} filter is accepted so the method signature maps
     * cleanly onto the generated {@code CardsApi.listCards(...)} delegation, but
     * it is intentionally ignored by this deferred stub.</p>
     *
     * @param accountId optional owning-account filter (ignored by this stub);
     *                  may be {@code null}
     * @param page      zero-based page index requested by the caller; when
     *                  {@code null} it defaults to {@code 0}
     * @param size      page size requested by the caller; when {@code null} it
     *                  defaults to {@code 20}
     * @return an empty {@link CardListResponse} echoing the requested (or
     *         defaulted) paging window, with {@code totalItems} and
     *         {@code totalPages} both zero
     */
    public CardListResponse listCards(String accountId, Integer page, Integer size) {
        CardListResponse response = new CardListResponse();
        response.setItems(List.of());
        response.setPage(page != null ? page : 0);
        response.setSize(size != null ? size : 20);
        response.setTotalItems(0L);
        response.setTotalPages(0);
        return response;
    }
}
