package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;

/**
 * Fast, DB-free, context-free unit test for the {@link JobExecutionDecider} produced by
 * {@link BatchConfig#postingRejectDecider()}. The suite invokes the decider factory directly
 * (plain {@code new BatchConfig()}, no Spring context, no {@code JobRepository}, no database),
 * so it runs in milliseconds, is fully deterministic, compiles warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2), and contributes line coverage toward the Gate&nbsp;8
 * (&ge;80%) JaCoCo threshold. The heavier job-flow assembly / stage-ordering and forced-failure
 * ({@code COND=(0,NE)}) assertions that require a live batch infrastructure belong to
 * {@code BatchConfigIT} (Failsafe / Testcontainers), not here.
 *
 * <h2>Contract under test &mdash; POSTTRAN reject-gate ({@code RETURN-CODE 4} parity)</h2>
 * <p>{@code postingRejectDecider()} is the Java parity for the exit-status logic of the legacy
 * posting program {@code app/cbl/CBTRN02C.cbl} (read-only @ commit SHA {@code 27d6c6f}). That
 * program does not abend on a business reject: it writes rejected records to {@code DALYREJS},
 * keeps processing, and at end-of-run sets the job {@code RETURN-CODE} to&nbsp;4 when at least one
 * record was rejected &mdash; {@code CBTRN02C.cbl} L229-230:
 * {@code IF WS-REJECT-COUNT > 0} &rarr; {@code MOVE 4 TO RETURN-CODE}. The migrated
 * {@code postTransactionStep} still ends {@code COMPLETED} (rejects are handled as data by its
 * writer) and promotes the running reject count into the step {@code ExecutionContext} under the
 * key {@code "rejectCount"}. The decider inspects that count and maps it to a flow status:</p>
 * <ul>
 *   <li>{@code rejectCount == 0} &rarr; {@link FlowExecutionStatus#COMPLETED} (name
 *       {@code "COMPLETED"}) &mdash; the {@code RETURN-CODE 0} clean-posting case.</li>
 *   <li>{@code rejectCount > 0} &rarr; {@code new FlowExecutionStatus("COMPLETED_WITH_REJECTS")}
 *       &mdash; the {@code RETURN-CODE 4} <em>warning</em> case.</li>
 *   <li>a missing {@code "rejectCount"} key defaults to&nbsp;0 (via
 *       {@code ExecutionContext.getLong("rejectCount", 0L)}) &rarr; {@code "COMPLETED"}.</li>
 *   <li>a {@code null} {@code stepExecution} is null-guarded &rarr; {@code "COMPLETED"} (the
 *       decider is total and side-effect free).</li>
 * </ul>
 *
 * <h2>Why both outcomes are non-aborting</h2>
 * <p>Both {@code "COMPLETED"} and {@code "COMPLETED_WITH_REJECTS"} transition the pipeline onward
 * to INTCALC: a reject warning must never stop the run. This mirrors the JCL job stream
 * ({@code app/jcl/POSTTRAN.jcl} runs {@code PGM=CBTRN02C}; {@code app/jcl/CREASTMT.JCL} &mdash;
 * note the UPPERCASE {@code .JCL} &mdash; guards downstream steps with {@code COND=(0,NE)}, i.e.
 * "run only if every prior step ended RC&nbsp;0"), where an {@code RC 4} posting is a
 * non-fatal warning that still flows forward. This test asserts only the reject-flag distinction
 * the decider is responsible for; the actual forward transitions are covered by the integration
 * test.</p>
 *
 * <p>Design rationale, alternatives, and risks live in {@code docs/decision-log.md} (Explainability
 * rule), not in these comments; the COBOL/JCL &rarr; Java mapping lives in
 * {@code docs/traceability-matrix.md}. The frozen legacy source under {@code app/} is referenced
 * read-only by commit SHA {@code 27d6c6f} and is never copied into the target.</p>
 *
 * @see BatchConfig#postingRejectDecider()
 * @see FlowExecutionStatus
 * @see JobExecutionDecider
 */
@DisplayName("BatchConfig.postingRejectDecider — CBTRN02C RETURN-CODE 4 reject-gate parity")
class BatchConfigTest {

    /**
     * Execution-context key under which {@code postTransactionStep} promotes its running reject
     * count. Must stay aligned with {@code BatchConfig.POSTING_REJECT_COUNT_KEY} /
     * {@code PostTransactionItemWriter.REJECT_COUNT_KEY} (value {@code "rejectCount"}).
     */
    private static final String REJECT_COUNT_KEY = "rejectCount";

    /** Flow-status name for a clean completion (framework constant {@link FlowExecutionStatus#COMPLETED}). */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** Distinct flow-status name emitted when at least one record was rejected ({@code RETURN-CODE 4} parity). */
    private static final String STATUS_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /** Step name used for the throwaway {@link StepExecution}; matches the migrated POSTTRAN step bean name. */
    private static final String POST_TRANSACTION_STEP = "postTransactionStep";

    /**
     * Builds the decider under test from a plain {@code BatchConfig} instance. {@code BatchConfig}
     * has no constructor-injected collaborators, so no mocks are needed; centralizing construction
     * here keeps any future signature change confined to a single spot.
     *
     * @return the {@link JobExecutionDecider} produced by {@link BatchConfig#postingRejectDecider()}
     */
    private JobExecutionDecider newDecider() {
        return new BatchConfig().postingRejectDecider();
    }

    /**
     * Runs the decider against a fresh, real {@link StepExecution} (a lightweight POJO &mdash; no DB
     * or running context required) whose {@code ExecutionContext} carries the supplied reject count.
     *
     * @param rejectCount the value stored under {@link #REJECT_COUNT_KEY}
     * @return the {@link FlowExecutionStatus} returned by the decider
     */
    private FlowExecutionStatus decideWithRejectCount(long rejectCount) {
        JobExecution jobExecution = new JobExecution(1L);
        StepExecution stepExecution = new StepExecution(POST_TRANSACTION_STEP, jobExecution);
        stepExecution.getExecutionContext().putLong(REJECT_COUNT_KEY, rejectCount);
        return newDecider().decide(jobExecution, stepExecution);
    }

    @Test
    @DisplayName("rejectCount == 0 → COMPLETED (RETURN-CODE 0, clean posting)")
    void zeroRejectsCompletesClean() {
        FlowExecutionStatus status = decideWithRejectCount(0L);

        // Assert both the exact status name and identity with the canonical framework constant:
        // a clean posting yields the shared FlowExecutionStatus.COMPLETED instance.
        assertThat(status.getName()).isEqualTo(STATUS_COMPLETED);
        assertThat(status).isEqualTo(FlowExecutionStatus.COMPLETED);
    }

    @ParameterizedTest(name = "rejectCount = {0} → COMPLETED_WITH_REJECTS")
    @ValueSource(longs = {1L, 5L, 100L, 999999L})
    @DisplayName("rejectCount > 0 → COMPLETED_WITH_REJECTS (RETURN-CODE 4 warning parity)")
    void positiveRejectsFlagWithRejects(long rejectCount) {
        // Parity anchor: app/cbl/CBTRN02C.cbl L229-230 (@ SHA 27d6c6f)
        //     IF WS-REJECT-COUNT > 0  →  MOVE 4 TO RETURN-CODE
        // Any positive reject count is a non-fatal warning (RC 4): the decider flags it distinctly
        // but the pipeline still advances to INTCALC (CREASTMT.JCL COND=(0,NE) semantics).
        assertThat(decideWithRejectCount(rejectCount).getName())
                .isEqualTo(STATUS_COMPLETED_WITH_REJECTS);
    }

    @Test
    @DisplayName("missing rejectCount key defaults to 0 → COMPLETED")
    void missingRejectCountDefaultsToCompleted() {
        JobExecution jobExecution = new JobExecution(1L);
        StepExecution stepExecution = new StepExecution(POST_TRANSACTION_STEP, jobExecution);
        // Intentionally do NOT populate "rejectCount": this exercises the getLong(key, 0L) default
        // path, proving an absent key is treated as zero rejects (clean completion).

        FlowExecutionStatus status = newDecider().decide(jobExecution, stepExecution);

        assertThat(status.getName()).isEqualTo(STATUS_COMPLETED);
    }

    @Test
    @DisplayName("null stepExecution is guarded → COMPLETED (decider is total)")
    void nullStepExecutionIsSafe() {
        // The production decider null-guards stepExecution and returns COMPLETED, keeping it total
        // and side-effect free even in the (unreachable in normal flow) null case.
        FlowExecutionStatus status = newDecider().decide(new JobExecution(1L), null);

        assertThat(status.getName()).isEqualTo(STATUS_COMPLETED);
    }
}
