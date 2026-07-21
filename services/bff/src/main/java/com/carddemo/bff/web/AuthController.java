package com.carddemo.bff.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.AuthAggregator;
import com.carddemo.bff.api.AuthApi;
import com.carddemo.bff.model.LoginRequest;
import com.carddemo.bff.model.LoginResponse;

/**
 * {@code [DEFERRED]} Permissive sign-on controller (typed stub) &mdash; the BFF
 * HTTP edge for the CardDemo walking-skeleton Sign-On flow.
 *
 * <p>Web/REST layer of the {@code bff} aggregation service. This class implements
 * the OpenAPI-generated {@link AuthApi} interface (contract-first: the frozen
 * {@code contracts/bff.openapi.yaml} is the single source of truth, vendored into
 * this module at {@code src/main/resources/openapi/bff.openapi.yaml}) and returns a
 * <strong>typed placeholder</strong> response. The {@link AuthApi} interface and the
 * {@link LoginRequest}/{@link LoginResponse} DTO models are generated into
 * {@code target/generated-sources} at build time and are never hand-edited.</p>
 *
 * <p>Provenance: {@code [SRC: COSGN00 | sign-on]} &mdash; the legacy CICS Sign-On
 * transaction {@code CC00 -> COSGN00C} (registered in {@code app/csd/CARDDEMO.CSD})
 * and its {@code COSGN00} map (fields {@code USERID X(08)} and masked
 * {@code PASSWD X(08)} in {@code app/bms/COSGN00.bms}).</p>
 *
 * <p><strong>Permissive stub by design.</strong> The {@code POST /api/auth/login}
 * operation is intentionally <em>unauthenticated</em> ({@code security: []} in the
 * contract): it is the entry point that mints the token. Delegation to
 * {@link AuthAggregator} issues a <em>real</em> opaque bearer token so the token hop
 * (UI attaches it to every subsequent request) and the React Router route guards
 * function end-to-end, but there is deliberately <em>no</em> credential validation,
 * password hashing, RBAC, or session management. A typed placeholder is the correct
 * outcome for this skeleton seam; inventing authentication behavior would be a
 * failure.</p>
 *
 * <p><strong>Thin HTTP layer.</strong> The controller performs HTTP concerns only and
 * delegates aggregation to {@link AuthAggregator}; it holds no domain logic, no
 * persistence, and issues no downstream service call of its own. The HTTP method and
 * the {@code /api/auth/login} path are declared by the generated {@link AuthApi}
 * interface (the {@code /api} prefix is served verbatim and never stripped), so this
 * class carries no {@code @RequestMapping}/{@code @PostMapping} annotations.</p>
 *
 * <p>Cross-cutting concerns live elsewhere: request-body validation is enforced by the
 * bean-validation annotations on the generated {@link AuthApi} / {@link LoginRequest}
 * (a malformed body yields HTTP 400 before this method body runs); the correlation id
 * is placed into the SLF4J MDC by the sibling {@code config.CorrelationIdFilter}; and
 * {@code /actuator/health} is served by Spring Boot Actuator (the generated
 * {@code HealthApi} is intentionally left unimplemented).</p>
 */
@RestController
public class AuthController implements AuthApi {

    /**
     * Permissive sign-on aggregator. Injected via constructor (no field/setter
     * injection and no {@code @Autowired}); Spring resolves the single candidate
     * bean automatically for a one-argument constructor.
     */
    private final AuthAggregator authAggregator;

    /**
     * Creates the controller with its collaborating aggregator.
     *
     * @param authAggregator the permissive sign-on aggregator that mints the opaque
     *                        bearer token; never {@code null}
     */
    public AuthController(AuthAggregator authAggregator) {
        this.authAggregator = authAggregator;
    }

    /**
     * Permissive sign-on: delegates to {@link AuthAggregator#login(LoginRequest)} and
     * returns HTTP 200 with the resulting {@link LoginResponse}.
     *
     * <p>Mirrors the generated {@link AuthApi} contract signature verbatim. The
     * request body is validated up front by the generated interface's bean-validation
     * annotations, so this body runs only for well-formed requests; the aggregator
     * then issues a real opaque bearer token without validating credentials.
     * {@code [DEFERRED]} &mdash; no real authentication is performed.</p>
     *
     * @param loginRequest the validated sign-on credentials (userId + password, each
     *                      1&ndash;8 characters); never {@code null}
     * @param xCorrelationID the optional propagated {@code X-Correlation-ID} header;
     *                       intentionally not referenced here because the sibling
     *                       {@code config.CorrelationIdFilter} owns the SLF4J MDC
     * @return HTTP 200 with a {@link LoginResponse} carrying a real opaque bearer token
     */
    @Override
    public ResponseEntity<LoginResponse> login(LoginRequest loginRequest, String xCorrelationID) {
        return ResponseEntity.ok(authAggregator.login(loginRequest));
    }
}
