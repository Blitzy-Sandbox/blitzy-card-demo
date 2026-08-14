/*
 * ******************************************************************
 * Program     : ProblemJsonErrorBoundaryTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 unit test
 * Function    : Proves that a request the servlet container refuses
 *               BEFORE the application is answered with the refusal
 *               envelope, and that the answer discloses no stack trace,
 *               no internal Java type, no filesystem path and none of
 *               the container's own HTML report. Covers both the valve
 *               and the customiser that installs it.
 * Source      : app/csd/CARDDEMO.CSD (every request reached a program
 *               only through the CICS region, so a malformed one
 *               reached none at all - the property this boundary
 *               restores) @ 7756d89
 * Subject     : com.cardemo.config.WebConfig.ProblemJsonErrorReportValve
 *               and WebConfig#problemJsonErrorReportValve()
 * ******************************************************************
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
 * ******************************************************************
 */
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cardemo.config.WebConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

/**
 * FINDING LOW-002, severity Low - the error boundary outside MVC is proven rather than described.
 *
 * <p><strong>What was missing.</strong> {@code WebConfig.ProblemJsonErrorReportValve} and the customiser that
 * installs it had no test reference anywhere in the suite, unlike every adjacent request-boundary component.
 * That is the worst place in the application to have no coverage, because the valve is the <em>only</em> code
 * that can shape a response for a request the connector refuses while it is still parsing the request line:
 * no filter, no servlet, no Spring resolver and no {@code @ExceptionHandler} runs. A regression there is
 * silent, and what it silently restores is the container's own error page - which names the servlet container
 * and its version, and on an exception path renders a stack trace.
 *
 * <p><strong>Why this test starts a real container.</strong> The refusals in question cannot be reached any
 * other way. They are produced by the connector before a request object is handed to anything, so there is no
 * seam to drive with a mock: a {@code MockHttpServletRequest} cannot be malformed in the way that matters,
 * because it never goes through request-line parsing. The test therefore starts an embedded Tomcat through the
 * <em>production</em> customiser and speaks HTTP to it over a raw socket. The socket is deliberate too - an
 * HTTP client would parse and normalise the response, and what is under test is the exact bytes, headers
 * included.
 *
 * <p><strong>How this test fails.</strong> Delete the {@code host.setErrorReportValveClass(...)} call, or
 * make the valve fall back to {@code super.report(...)}, and the body becomes Tomcat's HTML page: every
 * disclosure assertion below then fails, and so does the media-type assertion. That is checked rather than
 * asserted about, by {@link ContainerDefaultRendering#theContainerDefaultIsWhatIsBeingSuppressed()}, which
 * runs the same requests against a factory with no customiser and shows the disclosure the valve removes.
 */
@DisplayName("LOW-002: the container-level error boundary publishes the refusal envelope and nothing else")
class ProblemJsonErrorBoundaryTest {

    /** The media type every refusal must carry, envelope included. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /**
     * Fragments that must never appear in a container-level refusal.
     *
     * <p>Each one is a distinct disclosure class rather than a variation on one: container HTML identifies the
     * runtime and its version, a {@code java.} or {@code org.apache.} token names an internal type, a frame
     * naming a package is a stack trace, and a path fragment names the filesystem the process runs on. The
     * container's own report carries all four, which is why all four are screened.
     *
     * <p>The stack-frame probes are the qualified forms - {@code at org.}, {@code at java.} and a
     * tab-prefixed {@code at } - rather than a bare {@code at }. A bare probe is not a screen for a stack
     * frame, it is a screen for the letters {@code a}, {@code t} and a space, and the envelope's own published
     * prose contains them twice: "the methods <em>that a</em>re" and "refused <em>at </em>the protocol
     * boundary". It failed on a correct response, which is a false positive in a security assertion and
     * therefore the kind of test that gets weakened rather than fixed the next time it fires. The qualified
     * forms cannot match published prose, and no stack frame can appear without one of them or without one of
     * the package tokens already screened above.
     */
    private static final List<String> FORBIDDEN = List.of(
            "<html", "<!doctype", "<body", "<h1", "<pre",
            "Apache Tomcat", "apache-tomcat", "Servlet",
            "java.lang.", "jakarta.servlet.", "org.apache.", "com.cardemo.",
            "at org.", "at java.", "at jakarta.", "at com.", "\tat ", "Caused by", "StackTrace",
            "/tmp/", "/usr/", "src/main/java", ".java:");

    /** The server under test, started once because starting a container is the expensive part. */
    private static WebServer server;

    /** The port {@link #server} bound to, chosen by the operating system. */
    private static int port;

    /**
     * Starts an embedded container with the production customiser applied and nothing else.
     *
     * <p>No Spring context, no {@code @SpringBootApplication} and no application beans: the subject is the
     * valve, and adding the application would mean an assertion could pass because some other layer answered.
     * Port zero, so the test cannot collide with the compose topology or with a sibling clone.
     */
    @BeforeAll
    static void startContainerWithTheValveInstalled() {
        final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        new WebConfig().problemJsonErrorReportValve().customize(factory);
        server = factory.getWebServer();
        server.start();
        port = server.getPort();
    }

    /** Stops the container. */
    @AfterAll
    static void stopContainer() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Sends a raw request line and returns the whole response, headers included, exactly as received.
     *
     * @param requestLine the request line and any headers, without the terminating blank line
     * @return the raw response bytes decoded as ISO-8859-1 so no byte is lost to a decoder
     * @throws IOException if the exchange fails, which is a test failure rather than an expected outcome
     */
    private static String exchange(final String requestLine) throws IOException {
        return exchange(requestLine, port);
    }

    /**
     * Sends a raw request line to a nominated port and returns the whole response.
     *
     * @param requestLine the request line and any headers, without the terminating blank line
     * @param targetPort the port to speak to
     * @return the raw response bytes decoded as ISO-8859-1
     * @throws IOException if the exchange fails
     */
    private static String exchange(final String requestLine, final int targetPort) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", targetPort), 5000);
            socket.setSoTimeout(5000);
            final OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            final ByteArrayOutputStream received = new ByteArrayOutputStream();
            final InputStream in = socket.getInputStream();
            final byte[] buffer = new byte[4096];
            int read = in.read(buffer);
            while (read >= 0) {
                received.write(buffer, 0, read);
                read = in.read(buffer);
            }
            return received.toString(StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Asserts that a response discloses none of the four classes of internal detail.
     *
     * @param response the whole raw response, headers included
     */
    private static void assertDisclosesNothing(final String response) {
        final String lowered = response.toLowerCase(Locale.ROOT);
        for (final String forbidden : FORBIDDEN) {
            assertThat(lowered)
                    .as("a container-level refusal must not disclose [%s]; the container's own error page "
                            + "carries it, which is what the valve exists to replace", forbidden)
                    .doesNotContain(forbidden.toLowerCase(Locale.ROOT));
        }
    }

    /** The refusals a client can actually provoke before the application is reached. */
    @Nested
    @DisplayName("a request refused before the application is answered with the envelope")
    class RefusedBeforeTheApplication {

        /**
         * TRACE is the case the valve's own documentation cites as the reason its guard differs from the
         * superclass's. The connector refuses the method with {@code sendError}, which sets the
         * error-reported flag and dispatches to the error page; that dispatch is refused as well, so nothing
         * is ever written - and honouring the flag would leave the caller a bare status with no body.
         *
         * @throws IOException if the exchange fails
         */
        @Test
        @DisplayName("TRACE is refused with the problem+json envelope, not a bare status and not HTML")
        void traceIsRefusedWithTheEnvelope() throws IOException {
            final String response = exchange("TRACE /api/accounts HTTP/1.1");

            assertThat(response).startsWith("HTTP/1.1 4");
            assertThat(response.toLowerCase(Locale.ROOT))
                    .as("the envelope's media type, which is what makes this refusal indistinguishable in "
                            + "shape from the ones the eight controllers publish")
                    .contains("content-type: " + PROBLEM_JSON);
            assertThat(response)
                    .as("all six envelope members, in the order renderProblemEnvelope emits them")
                    .containsSubsequence("\"type\":\"about:blank\"", "\"title\":", "\"status\":",
                            "\"detail\":", "\"errorCode\":\"CARDDEMO-", "\"correlationId\":");
            assertDisclosesNothing(response);
        }

        /**
         * The request target that produced the original defect: an encoded NUL in a path segment. The
         * connector rejects it while the request line is still being parsed, so this is the refusal that
         * proves no filter or resolver is involved.
         *
         * @param target the malformed request target
         * @throws IOException if the exchange fails
         */
        @ParameterizedTest(name = "a request target of [{0}] is refused with the envelope")
        @ValueSource(strings = {
            "/api/accounts/000000000%00",
            "/api/accounts/%zz",
            "/api/cards/..%2f..%2fetc%2fpasswd"})
        @DisplayName("a malformed request target is refused with the envelope and discloses nothing")
        void aMalformedRequestTargetIsRefusedWithTheEnvelope(final String target) throws IOException {
            final String response = exchange("GET " + target + " HTTP/1.1");

            assertThat(response).startsWith("HTTP/1.1 4");
            assertThat(response.toLowerCase(Locale.ROOT)).contains("content-type: " + PROBLEM_JSON);
            assertThat(response).contains("\"type\":\"about:blank\"", "\"errorCode\":\"CARDDEMO-");
            assertDisclosesNothing(response);
        }

        /**
         * The boundary-header finding on the container path.
         *
         * <p>This valve sits outside the servlet filter stack, so the security chain's
         * {@code HeaderWriterFilter} never runs for a request the connector refused - a percent-encoded NUL
         * is rejected while the request line is still being parsed, and {@code TRACE} is refused by the
         * connector itself. Measured against the running application, both came back with
         * {@code Content-Type} and nothing else while every response produced from inside the chain carried
         * the full hardening set. The values asserted here are the ones measured on that chain's 401.
         *
         * <p>{@code Strict-Transport-Security} is deliberately not asserted present: it is in the writer set
         * and its default matcher requires a secure request, and this container serves plaintext. Its
         * absence here is therefore correct, and its presence under TLS is asserted on the firewall path in
         * {@code RequestBoundaryHardeningTest} where a request's transport can be set directly.
         *
         * @param requestLine the raw request line that provokes a container-level refusal
         * @throws IOException if the exchange fails
         */
        @ParameterizedTest(name = "a container refusal of [{0}] carries the hardening headers")
        @ValueSource(strings = {
            "TRACE /api/accounts HTTP/1.1",
            "GET /api/accounts/000000000%00 HTTP/1.1",
            "GET /api/cards/..%2f..%2fetc%2fpasswd HTTP/1.1"})
        @DisplayName("a container-level refusal carries the same hardening headers as the application's own")
        void aContainerLevelRefusalCarriesTheHardeningHeaders(final String requestLine) throws IOException {
            final String response = exchange(requestLine);
            final String lowered = response.toLowerCase(Locale.ROOT);

            assertThat(response).startsWith("HTTP/1.1 4");
            assertThat(lowered)
                    .as("without it a browser may sniff the refusal body as something other than the "
                            + "problem+json it declares")
                    .contains("x-content-type-options: nosniff");
            assertThat(lowered)
                    .as("the framework's own default value, and part of the set measured on every response "
                            + "the chain produces")
                    .contains("x-xss-protection: 0");
            assertThat(lowered)
                    .as("a refusal carrying a correlation identifier must not be cached and handed to a "
                            + "second caller as though it were their own")
                    .contains("cache-control: no-cache, no-store, max-age=0, must-revalidate")
                    .contains("pragma: no-cache")
                    .contains("expires: 0");
            assertThat(lowered)
                    .as("the chain writes DENY, so this boundary writes DENY; absence would be a weaker "
                            + "policy reachable by malforming a request target")
                    .contains("x-frame-options: deny");
            assertThat(lowered)
                    .as("and the refusal itself is unchanged - same media type, same envelope")
                    .contains("content-type: " + PROBLEM_JSON);
            assertThat(response).contains("\"type\":\"about:blank\"", "\"errorCode\":\"CARDDEMO-");
            assertDisclosesNothing(response);
        }

        /**
         * The one detail of the envelope that is not a constant: a pre-servlet refusal has no correlation
         * identifier, because the filter that mints one never ran. The valve mints one rather than publishing
         * nothing, so the identifier in the client's hands resolves to a log entry - and it must never be the
         * unavailable sentinel on this path, because a value is always produced.
         *
         * @throws IOException if the exchange fails
         */
        @Test
        @DisplayName("the envelope carries a minted correlation identifier, not the unavailable sentinel")
        void theEnvelopeCarriesAMintedCorrelationIdentifier() throws IOException {
            final String first = exchange("TRACE /api/accounts HTTP/1.1");
            final String second = exchange("TRACE /api/cards HTTP/1.1");

            assertThat(correlationIdOf(first))
                    .as("a pre-servlet refusal has no identifier to inherit, so one is minted")
                    .isNotBlank()
                    .isNotEqualTo("unavailable")
                    .matches("[A-Za-z0-9_-]+");
            assertThat(correlationIdOf(second))
                    .as("and it is per-refusal, so two clients cannot be told to quote the same identifier")
                    .isNotEqualTo(correlationIdOf(first));
        }

        /**
         * Extracts the correlation identifier from a raw response.
         *
         * @param response the raw response
         * @return the identifier the envelope carried
         */
        private String correlationIdOf(final String response) {
            final String marker = "\"correlationId\":\"";
            final int start = response.indexOf(marker);
            assertThat(start).as("the envelope must carry a correlationId member").isNotNegative();
            final int from = start + marker.length();
            return response.substring(from, response.indexOf('"', from));
        }
    }

    /** The installation seam, so a passing body assertion cannot be a valve nothing installed. */
    @Nested
    @DisplayName("the customiser installs this valve on the host, and tolerates a host it cannot")
    class Installation {

        /**
         * The customiser's effect is one property on the Tomcat {@code Host}, set during context creation and
         * before the host starts. Asserted directly as well as through the response above, because a
         * behavioural assertion tells you the boundary works while this one tells you <em>why</em> - and
         * because the two fail differently: this one fails the moment the call is deleted, whichever way the
         * container happens to render afterwards.
         */
        @Test
        @DisplayName("the customiser sets the host's error report valve class to this valve")
        void theCustomiserSetsTheHostsErrorReportValveClass() {
            final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
            new WebConfig().problemJsonErrorReportValve().customize(factory);
            final StandardHost host = new StandardHost();
            final StandardContext context = new StandardContext();
            context.setParent(host);

            factory.getTomcatContextCustomizers().forEach(customizer -> customizer.customize(context));

            assertThat(host.getErrorReportValveClass())
                    .as("Tomcat instantiates the valve reflectively from this class name, which is why the "
                            + "valve is public with a public no-argument constructor")
                    .isEqualTo(WebConfig.ProblemJsonErrorReportValve.class.getName());
        }

        /**
         * The documented non-fatal branch. A context whose parent is not a {@code StandardHost} is not
         * reachable with the embedded Tomcat this application pins, and the class deliberately warns rather
         * than failing: a missing envelope on a pre-servlet refusal must not stop the application starting.
         * Asserted so that the branch is exercised rather than merely described.
         */
        @Test
        @DisplayName("a context with no StandardHost parent is tolerated rather than fatal")
        void aContextWithoutAStandardHostParentIsTolerated() {
            final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
            new WebConfig().problemJsonErrorReportValve().customize(factory);
            final StandardContext orphan = new StandardContext();

            assertThatCode(() -> factory.getTomcatContextCustomizers()
                    .forEach(customizer -> customizer.customize(orphan)))
                    .as("startup must survive a container topology this customiser cannot adjust")
                    .doesNotThrowAnyException();
        }

        /**
         * The valve has to be a {@link ErrorReportValve} for Tomcat to accept the class name, and it has to be
         * instantiable reflectively with no arguments. Both are contract with the container rather than with
         * this application, so neither is visible from the call site that depends on them.
         *
         * @throws ReflectiveOperationException if the no-argument constructor is not usable
         */
        @Test
        @DisplayName("the valve is an ErrorReportValve and is reflectively instantiable, as Tomcat requires")
        void theValveMeetsTheContainersInstantiationContract() throws ReflectiveOperationException {
            final Object instance = WebConfig.ProblemJsonErrorReportValve.class
                    .getDeclaredConstructor().newInstance();

            assertThat(instance).isInstanceOf(ErrorReportValve.class);
        }
    }

    /** What the valve is suppressing, demonstrated rather than asserted about. */
    @Nested
    @DisplayName("the container default the valve replaces")
    class ContainerDefaultRendering {

        /**
         * The control case, and the reason the disclosure assertions above are meaningful.
         *
         * <p>A test that only asserts absence proves nothing on its own: absence is also what an empty
         * response gives. This case runs the same requests against a container with <strong>no</strong>
         * customiser applied and shows that the container really does disclose what the other cases screen
         * for - so those cases are known to be capable of failing, which is the property that makes them
         * worth having.
         *
         * @throws IOException if the exchange fails
         */
        @Test
        @DisplayName("the container default is what is being suppressed, so the screen can genuinely fail")
        void theContainerDefaultIsWhatIsBeingSuppressed() throws IOException {
            final TomcatServletWebServerFactory bare = new TomcatServletWebServerFactory(0);
            final WebServer unprotected = bare.getWebServer();
            unprotected.start();
            try {
                final String response = exchange("GET /api/accounts/000000000%00 HTTP/1.1",
                        unprotected.getPort());

                assertThat(response.toLowerCase(Locale.ROOT))
                        .as("without the valve the container renders its own report, and it is HTML naming "
                                + "the runtime - which is precisely the disclosure finding LOW-002 asked to "
                                + "be proven absent, and could not be proven absent while nothing exercised "
                                + "this boundary at all")
                        .contains("<html")
                        .contains("apache tomcat");
                assertThat(response.toLowerCase(Locale.ROOT))
                        .as("and it is emphatically not the refusal envelope")
                        .doesNotContain(PROBLEM_JSON);
            } finally {
                unprotected.stop();
            }
        }
    }
}
