package com.cardemo.batch.jobs;

import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.DailyTransaction;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.classify.Classifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@code @Configuration} that assembles <strong>Stage&nbsp;1</strong> of the greenfield
 * Java&nbsp;25 LTS + Spring&nbsp;Boot&nbsp;3.5.x CardDemo migration pipeline &mdash; the
 * <em>Daily Transaction Posting</em> job. It is the faithful translation of the legacy JCL job
 * {@code app/jcl/POSTTRAN.jcl}, whose lone step {@code //STEP15 EXEC PGM=CBTRN02C} runs the COBOL
 * batch program {@code app/cbl/CBTRN02C.cbl} (Post the daily transaction file, build the
 * transaction-category balances and update the transaction master).
 *
 * <h2>Provenance &amp; governance (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>Translated from the frozen AWS CardDemo COBOL/JCL baseline at commit SHA {@code 27d6c6f}. The
 * COBOL/JCL source is <strong>read-only</strong> reference material and is <strong>never copied</strong>
 * into this repository; traceability is by commit SHA only. Per the <strong>Minimal Change Clause</strong>
 * this class reproduces the legacy behaviour <em>exactly</em> &mdash; it adds no features, endpoints or
 * optimizations &mdash; and documents every technology substitution at its point of change. The
 * application base package is {@code com.cardemo} (decision <strong>D-006</strong>, deliberately
 * <em>not</em> {@code com.carddemo}).</p>
 *
 * <h2>Responsibility &mdash; ORCHESTRATION / WIRING only</h2>
 * <p>This class contains <strong>no business logic</strong>. The sequential read cycle, the
 * {@code 1500-VALIDATE-TRAN} validation cascade, the {@code 2000-POST-TRANSACTION} persistence
 * ({@code 2700}/{@code 2800}/{@code 2900}) and the {@code 2500-WRITE-REJECT-REC} reject formatting all
 * live in the sibling components below; this {@code @Configuration} only composes them into a
 * {@code Step} and a {@code Job}, routes accepted vs. rejected records, and surfaces the COBOL
 * {@code RETURN-CODE=4} "success-with-rejects" signal as a custom {@link ExitStatus}:</p>
 * <ul>
 *   <li>{@link ItemReader}&lt;{@link DailyTransaction}&gt; &mdash; the single active reader bean produced
 *       by the {@code com.cardemo.batch.readers.DailyTransactionReader} {@code @Configuration} (the
 *       S3-staged {@code FlatFileItemReader} by default; the repository-backed alternative when the
 *       {@code carddemo.batch.daily-transaction.reader} property selects it). Reproduces the
 *       {@code CBTRN01C}/{@code CBTRN02C} sequential {@code DALYTRAN} read.</li>
 *   <li>{@link TransactionPostingProcessor} &mdash; the {@code 1500} validation cascade and field
 *       mapping; always returns a non-null {@link PostedTransactionResult} carrier (accepted vs. rejected
 *       distinguished by {@link PostedTransactionResult#isRejected()}).</li>
 *   <li>{@link TransactionWriter} &mdash; the accepted path ({@code 2700}/{@code 2800}/{@code 2900}
 *       persistence to the transaction master).</li>
 *   <li>{@link RejectWriter} &mdash; the reject path ({@code 2500}, 430-byte reject record).</li>
 * </ul>
 *
 * <h2>Technology substitutions (documented per Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <dl>
 *   <dt>{@code //STEP15 EXEC PGM=CBTRN02C} &rarr; Spring Batch {@code Step}/{@code Job} beans</dt>
 *   <dd>The single JCL step becomes {@link #dailyTransactionPostingStep(JobRepository,
 *       PlatformTransactionManager)} composed into {@link #dailyTransactionPostingJob(JobRepository,
 *       Step)}. {@code POSTTRAN.jcl} carries <strong>no {@code COND} and no {@code PARM}</strong>, so the
 *       job is an unconditional single step with no job parameters of its own.</dd>
 *   <dt>{@code DALYTRAN.PS} sequential dataset (DD {@code DALYTRAN}) &rarr; S3 {@code carddemo-batch-input}</dt>
 *   <dd>Read by the injected {@code DailyTransactionReader} bean (GDG/PS&rarr;S3, decision D-003).</dd>
 *   <dt>{@code DALYREJS(+1)} GDG, {@code RECFM=F LRECL=430} (DD {@code DALYREJS}) &rarr; S3 {@code carddemo-batch-output}</dt>
 *   <dd>Written by {@link RejectWriter}; the GDG generation becomes a versioned, generation-prefixed S3
 *       object (GDG&rarr;S3, decision D-003).</dd>
 *   <dt>{@code FILE STATUS} / abend handling &rarr; exception hierarchy</dt>
 *   <dd>An I/O failure no longer abends the address space; it propagates as an exception from a
 *       collaborator and fails/rolls back the chunk (AAP &sect;0.7.5). Handled in the collaborators,
 *       not here.</dd>
 *   <dt>{@code GOBACK} {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} &rarr;
 *       {@code ExitStatus("COMPLETED_WITH_REJECTS")}</dt>
 *   <dd>Partial rejects are <strong>success-with-rejects</strong>, not a failure: the
 *       {@link BatchStatus} stays {@code COMPLETED} and the custom {@link ExitStatus} (code
 *       {@link #EXIT_CODE_COMPLETED_WITH_REJECTS}) lets the {@code BatchPipelineOrchestrator}'s
 *       {@code JobExecutionDecider} treat {@code RC=4} as PROCEED so the downstream stages still run.
 *       See {@link #rejectCountStepListener()}.</dd>
 * </dl>
 *
 * <h2>Decimal precision (AAP &sect;0.7.3)</h2>
 * <p>This wiring class performs no arithmetic. All monetary values are handled as
 * {@link java.math.BigDecimal} by the collaborators (never {@code float}/{@code double}), compared with
 * {@code compareTo}; no money flows through this class.</p>
 *
 * @see TransactionPostingProcessor
 * @see TransactionWriter
 * @see RejectWriter
 * @see PostedTransactionResult
 * @see ClassifierCompositeItemWriter
 * @see Configuration
 */
// Explicit configuration-bean name to avoid a BeanDefinitionOverrideException. The default component
// name for this class is its decapitalized simple name, "dailyTransactionPostingJob", which would
// collide with the Job @Bean method of the same name below (Spring Boot disables bean-definition
// overriding by default). Naming the @Configuration bean distinctly lets the Job bean keep the natural
// name "dailyTransactionPostingJob" (the name the pipeline orchestrator resolves).
@Configuration("dailyTransactionPostingJobConfiguration")
public class DailyTransactionPostingJob {

    /**
     * {@link ExitStatus} code published when the step completes with at least one rejected record.
     *
     * <p>This is the Spring Batch realization of the COBOL
     * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} at {@code CBTRN02C}'s {@code GOBACK}: a
     * <strong>success-with-rejects</strong> outcome that is <em>not</em> a failure. The
     * {@code BatchPipelineOrchestrator}'s {@code JobExecutionDecider} should treat this exit code the
     * same way the legacy JCL treats {@code RC=4} &mdash; as PROCEED to the next stage.</p>
     */
    public static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /**
     * Step {@code ExecutionContext} key under which the number of rejected records is published by
     * {@link #rejectCountStepListener()} at end-of-step. Exposed so the orchestrator's
     * {@code JobExecutionDecider} (or operational tooling) can read the exact reject tally that drove
     * the {@link #EXIT_CODE_COMPLETED_WITH_REJECTS} decision, mirroring the COBOL
     * {@code WS-REJECT-COUNT}.
     */
    public static final String REJECT_COUNT_KEY = "rejectCount";

    /**
     * Chunk (commit-interval) size for the posting step. This is a <strong>tuning parameter only</strong>
     * and does <strong>not</strong> affect final-state parity: every record is read, validated, routed
     * and persisted identically to {@code CBTRN02C}'s sequential read loop regardless of how the stream
     * is chunked. The accepted-path persistence ({@code 2700}/{@code 2800}/{@code 2900}) is committed
     * per chunk inside one transaction by {@link TransactionWriter}; reject counting is per record.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Name of the posting step bean and its Spring Batch {@code BATCH_STEP_EXECUTION} identity. Mirrors
     * the JCL step name role of {@code //STEP15}.
     */
    private static final String STEP_NAME = "dailyTransactionPostingStep";

    /**
     * Name of the posting job bean and its Spring Batch {@code BATCH_JOB_INSTANCE} identity. Mirrors the
     * JCL job name {@code //POSTTRAN}.
     */
    private static final String JOB_NAME = "dailyTransactionPostingJob";

    /**
     * The single active reader bean produced by the {@code com.cardemo.batch.readers.DailyTransactionReader}
     * {@code @Configuration} factory. That factory declares two mutually-exclusive
     * {@code @ConditionalOnProperty} reader beans (the S3-staged {@code FlatFileItemReader}, active by
     * default, and a repository-backed alternative), so exactly <strong>one</strong>
     * {@code ItemReader<DailyTransaction>} exists in the context and is injected here by type. It
     * reproduces the {@code CBTRN01C}/{@code CBTRN02C} sequential {@code DALYTRAN} read (DD
     * {@code DALYTRAN}, staged to S3 {@code carddemo-batch-input}).
     */
    private final ItemReader<DailyTransaction> dailyTransactionReader;

    /**
     * The validation/mapping processor &mdash; reproduces {@code CBTRN02C}'s {@code 1500-VALIDATE-TRAN}
     * cascade (reject codes {@code 100}/{@code 101}/{@code 102}/{@code 103}) and the
     * {@code 2000-POST-TRANSACTION} field mapping. Always returns a non-null
     * {@link PostedTransactionResult} so rejects are routed (never silently filtered).
     */
    private final TransactionPostingProcessor transactionPostingProcessor;

    /**
     * The accepted-path writer &mdash; reproduces {@code CBTRN02C}'s {@code 2000-POST-TRANSACTION}
     * persistence cascade ({@code 2700-UPDATE-TCATBAL} &rarr; {@code 2800-UPDATE-ACCOUNT-REC} &rarr;
     * {@code 2900-WRITE-TRANSACTION-FILE}) within the chunk transaction, plus the S3 backup of the
     * posted stream.
     */
    private final TransactionWriter transactionWriter;

    /**
     * The reject-path writer &mdash; reproduces {@code CBTRN02C}'s {@code 2500-WRITE-REJECT-REC},
     * assembling the fixed 430-byte ({@code 350 + 80}) reject record and writing it to the
     * {@code DALYREJS} sink (GDG&rarr;S3 {@code carddemo-batch-output}).
     */
    private final RejectWriter rejectWriter;

    /**
     * Constructor injection of the four sibling collaborators. A single constructor is used, so Spring
     * autowires it without an explicit {@code @Autowired} annotation. All collaborators are
     * Spring-managed singletons defined in sibling {@code com.cardemo.batch.*} / {@code model} packages.
     *
     * @param dailyTransactionReader      the single active {@code ItemReader<DailyTransaction>} bean
     *                                    (S3-staged by default); must not be {@code null}
     * @param transactionPostingProcessor the {@code 1500} validation/mapping processor; must not be
     *                                    {@code null}
     * @param transactionWriter           the accepted-path posting writer ({@code 2000} cascade); must
     *                                    not be {@code null}
     * @param rejectWriter                the reject-path writer ({@code 2500}, 430-byte record); must not
     *                                    be {@code null}
     */
    public DailyTransactionPostingJob(final ItemReader<DailyTransaction> dailyTransactionReader,
                                      final TransactionPostingProcessor transactionPostingProcessor,
                                      final TransactionWriter transactionWriter,
                                      final RejectWriter rejectWriter) {
        this.dailyTransactionReader = dailyTransactionReader;
        this.transactionPostingProcessor = transactionPostingProcessor;
        this.transactionWriter = transactionWriter;
        this.rejectWriter = rejectWriter;
    }

    /**
     * The {@link StepExecutionListener} that reproduces the COBOL {@code RETURN-CODE=4} signal. Declared
     * as its own bean so the same instance is shared by the classifier (which tallies rejects) and by
     * the step (which registers it as a listener). {@code @Configuration} bean-method proxying guarantees
     * both call sites receive this singleton.
     *
     * @return the reject-counting step listener
     */
    @Bean
    public RejectCountStepListener rejectCountStepListener() {
        return new RejectCountStepListener();
    }

    /**
     * The composite writer that routes each {@link PostedTransactionResult} to the correct sink,
     * reproducing the {@code CBTRN02C} branch {@code IF WS-VALIDATION-FAIL-REASON = 0}.
     *
     * <p>Accepted records (the {@code 2000-POST-TRANSACTION} path) go to {@link TransactionWriter}, which
     * performs the {@code 2700-UPDATE-TCATBAL} &rarr; {@code 2800-UPDATE-ACCOUNT-REC} &rarr;
     * {@code 2900-WRITE-TRANSACTION-FILE} cascade; rejected records (the {@code 2500-WRITE-REJECT-REC}
     * path) go to {@link RejectWriter}, which emits the 430-byte {@code DALYREJS} record. The
     * "rejected vs. accepted" decision uses the carrier's own {@link PostedTransactionResult#isRejected()}
     * accessor (a non-{@link com.cardemo.model.enums.RejectCode#NONE NONE} reject code).</p>
     *
     * <p>The classifier also tallies each reject (via {@link RejectCountStepListener#recordReject()})
     * exactly once per routed record &mdash; the Java equivalent of COBOL {@code ADD 1 TO
     * WS-REJECT-COUNT} in the {@code ELSE} branch &mdash; so the end-of-step listener can reproduce
     * {@code RETURN-CODE=4}.</p>
     *
     * @return the classifier-composite writer that fans accepted/rejected records to their writers
     */
    @Bean
    public ClassifierCompositeItemWriter<PostedTransactionResult> postingClassifierWriter() {
        // Resolve the singleton reject-count listener once and share it with the classifier so the routing
        // decision and the RETURN-CODE=4 tally observe the SAME counter. @Configuration bean-method
        // proxying guarantees this is the same instance the step registers via .listener(...).
        final RejectCountStepListener listener = rejectCountStepListener();

        final ClassifierCompositeItemWriter<PostedTransactionResult> classifierWriter =
                new ClassifierCompositeItemWriter<>();

        // Reproduces CBTRN02C's post-validation branch: WS-VALIDATION-FAIL-REASON = 0 -> 2000-POST
        // (TransactionWriter, the transaction master); non-zero -> 2500-WRITE-REJECT-REC (RejectWriter,
        // the DALYREJS sink). The reject is counted here, once per rejected record, mirroring the COBOL
        // increment of WS-REJECT-COUNT.
        final Classifier<PostedTransactionResult, ItemWriter<? super PostedTransactionResult>> classifier =
                item -> {
                    if (item.isRejected()) {
                        listener.recordReject();
                        return rejectWriter;
                    }
                    return transactionWriter;
                };
        classifierWriter.setClassifier(classifier);
        return classifierWriter;
    }

    /**
     * Builds the single chunk-oriented step that realizes {@code POSTTRAN.jcl}'s lone
     * {@code //STEP15 EXEC PGM=CBTRN02C}. The injected reader drives the sequential {@code DALYTRAN}
     * read, {@link TransactionPostingProcessor} runs the {@code 1500} validation cascade and the
     * {@code 2000} mapping, and {@link #postingClassifierWriter()} routes the result to the posting or
     * reject writer. The {@link #rejectCountStepListener()} surfaces the {@code RETURN-CODE=4} signal at
     * end-of-step.
     *
     * <p>Spring&nbsp;Batch&nbsp;5.x builders are used with an explicit {@link JobRepository} and
     * {@link PlatformTransactionManager} (the removed {@code StepBuilderFactory} is deliberately
     * <strong>not</strong> used). The {@code transactionManager} bounds the per-chunk unit of work, so
     * the accepted-path {@code 2700}/{@code 2800}/{@code 2900} writes commit or roll back atomically
     * (AAP &sect;0.7.5) &mdash; the faithful equivalent of {@code CBTRN02C}'s {@code ABEND}-on-error
     * behaviour.</p>
     *
     * <p>No {@code .stream(...)} registration is required. {@link ClassifierCompositeItemWriter} does not
     * propagate {@code ItemStream} lifecycle to its delegates, but neither {@link TransactionWriter} nor
     * {@link RejectWriter} implements {@code ItemStream} (both are stateless singletons), so there is no
     * delegate stream state to open/update/close.</p>
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     * @param transactionManager the auto-configured transaction manager bounding each chunk transaction
     * @return the configured daily-transaction posting step
     */
    @Bean
    public Step dailyTransactionPostingStep(final JobRepository jobRepository,
                                            final PlatformTransactionManager transactionManager) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostedTransactionResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionReader)
                .processor(transactionPostingProcessor)
                .writer(postingClassifierWriter())
                .listener(rejectCountStepListener())
                .build();
    }

    /**
     * Assembles the Stage&nbsp;1 job from its single step, mirroring {@code POSTTRAN.jcl} (one
     * {@code EXEC PGM=CBTRN02C}, with <strong>no</strong> {@code COND} and no {@code PARM}). The
     * Spring&nbsp;Batch&nbsp;5.x {@link JobBuilder} is used with an explicit {@link JobRepository}.
     *
     * <p>This {@code Job} bean enables standalone execution (e.g. via the auto-configured
     * {@code JobLauncher}); the {@link #dailyTransactionPostingStep(JobRepository,
     * PlatformTransactionManager)} bean is also reused by the {@code BatchPipelineOrchestrator} (Stage 0)
     * as the first stage of the {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT/TRANREPT}
     * pipeline.</p>
     *
     * @param jobRepository               the auto-configured Spring Batch job repository
     * @param dailyTransactionPostingStep the single step assembled by
     *                                    {@link #dailyTransactionPostingStep(JobRepository,
     *                                    PlatformTransactionManager)}
     * @return the configured daily-transaction posting job
     */
    @Bean
    public Job dailyTransactionPostingJob(final JobRepository jobRepository,
                                          final Step dailyTransactionPostingStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(dailyTransactionPostingStep)
                .build();
    }

    /**
     * {@link StepExecutionListener} that reproduces the COBOL
     * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} logic at {@code CBTRN02C}'s {@code GOBACK}.
     *
     * <p>The classifier in {@link #postingClassifierWriter()} calls {@link #recordReject()} exactly once
     * for every record it routes to the reject writer, so {@link #rejectCount} mirrors the COBOL
     * {@code WS-REJECT-COUNT} accumulator. Because this listener is a Spring singleton reused across job
     * runs, {@link #beforeStep(StepExecution)} resets the counter at the start of each step execution.
     * {@link AtomicLong} keeps the count correct even if the step is later configured with a
     * multi-threaded {@code TaskExecutor} (the default posting step is single-threaded).</p>
     *
     * <p>At end-of-step the tally is published to the step {@code ExecutionContext} under
     * {@link #REJECT_COUNT_KEY} (so the orchestrator's {@code JobExecutionDecider} can inspect it), and
     * the {@link ExitStatus} is overridden to {@link #EXIT_CODE_COMPLETED_WITH_REJECTS} only when there
     * was at least one reject AND the step otherwise completed. The {@link BatchStatus} stays
     * {@code COMPLETED}: rejects are success-with-rejects, not a failure (a genuine I/O abend surfaces as
     * a failed step, which leaves the exit status untouched here).</p>
     */
    static final class RejectCountStepListener implements StepExecutionListener {

        /** Per-execution count of rejected records, mirroring COBOL {@code WS-REJECT-COUNT}. */
        private final AtomicLong rejectCount = new AtomicLong();

        /**
         * Records a single rejected record. Invoked by the classifier in
         * {@link DailyTransactionPostingJob#postingClassifierWriter()} once per record routed to the
         * reject writer &mdash; the Java equivalent of COBOL {@code ADD 1 TO WS-REJECT-COUNT}.
         */
        void recordReject() {
            rejectCount.incrementAndGet();
        }

        /**
         * Resets the reject tally at the start of each step execution. Required because this listener is
         * a context-wide singleton reused across job runs; without the reset, a prior run's rejects would
         * leak into a subsequent execution.
         *
         * @param stepExecution the starting step execution
         */
        @Override
        public void beforeStep(final StepExecution stepExecution) {
            rejectCount.set(0L);
        }

        /**
         * Publishes the reject tally and reproduces {@code CBTRN02C}'s
         * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}. The tally is always recorded to the step
         * {@code ExecutionContext}; the {@link ExitStatus} is changed to
         * {@link #EXIT_CODE_COMPLETED_WITH_REJECTS} only when at least one record was rejected and the
         * step completed normally, so downstream stages still PROCEED (RC=4 is not a failure).
         *
         * @param stepExecution the completed step execution
         * @return {@link #EXIT_CODE_COMPLETED_WITH_REJECTS} when there were rejects on an otherwise
         *         completed step; otherwise the step's existing {@link ExitStatus}
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            final long rejects = rejectCount.get();
            // Publish WS-REJECT-COUNT for the orchestrator's JobExecutionDecider / operational tooling.
            stepExecution.getExecutionContext().putLong(REJECT_COUNT_KEY, rejects);
            if (rejects > 0L && stepExecution.getStatus() == BatchStatus.COMPLETED) {
                // COBOL: MOVE 4 TO RETURN-CODE. BatchStatus stays COMPLETED; only the ExitStatus carries
                // the "success-with-rejects" signal so the pipeline proceeds, exactly as RC=4 did in JCL.
                return new ExitStatus(EXIT_CODE_COMPLETED_WITH_REJECTS);
            }
            return stepExecution.getExitStatus();
        }
    }
}
