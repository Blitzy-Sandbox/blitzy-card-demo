package com.carddemo.config;

import com.carddemo.observability.ContextPropagatingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring Batch infrastructure configuration for the CardDemo migration: it supplies the bounded,
 * multi-threaded {@link TaskExecutor} that drives the batch pipeline's parallel stage-4 split
 * (statement generation running alongside transaction reporting). JCL lineage (REFERENCE ONLY,
 * never copied): POSTTRAN.jcl (CBTRN02C daily posting), INTCALC.jcl (CBACT04C interest
 * calculation), and COMBTRAN.jcl (DFSORT + IDCAMS REPRO combine) from source commit 27d6c6f.
 * This class deliberately relies on Spring Boot 3.x batch auto-configuration for the JobRepository,
 * JobLauncher, and PlatformTransactionManager; it adds no {@code @EnableBatchProcessing} (which
 * would disable that auto-configuration) and provides only the parallel-split TaskExecutor.
 * Decision rationale is recorded in DECISION_LOG.md.
 */
@Configuration(proxyBeanMethods = false)
public class BatchConfig {

    /**
     * Bounded thread pool that powers the batch pipeline's parallel stage-4 flow split (CREASTMT
     * alongside TRANREPT). {@code BatchPipelineOrchestrator} injects this bean via
     * {@code @Qualifier("batchTaskExecutor")} and hands it to {@code FlowBuilder.split(...)} so the
     * two stage-4 flows run concurrently; a synchronous executor would serialize the split and
     * defeat the parallelism, so a real {@link ThreadPoolTaskExecutor} is required rather than a
     * {@code SyncTaskExecutor}.
     *
     * <p>The pool is intentionally small (core 2 / max 4 / queue 16): exactly two concurrent flows
     * are expected, and a bounded pool keeps resource usage predictable. {@code initialize()} is
     * deliberately not called here because {@link ThreadPoolTaskExecutor} implements
     * {@code InitializingBean}; Spring invokes {@code afterPropertiesSet()} (which calls
     * {@code initialize()}) exactly once for {@code @Bean}-returned instances, so a manual call
     * would double-initialize and leak a thread pool. Graceful shutdown is enabled so in-flight
     * stage-4 work drains before the context closes.
     *
     * <p>Defining an {@link java.util.concurrent.Executor}/{@link TaskExecutor} bean causes Spring
     * Boot's default {@code applicationTaskExecutor} to back off
     * ({@code @ConditionalOnMissingBean(Executor.class)}). This is acceptable because CardDemo uses
     * no {@code @Async} or async-MVC; this executor is dedicated to the batch pipeline split.
     *
     * <p>A {@link ContextPropagatingTaskDecorator} is attached so the parallel stage-4 worker
     * threads inherit the launching thread's SLF4J {@code MDC} (including the {@code correlationId})
     * and Micrometer tracing context; without it the CREASTMT/TRANREPT split logs and spans would
     * lose the pipeline's correlation context across the thread-pool boundary (Observability rule,
     * AAP &sect;0.7.1). Rationale is recorded in {@code DECISION_LOG.md}.
     *
     * @return a graceful-shutdown-aware, bounded {@link ThreadPoolTaskExecutor} that propagates the
     *         observability context to its workers, registered under the bean name
     *         {@code "batchTaskExecutor"}
     */
    @Bean("batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("carddemo-batch-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
