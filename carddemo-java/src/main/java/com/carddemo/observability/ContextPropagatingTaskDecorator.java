package com.carddemo.observability;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * {@link TaskDecorator} that carries the submitting thread's observability context onto the worker
 * threads of a {@link org.springframework.core.task.TaskExecutor}. It is applied to the
 * {@code batchTaskExecutor} that backs the batch pipeline's parallel stage-4 split (statement
 * generation running concurrently with transaction reporting), so the CREASTMT/TRANREPT worker
 * threads emit logs and spans correlated with the launching pipeline thread rather than losing the
 * context across the thread-pool boundary.
 *
 * <p>Two complementary mechanisms are combined because they cover disjoint context:</p>
 * <ul>
 *   <li><b>SLF4J {@link MDC}.</b> The full MDC map (notably the {@code correlationId} key published
 *       by {@code CorrelationIdFilter} and the {@code BatchPipelineOrchestrator}, and rendered by
 *       {@code logback-spring.xml} as {@code %X{correlationId}}) is captured on the submitting
 *       thread and re-applied on the worker thread, then restored to the worker's prior value so a
 *       pooled thread never leaks one task's context into the next.</li>
 *   <li><b>Micrometer {@link ContextSnapshot}.</b> A snapshot of every registered
 *       {@code ThreadLocalAccessor} (including the Micrometer Tracing / OpenTelemetry observation
 *       scope that owns {@code traceId}/{@code spanId}) is captured and re-established for the
 *       duration of the task, so spans created on the worker thread attach to the pipeline's
 *       trace.</li>
 * </ul>
 *
 * <p>The decorator degrades gracefully: when the submitting thread has no MDC the worker MDC is
 * cleared, and when no observation context is active {@link ContextSnapshot#setThreadLocals()}
 * yields a no-op scope. The decorator is stateless and thread-safe; a single instance can decorate
 * every submitted task. The rationale for adding context propagation to the batch split executor is
 * recorded in {@code DECISION_LOG.md} (Explainability rule, AAP &sect;0.7.3), not in code comments.
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

    /** SLF4J MDC key carrying the request/launch correlation id; matches {@code CorrelationIdFilter}. */
    private static final String CORRELATION_ID_KEY = "correlationId";

    /** Factory for capturing the registered thread-local (tracing/observation) context. */
    private final ContextSnapshotFactory contextSnapshotFactory;

    /**
     * Creates a decorator backed by a default {@link ContextSnapshotFactory} that captures all
     * registered {@code ThreadLocalAccessor}s.
     */
    public ContextPropagatingTaskDecorator() {
        this.contextSnapshotFactory = ContextSnapshotFactory.builder().build();
    }

    /**
     * Wraps {@code runnable} so that, when it later executes on a worker thread, it observes the MDC
     * and Micrometer context captured here on the submitting thread, and the worker's prior context
     * is restored afterwards.
     *
     * @param runnable the task to execute on a worker thread
     * @return a decorated task that establishes and then restores the propagated context
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        ContextSnapshot capturedContext = contextSnapshotFactory.captureAll();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            applyMdc(capturedMdc);
            ContextSnapshot.Scope scope = capturedContext.setThreadLocals();
            try {
                runnable.run();
            } finally {
                // Close the tracing scope first, then unconditionally restore the worker's prior MDC
                // (the nested finally guarantees restoration even if the scope close throws).
                try {
                    scope.close();
                } finally {
                    applyMdc(previousMdc);
                }
            }
        };
    }

    /**
     * Replaces the current thread's MDC with {@code mdc}, or clears it when {@code mdc} is
     * {@code null} (the submitting/worker thread carried no MDC).
     *
     * @param mdc the MDC map to install, or {@code null} to clear the MDC
     */
    private static void applyMdc(Map<String, String> mdc) {
        if (mdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
    }

    /**
     * @return the MDC key under which the correlation id propagates, exposed for tests asserting
     *         split-stage correlation is preserved
     */
    public static String correlationIdKey() {
        return CORRELATION_ID_KEY;
    }
}
