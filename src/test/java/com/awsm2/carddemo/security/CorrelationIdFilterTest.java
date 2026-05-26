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
package com.awsm2.carddemo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CorrelationIdFilter}, the QA CP11 Finding M-1 fix
 * that establishes a per-request correlation identifier in the SLF4J MDC by
 * either consuming an inbound {@code X-Correlation-Id} HTTP header or
 * generating a UUID fallback.
 *
 * <p>Coverage matrix:</p>
 * <ol>
 *   <li><b>Inbound-header echo</b> &mdash; a well-formed inbound header is
 *       copied verbatim into the MDC and echoed on the response header.</li>
 *   <li><b>Fallback UUID</b> &mdash; an absent inbound header yields a UUID
 *       in both MDC and response header.</li>
 *   <li><b>Malformed inbound rejected</b> &mdash; over-length headers and
 *       headers containing CRLF/control characters fall back to UUID.</li>
 *   <li><b>MDC cleanup</b> &mdash; the MDC entry is removed after the
 *       chain completes, even on exception, to prevent leakage across
 *       servlet-thread reuse.</li>
 *   <li><b>Chain progression</b> &mdash; the filter always invokes the
 *       downstream filter chain.</li>
 * </ol>
 *
 * @see CorrelationIdFilter
 */
@DisplayName("CorrelationIdFilter — QA CP11 M-1 X-Correlation-Id propagation")
class CorrelationIdFilterTest {

    /**
     * UUID v4 regex used to assert that the fallback path produces a
     * canonical RFC 4122 random UUID.
     */
    private static final String UUID_V4_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        // Defensive clear — the filter reads/writes MDC, so any pre-existing
        // entry from a previous test must not bleed across.
        MDC.clear();
        filter = new CorrelationIdFilter();
    }

    @AfterEach
    void cleanup() {
        // Mirror the @BeforeEach to ensure even mid-test exceptions cannot
        // leak the MDC entry into subsequent tests.
        MDC.clear();
    }

    /**
     * Helper that builds a FilterChain lambda that captures the MDC value
     * visible to downstream handlers. FilterChain is a functional
     * interface so a lambda satisfies the SAM contract.
     *
     * @param mdcCapture container that receives the captured MDC value
     * @return a FilterChain that captures MDC[correlationId] when invoked
     */
    private FilterChain capturingMdc(AtomicReference<String> mdcCapture) {
        return (req, res) -> mdcCapture.set(MDC.get("correlationId"));
    }

    // ------------------------------------------------------------------
    // Inbound-header happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Well-formed inbound X-Correlation-Id is copied into MDC verbatim")
    void wellFormedInboundHeader_isCopiedIntoMdcVerbatim() throws ServletException, IOException {
        // GIVEN — a request carrying a valid X-Correlation-Id header
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", "my-trace-id-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        // WHEN — the filter runs
        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        // THEN — the MDC visible to downstream sees the inbound value
        assertThat(mdcDuringChain.get()).isEqualTo("my-trace-id-12345");
        // AND — the response header echoes the inbound value
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("my-trace-id-12345");
        // AND — the MDC is cleared after the filter exits (no leakage)
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("Inbound header with surrounding whitespace is trimmed")
    void inboundHeaderWithWhitespace_isTrimmed() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", "  abc-123-def  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        assertThat(mdcDuringChain.get()).isEqualTo("abc-123-def");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("abc-123-def");
    }

    @Test
    @DisplayName("UUID-shaped inbound header is accepted")
    void uuidShapedInboundHeader_isAccepted() throws ServletException, IOException {
        String uuid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cards/123");
        request.addHeader("X-Correlation-Id", uuid);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        assertThat(mdcDuringChain.get()).isEqualTo(uuid);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(uuid);
    }

    // ------------------------------------------------------------------
    // Fallback UUID
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Absent inbound header yields a server-generated UUID")
    void absentInboundHeader_yieldsServerGeneratedUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        // Explicitly NOT adding the X-Correlation-Id header.
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        // The fallback path generates a random version-4 UUID.
        assertThat(mdcDuringChain.get())
                .isNotNull()
                .matches(UUID_V4_REGEX);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(mdcDuringChain.get());
    }

    @Test
    @DisplayName("Empty inbound header value yields a server-generated UUID")
    void emptyInboundHeader_yieldsServerGeneratedUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        request.addHeader("X-Correlation-Id", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        assertThat(mdcDuringChain.get())
                .isNotNull()
                .matches(UUID_V4_REGEX);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(mdcDuringChain.get());
    }

    // ------------------------------------------------------------------
    // Malformed inbound rejected
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Over-length inbound header (>128 chars) is rejected — UUID fallback emitted")
    void overlengthInboundHeader_isRejected() throws ServletException, IOException {
        // 129 'a' characters — one over the MAX_HEADER_LENGTH boundary.
        String overlength = "a".repeat(129);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", overlength);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        // The malformed inbound value is NEVER copied into MDC; UUID fallback used.
        assertThat(mdcDuringChain.get())
                .isNotEqualTo(overlength)
                .matches(UUID_V4_REGEX);
        assertThat(response.getHeader("X-Correlation-Id")).isNotEqualTo(overlength);
    }

    @Test
    @DisplayName("Inbound header containing CRLF is rejected (log-injection defense)")
    void inboundHeaderContainingCrlf_isRejected() throws ServletException, IOException {
        // Attempted log-injection payload — CRLF + fake log line.
        String malicious = "abc\r\n[CRITICAL] fake log entry";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", malicious);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        // The malicious payload MUST NOT appear in the MDC nor in the
        // response header.
        assertThat(mdcDuringChain.get())
                .isNotEqualTo(malicious)
                .doesNotContain("\n")
                .doesNotContain("\r")
                .matches(UUID_V4_REGEX);
        assertThat(response.getHeader("X-Correlation-Id"))
                .doesNotContain("\n")
                .doesNotContain("\r");
    }

    @Test
    @DisplayName("Inbound header containing spaces is rejected")
    void inboundHeaderContainingSpaces_isRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", "abc 123 xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        assertThat(mdcDuringChain.get())
                .isNotEqualTo("abc 123 xyz")
                .matches(UUID_V4_REGEX);
    }

    @Test
    @DisplayName("Inbound header containing angle brackets is rejected")
    void inboundHeaderContainingAngleBrackets_isRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", "<script>alert(1)</script>");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        assertThat(mdcDuringChain.get())
                .matches(UUID_V4_REGEX);
        assertThat(response.getHeader("X-Correlation-Id"))
                .doesNotContain("<")
                .doesNotContain(">");
    }

    // ------------------------------------------------------------------
    // MDC cleanup on exception
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MDC entry is removed even when downstream chain throws ServletException")
    void mdcEntryIsRemovedEvenWhenChainThrowsServletException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", "before-exception-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // A chain that throws after the filter has populated the MDC.
        FilterChain chain = (req, res) -> { throw new ServletException("downstream failure"); };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        // Critical: even after the downstream chain throws, the MDC entry
        // MUST be removed so the next request handled by the same thread
        // does not inherit the stale identifier.
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("MDC entry is removed when downstream chain throws RuntimeException")
    void mdcEntryIsRemovedWhenChainThrowsRuntimeException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { throw new RuntimeException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RuntimeException.class);

        assertThat(MDC.get("correlationId")).isNull();
    }

    // ------------------------------------------------------------------
    // Chain progression
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Filter invokes downstream chain exactly once per request")
    void filterInvokesDownstreamChainExactlyOnce() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Count chain invocations to confirm exactly one progression.
        AtomicInteger invocations = new AtomicInteger();
        FilterChain chain = (req, res) -> invocations.incrementAndGet();

        filter.doFilter(request, response, chain);

        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Response header is set before filter chain progression")
    void responseHeaderIsSetBeforeChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        request.addHeader("X-Correlation-Id", "early-bird");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Inside the chain, the response header should already be present.
        AtomicReference<String> responseHeaderDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            responseHeaderDuringChain.set(httpResponse.getHeader("X-Correlation-Id"));
        };

        filter.doFilter(request, response, chain);

        assertThat(responseHeaderDuringChain.get()).isEqualTo("early-bird");
    }

    // ------------------------------------------------------------------
    // Constants surface
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Public constants expose the canonical header and MDC key names")
    void publicConstantsExposeCanonicalNames() {
        // The header constant must match the value documented in
        // ApiResponse.java and MdcHeaderProducerInterceptor.
        assertThat(CorrelationIdFilter.CORRELATION_ID_HEADER).isEqualTo("X-Correlation-Id");
        // The MDC key must match what GlobalExceptionHandler reads and
        // what MdcHeaderProducerInterceptor consumes.
        assertThat(CorrelationIdFilter.MDC_CORRELATION_ID_KEY).isEqualTo("correlationId");
    }

    /**
     * Pseudo-test that documents a side observation: when the inbound
     * header is composed of multiple values (RFC 7230 allows comma-
     * separated header lists), {@code HttpServletRequest.getHeader} returns
     * the first value, which is the behaviour the filter relies on. The
     * Spring MockHttpServletRequest mirrors this contract.
     */
    @Test
    @DisplayName("First header value is used when multiple X-Correlation-Id headers present")
    void firstHeaderValueIsUsed_whenMultipleHeadersPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/123");
        // Add two values — getHeader returns the first per Servlet API.
        request.addHeader("X-Correlation-Id", "first-value");
        request.addHeader("X-Correlation-Id", "second-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringChain));

        // The first header value wins per Servlet API contract.
        assertThat(mdcDuringChain.get()).isEqualTo("first-value");
    }
}
