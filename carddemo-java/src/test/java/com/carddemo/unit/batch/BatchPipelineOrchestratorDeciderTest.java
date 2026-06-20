package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.jobs.BatchPipelineOrchestrator;
import com.carddemo.batch.jobs.DailyTransactionPostingJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.core.task.TaskExecutor;

/**
 * Pure-JVM unit tests for the POSTTRAN condition-code {@link JobExecutionDecider} exposed by
 * {@link BatchPipelineOrchestrator#postingConditionDecider()}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL/JCL not copied; source commit {@code 27d6c6f}): the
 * mainframe JCL job {@code app/jcl/POSTTRAN.jcl} (single step {@code EXEC PGM=CBTRN02C}) and the
 * COBOL daily-transaction posting program {@code app/cbl/CBTRN02C.cbl}. After the per-record loop,
 * {@code CBTRN02C} raises {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} &mdash; a partial
 * failure is a warning ({@code RC=4}), not a hard failure. The JCL {@code COND} condition-code
 * decision that follows POSTTRAN is re-hosted as the Spring Batch decider under test (AAP
 * &sect;0.8.5): a posting outcome of {@link ExitStatus#COMPLETED} or the {@code RC=4} warning
 * ({@link DailyTransactionPostingJob#COMPLETED_WITH_REJECTS}) lets the pipeline continue, while a
 * genuine {@code FAILED} posting stops it.</p>
 *
 * <p>The decider is exercised in isolation: the {@code @Configuration} is instantiated directly with
 * Mockito mocks for its collaborators (which the constructor only null-checks), the decider bean is
 * obtained from its public bean method, and only {@link JobExecutionDecider#decide} is invoked. No
 * Spring context, Spring Batch {@code JobLauncher}, Testcontainers, or LocalStack is started.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchPipelineOrchestrator - POSTTRAN condition-code decider (POSTTRAN COND / CBTRN02C RC)")
class BatchPipelineOrchestratorDeciderTest {

    /**
     * The {@link FlowExecutionStatus} name the production decider returns to route the pipeline onward
     * (a successful POSTTRAN, clean or with rejects). Mirrors {@code BatchPipelineOrchestrator}'s
     * package-private {@code DECISION_CONTINUE}; redeclared here because that constant is not visible
     * from this test package.
     */
    private static final String CONTINUE_TOKEN = "CONTINUE";

    /**
     * The {@link FlowExecutionStatus} name the production decider returns to stop the pipeline (a failed
     * POSTTRAN). Mirrors {@code BatchPipelineOrchestrator}'s package-private {@code DECISION_STOP};
     * redeclared here because that constant is not visible from this test package.
     */
    private static final String STOP_TOKEN = "STOP";

    /**
     * Stand-in for every {@link Job} the orchestrator constructor requires (the five child jobs plus
     * the lazy master self-reference). The constructor only null-checks and stores each, and the
     * decider bean does not read any of them, so a single shared non-null mock is sufficient.
     */
    @Mock
    private Job childJob;

    /** Auto-configured job repository required by the constructor; not exercised by the decider. */
    @Mock
    private JobRepository jobRepository;

    /** Auto-configured job launcher required by the constructor; not exercised by the decider. */
    @Mock
    private JobLauncher jobLauncher;

    /** Bounded split executor required by the constructor; not exercised by the decider. */
    @Mock
    private TaskExecutor batchTaskExecutor;

    /** The decider under test, obtained from the orchestrator's public bean method before each test. */
    private JobExecutionDecider decider;

    /**
     * Builds the orchestrator configuration with all nine constructor collaborators mocked and obtains
     * the POSTTRAN condition-code decider from its public bean method. The decider is real production
     * code; the mocks merely satisfy the constructor's non-null requirements.
     */
    @BeforeEach
    void setUp() {
        BatchPipelineOrchestrator orchestrator = new BatchPipelineOrchestrator(
                childJob, childJob, childJob, childJob, childJob,
                jobRepository, jobLauncher, batchTaskExecutor, childJob);
        decider = orchestrator.postingConditionDecider();
    }

    /**
     * Builds a posting {@link StepExecution} carrying the given exit status, aligning the
     * {@link BatchStatus} with it ({@link BatchStatus#FAILED} for a failing exit code, otherwise
     * {@link BatchStatus#COMPLETED}) so the decider reads a consistent step outcome. The execution is
     * created by {@link MetaDataInstanceFactory}, which attaches a non-null owning {@code JobExecution}.
     *
     * @param exit the exit status to place on the posting step
     * @return a step execution whose status and exit status reflect {@code exit}
     */
    private static StepExecution stepWithExit(ExitStatus exit) {
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setExitStatus(exit);
        stepExecution.setStatus(
                exit.getExitCode().startsWith("FAIL") ? BatchStatus.FAILED : BatchStatus.COMPLETED);
        return stepExecution;
    }

    @Test
    @DisplayName("COMPLETED posting continues the pipeline")
    void completed_continuesPipeline() {
        StepExecution stepExecution = stepWithExit(ExitStatus.COMPLETED);

        FlowExecutionStatus result = decider.decide(stepExecution.getJobExecution(), stepExecution);

        assertThat(result.getName()).isEqualTo(CONTINUE_TOKEN);
    }

    @Test
    @DisplayName("COMPLETED_WITH_REJECTS (COBOL RC=4) is a warning that still continues the pipeline")
    void completedWithRejects_continuesPipeline() {
        StepExecution cleanStep = stepWithExit(ExitStatus.COMPLETED);
        StepExecution rejectsStep =
                stepWithExit(new ExitStatus(DailyTransactionPostingJob.COMPLETED_WITH_REJECTS));

        FlowExecutionStatus cleanResult = decider.decide(cleanStep.getJobExecution(), cleanStep);
        FlowExecutionStatus rejectsResult = decider.decide(rejectsStep.getJobExecution(), rejectsStep);

        assertThat(rejectsResult.getName()).isEqualTo(CONTINUE_TOKEN);
        // Parity: a partial-reject outcome (RC=4) is routed identically to a clean COMPLETED.
        assertThat(rejectsResult.getName()).isEqualTo(cleanResult.getName());
    }

    @Test
    @DisplayName("FAILED posting stops the pipeline")
    void failed_stopsPipeline() {
        StepExecution stepExecution = stepWithExit(ExitStatus.FAILED);

        FlowExecutionStatus result = decider.decide(stepExecution.getJobExecution(), stepExecution);

        assertThat(result.getName()).isEqualTo(STOP_TOKEN);
        assertThat(result.getName()).isNotEqualTo(CONTINUE_TOKEN);
    }
}
