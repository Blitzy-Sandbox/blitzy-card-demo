package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit tests for {@link BatchConfig#batchTaskExecutor()}, the bounded
 * multi-threaded executor that backs the batch pipeline's parallel stage-4
 * split. Verifies the pool is configured (core 2, max 4, named prefix), is
 * returned un-initialized so Spring's {@code afterPropertiesSet} builds the
 * pool exactly once, and &mdash; behaviorally, since {@link ThreadPoolTaskExecutor}
 * exposes no task-decorator getter &mdash; that it propagates the submitting
 * thread's MDC {@code correlationId} onto its worker threads via the wired
 * {@code BatchContextPropagatingTaskDecorator} (Observability rule, AAP §0.7.1).
 */
class BatchConfigTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("batchTaskExecutor is a bounded, named ThreadPoolTaskExecutor")
    void batchTaskExecutorConfigured() {
        TaskExecutor executor = new BatchConfig().batchTaskExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("carddemo-batch-");
    }

    @Test
    @DisplayName("batchTaskExecutor propagates the submitting thread's MDC correlationId to worker threads")
    void batchTaskExecutorPropagatesCorrelationId() throws InterruptedException {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) new BatchConfig().batchTaskExecutor();
        pool.initialize();
        try {
            MDC.put("correlationId", "CFG-CORRELATION-123");

            AtomicReference<String> seenOnWorker = new AtomicReference<>();
            AtomicReference<String> workerThreadName = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(() -> {
                try {
                    workerThreadName.set(Thread.currentThread().getName());
                    seenOnWorker.set(MDC.get("correlationId"));
                } finally {
                    done.countDown();
                }
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            // Ran on a real pool worker (not the test thread) and still saw the submitter's id.
            assertThat(workerThreadName.get()).startsWith("carddemo-batch-");
            assertThat(seenOnWorker.get()).isEqualTo("CFG-CORRELATION-123");
        } finally {
            pool.shutdown();
        }
    }
}
