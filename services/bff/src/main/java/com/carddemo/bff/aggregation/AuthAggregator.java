package com.carddemo.bff.aggregation;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.carddemo.bff.model.LoginRequest;
import com.carddemo.bff.model.LoginResponse;

/**
 * Permissive sign-on aggregator &mdash; the real BFF&nbsp;-&gt;&nbsp;auth-svc token hop.
 *
 * <p>Forwards the UI's Sign-On {@code POST /api/auth/login} to auth-svc's
 * {@code POST /auth/login} via the injected {@link RestClient}, so that
 * <strong>auth-svc &mdash; not the BFF &mdash; is the single authority that issues the bearer
 * token</strong>. The token auth-svc returns is passed straight back to the UI, which attaches it
 * to every subsequent request; React Router route guards gate navigation on its presence.
 * Provenance: {@code [SRC: COSGN00C | COSGN00.bms]}.</p>
 *
 * <p><strong>Real seam over a permissive stub.</strong> The AAP designates this hop real even
 * though authentication itself is a permissive stub:
 * <ul>
 *   <li><strong>AAP §0.1.3</strong> &mdash; "create an {@code auth-svc} permissive login stub that
 *       <em>issues a real token</em> and propagates a correlation ID through the UI &rarr; BFF
 *       &rarr; card-svc chain."</li>
 *   <li><strong>AAP §0.4</strong> &mdash; "the UI Sign-On <em>posts through the BFF to
 *       {@code auth-svc}, which returns a token</em>; the token is carried on subsequent requests
 *       and React Router route guards enforce its presence."</li>
 * </ul>
 * "Permissive stub" describes auth-svc's <em>behavior</em> (no credential validation, no password
 * hashing, no RBAC, no session management &mdash; any well-formed request succeeds), not the
 * absence of the hop. The §0.8 "exactly one tracer slice is live" rule governs the one live
 * <em>data</em> tracer (Card Detail reading a seeded Oracle row); it does not forbid the real
 * service-to-service hops the same section calls real (the correlation-ID hop, and here the token
 * hop). Consistently, the sibling {@code config.OpenApiConfig} now declares an
 * {@code authServiceRestClient} bean alongside {@code cardServiceRestClient}.</p>
 *
 * <p><strong>Aggregation only.</strong> The BFF owns no domain logic and no persistence: this
 * class holds no credential logic, mints no token, and normalizes nothing. auth-svc owns token
 * issuance and the legacy {@code MOVE FUNCTION UPPER-CASE(USERIDI)} user-id normalization
 * ({@code COSGN00C.cbl} L132-134), so the identity shape is defined in exactly one place and is
 * identical whether the client authenticates through the BFF or against auth-svc directly. The
 * sign-on fields mirror the legacy CICS Sign-On screen COSGN00 (USERID/PASSWD, each up to 8
 * characters) handled by program COSGN00C over the USRSEC VSAM file (transaction CC00).</p>
 *
 * <p><strong>Contract is the single source of truth (SSoT).</strong> Both {@link LoginRequest}
 * and {@link LoginResponse} are generated at build time from the frozen {@code contracts/bff.openapi.yaml}
 * and are field-identical to auth-svc's own {@code LoginRequest}/{@code LoginResponse} (userId +
 * password in; token, userId, userType, expiresIn, tokenType out), so the request body serializes
 * and the response body deserializes directly across the hop with no intermediate type.</p>
 *
 * <p><strong>Downstream failure.</strong> A failed hop is left to propagate to the sibling
 * {@code web.GlobalExceptionHandler}: an auth-svc error <em>status</em>
 * ({@code RestClientResponseException}) surfaces as {@code 502 Bad Gateway}, and an auth-svc
 * connection failure / timeout ({@code ResourceAccessException}) as {@code 500}, both as
 * {@code application/problem+json} carrying the correlation id. No credential or token detail is
 * ever echoed to the client.</p>
 */
@Service
public class AuthAggregator {

    /**
     * Synchronous HTTP client for the sign-on hop, pre-configured with auth-svc's base URL, bounded
     * connect/read timeouts, and the correlation-ID forwarding interceptor by
     * {@code config/OpenApiConfig}. Injected by constructor and held {@code final} for immutability
     * and thread safety. The constructor parameter is named to match the {@code authServiceRestClient}
     * bean name, keeping injection unambiguous alongside the sibling {@code cardServiceRestClient}.
     */
    private final RestClient authServiceRestClient;

    /**
     * Constructor injection of the auth-svc {@link RestClient}. As the sole constructor, Spring
     * autowires it automatically (constructor injection is the enforced convention; no field or
     * setter injection).
     *
     * @param authServiceRestClient the synchronous client bean for auth-svc; Spring supplies the
     *                              managed {@code authServiceRestClient} bean, so this is never
     *                              {@code null}
     */
    public AuthAggregator(RestClient authServiceRestClient) {
        this.authServiceRestClient = authServiceRestClient;
    }

    /**
     * Permissive sign-on through the real BFF&nbsp;-&gt;&nbsp;auth-svc hop.
     *
     * <p>Issues a real synchronous {@code POST /auth/login} to auth-svc (served at auth-svc's root;
     * no {@code /api} prefix, which belongs only to the BFF's own inbound contract), sending the
     * generated {@link LoginRequest} as the JSON body and extracting auth-svc's {@link LoginResponse}
     * directly. auth-svc mints the real opaque bearer token, normalizes the user id to upper case,
     * and returns the full response, which is passed back unchanged. The correlation id is
     * <em>not</em> set here: the client interceptor configured in {@code config/OpenApiConfig}
     * forwards the MDC {@code correlationId} onto the outbound request automatically.</p>
     *
     * @param request the sign-on request (userId + password); never {@code null}
     * @return the {@link LoginResponse} issued by auth-svc, carrying the real bearer token, the
     *         upper-cased user id, the user type, the token lifetime, and the token scheme
     * @throws org.springframework.web.client.RestClientResponseException when auth-svc returns an
     *         error status (mapped to {@code 502 Bad Gateway} by the web layer)
     * @throws org.springframework.web.client.ResourceAccessException when auth-svc is unreachable or
     *         the request times out (mapped to {@code 500} by the web layer)
     */
    public LoginResponse login(LoginRequest request) {
        return authServiceRestClient.post()
                .uri("/auth/login")
                .body(request)
                .retrieve()
                .body(LoginResponse.class);
    }
}
