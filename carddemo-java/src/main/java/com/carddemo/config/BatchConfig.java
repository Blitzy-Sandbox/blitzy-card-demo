package com.carddemo.config;

import com.carddemo.observability.BatchContextPropagatingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring Batch infrastructure wiring for the CardDemo batch pipeline.
 *
 * <p>This class contributes a single piece of infrastructure: the bounded,
 * multi-threaded {@link TaskExecutor} (bean {@code "batchTaskExecutor"}) that
 * the {@code BatchPipelineOrchestrator} uses to fan the pipeline's parallel
 * stage&nbsp;4 (statement generation alongside transaction reporting) across
 * two concurrent Spring Batch flows. Everything else the batch jobs require
 * &mdash; the {@code JobRepository}, {@code JobLauncher}, and
 * {@code PlatformTransactionManager} &mdash; is supplied by Spring Boot 3.x
 * batch <em>auto-configuration</em> and is intentionally <strong>not</strong>
 * redefined here.</p>
 *
 * <p><strong>No {@code @EnableBatchProcessing}.</strong> Declaring that
 * annotation anywhere in the application would switch off Boot's batch
 * auto-configuration and force every infrastructure bean to be defined by hand;
 * this configuration deliberately relies on the auto-configured beans so the
 * {@code batch/jobs/*} components can inject them by type. The job and step
 * definitions themselves live in {@code com.carddemo.batch.jobs}; this class is
 * wiring only and declares no {@code Job} or {@code Step}.</p>
 *
 * <p>Source lineage (reference only, COBOL/JCL not copied): the batch pipeline
 * these beans support is migrated from the AWS CardDemo JCL job streams
 * {@code POSTTRAN.jcl} (daily transaction posting, {@code EXEC PGM=CBTRN02C}),
 * {@code INTCALC.jcl} (interest calculation, {@code EXEC PGM=CBACT04C}), and
 * {@code COMBTRAN.jcl} (transaction combine, {@code SORT} + IDCAMS
 * {@code REPRO}) at commit {@code 27d6c6f}.</p>
 */
@Configuration(proxyBeanMethods = false)
public final class BatchConfig {

    /**
     * Creates the batch configuration. The class is stateless; Spring
     * instantiates it once during context startup to register the bean below.
     */
    public BatchConfig() {
        // No initialization required; the executor is built by the bean method.
    }

    /**
     * Defines the dedicated, bounded thread pool that backs the batch
     * pipeline's parallel stage-4 split.
     *
     * <p>The {@code BatchPipelineOrchestrator} injects this bean via
     * {@code @Qualifier("batchTaskExecutor")} and hands it to
     * {@code FlowBuilder.split(TaskExecutor)} so the statement-generation flow
     * and the transaction-report flow execute concurrently. A real
     * multi-threaded pool is required: a synchronous executor would run the
     * "parallel" flows one after another and defeat the split entirely.</p>
     *
     * <p>The pool is sized for exactly that workload &mdash; a core of two
     * threads (one per concurrent stage-4 flow), a ceiling of four for transient
     * bursts, and a bounded queue of sixteen &mdash; keeping resource use
     * predictable rather than unbounded. Graceful shutdown is enabled so an
     * in-flight flow is allowed to finish (up to thirty seconds) before the
     * pool is torn down.</p>
     *
     * <p>The executor is returned un-initialized on purpose:
     * {@link ThreadPoolTaskExecutor} implements {@code InitializingBean}, so
     * Spring invokes {@code afterPropertiesSet()} &mdash; which builds the
     * underlying pool exactly once &mdash; for this {@code @Bean}. Calling
     * {@code initialize()} here as well would create and leak a second pool.</p>
     *
     * <p>Note: contributing an {@code Executor}/{@code TaskExecutor} bean causes
     * Spring Boot's default {@code applicationTaskExecutor} to back off (it is
     * {@code @ConditionalOnMissingBean(Executor.class)}). That is acceptable
     * here because CardDemo uses neither {@code @Async} nor asynchronous MVC;
     * this executor is dedicated to the batch pipeline split.</p>
     *
     * <p>The pool is fitted with a {@link BatchContextPropagatingTaskDecorator} so each branch the
     * split submits inherits the launching thread's SLF4J {@code MDC} (notably the
     * {@code correlationId}) and its Micrometer observation/tracing context. Without the decorator
     * the {@code carddemo-batch-*} worker threads would log without a correlation id and begin
     * unrelated traces, breaking the Observability rule (AAP &sect;0.7.1) for the parallel stage.</p>
     *
     * @return the bounded, multi-threaded executor for the stage-4 flow split
     */
    @Bean("batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("carddemo-batch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Propagate the launching thread's MDC (correlationId) and observation/tracing context onto
        // the carddemo-batch-* worker threads so the parallel stage-4 split keeps unbroken log
        // correlation and child spans (Observability rule, AAP §0.7.1). Without this decorator the
        // split branches would log without a correlationId and start unrelated traces.
        executor.setTaskDecorator(new BatchContextPropagatingTaskDecorator());
        return executor;
    }
}
