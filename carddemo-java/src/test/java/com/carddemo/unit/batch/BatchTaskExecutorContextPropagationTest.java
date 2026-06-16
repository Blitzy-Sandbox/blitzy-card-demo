package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.carddemo.config.BatchConfig;
import com.carddemo.observability.ContextPropagatingTaskDecorator;

/**
 * Pure-JVM unit tests proving the batch pipeline's parallel stage-4 split preserves the
 * observability correlation context across the thread-pool boundary (Observability rule, AAP
 * &sect;0.7.1). The split runs CREASTMT alongside TRANREPT on the {@code batchTaskExecutor}
 * ({@link BatchConfig#batchTaskExecutor()}); because that executor carries a
 * {@link ContextPropagatingTaskDecorator}, a task launched with a {@code correlationId} in the MDC
 * still observes that id when it runs on a worker thread, and the worker thread does not leak the
 * propagated context into subsequent tasks.
 *
 * <p>These assertions are the unit-level evidence the CP5 review required: that split-stage
 * logs/spans keep the pipeline correlation id rather than losing it on the executor threads.</p>
 */
class BatchTaskExecutorContextPropagationTest {

    /** MDC key under which the correlation id is published and rendered ({@code %X{correlationId}}). */
    private static final String CORRELATION_ID_KEY = ContextPropagatingTaskDecorator.correlationIdKey();

    /** A secondary MDC key proving the whole MDC map (not just one key) is propagated. */
    private static final String EXTRA_KEY = "batchStage";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("batchTaskExecutor propagates the launching thread's correlationId to its worker thread")
    void batchTaskExecutorPropagatesCorrelationIdToWorkerThread() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new BatchConfig().batchTaskExecutor();
        // Spring normally calls afterPropertiesSet(); a standalone unit test must initialize the pool.
        executor.afterPropertiesSet();
        try {
            String expectedCorrelationId = "corr-" + System.nanoTime();
            MDC.put(CORRELATION_ID_KEY, expectedCorrelationId);
            MDC.put(EXTRA_KEY, "stage4-split");

            Thread submitterThread = Thread.currentThread();
            CompletableFuture<String[]> observedOnWorker = new CompletableFuture<>();
            TaskExecutor asTaskExecutor = executor;
            asTaskExecutor.execute(() -> {
                if (Thread.currentThread() == submitterThread) {
                    observedOnWorker.completeExceptionally(
                            new AssertionError("task unexpectedly ran on the submitting thread"));
                    return;
                }
                observedOnWorker.complete(
                        new String[] {MDC.get(CORRELATION_ID_KEY), MDC.get(EXTRA_KEY)});
            });

            String[] observed = observedOnWorker.get(10, TimeUnit.SECONDS);
            assertThat(observed[0])
                    .as("worker thread sees the launching thread's correlationId")
                    .isEqualTo(expectedCorrelationId);
            assertThat(observed[1])
                    .as("the whole MDC map propagates, not only the correlationId key")
                    .isEqualTo("stage4-split");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("decorator restores the worker thread's prior MDC so a pooled thread leaks no context")
    void decoratorRestoresPriorMdcAndLeaksNoContext() {
        ContextPropagatingTaskDecorator decorator = new ContextPropagatingTaskDecorator();

        // Capture happens on the "submitting" thread, which carries the pipeline correlation id.
        MDC.put(CORRELATION_ID_KEY, "pipeline-correlation");
        CompletableFuture<String> seenInsideTask = new CompletableFuture<>();
        Runnable decorated = decorator.decorate(() -> seenInsideTask.complete(MDC.get(CORRELATION_ID_KEY)));

        // Simulate a pooled worker thread that already holds a stale value from a previous task.
        MDC.put(CORRELATION_ID_KEY, "stale-worker-value");
        decorated.run();

        assertThat(seenInsideTask.getNow(null))
                .as("the decorated task observes the captured (pipeline) correlationId")
                .isEqualTo("pipeline-correlation");
        assertThat(MDC.get(CORRELATION_ID_KEY))
                .as("after the task the worker's prior MDC is restored (no context leak)")
                .isEqualTo("stale-worker-value");
    }

    @Test
    @DisplayName("decorator clears worker MDC when the submitting thread carried none")
    void decoratorClearsWorkerMdcWhenNoContextCaptured() {
        ContextPropagatingTaskDecorator decorator = new ContextPropagatingTaskDecorator();

        // No MDC on the "submitting" thread at capture time.
        MDC.clear();
        CompletableFuture<String> seenInsideTask = new CompletableFuture<>();
        Runnable decorated = decorator.decorate(() -> seenInsideTask.complete(MDC.get(CORRELATION_ID_KEY)));

        // A worker thread with a stale value must be cleared for the task and restored afterwards.
        MDC.put(CORRELATION_ID_KEY, "stale-worker-value");
        decorated.run();

        assertThat(seenInsideTask.getNow("not-run"))
                .as("with no captured MDC the task sees a cleared correlationId")
                .isNull();
        assertThat(MDC.get(CORRELATION_ID_KEY))
                .as("the worker's prior MDC is still restored afterwards")
                .isEqualTo("stale-worker-value");
    }
}
