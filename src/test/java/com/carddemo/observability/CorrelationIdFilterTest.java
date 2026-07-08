package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

/**
 * Pure, dependency-free unit test for {@link CorrelationIdFilter}, the request-side half of the
 * CardDemo observability trio.
 *
 * <p>It proves the correlation-ID contract that the Observability rule requires on day one: for
 * every request a correlation ID is published to the SLF4J {@link MDC} under the exact shared key
 * {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY}, reused from the inbound
 * {@link CorrelationIdFilter#CORRELATION_ID_HEADER} header when a usable value is supplied,
 * sanitized against log- and response-header injection, echoed back on the response, and —
 * critically — always removed once the chain completes, including when the chain throws (pooled
 * servlet worker threads must never carry a stale correlation ID into the next request).</p>
 *
 * <p>The test lives in the same package as the filter, so it invokes the {@code protected}
 * {@link CorrelationIdFilter#doFilterInternal} directly and captures the MDC value <em>while the
 * chain is executing</em> via a lambda {@link FilterChain}. It loads no Spring context and uses no
 * servlet container, database, or network I/O, so it runs fast and deterministically and
 * contributes to line coverage (JaCoCo, Gate 8). The design rationale for the shared MDC-key
 * contract lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 */
@DisplayName("CorrelationIdFilter — request-side correlation-ID MDC contract")
class CorrelationIdFilterTest {

    /** The filter under test; it exposes a no-arg constructor and holds no per-request state. */
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /**
     * Guarantees thread-local MDC isolation between tests. The filter removes its own key in a
     * {@code finally} block, but clearing before and after each test defensively ensures no
     * unrelated MDC state can leak into (or out of) an assertion.
     */
    @BeforeEach
    void clearMdcBefore() {
        MDC.clear();
    }

    @AfterEach
    void clearMdcAfter() {
        MDC.clear();
    }

    // ---------------------------------------------------------------------
    // Generation path — no inbound header
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("No inbound header: a UUID is generated, visible in the MDC during the chain, echoed, then cleared")
    void noInboundHeader_generatesUuid_visibleInMdcDuringChain_echoedOnResponse_thenCleared()
            throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] mdcDuringChain = new String[1];

        final FilterChain chain =
                (req, res) -> mdcDuringChain[0] = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

        filter.doFilterInternal(request, response, chain);

        // A non-blank ID was present in the MDC while the downstream chain executed.
        assertThat(mdcDuringChain[0]).isNotBlank();
        // The generated ID is a syntactically valid UUID.
        assertDoesNotThrow(() -> UUID.fromString(mdcDuringChain[0]));
        // The exact same ID was echoed back on the response header.
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(mdcDuringChain[0]);
        // The MDC key was removed once the filter returned (no thread-local leak).
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    // ---------------------------------------------------------------------
    // Reuse path — valid inbound header
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Inbound header present: the upstream ID is reused verbatim, not regenerated")
    void inboundHeaderPresent_isReusedNotRegenerated() throws Exception {
        // A clean value with no control characters and no surrounding whitespace.
        final String inbound = "11111111-2222-3333-4444-555555555555";
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, inbound);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] mdcDuringChain = new String[1];

        final FilterChain chain =
                (req, res) -> mdcDuringChain[0] = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

        filter.doFilterInternal(request, response, chain);

        // The inbound value is propagated unchanged into the MDC and onto the response header.
        assertThat(mdcDuringChain[0]).isEqualTo(inbound);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(inbound);
    }

    // ---------------------------------------------------------------------
    // Sanitize path — log- and response-header injection safety
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Inbound header with CR/LF/control characters is sanitized (no injection into MDC or response)")
    void inboundHeaderWithCrLfControlChars_isSanitized() throws Exception {
        // CR + LF + TAB are all ISO control characters an attacker could use to forge log lines
        // or split the HTTP response.
        final String malicious = "abc\r\ndef\tINJECTED-LOG-LINE";
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, malicious);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] mdcDuringChain = new String[1];

        final FilterChain chain =
                (req, res) -> mdcDuringChain[0] = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

        filter.doFilterInternal(request, response, chain);

        // Assert the security INVARIANT only (the implementation may strip or replace): no CR, no
        // LF, and no other ISO control character survives into the MDC value.
        assertThat(mdcDuringChain[0]).doesNotContain("\r").doesNotContain("\n");
        assertThat(mdcDuringChain[0].chars().noneMatch(Character::isISOControl)).isTrue();

        // The echoed response header is likewise free of control characters (prevents response
        // splitting).
        final String echoed = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(echoed).doesNotContain("\r").doesNotContain("\n");
        assertThat(echoed.chars().noneMatch(Character::isISOControl)).isTrue();
    }

    // ---------------------------------------------------------------------
    // Blank-fallback path — whitespace-only inbound header
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Blank (whitespace-only) inbound header falls back to a freshly generated UUID")
    void blankInboundHeader_generatesFreshUuid() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] mdcDuringChain = new String[1];

        final FilterChain chain =
                (req, res) -> mdcDuringChain[0] = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

        filter.doFilterInternal(request, response, chain);

        // A blank header is not propagated; the filter mints a valid UUID instead of an empty ID.
        assertThat(mdcDuringChain[0]).isNotBlank();
        assertDoesNotThrow(() -> UUID.fromString(mdcDuringChain[0]));
    }

    // ---------------------------------------------------------------------
    // Cleanup-on-throw path — the critical thread-leak guard
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("MDC is cleared in the finally block even when the downstream chain throws")
    void mdcIsClearedWhenChainThrows() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final FilterChain throwingChain = (req, res) -> {
            throw new ServletException("boom");
        };

        // The downstream failure propagates unchanged to the caller.
        assertThrows(ServletException.class,
                () -> filter.doFilterInternal(request, response, throwingChain));

        // Cleanup still happened via the finally block: a pooled thread must not carry a stale ID.
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    // ---------------------------------------------------------------------
    // Shared-contract constants
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Public constants pin the shared MDC key and header name")
    void constantsMatchSharedContract() {
        // The whole logging trio plus logback-spring.xml's %X{correlationId} depends on this key.
        assertThat(CorrelationIdFilter.CORRELATION_ID_MDC_KEY).isEqualTo("correlationId");
        assertThat(CorrelationIdFilter.CORRELATION_ID_HEADER).isEqualTo("X-Correlation-Id");
    }
}
