package com.carddemo.observability;

import java.util.Map;
import java.util.Objects;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * A {@link TaskDecorator} that carries the submitting thread's diagnostic context onto the worker
 * threads of the batch pipeline's parallel stage-4 split, so log correlation and distributed traces
 * remain unbroken across the thread boundary.
 *
 * <p><strong>Why this exists.</strong> The {@code BatchPipelineOrchestrator} runs stage&nbsp;4a
 * (statement generation) and stage&nbsp;4b (transaction reporting) concurrently via
 * {@code FlowBuilder.split(TaskExecutor)} on the bounded {@code batchTaskExecutor}. Without
 * intervention each branch executes on a fresh {@code carddemo-batch-*} worker thread that inherits
 * none of the launcher's context: the SLF4J {@link MDC} (which carries the {@code correlationId}
 * established by {@link CorrelationIdFilter}) is empty, and any active Micrometer observation /
 * tracing span is absent, so the branch steps would emit logs without a correlation id and start
 * unrelated traces. That breaks the Observability rule (AAP &sect;0.7.1), which requires correlation
 * and trace context to propagate through the batch threads.</p>
 *
 * <p><strong>What it propagates.</strong> Two distinct mechanisms are combined because they cover
 * different context:</p>
 * <ul>
 *   <li><em>MDC, captured explicitly.</em> The {@code context-propagation} library ships
 *       {@code Slf4jThreadLocalAccessor} but does <strong>not</strong> register it via
 *       {@code ServiceLoader}, so {@link ContextSnapshotFactory#captureAll(Object...)} alone does
 *       <strong>not</strong> capture the MDC. The MDC map (including {@code correlationId}) is
 *       therefore copied directly with {@link MDC#getCopyOfContextMap()} on the submitting thread
 *       and re-applied on the worker thread.</li>
 *   <li><em>Observation / tracing context, via a {@link ContextSnapshot}.</em> Micrometer's
 *       {@code ObservationThreadLocalAccessor} <em>is</em> {@code ServiceLoader}-registered, so the
 *       captured snapshot restores the current observation (and, through the tracing bridge, the
 *       current span) on the worker thread. This makes the two branch steps children of the
 *       pipeline's trace rather than roots of new ones.</li>
 * </ul>
 *
 * <p><strong>Execution order on the worker thread.</strong> The worker's pre-existing MDC is saved,
 * the captured MDC is applied, then the observation snapshot scope is opened; the delegate runs; and
 * finally the snapshot scope is closed and the worker's original MDC is restored. Saving and
 * restoring the worker's prior state matters because the executor pools and reuses its threads, so a
 * task must not leak its context into the next task that lands on the same thread. When the
 * submitting thread had no MDC, the worker's MDC is cleared for the duration of the task so a stale
 * correlation id is never carried in.</p>
 *
 * <p>The decorator is applied at task-submission time on the submitting (launcher) thread; the
 * returned {@link Runnable} performs the capture/restore on the worker thread when the executor runs
 * it. The class is stateless aside from its {@link ContextSnapshotFactory} and is therefore
 * thread-safe and reusable across submissions.</p>
 */
public final class BatchContextPropagatingTaskDecorator implements TaskDecorator {

    /** Factory used to capture the submitting thread's observation/tracing context as a snapshot. */
    private final ContextSnapshotFactory contextSnapshotFactory;

    /**
     * Creates a decorator backed by a {@link ContextSnapshotFactory} bound to the global
     * {@code ContextRegistry}. This is the constructor used in production wiring (by
     * {@code com.carddemo.config.BatchConfig}); the global registry is where Micrometer's
     * {@code ObservationThreadLocalAccessor} is registered, so observation/tracing context is
     * captured and restored.
     */
    public BatchContextPropagatingTaskDecorator() {
        this(ContextSnapshotFactory.builder().build());
    }

    /**
     * Creates a decorator backed by the supplied {@link ContextSnapshotFactory}. This constructor lets
     * callers (and unit tests) provide a factory bound to a specific {@code ContextRegistry} &mdash;
     * for example one with a controlled {@code ThreadLocalAccessor} &mdash; so snapshot-based
     * propagation can be wired or asserted deterministically without relying on the global registry.
     *
     * @param contextSnapshotFactory the factory used to capture the submitter's context; must not be
     *                               {@code null}
     */
    public BatchContextPropagatingTaskDecorator(final ContextSnapshotFactory contextSnapshotFactory) {
        this.contextSnapshotFactory =
                Objects.requireNonNull(contextSnapshotFactory, "contextSnapshotFactory must not be null");
    }

    /**
     * Wraps {@code runnable} so that, when it later runs on a worker thread, it observes the MDC and
     * observation/tracing context that were active on the thread that submitted the task. The MDC map
     * and the context snapshot are captured eagerly here (on the submitting thread); the returned
     * {@link Runnable} applies and later restores them around the delegate's execution.
     *
     * @param runnable the task to decorate; must not be {@code null}
     * @return a context-propagating wrapper around {@code runnable}
     */
    @Override
    public Runnable decorate(final Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        // Captured on the SUBMITTING thread (the pipeline launcher). getCopyOfContextMap() returns
        // null when the submitter has no MDC, which is handled explicitly below.
        final Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        final ContextSnapshot capturedContext = this.contextSnapshotFactory.captureAll();
        return () -> {
            // Runs on a pooled worker thread (carddemo-batch-*). Preserve its prior MDC so the task
            // does not leak context into the next task scheduled on the same thread.
            final Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            applyMdc(capturedMdc);
            final ContextSnapshot.Scope scope = capturedContext.setThreadLocals();
            try {
                runnable.run();
            } finally {
                // Close in reverse order: restore observation/tracing first, then the MDC.
                scope.close();
                applyMdc(previousMdc);
            }
        };
    }

    /**
     * Applies an MDC snapshot to the current thread, treating {@code null} as "no context" by
     * clearing the MDC. {@link MDC#setContextMap(Map)} rejects {@code null}, so the branch is
     * required.
     *
     * @param mdc the MDC map to install, or {@code null} to clear the MDC
     */
    private static void applyMdc(final Map<String, String> mdc) {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        } else {
            MDC.clear();
        }
    }
}
