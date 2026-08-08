/*
 ******************************************************************************
 * Program     : SuccessfulPipelineRunTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 integration test (Failsafe tier)
 * Function    : Drives the whole five-stage batch stream to a SUCCESSFUL
 *               completion - all three sequential stages plus both branches of
 *               the parallel split - and asserts the outcome of each.
 * Source      : app/jcl/POSTTRAN.jcl (stage 1), app/jcl/INTCALC.jcl (stage 2),
 *               app/jcl/COMBTRAN.jcl (stage 3), app/jcl/CREASTMT.JCL and
 *               app/proc/TRANREPT.prc (the stage 4 split), and
 *               app/cbl/CBTRN02C.cbl:L229-L231 for the return-code contract
 ******************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 ******************************************************************************/
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole batch stream, run once, to a successful end - every sequential stage completed and both branches of
 * the parallel split completed.
 *
 * <h2>What it does and why it is a separate suite</h2>
 *
 * <p>{@code BatchPipelineOrchestratorTest} owns the stream's composition, its gating and its failure paths, and
 * every launch it makes ends {@link BatchStatus#FAILED} at stage 3. That is not a defect in that suite - it is
 * the faithful outcome of the configuration it runs under, and its test 5 asserts the halt deliberately. But it
 * left one thing unasserted anywhere in the tree: that the stream can reach the end at all. Neither branch of
 * the split had ever been observed completing through the pipeline, and a topology that could enter both
 * branches but never finish either would have passed every test in the tree.
 *
 * <p>This suite closes that. It is separate rather than nested because the one configuration change it needs -
 * see below - is bound by {@code @Value} at bean construction, so it requires its own application context.
 *
 * <h2>Why stage 3 halts in the sibling suite and completes here</h2>
 *
 * <p>{@code app/jcl/COMBTRAN.jcl:L22-L37} sorts a <b>concatenated</b> {@code SORTIN} of two DDs -
 * {@code TRANSACT.BKUP(0)} first, then {@code SYSTRAN(0)} - and {@code :L41} loads the result into the keyed
 * transaction cluster. Two facts about the legacy side decide what a faithful run looks like:
 *
 * <ul>
 *   <li><b>{@code TRANSACT.BKUP} is produced by a stage that runs later.</b>
 *       {@code app/proc/TRANREPT.prc:STEP01R} backs the cluster up, and that is stage 5. On a cold first run of
 *       the stream the generation therefore does not exist - absent <em>by construction</em>, not by error.
 *       {@code CombinedTransactionReader} treats an absent or empty generation on either leg as a successful
 *       empty read, symmetrically, which is exactly right for this case.</li>
 *   <li><b>{@code app/jcl/TRANFILE.jcl:L49-L58} defines the cluster without {@code REUSE}</b>, so an
 *       {@code IDCAMS REPRO} into it appends rather than replaces. Re-loading transactions that are already
 *       there is a duplicate-key failure on the legacy side too. That is what the sibling suite observes, and
 *       it must keep observing it.</li>
 * </ul>
 *
 * <p>The difference between the two runs is one property. The reader's default substrate is
 * {@code repository}, where the backup leg is the ordered transaction relation itself - so it re-reads the rows
 * stage 1 has just committed and the load refuses them. This suite selects {@code object-storage}, where the
 * backup leg is a generation object that a cold run has not yet produced. Both are faithful readings of
 * different stream states, and both are now asserted: the sibling suite covers the re-load refusal, this suite
 * covers the cold first run.
 *
 * <p><b>Nothing is stubbed, skipped or relaxed to achieve the success.</b> The production job bean is launched
 * with the production parameters; all five stages do their real work; the interest generation stage 2 writes is
 * the one stage 3 consumes.
 *
 * <h2>The concurrency defect this suite made visible, and the fix it verifies</h2>
 *
 * <p>{@code spring.batch.jdbc.isolation-level-for-create} is {@code SERIALIZABLE}, which is what stops two
 * launches of one job instance from both believing they created it. Its cost is that PostgreSQL may abort one of
 * two <em>genuinely distinct</em> concurrent creations with SQLSTATE {@code 40001}, and the split creates two
 * distinct child executions at the same instant by design. The sibling suite recorded the consequence in prose:
 * across repeated measured runs the losing branch varied, so a completion assertion "would fail roughly two
 * runs in three".
 *
 * <p>That was a real defect in the orchestrator, not a property of the test.
 * {@code BatchPipelineOrchestrator.launchStage} now retries a launch a bounded number of times when the
 * creating transaction does not serialize, and the retry is confined to the creation phase by construction
 * rather than by inspection: the launcher creates the execution row and only then hands off to the job, so a
 * transient data-access failure escaping it cannot have come from a step. The isolation level is unchanged and
 * the branches are still parallel. This suite is what proves the fix - if the retry were removed, the branch
 * assertions below would fail intermittently.
 *
 * <h2>How to run, build and test</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -Ddependency-check.skip=true \
 *     -Dit.test='com.cardemo.integration.batch.SuccessfulPipelineRunTest' -DfailIfNoTests=false verify
 * }</pre>
 *
 * <p>A Docker daemon is required: the harness starts PostgreSQL and LocalStack containers.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>One property is overridden, {@value #COMBINED_READER_SOURCE_PROPERTY}, from its default
 * {@code repository} to {@code object-storage}. Everything else is inherited from
 * {@link AbstractBatchIntegrationTest} and the {@code test} profile, including the injected fixed clock, which
 * matters: the report window of {@code app/proc/TRANREPT.prc:L41-L42} is in 2022 and a wall clock would put
 * every generated timestamp outside it.
 *
 * <p>{@code @TestPropertySource} is sufficient here, and the reason is worth recording. Inlined properties land
 * in a source named {@code test}, which sits <em>behind</em> the {@code Dynamic Test Properties} source that
 * {@code @DynamicPropertySource} writes to - so an inlined property can only override a key the parent's
 * dynamic registration does not also set. The parent registers container endpoints, bucket names, the report
 * queue and the signing key, and touches nothing under
 * {@code carddemo.batch.combined-transaction-reader}, so this override takes effect.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>Stage 3 halts with the {@code HALT} gate.</b> The property override did not take effect, or a
 *       {@code TRANSACT.BKUP} generation already exists in the bucket from an earlier run in the same
 *       container. The harness gives each run its own cloud namespace, so the second cause should be
 *       impossible; check the resolved substrate the stage recorded.</li>
 *   <li><b>One branch of the split fails while the other completes.</b> Look for SQLSTATE {@code 40001} in the
 *       log. If it is there, the bounded launch retry has been removed or narrowed - restore it rather than
 *       serialising the branches or weakening the isolation level.</li>
 *   <li><b>The stream ends {@code COMPLETED} rather than {@code COMPLETED WITH REJECTS}.</b> Stage 1 rejected
 *       nothing, which over the shipped fixtures means the over-limit test of
 *       {@code app/cbl/CBTRN02C.cbl:L407-L414} stopped firing.</li>
 * </ul>
 */
@TestPropertySource(properties = {
        SuccessfulPipelineRunTest.COMBINED_READER_SOURCE_PROPERTY + "=object-storage"
})
class SuccessfulPipelineRunTest extends AbstractBatchIntegrationTest {

    /**
     * The property selecting the combined reader's input substrate.
     *
     * <p>Restated as a literal rather than referenced from {@code CombinedTransactionReader}, which declares it
     * in {@code com.cardemo.batch.readers} and is not on this class's dependency surface. A constant is
     * required because {@code @TestPropertySource} takes a compile-time constant expression.
     */
    static final String COMBINED_READER_SOURCE_PROPERTY =
            "carddemo.batch.combined-transaction-reader.source";

    /** Diagnostics for the run, so a failure in CI carries the stage outcomes with it. */
    private static final Logger LOG = LoggerFactory.getLogger(SuccessfulPipelineRunTest.class);

    /** The production pipeline job bean, launched unmodified. */
    @Autowired
    @Qualifier("batchPipelineJob")
    private Job batchPipelineJob;

    /** Reaches the child executions the orchestrator recorded the identifiers of. */
    @Autowired
    private JobExplorer jobExplorer;

    // =================================================================================================
    // Contract values, as immutable instance fields. The harness documents a hard limit of two static
    // fields in this package and both are containers, so nothing here is static and nothing is mutable.
    // The orchestrator declares these names package-private in com.cardemo.batch.jobs, so they are
    // restated here and every read goes through a helper that fails loudly on an absent entry rather than
    // defaulting to zero.
    // =================================================================================================

    /** Launcher step bean name for stage 1. */
    private final String postTranStepBeanName = "batchPipelinePostTranStep";

    /** Launcher step bean name for stage 2. */
    private final String intCalcStepBeanName = "batchPipelineIntCalcStep";

    /** Launcher step bean name for stage 3. */
    private final String combTranStepBeanName = "batchPipelineCombTranStep";

    /** Launcher step bean name for the statement branch of stage 4. */
    private final String creaStmtStepBeanName = "batchPipelineCreaStmtStep";

    /** Launcher step bean name for the report branch of stage 4. */
    private final String tranReptStepBeanName = "batchPipelineTranReptStep";

    /** Prefix of every per-stage execution-context entry the orchestrator publishes. */
    private final String pipelineContextPrefix = "carddemo.pipeline.";

    /** Context infix for stage 1. */
    private final String postTranInfix = "posttran";

    /** Context infix for stage 2. */
    private final String intCalcInfix = "intcalc";

    /** Context infix for stage 3. */
    private final String combTranInfix = "combtran";

    /** Context infix for the statement branch. */
    private final String creaStmtInfix = "creastmt";

    /** Context infix for the report branch. */
    private final String tranReptInfix = "tranrept";

    /** The aggregate return code the stream publishes. */
    private final String aggregateReturnCodeEntry = "carddemo.pipeline.returnCode";

    /** The aggregate outcome the stream publishes. */
    private final String aggregateOutcomeEntry = "carddemo.pipeline.outcome";

    /** The recorded outcome of the stage 2 to stage 3 generation hand-off. */
    private final String systranHandoffEntry = "carddemo.pipeline.systran.handoff";

    /** The hand-off outcome that means stage 3 read exactly what stage 2 wrote. */
    private final String handoffVerified = "VERIFIED";

    /** Gate outcome for a clean stage. */
    private final String gateProceed = "PROCEED";

    /** Gate outcome for a stage that completed with rejects. */
    private final String gateProceedWithRejects = "PROCEED WITH REJECTS";

    /** Gate outcome for a stage that stops the stream. */
    private final String gateHalt = "HALT";

    /** The exit code {@code app/cbl/CBTRN02C.cbl:L230} stands behind. */
    private final String exitCodeCompletedWithRejects = "COMPLETED WITH REJECTS";

    /** Return code 0, the fall-through of {@code app/cbl/CBTRN02C.cbl:L229}. */
    private final int returnCodeCompleted = 0;

    /** Return code 4, {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}. */
    private final int returnCodeCompletedWithRejects = 4;

    /** The ten-digit, separator-free interest date {@code app/jcl/INTCALC.jcl:L22} passes as {@code PARM=}. */
    private final String interestDateParameter = "2022071800";

    /** Inclusive report window start, in the dashed form {@code app/proc/TRANREPT.prc:L45-L46} compares. */
    private final String reportStartDate = "2022-01-01";

    /** Inclusive report window end, same form. */
    private final String reportEndDate = "2022-07-06";

    /** Job-parameter name carrying the interest date. */
    private final String parmDateParameter = "parmDate";

    /** Job-parameter name carrying the report window start. */
    private final String startDateParameter = "startDate";

    /** Job-parameter name carrying the report window end. */
    private final String endDateParameter = "endDate";

    // =================================================================================================
    // The one test. One launch, because the stream declares no incrementer: relaunching with unchanged
    // parameters is refused by the job repository, deliberately, so that a repeated post surfaces rather
    // than being absorbed. Every assertion below reads that single execution.
    // =================================================================================================

    /**
     * The stream runs all five stages and ends successfully, with both branches of the split completed.
     *
     * <p><b>Purpose.</b> Assert that the pipeline has an end. Every other launch in this tier stops at stage 3,
     * so before this test nothing anywhere established that the split could finish, that the aggregate outcome
     * could be anything but a failure, or that stage 3 could consume the interest generation rather than
     * refusing it.
     *
     * <p><b>Inputs.</b> The seeded state the harness establishes, the three production job parameters, and the
     * {@code object-storage} substrate for the combined reader's concatenated input.
     *
     * <p><b>Output.</b> None. <b>Side effects:</b> the run commits its writes to the container database and
     * writes generation objects to the container's buckets; both are disposed with the containers.
     *
     * <p><b>The expected end state is {@code COMPLETED} with the exit code
     * {@value #exitCodeCompletedWithRejects}, not plain {@code COMPLETED}</b>, and the distinction is the
     * source's own. {@code app/cbl/CBTRN02C.cbl:L229-L231} sets return code 4 when and only when the reject
     * count exceeded zero, the shipped fixtures do drive rejects, and return code 4 is a completion. The
     * topology says so structurally: {@code batchPipelineFlow} routes {@code PROCEED} and
     * {@code PROCEED WITH REJECTS} to the same next stage at all four gates. An assertion of plain
     * {@code COMPLETED} here would be asserting that the fixtures reject nothing.
     *
     * <p><b>Error modes.</b> A stage that halted fails on its own gate assertion before the aggregate is
     * examined, so the failure names the stage rather than only the stream. A branch aborted by the
     * serialization conflict fails on the branch status with SQLSTATE {@code 40001} in the log. An absent
     * context entry fails in the reader rather than reading a defaulted zero.
     */
    @Test
    @DisplayName("the whole five-stage stream completes: three sequential stages and both split branches")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theWholeStreamCompletesIncludingBothSplitBranches() {
        final JobExecution pipeline = launchJob(batchPipelineJob, runIdParameters(pipelineParameters()));

        LOG.info("Pipeline {} finished: status {}, exit {}, steps {}",
                pipeline.getId(), pipeline.getStatus(), pipeline.getExitStatus().getExitCode(),
                stepNames(pipeline));

        // ---- Stage 1: POSTTRAN. Return code 4 is a completion, and it carries the stream on. ----
        assertThat(stepNamed(pipeline, postTranStepBeanName).getExitStatus().getExitCode())
                .as("stage 1 rejected at least one record, so app/cbl/CBTRN02C.cbl:L229-L231 gives return "
                        + "code 4 and the gate publishes a proceed - never a halt. app/jcl/POSTTRAN.jcl "
                        + "carries no COND at all, so the legacy stream proceeds past 4 unconditionally")
                .isEqualTo(gateProceedWithRejects);
        assertThat(stageReturnCode(pipeline, postTranInfix))
                .as("and the recorded return code is 4 rather than 0 or 8")
                .isEqualTo(returnCodeCompletedWithRejects);

        // ---- Stage 2: INTCALC. Clean, and it writes the generation stage 3 needs. ----
        assertThat(stepNamed(pipeline, intCalcStepBeanName).getExitStatus().getExitCode())
                .as("stage 2 ran and completed cleanly. app/cbl/CBACT04C.cbl sets no return code of its own, "
                        + "so a clean interest run is a plain proceed")
                .isEqualTo(gateProceed);
        assertThat(stageReturnCode(pipeline, intCalcInfix))
                .as("recorded as return code 0")
                .isEqualTo(returnCodeCompleted);

        // ---- Stage 3: COMBTRAN. The stage that halts under the default substrate. ----
        assertThat(stepNamed(pipeline, combTranStepBeanName).getExitStatus().getExitCode())
                .as("stage 3 COMPLETED rather than halting. Under the default repository substrate the "
                        + "backup leg re-reads the rows "
                        + "stage 1 committed and app/jcl/COMBTRAN.jcl:L41 refuses the repeat, which is "
                        + "faithful and is asserted in BatchPipelineOrchestratorTest. On the object-storage "
                        + "substrate of a cold run the backup generation is absent by construction, because "
                        + "app/proc/TRANREPT.prc:STEP01R produces it in stage 5")
                .isEqualTo(gateProceed)
                .isNotEqualTo(gateHalt);
        assertThat(stageReturnCode(pipeline, combTranInfix))
                .as("recorded as return code 0, not the 8 a duplicate-key refusal produces")
                .isEqualTo(returnCodeCompleted);
        assertThat(contextString(pipeline, systranHandoffEntry))
                .as("and stage 3 consumed exactly the SYSTRAN generation stage 2 created, carried across as a "
                        + "concrete key. app/jcl/INTCALC.jcl:L37-L41 allocates a new generation and "
                        + "app/jcl/COMBTRAN.jcl:L25-L26 reads the current one, so this is the stream's one "
                        + "genuine data dependency")
                .isEqualTo(handoffVerified);

        // ---- Stage 4: the split. Both branches present, both completed, each with its own outcome. ----
        assertThat(stepNames(pipeline))
                .as("all five launcher steps executed, which is the whole point of this suite: the two "
                        + "branches of app/jcl/CREASTMT.JCL and app/proc/TRANREPT.prc were reached rather "
                        + "than bypassed. Resolved: %s", stepNames(pipeline))
                .containsExactlyInAnyOrder(postTranStepBeanName, intCalcStepBeanName, combTranStepBeanName,
                        creaStmtStepBeanName, tranReptStepBeanName);

        for (final Map.Entry<String, String> branch : Map.of(
                creaStmtStepBeanName, creaStmtInfix,
                tranReptStepBeanName, tranReptInfix).entrySet()) {

            final StepExecution branchStep = stepNamed(pipeline, branch.getKey());
            assertThat(branchStep.getStatus())
                    .as("branch %s COMPLETED. This holds only because BatchPipelineOrchestrator.launchStage "
                            + "confines a bounded retry to the creation phase: SERIALIZABLE create isolation "
                            + "otherwise aborts one of the two concurrent child launches and would fail about "
                            + "two runs in three - a SQLSTATE 40001 in the log "
                            + "alongside a failure here means the retry has been removed", branch.getKey())
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(branchStep.getExitStatus().getExitCode())
                    .as("branch %s carries its own outcome rather than inheriting its sibling's, and is not "
                            + "a no-operation - which is what a branch bypassed because of the other one "
                            + "would look like", branch.getKey())
                    .isNotBlank()
                    .isNotEqualTo(ExitStatus.NOOP.getExitCode())
                    .isNotEqualTo(gateHalt);
            assertThat(stageReturnCode(pipeline, branch.getValue()))
                    .as("branch %s recorded return code 0; app/jcl/CREASTMT.JCL and app/proc/TRANREPT.prc "
                            + "publish no non-zero code of their own on a clean run", branch.getValue())
                    .isEqualTo(returnCodeCompleted);
        }

        assertThat(stepNamed(pipeline, creaStmtStepBeanName).getId())
                .as("the two branch executions are distinct records, so neither branch is the other one "
                        + "counted twice")
                .isNotEqualTo(stepNamed(pipeline, tranReptStepBeanName).getId());

        // ---- The stream as a whole. ----
        assertThat(contextInt(pipeline, aggregateReturnCodeEntry))
                .as("the aggregate is the highest code any stage reported. Every stage completed and stage 1 "
                        + "reported 4, so the aggregate is 4 - and 4 is a completion, not a failure")
                .isEqualTo(returnCodeCompletedWithRejects);
        assertThat(contextString(pipeline, aggregateOutcomeEntry))
                .as("published as the completed-with-rejects outcome rather than as FAILED")
                .isEqualTo(exitCodeCompletedWithRejects);
        assertThat(pipeline.getStatus())
                .as("and the stream itself COMPLETED. This is the state no launch in this tier had ever "
                        + "reached before: the sibling suite's every launch ends FAILED at stage 3, so "
                        + "nothing asserted that the stream had an end at all")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(pipeline.getExitStatus().getExitCode())
                .as("consistently in the exit status. batchPipelineFlow routes the split's "
                        + "PROCEED WITH REJECTS gate to end(COMPLETED WITH REJECTS), which is how the "
                        + "topology itself states that return code 4 is not a failure")
                .isEqualTo(exitCodeCompletedWithRejects);
        assertThat(pipeline.getAllFailureExceptions())
                .as("a successful run records no failure. Rejects are business outcomes and are never "
                        + "thrown, so a throwable here would mean something else went wrong and was absorbed")
                .isEmpty();
        assertThat(pipeline.getStepExecutions().stream()
                .filter(step -> step.getStatus() != BatchStatus.COMPLETED)
                .map(StepExecution::getStepName)
                .toList())
                .as("and no launcher step is in any state other than COMPLETED, so the stream's success is "
                        + "not one stage's failure masked by the aggregate")
                .isEmpty();
    }

    // =================================================================================================
    // Helpers. Each is a pure read of the execution the launch returned, and each fails loudly on an
    // absent entry rather than substituting a default that would read as a pass.
    // =================================================================================================

    /**
     * The three parameters every stage of the stream receives.
     *
     * <p>All three are character data. The interest date is ten digits with no separator because
     * {@code app/cbl/CBACT04C.cbl} concatenates it into a transaction identifier, and the two report bounds are
     * the dashed ten-character form the {@code INCLUDE COND} of {@code app/proc/TRANREPT.prc:L45-L46} compares
     * as text. Neither shape is a temporal type and the two are not interchangeable.
     *
     * <p>Inputs: none. Output: the parameter map. Side effects: none.
     *
     * @return the parameter map, never {@code null}
     */
    private Map<String, String> pipelineParameters() {
        final Map<String, String> parameters = new TreeMap<>();
        parameters.put(parmDateParameter, interestDateParameter);
        parameters.put(startDateParameter, reportStartDate);
        parameters.put(endDateParameter, reportEndDate);
        return parameters;
    }

    /**
     * The step names the execution actually ran, in no particular order.
     *
     * <p>Inputs: the finished execution. Output: the step names. Side effects: none.
     *
     * @param execution the finished execution; must not be {@code null}
     * @return the step names, never {@code null}
     */
    private List<String> stepNames(final JobExecution execution) {
        final List<String> names = new ArrayList<>();
        execution.getStepExecutions().forEach(step -> names.add(step.getStepName()));
        return List.copyOf(names);
    }

    /**
     * The one step execution with the given name.
     *
     * <p>Inputs: the finished execution and the step name. Output: the step execution. Side effects: none.
     *
     * @param execution the finished execution; must not be {@code null}
     * @param stepName the launcher step bean name; must not be {@code null}
     * @return the step execution, never {@code null}
     * @throws AssertionError if the step did not run, naming the steps that did - which is the failure a
     *     bypassed stage produces, and it must say which stages were reached rather than only that one was not
     */
    private StepExecution stepNamed(final JobExecution execution, final String stepName) {
        final Optional<StepExecution> found = execution.getStepExecutions().stream()
                .filter(step -> stepName.equals(step.getStepName()))
                .findFirst();
        if (found.isEmpty()) {
            throw new AssertionError("The stream ran no step named '" + stepName + "'. A bypassed stage has no "
                    + "step execution at all, exactly as COND=(0,NE) bypasses rather than skips, so this "
                    + "means an upstream stage halted. The steps that did run were " + stepNames(execution)
                    + " and the stream ended " + execution.getStatus() + ".");
        }
        return found.get();
    }

    /**
     * The return code one stage recorded, read from the entry the orchestrator publishes for it.
     *
     * <p>Inputs: the finished execution and the stage's context infix. Output: the recorded return code. Side
     * effects: none.
     *
     * @param execution the finished execution; must not be {@code null}
     * @param infix the stage's context infix; must not be {@code null}
     * @return the recorded return code
     * @throws AssertionError if the entry is absent
     */
    private int stageReturnCode(final JobExecution execution, final String infix) {
        return contextInt(execution, pipelineContextPrefix + infix + ".returnCode");
    }

    /**
     * One integer execution-context entry, required to be present.
     *
     * <p>Reading a defaulted zero would be the worst available outcome here: zero is the success code, so an
     * entry that had stopped being published would read as a clean stage.
     *
     * <p>Inputs: the finished execution and the entry name. Output: the value. Side effects: none.
     *
     * @param execution the finished execution; must not be {@code null}
     * @param entry the entry name; must not be {@code null}
     * @return the recorded value
     * @throws AssertionError if the entry is absent
     */
    private int contextInt(final JobExecution execution, final String entry) {
        requireEntry(execution, entry);
        return execution.getExecutionContext().getInt(entry);
    }

    /**
     * One string execution-context entry, required to be present.
     *
     * <p>Inputs: the finished execution and the entry name. Output: the value. Side effects: none.
     *
     * @param execution the finished execution; must not be {@code null}
     * @param entry the entry name; must not be {@code null}
     * @return the recorded value
     * @throws AssertionError if the entry is absent
     */
    private String contextString(final JobExecution execution, final String entry) {
        requireEntry(execution, entry);
        return execution.getExecutionContext().getString(entry);
    }

    /**
     * Fails with the entries that are present when a required one is not.
     *
     * <p>Inputs: the finished execution and the entry name. Output: none. Side effects: none.
     *
     * @param execution the finished execution; must not be {@code null}
     * @param entry the entry name; must not be {@code null}
     * @throws AssertionError if the entry is absent
     */
    private void requireEntry(final JobExecution execution, final String entry) {
        final ExecutionContext context = execution.getExecutionContext();
        if (!context.containsKey(entry)) {
            throw new AssertionError("The stream published no execution-context entry '" + entry + "'. The "
                    + "entry names are restated as literals in this class because the orchestrator declares "
                    + "them package-private in another package, so a renamed entry surfaces here. The entries "
                    + "present were " + context.entrySet().stream().map(Map.Entry::getKey).sorted().toList()
                    + " and the stream ended " + execution.getStatus() + ".");
        }
    }

    /**
     * Confirms the explorer is reachable, so a launch that recorded nothing is distinguishable from one that
     * was never made.
     *
     * <p>Inputs: none. Output: the pipeline job name the repository knows. Side effects: none.
     *
     * @return the job names the repository holds, never {@code null}
     */
    private List<String> knownJobNames() {
        return jobExplorer.getJobNames();
    }

    /**
     * The stream is registered with the job repository under a name, so the launch above went through the
     * repository rather than around it.
     *
     * <p><b>Purpose.</b> A launch that bypassed the repository would leave the assertions in the main test
     * reading an in-memory object rather than persisted state, and would pass. This is the guard against that.
     *
     * <p><b>Inputs.</b> None beyond the launch the main test makes; this test makes its own so the two are
     * independent. <b>Output:</b> none. <b>Side effects:</b> one pipeline run, committed and disposed with the
     * containers.
     *
     * <p><b>Error modes.</b> An empty job-name list means nothing was persisted at all.
     */
    @Test
    @DisplayName("the stream's outcome is persisted through the job repository, not held only in memory")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theStreamsOutcomeIsPersistedThroughTheJobRepository() {
        final JobExecution pipeline = launchJob(batchPipelineJob, runIdParameters(pipelineParameters()));

        assertThat(knownJobNames())
                .as("the job repository must know the stream's job name, which is what makes every status and "
                        + "context assertion in this suite a read of persisted state rather than of a "
                        + "transient object. Resolved: %s", knownJobNames())
                .contains(pipeline.getJobInstance().getJobName());

        final JobExecution reloaded = jobExplorer.getJobExecution(pipeline.getId());
        assertThat(reloaded)
                .as("and the execution itself must be reloadable by identifier")
                .isNotNull();
        assertThat(reloaded.getStatus())
                .as("with the same terminal status the launch returned, so nothing about the outcome exists "
                        + "only in the launching thread")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(reloaded.getExitStatus().getExitCode())
                .as("and the same exit code")
                .isEqualTo(exitCodeCompletedWithRejects);
    }
}
