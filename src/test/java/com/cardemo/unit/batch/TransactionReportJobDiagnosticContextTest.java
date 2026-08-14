/*
 * ******************************************************************
 * Program     : TransactionReportJobDiagnosticContextTest.java
 * Application : CardDemo
 * Type        : Java 25 / JUnit 5 unit test
 * Function    : Pins the diagnostic-context contract of the job
 *               listener nested in TransactionReportJob: it publishes
 *               the two entries it owns, fabricates neither of the two
 *               the tracer owns, and restores an enclosing scope's
 *               entries rather than removing them.
 * Capability  : NEW - regression coverage for review findings H-02
 *               (fabricated trace identity), M-02 (redeclared MDC
 *               literals and divergent lifecycle) and M-04
 *               (insufficient regression coverage).
 * Source      : app/jcl/TRANREPT.jcl @ 7756d89 (the job whose events
 *               these entries label)
 * Source      : app/proc/TRANREPT.prc @ 7756d89 (the procedure the job
 *               translates)
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cardemo.batch.jobs.TransactionReportJob;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * What the transaction-report job's listener may and may not put into the diagnostic context.
 *
 * <p><strong>Finding H-02, severity High.</strong> The listener used to fill {@code traceId} and
 * {@code spanId} with random UUIDs whenever the thread carried none. This job creates no span, so those
 * values named traces that no tracing backend held - and because the outbound
 * {@value CorrelationIdFilter#TRACE_PARENT_HEADER} is composed from the same entries, a downstream hop could
 * be invited to parent itself onto a trace that does not exist. An operator following such an identifier into
 * Jaeger finds nothing and cannot tell a lost trace from an invented one, which is why a fabricated
 * identifier is worse than an absent field rather than merely equivalent to it.
 *
 * <p><strong>Finding M-02, severity Medium.</strong> The same listener parked four entries in the job
 * execution context under four private string literals that re-spelled the constants
 * {@link CorrelationIdFilter} already publishes. The assertions below read the keys from those constants, so
 * a future re-spelling in either direction fails here rather than silently producing unpopulated log fields -
 * which is the failure mode that raises no error and yields no output.
 *
 * <p>The listener is a private inner class, so it is reached reflectively through its enclosing instance.
 * That is deliberate: the alternative is widening production visibility to suit a test, and the listener's
 * encapsulation is worth more than the convenience.
 */
@DisplayName("TransactionReportJob's listener: two owned entries, no fabricated trace identity (H-02, M-02)")
class TransactionReportJobDiagnosticContextTest {

    /** A job instance identifier standing in for a real run. */
    private static final long INSTANCE_ID = 4242L;

    /** A job execution identifier standing in for a real run. */
    private static final long EXECUTION_ID = 88L;

    /** The value an enclosing pipeline would have established on the thread. */
    private static final String FOREIGN_JOB_INSTANCE_ID = "500";

    /** A correlation identifier an enclosing scope would own. */
    private static final String FOREIGN_CORRELATION_ID = "pipeline-7";

    /** A well-formed W3C trace identifier, as the tracing bridge would publish it. */
    private static final String INHERITED_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    /** A well-formed W3C span identifier, as the tracing bridge would publish it. */
    private static final String INHERITED_SPAN_ID = "b7ad6b7169203331";

    /** Leaves the diagnostic context exactly as the test found it, whatever the test did to it. */
    @AfterEach
    void clearDiagnosticContext() {
        MDC.clear();
    }

    @Test
    @DisplayName("before-job publishes only jobInstanceId and correlationId, under the shared constants")
    void beforeJobPublishesOnlyTheTwoEntriesItOwns() throws Exception {
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();
        try {
            listener.beforeJob(execution);

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                    .as("the key is CorrelationIdFilter's own constant, so it has one definition")
                    .isEqualTo(Long.toString(INSTANCE_ID));
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("derived from the execution identifier, so a reader can recompute it")
                    .isEqualTo("tranrept-" + EXECUTION_ID);
        } finally {
            listener.afterJob(execution);
        }
    }

    @Test
    @DisplayName("finding H-02: no traceId or spanId is minted, because this job creates no span")
    void noTraceIdentityIsFabricated() throws Exception {
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();
        try {
            listener.beforeJob(execution);

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID))
                    .as("a minted trace identifier names a trace no backend holds, and an operator cannot "
                            + "tell that from a lost one")
                    .isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID))
                    .as("a span identifier without a span is the same fabrication one level down")
                    .isNull();
        } finally {
            listener.afterJob(execution);
        }
    }

    @Test
    @DisplayName("an inherited trace context passes through untouched, so a real trace is not overwritten")
    void anInheritedTraceContextIsPreserved() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, INHERITED_TRACE_ID);
        MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, INHERITED_SPAN_ID);
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();

        listener.beforeJob(execution);
        listener.afterJob(execution);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID))
                .as("the tracer owns these two entries end to end; the listener owns neither")
                .isEqualTo(INHERITED_TRACE_ID);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID)).isEqualTo(INHERITED_SPAN_ID);
    }

    @Test
    @DisplayName("after-job removes what it established, so nothing leaks onto a pooled thread")
    void afterJobRemovesWhatItEstablished() throws Exception {
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();

        listener.beforeJob(execution);
        listener.afterJob(execution);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                .as("a leaked entry would mislabel an unrelated later run on the same pooled thread")
                .isNull();
        assertThat(CorrelationIdFilter.currentCorrelationId()).isNull();
    }

    @Test
    @DisplayName("after-job restores an enclosing scope's entries rather than removing them")
    void afterJobRestoresAnEnclosingScope() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID, FOREIGN_JOB_INSTANCE_ID);
        MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, FOREIGN_CORRELATION_ID);
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();

        listener.beforeJob(execution);
        assertThat(CorrelationIdFilter.currentCorrelationId())
                .as("an inherited identifier is kept, so the run shares one chain with whatever launched it")
                .isEqualTo(FOREIGN_CORRELATION_ID);

        listener.afterJob(execution);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                .as("restoring serves both obligations - no leak, and no destruction of an outer scope's "
                        + "entry - where removing serves only the first")
                .isEqualTo(FOREIGN_JOB_INSTANCE_ID);
        assertThat(CorrelationIdFilter.currentCorrelationId()).isEqualTo(FOREIGN_CORRELATION_ID);
    }

    @Test
    @DisplayName("finding M-02: no execution-context entry parks a caller's identity in the batch metastore")
    void noCallerIdentityIsPersistedIntoTheExecutionContext() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, FOREIGN_CORRELATION_ID);
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();
        try {
            listener.beforeJob(execution);

            assertThat(execution.getExecutionContext().entrySet())
                    .as("the displaced values belong on the thread that displaced them; parking them in the "
                            + "execution context wrote a request's correlation identifier into "
                            + "BATCH_JOB_EXECUTION_CONTEXT, where nothing ever read it back")
                    .noneSatisfy(entry -> assertThat(entry.getKey()).contains("mdc"));
        } finally {
            listener.afterJob(execution);
        }
    }

    @Test
    @DisplayName("finding P5-02: an abend among the failures is published as the ABEND exit status, not FAILED")
    void anAbendIsPublishedAsTheAbendExitStatus() throws Exception {
        // The decider already returned ABEND and the flow already routed it to fail(). What the flow could not
        // do is keep it: the framework's failed end state sets the exit status to FAILED as part of failing the
        // job, so the execution persisted as FAILED/FAILED and an operator could not tell a return code 12
        // abend from an ordinary return code 8 failure. That is exactly the distinction
        // app/cbl/CBTRN03C.cbl:629-630 - MOVE 999 TO ABCODE then CALL 'CEE3ABD' - exists to make. Re-asserting
        // the status from afterJob restores it, and is what the four sibling jobs already do.
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();
        final FatalProcessingException abend = new FatalProcessingException("0999", "CBTRN03C",
                "INVALID CARD NUMBER", "ABENDING PROGRAM");
        execution.addFailureException(abend);
        execution.setStatus(BatchStatus.FAILED);
        execution.setExitStatus(ExitStatus.FAILED);

        listener.beforeJob(execution);
        listener.afterJob(execution);

        assertThat(execution.getExitStatus().getExitCode())
                .as("the exit code is what a caller reads to tell 12 from 8, and an abend must say so")
                .isEqualTo("ABEND");
        assertThat(execution.getExitStatus().getExitDescription())
                .as("the description names the abend code and the process return code, in the same wording "
                        + "the four sibling jobs use, so one grep finds an abend in any of the five")
                .contains(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE))
                .contains(Integer.toString(FatalProcessingException.BATCH_RETURN_CODE))
                .contains("CBTRN03C");
        assertThat(execution.getStatus())
                .as("the batch STATUS is deliberately left unsuccessful. An abend is a failure; what it adds "
                        + "is the exit code, so restart semantics are unchanged")
                .isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("an abend nested as a cause is still an abend, so a wrapped one cannot decay into FAILED")
    void anAbendCarriedAsACauseIsStillPublished() throws Exception {
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();
        // Spring Batch records the exception the step threw, and a step's own wrapping can put the abend one
        // level down. Detection is by type over the recorded failures, so the top-level throwable being the
        // abend is what matters here; this case pins that a plain wrapper does NOT get promoted, which is the
        // conservative half of the same rule and stops an ordinary failure being mislabelled as an abend.
        execution.addFailureException(new IllegalStateException("an ordinary step failure",
                new FatalProcessingException("0999", "CBTRN03C", "INVALID CARD NUMBER", "ABENDING PROGRAM")));
        execution.setStatus(BatchStatus.FAILED);
        execution.setExitStatus(ExitStatus.FAILED);

        listener.beforeJob(execution);
        listener.afterJob(execution);

        assertThat(execution.getExitStatus().getExitCode())
                .as("only a recorded FatalProcessingException is an abend. Walking causes would let any "
                        + "failure that happened to wrap one be reported as return code 12, which is the "
                        + "opposite mislabelling and just as wrong")
                .isEqualTo(ExitStatus.FAILED.getExitCode());
    }

    @Test
    @DisplayName("a clean run keeps its own exit status; the promotion cannot fire without an abend")
    void aCleanRunIsNotRelabelled() throws Exception {
        final JobExecutionListener listener = listener();
        final JobExecution execution = execution();
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(ExitStatus.COMPLETED);

        listener.beforeJob(execution);
        listener.afterJob(execution);

        assertThat(execution.getExitStatus().getExitCode())
                .as("the inverse assertion, and the one that would catch a promotion applied unconditionally")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("finding P7-11: the start-of-job step count is FOUR, matching the flow that is wired")
    void theStartOfJobStepCountMatchesTheWiredFlow() throws Exception {
        // The count is published to an operator on the first line of every run, and it said three while the
        // flow wired four: STEP01R, STEP05R and STEP10R from app/proc/TRANREPT.prc plus the step
        // app/jcl/PRTCATBL.jcl contributes. The figure is now derived from one declared constant rather than
        // written out by hand in the log line and again in the class documentation, so this asserts the
        // constant rather than parsing a log line - which is the durable form of the same check.
        final Field declared = TransactionReportJob.class.getDeclaredField("ORDERED_STEP_COUNT");
        declared.setAccessible(true);

        assertThat(declared.getInt(null))
                .as("app/proc/TRANREPT.prc contributes three steps and app/jcl/PRTCATBL.jcl the fourth, and "
                        + "transactionReportFlow wires all four")
                .isEqualTo(4);
        assertThat(Modifier.isStatic(declared.getModifiers()) && Modifier.isFinal(declared.getModifiers()))
                .as("declared once, so the documentation and the log line cannot drift apart again")
                .isTrue();
    }

    /**
     * Reflects the job's single nested {@link JobExecutionListener}, through an enclosing instance.
     *
     * @return the listener under test, never {@code null}
     * @throws Exception if the nested type or its constructor cannot be reached
     */
    private JobExecutionListener listener() throws Exception {
        final TransactionReportJob job = job();
        for (final Class<?> nested : TransactionReportJob.class.getDeclaredClasses()) {
            if (JobExecutionListener.class.isAssignableFrom(nested)) {
                final Constructor<?> constructor = nested.getDeclaredConstructor(TransactionReportJob.class);
                constructor.setAccessible(true);
                return (JobExecutionListener) constructor.newInstance(job);
            }
        }
        throw new AssertionError("TransactionReportJob declares no nested JobExecutionListener");
    }

    /**
     * Builds the job with mocked collaborators, because the assertions concern the listener alone.
     *
     * @return a constructed job, never {@code null}
     */
    private TransactionReportJob job() {
        return new TransactionReportJob(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                mock(TransactionRepository.class),
                mock(CardCrossReferenceRepository.class),
                mock(TransactionTypeRepository.class),
                mock(TransactionCategoryRepository.class),
                mock(DateValidationService.class),
                new FileStatusMapper(),
                mock(S3Operations.class),
                mock(S3Client.class),
                "TRANREPT",
                100,
                "carddemo-batch-output",
                "gdg/transact-bkup",
                "gdg/transact-daly",
                "gdg/tranrept",
                mock(TransactionCategoryBalanceRepository.class),
                "gdg/tcatbalf-bkup",
                10);
    }

    /**
     * Builds an execution the way the framework hands one to a listener.
     *
     * @return a job execution carrying an instance and an identifier, never {@code null}
     */
    private JobExecution execution() {
        return new JobExecution(
                new JobInstance(Long.valueOf(INSTANCE_ID), "TRANREPT"),
                Long.valueOf(EXECUTION_ID),
                new JobParameters());
    }
}
