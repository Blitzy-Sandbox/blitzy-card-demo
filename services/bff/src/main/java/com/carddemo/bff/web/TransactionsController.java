package com.carddemo.bff.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.TransactionsAggregator;
import com.carddemo.bff.api.TransactionsApi;
import com.carddemo.bff.model.TransactionListResponse;

/**
 * [DEFERRED] Transaction List controller (typed stub) — the backend-for-frontend
 * HTTP edge for the placeholder Transaction List screen.
 *
 * <p>This controller is the thin HTTP boundary that implements the generated
 * server interface {@link TransactionsApi} ({@code GET /api/transactions}) and
 * delegates to {@link TransactionsAggregator}. In the CardDemo walking skeleton
 * the transaction surface is intentionally a typed {@code [DEFERRED]}
 * placeholder: the aggregator returns an empty, well-formed paged
 * {@link TransactionListResponse} and this controller simply wraps that payload
 * in a {@code 200 OK}. Only the Card Detail path is wired as the single live
 * vertical tracer slice; every other aggregation surface — including this one —
 * is deliberately stubbed. Returning a typed placeholder is the <em>correct</em>
 * outcome here; fabricating transaction rows or wiring real logic would be a
 * failure.</p>
 *
 * <p><strong>Contract-first (frozen SSoT):</strong> the {@link TransactionsApi}
 * interface and the {@link TransactionListResponse} model are generated at build
 * time by the {@code openapi-generator-maven-plugin}
 * ({@code generatorName=spring}, {@code interfaceOnly=true}, {@code useTags=true})
 * from the frozen OpenAPI 3.1 contract
 * ({@code services/bff/src/main/resources/openapi/bff.openapi.yaml}, the vendored
 * byte-for-byte copy of {@code contracts/bff.openapi.yaml}). The generated
 * artifacts are never hand-edited; this controller {@code implements} the
 * generated interface so a contract mismatch fails the build rather than
 * surfacing at runtime.</p>
 *
 * <p><strong>Routing:</strong> the {@code /api/transactions} path, HTTP method,
 * query/header parameter bindings, and content negotiation are all declared on
 * the generated {@link TransactionsApi} interface and are inherited by this
 * controller. No {@code @RequestMapping}/{@code @GetMapping} mapping annotations
 * are declared here; the {@code /api} prefix is served verbatim.</p>
 *
 * <p><strong>Aggregation boundary:</strong> this class is a thin HTTP layer only.
 * It performs no domain logic, no persistence, and no downstream service call —
 * all of that (deliberately absent in the skeleton) belongs behind the
 * aggregator. The BFF owns aggregation exclusively and the React SPA binds only
 * to the BFF.</p>
 *
 * <p>Provenance: {@code [SRC: COTRN00C | TRANSACT]} — the legacy CICS
 * Transaction List program {@code COTRN00C} (CICS transaction {@code CT00})
 * listing rows from the {@code TRANSACT} VSAM KSDS registered in
 * {@code app/csd/CARDDEMO.CSD}.</p>
 *
 * @see TransactionsApi
 * @see TransactionsAggregator
 * @see TransactionListResponse
 */
@RestController
public class TransactionsController implements TransactionsApi {

    /**
     * The [DEFERRED] transaction aggregator this controller delegates to. Injected
     * via the single-argument constructor (constructor injection; no field
     * injection and no {@code @Autowired}).
     */
    private final TransactionsAggregator transactionsAggregator;

    /**
     * Creates the controller with its collaborating aggregator.
     *
     * <p>Uses constructor injection: Spring supplies the singleton
     * {@link TransactionsAggregator} bean, keeping the dependency {@code final}
     * and the controller trivially unit-testable.</p>
     *
     * @param transactionsAggregator the transaction aggregator delegate; must not
     *                               be {@code null}
     */
    public TransactionsController(TransactionsAggregator transactionsAggregator) {
        this.transactionsAggregator = transactionsAggregator;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Handles {@code GET /api/transactions} by delegating to
     * {@link TransactionsAggregator#listTransactions(String, Integer, Integer)}
     * and wrapping the resulting {@link TransactionListResponse} in a
     * {@code 200 OK}. The aggregator returns a typed, empty page in the skeleton,
     * so this endpoint is a well-formed {@code [DEFERRED]} placeholder with no
     * real aggregation, persistence, or downstream call.</p>
     *
     * <p>The {@code xCorrelationID} header is part of the frozen contract and is
     * declared here to match the generated interface signature, but it is
     * intentionally not consumed by this thin HTTP layer: correlation-ID capture
     * and MDC propagation are handled by the per-service {@code CorrelationIdFilter}
     * ({@code OncePerRequestFilter}), not by individual controllers. It is
     * therefore accepted and ignored.</p>
     *
     * @param cardNumber     optional 16-digit card-number filter ({@code CARD-NUM
     *                       X(16)}); passed through to the aggregator, which
     *                       ignores it in the skeleton
     * @param page           optional zero-based page index (contract default
     *                       {@code 0}); passed through to the aggregator
     * @param size           optional page size (contract default {@code 20},
     *                       bounded 1..100); passed through to the aggregator
     * @param xCorrelationID optional {@code X-Correlation-ID} request header;
     *                       declared to satisfy the generated contract signature
     *                       and intentionally ignored here
     * @return {@code 200 OK} wrapping the typed, empty
     *         {@link TransactionListResponse} produced by the aggregator
     */
    @Override
    public ResponseEntity<TransactionListResponse> listTransactions(String cardNumber, Integer page, Integer size, String xCorrelationID) {
        return ResponseEntity.ok(transactionsAggregator.listTransactions(cardNumber, page, size));
    }
}
