package com.carddemo.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * A tiny, in-process {@link SpanExporter} that simply retains every finished span so a test can assert
 * on the emitted span graph. It is the hermetic, dependency-free stand-in for
 * {@code io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter}: the {@code opentelemetry-sdk-testing}
 * artifact is not present in the offline Maven cache, whereas {@link SpanExporter}, {@link SpanData} and
 * {@code SimpleSpanProcessor} are already on the test classpath (transitively via
 * {@code micrometer-tracing-bridge-otel} &rarr; {@code opentelemetry-sdk-trace}). Implementing the
 * three-method interface here keeps the distributed-tracing integration tests fully hermetic (no live
 * Jaeger/OTLP collector, no network) &mdash; the same LocalStack-only, zero-live-AWS philosophy the rest
 * of the suite follows (AAP&nbsp;&sect;0.7.7).
 *
 * <p>Wired into the Spring-managed OpenTelemetry SDK by a {@code @Bean SpanProcessor} that wraps this
 * exporter in a {@code SimpleSpanProcessor} (synchronous export on span end), so a span is visible to
 * {@link #getFinishedSpans()} as soon as it ends. Access is synchronized because spans finish on
 * request-handling, batch, and SDK-client threads while the test thread reads them.</p>
 *
 * <p>This class is a test <em>fixture</em>, not a test: it declares no {@code @Test} method and is never
 * named {@code *Test}/{@code *IT}, so neither Surefire nor Failsafe executes it.</p>
 */
final class InMemorySpanCollector implements SpanExporter {

    /** All spans received so far, guarded by {@code this}. */
    private final List<SpanData> finishedSpans = new ArrayList<>();

    /**
     * Retains the finished spans. Invoked synchronously by {@code SimpleSpanProcessor} on the thread that
     * ended each span.
     *
     * @param spans the batch of just-finished spans; never {@code null}
     * @return always {@link CompletableResultCode#ofSuccess()} (retention cannot fail)
     */
    @Override
    public synchronized CompletableResultCode export(final Collection<SpanData> spans) {
        finishedSpans.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    /**
     * No-op: this exporter holds spans in memory, so there is nothing to flush to an external system.
     *
     * @return always {@link CompletableResultCode#ofSuccess()}
     */
    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    /**
     * No-op: there is no external connection to close.
     *
     * @return always {@link CompletableResultCode#ofSuccess()}
     */
    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    /**
     * Returns an immutable snapshot of every span captured so far. Callers should typically
     * {@link #reset()} before the action under test so only that action's spans are asserted on.
     *
     * @return a thread-safe copy of the captured spans (never {@code null})
     */
    synchronized List<SpanData> getFinishedSpans() {
        return Collections.unmodifiableList(new ArrayList<>(finishedSpans));
    }

    /**
     * Discards all captured spans, isolating one test (or one action) from the next. Necessary because
     * the OpenTelemetry SDK and this collector are singletons in the shared application context.
     */
    synchronized void reset() {
        finishedSpans.clear();
    }
}
