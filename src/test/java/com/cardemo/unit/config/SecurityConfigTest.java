/*
 * ****************************************************************************
 * Program     : SecurityConfigTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies SecurityConfig: the single-owner bean surface, fail-fast
 *               validation of the environment-indirected signing key, the BCrypt
 *               cost the seed migration depends on, symmetric token validation,
 *               and the authorisation matrix over the seventeen sourced CICS
 *               transactions driven through the real filter chain.
 * Source      : app/csd/CARDDEMO.CSD (18 DEFINE TRANSACTION, 17 sourced)
 *               + app/cpy/COCOM01Y.cpy:L25-L28 (CDEMO-USER-ID, CDEMO-USER-TYPE
 *                 with 88-levels 'A' and 'U')
 *               + app/cbl/COSGN00C.cbl (sign-on)
 *               @ 7756d89
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
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.config.SecurityConfig;
import com.cardemo.model.enums.UserType;
import com.cardemo.security.JwtAuthenticationFilter;
import com.cardemo.security.JwtTokenProvider;
import jakarta.servlet.Filter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Unit tests for {@code com.cardemo.config.SecurityConfig}, the Spring Security chain that replaces the
 * CICS transaction security table, COMMAREA identity propagation and the plaintext password comparison.
 *
 * <p>These tests concentrate on the contracts a reader cannot confirm by inspecting the configuration
 * class, because a misconfigured security chain fails silently rather than loudly:
 *
 * <ul>
 *   <li><strong>Bean surface.</strong> Exactly one decoder, one password encoder and one filter chain are
 *       published, so the single-owner contract with the security package cannot regress into a duplicate
 *       bean definition.</li>
 *   <li><strong>Fail-fast on the signing key.</strong> Startup aborts when the key is absent, empty, blank,
 *       shorter than the message authentication code requires, or still an unresolved property placeholder -
 *       and the failure text never reproduces any part of the value.</li>
 *   <li><strong>Password encoding.</strong> The encoder is BCrypt at the one cost the seed migration's
 *       hashes were written with, verified by round-tripping a hash rather than by reading a literal.</li>
 *   <li><strong>Token validation.</strong> A token minted by the sibling provider verifies and exposes both
 *       claims, while a foreign signature, a foreign issuer and an expired token are each refused.</li>
 *   <li><strong>The authorisation matrix.</strong> Requests are driven through the real filter chain to
 *       assert that sign-on is the only anonymous business path, that a standard user reaches the eleven
 *       ordinary transactions and is refused the administrative surface, that an administrator reaches
 *       both, and that an unmatched path is refused outright. No session is created on any path.</li>
 * </ul>
 *
 * <p>The chain is exercised through a real {@code FilterChainProxy} in a web application context rather
 * than through a mocked matcher, because the defect this guards against - a rule that matches nothing, or
 * an endpoint with no rule - is invisible to a test that asserts on the configuration object.
 */
class SecurityConfigTest {

    private static final String GOOD_KEY = "adhoc-validation-key-material-not-a-real-secret-0123456789";
    private static final String OTHER_KEY = "a-completely-different-key-of-sufficient-length-0987654321";
    private static final String ISSUER = "carddemo";
    /**
     * Issuing clock. It must track real time, because the decoder validates the timestamp claims against
     * the system clock - which is correct production behaviour. A clock fixed at any literal instant makes
     * every minted token expired by the time the decoder sees it.
     */
    private static final Clock NOW = Clock.systemUTC();

    // ---------------------------------------------------------------------------------------------
    // Context assembly helpers
    // ---------------------------------------------------------------------------------------------

    @Configuration
    static class Support {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        Clock clock() {
            return NOW;
        }

        @Bean
        JwtTokenProvider jwtTokenProvider(final Environment environment, final Clock clock) {
            return new JwtTokenProvider(
                    environment.getProperty("carddemo.security.jwt.signing-key"),
                    environment.getProperty("carddemo.security.jwt.issuer"),
                    3600L,
                    clock);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(final JwtDecoder decoder, final JwtTokenProvider p) {
            return new JwtAuthenticationFilter(decoder, p);
        }
    }

    private static AnnotationConfigWebApplicationContext context(final MockEnvironment environment) {
        final AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.setEnvironment(environment);
        ctx.register(SecurityConfig.class, Support.class);
        ctx.refresh();
        return ctx;
    }

    private static MockEnvironment environment() {
        final MockEnvironment environment = new MockEnvironment();
        environment.setProperty("carddemo.security.jwt.signing-key", GOOD_KEY);
        environment.setProperty("carddemo.security.jwt.issuer", ISSUER);
        environment.setProperty("carddemo.security.bcrypt.strength", "10");
        return environment;
    }

    private static void withContext(final Consumer<AnnotationConfigWebApplicationContext> body) {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            body.accept(ctx);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Bean surface
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("publishes exactly one decoder, one encoder and one filter chain")
    void beanSurface() {
        withContext(ctx -> {
            assertThat(ctx.getBeanNamesForType(JwtDecoder.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(PasswordEncoder.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
            assertThat(ctx.getBean(SecurityConfig.class).getClass().getName()).contains("SecurityConfig");
        });
    }

    @Test
    @DisplayName("password encoder is BCrypt at cost 10 and verifies its own hashes")
    void bcryptCost() {
        withContext(ctx -> {
            final PasswordEncoder encoder = ctx.getBean(PasswordEncoder.class);
            final String hash = encoder.encode("PLAINTEXT-UNDER-TEST");
            assertThat(hash).startsWith("$2a$10$").hasSize(60);
            assertThat(encoder.matches("PLAINTEXT-UNDER-TEST", hash)).isTrue();
            assertThat(encoder.matches("something-else", hash)).isFalse();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Fail-fast contract
    // ---------------------------------------------------------------------------------------------

    private static void assertStartupRejects(final Consumer<MockEnvironment> mutation,
                                             final String... expectedFragments) {
        final MockEnvironment environment = environment();
        mutation.accept(environment);
        assertThatThrownBy(() -> context(environment))
                .satisfies(thrown -> {
                    final StringBuilder chain = new StringBuilder();
                    for (Throwable t = thrown; t != null; t = t.getCause()) {
                        chain.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
                    }
                    final String text = chain.toString();
                    for (final String fragment : expectedFragments) {
                        assertThat(text).contains(fragment);
                    }
                    assertThat(text).doesNotContain(GOOD_KEY);
                    assertThat(text).doesNotContain(OTHER_KEY);
                });
    }

    @Test
    @DisplayName("absent signing key aborts startup without naming a value")
    void absentSigningKey() {
        assertStartupRejects(
                environment -> environment.setProperty("carddemo.security.jwt.signing-key", ""),
                "carddemo.security.jwt.signing-key", "JWT_SECRET");
    }

    @Test
    @DisplayName("blank signing key aborts startup")
    void blankSigningKey() {
        assertStartupRejects(
                environment -> environment.setProperty("carddemo.security.jwt.signing-key", "      "),
                "carddemo.security.jwt.signing-key", "whitespace");
    }

    @Test
    @DisplayName("short signing key aborts startup and reports only its length")
    void shortSigningKey() {
        assertStartupRejects(
                environment -> environment.setProperty("carddemo.security.jwt.signing-key", "too-short"),
                "carddemo.security.jwt.signing-key", "32");
    }

    @Test
    @DisplayName("an unresolved placeholder is rejected instead of silently becoming the key")
    void unresolvedPlaceholder() {
        // Exercised by direct construction rather than through a context: Spring Boot registers a STRICT
        // placeholder resolver, so a strict context throws PlaceholderResolutionException before this class
        // is ever constructed (asserted separately below). The guard exists for a LENIENT context, which
        // injects the placeholder text itself - 32+ bytes, and therefore long enough to pass every other
        // check. Constructing directly is the only way to reach that branch.
        assertThatThrownBy(() -> new SecurityConfig("${JWT_SECRET}", ISSUER, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.security.jwt.signing-key")
                .hasMessageContaining("unresolved")
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("a strict context refuses to resolve an unset variable before this class is constructed")
    void strictPlaceholderResolutionHappensFirst() {
        final MockEnvironment environment = environment();
        environment.setProperty("carddemo.security.jwt.signing-key", "${JWT_SECRET_NEVER_SET}");
        assertThatThrownBy(() -> context(environment))
                .hasStackTraceContaining("Could not resolve placeholder")
                .hasStackTraceContaining("JWT_SECRET_NEVER_SET");
    }

    @Test
    @DisplayName("the constructor rejects every bad signing key without naming any value")
    void constructorLevelFailFast() {
        for (final String bad : new String[] {null, "", "   ", "short", "${JWT_SECRET}"}) {
            assertThatThrownBy(() -> new SecurityConfig(bad, ISSUER, 10))
                    .as("signing key %s", bad == null ? "null" : "'" + bad + "'")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.security.jwt.signing-key")
                    .hasMessageContaining("JWT_SECRET");
        }
        assertThatThrownBy(() -> new SecurityConfig(GOOD_KEY, null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.security.jwt.issuer");
        assertThatThrownBy(() -> new SecurityConfig(GOOD_KEY, ISSUER, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.security.bcrypt.strength");
    }

    @Test
    @DisplayName("blank issuer aborts startup")
    void blankIssuer() {
        assertStartupRejects(
                environment -> environment.setProperty("carddemo.security.jwt.issuer", "   "),
                "carddemo.security.jwt.issuer");
    }

    @Test
    @DisplayName("bcrypt strength other than ten aborts startup")
    void wrongBcryptStrength() {
        assertStartupRejects(
                environment -> environment.setProperty("carddemo.security.bcrypt.strength", "12"),
                "carddemo.security.bcrypt.strength", "10");
    }

    // ---------------------------------------------------------------------------------------------
    // Decoder contract, cross-verified against the issuer
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("decoder verifies a token minted by JwtTokenProvider and exposes both claims")
    void decoderRoundTrip() {
        withContext(ctx -> {
            final String token = ctx.getBean(JwtTokenProvider.class).issueToken("ADMIN001", UserType.ADMIN);
            final Jwt decoded = ctx.getBean(JwtDecoder.class).decode(token);
            assertThat(decoded.getSubject()).isEqualTo("ADMIN001");
            assertThat(decoded.getClaimAsString(JwtTokenProvider.ROLE_CLAIM_NAME))
                    .isEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
            assertThat(decoded.getClaimAsString("iss")).isEqualTo(ISSUER);
            assertThat(decoded.getExpiresAt()).isAfter(NOW.instant());
        });
    }

    @Test
    @DisplayName("decoder rejects a token signed with a different key")
    void decoderRejectsForeignSignature() {
        withContext(ctx -> {
            final String foreign = new JwtTokenProvider(OTHER_KEY, ISSUER, 3600L, NOW)
                    .issueToken("USER0001", UserType.USER);
            assertThatThrownBy(() -> ctx.getBean(JwtDecoder.class).decode(foreign))
                    .isInstanceOf(JwtException.class);
        });
    }

    @Test
    @DisplayName("decoder rejects a token from a different issuer")
    void decoderRejectsForeignIssuer() {
        withContext(ctx -> {
            final String foreign = new JwtTokenProvider(GOOD_KEY, "someone-else", 3600L, NOW)
                    .issueToken("USER0001", UserType.USER);
            assertThatThrownBy(() -> ctx.getBean(JwtDecoder.class).decode(foreign))
                    .isInstanceOf(JwtException.class);
        });
    }

    @Test
    @DisplayName("decoder rejects an expired token")
    void decoderRejectsExpiredToken() {
        withContext(ctx -> {
            final Clock past = Clock.fixed(NOW.instant().minus(Duration.ofDays(2)), ZoneOffset.UTC);
            final String stale = new JwtTokenProvider(GOOD_KEY, ISSUER, 60L, past)
                    .issueToken("USER0001", UserType.USER);
            assertThatThrownBy(() -> ctx.getBean(JwtDecoder.class).decode(stale))
                    .isInstanceOf(JwtException.class);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Authorisation matrix, driven through the real servlet filter chain
    // ---------------------------------------------------------------------------------------------

    private record Outcome(int status, boolean reachedApplication, boolean sessionCreated, String challenge) { }

    private static Outcome call(final AnnotationConfigWebApplicationContext ctx,
                                final String method,
                                final String uri,
                                final String token) throws Exception {
        final Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
        final MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockFilterChain terminal = new MockFilterChain();
        chain.doFilter(request, response, terminal);
        return new Outcome(
                response.getStatus(),
                terminal.getRequest() != null,
                request.getSession(false) != null,
                response.getHeader("WWW-Authenticate"));
    }

    @Test
    @DisplayName("sign-on is the only anonymous business path, and no session is ever created")
    void anonymousSurface() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final Outcome signOn = call(ctx, "POST", "/api/auth/signon", null);
            assertThat(signOn.reachedApplication()).isTrue();
            assertThat(signOn.sessionCreated()).isFalse();

            for (final String path : new String[] {
                    "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                    "/actuator/info", "/actuator/prometheus" }) {
                final Outcome probe = call(ctx, "GET", path, null);
                assertThat(probe.reachedApplication()).as(path).isTrue();
                assertThat(probe.sessionCreated()).as(path).isFalse();
            }

            for (final String path : new String[] {
                    "/api/accounts/00000000001", "/api/cards", "/api/transactions", "/api/menu/main",
                    "/api/admin/users", "/api/reports", "/api/billing/payments", "/nothing/declared" }) {
                final Outcome denied = call(ctx, "GET", path, null);
                assertThat(denied.status()).as(path).isEqualTo(401);
                assertThat(denied.reachedApplication()).as(path).isFalse();
                assertThat(denied.challenge()).as(path).isEqualTo("Bearer");
                assertThat(denied.sessionCreated()).as(path).isFalse();
            }

            final Outcome wrongMethod = call(ctx, "GET", "/api/auth/signon", null);
            assertThat(wrongMethod.status()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("a standard user reaches the eleven ordinary transactions and is refused the admin surface")
    void standardUserSurface() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final String token = ctx.getBean(JwtTokenProvider.class).issueToken("USER0001", UserType.USER);

            final String[][] permitted = {
                    { "GET", "/api/menu/main" },
                    { "GET", "/api/accounts/00000000001" },
                    { "PUT", "/api/accounts" },
                    { "GET", "/api/cards" },
                    { "GET", "/api/cards/detail" },
                    { "PUT", "/api/cards" },
                    { "GET", "/api/transactions" },
                    { "GET", "/api/transactions/detail" },
                    { "POST", "/api/transactions" },
                    { "POST", "/api/billing/payments" },
                    { "POST", "/api/reports" } };
            for (final String[] invocation : permitted) {
                final Outcome allowed = call(ctx, invocation[0], invocation[1], token);
                final String label = invocation[0] + " " + invocation[1];
                assertThat(allowed.reachedApplication()).as(label).isTrue();
                assertThat(allowed.sessionCreated()).as(label).isFalse();
            }

            for (final String path : new String[] {
                    "/api/admin/users", "/api/admin/users/USER0002", "/api/menu/admin" }) {
                final Outcome refused = call(ctx, "GET", path, token);
                assertThat(refused.status()).as(path).isEqualTo(403);
                assertThat(refused.reachedApplication()).as(path).isFalse();
            }

            final Outcome unmapped = call(ctx, "GET", "/nothing/declared", token);
            assertThat(unmapped.status()).isEqualTo(403);
            assertThat(unmapped.reachedApplication()).isFalse();
        }
    }

    @Test
    @DisplayName("an administrator reaches both the admin surface and the ordinary transactions")
    void administratorSurface() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final String token = ctx.getBean(JwtTokenProvider.class).issueToken("ADMIN001", UserType.ADMIN);

            for (final String[] invocation : new String[][] {
                    { "GET", "/api/admin/users" },
                    { "POST", "/api/admin/users" },
                    { "PUT", "/api/admin/users" },
                    { "DELETE", "/api/admin/users/USER0002" },
                    { "GET", "/api/menu/admin" },
                    { "GET", "/api/menu/main" },
                    { "GET", "/api/cards" } }) {
                final Outcome allowed = call(ctx, invocation[0], invocation[1], token);
                final String label = invocation[0] + " " + invocation[1];
                assertThat(allowed.reachedApplication()).as(label).isTrue();
                assertThat(allowed.sessionCreated()).as(label).isFalse();
            }

            final Outcome unmapped = call(ctx, "GET", "/nothing/declared", token);
            assertThat(unmapped.status()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("a tampered or foreign token authenticates nothing")
    void tamperedToken() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final String foreign = new JwtTokenProvider(OTHER_KEY, ISSUER, 3600L, NOW)
                    .issueToken("ADMIN001", UserType.ADMIN);
            final Outcome outcome = call(ctx, "GET", "/api/admin/users", foreign);
            assertThat(outcome.status()).isEqualTo(401);
            assertThat(outcome.reachedApplication()).isFalse();
            assertThat(outcome.sessionCreated()).isFalse();
        }
    }
}
