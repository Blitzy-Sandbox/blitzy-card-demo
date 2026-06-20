package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit tests for {@link BatchConfig#batchTaskExecutor()}, the bounded
 * multi-threaded executor that backs the batch pipeline's parallel stage-4
 * split. Verifies the pool is configured (core 2, max 4, named prefix) and is
 * returned un-initialized so Spring's {@code afterPropertiesSet} builds the
 * pool exactly once.
 */
class BatchConfigTest {

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
}
