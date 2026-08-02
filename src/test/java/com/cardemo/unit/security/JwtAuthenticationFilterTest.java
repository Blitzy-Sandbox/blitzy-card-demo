/*
 * ****************************************************************************
 * Program     : JwtAuthenticationFilterTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies JwtAuthenticationFilter, the replacement for COMMAREA
 *               identity propagation across EXEC CICS XCTL. Concentrates on the
 *               contracts a reader cannot confirm by inspection: that all four
 *               input paths (absent, malformed, expired, valid) are handled
 *               distinguishably; that an absent credential still reaches the
 *               chain so sign-on stays reachable; that the SecurityContext is
 *               empty after the filter returns on EVERY path including the
 *               exception path, because a context leaked onto a pooled servlet
 *               thread would let the next request inherit the previous user's
 *               identity; that the header is length-bounded BEFORE it reaches
 *               the decoder; that no default role is ever substituted for an
 *               absent or unrecognised role claim; that the presented
 *               credential never enters the SecurityContext; and that the body
 *               runs exactly once when the bean is registered twice.
 * Source      : app/cbl/COMEN01C.cbl      (L149-L150 identity MOVEs commented
 *                                          out - identity rode the COMMAREA)
 *               app/cbl/COSGN00C.cbl      (L98-L102 RETURN TRANSID ... COMMAREA)
 *               app/cpy/COCOM01Y.cpy      (L25 CDEMO-USER-ID, L26-L28 USER-TYPE)
 *               app/csd/CARDDEMO.CSD      (L378 CC00, the only unauthenticated
 *                                          operation)
 *               app/cbl/CBACT04C.cbl      (canonical banner form, L1-L21)
 *               CONTRIBUTING.md, NOTICE   (style and licence conventions)
 *                                                                  @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.enums.UserType;
import com.cardemo.security.JwtAuthenticationFilter;
import com.cardemo.security.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Unit tests for {@link JwtAuthenticationFilter}.
 *
 * <p>Every signing key used here is generated at run time by {@link SecureRandom} and lives only for the
 * duration of one test method. No key, and no credential of any kind, is committed - Rule 1 Clause D
 * ("no secrets in code, logs, tests, or config") binds test sources exactly as it binds main sources.
 *
 * <p>The collaborators are real rather than mocked: a genuine {@link JwtTokenProvider} issues the tokens
 * and a genuine {@link NimbusJwtDecoder} verifies them, so the signature check, the expiry check and the
 * claim contract are all exercised for real. Only two thin test doubles are introduced, and each exists to
 * observe something a real collaborator cannot report: {@code CapturingChain} records the
 * {@link Authentication} that was visible to the downstream chain, and {@code CountingDecoder} records how
 * many credentials actually reached the decoder - which is how "bounded before parsing" and
 * "short-circuited" are proven rather than asserted.
 */
@DisplayName("JwtAuthenticationFilter: a bearer token replaces COMMAREA identity propagation")
class JwtAuthenticationFilterTest {

    /** Any non-blank value: the decoder under test carries no issuer validator, so this is inert. */
    private static final String ISSUER = "carddemo-test";

    /** Generated per test method. 48 random bytes, Base64-encoded, well above the 32-byte HS256 floor. */
    private String signingKey;

    private JwtTokenProvider provider;
    private CountingDecoder decoder;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        signingKey = generatedKey();
        provider = new JwtTokenProvider(signingKey, ISSUER, 60L, Clock.systemUTC());
        decoder = new CountingDecoder(decoderFor(signingKey));
        filter = new JwtAuthenticationFilter(decoder, provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String generatedKey() {
        final byte[] material = new byte[48];
        new SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    private static JwtDecoder decoderFor(final String key) {
        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private static MockHttpServletRequest requestWith(final String headerValue) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        if (headerValue != null) {
            request.addHeader("Authorization", headerValue);
        }
        return request;
    }

    private CapturingChain run(final MockHttpServletRequest request) throws ServletException, IOException {
        final CapturingChain chain = new CapturingChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    // ---------------------------------------------------------------------------------------------
    // Path 4 - valid
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("An admin token authenticates with ROLE_ADMIN and no credential enters the context")
    void adminTokenAuthenticatesWithAdminAuthority() throws Exception {
        final String token = provider.issueToken("ADMIN001", UserType.ADMIN);

        final CapturingChain chain = run(requestWith("Bearer " + token));

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.seen).isNotNull();
        assertThat(chain.seen.isAuthenticated()).isTrue();
        assertThat(chain.seen.getName()).isEqualTo("ADMIN001");
        assertThat(chain.seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
        assertThat(chain.seen.getCredentials()).as("credential must never enter the context").isNull();
        assertThat(chain.seen.getDetails()).as("no details: they capture IP and touch the session").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("context must be cleared after the filter returns").isNull();
    }

    @Test
    @DisplayName("A standard user token authenticates with ROLE_USER")
    void standardUserTokenAuthenticatesWithUserAuthority() throws Exception {
        final String token = provider.issueToken("USER0001", UserType.USER);

        final CapturingChain chain = run(requestWith("Bearer " + token));

        assertThat(chain.seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("The Bearer scheme matches case-insensitively and tolerates surrounding whitespace")
    void schemeIsMatchedCaseInsensitivelyAndTolerantOfExtraWhitespace() throws Exception {
        final String token = provider.issueToken("USER0002", UserType.USER);

        assertThat(run(requestWith("bearer " + token)).seen).isNotNull();
        assertThat(run(requestWith("BEARER " + token)).seen).isNotNull();
        assertThat(run(requestWith("BeArEr   " + token)).seen).isNotNull();
        assertThat(run(requestWith("   Bearer " + token + "   ")).seen).isNotNull();
        assertThat(run(requestWith("Bearer\t" + token)).seen).as("a tab separates the scheme too").isNotNull();
        assertThat(run(requestWith("Bearer \t " + token)).seen).as("mixed run of spaces and tabs").isNotNull();
    }

    @Test
    @DisplayName("The scheme must match as a whole token: a longer word merely starting with it is not Bearer")
    void schemeIsMatchedAsACompleteTokenNotAsAPrefix() throws Exception {
        final String token = provider.issueToken("USER0002", UserType.USER);

        // "Bearerish" starts with the scheme but is a different token. Accepting it as the bearer scheme
        // would widen the accepted header grammar beyond RFC 6750 for no benefit, so it is treated as some
        // other scheme: unauthenticated, and nothing reaches the decoder.
        for (final String header : new String[] {"Bearerish " + token, "Bearer2 " + token, "Bearer." + token}) {
            final CapturingChain chain = run(requestWith(header));
            assertThat(chain.invocations).isEqualTo(1);
            assertThat(chain.seen).as("header starting with but not equal to the scheme: %s", header).isNull();
        }
        assertThat(decoder.calls).as("a near-miss scheme never reaches the decoder").hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Path 1 - absent
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("An absent header continues the chain unauthenticated so sign-on stays reachable")
    void absentHeaderContinuesChainUnauthenticated() throws Exception {
        final CapturingChain chain = run(requestWith(null));

        assertThat(chain.invocations).as("sign-on must stay reachable").isEqualTo(1);
        assertThat(chain.seen).isNull();
        assertThat(decoder.calls).hasValue(0);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("A blank header and a non-Bearer scheme are treated as absent, not as malformed")
    void blankAndNonBearerHeadersAreTreatedAsAbsent() throws Exception {
        // Deliberately inert placeholders rather than realistic credential material: the assertion is only
        // that a scheme this filter does not implement is treated as absent, so nothing here needs to look
        // like a credential, and secret hygiene says it must not.
        for (final String header : new String[] {"   ", "Basic REDACTED_NOT_A_CREDENTIAL", "Token abc"}) {
            final CapturingChain chain = run(requestWith(header));
            assertThat(chain.invocations).isEqualTo(1);
            assertThat(chain.seen).as("header %s", header).isNull();
        }
        assertThat(decoder.calls).as("nothing was handed to the decoder").hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Path 2 - malformed
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("An undecodable credential leaves the context empty and continues the chain")
    void malformedCredentialLeavesContextEmptyAndContinues() throws Exception {
        final CapturingChain chain = run(requestWith("Bearer aaa.bbb.ccc"));

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.seen).isNull();
        assertThat(decoder.calls).hasValue(1);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("A well-formed token signed with a different key does not authenticate")
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        final JwtTokenProvider foreign =
                new JwtTokenProvider(generatedKey(), ISSUER, 60L, Clock.systemUTC());
        final String foreignToken = foreign.issueToken("ADMIN001", UserType.ADMIN);

        final CapturingChain chain = run(requestWith("Bearer " + foreignToken));

        assertThat(chain.seen).as("a signature from a different key must not authenticate").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("An implausibly long header is rejected BEFORE anything reaches the parser")
    void overLongHeaderIsRejectedBeforeParsing() throws Exception {
        final String oversized = "Bearer " + "a".repeat(JwtAuthenticationFilter.MAX_AUTHORIZATION_HEADER_LENGTH);

        final CapturingChain chain = run(requestWith(oversized));

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.seen).isNull();
        assertThat(decoder.calls).as("bounded before parsing: nothing reached the decoder").hasValue(0);
    }

    @Test
    @DisplayName("A Bearer scheme with a missing or ill-shaped credential never reaches the decoder")
    void bearerSchemeWithNoOrIllShapedCredentialIsRejectedWithoutDecoding() throws Exception {
        for (final String header : new String[] {"Bearer", "Bearer ", "Bearer   ", "Bearer a b", "Bearer !!!"}) {
            final CapturingChain chain = run(requestWith(header));
            assertThat(chain.seen).as("header %s", header).isNull();
        }
        assertThat(decoder.calls).as("no ill-shaped value reached the decoder").hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Path 3 - expired, and the folded unusable-claims condition
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("An expired token is rejected and is structurally distinguishable from a malformed one")
    void expiredTokenIsRejectedAndDistinguishedFromMalformed() throws Exception {
        final Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        final JwtTokenProvider stale = new JwtTokenProvider(signingKey, ISSUER, 60L, twoHoursAgo);
        final String expired = stale.issueToken("USER0001", UserType.USER);

        // The decoder itself must classify this as a validation failure, not a decoding failure: that
        // subtype is precisely how the filter tells "expired" from "malformed" without parsing any message.
        final Throwable raised = catchThrowable(() -> decoder.decode(expired));
        assertThat(raised).isInstanceOf(JwtValidationException.class);

        final CapturingChain chain = run(requestWith("Bearer " + expired));

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.seen).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("A verified token whose subject or role claim is absent or unrecognised grants nothing")
    void decodedTokenMissingSubjectOrRoleClaimGrantsNothing() throws Exception {
        final JwtDecoder noRole = value -> Jwt.withTokenValue(value)
                .header("alg", "HS256")
                .subject("USER0001")
                .build();
        final JwtDecoder noSubject = value -> Jwt.withTokenValue(value)
                .header("alg", "HS256")
                .claim(JwtTokenProvider.ROLE_CLAIM_NAME, JwtTokenProvider.USER_AUTHORITY)
                .build();
        final JwtDecoder unknownRole = value -> Jwt.withTokenValue(value)
                .header("alg", "HS256")
                .subject("USER0001")
                .claim(JwtTokenProvider.ROLE_CLAIM_NAME, "ROLE_SUPERUSER")
                .build();

        for (final JwtDecoder broken : new JwtDecoder[] {noRole, noSubject, unknownRole}) {
            final CapturingChain chain = new CapturingChain();
            new JwtAuthenticationFilter(broken, provider)
                    .doFilter(requestWith("Bearer a.b.c"), new MockHttpServletResponse(), chain);
            assertThat(chain.invocations).isEqualTo(1);
            assertThat(chain.seen).as("no default role may ever be substituted").isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cleanup on the exception path, single execution, short-circuit, constructor validation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The context is cleared even when the downstream chain throws (a leak would be a Blocker)")
    void contextIsClearedEvenWhenTheDownstreamChainThrows() throws Exception {
        final String token = provider.issueToken("ADMIN001", UserType.ADMIN);
        final CapturingChain exploding = new CapturingChain();
        exploding.failure = new ServletException("downstream failure");

        final Throwable raised = catchThrowable(() -> filter
                .doFilter(requestWith("Bearer " + token), new MockHttpServletResponse(), exploding));

        assertThat(raised).isInstanceOf(ServletException.class).hasMessage("downstream failure");
        assertThat(exploding.seen).as("it was authenticated before the failure").isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("a leaked context on a pooled thread is a Blocker").isNull();
    }

    @Test
    @DisplayName("The body runs exactly once when the bean is registered twice (OncePerRequestFilter)")
    void filterBodyExecutesOnlyOncePerRequestWhenRegisteredTwice() throws Exception {
        final String token = provider.issueToken("ADMIN001", UserType.ADMIN);
        final MockHttpServletRequest request = requestWith("Bearer " + token);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final CapturingChain terminal = new CapturingChain();

        // Models the real double-registration hazard exactly: one bean reachable through two chains, the
        // servlet-level invocation wrapping the in-chain one. OncePerRequestFilter removes its marker in a
        // finally block, so the guarantee it offers is about a NESTED second invocation - which is the
        // hazard. Two sequential dispatches of the same request are a different thing entirely and would
        // legitimately run the body twice.
        final FilterChain reentrant = (req, res) -> filter.doFilter(req, res, terminal);

        filter.doFilter(request, response, reentrant);

        assertThat(terminal.invocations).as("the downstream chain still runs exactly once").isEqualTo(1);
        assertThat(terminal.seen).as("the outer invocation's authentication is what survives").isNotNull();
        assertThat(terminal.seen.getName()).isEqualTo("ADMIN001");
        assertThat(decoder.calls).as("the body ran once despite two registrations").hasValue(1);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("An authentication already in the context is left untouched and the credential is not read")
    void anExistingAuthenticationIsNotReExamined() throws Exception {
        final String token = provider.issueToken("ADMIN001", UserType.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("PRESET", null));

        final CapturingChain chain = run(requestWith("Bearer " + token));

        assertThat(chain.seen).isNotNull();
        assertThat(chain.seen.getName()).as("the pre-existing authentication survives").isEqualTo("PRESET");
        assertThat(decoder.calls).as("short-circuited: the credential was never examined").hasValue(0);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("The constructor rejects a null decoder and a null token provider")
    void constructorRejectsNullCollaborators() {
        assertThatThrownBy(() -> new JwtAuthenticationFilter(null, provider))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JwtAuthenticationFilter(decoder, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ORDER places the auto-registered copy after the security chain so the in-chain copy wins")
    void orderRunsAfterTheSecurityChainSoTheInChainCopyWins() {
        assertThat(JwtAuthenticationFilter.ORDER)
                .as("must be after SecurityProperties.DEFAULT_FILTER_ORDER (-100)")
                .isGreaterThan(-100);
    }

    @Test
    @DisplayName("No response header is written, so registration order can never double a header")
    void noResponseHeaderIsWritten() throws Exception {
        final String token = provider.issueToken("ADMIN001", UserType.ADMIN);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWith("Bearer " + token), response, new CapturingChain());

        assertThat(response.getHeaderNames()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------------------------------

    /** Records the authentication visible to the downstream chain, and how often the chain ran. */
    private static final class CapturingChain implements FilterChain {

        private int invocations;
        private Authentication seen;
        private ServletException failure;

        @Override
        public void doFilter(final ServletRequest request, final ServletResponse response)
                throws ServletException {
            invocations++;
            seen = SecurityContextHolder.getContext().getAuthentication();
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Counts how many times the filter body handed a credential to the decoder. */
    private static final class CountingDecoder implements JwtDecoder {

        private final JwtDecoder delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingDecoder(final JwtDecoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public Jwt decode(final String token) {
            calls.incrementAndGet();
            return delegate.decode(token);
        }
    }
}
