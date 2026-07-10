package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Pure unit test for {@link BatchCorrelationIdListener}, the batch-side counterpart of
 * {@link CorrelationIdFilter} that establishes a per-job-execution correlation ID in the SLF4J
 * {@link MDC}. Net-new observability infrastructure mandated by the Observability rule
 * (AAP&nbsp;&sect;0.7.1); the legacy COBOL/CICS system had no observability tier. Legacy source is
 * referenced read-only at commit SHA {@code 27d6c6f}.
 *
 * <p>Both the REST filter and this listener publish under the <em>same</em> MDC key
 * ({@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY}, value {@code "correlationId"}) so
 * {@code logback-spring.xml} renders the ID identically for online and batch logs and an end-to-end
 * trace can stitch across the REST&nbsp;&rarr;&nbsp;SQS&nbsp;&rarr;&nbsp;batch boundary. This suite
 * pins that contract:</p>
 * <ul>
 *   <li><strong>{@code beforeJob}</strong> resolves the correlation ID (the non-blank
 *       {@code correlationId} job parameter when present, otherwise a random UUID) and publishes it
 *       both to the MDC and to the job's execution context (for restart visibility).</li>
 *   <li><strong>{@code afterJob}</strong> removes the correlation ID from the MDC (in a
 *       {@code finally} block) so it never leaks into the next job on a pooled launching thread.</li>
 * </ul>
 *
 * <p>Job executions are fabricated with Spring Batch's {@link MetaDataInstanceFactory}; no Spring
 * context, database, or network is used, so the suite is fast and compiles warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2), contributing to Gate&nbsp;8 coverage. The MDC is cleared after
 * each test so cases cannot bleed into one another.</p>
 *
 * @see BatchCorrelationIdListener
 * @see CorrelationIdFilter
 */
@DisplayName("BatchCorrelationIdListener — per-job correlation ID in MDC + execution context")
class BatchCorrelationIdListenerTest {

    /** The shared MDC / job-parameter key ({@code "correlationId"}). */
    private static final String KEY = CorrelationIdFilter.CORRELATION_ID_MDC_KEY;

    private final BatchCorrelationIdListener listener = new BatchCorrelationIdListener();

    @BeforeEach
    void clearBefore() {
        MDC.remove(KEY);
    }

    @AfterEach
    void clearAfter() {
        // Defensive: never let a correlation id bleed into another test (mirrors the pooled-thread
        // concern the listener itself guards against).
        MDC.remove(KEY);
    }

    /**
     * Builds a job execution carrying the given correlation-id job parameter.
     *
     * @param correlationId the value for the {@code correlationId} job parameter
     * @return a fabricated job execution with that parameter
     */
    private static JobExecution executionWithCorrelationId(final String correlationId) {
        final JobParameters parameters = new JobParametersBuilder()
                .addString(KEY, correlationId)
                .toJobParameters();
        return MetaDataInstanceFactory.createJobExecution("postTransactionJob", 1L, 1L, parameters);
    }

    // =====================================================================
    // 1) beforeJob — publishes the resolved id to MDC + execution context
    // =====================================================================

    @Nested
    @DisplayName("beforeJob — resolve + publish")
    class BeforeJob {

        @Test
        @DisplayName("uses the supplied correlationId job parameter for MDC and execution context")
        void usesSuppliedJobParameter() {
            final JobExecution execution = executionWithCorrelationId("corr-abc-123");

            listener.beforeJob(execution);

            assertThat(MDC.get(KEY)).isEqualTo("corr-abc-123");
            assertThat(execution.getExecutionContext().getString(KEY)).isEqualTo("corr-abc-123");
        }

        @Test
        @DisplayName("generates a non-blank id when no job parameter is supplied, consistent across MDC + context")
        void generatesIdWhenParameterAbsent() {
            final JobExecution execution = MetaDataInstanceFactory.createJobExecution();

            listener.beforeJob(execution);

            final String mdcValue = MDC.get(KEY);
            assertThat(mdcValue).isNotNull().isNotBlank();
            // The MDC and execution-context values are the same generated id.
            assertThat(execution.getExecutionContext().getString(KEY)).isEqualTo(mdcValue);
        }

        @Test
        @DisplayName("a blank job parameter falls back to a generated id")
        void blankParameterFallsBackToGeneratedId() {
            final JobExecution execution = executionWithCorrelationId("   ");

            listener.beforeJob(execution);

            // Blank is treated as absent (isBlank() guard), so a generated non-blank id is used.
            assertThat(MDC.get(KEY)).isNotBlank();
            assertThat(MDC.get(KEY)).isNotEqualTo("   ");
        }
    }

    // =====================================================================
    // 2) afterJob — clears the MDC key
    // =====================================================================

    @Nested
    @DisplayName("afterJob — clears the MDC key")
    class AfterJob {

        @Test
        @DisplayName("removes the correlationId from the MDC after the job finishes")
        void removesCorrelationIdFromMdc() {
            final JobExecution execution = executionWithCorrelationId("corr-to-be-cleared");

            listener.beforeJob(execution);
            assertThat(MDC.get(KEY)).isEqualTo("corr-to-be-cleared");

            listener.afterJob(execution);

            assertThat(MDC.get(KEY)).isNull();
        }
    }
}
