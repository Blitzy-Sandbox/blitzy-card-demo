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

import com.cardemo.batch.jobs.BatchPipelineOrchestrator;
import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.batch.jobs.InterestCalculationJob;
import com.cardemo.batch.readers.CombinedTransactionReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.observability.CorrelationIdFilter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

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

    /** The only substrate the combine stage may run on; see finding M-01. */
    private static final String READER_SOURCE_OBJECT_STORAGE = "object-storage";

    /** The job parameter the pinned generation travels in; see finding C-04. */
    private static final String SYSTRAN_KEY_JOB_PARAMETER =
            CombinedTransactionReader.SYSTRAN_GENERATION_JOB_PARAMETER;

    /** A valid interest date: ten digits, no separator, two trailing zeros. */
    private static final String VALID_PARM_DATE = "2022071800";

    /** A valid report start date in {@code yyyy-MM-dd}. */
    private static final String VALID_START_DATE = "2022-01-01";

    /** A valid report end date in {@code yyyy-MM-dd}. */
    private static final String VALID_END_DATE = "2022-07-06";

    /** How long a branch waits for its sibling before the parallelism assertion fails. */
    private static final int BARRIER_TIMEOUT_SECONDS = 20;

    // Finding M-02: these four names were re-spelled here as string literals, mirroring literals the
    // production class also re-spelled. Both sets are now read from the single public definition, so a
    // rename in either direction fails this test rather than silently emptying a log field.

    /** Diagnostic key carrying the job instance identifier. */
    private static final String MDC_KEY_JOB_INSTANCE_ID = CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID;

    /** Diagnostic key carrying the correlation identifier. */
    private static final String MDC_KEY_CORRELATION_ID = CorrelationIdFilter.MDC_KEY_CORRELATION_ID;

    /** Diagnostic key carrying the trace identifier. */
    private static final String MDC_KEY_TRACE_ID = CorrelationIdFilter.MDC_KEY_TRACE_ID;

    /** Diagnostic key carrying the span identifier. */
    private static final String MDC_KEY_SPAN_ID = CorrelationIdFilter.MDC_KEY_SPAN_ID;

    /** Thread-name prefix the split executor uses. */
    private static final String SPLIT_THREAD_NAME_PREFIX = "carddemo-pipeline-split-";

    /** The generation prefix stage 2 would publish for the {@code SYSTRAN(+1)} it created. */
    private static final String SYSTRAN_GENERATION = "gdg/systran/generation=0000000000000000002";

    /** A different generation, standing in for one a concurrent run left behind. */
    private static final String OTHER_SYSTRAN_GENERATION = "gdg/systran/generation=0000000000000000009";

    /** A representative object key stage 2 would create under {@link #SYSTRAN_GENERATION}. */
    private static final String SYSTRAN_KEY_ONE = SYSTRAN_GENERATION + "/systran-0000000000000000001.dat";

    /** A second object of the same generation, which stage 2 emits one per chunk. */
    private static final String SYSTRAN_KEY_TWO = SYSTRAN_GENERATION + "/systran-0000000000000000002.dat";

    /** An object of a different generation, which stage 3 must never resolve once a generation is pinned. */
    private static final String OTHER_GENERATION_KEY =
            OTHER_SYSTRAN_GENERATION + "/systran-0000000000000000001.dat";

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

    /** The harness job repository, retained so a test can seed a newly created pipeline execution. */
    private HarnessJobRepository harnessRepository;

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

        final HarnessJobRepository repository = new HarnessJobRepository();
        harnessRepository = repository;
        final TaskExecutorJobLauncher pipelineLauncher = new TaskExecutorJobLauncher();
        pipelineLauncher.setJobRepository(repository);
        // Synchronous, so the pipeline runs on this thread and the diagnostic-context assertions that look
        // at what is left behind after a run are meaningful. Only the stage-4 split forks.
        pipelineLauncher.setTaskExecutor(new SyncTaskExecutor());
        final JobLauncher launcher = new HarnessJobLauncher(pipelineLauncher);
        transactionManager = new RecordingTransactionManager();

        // The substrate the combine stage requires (finding M-01): the orchestrator refuses that stage on
        // any other value, so the harness supplies the one a real deployment declares.
        orchestrator = new BatchPipelineOrchestrator(postTran, intCalc, combTran, creaStmt, tranRept,
                repository, launcher, transactionManager, PIPELINE_NAME, READER_SOURCE_OBJECT_STORAGE);

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
     * Runs the production pipeline flow with the aggregate return-code entry pre-seeded.
     *
     * <p>The job is composed around the <em>production</em> flow - the same five launcher steps and the same
     * four gates - with one listener added that writes the entry before the first step runs. A stage cannot
     * produce a value outside {0, 4, 8, 12}, so seeding is the only way to reach the gaps and the malformed
     * state through a real execution rather than through a reflective call.
     *
     * @param seeded the value to place under the aggregate entry; an {@code Integer} for a band test and
     *     any other type for the malformed-entry test
     * @return the finished pipeline execution
     */
    private JobExecution runWithSeededAggregate(final Object seeded) {
        harnessRepository.onFirstStep(execution ->
                execution.getExecutionContext().put(PIPELINE_RETURN_CODE_ENTRY, seeded));
        return run();
    }

    /**
     * Invokes {@code BatchPipelineOrchestrator.gateFor(int)} reflectively.
     *
     * @param returnCode the return code to band
     * @return the gate outcome the flow would transition on
     */
    private static String gateFor(final int returnCode) {
        return invokePrivateBanding("gateFor", returnCode);
    }

    /**
     * Invokes {@code BatchPipelineOrchestrator.outcomeFor(int)} reflectively.
     *
     * @param returnCode the return code to band
     * @return the exit code the pipeline would publish
     */
    private static String outcomeFor(final int returnCode) {
        return invokePrivateBanding("outcomeFor", returnCode);
    }

    /**
     * Invokes {@code BatchPipelineOrchestrator.readAggregateReturnCode(ExecutionContext)} reflectively.
     *
     * @param context the execution context to read
     * @return the aggregate return code under the documented policy
     */
    private static int readAggregate(final ExecutionContext context) {
        try {
            final Method method = BatchPipelineOrchestrator.class
                    .getDeclaredMethod("readAggregateReturnCode", ExecutionContext.class);
            method.setAccessible(true);
            return ((Integer) method.invoke(null, context)).intValue();
        } catch (final ReflectiveOperationException unreachable) {
            throw new AssertionError("BatchPipelineOrchestrator.readAggregateReturnCode(ExecutionContext) "
                    + "is the documented policy method; a rename must update this test rather than remove "
                    + "the assertion.", unreachable);
        }
    }

    /**
     * Invokes one of the two private banding methods.
     *
     * <p>Both keep the same shape: one {@code int} in, one {@code String} out, no state. Sharing the
     * reflection here means a rename fails with a message that names the method rather than with a bare
     * {@link NoSuchMethodException}.
     *
     * @param methodName the banding method's name
     * @param returnCode the return code to band
     * @return the banded value
     */
    private static String invokePrivateBanding(final String methodName, final int returnCode) {
        try {
            final Method method =
                    BatchPipelineOrchestrator.class.getDeclaredMethod(methodName, int.class);
            method.setAccessible(true);
            return (String) method.invoke(null, Integer.valueOf(returnCode));
        } catch (final ReflectiveOperationException unreachable) {
            throw new AssertionError("BatchPipelineOrchestrator." + methodName + "(int) is the documented "
                    + "banding policy; a rename must update this test rather than remove the assertion.",
                    unreachable);
        }
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

    // The read-only dataset verification topology is asserted where it is declared: the four Step beans and
    // the Job that composes them live in com.cardemo.config.BatchConfig, so
    // com.cardemo.unit.config.BatchConfigTest.DatasetVerificationTopology covers the bean methods and
    // com.cardemo.integration.batch.DatasetVerificationJobTest launches the job against real
    // infrastructure. A nest here would have been a second definition site for the same job - the exact
    // duplication findings F-006 and TEST-012 were both closing - so this class asserts only the five-stage
    // pipeline it declares.

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
        @DisplayName("an absent aggregate entry reads as 0 rather than as unknown")
        void anAbsentAggregateEntryReadsAsZero() {
            // The entry is written by the stage runner as each stage finishes, so before the first stage
            // finishes it is absent - and absence has to mean "clean so far" rather than "unknown", or the
            // gate after stage 1 would have nothing to decide on. Asserted on the policy method itself,
            // because by the time a run ends the entry always exists and the case is unobservable.
            assertThat(readAggregate(new ExecutionContext()))
                    .as("an execution context with no aggregate entry reads as return code 0")
                    .isZero();
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

    /**
     * What a restart of a halted instance does, which is nothing.
     *
     * <p><strong>Finding P5-01, severity Critical.</strong> A pipeline that halted at return code 8 or abended
     * at 12 could be restarted straight through its own halt. Two mechanisms had to line up for that, and both
     * are reproduced faithfully here rather than simulated:
     *
     * <ul>
     *   <li>{@code SimpleJobRepository.createJobExecution} copies the previous execution's execution context
     *       onto the new one, so the halt is present from the outset. The harness repository does the same, in
     *       {@code createJobExecution}.</li>
     *   <li>{@code SimpleStepHandler.shouldStart} skips a step whose last execution completed, so the launcher
     *       step that recorded the halt does not run again and the stage runner never re-records it. The
     *       harness answers {@code getLastStepExecution}, which is what makes the skip happen.</li>
     * </ul>
     *
     * <p>The listener then zeroed the carried aggregate and both gate inputs read a clean run, so the first
     * gate said {@code PROCEED} and every downstream stage launched over data that had already been posted.
     * These tests assert the two properties that matter to a caller: <em>no downstream stage is launched</em>,
     * and the halt is still the reported outcome.
     */
    @Nested
    @DisplayName("a restart of a halted instance launches nothing (P5-01)")
    class RestartOfAHaltedInstance {

        @Test
        @DisplayName("the harness really does reproduce a restart: context carried forward, completed step "
                + "skipped")
        void theHarnessReproducesARestart() {
            // Asserted first and on its own, because every other test in this group is worthless if the
            // harness quietly fails to restart - they would all pass by never reaching the second execution.
            final JobExecution first = run();
            assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(invocations.stream().map(Invocation::stage).toList()).hasSize(5);

            final int before = invocations.size();
            final JobExecution second = run();

            assertThat(second.getId())
                    .as("a restart is a new execution of the SAME instance")
                    .isNotEqualTo(first.getId());
            assertThat(second.getJobInstance().getInstanceId())
                    .isEqualTo(first.getJobInstance().getInstanceId());
            assertThat(second.getExecutionContext().containsKey(PIPELINE_RETURN_CODE_ENTRY))
                    .as("the job repository copies the previous context forward, so the entry is present "
                            + "before the first listener callback runs")
                    .isTrue();
            assertThat(invocations.size() - before)
                    .as("all five launcher steps completed on the first execution, so all five are skipped "
                            + "on the restart and no child job is launched a second time")
                    .isZero();
        }

        @Test
        @DisplayName("a POSTTRAN return code 8 halt survives the restart, and INTCALC never launches")
        void aReturnCodeEightHaltSurvivesTheRestart() {
            postTran.onExecute(execution -> {
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
            });

            final JobExecution first = run();
            assertThat(first.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1)).isEqualTo(8);
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("the first run halts at the gate after stage 1")
                    .containsExactly("POSTTRAN");

            final JobExecution restarted = run();

            assertThat(restarted.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .as("the persisted halt is authoritative and is NOT reset to zero by the listener")
                    .isEqualTo(8);
            assertThat(restarted.getStatus())
                    .as("the restart re-reads the halt at the first gate and fails there")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(restarted.getExecutionContext().getString(PIPELINE_OUTCOME_ENTRY, ""))
                    .isEqualTo("FAILED");
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("THE FINDING: INTCALC, COMBTRAN, CREASTMT and TRANREPT must not launch. Restarting "
                            + "through the halt reapplied interest and produced duplicate transaction "
                            + "identifiers")
                    .containsExactly("POSTTRAN");
        }

        @Test
        @DisplayName("an INTCALC abend at return code 12 survives the restart, and COMBTRAN never launches")
        void anAbendSurvivesTheRestart() {
            final FatalProcessingException abend = new FatalProcessingException("0999", "CBACT04C",
                    "UNEXPECTED FILE STATUS", "ABENDING PROGRAM");
            intCalc.onExecute(execution -> {
                execution.addFailureException(abend);
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
            });

            final JobExecution first = run();
            assertThat(first.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1)).isEqualTo(12);
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .containsExactly("POSTTRAN", "INTCALC");

            final JobExecution restarted = run();

            assertThat(restarted.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .as("an abend is carried forward exactly as a halt is")
                    .isEqualTo(12);
            assertThat(restarted.getExitStatus().getExitCode())
                    .as("and it is still reported as an abend rather than decaying into a plain failure")
                    .isEqualTo("ABEND");
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("THE FINDING: COMBTRAN must not launch. Restarting through the abend ran the combine "
                            + "stage without the pinned generation stage 2 never published")
                    .containsExactly("POSTTRAN", "INTCALC");
        }

        @Test
        @DisplayName("a stage's own recorded outcome halts the restart even if the aggregate is wiped")
        void aPersistedStageOutcomeHaltsTheRestartOnItsOwn() {
            postTran.onExecute(execution -> {
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
            });
            run();

            // The second, independent guard. The carried aggregate alone would be enough, but it is a single
            // entry that one stray writer - or one future listener - could lower, and the cost of getting this
            // wrong is data damage rather than a wrong number. So the gate also reads the per-stage entries,
            // which the stage runner writes and nothing else rewrites. Wiping the aggregate here proves that
            // second path carries the halt by itself.
            harnessRepository.onFirstStep(execution ->
                    execution.getExecutionContext().remove(PIPELINE_RETURN_CODE_ENTRY));

            final JobExecution restarted = run();

            assertThat(restarted.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .as("the gate rebuilds the aggregate from carddemo.pipeline.posttran.returnCode")
                    .isEqualTo(8);
            assertThat(restarted.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("still nothing downstream")
                    .containsExactly("POSTTRAN");
        }

        @Test
        @DisplayName("a clean run is not turned into a failure by being restarted")
        void aCleanRunIsNotTurnedIntoAFailureByARestart() {
            // The inverse assertion, and the one that would catch an over-broad fix. Making the persisted
            // aggregate authoritative must not make a restart pessimistic: a first run that completed cleanly
            // carries a zero forward, and a zero is a zero.
            final JobExecution first = run();
            assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            final JobExecution restarted = run();

            assertThat(restarted.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .as("nothing to carry but a clean run")
                    .isZero();
            assertThat(restarted.getStatus())
                    .as("every step already completed, so the restart walks the gates and ends cleanly")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(restarted.getExitStatus().getExitCode())
                    .as("the framework's own label for a restart in which every step was skipped:"
                            + " AbstractJob.execute publishes NOOP when the new execution registered no step"
                            + " of its own. Worth pinning rather than smoothing over, because it is the"
                            + " clearest possible signal that a restart of a completed instance re-ran"
                            + " nothing - which is the whole point of the finding")
                    .isEqualTo(ExitStatus.NOOP.getExitCode());
            assertThat(restarted.getExitStatus().getExitDescription())
                    .contains("All steps already completed");
        }

        @Test
        @DisplayName("the first execution of an instance still seeds a zero, so the first gate reads a value")
        void aFirstExecutionStillSeedsTheAggregate() {
            // The seed is now conditional, so the condition is asserted from both sides. On a first execution
            // the entry is absent and must be written, or the gate after stage 1 would read a default rather
            // than a recorded value on a pipeline whose stage 1 somehow recorded nothing.
            final JobExecution execution = run();

            assertThat(execution.getExecutionContext().containsKey(PIPELINE_RETURN_CODE_ENTRY)).isTrue();
            assertThat(execution.getExecutionContext().getString(PIPELINE_OUTCOME_ENTRY, ""))
                    .as("the outcome label is seeded on the same condition, so the two entries agree")
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        }
    }

    /** The precondition stage 3 imposes on the input substrate. */
    @Nested
    @DisplayName("the combine stage refuses the relational substrate (M-01)")
    class SubstratePrecondition {

        @Test
        @DisplayName("a pipeline configured for the relation refuses stage 3 rather than loading from it")
        void theRelationalSubstrateIsRefused() {
            // FINDING M-01, severity High. carddemo.batch.combined-transaction-reader.source defaults to
            // `repository`, in which case stage 3's first leg IS the transaction relation that
            // app/jcl/COMBTRAN.jcl:L41-L48 then loads into - so the stage reads the rows it is about to
            // re-insert. This was previously documented as an operator prerequisite and left unchecked,
            // which meant the pipeline's default path was the wrong one.
            final JobRepository harnessRepository = new HarnessJobRepository();
            final TaskExecutorJobLauncher pipelineLauncher = new TaskExecutorJobLauncher();
            pipelineLauncher.setJobRepository(harnessRepository);
            pipelineLauncher.setTaskExecutor(new SyncTaskExecutor());
            final BatchPipelineOrchestrator relational = new BatchPipelineOrchestrator(
                    postTran, intCalc, combTran, creaStmt, tranRept, harnessRepository,
                    new HarnessJobLauncher(pipelineLauncher), new RecordingTransactionManager(),
                    PIPELINE_NAME, "repository");
            final Step postTranStep = relational.batchPipelinePostTranStep();
            final Step intCalcStep = relational.batchPipelineIntCalcStep();
            final Step combTranStep = relational.batchPipelineCombTranStep();
            final Step creaStmtStep = relational.batchPipelineCreaStmtStep();
            final Step tranReptStep = relational.batchPipelineTranReptStep();
            final Flow relationalFlow = relational.batchPipelineFlow(postTranStep, intCalcStep, combTranStep,
                    relational.batchPipelineStatementReportSplitFlow(creaStmtStep, tranReptStep));

            final JobExecution execution = relational.launchPipeline(
                    relational.batchPipelineJob(relationalFlow),
                    VALID_PARM_DATE, VALID_START_DATE, VALID_END_DATE);

            assertThat(execution.getStatus())
                    .as("a refused precondition must stop the stream, not be logged and continued")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(invocations)
                    .as("stage 3 must never have been launched: the point is that the wrong data is not "
                            + "read, so refusing after the launch would be too late. Launched: %s",
                            invocations)
                    .noneSatisfy(invocation -> assertThat(invocation.stage()).isEqualTo("COMBTRAN"));
            assertThat(execution.getAllFailureExceptions())
                    .anySatisfy(failure -> assertThat(failure)
                            .isInstanceOf(ValidationException.class)
                            .hasMessageContaining("combined-transaction-reader.source")
                            .hasMessageContaining("object-storage"));
        }

        @Test
        @DisplayName("the object-storage substrate runs the stage, so the guard admits the correct value")
        void theObjectStorageSubstrateIsAdmitted() {
            intCalc.onExecute(execution -> publishNothing(execution));

            final JobExecution execution = run();

            assertThat(invocations)
                    .as("the guard must not refuse the value a correct deployment declares")
                    .anySatisfy(invocation -> assertThat(invocation.stage()).isEqualTo("COMBTRAN"));
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        /**
         * Publishes an empty {@code SYSTRAN} generation, as a zero-rate interest run would.
         *
         * @param execution stage 2's execution
         */
        private void publishNothing(final JobExecution execution) {
            execution.getExecutionContext().putLong(
                    InterestCalculationJob.SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY, 0L);
        }
    }

    /**
     * The return codes that are <em>not</em> 0, 4, 8 or 12, and the two malformed states of the entry.
     *
     * <p><strong>Why this exists.</strong> The four canonical codes are what a stage produces, so the suite
     * above covers everything the stage runner can emit. The aggregate is not a stage's code, though: it is a
     * {@link Math#max} accumulated across five stages in an execution context that survives a restart, so a
     * value between two canonical codes - or of the wrong type entirely - is reachable and had no asserted
     * behaviour. Production banded such values silently, which is a defensible policy and was an
     * <em>undocumented</em> one; a reader could not tell an intended band from an accident of comparison
     * operators, and nothing would have noticed if the operators changed.
     *
     * <p>The policy is now stated on {@code gateFor} and {@code readAggregateReturnCode} and asserted here
     * twice over: directly on the mapping, for every boundary and every gap value, and again through a real
     * flow execution, because a mapping that is right in isolation is worth little if the gate reads the entry
     * differently from the way this test reads it.
     *
     * <p><strong>Why reflection.</strong> The three methods are private static, and they should stay private:
     * they are internal to one class's decision-making and exposing them for a test would widen the published
     * surface to make it observable, which Rule 1 clause B's separation-of-concerns clause weighs against. The
     * flow-execution assertions below are the ones that prove the policy is wired; the reflective ones prove
     * it is total, which no reachable flow can, because a stage cannot produce a 5.
     */
    @Nested
    @DisplayName("the return codes between and beyond the four, and a malformed aggregate entry")
    class UnknownAndMalformedReturnCodes {

        @ParameterizedTest(name = "return code {0} gates as {1} and publishes {2}")
        @CsvSource({
            // Below the reject band, negatives included: nothing has gone wrong.
            "-1,PROCEED,COMPLETED",
            "0,PROCEED,COMPLETED",
            "1,PROCEED,COMPLETED",
            "3,PROCEED,COMPLETED",
            // The reject band. 4 is the source's own value; 5 to 7 are the gap above it.
            "4,PROCEED WITH REJECTS,COMPLETED WITH REJECTS",
            "5,PROCEED WITH REJECTS,COMPLETED WITH REJECTS",
            "7,PROCEED WITH REJECTS,COMPLETED WITH REJECTS",
            // The halt band. 8 is the source's own value; 9 to 11 are the gap above it.
            "8,HALT,FAILED",
            "9,HALT,FAILED",
            "11,HALT,FAILED",
            // The abend band is open-ended upward: a code worse than the worst named one is not better.
            "12,ABEND,ABEND",
            "13,ABEND,ABEND",
            "2147483647,ABEND,ABEND",
        })
        @DisplayName("every integer bands monotonically, and the gate and the exit status always agree")
        void everyIntegerBandsMonotonically(final int returnCode, final String gate, final String outcome) {
            assertThat(gateFor(returnCode))
                    .as("""
                            The bands are thresholds, exactly as COND=(0,NE) tests a threshold rather than \
                            enumerating the codes a program is known to set. A value in a gap keeps the \
                            meaning of the named code it has reached and no more, so 5 is still "completed \
                            with rejects" and 9 is still a failure rather than an abend.""")
                    .isEqualTo(gate);
            assertThat(outcomeFor(returnCode))
                    .as("and the published exit status is banded on the same thresholds, so the gate the "
                            + "flow transitions on can never disagree with the status the run reports")
                    .isEqualTo(outcome);
        }

        @Test
        @DisplayName("a seeded 5 proceeds with rejects through the real flow, and every stage still runs")
        void aSeededFiveProceedsWithRejects() {
            final JobExecution execution = runWithSeededAggregate(Integer.valueOf(5));

            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("5 is in the reject band, and a reject does not stop the stream")
                    .hasSize(5);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED WITH REJECTS");
            assertThat(execution.getExecutionContext().getInt(PIPELINE_RETURN_CODE_ENTRY, -1))
                    .as("the seeded value survives: Math.max over the stages' zeros leaves it unchanged, so "
                            + "the aggregate is not quietly rounded to 4")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("a seeded 9 halts the real flow at the first gate, exactly as an 8 would")
        void aSeededNineHalts() {
            final JobExecution execution = runWithSeededAggregate(Integer.valueOf(9));

            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .as("the gate after stage 1 reads 9, bands it as HALT, and no downstream stage is given "
                            + "a step - a bypass, not a skipped status")
                    .containsExactly("POSTTRAN");
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
            assertThat(execution.getExecutionContext().getString(PIPELINE_OUTCOME_ENTRY, ""))
                    .isEqualTo("FAILED");
        }

        @Test
        @DisplayName("a seeded 13 is reported as an abend, not as a plain failure")
        void aSeededThirteenAbends() {
            final JobExecution execution = runWithSeededAggregate(Integer.valueOf(13));

            assertThat(invocations.stream().map(Invocation::stage).toList())
                    .containsExactly("POSTTRAN");
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode())
                    .as("13 is above the abend floor, so it keeps the abend label that distinguishes 12 "
                            + "from 8 rather than being flattened into a failure")
                    .isEqualTo("ABEND");
        }

        @Test
        @DisplayName("a non-integer aggregate entry abends with the key named, rather than reading as clean")
        void aCorruptAggregateEntryAbends() {
            final JobExecution execution = runWithSeededAggregate("not-an-integer");

            assertThat(execution.getStatus())
                    .as("""
                            Only this class writes that entry, so a value of another type means the context \
                            is not the one this run built - a manipulated restart, or a second writer. The \
                            two alternatives are both wrong: getInt raises a bare ClassCastException from \
                            inside a listener, naming neither the key nor the pipeline, and defaulting to 0 \
                            would report a clean run over a corrupted context.""")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .as("the abend carries the corpus's own abend code and names the offending entry")
                    .anySatisfy(failure -> {
                        assertThat(failure).isInstanceOf(FatalProcessingException.class);
                        assertThat(((FatalProcessingException) failure).getAbendReason())
                                .isEqualTo("CORRUPT PIPELINE RETURN CODE");
                        assertThat(failure.getMessage())
                                .contains(PIPELINE_RETURN_CODE_ENTRY)
                                .contains("java.lang.String");
                    });
        }
    }

    /** The generation handoff between {@code SYSTRAN(+1)} and {@code SYSTRAN(0)}. */
    @Nested
    @DisplayName("the generation handoff is published and then checked, never re-resolved")
    class GenerationHandoff {

        @Test
        @DisplayName("the generation stage 2 created is handed to stage 3 before it launches, and verifies")
        void thePinnedGenerationIsHandedOverBeforeLaunchAndVerified() {
            intCalc.onExecute(execution -> publishKeys(execution, SYSTRAN_KEY_ONE, SYSTRAN_KEY_TWO));
            combTran.onExecute(execution -> recordResolvedKey(execution, SYSTRAN_KEY_ONE));

            final JobExecution execution = run();

            // The pre-launch contract, asserted where it is observable: stage 3 was launched carrying the
            // exact generation, so it could not have resolved a lexical-greatest of its own. This is what
            // distinguishes a contract from the after-the-fact comparison below it.
            assertThat(parametersFor("COMBTRAN")
                    .getString(CombinedTransactionReader.SYSTRAN_GENERATION_JOB_PARAMETER))
                    .as("stage 3 receives the exact generation as a job parameter, before it runs")
                    .isEqualTo(SYSTRAN_GENERATION);
            assertThat(parametersFor("POSTTRAN").getParameters())
                    .as("no other stage receives it, because no other stage reads SYSTRAN")
                    .doesNotContainKey(CombinedTransactionReader.SYSTRAN_GENERATION_JOB_PARAMETER);

            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_GENERATION_ENTRY, ""))
                    .as("the pinned value is the generation stage 2 created, not one of its objects: stage 2 "
                            + "emits one object per chunk, so a single key is a fraction of SYSTRAN(0)")
                    .isEqualTo(SYSTRAN_GENERATION);
            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_HANDOFF_ENTRY, ""))
                    .isEqualTo(HANDOFF_VERIFIED);
            assertThat(execution.getExecutionContext()
                    .getInt(PIPELINE_SYSTRAN_KEY_COUNT_ENTRY, -1))
                    .isEqualTo(2);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("stage 3 reading a different generation is an abend that names both")
        void aDifferentGenerationIsAnAbend() {
            intCalc.onExecute(execution -> publishKeys(execution, SYSTRAN_KEY_ONE));
            combTran.onExecute(execution -> recordResolvedKey(execution, OTHER_GENERATION_KEY));

            final JobExecution execution = run();

            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_HANDOFF_ENTRY, ""))
                    .isEqualTo(HANDOFF_MISMATCH);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("ABEND");
            assertThat(execution.getAllFailureExceptions())
                    .anyMatch(failure -> failure instanceof FatalProcessingException
                            && failure.getMessage().contains(SYSTRAN_GENERATION)
                            && failure.getMessage().contains(OTHER_GENERATION_KEY));
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

        @Test
        @DisplayName("stage 3 is HANDED the pinned generation, as an identifying parameter")
        void theCombineStageIsHandedThePinnedGeneration() {
            // FINDING C-04, severity Blocker. Pinning the key in the pipeline's own context and comparing
            // afterwards leaves stage 3 free to resolve "newest generation" for itself in between, which a
            // concurrent interest run redirects. The key is therefore given to stage 3 before it opens its
            // input - the object-store equivalent of the catalogue resolving SYSTRAN(0) atomically at open.
            final AtomicReference<JobParameters> handed = new AtomicReference<>();
            intCalc.onExecute(execution -> publishKeys(execution, SYSTRAN_KEY_ONE, SYSTRAN_KEY_TWO));
            combTran.onExecute(execution -> {
                handed.set(execution.getJobParameters());
                recordResolvedKey(execution, SYSTRAN_KEY_TWO);
            });

            run();

            final JobParameters parameters = handed.get();
            assertThat(parameters).as("stage 3 must have been launched").isNotNull();
            assertThat(parameters.getString(SYSTRAN_KEY_JOB_PARAMETER))
                    .as("the GENERATION stage 2 catalogued, handed over rather than re-derived. Not one of "
                            + "its object keys: stage 2 emits one object per chunk under one generation "
                            + "prefix, so pinning a key would hand stage 3 a fraction of SYSTRAN(0) and "
                            + "report success")
                    .isEqualTo(SYSTRAN_GENERATION);
            assertThat(parameters.getParameters().get(SYSTRAN_KEY_JOB_PARAMETER).isIdentifying())
                    .as("IDENTIFYING, and deliberately so: two runs over different interest generations are "
                            + "two distinct stage-3 instances, so the generation belongs in the identity. The "
                            + "pipeline's own instance is keyed on its own parameters, which is what keeps "
                            + "one pipeline run meaning one run of each stage")
                    .isTrue();
        }

        @Test
        @DisplayName("a stage that published no generation prefix at all is recorded, not fabricated")
        void anAbsentPrefixIsRecordedRatherThanFabricated() {
            final AtomicReference<JobParameters> handed = new AtomicReference<>();
            intCalc.onExecute(execution -> publishKeys(execution));
            combTran.onExecute(execution -> handed.set(execution.getJobParameters()));

            final JobExecution execution = run();

            // Distinguish the two absences. An EMPTY generation is still a generation: stage 2 publishes the
            // prefix it owns even when every disclosure rate was zero, which is the counterpart of
            // DISP=(NEW,CATLG,DELETE) cataloguing an empty dataset, and that prefix is pinned and read as zero
            // records. This case is the other one - stage 2 published no prefix whatsoever - and the pipeline
            // records it rather than inventing a value it did not observe.
            assertThat(handed.get().getParameters())
                    .as("nothing is fabricated: the pipeline saw no generation, so it names none")
                    .doesNotContainKey(SYSTRAN_KEY_JOB_PARAMETER);
            assertThat(execution.getExecutionContext().getString(PIPELINE_SYSTRAN_GENERATION_ENTRY, ""))
                    .as("and it is recorded where an operator reads the run, so the omission is evidence "
                            + "rather than silence. The reader then means (0) as a standalone submission "
                            + "does - the greatest existing generation - which the run's own warning states")
                    .isEqualTo(HANDOFF_ABSENT);
        }

        @Test
        @DisplayName("only stage 3 is handed the key; the other stages' parameters are untouched")
        void onlyTheCombineStageIsHandedTheKey() {
            final AtomicReference<JobParameters> postTranParameters = new AtomicReference<>();
            final AtomicReference<JobParameters> intCalcParameters = new AtomicReference<>();
            postTran.onExecute(execution -> postTranParameters.set(execution.getJobParameters()));
            intCalc.onExecute(execution -> {
                intCalcParameters.set(execution.getJobParameters());
                publishKeys(execution, SYSTRAN_KEY_ONE);
            });
            combTran.onExecute(execution -> recordResolvedKey(execution, SYSTRAN_KEY_ONE));

            run();

            assertThat(postTranParameters.get().getParameters())
                    .as("stage 1 runs before the generation exists, so a key here would be meaningless")
                    .doesNotContainKey(SYSTRAN_KEY_JOB_PARAMETER);
            assertThat(intCalcParameters.get().getParameters())
                    .as("stage 2 CREATES the generation; being told which one to read would be circular")
                    .doesNotContainKey(SYSTRAN_KEY_JOB_PARAMETER);
        }

        @Test
        @DisplayName("stage 3 reading a generation it was told did not exist is an abend, not a pass")
        void aSubstitutedGenerationUnderAnInstructedAbsenceIsAnAbend() {
            // The arm that used to be accepted unconditionally. Stage 2 catalogued nothing, so stage 3 was
            // told to read nothing; a key here means it read an EARLIER run's generation, which would load
            // transactions this pipeline did not generate. Accepting it made the one case where a stale
            // generation could be read the one case that was never checked.
            intCalc.onExecute(execution -> publishKeys(execution));
            combTran.onExecute(execution -> recordResolvedKey(execution, SYSTRAN_KEY_ONE));

            final JobExecution execution = run();

            assertThat(execution.getExecutionContext()
                    .getString(PIPELINE_SYSTRAN_HANDOFF_ENTRY, ""))
                    .isEqualTo(HANDOFF_MISMATCH);
            assertThat(execution.getAllFailureExceptions())
                    .anySatisfy(failure -> assertThat(failure)
                            .isInstanceOf(FatalProcessingException.class));
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
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
            if (keys.length > 0) {
                // Stage 2 publishes the generation it owns alongside the keys, and the generation is what the
                // pipeline hands on: one generation, one dataset, however many chunk objects realise it.
                execution.getExecutionContext().putString(
                        InterestCalculationJob.SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY, SYSTRAN_GENERATION);
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
                    new RefusingJobLauncher(cause), new RecordingTransactionManager(), PIPELINE_NAME,
                    READER_SOURCE_OBJECT_STORAGE);
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
     * How a job is started, now that this class declares no runner of its own.
     *
     * <p>Finding CFG-001, severity High. No property-gated
     * {@code ApplicationRunner} named {@code carddemo.batch.launch} may be declared here, and no assertion
     * here may exercise one - that each of the six jobs can be submitted, that an unknown name is refused,
     * that a web context is warned about. All of that launches a job from inside
     * {@code SpringApplication.run}, which is a boot-time launch
     * however narrowly the bean is gated, and the contract for this class is that nothing here runs on
     * startup.
     *
     * <p>The problem such a runner would address is real - {@code launchPipeline} on its own has no
     * {@code src/main} caller,
     * so a deployed application could start none of its six jobs - and it is solved by the framework's
     * own {@code JobLauncherApplicationRunner} rather than by an authored one. These assertions pin the three
     * properties that makes true: no runner is declared here, the six job names an operator submits are the
     * ones this application answers to, and nothing on the launch path terminates the process.
     */
    @Nested
    @DisplayName("no runner is declared here; the framework's own launcher is the operator submission path")
    class OperatorSubmission {

        @Test
        @DisplayName("this class declares no ApplicationRunner and no CommandLineRunner")
        void noRunnerIsDeclared() {
            assertThat(Stream.of(BatchPipelineOrchestrator.class.getDeclaredMethods())
                    .filter(method -> ApplicationRunner.class.isAssignableFrom(method.getReturnType())
                            || CommandLineRunner.class.isAssignableFrom(method.getReturnType()))
                    .map(Method::getName))
                    .as("a runner declared here launches during context refresh, which is precisely what "
                            + "spring.batch.job.enabled=false exists to prevent; an operator submission goes "
                            + "through the framework's own runner, switched on for that one process")
                    .isEmpty();
        }

        @Test
        @DisplayName("no carddemo.batch.launch property survives anywhere, in code or in any profile")
        void theLaunchPropertyIsGone() throws Exception {
            final List<Path> files = new ArrayList<>(List.of(
                    Path.of("src", "main", "java", "com", "cardemo", "batch", "jobs",
                            "BatchPipelineOrchestrator.java"),
                    Path.of("src", "main", "java", "com", "cardemo", "config", "BatchConfig.java")));
            for (final String profile : List.of("application.yml", "application-local.yml",
                    "application-test.yml", "application-prod.yml")) {
                files.add(Path.of("src", "main", "resources", profile));
            }

            for (final Path file : files) {
                final String live = Files.readAllLines(file).stream()
                        .map(String::trim)
                        .filter(line -> !line.startsWith("//") && !line.startsWith("#")
                                && !line.startsWith("*") && !line.startsWith("/*"))
                        .collect(Collectors.joining("\n"));
                assertThat(live)
                        .as("%s still reads or publishes the removed launch property, so a key that binds "
                                + "nothing would be documented as though it did", file)
                        .doesNotContain("carddemo.batch.launch");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"CARDDEMO-PIPELINE", "POSTTRAN", "INTCALC", "COMBTRAN", "CREASTMT",
            "TRANREPT"})
        @DisplayName("each submittable job name is the default the profile publishes and the code binds")
        void eachSubmittableJobNameIsPublished(final String jobName) throws Exception {
            // spring.batch.job.name is matched against Job.getName(), not against the bean name - verified
            // against the compiled JobLauncherApplicationRunner of Spring Boot 3.5.11. Every job in this
            // stream is named after the JCL member it replaces, so these six strings are what an operator
            // actually types. A default that drifted from the published one would make the documented
            // submission command resolve nothing and, because the runner treats an unmatched name as "skip",
            // it would do so silently.
            final String pipeline = Files.readString(Path.of("src", "main", "java", "com", "cardemo", "batch",
                    "jobs", "BatchPipelineOrchestrator.java"));
            final String profile = Files.readString(
                    Path.of("src", "main", "resources", "application.yml"));

            assertThat(pipeline + profile)
                    .as("%s must appear as a default in the code that binds it or in the profile that "
                            + "publishes it", jobName)
                    .contains(jobName);
        }

        @Test
        @DisplayName("the submission path terminates no process: no System.exit and no shutdown hook")
        void theSubmissionPathTerminatesNoProcess() throws Exception {
            // A launcher that called System.exit would skip context close, abandoning the connection pool and
            // the final metrics flush. The outcome travels out as an exception instead, which Spring Boot
            // turns into a non-zero exit status by itself.
            // Comment lines are excluded, because the class documents the prohibition in prose and a plain
            // substring search over the whole file would match the documentation rather than a call.
            final String code = Files.readAllLines(
                            Path.of("src", "main", "java", "com", "cardemo", "batch", "jobs",
                                    "BatchPipelineOrchestrator.java")).stream()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("//") && !line.startsWith("*")
                            && !line.startsWith("/*"))
                    .collect(Collectors.joining("\n"));

            assertThat(code).doesNotContain("System.exit").doesNotContain("addShutdownHook");
        }

        /**
         * Documents in which a launch command may be published, and which are therefore checked here.
         *
         * <p>{@code docs/project-guide.md} is deliberately absent: it is retained unchanged as prior-run
         * evidence, so a stale command inside it is a record of what a previous attempt published rather than
         * an instruction to a reader of this one.
         */
        private static final List<String> LAUNCH_DOCUMENTING_FILES = List.of(
                "README.md",
                "docs/onboarding-guide.md",
                "docs/architecture-before-after.md",
                "docs/api-contracts.md",
                "docs/validation-gates.md",
                "docs/technical-specifications.md",
                "docs/executive-presentation.html");

        /**
         * The command-line spelling of the withdrawn property, which only ever appears inside a command.
         *
         * <p>The bare key is <b>not</b> forbidden in prose, and must not be: a document that records the
         * withdrawal has to be able to name what was withdrawn. The two leading hyphens are what separate the
         * two cases, because a Spring option prefix has no use in a sentence.
         */
        private static final String WITHDRAWN_LAUNCH_OPTION = "--carddemo.batch.launch";

        @Test
        @DisplayName("no published document offers a launch command built on the withdrawn property")
        void noPublishedCommandUsesTheWithdrawnLaunchProperty() throws Exception {
            // Why this reads the documents and not only the code. theLaunchPropertyIsGone above already keeps
            // the property out of src/main and out of every profile, and it passed for the whole time three
            // commands built on that property stood in the onboarding guide as the only published way to run a
            // batch job. Removing a property closes the code path; it does not close the instruction, and the
            // instruction is what a reader executes. Each of those three commands started a web server,
            // launched nothing and reported no error, so following the documentation produced a silent
            // non-result - the failure mode this assertion exists to make loud.
            for (final String file : LAUNCH_DOCUMENTING_FILES) {
                final Path path = Path.of(file);
                assertThat(path).as("%s is a document this assertion claims to cover", file).isRegularFile();
                assertThat(Files.readString(path))
                        .as("%s publishes a command built on a property that binds nothing, so a reader who "
                                + "copies it starts a web server and launches no job", file)
                        .doesNotContain(WITHDRAWN_LAUNCH_OPTION);
            }
        }

        @Test
        @DisplayName("the onboarding guide publishes the framework submission command in full")
        void theOnboardingGuidePublishesTheFrameworkSubmissionCommand() throws Exception {
            // The negative above is only half of the finding. "No wrong command is published" is also true of a
            // document that publishes no command at all, which is the state the guide would have been left in
            // by deleting the three inert ones - and a reader with no command is no better off than a reader
            // with a broken one. These are the four fragments that make a submission work, each of which fails
            // silently or misleadingly when omitted: the runner switch, the web-context suppression that lets
            // the process end, the job name, and a job parameter in its bare form.
            //
            // The bean-name and --prefixed spellings are deliberately NOT forbidden in this file: the guide
            // documents both as measured failure modes, and an assertion that banned them would fire on the
            // very passage that warns against them.
            final String guide = Files.readString(Path.of("docs", "onboarding-guide.md"));

            assertThat(guide)
                    .as("the guide must publish the runner switch, the web-context suppression and a real "
                            + "job name together, because omitting any one of them launches nothing")
                    .contains("--spring.batch.job.enabled=true")
                    .contains("--spring.main.web-application-type=none")
                    .contains("--spring.batch.job.name=POSTTRAN")
                    .contains("parmDate=");

            // A first draft of this test went on to forbid the option-prefixed parameter spelling outright,
            // and it would have failed on the guide's own warning table, which prints that exact spelling as
            // the thing not to type. Recorded because the mistake is the general one: an assertion written
            // over prose cannot ban a string the prose has to quote. What is checkable instead is that the
            // working form is present, which the assertion above does, and that every place the option form
            // appears is a warning - which is a judgement, and belongs to review rather than to a matcher.
        }

        @ParameterizedTest
        @ValueSource(strings = {"CARDDEMO-PIPELINE", "POSTTRAN", "INTCALC", "COMBTRAN", "CREASTMT",
            "TRANREPT"})
        @DisplayName("each submittable job name is published to the operator who has to type it")
        void eachSubmittableJobNameIsPublishedToTheOperator(final String jobName) throws Exception {
            // eachSubmittableJobNameIsPublished above pins each name against the code and the profile that
            // declare it. That is necessary and not sufficient: a name that only the code knows is a name the
            // operator has to reverse-engineer, and the reproduction that opened this finding was exactly a
            // reader guessing a bean name because no document offered the real one.
            assertThat(Files.readString(Path.of("docs", "onboarding-guide.md")))
                    .as("%s is submittable, so the guide's launch section has to name it", jobName)
                    .contains(jobName);
        }
    }

    /**
     * One stage launch, with everything the assertions need about it.
     *
     * @param stage the stage's display name
     * @param thread the name of the thread the stage was launched on
     * @param diagnostic the diagnostic context as it stood on that thread, never {@code null}
     */
    private record Invocation(String stage, String thread, Map<String, String> diagnostic,
            JobParameters parameters) {
    }

    /**
     * The parameters one stage was actually launched with.
     *
     * <p>This is what makes the pre-launch handoff of the {@code SYSTRAN} generation observable: the parameter
     * set is fixed before the child's first step runs, so asserting on it asserts a contract rather than an
     * after-the-fact comparison.
     *
     * @param stage the stage's display name
     * @return that stage's launch parameters, never {@code null}
     */
    private JobParameters parametersFor(final String stage) {
        return invocations.stream()
                .filter(invocation -> stage.equals(invocation.stage()))
                .map(Invocation::parameters)
                .findFirst()
                .orElseThrow(() -> new AssertionError("stage " + stage + " was never launched"));
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
                    diagnostic == null ? new HashMap<>() : new HashMap<>(diagnostic), parameters));
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
         * The most recent step execution per instance identifier and step name.
         *
         * <p>Held so that {@link #getLastStepExecution(JobInstance, String)} can answer, which is what makes a
         * <strong>restart</strong> reachable in this tier at all. Without it every step looks brand new on a
         * second execution and {@code SimpleStepHandler} re-runs all five, which is the opposite of the
         * production behaviour the restart tests exist to pin: a completed launcher step is skipped, so the
         * stage runner never runs again and never re-records the stage's return code.
         */
        private final Map<String, StepExecution> lastStepExecutions = new LinkedHashMap<>();

        /**
         * Applied once, to the pipeline execution, as its first step execution is registered.
         *
         * <p>The seam exists for one reason and its position is exact. A test that needs the aggregate
         * return-code entry pre-populated has no execution object to write to before the run - the launcher
         * creates it - and cannot seed from a stage stub, which is handed the child execution and never the
         * pipeline's. Registering a step execution is the first point at which the pipeline execution is
         * reachable, and it happens before the stage runner reads the entry. Composing a job around the flow
         * with an extra listener would also work and was rejected: it drops the pipeline's own outcome
         * listener, so the test would assert against a topology production does not build.
         *
         * <p>Note that the seam no longer <em>has</em> to sit after {@code beforeJob} to survive it. That
         * listener used to zero the entry unconditionally, so anything written earlier was discarded; it now
         * seeds only when the entry is absent, because on a restart the carried-forward value is the
         * authoritative one (finding P5-01). The position is unchanged all the same, since it is also the
         * earliest reachable point.
         */
        private Consumer<JobExecution> onFirstStep = execution -> { };

        /** Whether {@link #onFirstStep} has already fired, so it seeds once rather than once per step. */
        private boolean firstStepSeen;

        /**
         * Registers the hook applied to the pipeline execution as its first step is registered.
         *
         * @param hook the hook; must not be {@code null}
         */
        void onFirstStep(final Consumer<JobExecution> hook) {
            onFirstStep = hook;
            firstStepSeen = false;
        }

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
            final JobExecution previous = executions.get(key);
            final JobInstance instance = instances.containsKey(key)
                    ? instances.get(key)
                    : createJobInstance(jobName, jobParameters);
            final JobExecution execution = new JobExecution(
                    instance, Long.valueOf(identifiers.incrementAndGet()), jobParameters);
            if (previous != null) {
                // What SimpleJobRepository.createJobExecution does on a restart, and the fact the whole of
                // finding P5-01 turns on: the previous execution's context is copied onto the new one, so
                // everything the earlier run recorded - including the halt - is present before the first
                // listener callback fires. Copied rather than shared, exactly as the framework copies it, so
                // the two executions cannot alias one context.
                execution.setExecutionContext(new ExecutionContext(previous.getExecutionContext()));
            }
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
            lastStepExecutions.put(stepKeyOf(stepExecution.getJobExecution().getJobInstance(),
                    stepExecution.getStepName()), stepExecution);
            if (!firstStepSeen) {
                firstStepSeen = true;
                onFirstStep.accept(stepExecution.getJobExecution());
            }
        }

        /**
         * The key one step of one instance is remembered under.
         *
         * @param jobInstance the instance the step belongs to
         * @param stepName the step name
         * @return the key
         */
        private static String stepKeyOf(final JobInstance jobInstance, final String stepName) {
            return (jobInstance == null ? "0" : Long.toString(jobInstance.getInstanceId())) + '|' + stepName;
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

            return lastStepExecutions.get(stepKeyOf(jobInstance, stepName));
        }

        @Override
        public synchronized long getStepExecutionCount(final JobInstance jobInstance,
                final String stepName) {

            // Kept at zero deliberately. SimpleStepHandler.shouldStart compares this against the step's start
            // limit and raises StartLimitExceededException when the count has reached it, and no test here is
            // about the start limit. Answering zero means the limit never interferes with the restart tests
            // while getLastStepExecution above supplies the completed-step skip they do depend on.
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
