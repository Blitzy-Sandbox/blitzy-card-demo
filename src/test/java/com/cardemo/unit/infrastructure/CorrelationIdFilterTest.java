/*
 * ******************************************************************
 * Program     : CorrelationIdFilterTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test
 * Function    : Pins the request-scoped thread of identity that replaces EIBTRNID -
 *               correlation id resolution, header echo, span tagging, MDC
 *               population and, above all, MDC restoration on every exit path.
 * Source      : app/cbl/CBTRN02C.cbl:L714-L731 (9910-DISPLAY-IO-STATUS, the only
 *               instrumentation the corpus has) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (EIBTRNID, the per-task identity this
 *               filter replaces) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.observability.CorrelationIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test for {@link CorrelationIdFilter}, the request-scoped thread of identity that replaces
 * {@code EIBTRNID}.
 *
 * <h2>What it does</h2>
 *
 * <p>The legacy system had exactly one per-task identifier and no instrumentation beyond {@code DISPLAY} and
 * the four-character status renderer at {@code app/cbl/CBTRN02C.cbl:L714-L731}. This filter is new capability
 * rather than a translation, so its contract is defined here rather than derived from a paragraph - which is
 * precisely why it needs pinning: there is no COBOL oracle to fall back on if it regresses.
 *
 * <p>The property that matters most is <strong>MDC restoration</strong>. The filter runs on a pooled request
 * thread, and the mapped diagnostic context is thread-local. If a value survives the request, the next request
 * served by that thread inherits it and every log line it writes is attributed to the wrong correlation
 * identifier - a failure that is invisible in a single-request test and corrupts every audit trail in
 * aggregate. Restoration is therefore asserted on the normal path <em>and</em> on the exception path, and the
 * nested case - a request whose thread already carried a context - is asserted to be restored to its previous
 * value rather than merely cleared.
 *
 * <p>Input handling is asserted as a rejection contract. A caller-supplied identifier is honoured only when it
 * is well formed; a blank, over-length or metacharacter-bearing value is <strong>replaced</strong> rather than
 * sanitised or echoed. That is deliberate: the value is written into a response header and into log output, so
 * echoing an arbitrary caller string would make the filter a log-injection and header-injection vector.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run alone with {@code ./mvnw -B -ntp -o test -Dtest=CorrelationIdFilterTest -Djacoco.skip=true}, or with
 * the unit tier via {@code ./mvnw -B -ntp test}. The {@code -Dtest} separator is a comma, never a plus.
 *
 * <p>{@code doFilterInternal} is protected, so the filter is driven through the public
 * {@code doFilter(ServletRequest, ServletResponse, FilterChain)} inherited from {@code OncePerRequestFilter} -
 * the same entry point the container uses. The servlet objects are Spring's mock request and response; the
 * {@link Tracer} is a Mockito double because a real tracer would need an exporter and a backend to say
 * anything, and what is being asserted is how the filter <em>uses</em> the tracer, not what the tracer does.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>No configuration key participates; the header name, the MDC keys and the sixty-four character ceiling are
 * compile-time constants on the filter and are read from it here rather than restated, so a change to either
 * side cannot drift silently.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in the restoration group is the serious one: it means a correlation identifier, a trace id
 *       or a span id can leak between requests on a pooled thread. Check that every {@code MDC.put} is
 *       matched in a {@code finally} block.</li>
 *   <li>A failure in the rejection group means an unsanitised caller value can now reach a response header
 *       and the log stream. Restore the replace-rather-than-echo behaviour.</li>
 *   <li>A failure in the tracing group after a Micrometer upgrade usually means {@code currentSpan()} or
 *       {@code context()} began returning a non-null stub where it previously returned {@code null}; both
 *       null cases must stay guarded, because an unsampled request legitimately has no span.</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CorrelationIdFilter - the request-scoped thread of identity replacing EIBTRNID")
class CorrelationIdFilterTest {

    @Mock private Tracer tracer;
    @Mock private Span span;
    @Mock private TraceContext traceContext;

    private CorrelationIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        this.filter = new CorrelationIdFilter(this.tracer);
        this.request = new MockHttpServletRequest("GET", "/api/accounts/00000000001");
        this.response = new MockHttpServletResponse();
        MDC.clear();
    }

    @AfterEach
    void clearDiagnosticContext() {
        MDC.clear();
    }

    /** Drives the filter through the public entry point the servlet container uses. */
    private void invoke(final FilterChain chain) throws ServletException, IOException {
        this.filter.doFilter(this.request, this.response, chain);
    }

    /** The correlation identifier the filter published on the response. */
    private String publishedCorrelationId() {
        return this.response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
    }

    @Nested
    @DisplayName("resolving the correlation identifier")
    class Resolving {

        @Test
        @DisplayName("a request with no header is given a fresh UUID")
        void aRequestWithNoHeaderIsGivenAFreshUuid() throws Exception {
            invoke(new MockFilterChain());

            final String published = publishedCorrelationId();
            assertThat(published).isNotNull();
            assertThat(UUID.fromString(published))
                    .as("an absent identifier must be generated, never left empty")
                    .isNotNull();
        }

        @Test
        @DisplayName("a well-formed caller-supplied identifier is honoured verbatim")
        void aWellFormedSuppliedIdentifierIsHonoured() throws Exception {
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "client-abc_123");

            invoke(new MockFilterChain());

            assertThat(publishedCorrelationId())
                    .as("honouring a caller identifier is what lets a trace span a client and this service")
                    .isEqualTo("client-abc_123");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "",
            "   ",
            "has space",
            "has\nnewline",
            "semi;colon",
            "quote\"mark",
            "brace{}",
            "percent%25",
            "angle<script>",
        })
        @DisplayName("a malformed identifier is REPLACED, never echoed into the header or the log")
        void aMalformedIdentifierIsReplaced(final String malformed) throws Exception {
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, malformed);

            invoke(new MockFilterChain());

            final String published = publishedCorrelationId();
            assertThat(published)
                    .as("the value reaches a response header and the log stream, so echoing an arbitrary "
                            + "caller string would be a header- and log-injection vector")
                    .isNotEqualTo(malformed);
            assertThat(UUID.fromString(published)).isNotNull();
        }

        @Test
        @DisplayName("an identifier at exactly the 64-character ceiling is accepted")
        void anIdentifierAtTheCeilingIsAccepted() throws Exception {
            final String atLimit = "a".repeat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH);
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, atLimit);

            invoke(new MockFilterChain());

            assertThat(publishedCorrelationId()).isEqualTo(atLimit);
        }

        @Test
        @DisplayName("an identifier one character over the ceiling is replaced")
        void anIdentifierOverTheCeilingIsReplaced() throws Exception {
            final String tooLong = "a".repeat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH + 1);
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, tooLong);

            invoke(new MockFilterChain());

            assertThat(publishedCorrelationId()).isNotEqualTo(tooLong).hasSize(36);
        }

        @Test
        @DisplayName("an identifier already on the response wins over the request header")
        void anIdentifierAlreadyOnTheResponseWins() throws Exception {
            response.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "already-published");
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "from-the-client");

            invoke(new MockFilterChain());

            assertThat(publishedCorrelationId())
                    .as("re-entry must not renumber a request that has already been identified")
                    .isEqualTo("already-published");
        }
    }

    @Nested
    @DisplayName("the diagnostic context is visible during the chain and gone afterwards")
    class DiagnosticContext {

        @Test
        @DisplayName("the correlation identifier is in the MDC while the chain runs")
        void theCorrelationIdIsInTheMdcDuringTheChain() throws Exception {
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "during-the-chain");
            final String[] seen = new String[1];

            invoke((servletRequest, servletResponse) ->
                    seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID));

            assertThat(seen[0])
                    .as("a log line written by a downstream component must carry the identifier")
                    .isEqualTo("during-the-chain");
        }

        @Test
        @DisplayName("the MDC is clean once the request completes, so nothing leaks to the next request")
        void theMdcIsCleanAfterTheRequest() throws Exception {
            invoke(new MockFilterChain());

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("the filter runs on a pooled thread; a surviving value mis-attributes every log "
                            + "line the next request writes")
                    .isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID)).isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID)).isNull();
        }

        @Test
        @DisplayName("the MDC is restored even when the chain throws")
        void theMdcIsRestoredWhenTheChainThrows() {
            assertThatIOException().isThrownBy(() -> invoke((servletRequest, servletResponse) -> {
                throw new IOException("the downstream handler failed");
            }));

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("restoration must live in a finally block, or a failing request poisons the thread")
                    .isNull();
        }

        @Test
        @DisplayName("a pre-existing context is restored to its previous value, not merely cleared")
        void aPreExistingContextIsRestoredNotCleared() throws Exception {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "outer-context");

            invoke(new MockFilterChain());

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("a nested invocation must hand the thread back exactly as it found it")
                    .isEqualTo("outer-context");
        }

        @Test
        @DisplayName("a pre-existing context does not suppress the fresh identifier during the chain")
        void aPreExistingContextDoesNotSuppressTheFreshIdentifier() throws Exception {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "outer-context");
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "inner-context");
            final String[] seen = new String[1];

            invoke((servletRequest, servletResponse) ->
                    seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID));

            assertThat(seen[0]).isEqualTo("inner-context");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isEqualTo("outer-context");
        }
    }

    @Nested
    @DisplayName("trace context propagation")
    class TraceContextPropagation {

        @Test
        @DisplayName("the correlation identifier is tagged onto the current span")
        void theCorrelationIdIsTaggedOntoTheSpan() throws Exception {
            when(tracer.currentSpan()).thenReturn(span);
            when(span.context()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
            when(traceContext.spanId()).thenReturn("b7ad6b7169203331");
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "tagged-id");

            invoke(new MockFilterChain());

            verify(span).tag(CorrelationIdFilter.SPAN_TAG_CORRELATION_ID, "tagged-id");
        }

        @Test
        @DisplayName("the trace and span identifiers reach the MDC while the chain runs")
        void theTraceAndSpanIdentifiersReachTheMdc() throws Exception {
            when(tracer.currentSpan()).thenReturn(span);
            when(span.context()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
            when(traceContext.spanId()).thenReturn("b7ad6b7169203331");
            final String[] seen = new String[2];

            invoke((servletRequest, servletResponse) -> {
                seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID);
                seen[1] = MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID);
            });

            assertThat(seen[0]).isEqualTo("0af7651916cd43dd8448eb211c80319c");
            assertThat(seen[1]).isEqualTo("b7ad6b7169203331");
        }

        @Test
        @DisplayName("an unsampled request with no current span is served normally")
        void anUnsampledRequestWithNoSpanIsServedNormally() throws Exception {
            when(tracer.currentSpan()).thenReturn(null);

            invoke(new MockFilterChain());

            assertThat(publishedCorrelationId())
                    .as("tracing is best-effort; its absence must never fail a request")
                    .isNotNull();
            verify(span, never()).tag(eq(CorrelationIdFilter.SPAN_TAG_CORRELATION_ID), eq("unused"));
        }

        @Test
        @DisplayName("a span with no context is tagged but contributes no MDC entries")
        void aSpanWithNoContextContributesNoMdcEntries() throws Exception {
            when(tracer.currentSpan()).thenReturn(span);
            when(span.context()).thenReturn(null);
            final String[] seen = new String[2];

            invoke((servletRequest, servletResponse) -> {
                seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID);
                seen[1] = MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID);
            });

            verify(span).tag(eq(CorrelationIdFilter.SPAN_TAG_CORRELATION_ID),
                    org.mockito.ArgumentMatchers.anyString());
            assertThat(seen[0]).isNull();
            assertThat(seen[1]).isNull();
        }

        @Test
        @DisplayName("an all-zero trace identifier is refused, because it means no trace was recorded")
        void anAllZeroTraceIdentifierIsRefused() throws Exception {
            when(tracer.currentSpan()).thenReturn(span);
            when(span.context()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn("00000000000000000000000000000000");
            when(traceContext.spanId()).thenReturn("0000000000000000");
            final String[] seen = new String[2];

            invoke((servletRequest, servletResponse) -> {
                seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID);
                seen[1] = MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID);
            });

            assertThat(seen[0])
                    .as("an all-zero identifier is the not-recorded sentinel and would be noise in a log")
                    .isNull();
            assertThat(seen[1]).isNull();
        }

        @Test
        @DisplayName("a blank trace identifier is refused")
        void aBlankTraceIdentifierIsRefused() throws Exception {
            when(tracer.currentSpan()).thenReturn(span);
            when(span.context()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn("  ");
            when(traceContext.spanId()).thenReturn(null);
            final String[] seen = new String[2];

            invoke((servletRequest, servletResponse) -> {
                seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID);
                seen[1] = MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID);
            });

            assertThat(seen[0]).isNull();
            assertThat(seen[1]).isNull();
        }
    }

    @Nested
    @DisplayName("registration contract")
    class RegistrationContract {

        @Test
        @DisplayName("the filter refuses to be built without a tracer")
        void theFilterRefusesToBeBuiltWithoutATracer() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CorrelationIdFilter(null))
                    .withMessageContaining("tracer");
        }

        @Test
        @DisplayName("the filter runs before anything that logs, but after the span is opened")
        void theFilterRunsFirst() {
            // The identifier must exist before authentication logs anything, and it does: the security
            // filter chain registers far later. But it must NOT run at HIGHEST_PRECEDENCE, because Boot
            // registers ServerHttpObservationFilter at HIGHEST_PRECEDENCE + 1 and that filter is what opens
            // the observation this filter tags. Running ahead of it left the tag with no span to attach to,
            // so the correlation identifier was absent from exactly the traces that needed it.
            assertThat(CorrelationIdFilter.ORDER)
                    .as("immediately after the observation filter that opens the span this filter tags")
                    .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 2)
                    .isGreaterThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1)
                    .as("still far ahead of the security chain, so authentication cannot log without it")
                    .isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
        }

        @Test
        @DisplayName("the filter also runs on an error dispatch, so a 500 is still correlated")
        void theFilterAlsoRunsOnAnErrorDispatch() throws Exception {
            request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);

            invoke(new MockFilterChain());

            assertThat(publishedCorrelationId())
                    .as("an error dispatch is exactly when a correlation identifier matters most")
                    .isNotNull();
        }
    }
}
