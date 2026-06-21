package com.carddemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.observability.BatchContextPropagatingTaskDecorator;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for {@link BatchContextPropagatingTaskDecorator}.
 *
 * <p>The decorator captures context on the <em>submitting</em> thread (inside {@code decorate})
 * and re-applies it on the <em>worker</em> thread (when the wrapped {@link Runnable} runs). To
 * exercise that boundary faithfully &mdash; the MDC and the context snapshot are thread-locals
 * &mdash; each test calls {@code decorate} on the test thread and then runs the wrapper on a freshly
 * spawned {@link Thread}, capturing what that worker observed during and after execution.</p>
 *
 * <p>Coverage: correlation-id propagation, clearing when the submitter has no context, restoration of
 * a pooled worker's prior MDC, snapshot-based propagation of an observation-style thread-local via an
 * injected {@link ContextRegistry}, and null-safety of the constructor and {@code decorate}.</p>
 */
class BatchContextPropagatingTaskDecoratorTest {

    private static final String CORRELATION_ID = "correlationId";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("propagates the submitting thread's correlationId onto the worker thread")
    void propagatesCorrelationIdToWorker() throws InterruptedException {
        MDC.put(CORRELATION_ID, "SUBMIT-123");
        BatchContextPropagatingTaskDecorator decorator = new BatchContextPropagatingTaskDecorator();

        AtomicReference<String> seenOnWorker = new AtomicReference<>();
        Runnable wrapped = decorator.decorate(() -> seenOnWorker.set(MDC.get(CORRELATION_ID)));

        runOnFreshThread(wrapped);

        assertThat(seenOnWorker.get()).isEqualTo("SUBMIT-123");
    }

    @Test
    @DisplayName("clears the worker MDC when the submitting thread has no context")
    void clearsWorkerMdcWhenSubmitterHasNone() throws InterruptedException {
        MDC.clear(); // submitting thread carries no correlation id
        BatchContextPropagatingTaskDecorator decorator = new BatchContextPropagatingTaskDecorator();

        AtomicReference<String> seenDuring = new AtomicReference<>("unset");
        AtomicReference<String> seenAfter = new AtomicReference<>();
        Runnable wrapped = decorator.decorate(() -> seenDuring.set(MDC.get(CORRELATION_ID)));

        Thread worker = new Thread(() -> {
            MDC.put(CORRELATION_ID, "WORKER-STALE"); // pooled thread's leftover context
            wrapped.run();
            seenAfter.set(MDC.get(CORRELATION_ID));
        });
        worker.start();
        worker.join();

        assertThat(seenDuring.get()).isNull();              // stale value not visible to the task
        assertThat(seenAfter.get()).isEqualTo("WORKER-STALE"); // worker's prior context restored
    }

    @Test
    @DisplayName("restores the pooled worker's prior MDC after the task completes")
    void restoresWorkerPriorMdc() throws InterruptedException {
        MDC.put(CORRELATION_ID, "SUBMIT-XYZ");
        BatchContextPropagatingTaskDecorator decorator = new BatchContextPropagatingTaskDecorator();

        AtomicReference<String> seenDuring = new AtomicReference<>();
        AtomicReference<String> seenAfter = new AtomicReference<>();
        Runnable wrapped = decorator.decorate(() -> seenDuring.set(MDC.get(CORRELATION_ID)));

        Map<String, String> priorWorkerMdc = new HashMap<>();
        priorWorkerMdc.put(CORRELATION_ID, "WORKER-PRIOR");
        Thread worker = new Thread(() -> {
            MDC.setContextMap(priorWorkerMdc);
            wrapped.run();
            seenAfter.set(MDC.get(CORRELATION_ID));
        });
        worker.start();
        worker.join();

        assertThat(seenDuring.get()).isEqualTo("SUBMIT-XYZ"); // submitter context applied for the task
        assertThat(seenAfter.get()).isEqualTo("WORKER-PRIOR"); // worker's prior context restored after
    }

    @Test
    @DisplayName("propagates observation/tracing-style context via the captured ContextSnapshot")
    void propagatesSnapshotThreadLocal() throws InterruptedException {
        // A private registry + thread-local accessor stands in for Micrometer's globally registered
        // ObservationThreadLocalAccessor, proving the ContextSnapshot path without touching global state.
        ThreadLocal<String> observationLike = new ThreadLocal<>();
        ContextRegistry registry = new ContextRegistry();
        registry.registerThreadLocalAccessor("test.observation", observationLike);
        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().contextRegistry(registry).build();
        BatchContextPropagatingTaskDecorator decorator = new BatchContextPropagatingTaskDecorator(factory);

        observationLike.set("OBS-SUBMIT"); // active on the submitting thread
        AtomicReference<String> seenDuring = new AtomicReference<>();
        AtomicReference<String> seenAfter = new AtomicReference<>("unset");
        Runnable wrapped = decorator.decorate(() -> seenDuring.set(observationLike.get()));

        Thread worker = new Thread(() -> {
            wrapped.run();
            seenAfter.set(observationLike.get());
        });
        worker.start();
        worker.join();

        assertThat(seenDuring.get()).isEqualTo("OBS-SUBMIT"); // snapshot restored on the worker
        assertThat(seenAfter.get()).isNull();                 // scope closed -> worker's prior (none) restored

        observationLike.remove();
    }

    @Test
    @DisplayName("rejects a null ContextSnapshotFactory")
    void rejectsNullFactory() {
        assertThatThrownBy(() -> new BatchContextPropagatingTaskDecorator(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects a null Runnable")
    void rejectsNullRunnable() {
        assertThatThrownBy(() -> new BatchContextPropagatingTaskDecorator().decorate(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** Runs {@code task} on a freshly spawned thread and waits for it to finish. */
    private static void runOnFreshThread(final Runnable task) throws InterruptedException {
        Thread worker = new Thread(task);
        worker.start();
        worker.join();
    }
}
