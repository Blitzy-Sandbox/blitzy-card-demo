package com.carddemo.bff.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.CardsAggregator;
import com.carddemo.bff.api.CardsApi;
import com.carddemo.bff.model.CardListResponse;

/**
 * [DEFERRED] Credit Card List controller (typed stub) &mdash; the backend-for-frontend
 * HTTP edge for the Credit Card <em>List</em> screen.
 *
 * <p>This controller implements the contract-first, build-time generated
 * {@link CardsApi} interface (OpenAPI tag {@code Cards}, operation
 * {@code listCards}, {@code GET /api/cards}) and delegates to
 * {@link CardsAggregator}. In the CardDemo walking skeleton the card <em>list</em>
 * surface is deliberately deferred: the aggregator returns an empty, well-formed
 * paged {@link CardListResponse} placeholder. Returning a typed placeholder is
 * the <strong>correct</strong> outcome here; fabricating card rows or wiring real
 * aggregation would be a failure.</p>
 *
 * <p><strong>Distinct from the live tracer.</strong> {@link CardsApi}
 * ({@code GET /api/cards}, tag {@code Cards}) is a separate generated interface
 * from {@code CardDetailApi} (the single LIVE vertical tracer slice
 * {@code GET /api/cards/{cardNumber}}, tag {@code CardDetail}, implemented by
 * {@code CardDetailController}). Although both share the {@code /api/cards} path
 * root, distinct OpenAPI tags produce distinct interfaces and therefore distinct
 * controllers. This file is the deferred list; the detail path is the live tracer
 * elsewhere.</p>
 *
 * <p><strong>Thin HTTP layer.</strong> This controller performs no domain logic,
 * no persistence, and no downstream service call. It maps the generated request
 * signature onto the aggregator and wraps the result in an HTTP {@code 200 OK}.
 * The path {@code /api/cards} is supplied by the {@code @RequestMapping} on the
 * generated {@link CardsApi} method, so no mapping annotation is (or may be)
 * declared here &mdash; the {@code /api} prefix is served verbatim.</p>
 *
 * <p>The {@code X-Correlation-ID} header parameter is part of the frozen contract
 * (the UI Axios interceptor sets it and the BFF propagates it to {@code card-svc}
 * where it is logged via SLF4J MDC). For this deferred list surface there is no
 * downstream hop, so the parameter is intentionally declared-and-ignored to keep
 * the {@code @Override} signature aligned with the generated interface.</p>
 *
 * <p>Provenance: {@code [SRC: COCRDLIC | CARDDAT]} &mdash; the legacy CICS Credit
 * Card List transaction {@code CCLI} runs program {@code COCRDLIC}, reading the
 * {@code CARDDAT} VSAM dataset.</p>
 *
 * @see CardsApi the build-time generated OpenAPI server interface (never hand-edited)
 * @see CardsAggregator the typed [DEFERRED] aggregation collaborator
 * @see CardListResponse the generated contract response DTO
 */
@RestController
public class CardsController implements CardsApi {

    /**
     * Aggregation collaborator for the Credit Card List screen. Injected via the
     * constructor (constructor injection is the enforced convention; no
     * field/setter injection and no {@code @Autowired}).
     */
    private final CardsAggregator cardsAggregator;

    /**
     * Creates the controller with its single required collaborator.
     *
     * @param cardsAggregator the [DEFERRED] card-list aggregator that produces the
     *                        typed, empty paged placeholder response; must not be
     *                        {@code null} (Spring supplies the managed bean)
     */
    public CardsController(CardsAggregator cardsAggregator) {
        this.cardsAggregator = cardsAggregator;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Handles {@code GET /api/cards} by delegating the paging window and the
     * optional owning-account filter to {@link CardsAggregator#listCards(String, Integer, Integer)}
     * and wrapping the typed placeholder result in {@code ResponseEntity.ok(...)}.
     * No filtering or pagination logic is applied in this thin edge layer; the
     * aggregator owns the (deferred) placeholder assembly.</p>
     *
     * @param accountId     optional owning-account filter ({@code CARD-ACCT-ID}); may be {@code null}
     * @param page          zero-based page index (contract default {@code 0})
     * @param size          page size, i.e. rows per page (contract default {@code 20})
     * @param xCorrelationID correlation identifier from the {@code X-Correlation-ID}
     *                       header; part of the frozen contract but declared-and-ignored
     *                       by this deferred list surface (no downstream hop)
     * @return {@code 200 OK} carrying an empty, well-formed {@link CardListResponse}
     */
    @Override
    public ResponseEntity<CardListResponse> listCards(String accountId, Integer page, Integer size, String xCorrelationID) {
        return ResponseEntity.ok(cardsAggregator.listCards(accountId, page, size));
    }
}
