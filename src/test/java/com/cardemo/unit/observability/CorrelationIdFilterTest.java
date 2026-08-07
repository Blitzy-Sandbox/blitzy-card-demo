/*
 ******************************************************************************
 * Program     : CorrelationIdFilterTest
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5, Surefire tier)
 * Function    : Asserts the request-scoped thread of identity the corpus never
 *               had: that a correlation identifier is always published,
 *               that a malformed supplied one is never trusted, that the
 *               diagnostic context is restored on every exit path including the
 *               exceptional one, and that the MDC key spellings logback binds
 *               do not drift.
 * Source      : app/cbl/CBTRN02C.cbl:714-731 @ 7756d89 - the only per-run
 *               instrumentation the corpus has, which this replaces
 * Note        : EIBTRNID has ZERO occurrences in the frozen corpus - the nearest legacy
 *               analogue of a per-request identity, absent from the corpus
 ******************************************************************************
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
 ******************************************************************************
 */
package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.observability.CorrelationIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit cover for {@link CorrelationIdFilter}.
 *
 * <h2>What it does</h2>
 *
 * <p>The filter is the replacement for the CICS transaction identifier as the thread of identity, and the
 * legacy corpus has no analogue for most of what it does, so its contract cannot be verified against a
 * paragraph. It is verified here against the three properties the observability layer actually depends on: a
 * correlation identifier is published on every response, a supplied identifier is trusted only when it is
 * well formed, and the diagnostic context is left exactly as it was found no matter how the request exits.
 *
 * <p>The last of those is the one with teeth. Servlet containers pool threads, so an entry left in the
 * mapped diagnostic context after a request completes is inherited by an unrelated later request on the same
 * thread, which attributes one user's log lines to another's correlation identifier. A test that only checks
 * the happy path cannot see that; the assertions here inspect the context after the chain returns, after the
 * chain throws, and after a request that ran with a pre-existing context.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>{@code ./mvnw -B clean test}, or this class alone with
 * {@code ./mvnw -B test -Dtest=CorrelationIdFilterTest}. No container and no database are required: the
 * servlet contract is exercised through Spring's mock request and response, and the tracer is a stub.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None. The filter reads no property and no environment variable.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A key-spelling assertion fails</dt>
 *   <dd>An MDC key was renamed. {@code logback-spring.xml} binds these names literally, so a drift empties a
 *       JSON field silently rather than failing anything at runtime - which is why the spellings are pinned
 *       here rather than left to the encoder to reveal.</dd>
 *   <dt>A context-restoration assertion fails intermittently</dt>
 *   <dd>The context is being cleared rather than restored, or restored outside a {@code finally}. Both
 *       present as cross-test interference because JUnit reuses the calling thread.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe. Each method mutates the calling thread's diagnostic context deliberately and clears it
 * in an {@code @AfterEach}, so no entry escapes into another test.
 */
@DisplayName("CorrelationIdFilter: the request-scoped thread of identity the corpus never had")
class CorrelationIdFilterTest {

    /** A supplied identifier that satisfies the accepted character set and length. */
    private static final String WELL_FORMED = "client-supplied_ID-0123456789";

    /** The filter under test, rebuilt per method over a tracer that reports no active span. */
    private CorrelationIdFilter filter;

    /** Stub tracer; individual tests override {@code currentSpan()} where a span matters. */
    private Tracer tracer;

    /** Mock request, rebuilt per method so no header survives between tests. */
    private MockHttpServletRequest request;

    /** Mock response, rebuilt per method. */
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MDC.clear();
        this.tracer = Mockito.mock(Tracer.class);
        Mockito.when(this.tracer.currentSpan()).thenReturn(null);
        this.filter = new CorrelationIdFilter(this.tracer);
        this.request = new MockHttpServletRequest("GET", "/api/accounts/00000000001");
        this.response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearDiagnosticContext() {
        MDC.clear();
    }

    /**
     * Records the diagnostic context as it stood while the chain was executing, which is the only moment the
     * values are supposed to be visible.
     */
    private static final class ContextCapturingChain implements FilterChain {

        /** Correlation identifier seen inside the chain, or {@code null} if none was set. */
        private String correlationId;

        /** Trace identifier seen inside the chain, or {@code null} if none was set. */
        private String traceId;

        /** Span identifier seen inside the chain, or {@code null} if none was set. */
        private String spanId;

        /** Trace-flags octet seen inside the chain, or {@code null} if none was set. */
        private String traceFlags;

        /** How many times the chain was invoked, which must be exactly one per request. */
        private int invocations;

        @Override
        public void doFilter(final jakarta.servlet.ServletRequest servletRequest,
                             final jakarta.servlet.ServletResponse servletResponse) {
            this.invocations++;
            this.correlationId = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
            this.traceId = MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID);
            this.spanId = MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID);
            this.traceFlags = MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_FLAGS);
        }
    }

    /** Publication of the identifier on the response and into the diagnostic context. */
    @Nested
    @DisplayName("Publication: every request leaves with a correlation identifier")
    class Publication {

        @Test
        @DisplayName("an absent request header yields a minted identifier, published and in context")
        void absentHeaderMintsAnIdentifier() throws ServletException, IOException {
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            String published = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
            assertThat(published).isNotBlank();
            assertThat(published).hasSizeLessThanOrEqualTo(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH);
            assertThat(published).matches("[A-Za-z0-9_-]+");
            assertThat(chain.invocations).isOne();
            assertThat(chain.correlationId)
                    .as("the chain must see the identifier, since that is when logging happens")
                    .isEqualTo(published);
        }

        @Test
        @DisplayName("a minted identifier is a fresh value on each request, not a constant")
        void mintedIdentifiersDiffer() throws ServletException, IOException {
            List<String> minted = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                MockHttpServletResponse freshResponse = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET", "/api/cards"),
                        freshResponse, new ContextCapturingChain());
                minted.add(freshResponse.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
            }

            assertThat(minted).doesNotHaveDuplicates().doesNotContainNull();
        }

        @Test
        @DisplayName("a well-formed supplied identifier is echoed verbatim, so a caller can correlate")
        void wellFormedSuppliedHeaderIsHonoured() throws ServletException, IOException {
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, WELL_FORMED);
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(WELL_FORMED);
            assertThat(chain.correlationId).isEqualTo(WELL_FORMED);
        }

        @Test
        @DisplayName("an identifier already published on the response is reused, not replaced")
        void alreadyPublishedIdentifierIsReused() throws ServletException, IOException {
            response.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, WELL_FORMED);
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "a-different-one");
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .as("the response value wins, which keeps a re-entrant dispatch on one identifier")
                    .isEqualTo(WELL_FORMED);
            assertThat(chain.correlationId).isEqualTo(WELL_FORMED);
        }
    }

    /** Rejection of anything a caller supplies that is not demonstrably safe to echo. */
    @Nested
    @DisplayName("Untrusted input: a supplied identifier is echoed only when well formed")
    class UntrustedInput {

        @ParameterizedTest(name = "[{index}] a supplied {0} is replaced by a minted identifier")
        @ValueSource(strings = {
            "",
            "   ",
            "has spaces",
            "has/slash",
            "has:colon",
            "has;semicolon",
            "newline\ninjected",
            "carriage\rreturn",
            "tab\tseparated",
            "quote\"inside",
            "angle<bracket>",
            "percent%encoded",
        })
        @DisplayName("a malformed supplied identifier is never echoed")
        void malformedSuppliedHeaderIsReplaced(final String supplied)
                throws ServletException, IOException {

            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, supplied);

            filter.doFilter(request, response, new ContextCapturingChain());

            String published = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
            assertThat(published)
                    .as("echoing this back would put caller-controlled bytes into every log line")
                    .isNotEqualTo(supplied);
            assertThat(published).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("an over-length identifier is replaced, bounding the log field")
        void overLengthSuppliedHeaderIsReplaced() throws ServletException, IOException {
            String tooLong = "a".repeat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH + 1);
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, tooLong);

            filter.doFilter(request, response, new ContextCapturingChain());

            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .isNotEqualTo(tooLong)
                    .hasSizeLessThanOrEqualTo(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH);
        }

        @Test
        @DisplayName("an identifier of exactly the maximum length is accepted, so the bound is inclusive")
        void maximumLengthIdentifierIsAccepted() throws ServletException, IOException {
            String atLimit = "b".repeat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH);
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, atLimit);

            filter.doFilter(request, response, new ContextCapturingChain());

            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(atLimit);
        }
    }

    /** The property that protects a pooled thread from inheriting a previous request's identity. */
    @Nested
    @DisplayName("Diagnostic context hygiene: nothing survives the request that entered clean")
    class ContextHygiene {

        @Test
        @DisplayName("a context that was empty before the request is empty again afterwards")
        void emptyContextIsEmptyAgain() throws ServletException, IOException {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isNull();

            filter.doFilter(request, response, new ContextCapturingChain());

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("a leaked entry is inherited by the next request on this pooled thread")
                    .isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID)).isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID)).isNull();
        }

        @Test
        @DisplayName("a pre-existing entry is restored to its original value, not cleared")
        void preExistingEntryIsRestored() throws ServletException, IOException {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "outer-scope-value");
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.correlationId)
                    .as("the inner request must run under its own identifier")
                    .isNotEqualTo("outer-scope-value");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("restoring must put the previous value back rather than remove the key")
                    .isEqualTo("outer-scope-value");
        }

        @Test
        @DisplayName("the context is restored even when the chain throws")
        void contextIsRestoredWhenTheChainThrows() {
            FilterChain exploding = (servletRequest, servletResponse) -> {
                throw new ServletException("downstream failure");
            };

            assertThatThrownBy(() -> filter.doFilter(request, response, exploding))
                    .isInstanceOf(ServletException.class);

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("restoration must sit in a finally block, or a failed request poisons the thread")
                    .isNull();
        }

        @Test
        @DisplayName("the response still carries an identifier when the chain throws")
        void identifierIsPublishedBeforeTheChainRuns() {
            FilterChain exploding = (servletRequest, servletResponse) -> {
                throw new ServletException("downstream failure");
            };

            assertThatThrownBy(() -> filter.doFilter(request, response, exploding))
                    .isInstanceOf(ServletException.class);

            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .as("the header is set before the chain, so a failure is still correlatable")
                    .isNotBlank();
        }
    }

    /** Trace-context propagation, which is present only when a span is active. */
    @Nested
    @DisplayName("Trace context: copied into the diagnostic context only when a span is active")
    class TraceContextPropagation {

        @Test
        @DisplayName("with no active span, no trace or span identifier is published")
        void noActiveSpanPublishesNoTraceIdentity() throws ServletException, IOException {
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.traceId).isNull();
            assertThat(chain.spanId).isNull();
            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNotBlank();
        }

        @Test
        @DisplayName("an active span is tagged and its identity reaches the diagnostic context")
        void activeSpanIsTaggedAndCopied() throws ServletException, IOException {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            String spanId = "abcdef0123456789";

            TraceContext context = Mockito.mock(TraceContext.class);
            Mockito.when(context.traceId()).thenReturn(traceId);
            Mockito.when(context.spanId()).thenReturn(spanId);
            Span span = Mockito.mock(Span.class);
            Mockito.when(span.context()).thenReturn(context);
            Mockito.when(tracer.currentSpan()).thenReturn(span);

            ContextCapturingChain chain = new ContextCapturingChain();
            filter.doFilter(request, response, chain);

            assertThat(chain.traceId).isEqualTo(traceId);
            assertThat(chain.spanId).isEqualTo(spanId);
            Mockito.verify(span).tag(CorrelationIdFilter.SPAN_TAG_CORRELATION_ID,
                    response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
        }

        @Test
        @DisplayName("an active span with no context is tagged but publishes no identity")
        void activeSpanWithoutContextIsTolerated() throws ServletException, IOException {
            Span span = Mockito.mock(Span.class);
            Mockito.when(span.context()).thenReturn(null);
            Mockito.when(tracer.currentSpan()).thenReturn(span);

            ContextCapturingChain chain = new ContextCapturingChain();
            filter.doFilter(request, response, chain);

            assertThat(chain.traceId).isNull();
            assertThat(chain.spanId).isNull();
            Mockito.verify(span).tag(Mockito.eq(CorrelationIdFilter.SPAN_TAG_CORRELATION_ID),
                    Mockito.anyString());
        }

        @Test
        @DisplayName("trace entries are restored after the request, exactly like the correlation entry")
        void traceEntriesAreRestored() throws ServletException, IOException {
            TraceContext context = Mockito.mock(TraceContext.class);
            Mockito.when(context.traceId()).thenReturn("11111111111111111111111111111111");
            Mockito.when(context.spanId()).thenReturn("2222222222222222");
            Span span = Mockito.mock(Span.class);
            Mockito.when(span.context()).thenReturn(context);
            Mockito.when(tracer.currentSpan()).thenReturn(span);

            filter.doFilter(request, response, new ContextCapturingChain());

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID)).isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID)).isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_FLAGS))
                    .as("finding M-03: the sampling decision shares the identifiers' lifetime, so a leaked "
                            + "flag could describe the next request's span")
                    .isNull();
        }
    }

    /**
     * The sampling decision, and the header composed from it.
     *
     * <p><strong>Finding M-03, severity Medium.</strong> The trace-flags octet was a hard-coded {@code 01}.
     * Under the base profile, which samples every trace, that assertion was accidentally true - so the defect
     * was invisible to every test and to all local running. Under {@code application-prod.yml}, which samples
     * one in ten, it was wrong nine times in ten, and each wrong header told the next hop to record a child of
     * a trace this process had already dropped. These tests exercise both decisions and the deferred third
     * state, so the flag can no longer be right by coincidence.
     */
    @Nested
    @DisplayName("Sampling decision: the traceparent flags octet states it rather than asserting it (M-03)")
    class SamplingDecisionPropagation {

        /** The specification's own example trace identifier. */
        private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

        /** The specification's own example span identifier. */
        private static final String SPAN_ID = "00f067aa0ba902b7";

        /**
         * Makes a span whose context reports the given sampling decision the current one.
         *
         * @param sampled the decision the tracing bridge should report; {@code null} means deferred
         */
        private void currentSpanSampled(final Boolean sampled) {
            TraceContext context = Mockito.mock(TraceContext.class);
            Mockito.when(context.traceId()).thenReturn(TRACE_ID);
            Mockito.when(context.spanId()).thenReturn(SPAN_ID);
            Mockito.when(context.sampled()).thenReturn(sampled);
            Span span = Mockito.mock(Span.class);
            Mockito.when(span.context()).thenReturn(context);
            Mockito.when(tracer.currentSpan()).thenReturn(span);
        }

        @Test
        @DisplayName("a sampled span yields flags 01 and a traceparent ending -01")
        void aSampledSpanIsAdvertisedAsSampled() throws ServletException, IOException {
            currentSpanSampled(Boolean.TRUE);
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.traceFlags).isEqualTo("01");
        }

        @Test
        @DisplayName("an UNSAMPLED span yields flags 00 - the defect this finding names")
        void anUnsampledSpanIsNoLongerAdvertisedAsSampled() throws ServletException, IOException {
            currentSpanSampled(Boolean.FALSE);
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.traceFlags)
                    .as("this process decided not to record the trace; telling the next hop otherwise leaves "
                            + "an orphaned child at the collector")
                    .isEqualTo("00");
        }

        @Test
        @DisplayName("a deferred decision yields flags 01, which is documented rather than incidental")
        void aDeferredDecisionDefaultsToSampled() throws ServletException, IOException {
            currentSpanSampled(null);
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.traceFlags)
                    .as("null means no sampler has run yet, not decided-against: this process may still "
                            + "export the span, and a broken trace is harder to diagnose than an eager one")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("the composed traceparent carries the unsampled flag through to the wire form")
        void theComposedHeaderCarriesTheUnsampledFlag() throws ServletException, IOException {
            currentSpanSampled(Boolean.FALSE);
            final String[] observed = new String[1];

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    observed[0] = CorrelationIdFilter.currentTraceParent());

            assertThat(observed[0])
                    .as("currentTraceParent composes from the ambient context, so the decision has to travel "
                            + "in that context to reach it")
                    .isEqualTo("00-" + TRACE_ID + '-' + SPAN_ID + "-00");
        }

        @Test
        @DisplayName("the explicit form states the decision it is given, both ways and when deferred")
        void theExplicitFormStatesTheDecisionItIsGiven() {
            assertThat(CorrelationIdFilter.traceParent(TRACE_ID, SPAN_ID, Boolean.TRUE))
                    .isEqualTo("00-" + TRACE_ID + '-' + SPAN_ID + "-01");
            assertThat(CorrelationIdFilter.traceParent(TRACE_ID, SPAN_ID, Boolean.FALSE))
                    .isEqualTo("00-" + TRACE_ID + '-' + SPAN_ID + "-00");
            assertThat(CorrelationIdFilter.traceParent(TRACE_ID, SPAN_ID, null))
                    .as("a caller holding a TraceContext passes sampled() straight through and does not have "
                            + "to decide for itself what a null means")
                    .isEqualTo("00-" + TRACE_ID + '-' + SPAN_ID + "-01");
        }

        @Test
        @DisplayName("an unusable identifier still yields no header, whatever the decision says")
        void anUnusableIdentifierStillYieldsNoHeader() {
            assertThat(CorrelationIdFilter.traceParent("00000000000000000000000000000000", SPAN_ID,
                    Boolean.FALSE))
                    .as("the sampling decision is orthogonal to validity; a malformed header is worse than an "
                            + "absent one either way")
                    .isNull();
        }

        @Test
        @DisplayName("with no active span there is no decision to publish, and none is invented")
        void noActiveSpanPublishesNoDecision() throws ServletException, IOException {
            ContextCapturingChain chain = new ContextCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.traceFlags)
                    .as("a flag without identifiers describes nothing, and currentTraceParent already yields "
                            + "no header in this state")
                    .isNull();
        }

        @Test
        @DisplayName("an all-zero trace identifier is refused, because it means no trace was recorded")
        void anAllZeroTraceIdentifierIsRefused() throws ServletException, IOException {
            TraceContext context = Mockito.mock(TraceContext.class);
            Mockito.when(context.traceId()).thenReturn("00000000000000000000000000000000");
            Mockito.when(context.spanId()).thenReturn("0000000000000000");
            Span span = Mockito.mock(Span.class);
            Mockito.when(span.context()).thenReturn(context);
            Mockito.when(tracer.currentSpan()).thenReturn(span);

            ContextCapturingChain chain = new ContextCapturingChain();
            filter.doFilter(request, response, chain);

            assertThat(chain.traceId)
                    .as("an all-zero identifier is the not-recorded sentinel, so publishing it would put "
                            + "a value that means 'no trace' into every log line as though it were one")
                    .isNull();
            assertThat(chain.spanId).isNull();
        }

        @Test
        @DisplayName("a blank or absent trace identifier is refused")
        void aBlankTraceIdentifierIsRefused() throws ServletException, IOException {
            TraceContext context = Mockito.mock(TraceContext.class);
            Mockito.when(context.traceId()).thenReturn("  ");
            Mockito.when(context.spanId()).thenReturn(null);
            Span span = Mockito.mock(Span.class);
            Mockito.when(span.context()).thenReturn(context);
            Mockito.when(tracer.currentSpan()).thenReturn(span);

            ContextCapturingChain chain = new ContextCapturingChain();
            filter.doFilter(request, response, chain);

            assertThat(chain.traceId)
                    .as("whitespace is not an identifier, and neither is null; both are refused rather "
                            + "than published as an empty field a query would still match")
                    .isNull();
            assertThat(chain.spanId).isNull();
        }
    }

    /**
     * Two dispatch and nesting properties that the publication group's happy paths cannot show.
     *
     * <p>Consolidated here from a second unit test of this same class that covered the filter twice over.
     * Everything the other rendition asserted is now in this file: these two cases were the only ones it
     * held that this one did not, so the merge removed a duplicated suite without losing an assertion.
     */
    @Nested
    @DisplayName("Dispatch and nesting: the cases a single straight-through request cannot show")
    class DispatchAndNesting {

        @Test
        @DisplayName("the filter also runs on an error dispatch, so a 500 is still correlated")
        void theFilterAlsoRunsOnAnErrorDispatch() throws ServletException, IOException {
            request.setDispatcherType(DispatcherType.ERROR);

            filter.doFilter(request, response, new ContextCapturingChain());

            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .as("an error dispatch is exactly when a correlation identifier matters most - it is "
                            + "the request someone will come looking for - so the filter must not be "
                            + "restricted to the initial dispatch")
                    .isNotBlank();
        }

        @Test
        @DisplayName("a pre-existing context does not suppress the fresh identifier during the chain")
        void aPreExistingContextDoesNotSuppressTheFreshIdentifier() throws ServletException, IOException {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "outer-context");
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "inner-context");

            ContextCapturingChain chain = new ContextCapturingChain();
            filter.doFilter(request, response, chain);

            assertThat(chain.correlationId)
                    .as("the inner request's own identifier wins WHILE the chain runs - an outer value must "
                            + "not leak into the nested request's log lines")
                    .isEqualTo("inner-context");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("and the outer value is put back afterwards, so restoration is a swap rather than "
                            + "a clear. A clear would silently de-correlate everything after the nested call")
                    .isEqualTo("outer-context");
        }
    }

    /** Names and ordering that other artefacts bind literally and that must therefore not drift. */
    @Nested
    @DisplayName("Bound names and ordering: values other files depend on literally")
    class BoundContract {

        @Test
        @DisplayName("the MDC key spellings logback-spring.xml binds are exactly these")
        void mdcKeySpellingsArePinned() {
            assertThat(CorrelationIdFilter.MDC_KEY_CORRELATION_ID).isEqualTo("correlationId");
            assertThat(CorrelationIdFilter.MDC_KEY_TRACE_ID).isEqualTo("traceId");
            assertThat(CorrelationIdFilter.MDC_KEY_SPAN_ID).isEqualTo("spanId");
            assertThat(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID).isEqualTo("jobInstanceId");
            assertThat(CorrelationIdFilter.MDC_KEY_TRACE_FLAGS)
                    .as("finding M-03 added this one; logback-spring.xml does not render it, so nothing but "
                            + "this assertion would notice a change of spelling")
                    .isEqualTo("traceFlags");
        }

        @Test
        @DisplayName("the wire header and span tag names are exactly these")
        void wireNamesArePinned() {
            assertThat(CorrelationIdFilter.CORRELATION_ID_HEADER).isEqualTo("X-Correlation-Id");
            assertThat(CorrelationIdFilter.SPAN_TAG_CORRELATION_ID).isEqualTo("correlation.id");
        }

        @Test
        @DisplayName("the filter runs ahead of every logging filter, but behind the span opener")
        void filterRunsAtHighestPrecedence() {
            // Not HIGHEST_PRECEDENCE: Boot's ServerHttpObservationFilter sits at HIGHEST_PRECEDENCE + 1 and
            // opens the observation this filter tags with correlation.id. Ahead of it there was no span to
            // tag. Two places behind it there is one, and everything that logs still runs later.
            assertThat(CorrelationIdFilter.ORDER)
                    .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2)
                    .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1);
        }

        @Test
        @DisplayName("the length bound is 64, which keeps the log field bounded")
        void lengthBoundIsPinned() {
            assertThat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH).isEqualTo(64);
        }

        @Test
        @DisplayName("a null tracer is refused at construction rather than at the first request")
        void nullTracerIsRefused() {
            assertThatThrownBy(() -> new CorrelationIdFilter(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
