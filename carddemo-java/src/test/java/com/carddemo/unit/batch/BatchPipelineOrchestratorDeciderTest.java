package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.test.MetaDataInstanceFactory;

import com.carddemo.batch.jobs.BatchPipelineOrchestrator;

/**
 * Pure-JVM unit tests for the POSTTRAN condition-code {@link JobExecutionDecider} assembled by
 * {@link BatchPipelineOrchestrator} &mdash; the Java realisation of the JCL {@code COND} logic that
 * gates the five-stage batch pipeline immediately after stage 1, daily transaction posting
 * ({@code app/jcl/POSTTRAN.jcl} &rarr; {@code EXEC PGM=CBTRN02C}; COBOL {@code app/cbl/CBTRN02C.cbl};
 * source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL is copied, lineage is preserved
 * by the commit SHA).
 *
 * <p>The decider under test is {@link BatchPipelineOrchestrator.PostingConditionDecider}. It inspects
 * the just-finished posting step's {@link ExitStatus} exit code and routes the master flow:</p>
 * <ul>
 *   <li>{@link ExitStatus#COMPLETED} (the COBOL {@code RETURN-CODE=0} clean run) &rarr;
 *       {@link BatchPipelineOrchestrator#DECISION_CONTINUE};</li>
 *   <li>{@code COMPLETED_WITH_REJECTS} (the COBOL {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}
 *       warning) &rarr; {@link BatchPipelineOrchestrator#DECISION_CONTINUE} &mdash; a partial-reject
 *       return code is a warning, so condition-permitting downstream stages still run;</li>
 *   <li>any other status, notably {@code FAILED} (a hard posting abend) &rarr;
 *       {@link BatchPipelineOrchestrator#DECISION_STOP}, halting the pipeline.</li>
 * </ul>
 *
 * <p>{@link BatchPipelineOrchestrator.PostingConditionDecider} is a stateless, public, dependency-free
 * decider, so these tests exercise it directly: they construct it, build {@link StepExecution}
 * fixtures with {@link MetaDataInstanceFactory}, and invoke only {@code decide(...)}. No Spring
 * context, {@code JobLauncher}, Testcontainers, or LocalStack is involved &mdash; this is the
 * unit-testable core of the pipeline's condition-code parity (AAP &sect;0.8.5).</p>
 */
@DisplayName("BatchPipelineOrchestrator.PostingConditionDecider - JCL COND to Spring Batch decision")
class BatchPipelineOrchestratorDeciderTest {

    /** Decider outcome name that lets the pipeline proceed past POSTTRAN (RC=0 or the RC=4 warning). */
    private static final String CONTINUE_TOKEN = BatchPipelineOrchestrator.DECISION_CONTINUE;

    /** Decider outcome name that halts the pipeline after a failed POSTTRAN. */
    private static final String STOP_TOKEN = BatchPipelineOrchestrator.DECISION_STOP;

    /**
     * Posting-step exit code emitted when at least one transaction was rejected (the COBOL
     * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} warning). The value mirrors the production
     * {@code DailyTransactionPostingJob.COMPLETED_WITH_REJECTS} constant; it is inlined as a literal so
     * this test imports only its declared dependency, {@link BatchPipelineOrchestrator}.
     */
    private static final String COMPLETED_WITH_REJECTS_EXIT_CODE = "COMPLETED_WITH_REJECTS";

    /** The production decider under test; stateless and collaborator-free, so instantiated directly. */
    private final JobExecutionDecider decider = new BatchPipelineOrchestrator.PostingConditionDecider();

    @Test
    @DisplayName("posting COMPLETED (RC=0) yields CONTINUE so downstream stages run")
    void completed_continuesPipeline() {
        StepExecution posting = stepWithExit(ExitStatus.COMPLETED);

        FlowExecutionStatus result = decider.decide(posting.getJobExecution(), posting);

        assertThat(result.getName()).isEqualTo(CONTINUE_TOKEN);
    }

    @Test
    @DisplayName("posting COMPLETED_WITH_REJECTS (RC=4 warning) yields the same CONTINUE as COMPLETED")
    void completedWithRejects_continuesPipeline() {
        StepExecution posting = stepWithExit(new ExitStatus(COMPLETED_WITH_REJECTS_EXIT_CODE));

        FlowExecutionStatus result = decider.decide(posting.getJobExecution(), posting);

        // A partial-reject outcome is treated identically to a clean COMPLETED (a warning, not a
        // failure): it resolves to the very same continue token, so condition-permitting downstream
        // stages still run.
        assertThat(result.getName()).isEqualTo(CONTINUE_TOKEN);
    }

    @Test
    @DisplayName("posting FAILED yields STOP, which is distinct from CONTINUE, halting the pipeline")
    void failed_stopsPipeline() {
        StepExecution posting = stepWithExit(ExitStatus.FAILED);

        FlowExecutionStatus result = decider.decide(posting.getJobExecution(), posting);

        assertThat(result.getName()).isEqualTo(STOP_TOKEN);
        assertThat(result.getName()).isNotEqualTo(CONTINUE_TOKEN);
    }

    /**
     * Builds a posting {@link StepExecution} carrying the supplied exit status. The decider keys its
     * decision off the immediately-preceding step's {@link ExitStatus} exit code, so only the exit
     * status needs to be set; the attached {@code JobExecution} (from
     * {@link StepExecution#getJobExecution()}) is forwarded to {@code decide(...)} as the master
     * execution argument exactly as Spring Batch would supply it at runtime.
     *
     * @param exit the posting-step exit status to evaluate
     * @return a step execution bearing {@code exit}
     */
    private static StepExecution stepWithExit(ExitStatus exit) {
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setExitStatus(exit);
        return stepExecution;
    }
}
