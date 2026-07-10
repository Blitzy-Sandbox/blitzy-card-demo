package com.carddemo.batch;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import com.carddemo.observability.CorrelationIdFilter;

/**
 * Spring Batch {@link JobExecutionListener} that establishes a per-job-execution
 * <em>correlation ID</em> in the SLF4J {@link MDC}, so that every structured log line emitted while
 * a batch job runs can be tied back to that single execution.
 *
 * <p>This is the batch-side counterpart of {@link CorrelationIdFilter}: the filter sets the
 * correlation ID for inbound REST requests, whereas this listener does the equivalent for Spring
 * Batch job executions. Both publish under the <strong>same</strong> MDC key
 * ({@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY}, value {@code "correlationId"}) so that
 * {@code src/main/resources/logback-spring.xml} — whose console appender renders
 * {@code %X{correlationId}} and whose JSON (logstash) appender includes the full MDC — formats the
 * ID identically for online and batch logs. Sharing the key is what lets an end-to-end trace stitch
 * across the REST &rarr; SQS &rarr; batch boundary: an ID minted by the REST filter can be carried on
 * the SQS FIFO message, handed to the batch launcher as the {@code correlationId} job parameter, and
 * re-published here.</p>
 *
 * <h2>Observability lineage</h2>
 * <p>The listener is net-new infrastructure mandated by the <em>Observability</em> rule
 * (AAP &sect;0.7.1, &sect;0.8.6); the legacy COBOL/CICS system had no observability tier at all.
 * The rationale for the shared correlation-ID contract is documented in
 * {@code docs/decision-log.md} rather than inline, per the Explainability rule.</p>
 *
 * <h2>COBOL lineage (reference-only, source SHA {@code 27d6c6f})</h2>
 * <p>This listener wraps the migrated batch pipeline whose primary stage is the transaction-posting
 * job translated from {@code CBTRN02C.CBL}. That program signals a non-fatal, partial-success outcome
 * by setting {@code RETURN-CODE} to {@code 4} when it writes at least one rejected transaction
 * (COBOL {@code IF WS-REJECT-COUNT > 0 ... MOVE 4 TO RETURN-CODE}), and abends through its
 * {@code 9999-ABEND-PROGRAM} paragraph on unrecoverable file errors. In the migrated system that
 * disposition surfaces as the Spring Batch {@link org.springframework.batch.core.ExitStatus} exit
 * code, which {@link #afterJob(JobExecution)} logs — the modern analog of the mainframe job
 * {@code RETURN-CODE}.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>The MDC is backed by a thread-local map and the listener runs on the job's launching thread,
 * which may be pooled. The correlation ID is therefore always removed in {@link #afterJob(JobExecution)}
 * using a {@code finally} block so the value never leaks into the next job served by the same thread.
 * A targeted {@link MDC#remove(String)} is used (never {@link MDC#clear()}) so that tracing-provided
 * MDC entries such as {@code traceId} / {@code spanId} contributed by Micrometer Tracing are left
 * untouched — mirroring {@link CorrelationIdFilter}.</p>
 */
@Component
public class BatchCorrelationIdListener implements JobExecutionListener {

    /** SLF4J logger for this listener. */
    private static final Logger log = LoggerFactory.getLogger(BatchCorrelationIdListener.class);

    /**
     * Name of the optional job parameter that carries an already-established correlation ID into a
     * job execution (for example, one propagated by {@code ReportJobLauncher} from an SQS FIFO
     * message or the originating REST request).
     *
     * <p>Its value is deliberately bound to {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY}
     * ({@code "correlationId"}) rather than re-typing the literal, so the job-parameter name, the MDC
     * key, and the {@code logback-spring.xml} field stay in lock-step under a single source of
     * truth.</p>
     */
    private static final String CORRELATION_ID_JOB_PARAMETER = CorrelationIdFilter.CORRELATION_ID_MDC_KEY;

    /**
     * Resolves the correlation ID for the starting job, publishes it to the {@link MDC} and to the
     * job's execution context (for restart visibility), and logs the job start at {@code INFO}.
     *
     * <p>The ID is resolved with the following precedence:</p>
     * <ol>
     *   <li>the non-blank {@code correlationId} job parameter, when present (propagated end-to-end
     *       from an upstream REST request or SQS message); otherwise</li>
     *   <li>a freshly generated random {@link UUID}.</li>
     * </ol>
     *
     * @param jobExecution the job execution that is about to start (never {@code null})
     */
    @Override
    public void beforeJob(final JobExecution jobExecution) {
        final String correlationId = resolveCorrelationId(jobExecution);

        // Publish before the job body runs so every downstream (step/reader/processor/writer) log
        // statement on this thread carries the ID.
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId);

        // Persist into the execution context so the ID remains visible on a restart of this instance.
        jobExecution.getExecutionContext().putString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId);

        log.info("Batch job starting jobName={} jobInstanceId={} correlationId={}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getJobInstance().getInstanceId(),
                correlationId);
    }

    /**
     * Logs the completed job's outcome at {@code INFO} — its {@link org.springframework.batch.core.BatchStatus}
     * and {@link org.springframework.batch.core.ExitStatus} exit code (where a {@code RETURN-CODE 4}-style
     * partial-success disposition surfaces for the posting job) — and unconditionally removes the
     * correlation ID from the {@link MDC}.
     *
     * <p>The removal is performed in a {@code finally} block so the thread-local MDC entry is cleared
     * even if logging fails, preventing the ID from bleeding into a subsequent job on a pooled
     * launching thread.</p>
     *
     * @param jobExecution the job execution that has finished (never {@code null})
     */
    @Override
    public void afterJob(final JobExecution jobExecution) {
        try {
            log.info("Batch job finished jobName={} batchStatus={} exitCode={}",
                    jobExecution.getJobInstance().getJobName(),
                    jobExecution.getStatus(),
                    jobExecution.getExitStatus().getExitCode());
        } finally {
            MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Returns the correlation ID for the given execution: the non-blank {@code correlationId} job
     * parameter when supplied, otherwise a newly generated random {@link UUID}.
     *
     * @param jobExecution the starting job execution (never {@code null})
     * @return a non-null, non-blank correlation ID
     */
    private static String resolveCorrelationId(final JobExecution jobExecution) {
        final String fromParameters = jobExecution.getJobParameters().getString(CORRELATION_ID_JOB_PARAMETER);
        if (fromParameters != null && !fromParameters.isBlank()) {
            return fromParameters;
        }
        return UUID.randomUUID().toString();
    }
}
