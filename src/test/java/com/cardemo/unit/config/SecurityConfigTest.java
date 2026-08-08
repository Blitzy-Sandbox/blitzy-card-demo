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

import com.cardemo.config.ObservabilityConfig;
import com.cardemo.config.SecurityConfig;
import com.cardemo.model.enums.UserType;
import com.cardemo.security.JwtAuthenticationFilter;
import com.cardemo.security.JwtTokenProvider;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.UserDetailsService;
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
 *   </ul>
 *
 * <p>The chain is exercised through a real {@code FilterChainProxy} in a web application context rather
 * than through a mocked matcher, because the defect this guards against - a rule that matches nothing, or
 * an endpoint with no rule - is invisible to a test that asserts on the configuration object.
 */
class SecurityConfigTest {

    /** A signing key the provider accepts, generated per run so no key material is committed. */
    private static final String GOOD_KEY = ephemeralSigningKey();
    private static final String OTHER_KEY = "a-completely-different-key-of-sufficient-length-0987654321";
    private static final String ISSUER = "carddemo";
    /**
     * A fixed instant used only by the {@code @Primary} override test. It is deliberately in the past, so
     * that a token minted against it is unmistakably distinguishable from one minted against the production
     * bean, and it is never installed in the contexts the other tests build - those use the real production
     * clock, because the decoder validates the timestamp claims against real time and a clock fixed at any
     * literal instant would make every minted token already expired.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:30:00Z");

    /**
     * Real-time clock for the token providers these tests construct <em>directly</em> - the foreign-key,
     * foreign-issuer and expiry cases. It is deliberately not registered as a bean: the context under test
     * now publishes its own production clock, and shadowing it here would hide the very declaration the
     * blocker regression guards assert.
     */
    private static final Clock NOW = Clock.systemUTC();

    /**
     * Token lifetime handed to every {@link JwtTokenProvider} this test constructs, expressed in
     * <strong>minutes</strong> because that is the unit the provider's fourth-from-last constructor
     * parameter takes and the unit {@code carddemo.security.jwt.expiration-minutes} publishes. Thirty is
     * the mandated default. The value is named rather than inlined for one specific reason: the contract
     * was previously expressed in seconds, so a bare literal here would read as either unit and 3,600 -
     * the old default - now falls outside the provider's accepted 1..1440 range and would abort
     * construction rather than mint a token.
     */
    private static final long LIFETIME_MINUTES = 30L;

    // ---------------------------------------------------------------------------------------------
    // Context assembly helpers
    // ---------------------------------------------------------------------------------------------

    // @TestConfiguration, not @Configuration. This class is registered EXPLICITLY into a hand-built
    // context, so the annotation's only other effect matters: @SpringBootApplication component-scans
    // com.cardemo.**, the test classes are on the classpath during the integration tier, and a nested
    // @Configuration is a scan candidate while a @TestConfiguration is excluded by Boot's TypeExcludeFilter.
    // Scanned in, its @Bean methods entered the application context and collided by NAME with the real
    // definitions - which fails the context outright, because bean-definition overriding is off.
    @TestConfiguration
    static class Support {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        /**
         * The slice's time source.
         *
         * <p>{@link SecurityConfig} deliberately declares no {@code Clock} bean - the application's single
         * declaration belongs to {@code com.cardemo.config.ObservabilityConfig}, and
         * {@link #theClockIsNotDeclaredHere()} asserts that it is not duplicated here, because
         * {@code spring.main.allow-bean-definition-overriding} is {@code false} and a second declaration
         * would abort startup rather than be ignored. This slice registers only the configuration class
         * under test, so the collaborator that needs a clock gets one from here rather than from the
         * observability configuration, which would drag its logging and metric wiring into a security test.
         *
         * @return a live clock in the deployment's zone, matching what the production declaration returns
         *     when {@code carddemo.time.zone} is unset
         */
        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }

        @Bean
        JwtTokenProvider jwtTokenProvider(final Environment environment, final Clock clock) {
            return new JwtTokenProvider(
                    environment.getProperty("carddemo.security.jwt.signing-key"),
                    environment.getProperty("carddemo.security.jwt.issuer"),
                    LIFETIME_MINUTES,
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
    @DisplayName("publishes exactly one decoder, one encoder and two filter chains, and no second clock")
    void beanSurface() {
        withContext(ctx -> {
            assertThat(ctx.getBeanNamesForType(JwtDecoder.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(PasswordEncoder.class)).hasSize(1);
            // TWO chains, and the count is the contract. The business chain declares no security matcher
            // and therefore matches every request, so the metrics scrape chain can only win its one path by
            // being declared ahead of it - @Order(1) against @Order(2). A regression that deleted the scrape
            // chain would silently return /actuator/prometheus to anonymous reachability, because the
            // business chain would then serve it and its deny-all would be the only rule left; this
            // assertion is what makes that deletion fail instead.
            assertThat(ctx.getBeanNamesForType(SecurityFilterChain.class)).hasSize(2);
            // No second UserDetailsService: the scrape principal is confined to the scrape chain's own
            // authentication manager, so it cannot authenticate against any business rule.
            assertThat(ctx.getBeanNamesForType(UserDetailsService.class)).isEmpty();
            // Exactly one, and it is the slice's own: see Support#clock(). What matters here is that
            // registering SecurityConfig does not add a second one.
            assertThat(ctx.getBeanNamesForType(Clock.class)).containsExactly("clock");
            assertThat(ctx.getBean(SecurityConfig.class).getClass().getName()).contains("SecurityConfig");
        });
    }

    /**
     * BLOCKER regression guard, in both directions. Nineteen singleton beans take a {@code Clock} by
     * constructor, so a context with no declaration at all cannot refresh - and a context with <em>two</em>
     * cannot either, because the base profile sets {@code spring.main.allow-bean-definition-overriding} to
     * {@code false}, which turns a duplicate into a startup failure rather than a silent replacement. The
     * single production declaration is {@code ObservabilityConfig#clock(String)}; a declaration was twice
     * added to this class as well, and this test is what makes a third addition fail immediately instead of
     * at the next full context refresh.
     *
     * <p>It asserts three things: this class declares no bean of that type, the observability configuration
     * declares exactly the one that it does, and a collaborator that needs a clock is still satisfied - here
     * the token provider, which the {@code Support} configuration builds from an injected clock rather than
     * from a literal.
     */
    @Test
    @DisplayName("the Clock is declared by the observability configuration and never a second time here")
    void theClockIsNotDeclaredHere() throws NoSuchMethodException {
        assertThat(Arrays.stream(SecurityConfig.class.getDeclaredMethods())
                        .filter(method -> method.getAnnotation(Bean.class) != null)
                        .filter(method -> Clock.class.equals(method.getReturnType()))
                        .toList())
                .as("a second Clock declaration aborts startup, because bean-definition overriding is off")
                .isEmpty();

        final Method owner = ObservabilityConfig.class.getDeclaredMethod("clock", String.class);
        assertThat(owner.getAnnotation(Bean.class)).isNotNull();
        assertThat(owner.getReturnType()).isEqualTo(Clock.class);

        withContext(ctx -> {
            final Clock clock = ctx.getBean(Clock.class);
            assertThat(clock).isNotNull();
            assertThat(ctx.getBean(JwtTokenProvider.class)).isNotNull();
            // Two reads of a real clock never go backwards; a fixed clock would return the same instant.
            assertThat(clock.instant()).isAfterOrEqualTo(clock.instant().minusSeconds(1));
        });
    }

    /**
     * The zone is a parity contract, not a preference. The legacy CICS region rendered local civil time
     * ({@code EXEC CICS ASKTIME}/{@code FORMATTIME} at {@code app/cbl/COSGN00C.cbl:L177}) and the batch
     * timestamp is built from {@code FUNCTION CURRENT-DATE} at {@code app/cbl/CBTRN02C.cbl:L689-L705}, which
     * is likewise local. A UTC clock would shift every rendered {@code CURDATE}, {@code CURTIME} and
     * generated 26-character timestamp by the deployment's offset.
     */
    @Test
    @DisplayName("the production clock is region-local, not UTC, because the legacy region was")
    void clockIsRegionLocal() {
        // Asserted against the production declaration rather than against the bean in this slice, because
        // the slice supplies its own stand-in and would otherwise only be restating Support#clock().
        assertThat(new ObservabilityConfig().clock("").getZone()).isEqualTo(ZoneId.systemDefault());
    }

    /**
     * The documented test-override contract. A deterministic test supplies its own instance as
     * {@code @Primary}, which must win without deleting the declaration it overrides - here the slice's
     * {@code Support#clock()}, standing in for the production one - and without enabling bean
     * overriding - the base profile sets {@code spring.main.allow-bean-definition-overriding} to
     * {@code false}, so an override that relied on replacement rather than on primacy would fail at startup.
     */
    @Test
    @DisplayName("a @Primary fixed clock overrides the production clock without replacing it")
    void primaryFixedClockOverridesProductionClock() {
        final AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.setEnvironment(environment());
        ctx.setAllowBeanDefinitionOverriding(false);
        ctx.register(SecurityConfig.class, Support.class, FixedClockOverride.class);
        ctx.refresh();
        try (ctx) {
            assertThat(ctx.getBeanNamesForType(Clock.class)).hasSize(2);
            assertThat(ctx.getBean(Clock.class).instant()).isEqualTo(FIXED_INSTANT);
        }
    }

    /**
     * FINDING LOW-004, severity Low. The chain order is asserted, not described.
     *
     * <p>Four comments in {@link SecurityConfig} claimed the two request-boundary filters execute before the
     * bearer filter. They do not, and nothing in the suite would have caught the claim going stale, because
     * every other assertion about those filters drives them directly rather than through an assembled chain.
     * This test pins the assembled order so that the prose has something mechanical standing behind it.
     *
     * <p>Two properties of the order carry the guarantees the filters exist for, and both are asserted rather
     * than left to the reader:
     *
     * <ul>
     *   <li>{@code HeaderWriterFilter} precedes both, so a refusal still carries the default security
     *       headers.</li>
     *   <li>{@code AuthorizationFilter} follows both - it is last - so a refusal for want of authorization
     *       cannot pre-empt either bound, and an anonymous caller is screened. The bearer filter's position
     *       ahead of them is irrelevant to that: it refuses nothing, it only populates the security context
     *       when a token is present.</li>
     * </ul>
     */
    @Test
    @DisplayName("the request-boundary filters sit after the header writer and before authorization")
    void theRequestBoundaryFiltersSitBetweenTheHeaderWriterAndAuthorization() {
        withContext(ctx -> {
            final List<String> business = ctx.getBeansOfType(SecurityFilterChain.class).values().stream()
                    .map(chain -> chain.getFilters().stream()
                            .map(filter -> filter.getClass().getSimpleName())
                            .toList())
                    .filter(names -> names.contains("RequestBodyLimitFilter"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no chain carries the request-boundary filters; the scrape chain does not, and "
                                    + "the business chain must"));

            assertThat(business)
                    .as("both bounds are installed, in size-then-shape order")
                    .containsSubsequence("RequestBodyLimitFilter", "RequestMediaTypeFilter");
            assertThat(business)
                    .as("the header writer precedes them, so a 413 or a 415 still carries "
                            + "X-Content-Type-Options and X-Frame-Options")
                    .containsSubsequence("HeaderWriterFilter", "RequestBodyLimitFilter");
            assertThat(business)
                    .as("and authorization follows them, which is what makes both bounds apply to an "
                            + "anonymous caller - the sign-on route being the one that most needs it")
                    .containsSubsequence("RequestMediaTypeFilter", "AuthorizationFilter");
            assertThat(business.indexOf("AuthorizationFilter"))
                    .as("authorization is the last filter in the chain")
                    .isEqualTo(business.size() - 1);
            assertThat(business)
                    .as("FINDING LOW-004: the bearer filter runs BEFORE both bounds, which is the opposite of "
                            + "what four comments in SecurityConfig used to say. Asserted in the direction the "
                            + "chain actually runs, so the corrected comments cannot drift back")
                    .containsSubsequence("SecurityContextHolderFilter", "JwtAuthenticationFilter",
                            "HeaderWriterFilter", "RequestBodyLimitFilter");
        });
    }

    /** The override shape this class documents, exercised by {@link #primaryFixedClockOverridesProductionClock()}. */
    // @TestConfiguration, not @Configuration. This class is registered EXPLICITLY into a hand-built
    // context, so the annotation's only other effect matters: @SpringBootApplication component-scans
    // com.cardemo.**, the test classes are on the classpath during the integration tier, and a nested
    // @Configuration is a scan candidate while a @TestConfiguration is excluded by Boot's TypeExcludeFilter.
    // Scanned in, its @Bean methods entered the application context and collided by NAME with the real
    // definitions - which fails the context outright, because bean-definition overriding is off.
    @TestConfiguration
    static class FixedClockOverride {

        @Bean
        @Primary
        Clock carddemoFixedTestClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    /**
     * CVE-2026-22732 mitigation guard. The advisory's condition is that Spring Security's default
     * <em>lazy</em> header writing can leave the response security headers unwritten when the application
     * sets headers of its own. The configured mitigation is eager writing, and eagerness is observable
     * exactly once: the headers must already be on the response at the moment the chain hands control to the
     * application. Under the lazy default the response is wrapped and nothing is written until commit, so
     * this assertion fails - which is what makes it a regression guard rather than a restatement.
     */
    @Test
    @DisplayName("security response headers are written eagerly, before the application is reached")
    void securityHeadersAreWrittenEagerly() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/signon");
            request.setServletPath("/api/auth/signon");
            request.setRequestURI("/api/auth/signon");
            final MockHttpServletResponse response = new MockHttpServletResponse();

            final List<String> headersVisibleToApplication = new ArrayList<>();
            chain.doFilter(request, response, (req, res) ->
                    headersVisibleToApplication.addAll(((HttpServletResponse) res).getHeaderNames()));

            assertThat(headersVisibleToApplication)
                    .as("headers present when the application is entered proves eager writing")
                    .contains("X-Content-Type-Options", "X-Frame-Options", "Cache-Control");
        }
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
                "carddemo.security.jwt.signing-key", "JWT_SIGNING_KEY");
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
        assertThatThrownBy(() -> new SecurityConfig("${JWT_SIGNING_KEY}", ISSUER, 10, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.security.jwt.signing-key")
                .hasMessageContaining("unresolved")
                .hasMessageContaining("JWT_SIGNING_KEY");
    }

    @Test
    @DisplayName("a strict context refuses to resolve an unset variable before this class is constructed")
    void strictPlaceholderResolutionHappensFirst() {
        final MockEnvironment environment = environment();
        environment.setProperty("carddemo.security.jwt.signing-key", "${JWT_SIGNING_KEY_NEVER_SET}");
        assertThatThrownBy(() -> context(environment))
                .hasStackTraceContaining("Could not resolve placeholder")
                .hasStackTraceContaining("JWT_SIGNING_KEY_NEVER_SET");
    }

    /**
     * The two trailing arguments are the metrics scrape user name and credential. Blank is passed
     * throughout this test because blank is the fail-CLOSED value: the scrape chain then holds zero
     * principals. None of the assertions below concerns the scrape credential, and passing a value would
     * imply the signing-key guards depended on it.
     */
    @Test
    @DisplayName("the constructor rejects every bad signing key without naming any value")
    void constructorLevelFailFast() {
        for (final String bad : new String[] {null, "", "   ", "short", "${JWT_SIGNING_KEY}"}) {
            assertThatThrownBy(() -> new SecurityConfig(bad, ISSUER, 10, "", ""))
                    .as("signing key %s", bad == null ? "null" : "'" + bad + "'")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.security.jwt.signing-key")
                    .hasMessageContaining("JWT_SIGNING_KEY");
        }
        assertThatThrownBy(() -> new SecurityConfig(GOOD_KEY, null, 10, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.security.jwt.issuer");
        assertThatThrownBy(() -> new SecurityConfig(GOOD_KEY, ISSUER, 4, "", ""))
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
            final String foreign = new JwtTokenProvider(OTHER_KEY, ISSUER, LIFETIME_MINUTES, NOW)
                    .issueToken("USER0001", UserType.USER);
            assertThatThrownBy(() -> ctx.getBean(JwtDecoder.class).decode(foreign))
                    .isInstanceOf(JwtException.class);
        });
    }

    @Test
    @DisplayName("decoder rejects a token from a different issuer")
    void decoderRejectsForeignIssuer() {
        withContext(ctx -> {
            final String foreign = new JwtTokenProvider(GOOD_KEY, "someone-else", LIFETIME_MINUTES, NOW)
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
            final String stale = new JwtTokenProvider(GOOD_KEY, ISSUER, LIFETIME_MINUTES, past)
                    .issueToken("USER0001", UserType.USER);
            assertThatThrownBy(() -> ctx.getBean(JwtDecoder.class).decode(stale))
                    .isInstanceOf(JwtException.class);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Authorisation matrix, driven through the real servlet filter chain
    // ---------------------------------------------------------------------------------------------

    /**
     * One dispatch through the real servlet filter chain.
     *
     * @param status the status the chain produced
     * @param reachedApplication whether the terminal chain was invoked, that is whether a handler would have
     *     run
     * @param sessionCreated whether a session was minted on the way, which must never happen
     * @param challenge the {@code WWW-Authenticate} header, or {@code null} when none was written
     * @param contentType the response media type, or {@code null} when no body was written
     * @param body the response body as text, empty when none was written
     */
    private record Outcome(int status, boolean reachedApplication, boolean sessionCreated, String challenge,
            String contentType, String body) { }

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
                response.getHeader("WWW-Authenticate"),
                response.getContentType(),
                response.getContentAsString());
    }

    @Test
    @DisplayName("sign-on is the only anonymous business path, and no session is ever created")
    void anonymousSurface() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final Outcome signOn = call(ctx, "POST", "/api/auth/signon", null);
            assertThat(signOn.reachedApplication()).isTrue();
            assertThat(signOn.sessionCreated()).isFalse();

            // The health paths and build identity stay anonymous: the image's HEALTHCHECK probes
            // /actuator/health/readiness with no credential, so a rule requiring one would report a
            // permanently unhealthy container. PROMETHEUS IS DELIBERATELY ABSENT FROM THIS LIST - see
            // metricsScrapeIsNotAnonymous below.
            for (final String path : new String[] {
                    "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                    "/actuator/info" }) {
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

            // The anonymous rule matches the EXACT sign-on route, not the /api/auth namespace. Every other
            // path under that namespace - including plausible additions such as a refresh, a sign-off or a
            // password reset, none of which any CSD transaction sanctions - must fall through to
            // anyRequest().denyAll(). A prefix matcher would let all four of these through unauthenticated.
            for (final String[] namespaceProbe : new String[][] {
                    { "POST", "/api/auth" },
                    { "POST", "/api/auth/refresh" },
                    { "POST", "/api/auth/signon/extra" },
                    { "GET", "/api/auth/users" } }) {
                final Outcome overreach = call(ctx, namespaceProbe[0], namespaceProbe[1], null);
                assertThat(overreach.status())
                        .as("%s %s must not be anonymous", namespaceProbe[0], namespaceProbe[1])
                        .isEqualTo(401);
                assertThat(overreach.reachedApplication())
                        .as("%s %s must not reach a handler", namespaceProbe[0], namespaceProbe[1])
                        .isFalse();
                assertThat(overreach.sessionCreated()).isFalse();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // F-S06 - the metrics scrape is the one management endpoint that is NOT anonymous
    // ---------------------------------------------------------------------------------------------

    /** The scrape user name and credential this slice configures; neither is a production value. */
    private static final String SCRAPE_USER = "carddemo-metrics-scraper";

    /** Not a secret: a literal confined to this test, matching nothing any environment supplies. */
    private static final String SCRAPE_PASSWORD = "unit-test-scrape-credential-not-a-secret";

    /**
     * An environment that also carries a metrics scrape credential.
     *
     * @return the environment, never {@code null}
     */
    private static MockEnvironment environmentWithScrapeCredential() {
        final MockEnvironment environment = environment();
        environment.setProperty("carddemo.observability.metrics.scrape.username", SCRAPE_USER);
        environment.setProperty("carddemo.observability.metrics.scrape.password", SCRAPE_PASSWORD);
        return environment;
    }

    /**
     * Performs a {@code GET} carrying HTTP Basic credentials.
     *
     * @param ctx      the refreshed context whose chain is exercised; must not be {@code null}
     * @param uri      the request path; must not be {@code null}
     * @param user     the user name to present; must not be {@code null}
     * @param password the credential to present; must not be {@code null}
     * @return the outcome, never {@code null}
     * @throws Exception if the mock layer cannot dispatch, which is a harness failure
     */
    private static Outcome basicGet(final AnnotationConfigWebApplicationContext ctx,
                                    final String uri,
                                    final String user,
                                    final String password) throws Exception {
        final Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServletPath(uri);
        request.setRequestURI(uri);
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockFilterChain terminal = new MockFilterChain();
        chain.doFilter(request, response, terminal);
        return new Outcome(response.getStatus(), terminal.getRequest() != null,
                request.getSession(false) != null, response.getHeader("WWW-Authenticate"),
                response.getContentType(), response.getContentAsString());
    }

    /**
     * F-S06 regression guard. The scrape body renders business series - transaction volumes, reject counts
     * tagged by reject code and authentication attempt counts - so anonymous reachability is a disclosure,
     * not a convenience. This is the assertion that must fail if the path is ever returned to
     * {@code permitAll()}.
     *
     * @throws Exception if the mock layer cannot dispatch
     */
    @Test
    @DisplayName("F-S06: the metrics scrape refuses an anonymous caller and challenges for Basic")
    void metricsScrapeIsNotAnonymous() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environmentWithScrapeCredential())) {
            final Outcome anonymous = call(ctx, "GET", "/actuator/prometheus", null);
            assertThat(anonymous.status())
                    .as("an anonymous scrape must be refused; the body renders business series")
                    .isEqualTo(401);
            assertThat(anonymous.reachedApplication())
                    .as("and must not reach the Actuator handler at all")
                    .isFalse();
            assertThat(anonymous.challenge())
                    .as("the challenge must name Basic, not Bearer: a scraper cannot mint a JWT, which is "
                            + "why this path has its own chain and its own authentication manager")
                    .startsWith("Basic");
            assertThat(anonymous.sessionCreated())
                    .as("and no session may be minted on the way to the refusal")
                    .isFalse();
        }
    }

    /**
     * The other half of the contract: a correctly credentialled scrape is admitted, so securing the path
     * has not simply broken metrics collection.
     *
     * @throws Exception if the mock layer cannot dispatch
     */
    @Test
    @DisplayName("F-S06: the metrics scrape admits the configured principal and refuses a wrong credential")
    void metricsScrapeAdmitsTheConfiguredPrincipal() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environmentWithScrapeCredential())) {
            final Outcome admitted = basicGet(ctx, "/actuator/prometheus", SCRAPE_USER, SCRAPE_PASSWORD);
            assertThat(admitted.reachedApplication())
                    .as("the configured principal must reach the handler, or provisioning this credential "
                            + "would have secured the endpoint by breaking it")
                    .isTrue();
            assertThat(admitted.sessionCreated())
                    .as("a scrape arrives every fifteen seconds; a session per scrape would leak memory")
                    .isFalse();

            assertThat(basicGet(ctx, "/actuator/prometheus", SCRAPE_USER, "wrong-credential").status())
                    .as("a wrong credential must be refused")
                    .isEqualTo(401);
            assertThat(basicGet(ctx, "/actuator/prometheus", "wrong-user", SCRAPE_PASSWORD).status())
                    .as("an unknown user name must be refused")
                    .isEqualTo(401);
        }
    }

    /**
     * The fail-closed direction, which is the whole reason the credential is not fail-fast. A deployment
     * that omits the credential must lose metrics visibly rather than publish them silently.
     *
     * @throws Exception if the mock layer cannot dispatch
     */
    @Test
    @DisplayName("F-S06: with no credential configured the scrape fails CLOSED rather than open")
    void metricsScrapeFailsClosedWhenUnconfigured() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            assertThat(call(ctx, "GET", "/actuator/prometheus", null).status())
                    .as("an absent credential must not reopen the endpoint")
                    .isEqualTo(401);
            assertThat(basicGet(ctx, "/actuator/prometheus", SCRAPE_USER, SCRAPE_PASSWORD).status())
                    .as("and no principal exists to admit, so even the documented credential is refused")
                    .isEqualTo(401);
            assertThat(call(ctx, "GET", "/actuator/health/readiness", null).reachedApplication())
                    .as("while readiness stays anonymous, so the container health check keeps working")
                    .isTrue();
        }
    }

    /**
     * The scrape credential must authorise nothing beyond the scrape path. A private authority plus a
     * confined authentication manager is what guarantees that; this proves it behaviourally.
     *
     * @throws Exception if the mock layer cannot dispatch
     */
    @Test
    @DisplayName("F-S06: the scrape credential authorises no business path and no other method")
    void scrapeCredentialIsConfinedToTheScrapePath() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environmentWithScrapeCredential())) {
            for (final String path : new String[] {
                    "/api/admin/users", "/api/accounts/00000000001", "/api/transactions",
                    "/api/menu/main", "/actuator/health", "/actuator/info" }) {
                final Outcome outcome = basicGet(ctx, path, SCRAPE_USER, SCRAPE_PASSWORD);
                assertThat(outcome.status())
                        .as("the scrape credential must buy nothing at %s: the business chain knows "
                                + "nothing of it and answers 401, and an anonymous management path answers "
                                + "200 on its own merits rather than on the credential's", path)
                        .isIn(200, 401);
                if (path.startsWith("/api/")) {
                    assertThat(outcome.reachedApplication())
                            .as("%s must not be reachable with a scrape credential", path)
                            .isFalse();
                }
            }
        }
    }

    /**
     * The scrape refusal has to be readable, because an unreadable one was mistaken for a missing route.
     *
     * <p><strong>Finding, severity Medium - remediated.</strong> A review concluded that
     * {@code /actuator/prometheus} was "denied to every caller" with no matcher declared. The matcher was
     * there and the credentialled scrape worked; what was missing was any way to tell that from the response.
     * The refusal carried Spring's default body - {@code timestamp status error path} under
     * {@code application/json} - with no {@code errorCode}, no {@code correlationId} and no usable detail, so
     * a missing credential and a missing route looked identical from outside.
     *
     * <p>The cause was mechanical and is worth naming, because it recurs:
     * {@code BasicAuthenticationEntryPoint} refuses via {@code HttpServletResponse.sendError}, which forwards
     * to the registered error page, and {@code BasicErrorController} renders the body - so anything written
     * afterwards is discarded. Its bearer counterpart sets the status directly, which is why the business
     * chain's enveloping worked and this one's did not.
     *
     * @throws Exception if the mock layer cannot dispatch
     */
    @Test
    @DisplayName("F-S06: an anonymous scrape refusal carries the same problem envelope as every other refusal")
    void metricsScrapeRefusalCarriesTheEnvelope() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environmentWithScrapeCredential())) {
            final Outcome anonymous = call(ctx, "GET", "/actuator/prometheus", null);

            assertThat(anonymous.status()).isEqualTo(401);
            assertThat(anonymous.contentType())
                    .as("the envelope is a media type as much as a shape. Answering application/json is how "
                            + "this refusal escaped the contract in the first place")
                    .startsWith("application/problem+json");
            assertThat(anonymous.body())
                    .as("a caller must be able to act on the code and an operator must be able to join the "
                            + "response to a log record")
                    .contains("\"errorCode\":\"CARDDEMO-AUTHENTICATION-REQUIRED\"")
                    .contains("\"correlationId\":")
                    .doesNotContain("\"timestamp\"");
            assertThat(anonymous.body())
                    .as("the detail must describe THIS chain. Directing a scraper to the sign-on operation "
                            + "and a bearer token - which this chain refuses outright - is the misdirection "
                            + "that made the endpoint look unreachable")
                    .contains("HTTP Basic")
                    .doesNotContain("sign-on");
            assertThat(anonymous.challenge())
                    .as("the realm must name the endpoint's purpose. The framework default is the literal "
                            + "'Realm', which tells an operator debugging a failing scrape nothing")
                    .startsWith("Basic realm=")
                    .contains("carddemo-metrics-scrape");
        }
    }

    /**
     * The refusal must not disclose whether the deployment has a scrape credential at all. That is deployment
     * state, and an anonymous caller has no claim on it - so the two refusals must be indistinguishable.
     *
     * @throws Exception if the mock layer cannot dispatch
     */
    @Test
    @DisplayName("F-S06: the refusal is identical whether or not a credential is configured")
    void metricsScrapeRefusalDisclosesNothingAboutConfiguration() throws Exception {
        final String configured;
        final String absent;
        try (AnnotationConfigWebApplicationContext ctx = context(environmentWithScrapeCredential())) {
            configured = call(ctx, "GET", "/actuator/prometheus", null).body();
        }
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            absent = call(ctx, "GET", "/actuator/prometheus", null).body();
        }

        // The correlation identifier is per request by construction, so it is the one field that must differ.
        assertThat(withoutCorrelationId(absent))
                .as("an anonymous caller must not be able to probe whether a scrape credential exists. The "
                        + "distinction is written to the log instead, where the operator who needs it looks")
                .isEqualTo(withoutCorrelationId(configured));
    }

    /**
     * A non-GET reaches the chain's deny-all. It must answer inside the envelope, and it must not advertise a
     * mechanism this chain does not accept.
     *
     * @throws Exception if the mock layer cannot dispatch
     */
    @Test
    @DisplayName("F-S06: a non-GET scrape is refused 403 in the envelope, and advertises no bearer challenge")
    void metricsScrapeRefusesOtherMethodsInTheEnvelope() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environmentWithScrapeCredential())) {
            final Outcome posted = basicRequest(ctx, "POST", "/actuator/prometheus", SCRAPE_USER,
                    SCRAPE_PASSWORD);

            assertThat(posted.status())
                    .as("a scrape never writes, so every other method falls to the chain's deny-all")
                    .isEqualTo(403);
            assertThat(posted.reachedApplication()).isFalse();
            assertThat(posted.contentType()).startsWith("application/problem+json");
            assertThat(posted.body())
                    .contains("\"errorCode\":\"CARDDEMO-AUTHORIZATION-DENIED\"")
                    .contains("\"correlationId\":");
            assertThat(posted.body())
                    .as("the detail must not speak of a token. This chain authenticates HTTP Basic "
                            + "credentials and knows nothing of tokens")
                    .doesNotContain("token");
            assertThat(posted.challenge())
                    .as("RFC 7235 defines WWW-Authenticate for 401. The bearer variant on a 403 is an RFC "
                            + "6750 extension with no Basic counterpart, so telling a Basic-only caller "
                            + "'Bearer' here is worse than saying nothing")
                    .isNull();
        }
    }

    /**
     * Strips the per-request correlation identifier so two refusal bodies can be compared for everything else.
     *
     * @param body the problem envelope as written
     * @return the same text with the correlation identifier's value replaced by a fixed marker
     */
    private static String withoutCorrelationId(final String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"-\"");
    }

    /**
     * Dispatches a request with HTTP Basic credentials and an arbitrary method.
     *
     * @param ctx the configured context
     * @param method the HTTP method
     * @param uri the request path
     * @param user the user name to present
     * @param password the credential to present
     * @return the outcome of the dispatch
     * @throws Exception if the mock layer cannot dispatch
     */
    private static Outcome basicRequest(final AnnotationConfigWebApplicationContext ctx,
                                       final String method,
                                       final String uri,
                                       final String user,
                                       final String password) throws Exception {
        final Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
        final MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        request.setRequestURI(uri);
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockFilterChain terminal = new MockFilterChain();
        chain.doFilter(request, response, terminal);
        return new Outcome(response.getStatus(), terminal.getRequest() != null,
                request.getSession(false) != null, response.getHeader("WWW-Authenticate"),
                response.getContentType(), response.getContentAsString());
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

    // ---------------------------------------------------------------------------------------------
    // Default security headers, driven through the real chain with an application header already set
    // ---------------------------------------------------------------------------------------------

    /**
     * The default security headers the chain must emit on every response.
     *
     * <p>{@link SecurityConfig} configures no {@code headers(...)} block, so every one of these comes from
     * Spring Security's defaults. That is precisely why the advisory behind review finding F3 matters here:
     * it is the DEFAULT writer that was skipped, so an application relying on defaults loses all six at
     * once while an application that had spelled them out loses nothing.
     */
    private static final String[][] EXPECTED_DEFAULT_HEADERS = {
        { "X-Content-Type-Options", "nosniff" },
        { "X-XSS-Protection", "0" },
        { "Cache-Control", "no-cache, no-store, max-age=0, must-revalidate" },
        { "Pragma", "no-cache" },
        { "Expires", "0" },
        { "X-Frame-Options", "DENY" },
    };

    /**
     * Drives the real chain and returns the response, optionally with a response header already set.
     *
     * @param ctx the refreshed context supplying the chain
     * @param method the HTTP method
     * @param uri the request URI
     * @param token a bearer token, or {@code null} for an anonymous call
     * @param presetHeader an application header to set before the chain runs, or {@code null} for none
     * @return the response the chain produced
     * @throws Exception if the chain does
     */
    private static MockHttpServletResponse responseOf(final AnnotationConfigWebApplicationContext ctx,
                                                      final String method,
                                                      final String uri,
                                                      final String token,
                                                      final String presetHeader) throws Exception {
        final Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
        final MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        final MockHttpServletResponse response = new MockHttpServletResponse();
        if (presetHeader != null) {
            response.setHeader(presetHeader, "probe-correlation-id");
        }
        chain.doFilter(request, response, new MockFilterChain());
        return response;
    }

    /**
     * The default security headers are written on every response, including when the application has
     * already set one of its own.
     *
     * <p>{@link SecurityConfig} declares no {@code headers(...)} block, so all six values below come from
     * Spring Security's defaults and nothing else in this class asserts that they arrive at all. That is
     * what this test owns: the header contract itself, on the permitted path, on a rejected path, and with
     * an application header such as {@code X-Correlation-Id} already present.
     *
     * <p><strong>Scope limit, stated because it is easy to assume otherwise.</strong> This is NOT the guard
     * on review finding F3, and it does not detect the advisory behind it (CVE-2026-22732 /
     * GHSA-mf92-479x-3373, fixed in 6.5.9). That defect only manifests when a response is genuinely
     * committed before the header writer runs, and a post-commit header write is silently discarded - which
     * a real container does and {@code MockHttpServletResponse} does not. Driven through mocks, as here, the
     * writer's trailing write always records and the assertions below pass on the vulnerable 6.5.8 exactly
     * as they do on the pinned 6.5.11; that was verified by negative check rather than assumed. The
     * behavioural guard on F3 therefore lives in
     * {@link SecurityHeaderCommitContractTest}, which reproduces the commit condition in a real embedded
     * container and does discriminate between the two releases.
     *
     * @throws Exception if the chain does
     */
    @Test
    @DisplayName("the default security headers survive an application-set response header (finding F3)")
    void defaultSecurityHeadersSurviveAnApplicationSetHeader() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final String token = ctx.getBean(JwtTokenProvider.class).issueToken("USER0001", UserType.USER);

            for (final String[] probe : new String[][] {
                    { "POST", "/api/auth/signon", null },
                    { "GET", "/api/cards", token },
                    { "GET", "/api/accounts/00000000001", null } }) {
                for (final String preset : new String[] { null, "X-Correlation-Id" }) {
                    final MockHttpServletResponse response =
                            responseOf(ctx, probe[0], probe[1], probe[2], preset);
                    final String label = probe[0] + " " + probe[1]
                            + (preset == null ? " [no application header]" : " [application header set]");

                    for (final String[] header : EXPECTED_DEFAULT_HEADERS) {
                        assertThat(response.getHeader(header[0]))
                                .as("%s must carry %s", label, header[0])
                                .isEqualTo(header[1]);
                    }
                    if (preset != null) {
                        assertThat(response.getHeader(preset))
                                .as("%s must retain the application header too", label)
                                .isEqualTo("probe-correlation-id");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a tampered or foreign token authenticates nothing")
    void tamperedToken() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final String foreign = new JwtTokenProvider(OTHER_KEY, ISSUER, LIFETIME_MINUTES, NOW)
                    .issueToken("ADMIN001", UserType.ADMIN);
            final Outcome outcome = call(ctx, "GET", "/api/admin/users", foreign);
            assertThat(outcome.status()).isEqualTo(401);
            assertThat(outcome.reachedApplication()).isFalse();
            assertThat(outcome.sessionCreated()).isFalse();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The security refusals carry the same problem body every controller carries
    // ---------------------------------------------------------------------------------------------

    /**
     * The exact body an unauthenticated request receives.
     *
     * <p>Stated in full rather than matched loosely, because the defect being pinned was an <em>empty</em>
     * body: a client met a populated RFC 9457 document from every controller and nothing at all from the
     * authentication and authorisation layers, so one error handler could not cover the surface and a rejected
     * request carried no correlation identifier to tie it back to its log line. A loose assertion would pass
     * against a body that had quietly lost a member.
     *
     * <p>The correlation value is {@code unavailable} because these dispatches drive the security chain
     * directly; in a running application {@code com.cardemo.observability.CorrelationIdFilter} registers far
     * ahead of the chain and populates the diagnostic context first.
     */
    private static final String EXPECTED_UNAUTHENTICATED_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Authentication required\",\"status\":401,"
                    + "\"detail\":\"The request did not carry a usable bearer token. Obtain one from the "
                    + "sign-on operation and present it in the Authorization header.\","
                    + "\"errorCode\":\"CARDDEMO-AUTHENTICATION-REQUIRED\","
                    + "\"correlationId\":\"unavailable\"}";

    /** The exact body an authenticated but unentitled request receives, on the same terms. */
    private static final String EXPECTED_FORBIDDEN_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Authorization denied\",\"status\":403,"
                    + "\"detail\":\"The presented token is valid but does not carry the authority this "
                    + "operation requires.\",\"errorCode\":\"CARDDEMO-AUTHORIZATION-DENIED\","
                    + "\"correlationId\":\"unavailable\"}";

    /**
     * A 401 now carries the shared envelope, and still carries the bare bearer challenge.
     *
     * <p>Both halves matter. The body is the remediation; the header is the behaviour that must survive it,
     * because the writer delegates to the framework's own bearer entry point for the status and the challenge
     * and adds only the body it omits. A hand-written 401 would have been the moment the RFC 6750 form was
     * quietly lost.
     *
     * @throws Exception if the mock layer cannot dispatch, which is a harness failure
     */
    @Test
    @DisplayName("an unauthenticated request receives the shared problem envelope and keeps its challenge")
    void unauthenticatedRefusalCarriesTheSharedEnvelope() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            for (final String path : new String[] {
                    "/api/accounts/00000000001", "/api/cards", "/api/transactions", "/api/menu/main",
                    "/api/admin/users", "/nothing/declared" }) {
                final Outcome denied = call(ctx, "GET", path, null);

                assertThat(denied.status()).as("%s", path).isEqualTo(401);
                assertThat(denied.challenge())
                        .as("%s must keep the bare RFC 6750 challenge; the writer delegates rather than "
                                + "reimplements precisely so this cannot be lost", path)
                        .isEqualTo("Bearer");
                assertThat(denied.contentType())
                        .as("%s must answer the same media type a controller rejection answers", path)
                        .isEqualTo("application/problem+json");
                assertThat(denied.body())
                        .as("%s must answer the shared envelope, not an empty body", path)
                        .isEqualTo(EXPECTED_UNAUTHENTICATED_BODY);
                assertThat(denied.sessionCreated()).as("%s", path).isFalse();
            }
        }
    }

    /**
     * A 403 carries the same envelope with its own condition, and discloses nothing about the model.
     *
     * @throws Exception if the mock layer cannot dispatch, which is a harness failure
     */
    @Test
    @DisplayName("an authenticated but unentitled request receives the shared problem envelope")
    void forbiddenRefusalCarriesTheSharedEnvelope() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final String userToken = ctx.getBean(JwtTokenProvider.class)
                    .issueToken("USER0001", UserType.USER);
            final Outcome denied = call(ctx, "GET", "/api/admin/users", userToken);

            assertThat(denied.status())
                    .as("403 rather than 401: the caller authenticated and is simply not entitled")
                    .isEqualTo(403);
            assertThat(denied.reachedApplication()).isFalse();
            assertThat(denied.contentType()).isEqualTo("application/problem+json");
            assertThat(denied.body()).isEqualTo(EXPECTED_FORBIDDEN_BODY);
            assertThat(denied.body())
                    .as("and it names neither the required authority nor the token, because describing the "
                            + "authorisation model to a principal it has just refused is a disclosure")
                    .doesNotContain("ADMIN")
                    .doesNotContain(userToken);
        }
    }

    /**
     * The two refusals are distinguishable from each other and from a controller rejection only by their
     * condition, never by their shape.
     *
     * @throws Exception if the mock layer cannot dispatch, which is a harness failure
     */
    @Test
    @DisplayName("both security refusals publish exactly the members a controller rejection publishes")
    void bothRefusalsPublishTheSameMembers() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = context(environment())) {
            final String userToken = ctx.getBean(JwtTokenProvider.class)
                    .issueToken("USER0001", UserType.USER);

            for (final String body : new String[] {
                    call(ctx, "GET", "/api/cards", null).body(),
                    call(ctx, "GET", "/api/admin/users", userToken).body() }) {
                assertThat(body)
                        .as("the six members, in the order Spring's own ProblemDetail renders them")
                        .startsWith("{\"type\":\"about:blank\",\"title\":\"")
                        .contains("\"status\":")
                        .contains("\"detail\":\"")
                        .contains("\"errorCode\":\"CARDDEMO-")
                        .endsWith("\"correlationId\":\"unavailable\"}");
            }
        }
    }

    /**
     * Generates a single-use signing key for this suite.
     *
     * <p>Rule 1 Clause D forbids secrets in code, in configuration and <em>in tests</em>, with no carve-out
     * for material that happens to be synthetic: a literal key in a committed file is still committed key
     * material, indexable and copyable into a deployment, and it teaches the pattern the clause exists to
     * stop. Generating it removes the class of problem instead of declaring one instance of it harmless. The
     * value exists only in memory for the lifetime of this class, and no assertion depends on its content -
     * only on its being long enough for the algorithm to accept.
     *
     * <p>Thirty-two bytes of entropy is the HS256 minimum the token provider enforces; URL-safe unpadded
     * encoding widens that to forty-three characters, so the length guard passes with room to spare. The
     * negative paths in this class continue to use deliberately <em>invalid</em> literals - empty, blank and
     * too short - because those are the inputs under test rather than key material.
     *
     * @return a freshly generated key, never {@code null}, never logged and never persisted
     */
    private static String ephemeralSigningKey() {
        final byte[] keyMaterial = new byte[32];
        new SecureRandom().nextBytes(keyMaterial);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyMaterial);
    }

}
