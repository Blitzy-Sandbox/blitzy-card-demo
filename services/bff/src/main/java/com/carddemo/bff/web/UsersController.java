package com.carddemo.bff.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.UsersAggregator;
import com.carddemo.bff.api.UsersApi;
import com.carddemo.bff.model.UserListResponse;

/**
 * CardDemo BFF &mdash; User List (admin) web/REST edge.
 *
 * <p>This {@code @RestController} is the HTTP edge for the administrative
 * <strong>User List</strong> screen of the CardDemo walking skeleton. It implements the
 * OpenAPI-generated {@link UsersApi} interface (generator {@code spring},
 * {@code interfaceOnly=true}, {@code useTags=true}) produced at build time from the frozen
 * single-source-of-truth contract {@code contracts/bff.openapi.yaml} (vendored into this module
 * at {@code src/main/resources/openapi/bff.openapi.yaml}). Because the generated interface owns
 * the HTTP path, verb, and every parameter binding, this class carries a <em>bare</em>
 * {@code @RestController} and re-declares none of them: the {@code @RequestMapping}
 * ({@code GET /api/users}), {@code @RequestParam} ({@code page}, {@code size}), and
 * {@code @RequestHeader} ({@code X-Correlation-ID}) annotations are all inherited from
 * {@link UsersApi}. The {@code /api} prefix is served verbatim and is never stripped.</p>
 *
 * <p><strong>[DEFERRED] typed stub &mdash; aggregation only.</strong> The single operation
 * {@code listUsers} ({@code GET /api/users}) is a {@code [DEFERRED]} typed stub: this thin HTTP
 * layer performs no domain logic, no persistence, and no downstream service call. It delegates
 * to {@link UsersAggregator}, which returns an empty, well-formed {@link UserListResponse} page
 * (empty {@code items}, echoed/defaulted paging, zero totals). In the walking skeleton only the
 * Card Detail path is the live vertical tracer; every other surface &mdash; including this one
 * &mdash; is a typed placeholder. Returning a typed placeholder here is the correct, required
 * outcome; fabricating user rows, exposing passwords, or inventing a real implementation would
 * be a failure (AAP&nbsp;0.7.2, 0.8).</p>
 *
 * <p><strong>Health is Actuator's, not this controller's.</strong> This class implements ONLY
 * {@link UsersApi}. The co-generated {@code HealthApi} is intentionally left unimplemented:
 * Spring Boot Actuator serves {@code /actuator/health}, which backs the docker-compose
 * healthcheck and the {@code depends_on: condition: service_healthy} start-up gate. Mapping any
 * controller to that path would raise a duplicate-mapping failure at startup.</p>
 *
 * <p><strong>Correlation id is not touched here.</strong> The generated {@code X-Correlation-ID}
 * header parameter is accepted per the contract but deliberately ignored: the sibling
 * {@code com.carddemo.bff.config.CorrelationIdFilter} ({@code OncePerRequestFilter} + SLF4J MDC)
 * owns correlation propagation for the real UI&nbsp;-&gt;&nbsp;BFF&nbsp;-&gt;&nbsp;domain hop.
 * This controller never reads or writes the MDC.</p>
 *
 * <p><strong>Dependency injection.</strong> The single collaborator {@link UsersAggregator} is
 * supplied by constructor injection into a {@code final} field; no {@code @Autowired} annotation
 * is used (a single-constructor bean is autowired implicitly). The default Spring Boot component
 * scan rooted at the BFF application (package {@code com.carddemo.bff}) auto-detects both this
 * {@code @RestController} and the {@code @Service}-annotated aggregator.</p>
 *
 * <p>Provenance: [SRC: COUSR00C | USRSEC] &mdash; app/csd/CARDDEMO.CSD (legacy CICS User List
 * transaction {@code CU00 -> COUSR00C} "List all users from USRSEC file") reading the
 * {@code USRSEC} VSAM KSDS ({@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}). The legacy
 * {@code SEC-USR-PWD} password field is intentionally NOT surfaced by the contract or by this
 * controller.</p>
 */
@RestController
public class UsersController implements UsersApi {

    /**
     * The User List aggregator collaborator. A typed {@code [DEFERRED]} stub that returns an
     * empty {@link UserListResponse}; injected via the constructor below.
     */
    private final UsersAggregator usersAggregator;

    /**
     * Create the controller with its single aggregator collaborator.
     *
     * <p>Constructor injection into a {@code final} field; Spring autowires the sole constructor
     * implicitly, so no {@code @Autowired} annotation is required.</p>
     *
     * @param usersAggregator the User List aggregator (typed {@code [DEFERRED]} stub); never
     *                        {@code null}
     */
    public UsersController(UsersAggregator usersAggregator) {
        this.usersAggregator = usersAggregator;
    }

    /**
     * {@code GET /api/users} &mdash; list administrative users. {@code [DEFERRED]}.
     *
     * <p>Thin HTTP delegation: passes the contract paging inputs straight through to
     * {@link UsersAggregator#listUsers(Integer, Integer)} and wraps the resulting typed
     * placeholder {@link UserListResponse} in {@code 200 OK}. No aggregation, persistence, or
     * downstream call happens in this layer (mirrors the legacy {@code COUSR00C} list path, but
     * is not wired in this skeleton). The {@code page} and {@code size} query parameters are
     * already bean-validated by the generated {@link UsersApi} interface
     * ({@code page >= 0}; {@code 1 <= size <= 100}) before this method is invoked.</p>
     *
     * <p>The {@code xCorrelationID} header is accepted per the contract and intentionally not
     * used here; correlation propagation and MDC are owned by {@code CorrelationIdFilter}.</p>
     *
     * @param page           optional zero-based page index (contract default {@code 0})
     * @param size           optional page size (contract default {@code 20}; {@code 1..100})
     * @param xCorrelationID optional {@code X-Correlation-ID} header; accepted but not used here
     * @return {@code 200 OK} wrapping the typed placeholder {@link UserListResponse}
     */
    @Override
    public ResponseEntity<UserListResponse> listUsers(Integer page, Integer size, String xCorrelationID) {
        // [DEFERRED] typed stub — delegate to the aggregator; no USRSEC read (see COUSR00C).
        return ResponseEntity.ok(usersAggregator.listUsers(page, size));
    }
}
