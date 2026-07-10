package com.carddemo.config;

import com.carddemo.batch.BatchCorrelationIdListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch <strong>orchestration</strong> configuration that assembles the five migrated JCL
 * batch jobs into a single master {@link Job}, the {@code cardDemoBatchPipelineJob}. It reproduces
 * the legacy job stream &mdash; run as five <em>separate</em> JCL jobs on the mainframe &mdash; in
 * the exact, preserved order (AAP&nbsp;&sect;0.8.5):
 *
 * <pre>
 *   POSTTRAN (CBTRN02C)  &rarr;  INTCALC (CBACT04C)  &rarr;  COMBTRAN (SORT + IDCAMS REPRO)
 *                        &rarr;  CREASTMT (CBSTM03A/CBSTM03B)  &rarr;  TRANREPT (CBTRN03C)
 * </pre>
 *
 * <h2>Scope &mdash; assembly only</h2>
 * <p>This class is <strong>pure orchestration</strong>: it composes {@link Step} beans that are
 * authored in the sibling {@code com.carddemo.batch} package into the master flow and defines the
 * one {@link JobExecutionDecider} that governs the POSTTRAN reject disposition. It deliberately does
 * <strong>not</strong> declare any {@code Step}, {@code ItemReader}, {@code ItemProcessor},
 * {@code ItemWriter}, per-stage {@code Job}, print/reference job, or SQS report launcher &mdash;
 * those live in {@code com.carddemo.batch} and are injected here by {@link Qualifier name}. The
 * five per-stage {@code Job} beans ({@code postTransactionJob}, {@code interestCalculationJob},
 * &hellip;) and {@code transactionReportJob} (independently launchable via the SQS FIFO report
 * bridge) remain intact; this master pipeline simply reuses the same {@code Step} beans as its
 * stages.</p>
 *
 * <h2>COND-code parity (JCL {@code COND=(0,NE)} &rarr; Spring Batch flow gating)</h2>
 * <p>The legacy {@code CREASTMT.JCL} guards its steps with {@code COND=(0,NE)} ("run this step only
 * if every prior step ended with return code&nbsp;0"). Reproduced here as a flow that advances stage
 * N&nbsp;&rarr;&nbsp;N+1 <strong>only on {@code COMPLETED}</strong>: a step that FAILs (an ABEND
 * analogue) has no outgoing transition, so the flow ends and the job's status becomes
 * {@code FAILED} &mdash; downstream stages never run. No {@code .on("FAILED")} continuation is wired,
 * matching {@code COND=(0,NE)} exactly.</p>
 *
 * <h2>POSTTRAN reject warning ({@code RETURN-CODE 4} parity)</h2>
 * <p>{@code CBTRN02C} does not abend when it rejects records; it writes them to the {@code DALYREJS}
 * file, continues, and sets {@code RETURN-CODE 4} when at least one record was rejected. The migrated
 * {@code postTransactionStep} still ends {@code COMPLETED} (business rejects are handled as data by
 * its writer), and the writer surfaces the running reject count into the step's
 * {@code ExecutionContext}. {@link #postingRejectDecider()} inspects that count and yields a distinct
 * {@code COMPLETED_WITH_REJECTS} flow status for observability/exit-status parity &mdash; but both
 * {@code COMPLETED} and {@code COMPLETED_WITH_REJECTS} transition onward to INTCALC, because a reject
 * warning must never abort the pipeline.</p>
 *
 * <h2>Wiring notes</h2>
 * <ul>
 *   <li>This class deliberately does <strong>not</strong> use {@code @EnableBatchProcessing}: under
 *       Spring Boot&nbsp;3.x that annotation <em>disables</em> Boot's Batch auto-configuration. The
 *       {@link JobRepository} (and the platform transaction manager consumed by the step beans) are
 *       the Boot auto-configured beans; this class defines no {@code JobRepository},
 *       {@code JobLauncher}, {@code JobExplorer}, {@code JobRegistry}, or batch transaction-manager
 *       bean.</li>
 *   <li>A {@link RunIdIncrementer} makes each launch a distinct, re-runnable {@code JobInstance},
 *       the on-demand-rerun / idempotency analogue of the JCL rerun semantics (AAP&nbsp;&sect;0.8.5).</li>
 *   <li>{@link BatchCorrelationIdListener} is attached so every log line emitted across the whole
 *       run carries the MDC {@code correlationId} (Observability rule, AAP&nbsp;&sect;0.7.1).</li>
 *   <li>No job auto-runs at startup ({@code spring.batch.job.enabled=false}, declared in
 *       {@code application.yml}); the master job is launched explicitly (by a launcher/endpoint/test).</li>
 *   <li>PARM dates, queue names, and bucket names are externalized to {@code application*.yml}
 *       ({@code carddemo.batch.*}, {@code carddemo.aws.*}) and consumed inside the step beans; none
 *       are duplicated here (AAP&nbsp;&sect;0.8.6).</li>
 * </ul>
 *
 * <p>Design rationale (including recorded decision&nbsp;#5, "no {@code @EnableBatchProcessing}") lives
 * in {@code docs/decision-log.md}, and the COBOL/JCL&nbsp;&rarr;&nbsp;Java mapping in
 * {@code docs/traceability-matrix.md}, per the Explainability rule. The frozen legacy source under
 * {@code app/} is referenced read-only by commit SHA {@code 27d6c6f} and is never copied here.</p>
 *
 * @see BatchCorrelationIdListener
 */
@Configuration
public class BatchConfig {

    /** SLF4J logger for this orchestration configuration. */
    private static final Logger log = LoggerFactory.getLogger(BatchConfig.class);

    /** Bean/instance name of the assembled master pipeline {@link Job} (distinct from per-stage jobs). */
    static final String PIPELINE_JOB_NAME = "cardDemoBatchPipelineJob";

    /** Name of the {@link Flow} that sequences the five migrated stages. */
    static final String PIPELINE_FLOW_NAME = "cardDemoBatchPipelineFlow";

    /**
     * Key under which the {@code postTransactionStep} writer promotes its running reject count into
     * the step {@code ExecutionContext}.
     *
     * <p>This literal MUST stay aligned with {@code PostTransactionItemWriter.REJECT_COUNT_KEY}
     * (value {@code "rejectCount"}) in the {@code com.carddemo.batch} package. It is intentionally
     * duplicated as a local constant rather than imported: {@code PostTransactionItemWriter} is an
     * implementation detail of the batch layer and is not a declared dependency of this
     * configuration, and importing it would couple the orchestration layer to a concrete writer.</p>
     */
    static final String POSTING_REJECT_COUNT_KEY = "rejectCount";

    /**
     * Distinct flow status emitted by {@link #postingRejectDecider()} when POSTTRAN completed but at
     * least one transaction was rejected (the {@code RETURN-CODE 4} disposition). It is a
     * <em>non-aborting</em> status: it transitions onward to INTCALC just like {@link #STATUS_COMPLETED}.
     */
    static final String STATUS_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /**
     * Flow transition pattern for a normally completed step/decision, derived from the framework
     * constant so it stays in lock-step with Spring Batch rather than being a hand-typed literal.
     */
    private static final String STATUS_COMPLETED = FlowExecutionStatus.COMPLETED.getName();

    /**
     * Decider that maps the POSTTRAN posting outcome to a flow status, reproducing {@code CBTRN02C}'s
     * {@code RETURN-CODE 4} reject disposition.
     *
     * <p>It reads the reject count promoted by the {@code postTransactionStep} writer into the
     * just-executed step's {@code ExecutionContext} (key {@link #POSTING_REJECT_COUNT_KEY}). When the
     * count is positive it returns the distinct {@link #STATUS_COMPLETED_WITH_REJECTS} status;
     * otherwise it returns {@link FlowExecutionStatus#COMPLETED}. Both statuses are wired to continue
     * to INTCALC (see {@link #cardDemoBatchPipelineJob}); the distinction exists only so the reject
     * warning is visible in the flow/exit status, never to abort the pipeline.</p>
     *
     * <p>The {@code stepExecution} argument is null-guarded defensively: when a decider is positioned
     * immediately after a step it is always supplied, but returning a benign {@code COMPLETED} for a
     * hypothetical null keeps the decider total and side-effect free.</p>
     *
     * @return the reject-gate decider bean
     */
    @Bean
    JobExecutionDecider postingRejectDecider() {
        return (jobExecution, stepExecution) -> {
            final long rejects = (stepExecution != null)
                    ? stepExecution.getExecutionContext().getLong(POSTING_REJECT_COUNT_KEY, 0L)
                    : 0L;
            final FlowExecutionStatus status = (rejects > 0L)
                    ? new FlowExecutionStatus(STATUS_COMPLETED_WITH_REJECTS)
                    : FlowExecutionStatus.COMPLETED;
            log.info("POSTTRAN reject-gate: rejectCount={} -> flowStatus={} (both continue to INTCALC; "
                    + "RETURN-CODE 4 warning parity)", rejects, status.getName());
            return status;
        };
    }

    /**
     * Assembles the master five-stage batch pipeline job.
     *
     * <p>The stages are sequenced in the preserved legacy order POSTTRAN&nbsp;&rarr;&nbsp;INTCALC
     * &rarr;&nbsp;COMBTRAN&nbsp;&rarr;&nbsp;CREASTMT&nbsp;(statement)&nbsp;&rarr;&nbsp;TRANREPT&nbsp;(report).
     * After POSTTRAN the {@link #postingRejectDecider()} runs; both of its outcomes
     * ({@link #STATUS_COMPLETED} and {@link #STATUS_COMPLETED_WITH_REJECTS}) advance to INTCALC.
     * Every subsequent stage advances only on {@link #STATUS_COMPLETED}, so a failed step ends the
     * flow in {@code FAILED} without running downstream stages &mdash; the {@code COND=(0,NE)} parity.</p>
     *
     * <p>All collaborators are injected as method parameters (no field/constructor injection is
     * needed since this factory holds no state). The five {@link Step} beans are selected by
     * {@link Qualifier} because {@code Step} is an ambiguous type in this context (each stage plus the
     * print/reference jobs contribute {@code Step} beans). The {@link JobRepository} is the Boot
     * auto-configured bean.</p>
     *
     * @param jobRepository          the Spring Boot auto-configured Spring Batch job repository
     * @param postTransactionStep    stage&nbsp;1 &mdash; transaction posting (POSTTRAN / CBTRN02C)
     * @param interestCalculationStep stage&nbsp;2 &mdash; interest calculation (INTCALC / CBACT04C)
     * @param combineTransactionStep stage&nbsp;3 &mdash; combine/sort + load (COMBTRAN / SORT+IDCAMS)
     * @param statementStep          stage&nbsp;4 &mdash; statement generation (CREASTMT / CBSTM03A+CBSTM03B)
     * @param transactionReportStep  stage&nbsp;5 &mdash; transaction detail report (TRANREPT / CBTRN03C)
     * @param postingRejectDecider   the POSTTRAN reject-gate decider
     * @param correlationIdListener  the correlation-id listener attached for observability
     * @return the fully built {@code cardDemoBatchPipelineJob}
     */
    @Bean
    Job cardDemoBatchPipelineJob(
            final JobRepository jobRepository,
            @Qualifier("postTransactionStep") final Step postTransactionStep,
            @Qualifier("interestCalculationStep") final Step interestCalculationStep,
            @Qualifier("combineTransactionStep") final Step combineTransactionStep,
            @Qualifier("statementStep") final Step statementStep,
            @Qualifier("transactionReportStep") final Step transactionReportStep,
            final JobExecutionDecider postingRejectDecider,
            final BatchCorrelationIdListener correlationIdListener) {

        // Build the sequential flow. POSTTRAN is followed by the reject decider; both decider
        // outcomes continue to INTCALC (RC=4 is a warning, never an abort). Every other stage
        // transitions onward only on COMPLETED, so a FAILED step terminates the flow (COND=(0,NE)).
        final Flow pipeline = new FlowBuilder<Flow>(PIPELINE_FLOW_NAME)
                .start(postTransactionStep)
                .next(postingRejectDecider)
                    .on(STATUS_COMPLETED).to(interestCalculationStep)
                .from(postingRejectDecider)
                    .on(STATUS_COMPLETED_WITH_REJECTS).to(interestCalculationStep)
                .from(interestCalculationStep)
                    .on(STATUS_COMPLETED).to(combineTransactionStep)
                .from(combineTransactionStep)
                    .on(STATUS_COMPLETED).to(statementStep)
                .from(statementStep)
                    .on(STATUS_COMPLETED).to(transactionReportStep)
                .end();

        final Job job = new JobBuilder(PIPELINE_JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(correlationIdListener)
                .start(pipeline)
                .end()
                .build();

        log.info("Assembled master batch pipeline job '{}' preserving JCL order "
                + "POSTTRAN -> INTCALC -> COMBTRAN -> CREASTMT -> TRANREPT with COND=(0,NE) gating",
                PIPELINE_JOB_NAME);
        return job;
    }
}
