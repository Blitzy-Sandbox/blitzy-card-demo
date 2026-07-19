package com.carddemo.bff.aggregation;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.LoginRequest;
import com.carddemo.bff.model.LoginResponse;

/**
 * [DEFERRED] Permissive sign-on aggregator (typed stub).
 *
 * <p>Issues a real opaque bearer token locally so the token hop and React
 * Router route guards function; performs NO credential validation and NO
 * downstream call to auth-svc (BFF -&gt; auth is a stub in this skeleton; only
 * Card Detail is the live tracer). Provenance: [SRC: COSGN00 | COSGN00C].</p>
 *
 * <p>The walking-skeleton contract keeps this seam intentionally thin: the
 * correlation-ID / token hop is real (a genuine opaque token is minted so the
 * UI can attach it to every subsequent request and the router guards can gate
 * navigation), but there is deliberately no persistence, no domain logic, no
 * password hashing, no RBAC, and no session management. A typed placeholder is
 * the correct outcome for this stub; anything more would be a hallucinated
 * implementation of a deferred capability.</p>
 *
 * <p><strong>Local-stub by AAP mandate (no BFF&nbsp;-&gt;&nbsp;auth-svc hop).</strong>
 * This login is fulfilled entirely within the BFF and issues no downstream call. That
 * is a deliberate contract, not an omission:
 * <ul>
 *   <li><strong>AAP §0.8</strong> &mdash; "Exactly one tracer slice is live end-to-end
 *       (authenticated request &rarr; BFF &rarr; card-svc &rarr; Oracle FREEPDB1 seeded
 *       row &rarr; rendered Card Detail ...); everything else is a stub."</li>
 *   <li><strong>AAP §0.1.1</strong> &mdash; the topology diagram formally marks
 *       {@code BFF -.stub.-> AUTH} (dotted stub) versus {@code BFF ==>|TRACER live| CARD}.</li>
 *   <li><strong>AAP §0.7.1</strong> &mdash; the BFF's non-card aggregators are local stubs;
 *       only the Card Detail aggregator is wired live.</li>
 * </ul>
 * Consistently, the sibling {@code config.OpenApiConfig} declares only a
 * {@code cardServiceRestClient} bean &mdash; there is intentionally no auth-svc client.
 * The passing reference in AAP §0.4 to the sign-on "posting through the BFF to auth-svc"
 * describes the eventual logical seam; per the plan's own precedence it is superseded by
 * the explicit §0.8 rule and the formal §0.1.1 diagram, so wiring a live forward here is
 * out of scope for this run and would violate the "exactly one live tracer" rule.</p>
 *
 * <p>The sign-on fields mirror the legacy CICS Sign-On screen COSGN00
 * (USERID/PASSWD, each up to 8 characters) handled by program COSGN00C over the
 * USRSEC VSAM file (transaction CC00). The user id is normalized to upper case to match
 * the direct auth-svc behavior (legacy {@code MOVE FUNCTION UPPER-CASE(USERIDI)},
 * COSGN00C.cbl L132-134), so the permissive login presents the same identity shape
 * whether invoked through the BFF or against auth-svc directly.</p>
 */
@Service
public class AuthAggregator {

    /**
     * Permissive login: echoes the (upper-cased) user id, issues an opaque token,
     * and defaults the user type. No validation is performed. [DEFERRED]
     *
     * <p>Because auth is a stub in this skeleton, this method makes no
     * downstream call and inspects no credentials. It mints a fresh, opaque
     * bearer token ({@link UUID}) so the real token hop through
     * UI -&gt; BFF -&gt; card-svc continues to function, then echoes the
     * requested user id back to the caller normalized to upper case
     * ({@link String#toUpperCase(Locale)} with {@link Locale#ROOT} for
     * deterministic, locale-independent results), mirroring the direct auth-svc
     * behavior (legacy {@code MOVE FUNCTION UPPER-CASE(USERIDI)}). This keeps the
     * returned identity identical whether the client authenticates through the BFF
     * or against auth-svc directly.</p>
     *
     * @param request the sign-on request (userId + password); never {@code null}
     * @return a typed placeholder {@link LoginResponse} carrying a real opaque token
     */
    public LoginResponse login(LoginRequest request) {
        LoginResponse response = new LoginResponse();
        response.setToken(UUID.randomUUID().toString());
        // Normalize to upper case (legacy MOVE FUNCTION UPPER-CASE(USERIDI); COSGN00C.cbl L132-134),
        // locale-independent, to match auth-svc's LoginResponse.userId exactly (MINOR-4 parity fix).
        response.setUserId(request.getUserId().toUpperCase(Locale.ROOT));
        response.setUserType(LoginResponse.UserTypeEnum.U);
        response.setExpiresIn(3600L);
        response.setTokenType("Bearer");
        return response;
    }
}
