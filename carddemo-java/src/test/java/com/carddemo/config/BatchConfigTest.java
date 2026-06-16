package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit tests for {@link BatchConfig}.
 *
 * <p>Traceability (REFERENCE-ONLY; JCL lineage POSTTRAN/INTCALC/COMBTRAN, never copied; source
 * commit {@code 27d6c6f}): verifies the bounded {@link ThreadPoolTaskExecutor} that drives the
 * batch pipeline's parallel stage-4 split. The pool is configured (core 2 / max 4 / queue 16) but
 * not yet initialized, so the configured values are asserted directly on the bean.</p>
 */
@DisplayName("BatchConfig - parallel stage-4 task executor")
class BatchConfigTest {

    @Test
    @DisplayName("batchTaskExecutor is a bounded ThreadPoolTaskExecutor with the expected sizing")
    void batchTaskExecutorIsBoundedPool() {
        TaskExecutor executor = new BatchConfig().batchTaskExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getQueueCapacity()).isEqualTo(16);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("carddemo-batch-");
    }
}
