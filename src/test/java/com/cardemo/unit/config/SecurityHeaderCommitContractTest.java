/*
 * ****************************************************************************
 * Program     : SecurityHeaderCommitContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Reproduces the precondition of CVE-2026-22732 / GHSA-mf92-479x-3373
 *               against the real filter chain in a real servlet container, and
 *               asserts that the pinned Spring Security release writes its default
 *               security headers anyway. This is the BEHAVIOURAL guard on review
 *               finding F3, which was otherwise remediated by a version pin alone
 *               and therefore invisible to every other test in this repository.
 * Source      : app/cbl/COSGN00C.cbl      (the sign-on transaction whose HTTP
 *                                          successor is exercised here)
 *               app/csd/CARDDEMO.CSD      (transaction-to-endpoint inventory)
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
 * language governing permissions and limitations under the License.
 * ****************************************************************************
 */

package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.config.SecurityConfig;
import com.cardemo.security.JwtAuthenticationFilter;
import com.cardemo.security.JwtTokenProvider;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Asserts that the default security headers survive a response the application commits itself.
 *
 * <h2>Why this test exists, and why it needs a real container</h2>
 *
 * <p>Review finding F3 reported that Spring Security 6.5.8 "can omit default security headers when
 * application code sets a response header", and was remediated by pinning 6.5.11. A version pin is
 * invisible to a test suite: nothing else here would notice if {@code spring-security.version} were
 * lowered, or if the property were deleted and the parent's managed version took over again.
 *
 * <p>The precondition is narrower than the finding's summary suggests, and the difference matters because
 * the obvious test does not detect the defect. Comparing the two releases shows that 6.5.11 adds
 * {@code setHeader}, {@code setIntHeader} and {@code addIntHeader} overrides to
 * {@code org.springframework.security.web.util.OnCommittedResponseWrapper}, which 6.5.8 overrode only for
 * {@code addHeader}. That wrapper exists to notice the moment a response becomes committed, so that
 * {@code HeaderWriterFilter} can write its headers before it is too late. On 6.5.8 an application that
 * declares its length with {@code setHeader("Content-Length", n)} rather than
 * {@code setContentLength(n)} slips past the wrapper's accounting: the response commits when the nth byte
 * is written, the wrapper never notices, and the filter's trailing write happens against an
 * already-committed response, where it is silently discarded.
 *
 * <p><strong>That last step is why a mock response cannot express this test.</strong>
 * {@code HeaderWriterFilter} writes headers in a {@code finally} block as well as on commit, and against
 * {@code MockHttpServletResponse} the late write still records, so the headers appear present on both
 * releases and the probe passes either way. Only a real container discards a post-commit header. This test
 * was in fact written against mocks first, and was discarded after a negative check showed it passing
 * unchanged on 6.5.8 - the very failure it was supposed to detect.
 *
 * <p>Measured on this toolchain with the real container below, {@code Content-Length} declared through
 * {@code setHeader}:
 *
 * <pre>
 *   spring-security-web 6.5.11 -&gt; X-Content-Type-Options: nosniff, X-Frame-Options: DENY
 *   spring-security-web 6.5.8  -&gt; both headers ABSENT
 * </pre>
 *
 * <p>The {@code setContentLength} arm is asserted alongside it and passes on both releases. Keeping both
 * arms is what makes the failure diagnosable rather than merely visible: if only the tracked arm survives a
 * future regression, the fault is in commit detection, whereas if both fail the header configuration itself
 * has gone.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>This is not a test of {@code CorrelationIdFilter}. That filter runs at
 * {@code Ordered.HIGHEST_PRECEDENCE}, outside the security chain, and the header it sets is not a
 * {@code Content-Length}, so it does not by itself create the condition reproduced here. The exposure this
 * closes belongs to any handler on the chain that declares its own content length as a header, and the
 * point of asserting it is that no handler should have to know that distinction for the headers to appear.
 *
 * <h2>How to run it</h2>
 *
 * <p>{@code mvn -B test -Dtest=SecurityHeaderCommitContractTest}. It binds an ephemeral loopback port
 * chosen by the container, so it cannot collide with a parallel build, and it needs no database, no
 * container runtime and no network beyond loopback.
 */
@DisplayName("Default security headers survive an application-committed response (finding F3)")
class SecurityHeaderCommitContractTest {

    /** Signing key for the probe context. Test-only material, never a real secret. */
    private static final String KEY = "adhoc-validation-key-material-not-a-real-secret-0123456789";

    /** Issuer claim; non-secret metadata. */
    private static final String ISSUER = "carddemo";

    /** The sign-on route, the one anonymous business path the chain permits. */
    private static final String SIGN_ON = "/api/auth/signon";

    /** Response body the probe servlet writes; its length is what commits the response. */
    private static final String BODY = "{\"ok\":true}";

    /**
     * The default security headers the chain must emit.
     *
     * <p>{@link SecurityConfig} declares no {@code headers(...)} block, so every one of these comes from
     * Spring Security's defaults. That is exactly why the advisory bites here: it is the DEFAULT writer
     * that was skipped, so an application relying on defaults loses all of them at once while one that had
     * spelled them out loses nothing.
     */
    private static final String[][] EXPECTED_HEADERS = {
        { "X-Content-Type-Options", "nosniff" },
        { "X-XSS-Protection", "0" },
        { "Cache-Control", "no-cache, no-store, max-age=0, must-revalidate" },
        { "Pragma", "no-cache" },
        { "Expires", "0" },
        { "X-Frame-Options", "DENY" },
    };

    /** The running container, started once for the class. */
    private static Tomcat tomcat;

    /** The Spring context supplying the chain, closed with the container. */
    private static AnnotationConfigWebApplicationContext context;

    /** Ephemeral port the container bound. */
    private static int port;

    /** Beans {@link SecurityConfig} requires that the application's own configuration does not publish. */
    // @TestConfiguration, not @Configuration. This class is registered EXPLICITLY into a hand-built
    // context, so the annotation's only other effect matters: @SpringBootApplication component-scans
    // com.cardemo.**, the test classes are on the classpath during the integration tier, and a nested
    // @Configuration is a scan candidate while a @TestConfiguration is excluded by Boot's TypeExcludeFilter.
    // Scanned in, its @Bean methods entered the application context and collided by NAME with the real
    // definitions - which fails the context outright, because bean-definition overriding is off.
    @TestConfiguration
    static class Support {

        /**
         * Resolves the {@code ${...}} placeholders {@link SecurityConfig} reads.
         *
         * @return the placeholder configurer
         */
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        /**
         * Supplies the clock the token provider requires.
         *
         * <p>It must track real time: the decoder validates the timestamp claims against the system clock,
         * so a clock fixed at a literal instant makes every minted token expired before it is read.
         *
         * @return a UTC system clock
         */
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        /**
         * Publishes the token provider the authentication filter depends on.
         *
         * @param environment source of the signing key and issuer
         * @param clock the clock supplied above
         * @return the configured provider
         */
        @Bean
        JwtTokenProvider jwtTokenProvider(final Environment environment, final Clock clock) {
            return new JwtTokenProvider(
                    environment.getProperty("carddemo.security.jwt.signing-key"),
                    environment.getProperty("carddemo.security.jwt.issuer"),
                    30L,
                    clock);
        }

        /**
         * Publishes the authentication filter the chain installs.
         *
         * @param decoder the decoder published by {@link SecurityConfig}
         * @param provider the provider published above
         * @return the configured filter
         */
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(final JwtDecoder decoder,
                                                       final JwtTokenProvider provider) {
            return new JwtAuthenticationFilter(decoder, provider);
        }
    }

    /**
     * Terminal handler that declares its own content length and then commits the response.
     *
     * <p>A named class rather than an anonymous one because {@link HttpServlet} is {@link java.io.Serializable}
     * and this build compiles with {@code -Xlint:all -Werror}, under which an anonymous serialisable subclass
     * without a {@code serialVersionUID} is an error.
     *
     * <p>The {@code mode} request parameter selects which of the two ways of declaring the length is used.
     * That choice is the whole experiment: one path is tracked by the commit wrapper on both releases and
     * the other is tracked only after the fix.
     */
    static final class LengthDeclaringServlet extends HttpServlet {

        /** Required because the superclass is serialisable; this servlet is never actually serialised. */
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
                throws IOException {
            if ("setHeader".equals(request.getParameter("mode"))) {
                // The blind spot: a length declared as a header rather than through the typed setter.
                response.setHeader("Content-Length", String.valueOf(BODY.length()));
            } else {
                response.setContentLength(BODY.length());
            }
            response.getWriter().write(BODY);
            response.getWriter().flush();
        }
    }

    /**
     * Starts one container with the real security chain in front of the probe servlet.
     *
     * @throws Exception if the context or the container fails to start
     */
    @BeforeAll
    static void startContainer() throws Exception {
        final MockEnvironment environment = new MockEnvironment();
        environment.setProperty("carddemo.security.jwt.signing-key", KEY);
        environment.setProperty("carddemo.security.jwt.issuer", ISSUER);
        environment.setProperty("carddemo.security.bcrypt.strength", "10");

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.setEnvironment(environment);
        context.register(SecurityConfig.class, Support.class);
        context.refresh();
        final Filter securityChain = context.getBean("springSecurityFilterChain", Filter.class);

        tomcat = new Tomcat();
        // Port 0 lets the container choose a free ephemeral port, so parallel builds cannot collide.
        tomcat.setPort(0);
        tomcat.getConnector();
        final Context servletContext = tomcat.addContext("",
                Files.createTempDirectory("carddemo-header-probe").toAbsolutePath().toString());
        Tomcat.addServlet(servletContext, "probe", new LengthDeclaringServlet());
        servletContext.addServletMappingDecoded(SIGN_ON, "probe");

        final FilterDef definition = new FilterDef();
        definition.setFilterName("springSecurityFilterChain");
        definition.setFilter(securityChain);
        servletContext.addFilterDef(definition);
        final FilterMap mapping = new FilterMap();
        mapping.setFilterName("springSecurityFilterChain");
        mapping.addURLPattern("/*");
        servletContext.addFilterMap(mapping);

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    /**
     * Stops the container and closes the context.
     *
     * @throws Exception if shutdown fails
     */
    @AfterAll
    static void stopContainer() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        if (context != null) {
            context.close();
        }
    }

    /**
     * Issues a real HTTP request to the probe servlet.
     *
     * @param mode which way the handler declares its content length
     * @return the response, headers included
     * @throws Exception if the exchange fails
     */
    private static HttpResponse<String> post(final String mode) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + SIGN_ON + "?mode=" + mode))
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest(name = "content length declared through {0}")
    @ValueSource(strings = {"setHeader", "setContentLength"})
    @DisplayName("every default security header is present however the handler declares its length")
    void defaultHeadersArePresentForBothLengthDeclarations(final String mode) throws Exception {
        final HttpResponse<String> response = post(mode);

        assertThat(response.statusCode()).as("the handler must have run").isEqualTo(200);
        assertThat(response.body()).as("the response must be the committed body").isEqualTo(BODY);

        for (final String[] header : EXPECTED_HEADERS) {
            assertThat(response.headers().firstValue(header[0]))
                    .as("%s must be written when the length is declared through %s", header[0], mode)
                    .contains(header[1]);
        }
    }

    /**
     * The two declaration styles must be indistinguishable from outside.
     *
     * <p>Asserted as an equality between the two responses' security headers rather than as two separate
     * presence checks, because the defect this guards against is precisely a DIFFERENCE between them. A
     * regression that reintroduced it would leave the tracked arm intact, so a test that only checked each
     * arm against its own expectation could still be read as "one of them is fine".
     *
     * @throws Exception if either exchange fails
     */
    @Test
    @DisplayName("the two length declarations yield identical security headers, as the fix intends")
    void bothLengthDeclarationsYieldIdenticalHeaders() throws Exception {
        final HttpResponse<String> viaHeader = post("setHeader");
        final HttpResponse<String> viaSetter = post("setContentLength");

        for (final String[] header : EXPECTED_HEADERS) {
            assertThat(viaHeader.headers().firstValue(header[0]))
                    .as("%s must not depend on how the handler declared its length", header[0])
                    .isEqualTo(viaSetter.headers().firstValue(header[0]));
        }
    }
}
