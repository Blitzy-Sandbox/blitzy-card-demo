package com.carddemo.bff.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.AccountsAggregator;
import com.carddemo.bff.api.AccountsApi;
import com.carddemo.bff.model.AccountView;

/**
 * CardDemo BFF &mdash; Account View web/REST edge (typed {@code [DEFERRED]} stub).
 *
 * <p>This {@code @RestController} is the backend-for-frontend HTTP edge for the
 * Account View screen. It implements the OpenAPI-generated {@link AccountsApi}
 * interface (generator {@code spring}, {@code interfaceOnly=true},
 * {@code useTags=true}) produced at build time from the frozen single-source-of-truth
 * contract {@code contracts/bff.openapi.yaml} (vendored into this module at
 * {@code src/main/resources/openapi/bff.openapi.yaml}). Because the generated
 * interface owns the HTTP path, verb, and every parameter binding, this class
 * carries a <em>bare</em> {@code @RestController} and re-declares none of them:
 * {@code @RequestMapping}, {@code @GetMapping}, {@code @PathVariable}, and
 * {@code @RequestHeader} are all inherited from {@link AccountsApi}, so the
 * operation is served verbatim at {@code GET /api/accounts/{accountId}}.</p>
 *
 * <p><strong>[DEFERRED] typed stub &mdash; the correct outcome.</strong> In this
 * walking-skeleton run only the Card Detail vertical slice is the live tracer
 * (AAP&nbsp;0.7.2). The Account View path is deliberately not wired: this controller
 * is a thin HTTP layer that delegates to {@link AccountsAggregator}, which returns a
 * well-formed, typed placeholder {@link AccountView}. There is <strong>no</strong>
 * domain logic, <strong>no</strong> persistence, and <strong>no</strong> downstream
 * service call in this class. Returning a typed placeholder is the required,
 * correct result; inventing a real account read would be a defect.</p>
 *
 * <p><strong>Thin HTTP layer, aggregation lives elsewhere.</strong> All aggregation
 * behavior is owned by {@link AccountsAggregator} (the BFF's aggregation seam); this
 * controller performs only request-to-aggregator delegation and HTTP response
 * wrapping. The aggregator is supplied via constructor injection (single
 * {@code final} collaborator, no field/setter injection and no {@code @Autowired};
 * Spring resolves the single constructor automatically).</p>
 *
 * <p><strong>Health is Actuator's, not this controller's.</strong> This class
 * implements ONLY {@link AccountsApi}. The co-generated {@code HealthApi} is
 * intentionally left unimplemented: Spring Boot Actuator serves
 * {@code /actuator/health}, which backs the docker-compose healthcheck and the
 * {@code depends_on: condition: service_healthy} start-up gate. Mapping any
 * controller onto that path would raise a duplicate-mapping failure at start-up.</p>
 *
 * <p><strong>Correlation id is not touched here.</strong> The generated
 * {@code X-Correlation-ID} header parameter is accepted per the frozen contract but
 * is deliberately ignored by this controller: correlation propagation for the real
 * UI&nbsp;-&gt;&nbsp;BFF&nbsp;-&gt;&nbsp;card-svc hop is owned by the sibling
 * {@code com.carddemo.bff.config.CorrelationIdFilter} ({@code OncePerRequestFilter}
 * + SLF4J MDC). This controller never reads or writes the MDC.</p>
 *
 * <p>Provenance: [SRC: COACTVWC | ACCTDAT] &mdash; the legacy CICS Account View
 * program {@code COACTVWC} ("Accept and process Account View request",
 * {@code app/cbl/COACTVWC.cbl}) reading the {@code ACCTDAT} VSAM dataset; service
 * topology per {@code app/csd/CARDDEMO.CSD}.</p>
 */
@RestController
public class AccountsController implements AccountsApi {

    /**
     * BFF aggregation seam for the Account View screen. Supplied by constructor
     * injection and never {@code null}; this controller holds no other state.
     */
    private final AccountsAggregator accountsAggregator;

    /**
     * Creates the Account View controller.
     *
     * <p>Constructor injection of the single {@link AccountsAggregator}
     * collaborator. No {@code @Autowired} is required: Spring injects the sole
     * constructor argument automatically for a component with exactly one
     * constructor.</p>
     *
     * @param accountsAggregator the BFF Account View aggregation seam; must not be
     *                           {@code null}
     */
    public AccountsController(AccountsAggregator accountsAggregator) {
        this.accountsAggregator = accountsAggregator;
    }

    /**
     * {@code GET /api/accounts/{accountId}} &mdash; retrieve an account view by id.
     * {@code [DEFERRED]} typed stub.
     *
     * <p>Delegates to {@link AccountsAggregator#getAccount(String)} and wraps the
     * returned typed placeholder {@link AccountView} in a {@code 200 OK} response.
     * Performs no {@code ACCTDAT} read and no downstream call of any kind (mirrors
     * the legacy {@code COACTVWC} view path, but is not wired in this skeleton). The
     * {@code accountId} &mdash; already validated against {@code ^[0-9]{1,11}$} and a
     * max length of 11 by the generated interface &mdash; is passed through to the
     * aggregator. The {@code xCorrelationID} header is accepted per the contract and
     * intentionally not used here (MDC is owned by {@code CorrelationIdFilter}).</p>
     *
     * @param accountId      validated account identifier from the path
     *                       (ACCT-ID, PIC&nbsp;9(11))
     * @param xCorrelationID optional {@code X-Correlation-ID} header; accepted per
     *                       the contract but not used by this controller
     * @return {@code 200 OK} wrapping a typed placeholder {@link AccountView}
     */
    @Override
    public ResponseEntity<AccountView> getAccount(String accountId, String xCorrelationID) {
        return ResponseEntity.ok(accountsAggregator.getAccount(accountId));
    }
}
