package com.carddemo.batch;

import com.carddemo.entity.Transaction;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@link Configuration} for pipeline stage&nbsp;5 &mdash; the <strong>transaction
 * detail report</strong> stage (job/step config&nbsp;#5 of&nbsp;5). It wires the chunk-oriented
 * {@code transactionReportStep} and the standalone {@code transactionReportJob}, reproducing the
 * legacy {@code TRANREPT} mainframe job (a DFSORT filter/sort pre-step feeding the report program
 * {@code CBTRN03C}). This stage runs in parallel with {@code CREASTMT} (the statement stage) and is
 * <em>also</em> launched on demand via the SQS FIFO report trigger.
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f})</h2>
 * <p>The source artifacts are <strong>not</strong> copied into this repository; they are referenced
 * by commit SHA for traceability only (see {@code docs/traceability-matrix.md}). This job is the
 * structured-Java translation of {@code app/jcl/TRANREPT.jcl} (which reuses the shared
 * {@code app/proc/TRANREPT.prc} procedure) and the report program {@code app/cbl/CBTRN03C.cbl}:</p>
 *
 * <pre>
 *   //STEP05R  EXEC PGM=SORT
 *   //SYMNAMES DD *
 *   TRAN-CARD-NUM,263,16,ZD                            (sort key: card number)
 *   TRAN-PROC-DT,305,10,CH                             (filter key: processing date)
 *   PARM-START-DATE,C'2022-01-01'                      (inclusive window lower bound)
 *   PARM-END-DATE,C'2022-07-06'                        (inclusive window upper bound)
 *   //SYSIN    DD *
 *    SORT FIELDS=(TRAN-CARD-NUM,A)                     (ascending by 16-char card number)
 *    INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,
 *            TRAN-PROC-DT,LE,PARM-END-DATE)            (keep iff GE start AND LE end)
 *
 *   //STEP10R  EXEC PGM=CBTRN03C                       (produce the 133-char formatted report)
 *   //TRANREPT DD ... DCB=(LRECL=133,RECFM=FB,...)     (fixed-width report dataset)
 * </pre>
 *
 * <h2>Chunk-step realization (AAP&nbsp;&sect;0.4.3, &sect;0.8.5 batch-pipeline rules)</h2>
 * <p>The three legacy responsibilities are split across the standard Spring Batch
 * {@code reader &rarr; processor &rarr; writer} chunk shape, one collaborator per concern:</p>
 * <ul>
 *   <li><strong>Sort ({@code STEP05R} {@code SORT FIELDS=(TRAN-CARD-NUM,A)})</strong> &mdash;
 *       realized by {@link TransactionReportItemReader}, whose paged query orders rows by
 *       {@code tranCardNum} ascending (the DFSORT key) then {@code tranId} ascending (a unique
 *       tie-breaker that makes the paged read deterministic and restartable). The reader treats
 *       exhaustion as the idiomatic {@code null}-on-EOF signal, mirroring the COBOL
 *       {@code FILE STATUS '10'} handling of {@code 1000-TRANFILE-GET-NEXT}.</li>
 *   <li><strong>Filter + enrich ({@code STEP05R INCLUDE COND} and {@code CBTRN03C}
 *       {@code 1500-A/B/C} lookups)</strong> &mdash; realized by {@link TransactionReportProcessor},
 *       which drops rows outside the inclusive {@code [startDate, endDate]} window by returning
 *       {@code null} (the Spring Batch "filter this item" signal) and, for surviving rows, resolves
 *       the owning account plus the transaction-type and category descriptions into a structured
 *       {@link ReportDetailLine}.</li>
 *   <li><strong>Format + totals ({@code CBTRN03C} report paragraphs
 *       {@code 1100}/{@code 1110}/{@code 1120})</strong> &mdash; realized by
 *       {@link TransactionReportItemWriter}, a stateful writer that emits the 133-character report
 *       (matching {@code LRECL=133, RECFM=FB}) with per-page, per-account and grand totals and
 *       publishes it to the batch-output S3 bucket.</li>
 * </ul>
 *
 * <h2>Report window and the SQS report trigger</h2>
 * <p>The reporting date window arrives as the job parameters {@code startDate} / {@code endDate},
 * defaulting from the properties {@code carddemo.batch.report.start-date} ({@code 2022-01-01}) and
 * {@code carddemo.batch.report.end-date} ({@code 2022-07-06}) &mdash; the migrated JCL
 * {@code PARM-START-DATE} / {@code PARM-END-DATE} symbols. The {@code ReportJobLauncher} (the
 * consumer of the {@code carddemo-report-jobs.fifo} SQS queue, the migrated {@code CORPT00C} CICS
 * TDQ&rarr;JES bridge) builds a {@code JobParameters} carrying those two dates plus an identifying
 * {@code jobId} (the FIFO de-duplication key, which also gives each genuine request a distinct,
 * restartable {@code JobInstance}) and a non-identifying {@code correlationId} (consumed by
 * {@link BatchCorrelationIdListener}), then calls {@code JobLauncher.run(transactionReportJob, params)}.
 * That direct-launch path does <strong>not</strong> auto-apply this job's {@link RunIdIncrementer}
 * (the incrementer is consulted only on the operator {@code getNextJobParameters()} path), so the
 * launcher deliberately supplies no {@code run.id}; instance identity comes from {@code jobId} plus the
 * date window. Both step-scoped collaborators read the date-window parameters:
 * {@link TransactionReportProcessor} to filter rows to the window, and
 * {@link TransactionReportItemWriter} to render the matching "Date Range" in the report name header
 * (byte-parity with {@code CBTRN03C}'s {@code MOVE WS-START-DATE TO REPT-START-DATE}). This
 * configuration merely names the job {@code "transactionReportJob"} so the launcher can inject it by
 * name.</p>
 *
 * <h2>Wiring notes</h2>
 * <ul>
 *   <li>This configuration deliberately does <strong>not</strong> declare
 *       {@code @EnableBatchProcessing}; the {@link JobRepository} and
 *       {@link PlatformTransactionManager} are the Spring Boot auto-configured beans, injected by
 *       constructor.</li>
 *   <li>The reader is an ordinary singleton component, whereas the processor and writer are
 *       {@code @StepScope}. They are injected here by their concrete types and resolved as
 *       step-scoped proxies, so a fresh processor/writer backs each step execution while this
 *       configuration remains a singleton; the {@code @StepScope} writer is an
 *       {@code ItemStreamWriter} and is registered as a step stream automatically by the builder.</li>
 *   <li>The job registers {@link BatchCorrelationIdListener} (Observability rule, AAP&nbsp;&sect;0.7.1)
 *       so every log line emitted during the run carries the MDC {@code correlationId}, and a
 *       {@link RunIdIncrementer} so each launch is a distinct, re-runnable {@code JobInstance} (the
 *       JCL rerun analogue). No job is auto-run on startup; launching is explicit (the SQS bridge or
 *       a scheduler), consistent with the other pipeline stages.</li>
 *   <li>The chunk size / commit interval is externalized from the JCL {@code SYSIN}/PARM model to the
 *       Spring property {@code carddemo.batch.chunk-size} (default {@code 100}); the same value is the
 *       reader's page size, so each page read maps to exactly one chunk.</li>
 * </ul>
 *
 * <p><strong>Bean naming.</strong> The configuration class is explicitly named
 * {@code "transactionReportJobConfig"} so that the class's own component bean name does not collide
 * with the {@code transactionReportJob} {@link Job} bean it exposes (the decapitalized class name and
 * that bean name would otherwise be identical, which Spring Boot rejects under
 * {@code spring.main.allow-bean-definition-overriding = false}). The two exposed bean names &mdash;
 * {@code transactionReportStep} and {@code transactionReportJob} &mdash; are contractually fixed by
 * the AAP file specification.</p>
 *
 * <p>This class is pure wiring: it holds no business logic, performs no arithmetic, and defines no
 * monetary fields, so no {@code float}/{@code double} decimal-precision concerns apply
 * (AAP&nbsp;&sect;0.8.2). The report totals are {@link java.math.BigDecimal} of scale&nbsp;2 and live
 * entirely in {@link TransactionReportItemWriter}; the filter and enrichment live in
 * {@link TransactionReportProcessor}.</p>
 *
 * @see TransactionReportItemReader
 * @see TransactionReportProcessor
 * @see TransactionReportItemWriter
 * @see ReportDetailLine
 * @see BatchCorrelationIdListener
 * @see Transaction
 */
@Configuration("transactionReportJobConfig")
public class TransactionReportJob {

    /** Spring Boot auto-configured Spring Batch job repository, backing the job and step meta-data. */
    private final JobRepository jobRepository;

    /** Spring Boot auto-configured transaction manager bounding each chunk's read/commit window. */
    private final PlatformTransactionManager transactionManager;

    /** Listener that seeds the per-execution MDC correlation id; registered on the report job. */
    private final BatchCorrelationIdListener batchCorrelationIdListener;

    /** Reader supplying transactions ordered ascending by {@code tranCardNum} (the DFSORT key). */
    private final TransactionReportItemReader transactionReportItemReader;

    /** Step-scoped processor applying the date-window filter and building each {@link ReportDetailLine}. */
    private final TransactionReportProcessor transactionReportProcessor;

    /** Step-scoped stateful writer emitting the 133-char report with running totals to S3. */
    private final TransactionReportItemWriter transactionReportItemWriter;

    /**
     * Chunk size and commit interval, externalized from the JCL {@code SYSIN}/PARM model to the
     * Spring property {@code carddemo.batch.chunk-size}. Defaults to {@code 100} when the property is
     * not supplied by the active profile.
     */
    private final int chunkSize;

    /**
     * Creates the report-stage configuration with all collaborators injected by constructor (the
     * single constructor is auto-detected by Spring, so no {@code @Autowired} is required).
     *
     * @param jobRepository                the auto-configured Spring Batch job repository
     * @param transactionManager           the auto-configured platform transaction manager
     * @param batchCorrelationIdListener   the correlation-id listener registered on the report job
     * @param transactionReportItemReader  the reader streaming transactions ascending by card number
     * @param transactionReportProcessor   the step-scoped date-window filter / enrichment processor
     * @param transactionReportItemWriter  the step-scoped stateful writer emitting the S3 report
     * @param chunkSize                    the chunk size / commit interval
     *                                     ({@code carddemo.batch.chunk-size}, default {@code 100})
     */
    TransactionReportJob(final JobRepository jobRepository,
                         final PlatformTransactionManager transactionManager,
                         final BatchCorrelationIdListener batchCorrelationIdListener,
                         final TransactionReportItemReader transactionReportItemReader,
                         final TransactionReportProcessor transactionReportProcessor,
                         final TransactionReportItemWriter transactionReportItemWriter,
                         @Value("${carddemo.batch.chunk-size:100}") final int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchCorrelationIdListener = batchCorrelationIdListener;
        this.transactionReportItemReader = transactionReportItemReader;
        this.transactionReportProcessor = transactionReportProcessor;
        this.transactionReportItemWriter = transactionReportItemWriter;
        this.chunkSize = chunkSize;
    }

    /**
     * The report-stage chunk step: reads transactions ascending by {@code tranCardNum}
     * ({@link TransactionReportItemReader}), filters them to the reporting date window and enriches
     * each survivor to a {@link ReportDetailLine} ({@link TransactionReportProcessor}), and renders
     * the fixed-width report with running totals ({@link TransactionReportItemWriter}).
     *
     * <p>The chunk is explicitly typed {@code <Transaction, ReportDetailLine>} (input entity to output
     * detail line), and the commit interval equals {@link #chunkSize}. The step-scoped processor and
     * writer are supplied as scoped proxies, so a fresh instance backs each step execution.</p>
     *
     * @return the fully built {@code transactionReportStep}
     */
    @Bean
    Step transactionReportStep() {
        return new StepBuilder("transactionReportStep", jobRepository)
                .<Transaction, ReportDetailLine>chunk(chunkSize, transactionManager)
                .reader(transactionReportItemReader)
                .processor(transactionReportProcessor)
                .writer(transactionReportItemWriter)
                .build();
    }

    /**
     * The standalone transaction-report job wrapping {@link #transactionReportStep()}. It registers a
     * {@link RunIdIncrementer} (so each launch is a distinct, re-runnable {@code JobInstance}, matching
     * the JCL rerun semantics) and the {@link BatchCorrelationIdListener} (so every log line emitted
     * during the run carries the MDC {@code correlationId}). No job is auto-run on startup; launching
     * is driven explicitly &mdash; on demand or by {@code ReportJobLauncher} consuming the
     * {@code carddemo-report-jobs.fifo} SQS queue &mdash; which supplies the {@code startDate} /
     * {@code endDate} window and {@code correlationId} as job parameters.
     *
     * <p>The bean name is contractually fixed as {@code "transactionReportJob"} so
     * {@code ReportJobLauncher} can inject it by name.</p>
     *
     * @return the fully built {@code transactionReportJob}
     */
    @Bean
    Job transactionReportJob() {
        return new JobBuilder("transactionReportJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchCorrelationIdListener)
                .start(transactionReportStep())
                .build();
    }
}
