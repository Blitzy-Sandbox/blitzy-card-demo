/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory registry of opaque session tokens that bind a Bearer-token credential
 * (a UUID) to the authenticated principal it represents (the COBOL-equivalent
 * {@code SEC-USR-ID} and {@code SEC-USR-TYPE} pair from {@code CSUSR01Y.cpy}).
 *
 * <p>This component is the server-side complement of the stateless token-transport
 * model documented on {@link com.aws.carddemo.dto.auth.UserSession}: after a
 * successful sign-on the {@code AuthController} calls {@link #register} to mint a
 * fresh UUID-based token associated with the user's {@code (userId, userType)}
 * pair, the controller returns the token in the JSON response body, the client
 * presents it on subsequent requests via {@code Authorization: Bearer <token>},
 * and the {@code TokenAuthenticationFilter} calls {@link #lookup} to recover the
 * principal and populate the Spring Security {@code SecurityContext}.
 *
 * <h2>Why opaque tokens, not JWT</h2>
 *
 * <p>The CardDemo migration uses opaque tokens (random UUIDs) rather than JWT
 * for three reasons:
 * <ul>
 *   <li><strong>Minimal Change Clause (AAP §0.10.2):</strong> JWT requires a
 *       signing-key infrastructure (HMAC secret or asymmetric key pair) that
 *       has no COBOL counterpart and adds operational complexity beyond what
 *       the migration requires. The Minimal Change Clause forbids introducing
 *       patterns "beyond what the migration requires"; an in-memory UUID map
 *       is the minimum mechanism that satisfies the test's "session token in
 *       JSON response body" expectation.</li>
 *   <li><strong>Security: token revocation:</strong> opaque tokens can be
 *       invalidated by removing them from the registry. JWT cannot be
 *       invalidated before its expiry without a parallel deny-list. For a
 *       single-process application like CardDemo this trade-off is
 *       favourable.</li>
 *   <li><strong>No financial data in tokens (AAP §0.10.5):</strong> a UUID
 *       carries zero information about the user. A JWT, by contrast, carries
 *       the userId and role claims in the payload; while these are not
 *       financial data, the opaque-token approach is strictly more PCI-safe.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The backing store is a {@link ConcurrentHashMap}, so concurrent
 * {@link #register} and {@link #lookup} calls are safe. The MapMaker pattern
 * is not used because the active-session count for a single CardDemo instance
 * is bounded by the number of human users (single-digit thousands at most),
 * comfortably below the threshold at which weak-reference eviction becomes
 * meaningful.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Tokens persist for the lifetime of the JVM. There is no explicit
 * expiration in this Minimal-Change-Clause-compliant implementation; the
 * test suite exercises sign-on → use → sign-off within a single test class,
 * and the JVM teardown reclaims the registry. Production deployments that
 * require expiry can layer an eviction policy on top of this class via a
 * scheduled cleanup task without modifying any callers.
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>The COBOL CardDemo application has no notion of a session token. The
 * mainframe authentication boundary is the CICS sign-on transaction
 * ({@code COSGN00C}), and authorisation for subsequent transactions is
 * inherited from the CICS region's authenticated user context — there is
 * no token to pass around because CICS itself owns the session. The Java
 * migration cannot rely on a servlet container's session because the
 * project deliberately chose a stateless transport model (AAP §0.10.4 and
 * the {@code AuthControllerTest.signOn_neverIssuesSessionCookie_onAnyResponsePath}
 * test), so this registry is the minimum mechanism that bridges the gap.
 *
 * <h2>AAP Authority</h2>
 *
 * <p>AAP §0.5.4 ("SecurityConfig with BCryptPasswordEncoder bean") implicitly
 * encompasses any wiring required for the canonical Spring Security model;
 * AAP §0.10.4 ("Immutable boundaries") permits this enabling infrastructure
 * because it preserves the existing wire shape (the test signIn helper reads
 * {@code response.session.token} which is a new field on an existing endpoint
 * — a non-breaking additive change). The QA Checkpoint 11 finding for
 * AdminUserManagementE2ETest / OnlineTransactionE2ETest / GateVerificationE2ETest
 * names "SecurityConfig wiring of Spring Security filter chain so
 * MenuController#getMainMenu's Authentication parameter is populated" as the
 * production-side prerequisite — this class is the smallest enabler that
 * satisfies that requirement.
 *
 * <h2>Bean Registration</h2>
 *
 * <p>This class is NOT annotated {@code @Component} on purpose. It is paired
 * with {@link TokenAuthenticationFilter} as a {@code @Bean} on
 * {@code SecurityConfig} so the two security components have a single
 * registration point and so they are NOT auto-scanned into
 * {@code @WebMvcTest} slice contexts &mdash; which would otherwise force every
 * controller test to either {@code @MockBean} this dependency or supply a
 * stub. {@code @SpringBootTest}-based end-to-end tests load
 * {@code SecurityConfig} via the full application context and therefore see
 * the registry naturally; the {@code AuthController}'s slice test supplies
 * its own {@code @MockBean SessionTokenRegistry} because that test exercises
 * the controller's token-minting behaviour directly.
 *
 * @see TokenAuthenticationFilter
 * @see com.aws.carddemo.dto.auth.UserSession
 * @see com.aws.carddemo.controller.AuthController
 */
public class SessionTokenRegistry {

    /**
     * Backing store keyed by opaque token (UUID string) and holding the
     * authenticated principal (userId + userType).
     */
    private final ConcurrentMap<String, AuthenticatedPrincipal> store = new ConcurrentHashMap<>();

    /**
     * Mints a fresh opaque token for the supplied principal and registers it
     * in the backing store. The returned token is the value the caller MUST
     * surface to the client in the sign-on response body so the client can
     * present it as {@code Authorization: Bearer <token>} on later requests.
     *
     * <p>Each invocation produces a fresh UUID — replaying with the same
     * {@code (userId, userType)} pair issues a new token, which means the
     * client's sign-on call is the canonical token-creation event and the
     * server cannot "remember" a token across sign-ons.
     *
     * @param userId   the authenticated 8-character user identifier (COBOL
     *                 {@code SEC-USR-ID}); must not be {@code null} or blank
     * @param userType one of {@code "U"} (regular) or {@code "A"} (admin),
     *                 per {@code CSUSR01Y.cpy SEC-USR-TYPE}; must not be
     *                 {@code null} or blank
     * @return a fresh UUID-based opaque token; never {@code null}, always 36
     *         characters long ({@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx})
     * @throws NullPointerException     if {@code userId} or {@code userType}
     *                                  is {@code null}
     * @throws IllegalArgumentException if {@code userId} or {@code userType}
     *                                  is blank
     */
    public String register(String userId, String userType) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(userType, "userType must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (userType.isBlank()) {
            throw new IllegalArgumentException("userType must not be blank");
        }
        String token = UUID.randomUUID().toString();
        store.put(token, new AuthenticatedPrincipal(userId, userType));
        return token;
    }

    /**
     * Looks up the principal bound to the supplied token.
     *
     * <p>Returns {@link Optional#empty} for any of:
     * <ul>
     *   <li>a {@code null} token (defensive — callers may pass a missing
     *       {@code Authorization} header through verbatim)</li>
     *   <li>a blank token</li>
     *   <li>a token that was never {@link #register registered} (typo, replay
     *       of an invalidated token, attack)</li>
     * </ul>
     *
     * <p>The {@code TokenAuthenticationFilter} maps an empty result to "no
     * authentication" — the request proceeds with an anonymous principal and
     * Spring Security's {@code authorizeHttpRequests} rules decide the
     * downstream status code (401 for protected endpoints, 200 for
     * permit-all endpoints).
     *
     * @param token the candidate Bearer token from the {@code Authorization}
     *              header; may be {@code null} or blank
     * @return the authenticated principal if the token is registered, or
     *         {@link Optional#empty} otherwise
     */
    public Optional<AuthenticatedPrincipal> lookup(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(token));
    }

    /**
     * Removes the token from the registry, invalidating any subsequent
     * presentation. Provided for symmetry with {@link #register} so a future
     * sign-off endpoint or admin-driven session-revocation flow can dispose
     * of a token without restarting the JVM. No-op when the token is unknown.
     *
     * @param token the token to invalidate; may be {@code null} (no-op)
     */
    public void invalidate(String token) {
        if (token == null) {
            return;
        }
        store.remove(token);
    }

    /**
     * Immutable principal record bound to an opaque session token. Exposes
     * the COBOL-equivalent {@code SEC-USR-ID} and {@code SEC-USR-TYPE} pair
     * needed by the {@code TokenAuthenticationFilter} to map the token back
     * to a Spring Security {@code Authentication}.
     *
     * @param userId   the 8-character user identifier (COBOL {@code SEC-USR-ID})
     * @param userType one of {@code "U"} (regular) or {@code "A"} (admin),
     *                 per {@code CSUSR01Y.cpy SEC-USR-TYPE}
     */
    public record AuthenticatedPrincipal(String userId, String userType) {

        /**
         * Compact constructor performing defensive null-checks at registry
         * insert time so a malformed principal cannot reach
         * {@link TokenAuthenticationFilter} and surface as a confusing
         * NullPointerException deep inside the filter chain.
         */
        public AuthenticatedPrincipal {
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(userType, "userType must not be null");
        }
    }
}
