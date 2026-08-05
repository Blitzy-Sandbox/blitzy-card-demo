/*
 * ******************************************************************
 * Program     : BatchPipelineOrchestratorTest.java
 * Application : CardDemo
 * Type        : Unit test - Spring Batch flow composition
 * Function    : The named test obligation for the pipeline: stage
 *               order, the parallel split, all four return-code
 *               outcomes, the generation handoff and the diagnostic
 *               context across threads.
 * Source      : app/jcl/POSTTRAN.jcl + app/jcl/INTCALC.jcl +
 *               app/jcl/COMBTRAN.jcl + app/jcl/CREASTMT.JCL +
 *               app/proc/TRANREPT.prc + app/cbl/CBTRN02C.cbl
 *               @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowHolder;
import org.springframework.batch.core.job.flow.State;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

import com.cardemo.batch.jobs.BatchPipelineOrchestrator;
import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.batch.jobs.InterestCalculationJob;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;

/**
 * The named test obligation of {@code BatchPipelineOrchestrator}, discharged without a database and without a
 * container.
 *
 * <p>Rule 1 clause B requires tests for core logic, and the orchestrator's core logic is a topology plus a
 * gating rule. Both are asserted here by building the real beans over an in-memory batch harness and running
 * the real job: the sibling jobs are replaced by stubs whose outcome each test sets, so every return code and
 * every branch outcome is reachable deterministically.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>The stage order {@code POSTTRAN -> INTCALC -> COMBTRAN} and then <b>both</b> of
 *       {@code CREASTMT} and {@code TRANREPT}, with the last two proved to overlap by a barrier they can only
 *       clear if they run at the same time.</li>
 *   <li>All four outcomes of {@code app/cbl/CBTRN02C.cbl:L229-L231}: 0 proceeds, <b>4 proceeds</b>, 8 halts
 *       the dependent chain, and 12 reports an abend carrying the code from
 *       {@code app/cbl/CBTRN02C.cbl:L710}.</li>
 *   <li>The generation handoff between {@code app/jcl/INTCALC.jcl:L37-L41} and
 *       {@code app/jcl/COMBTRAN.jcl:L25-L26}, in all four of its recorded states.</li>
 *   <li>The diagnostic context on the two branch threads, and its release afterwards.</li>
 *   <li>The two ten-character date shapes, and that swapping them is refused.</li>
 * </ul>
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp test -Dtest=BatchPipelineOrchestratorTest}, or as part of the unit tier with
 * {@code ./mvnw -B -ntp test}. No profile, no database, no queue and no object store is required.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The harness supplies its own job repository, launcher and transaction manager, so no property is read.
 * The pipeline name is fixed at {@value #PIPELINE_NAME} so a failure message names the harness rather than a
 * deployment.
 *
 * <h2>Common failure modes</h2>
 *
 * <ul>
 *   <li>A timeout on the barrier means the split stopped running its branches in parallel.</li>
 *   <li>A stage count other than the one expected means a transition was added, removed or misrouted.</li>
 *   <li>An exit code of {@code FAILED} where {@code ABEND} was expected means the abend relabelling in the
 *       pipeline listener stopped running.</li>
 * </ul>
 */
@DisplayName("BatchPipelineOrchestrator - five stages, one split and four return codes")
final class BatchPipelineOrchestratorTest {

    /** The harness pipeline name, distinct from any configured value. */
    private static final String PIPELINE_NAME = "TEST-PIPELINE";

    /** A valid interest date: ten digits, no separator, two trailing zeros. */
    private static final String VALID_PARM_DATE = "2022071800";

    /** A valid report start date in {@code yyyy-MM-dd}. */
    private static final String VALID_START_DATE = "2022-01-01";

    /** A valid report end date in {@code yyyy-MM-dd}. */
    private static final String VALID_END_DATE = "2022-07-06";

    /** How long a branch waits for its sibling before the parallelism assertion fails. */
    private static final int BARRIER_TIMEOUT_SECONDS = 20;

    /** Diagnostic key carrying the job instance identifier. */
    private static final String MDC_KEY_JOB_INSTANCE_ID = "jobInstanceId";

    /** Diagnostic key carrying the correlation identifier. */
    private static final String MDC_KEY_CORRELATION_ID = "correlationId";

    /** Diagnostic key carrying the trace identifier. */
    private static final String MDC_KEY_TRACE_ID = "traceId";

    /** Diagnostic key carrying the span identifier. */
    private static final String MDC_KEY_SPAN_ID = "spanId";

    /** Thread-name prefix the split executor uses. */
    private static final String SPLIT_THREAD_NAME_PREFIX = "carddemo-pipeline-split-";

    /** A representative object key stage 2 would create for {@code SYSTRAN(+1)}. */
    private static final String SYSTRAN_KEY_ONE = "gdg/systran/generation=0000000000000000001/systran.dat";

    /** A later object key, lexicographically greater than {@link #SYSTRAN_KEY_ONE}. */
    private static final String SYSTRAN_KEY_TWO = "gdg/systran/generation=0000000000000000002/systran.dat";

    /** The step execution-context key the combined-transaction reader records its resolved key under. */
    private static final String READER_SYSTRAN_OBJECT_KEY_ENTRY = "carddemo.gdg.systran.objectKey";

    /*
     * The pipeline's execution-context keys and handoff markers, as literals.
     *
     * The orchestrator declares these package-private, and this test deliberately sits in the unit tier
     * alongside its siblings rather than inside the production package, so the literals are repeated here.
     * That is the stronger assertion: these keys are the contract an operator and an integration test read a
     * finished run through, so a rename must break a test rather than pass silently.
     */

    /** Aggregate return code across every stage. */
    private static final String PIPELINE_RETURN_CODE_ENTRY = "carddemo.pipeline.returnCode";

    /** The aggregate rendered as one of the four documented exit codes. */
    private static final String PIPELINE_OUTCOME_ENTRY = "carddemo.pipeline.outcome";

    /** How many {@code SYSTRAN} generations stage 2 created. */
    private static final String PIPELINE_SYSTRAN_KEY_COUNT_ENTRY =
            "carddemo.pipeline.systran.generation.keys.count";

    /** The single generation stage 3 is pinned to, or the absent marker. */
    private static final String PIPELINE_SYSTRAN_GENERATION_ENTRY = "carddemo.pipeline.systran.generation";

    /** The outcome of comparing the pinned generation against the one stage 3 read. */
    private static final String PIPELINE_SYSTRAN_HANDOFF_ENTRY = "carddemo.pipeline.systran.handoff";

    /** Stage 3 read exactly the generation stage 2 created. */
    private static final String HANDOFF_VERIFIED = "VERIFIED";

    /** Stage 2 created no generation, which is a legitimate outcome. */
    private static final String HANDOFF_ABSENT = "ABSENT";

    /** Stage 3 recorded no resolved key, so there was nothing to compare. */
    private static final String HANDOFF_NOT_APPLICABLE = "NOT APPLICABLE";

    /** Stage 3 read a different generation from the one stage 2 created. */
    private static final String HANDOFF_MISMATCH = "MISMATCH";

    /** Every stage invocation, in the order the stages were launched. Reset before each test. */
    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

    /** Stage 1's stub, {@code app/jcl/POSTTRAN.jcl:L23}. */
    private StubJob postTran;

    /** Stage 2's stub, {@code app/jcl/INTCALC.jcl:L22}. */
    private StubJob intCalc;

    /** Stage 3's stub, {@code app/jcl/COMBTRAN.jcl:L22} and {@code :L41}. */
    private StubJob combTran;

    /** Stage 4's statement-branch stub, {@code app/jcl/CREASTMT.JCL}. */
    private StubJob creaStmt;

    /** Stage 4's report-branch stub, {@code app/proc/TRANREPT.prc}. */
    private StubJob tranRept;

    /** The transaction manager the launcher steps are built on, recording what it was asked for. */
    private RecordingTransactionManager transactionManager;

    /** The class under test. */
    private BatchPipelineOrchestrator orchestrator;

    /** The assembled pipeline job. */
    private Job pipelineJob;

    /** The assembled pipeline flow, kept so the topology can be inspected directly. */
    private Flow pipelineFlow;

    /** Builds a fresh harness, so no test can observe another's stage outcomes. */
    @BeforeEach
    void buildHarness() {
        MDC.clear();
        invocations.clear();

        postTran = new StubJob("POSTTRAN", invocations);
        intCalc = new StubJob("INTCALC", invocations);
        combTran = new StubJob("COMBTRAN", invocations);
        creaStmt = new StubJob("CREASTMT", invocations);
        tranRept = new StubJob("TRANREPT", invocations);

        final JobRepository repository = new HarnessJobRepository();
        final TaskExecutorJobLauncher pipelineLauncher = new TaskExecutorJobLauncher();
        pipelineLauncher.setJobRepository(repository);
        // Synchronous, so the pipeline runs on this thread and the diagnostic-context assertions that look
        // at what is left behind after a run are meaningful. Only the stage-4 split forks.
        pipelineLauncher.setTaskExecutor(new SyncTaskExecutor());
        final JobLauncher launcher = new HarnessJobLauncher(pipelineLauncher);
        transactionManager = new RecordingTransactionManager();

        orchestrator = new BatchPipelineOrchestrator(postTran, intCalc, combTran, creaStmt, tranRept,
                repository, launcher, transactionManager, PIPELINE_NAME);

        final Step postTranStep = orchestrator.batchPipelinePostTranStep();
        final Step intCalcStep = orchestrator.batchPipelineIntCalcStep();
        final Step combTranStep = orchestrator.batchPipelineCombTranStep();
        final Step creaStmtStep = orchestrator.batchPipelineCreaStmtStep();
        final Step tranReptStep = orchestrator.batchPipelineTranReptStep();
        final Flow split =
                orchestrator.batchPipelineStatementReportSplitFlow(creaStmtStep, tranReptStep);
        pipelineFlow = orchestrator.batchPipelineFlow(postTranStep, intCalcStep, combTranStep, split);
        pipelineJob = orchestrator.batchPipelineJob(pipelineFlow);
    }

    /** Leaves no diagnostic entry behind for the next test on this thread. */
    @AfterEach
    void clearDiagnosticContext() {
        MDC.clear();
    }

    /**
     * Runs the pipeline once with valid parameters.
     *
     * @return the finished pipeline execution
     */
    private JobExecution run() {
        return orchestrator.launchPipeline(pipelineJob, VALID_PARM_DATE, VALID_START_DATE, VALID_END_DATE);
    }

    /**
     * Makes the two stage-4 stubs wait for each other, so the test fails unless they overlap.
     *
     * @param concurrent set to {@code false} if either branch waited out its timeout
     */
    private void requireBranchesToOverlap(final AtomicBoolean concurrent) {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final Consumer<JobExecution> rendezvous = execution -> {
            try {
                barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (final TimeoutException | BrokenBarrierException notConcurrent) {
                concurrent.set(false);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                concurrent.set(false);
            }
        };
        creaStmt.onExecute(rendezvous);
        tranRept.onExecute(rendezvous);
    }

    /** Stage order, the split, and the shape of the composed flow. */
    @Nested
    @DisplayName("the topology is explicit, not implied")
    class Topology {

        @Test
        @DisplayName("all five stages run, in the order the job stream requires")
        void allFiveStagesRunInOrder() {
            final JobExecution execution = run();

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("app/jcl/INTCALC.jcl:L37-L41 writes the generation app/jcl/COMBTRAN.jcl:L25-L26 "
                            + "reads, so stages 1, 2 and 3 are ordered; the last two are unordered")
                    .startsWith("POSTTRAN", "INTCALC", "COMBTRAN")
                    .hasSize(5)
                    .contains("CREASTMT", "TRANREPT");
        }

        @Test
        @DisplayName("the composed flow holds the three sequential steps, four gates and the split")
        void theFlowHoldsEveryStateTheStreamNeeds() {
            final SimpleFlow flow = (SimpleFlow) pipelineFlow;
            final List<String> stateNames = flow.getStates().stream().map(State::getName).sorted().toList();

            // The framework names the states it generates, so these are counted by kind rather than by a
            // name this class chose. Three step states are the three sequential stages, the single flow
            // state is the split, and the four decision states are the four gates.
            assertThat(stateNames.stream().filter(name -> name.startsWith("batchPipelineFlow.step")).count())
                    .as("POSTTRAN, INTCALC and COMBTRAN run as steps of this flow")
                    .isEqualTo(3L);
            assertThat(stateNames.stream().filter(name -> name.startsWith("batchPipelineFlow.flow")).count())
                    .as("stage 4 is one nested flow, the split")
                    .isEqualTo(1L);
            assertThat(stateNames.stream()
                    .filter(name -> name.startsWith("batchPipelineFlow.decision"))
                    .count())
                    .as("one gate after each sequential stage and one after the split")
                    .isEqualTo(4L);
            assertThat(stateNames)
                    .as("the flow can end cleanly and can fail, which is what makes 8 and 12 expressible")
                    .contains("batchPipelineFlow.COMPLETED", "batchPipelineFlow.FAILED");
        }

        @Test
        @DisplayName("the split really is a split, and it holds exactly the two independent branches")
        void theSplitHoldsBothBranches() throws Exception {
            final SimpleFlow split = (SimpleFlow) orchestrator.batchPipelineStatementReportSplitFlow(
                    orchestrator.batchPipelineCreaStmtStep(), orchestrator.batchPipelineTranReptStep());
            // A flow only materialises its states once initialised; wiring one into a job does this, and a
            // flow inspected on its own has to be asked directly.
            split.afterPropertiesSet();

            final List<State> splitStates =
                    split.getStates().stream().filter(FlowHolder.class::isInstance).toList();
            assertThat(splitStates).as("the split flow's start state holds the branches").isNotEmpty();

            final Collection<Flow> branches = ((FlowHolder) splitStates.getFirst()).getFlows();
            assertThat(branches)
                    .as("app/jcl/CREASTMT.JCL and app/proc/TRANREPT.prc reference neither each other's "
                            + "inputs nor each other's outputs, so there are exactly two unordered branches")
                    .hasSize(2);
            assertThat(branches.stream().map(Flow::getName).toList())
                    .contains("batchPipelineCreaStmtBranchFlow", "batchPipelineTranReptBranchFlow");
        }

        @Test
        @DisplayName("the two branches overlap in time, which is what makes the split a split")
        void theTwoBranchesOverlap() {
            final AtomicBoolean concurrent = new AtomicBoolean(true);
            requireBranchesToOverlap(concurrent);

            final JobExecution execution = run();

            assertThat(concurrent)
                    .as("neither branch could clear the barrier unless the other was already inside it")
                    .isTrue();
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("no transaction is open while a stage runs, so a child owns its own commits")
        void theLauncherStepsAreNotTransactional() {
            run();

            assertThat(transactionManager.propagationBehaviours())
                    .as("PROPAGATION_REQUIRED would make every child step join this transaction and turn "
                            + "each of the child's commits into a no-op")
                    .isNotEmpty()
                    .allMatch(behaviour ->
                            behaviour.intValue() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        }
    }

    /** The four outcomes of {@code app/cbl/CBTRN02C.cbl:L229-L231}. */
    @Nested
    @DisplayName("the return-code contract, all four outcomes")
    class ReturnCodeContract {

        @Test
        @DisplayName("return code 0 - every stage completes and the pipeline completes")
        void returnCodeZeroProceeds() {
            final JobExecution execution = run();

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
            assertThat(execution.getExecutionContext()
                    .getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .isZero();
            assertThat(invocations).hasSize(5);
        }

        @Test
        @DisplayName("return code 4 - a rejected record does NOT stop the pipeline")
        void returnCodeFourProceeds() {
            postTran.onExecute(execution -> execution.getExecutionContext()
                    .putLong(DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY, 7L));

            final JobExecution execution = run();

            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("app/cbl/CBTRN02C.cbl:L229-L231 sets 4 when the reject count exceeds zero, and the "
                            + "stream's only step gating is internal to app/jcl/CREASTMT.JCL, so every "
                            + "downstream stage must still run")
                    .hasSize(5);
            assertThat(execution.getStatus())
                    .as("return code 4 is not a failure")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED WITH REJECTS");
            assertThat(execution.getExecutionContext()
                    .getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("return code 8 - the dependent chain halts and nothing downstream runs")
        void returnCodeEightHaltsTheChain() {
            postTran.onExecute(execution -> {
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
            });

            final JobExecution execution = run();

            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("a failed stage 1 leaves stages 2 to 5 unrun")
                    .containsExactly("POSTTRAN");
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_OUTCOME_ENTRY, ""))
                    .isEqualTo("FAILED");
        }

        @Test
        @DisplayName("return code 12 - an abend is reported as one, and its cause survives")
        void returnCodeTwelveReportsAnAbend() {
            final FatalProcessingException abend = new FatalProcessingException("0999", "CBTRN02C",
                    "UNEXPECTED FILE STATUS", "ABENDING PROGRAM");
            postTran.onExecute(execution -> {
                execution.addFailureException(abend);
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
            });

            final JobExecution execution = run();

            assertThat(execution.getStatus())
                    .as("an abend is unsuccessful, exactly as a failure is")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode())
                    .as("app/cbl/CBTRN02C.cbl:L707-L711 raises abend code 999 through CALL 'CEE3ABD', which "
                            + "is what distinguishes 12 from 8")
                    .isEqualTo("ABEND");
            assertThat(execution.getAllFailureExceptions())
                    .as("the child's cause reaches the pipeline rather than being flattened")
                    .contains(abend);
            assertThat(abend.getAbendCode()).isEqualTo("0999");
            assertThat(FatalProcessingException.BATCH_ABEND_CODE).isEqualTo(999);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE).isEqualTo(12);
        }

        @Test
        @DisplayName("an abend is reported even when only a step of the stage recorded it")
        void anAbendRecordedOnAStepIsStillAnAbend() {
            final FatalProcessingException abend = new FatalProcessingException("abend on a step");
            intCalc.onExecute(execution -> {
                final StepExecution step = execution.createStepExecution("interestCalculationStep");
                step.addFailureException(abend);
                step.setStatus(BatchStatus.FAILED);
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
            });

            final JobExecution execution = run();

            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("ABEND");
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .containsExactly("POSTTRAN", "INTCALC");
        }

        @Test
        @DisplayName("a launcher step that dies before recording anything still halts the pipeline")
        void aStepThatRecordsNothingStillHalts() {
            // The stage runner is what records a stage's return code, so a step that fails before reaching it
            // leaves nothing recorded. Refusing the transaction the step is built on reproduces that exactly,
            // and it is the real scenario that exposed the hole: a gate reading only the recorded aggregate
            // saw a clean run and let the pipeline carry on over a dead stage.
            transactionManager.refuseTransactionFor("batchPipelineIntCalcStep");

            final JobExecution execution = run();

            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("stage 2's job was never launched, so it recorded no return code at all")
                    .containsExactly("POSTTRAN");
            assertThat(execution.getExecutionContext().containsKey("carddemo.pipeline.intcalc.returnCode"))
                    .as("nothing was recorded for the stage that died")
                    .isFalse();
            assertThat(execution.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .as("the gate must raise the aggregate from the failed step execution itself")
                    .isEqualTo(8);
            assertThat(execution.getExecutionContext().getString(PIPELINE_OUTCOME_ENTRY, ""))
                    .isEqualTo("FAILED");
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        }

        @Test
        @DisplayName("a stage the launcher refuses becomes an abend, with the refusal preserved")
        void aRefusedStageBecomesAnAbend() {
            final JobRestartException refusal = new JobRestartException("stage restart refused");
            combTran.refuseWith(refusal);

            final JobExecution execution = run();

            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("stage 4 must not run once stage 3 has abended")
                    .containsExactly("POSTTRAN", "INTCALC");
            assertThat(execution.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .as("app/cbl/CBTRN02C.cbl:L707-L711 abends rather than merely failing, so 12 not 8")
                    .isEqualTo(12);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("ABEND");
            assertThat(execution.getAllFailureExceptions())
                    .as("the launcher's refusal survives as the root cause")
                    .anySatisfy(failure -> assertThat(failure)
                            .isInstanceOf(FatalProcessingException.class)
                            .hasCause(refusal));
        }

        @Test
        @DisplayName("one split branch failing does not hide the other's outcome")
        void oneFailingBranchLeavesTheOtherRecorded() {
            final AtomicBoolean concurrent = new AtomicBoolean(true);
            requireBranchesToOverlap(concurrent);
            tranRept.onExecute(execution -> {
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
            });

            final JobExecution execution = run();

            assertThat(concurrent).isTrue();
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("both branches run: they share neither input nor output")
                    .hasSize(5)
                    .contains("CREASTMT", "TRANREPT");
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExecutionContext().getInt("carddemo.pipeline.creastmt.returnCode", -1))
                    .as("the branch that succeeded is still recorded as having succeeded")
                    .isZero();
            assertThat(execution.getExecutionContext().getInt("carddemo.pipeline.tranrept.returnCode", -1))
                    .isEqualTo(8);
        }
    }

    /** The generation handoff between {@code SYSTRAN(+1)} and {@code SYSTRAN(0)}. */
    @Nested
    @DisplayName("the generation handoff is published and then checked, never re-resolved")
    class GenerationHandoff {

        @Test
        @DisplayName("the greatest key stage 2 created is pinned, and stage 3 reading it verifies")
        void thePinnedKeyIsVerified() {
            intCalc.onExecute(execution -> publishKeys(execution, SYSTRAN_KEY_ONE, SYSTRAN_KEY_TWO));
            combTran.onExecute(execution -> recordResolvedKey(execution, SYSTRAN_KEY_TWO));

            final JobExecution execution = run();

            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_GENERATION_ENTRY, ""))
                    .as("SYSTRAN(0) is the lexicographically greatest existing key")
                    .isEqualTo(SYSTRAN_KEY_TWO);
            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_HANDOFF_ENTRY, ""))
                    .isEqualTo(HANDOFF_VERIFIED);
            assertThat(execution.getExecutionContext()
                    .getInt(PIPELINE_SYSTRAN_KEY_COUNT_ENTRY, -1))
                    .isEqualTo(2);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("stage 3 reading a different key is an abend that names both")
        void aDifferentKeyIsAnAbend() {
            intCalc.onExecute(execution -> publishKeys(execution, SYSTRAN_KEY_ONE));
            combTran.onExecute(execution -> recordResolvedKey(execution, SYSTRAN_KEY_TWO));

            final JobExecution execution = run();

            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_HANDOFF_ENTRY, ""))
                    .isEqualTo(HANDOFF_MISMATCH);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("ABEND");
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(failure -> failure instanceof FatalProcessingException
                            && failure.getMessage().contains(SYSTRAN_KEY_ONE)
                            && failure.getMessage().contains(SYSTRAN_KEY_TWO));
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("stage 4 must not run on a broken handoff")
                    .containsExactly("POSTTRAN", "INTCALC", "COMBTRAN");
        }

        @Test
        @DisplayName("an empty generation is a recorded outcome, not a silent zero-record success")
        void anEmptyGenerationIsRecordedAndProceeds() {
            intCalc.onExecute(execution -> publishKeys(execution));

            final JobExecution execution = run();

            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_GENERATION_ENTRY, ""))
                    .isEqualTo(HANDOFF_ABSENT);
            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_HANDOFF_ENTRY, ""))
                    .isEqualTo(HANDOFF_ABSENT);
            assertThat(execution.getStatus())
                    .as("a run in which every applicable rate was zero generates no object and is not a "
                            + "failure")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(invocations).hasSize(5);
        }

        @Test
        @DisplayName("a stage 3 that recorded no key at all is reported as not applicable")
        void noResolvedKeyIsReportedRatherThanAssumed() {
            intCalc.onExecute(execution -> publishKeys(execution, SYSTRAN_KEY_ONE));

            final JobExecution execution = run();

            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_HANDOFF_ENTRY, ""))
                    .isEqualTo(HANDOFF_NOT_APPLICABLE);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        /**
         * Publishes the {@code SYSTRAN} keys a stage-2 run would have created.
         *
         * @param execution stage 2's execution
         * @param keys the object keys, in creation order
         */
        private void publishKeys(final JobExecution execution, final String... keys) {
            execution.getExecutionContext().putLong(
                    InterestCalculationJob.SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY, keys.length);
            for (int index = 0; index < keys.length; index++) {
                execution.getExecutionContext().putString(
                        InterestCalculationJob.SYSTRAN_GENERATION_KEYS_INDEX_PREFIX + index, keys[index]);
            }
        }

        /**
         * Records the key a stage-3 reader would have resolved, in a step execution context.
         *
         * @param execution stage 3's execution
         * @param key the resolved object key
         */
        private void recordResolvedKey(final JobExecution execution, final String key) {
            execution.createStepExecution("combineTransactionsSortStep")
                    .getExecutionContext()
                    .putString(READER_SYSTRAN_OBJECT_KEY_ENTRY, key);
        }
    }

    /** The diagnostic context, including the thread hop the split forces. */
    @Nested
    @DisplayName("the diagnostic context spans every stage and both branch threads")
    class DiagnosticContext {

        @Test
        @DisplayName("one stable correlation identifier spans all five stages")
        void oneCorrelationIdentifierSpansEveryStage() {
            run();

            final List<String> correlationIds = invocations.stream()
                    .map(invocation -> invocation.diagnostic().get(MDC_KEY_CORRELATION_ID))
                    .distinct()
                    .toList();
            assertThat(correlationIds)
                    .as("a run must be traceable end to end, so one identifier reaches all five stages")
                    .hasSize(1);
            assertThat(correlationIds.getFirst()).startsWith("pipeline-");
        }

        @Test
        @DisplayName("both branch threads carry every identifier the launching thread carried")
        void bothBranchThreadsCarryTheContext() {
            MDC.put(MDC_KEY_TRACE_ID, "0af7651916cd43dd8448eb211c80319c");
            MDC.put(MDC_KEY_SPAN_ID, "b7ad6b7169203331");
            final AtomicBoolean concurrent = new AtomicBoolean(true);
            requireBranchesToOverlap(concurrent);

            run();

            assertThat(concurrent).isTrue();
            final List<Invocation> branchInvocations = invocations.stream()
                    .filter(invocation -> invocation.thread().startsWith(SPLIT_THREAD_NAME_PREFIX))
                    .toList();
            assertThat(branchInvocations)
                    .as("the split runs its two branches on threads of its own")
                    .hasSize(2);
            for (final Invocation invocation : branchInvocations) {
                assertThat(invocation.diagnostic())
                        .as("a naive put on the launching thread would leave these empty on %s",
                                invocation.thread())
                        .containsKeys(MDC_KEY_JOB_INSTANCE_ID, MDC_KEY_CORRELATION_ID, MDC_KEY_TRACE_ID,
                                MDC_KEY_SPAN_ID);
                assertThat(invocation.diagnostic().get(MDC_KEY_TRACE_ID))
                        .isEqualTo("0af7651916cd43dd8448eb211c80319c");
                assertThat(invocation.diagnostic().get(MDC_KEY_SPAN_ID))
                        .isEqualTo("b7ad6b7169203331");
            }
        }

        @Test
        @DisplayName("the context is released, and a value an outer scope owned is restored not deleted")
        void theContextIsReleasedAndAnOuterValueRestored() {
            MDC.put(MDC_KEY_CORRELATION_ID, "outer-scope-owns-this");
            MDC.put(MDC_KEY_JOB_INSTANCE_ID, "99");

            run();

            assertThat(MDC.get(MDC_KEY_CORRELATION_ID))
                    .as("an outer identifier is inherited by the run and handed back afterwards")
                    .isEqualTo("outer-scope-owns-this");
            assertThat(MDC.get(MDC_KEY_JOB_INSTANCE_ID)).isEqualTo("99");
            assertThat(invocations.stream()
                    .map(invocation -> invocation.diagnostic().get(MDC_KEY_CORRELATION_ID))
                    .distinct()
                    .toList())
                    .containsExactly("outer-scope-owns-this");
        }

        @Test
        @DisplayName("a key nothing owned is removed rather than left behind as a blank")
        void anUnownedKeyIsRemoved() {
            run();

            assertThat(MDC.get(MDC_KEY_CORRELATION_ID))
                    .as("restoring an empty string where there had been nothing would mislabel every later "
                            + "event on this thread")
                    .isNull();
            assertThat(MDC.get(MDC_KEY_JOB_INSTANCE_ID)).isNull();
        }
    }

    /** The two ten-character date shapes, and the launch refusals. */
    @Nested
    @DisplayName("job parameters are untrusted input")
    class ParameterContract {

        @Test
        @DisplayName("the three valid parameters are carried through unchanged")
        void validParametersAreCarriedThrough() {
            final JobParameters parameters = orchestrator.pipelineParameters(
                    VALID_PARM_DATE, VALID_START_DATE, VALID_END_DATE);

            assertThat(parameters.getString("parmDate")).isEqualTo(VALID_PARM_DATE);
            assertThat(parameters.getString("startDate")).isEqualTo(VALID_START_DATE);
            assertThat(parameters.getString("endDate")).isEqualTo(VALID_END_DATE);
        }

        @Test
        @DisplayName("the interest date must be ten digits with no separator")
        void theInterestDateRejectsAnIsoValue() {
            assertThatExceptionOfType(ValidationException.class)
                    .as("app/jcl/INTCALC.jcl:L22 supplies PARM='2022071800', not an ISO date")
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            VALID_START_DATE, VALID_START_DATE, VALID_END_DATE))
                    .withMessageContaining("parmDate");
        }

        @Test
        @DisplayName("a report date must be yyyy-MM-dd, dashes included")
        void aReportDateRejectsATenDigitValue() {
            assertThatExceptionOfType(ValidationException.class)
                    .as("app/proc/TRANREPT.prc:L41 declares C'2022-01-01'")
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            VALID_PARM_DATE, VALID_PARM_DATE, VALID_END_DATE))
                    .withMessageContaining("startDate");
        }

        @Test
        @DisplayName("the interest date must end in the two trailing zeros the PARM supplies")
        void theInterestDateRequiresItsTrailer() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            "2022071801", VALID_START_DATE, VALID_END_DATE))
                    .withMessageContaining("trailing zeros");
        }

        @Test
        @DisplayName("neither shape accepts an impossible calendar date")
        void neitherShapeAcceptsAnImpossibleDate() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            "2022023000", VALID_START_DATE, VALID_END_DATE))
                    .withMessageContaining("day");
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            VALID_PARM_DATE, "2023-02-29", VALID_END_DATE))
                    .withMessageContaining("day");
            assertThatCode(() -> orchestrator.pipelineParameters(
                    "2024022900", "2024-02-29", "2024-03-01"))
                    .as("2024 is a leap year, so the 29th is a real date in both shapes")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a start date after the end date is refused")
        void aReversedRangeIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            VALID_PARM_DATE, VALID_END_DATE, VALID_START_DATE))
                    .withMessageContaining("later than");
        }

        @Test
        @DisplayName("every missing parameter is named rather than defaulted")
        void everyMissingParameterIsNamed() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            null, VALID_START_DATE, VALID_END_DATE))
                    .withMessageContaining("parmDate");
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.pipelineParameters(VALID_PARM_DATE, "  ", VALID_END_DATE))
                    .withMessageContaining("startDate");
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.pipelineParameters(
                            VALID_PARM_DATE, VALID_START_DATE, null))
                    .withMessageContaining("endDate");
        }

        @Test
        @DisplayName("launching without the job is refused rather than dereferenced")
        void launchingWithoutTheJobIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> orchestrator.launchPipeline(
                            null, VALID_PARM_DATE, VALID_START_DATE, VALID_END_DATE))
                    .withMessageContaining("batchPipelineJob");
        }

        @Test
        @DisplayName("the job's own validator applies the same rules however it is launched")
        void theJobValidatorAppliesTheSameRules() {
            final JobParametersValidator validator = pipelineJob.getJobParametersValidator();
            assertThat(validator).isNotNull();

            assertThatExceptionOfType(ValidationException.class)
                    .as("a caller that built its own parameters must not bypass the check")
                    .isThrownBy(() -> validator.validate(new JobParameters()))
                    .withMessageContaining("parmDate");
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validator.validate(null))
                    .withMessageContaining("no parameters at all");
        }
    }

    /** Every refusal the launcher can raise, mapped to a typed exception with its cause kept. */
    @Nested
    @DisplayName("a launch refusal is typed and keeps its cause")
    class LaunchRefusals {

        @Test
        @DisplayName("invalid parameters become a validation failure")
        void invalidParametersBecomeAValidationFailure() {
            final JobParametersInvalidException cause = new JobParametersInvalidException("bad");
            assertRefusalMapsTo(cause, ValidationException.class);
        }

        @Test
        @DisplayName("an already-complete instance becomes a validation failure that says why")
        void anAlreadyCompleteInstanceBecomesAValidationFailure() {
            final JobInstanceAlreadyCompleteException cause =
                    new JobInstanceAlreadyCompleteException("done");
            final Throwable thrown = refusalOf(cause);
            assertThat(thrown).isInstanceOf(ValidationException.class).hasCause(cause);
            assertThat(thrown.getMessage())
                    .as("no incrementer is declared, deliberately, so the refusal must explain itself")
                    .contains("incrementer");
        }

        @Test
        @DisplayName("an already-running pipeline becomes a fatal abend")
        void anAlreadyRunningPipelineBecomesAnAbend() {
            assertRefusalMapsTo(new JobExecutionAlreadyRunningException("running"),
                    FatalProcessingException.class);
        }

        @Test
        @DisplayName("a refused restart becomes a fatal abend")
        void aRefusedRestartBecomesAnAbend() {
            assertRefusalMapsTo(new JobRestartException("no restart"), FatalProcessingException.class);
        }

        @Test
        @DisplayName("an unexpected launcher failure becomes a fatal abend carrying code 0999")
        void anUnexpectedFailureBecomesAnAbend() {
            final Throwable thrown = refusalOf(new IllegalStateException("launcher exploded"));
            assertThat(thrown).isInstanceOf(FatalProcessingException.class);
            assertThat(((FatalProcessingException) thrown).getAbendCode()).isEqualTo("0999");
            assertThat(((FatalProcessingException) thrown).getAbendCulprit()).isEqualTo("PIPELINE");
        }

        /**
         * Launches through a launcher that always refuses, and returns what the orchestrator threw.
         *
         * @param cause the refusal the launcher raises
         * @return the exception the orchestrator threw
         */
        private Throwable refusalOf(final Exception cause) {
            final BatchPipelineOrchestrator refusing = new BatchPipelineOrchestrator(
                    postTran, intCalc, combTran, creaStmt, tranRept, new HarnessJobRepository(),
                    new RefusingJobLauncher(cause), new RecordingTransactionManager(), PIPELINE_NAME);
            try {
                refusing.launchPipeline(pipelineJob, VALID_PARM_DATE, VALID_START_DATE, VALID_END_DATE);
            } catch (final RuntimeException thrown) {
                return thrown;
            }
            throw new AssertionError("the refusal was not surfaced");
        }

        /**
         * Asserts one refusal maps to one exception type and keeps its cause.
         *
         * @param cause the refusal the launcher raises
         * @param expected the exception type the orchestrator must throw
         */
        private void assertRefusalMapsTo(final Exception cause,
                final Class<? extends RuntimeException> expected) {

            assertThat(refusalOf(cause)).isInstanceOf(expected).hasCause(cause);
        }
    }

    /**
     * One stage launch, with everything the assertions need about it.
     *
     * @param stage the stage's display name
     * @param thread the name of the thread the stage was launched on
     * @param diagnostic the diagnostic context as it stood on that thread, never {@code null}
     */
    private record Invocation(String stage, String thread, Map<String, String> diagnostic) {
    }

    /**
     * A sibling job replaced by a recorded stub, so every outcome the pipeline must handle is reachable.
     *
     * <p>Its outcome is a consumer each test sets, applied to the execution the harness launcher created. That
     * is what lets one harness produce a clean run, a run with rejects, a failure, an abend and a broken
     * generation handoff without any of them touching a database.
     */
    private static final class StubJob implements Job {

        /** Identifiers handed out to the executions and instances this stub creates. */
        private static final AtomicLong IDENTIFIERS = new AtomicLong(1000L);

        /** The stage's display name, which is also the job name. */
        private final String name;

        /** The shared invocation log, in launch order. */
        private final List<Invocation> log;

        /** What this stage does to its execution. Replaced by {@link #onExecute(Consumer)}. */
        private volatile Consumer<JobExecution> outcome = execution -> { };

        /** A refusal the launcher raises instead of running this stage, or {@code null} to run it. */
        private volatile Exception refusal;

        /**
         * Creates a stub.
         *
         * @param name the stage's display name
         * @param log the shared invocation log
         */
        private StubJob(final String name, final List<Invocation> log) {
            this.name = name;
            this.log = log;
        }

        /**
         * Sets what this stage does when it runs. Composes with anything already set.
         *
         * @param additional the outcome to apply
         */
        void onExecute(final Consumer<JobExecution> additional) {
            final Consumer<JobExecution> existing = outcome;
            outcome = execution -> {
                existing.accept(execution);
                additional.accept(execution);
            };
        }

        /**
         * Makes the launcher refuse this stage rather than run it.
         *
         * @param launcherRefusal the exception the launcher raises
         */
        void refuseWith(final Exception launcherRefusal) {
            refusal = launcherRefusal;
        }

        /**
         * The refusal configured for this stage, if any.
         *
         * @return the refusal, or {@code null} when the stage should run
         */
        Exception refusal() {
            return refusal;
        }

        /**
         * Builds and completes one execution of this stage, recording the call.
         *
         * @param parameters the parameters the pipeline launched it with
         * @return the finished execution
         */
        JobExecution simulate(final JobParameters parameters) {
            final long identifier = IDENTIFIERS.incrementAndGet();
            final JobExecution execution = new JobExecution(
                    new JobInstance(Long.valueOf(identifier), name), Long.valueOf(identifier), parameters);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            final Map<String, String> diagnostic = MDC.getCopyOfContextMap();
            log.add(new Invocation(name, Thread.currentThread().getName(),
                    diagnostic == null ? new HashMap<>() : new HashMap<>(diagnostic)));
            execute(execution);
            return execution;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void execute(final JobExecution execution) {
            outcome.accept(execution);
        }
    }

    /**
     * Routes a launch either to the real launcher, for the pipeline itself, or to a stub, for a stage.
     *
     * <p>The orchestrator holds one launcher and uses it for both, which is exactly what makes this indirection
     * the right seam: the pipeline runs for real while every stage is controlled.
     */
    private static final class HarnessJobLauncher implements JobLauncher {

        /** The real launcher, used for the pipeline job. */
        private final JobLauncher delegate;

        /**
         * Creates the router.
         *
         * @param delegate the real launcher
         */
        private HarnessJobLauncher(final JobLauncher delegate) {
            this.delegate = delegate;
        }

        @Override
        public JobExecution run(final Job job, final JobParameters parameters)
                throws JobExecutionAlreadyRunningException, JobRestartException,
                JobInstanceAlreadyCompleteException, JobParametersInvalidException {

            if (job instanceof StubJob stub) {
                final Exception refusal = stub.refusal();
                if (refusal != null) {
                    throw asLauncherRefusal(refusal);
                }
                return stub.simulate(parameters);
            }
            return delegate.run(job, parameters);
        }
    }

    /**
     * Rethrows a configured refusal as the checked type the launcher contract declares.
     *
     * @param refusal the exception to raise
     * @return never returns; the declared return type lets a caller write {@code throw asLauncherRefusal(x)}
     * @throws JobExecutionAlreadyRunningException when that is the configured refusal
     * @throws JobRestartException when that is the configured refusal
     * @throws JobInstanceAlreadyCompleteException when that is the configured refusal
     * @throws JobParametersInvalidException when that is the configured refusal
     */
    private static RuntimeException asLauncherRefusal(final Exception refusal)
            throws JobExecutionAlreadyRunningException, JobRestartException,
            JobInstanceAlreadyCompleteException, JobParametersInvalidException {

        if (refusal instanceof JobExecutionAlreadyRunningException running) {
            throw running;
        }
        if (refusal instanceof JobRestartException restart) {
            throw restart;
        }
        if (refusal instanceof JobInstanceAlreadyCompleteException complete) {
            throw complete;
        }
        if (refusal instanceof JobParametersInvalidException invalid) {
            throw invalid;
        }
        return (RuntimeException) refusal;
    }

    /** A launcher that refuses every request with one configured exception. */
    private static final class RefusingJobLauncher implements JobLauncher {

        /** The refusal to raise. */
        private final Exception refusal;

        /**
         * Creates the launcher.
         *
         * @param refusal the exception every call raises
         */
        private RefusingJobLauncher(final Exception refusal) {
            this.refusal = refusal;
        }

        @Override
        public JobExecution run(final Job job, final JobParameters parameters)
                throws JobExecutionAlreadyRunningException, JobRestartException,
                JobInstanceAlreadyCompleteException, JobParametersInvalidException {

            throw asLauncherRefusal(refusal);
        }
    }

    /**
     * A thread-safe in-memory job repository.
     *
     * <p>The framework's own resourceless repository holds one instance and one execution in plain fields,
     * which two branch threads updating step executions at the same time would race on. This one is
     * synchronised, so the split cannot make a test flake.
     */
    private static final class HarnessJobRepository implements JobRepository {

        /** Identifiers handed out to instances, executions and step executions. */
        private final AtomicLong identifiers = new AtomicLong();

        /** Instances by name and parameter set, so one name and set means one instance. */
        private final Map<String, JobInstance> instances = new LinkedHashMap<>();

        /** The most recent execution per instance key. */
        private final Map<String, JobExecution> executions = new LinkedHashMap<>();

        /**
         * The instance key for one name and parameter set.
         *
         * @param jobName the job name
         * @param jobParameters the parameters
         * @return the key
         */
        private static String keyOf(final String jobName, final JobParameters jobParameters) {
            return jobName + '|' + jobParameters;
        }

        @Override
        public synchronized boolean isJobInstanceExists(final String jobName,
                final JobParameters jobParameters) {

            return instances.containsKey(keyOf(jobName, jobParameters));
        }

        @Override
        public synchronized JobInstance createJobInstance(final String jobName,
                final JobParameters jobParameters) {

            final JobInstance instance =
                    new JobInstance(Long.valueOf(identifiers.incrementAndGet()), jobName);
            instances.put(keyOf(jobName, jobParameters), instance);
            return instance;
        }

        @Override
        public synchronized JobExecution createJobExecution(final String jobName,
                final JobParameters jobParameters) {

            final String key = keyOf(jobName, jobParameters);
            final JobInstance instance = instances.containsKey(key)
                    ? instances.get(key)
                    : createJobInstance(jobName, jobParameters);
            final JobExecution execution = new JobExecution(
                    instance, Long.valueOf(identifiers.incrementAndGet()), jobParameters);
            executions.put(key, execution);
            return execution;
        }

        @Override
        public synchronized void update(final JobExecution jobExecution) {
            // In-memory: the caller already holds the object being updated.
        }

        @Override
        public synchronized void add(final StepExecution stepExecution) {
            stepExecution.setId(Long.valueOf(identifiers.incrementAndGet()));
        }

        @Override
        public synchronized void addAll(final Collection<StepExecution> stepExecutions) {
            for (final StepExecution stepExecution : stepExecutions) {
                add(stepExecution);
            }
        }

        @Override
        public synchronized void update(final StepExecution stepExecution) {
            // In-memory: see update(JobExecution).
        }

        @Override
        public synchronized void updateExecutionContext(final StepExecution stepExecution) {
            // In-memory: see update(JobExecution).
        }

        @Override
        public synchronized void updateExecutionContext(final JobExecution jobExecution) {
            // In-memory: see update(JobExecution).
        }

        @Override
        public synchronized StepExecution getLastStepExecution(final JobInstance jobInstance,
                final String stepName) {

            return null;
        }

        @Override
        public synchronized long getStepExecutionCount(final JobInstance jobInstance,
                final String stepName) {

            return 0L;
        }

        @Override
        public synchronized JobExecution getLastJobExecution(final String jobName,
                final JobParameters jobParameters) {

            return executions.get(keyOf(jobName, jobParameters));
        }
    }

    /**
     * A transaction manager that records the propagation behaviour it was asked for.
     *
     * <p>It is what makes the "no transaction while a stage runs" claim checkable without a database: the
     * launcher steps must ask for {@link TransactionDefinition#PROPAGATION_NOT_SUPPORTED} and nothing else.
     */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        /** The real manager, so the steps behave normally. */
        private final SuspendableTransactionManager delegate = new SuspendableTransactionManager();

        /** Every propagation behaviour requested, in request order. */
        private final List<Integer> requested = new CopyOnWriteArrayList<>();

        /** The transaction-attribute name to refuse, or {@code null} to serve every request. */
        private volatile String refusedName;

        /**
         * Refuses the transaction one launcher step is built on, so the step dies before its runner runs.
         *
         * @param transactionAttributeName the step name, which is also the transaction attribute's name
         */
        void refuseTransactionFor(final String transactionAttributeName) {
            refusedName = transactionAttributeName;
        }

        /**
         * The propagation behaviours requested so far.
         *
         * @return an immutable snapshot
         */
        List<Integer> propagationBehaviours() {
            return List.copyOf(requested);
        }

        @Override
        public TransactionStatus getTransaction(final TransactionDefinition definition)
                throws TransactionException {

            if (definition != null) {
                requested.add(Integer.valueOf(definition.getPropagationBehavior()));
                if (refusedName != null && refusedName.equals(definition.getName())) {
                    throw new CannotCreateTransactionException(
                            "the harness refused the transaction for " + refusedName);
                }
            }
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(final TransactionStatus status) throws TransactionException {
            delegate.commit(status);
        }

        @Override
        public void rollback(final TransactionStatus status) throws TransactionException {
            delegate.rollback(status);
        }
    }

    /**
     * A resourceless manager that can suspend, which every production manager can.
     *
     * <p>{@link TransactionDefinition#PROPAGATION_NOT_SUPPORTED} suspends whatever transaction is in progress,
     * and the framework's bare {@link ResourcelessTransactionManager} refuses to suspend at all. Without this
     * subclass the harness would be asserting a limitation of a test double rather than the behaviour of the
     * pipeline: {@code JpaTransactionManager}, which the application actually injects, supports suspension.
     *
     * <p>There is nothing to hand back on resume because there is no resource to unbind, so the suspended
     * handle is the transaction object itself and resuming is a no-op.
     */
    private static final class SuspendableTransactionManager extends ResourcelessTransactionManager {

        /** Serial identifier, inherited from {@code AbstractPlatformTransactionManager}. */
        private static final long serialVersionUID = 1L;

        @Override
        protected Object doSuspend(final Object transaction) {
            return transaction;
        }

        @Override
        protected void doResume(final Object transaction, final Object suspendedResources) {
            // Nothing is bound to the thread, so nothing has to be rebound.
        }
    }

}
