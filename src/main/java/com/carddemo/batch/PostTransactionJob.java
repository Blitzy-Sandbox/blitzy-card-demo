package com.carddemo.batch;

import com.carddemo.entity.DailyTransaction;
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
 * Spring Batch {@link Configuration} for pipeline stage&nbsp;1 &mdash; the <strong>transaction
 * posting</strong> stage (job/step config&nbsp;#1 of&nbsp;5, {@code POSTTRAN}). It wires the
 * chunk-oriented {@code postTransactionStep} and the standalone {@code postTransactionJob},
 * reproducing the legacy {@code CBTRN02C} batch program launched by {@code POSTTRAN.jcl}
 * ({@code EXEC PGM=CBTRN02C}). Pipeline order (AAP&nbsp;&sect;0.8.5): <strong>stage&nbsp;1
 * {@code POSTTRAN} &rarr; {@code INTCALC} &rarr; {@code COMBTRAN} &rarr;
 * {@code CREASTMT}/{@code TRANREPT}</strong>.
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f})</h2>
 * <p>The source artifacts are <strong>not</strong> copied into this repository; they are referenced
 * by commit SHA for traceability only (see {@code docs/traceability-matrix.md}). The migrated job is
 * a one-to-one translation of {@code app/jcl/POSTTRAN.jcl}, whose single step drives {@code CBTRN02C}
 * over six data sets:</p>
 *
 * <pre>
 *   //STEP15   EXEC PGM=CBTRN02C
 *   //DALYTRAN DD DISP=SHR,DSN=AWS.M2.CARDDEMO.DALYTRAN.PS            (daily transaction input)
 *   //TRANFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS    (transaction master output)
 *   //XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS    (card cross-reference lookup)
 *   //ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS    (account master update)
 *   //TCATBALF DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS    (txn category balance)
 *   //DALYREJS DD DISP=(NEW,CATLG,DELETE),DCB=(RECFM=F,LRECL=430),
 *   //            DSN=AWS.M2.CARDDEMO.DALYREJS(+1)                   (430-byte reject file, GDG +1)
 * </pre>
 *
 * <p>{@code CBTRN02C}'s {@code PERFORM UNTIL END-OF-FILE} read loop over {@code DALYTRAN}
 * (paragraphs 1000&ndash;2900: read, validate, look up cross-reference/account, apply the balance
 * updates, and either post or reject) is realized as the standard Spring Batch
 * {@code reader &rarr; processor &rarr; writer} chunk shape, one item per daily-transaction record:</p>
 * <ul>
 *   <li><strong>Read ({@code DALYTRAN}, paragraph&nbsp;1000)</strong> &mdash;
 *       {@link DailyTransactionItemReader} streams the staged daily-transaction rows; the reader
 *       returns {@code null} at end-of-input, which is the idiomatic translation of the COBOL EOF
 *       {@code FILE STATUS '10'} loop-termination condition (EOF is <em>not</em> an exception).</li>
 *   <li><strong>Validate &amp; post (paragraphs 1500&ndash;2800)</strong> &mdash;
 *       {@link PostTransactionProcessor} performs the cross-reference/account lookups, the
 *       {@code EVALUATE}-driven validation, and the account and transaction-category balance updates
 *       (2700/2800), emitting a {@link PostingResult} that carries either a posted transaction or a
 *       business reject verdict. It never returns {@code null} for a valid input, so no record is
 *       silently filtered out of the chunk (parity, see below).</li>
 *   <li><strong>Persist &amp; reject ({@code TRANFILE} + {@code DALYREJS}, paragraph&nbsp;2900)</strong>
 *       &mdash; {@link PostTransactionItemWriter} persists posted transactions to the transaction
 *       master and appends business rejects to the 430-byte reject object (the {@code DALYREJS(+1)}
 *       GDG analogue, versioned in S3), publishing the running reject count into the step execution
 *       context.</li>
 * </ul>
 *
 * <h2>Parity contract &mdash; reject handling and {@code RETURN-CODE 4}</h2>
 * <p>{@code CBTRN02C} does not abend on a business reject: it writes the offending record to
 * {@code DALYREJS}, continues the loop, and sets {@code RETURN-CODE 4} at end-of-job when at least one
 * record was rejected ({@code IF WS-REJECT-COUNT > 0}). To preserve this behavior exactly
 * (Gate&nbsp;1/Gate&nbsp;4 byte-parity), this configuration deliberately declares <strong>no
 * skip/retry policy</strong>: business rejects are <em>data</em> handled by the writer, never Spring
 * Batch skips (which would silently drop records and lose the reject-file output). A retry policy
 * would only ever be justified for genuinely transient <em>infrastructure</em> faults and only if the
 * chunk were idempotent; none is configured here.</p>
 * <p>The reject count is surfaced by {@link PostTransactionItemWriter} into the step execution context
 * under {@link PostTransactionItemWriter#REJECT_COUNT_KEY}. The writer's
 * {@code afterStep} intentionally leaves the step exit status unchanged (returns {@code null}); the
 * mapping of {@code rejectCount > 0 &rarr;} a non-OK exit status (the {@code RETURN-CODE 4}
 * disposition) is owned by {@code config/BatchConfig}'s {@code JobExecutionDecider} when this step is
 * composed into the master five-stage flow. This class keeps that mapping out of scope by design and
 * documents the contract here rather than duplicating the COND-code logic.</p>
 *
 * <h2>Transaction boundary (per-chunk atomicity)</h2>
 * <p>The chunk transaction, bounded by the injected {@link PlatformTransactionManager}, spans the
 * processor's balance updates (paragraphs&nbsp;2700/2800) together with the writer's transaction
 * persist (paragraph&nbsp;2900). All items in a chunk therefore commit or roll back atomically,
 * matching the legacy program's unit-of-work granularity; the commit interval equals
 * {@link #chunkSize}.</p>
 *
 * <h2>Wiring notes</h2>
 * <ul>
 *   <li>This configuration deliberately does <strong>not</strong> declare
 *       {@code @EnableBatchProcessing}; under Spring Boot 3.x that annotation would <em>disable</em>
 *       Boot's Batch auto-configuration. The {@link JobRepository} and
 *       {@link PlatformTransactionManager} are the auto-configured beans, injected by constructor
 *       (the Spring Batch 5.x replacement for the removed {@code JobBuilderFactory} /
 *       {@code StepBuilderFactory}).</li>
 *   <li>The three chunk components are constructor-injected by type as their concrete
 *       {@code @Component}s: {@link DailyTransactionItemReader}, {@link PostTransactionProcessor}, and
 *       {@link PostTransactionItemWriter}. The writer is {@code @StepScope} and additionally implements
 *       {@link org.springframework.batch.core.StepExecutionListener} and
 *       {@link org.springframework.batch.item.ItemStream}; when it is supplied to
 *       {@code .writer(...)}, Spring Batch's {@code SimpleStepBuilder} automatically registers it as
 *       both a step stream and a step-execution listener (via its scoped proxy), so no manual
 *       {@code .listener(...)}/{@code .stream(...)} registration is required or performed here.</li>
 *   <li>The job registers {@link BatchCorrelationIdListener} (Observability rule, AAP&nbsp;&sect;0.7.1)
 *       so every log line emitted during the run carries the MDC {@code correlationId}, and a
 *       {@link RunIdIncrementer} so each launch is a distinct, re-runnable {@code JobInstance} &mdash;
 *       the on-demand-rerun / idempotency analogue of the JCL rerun semantics (AAP&nbsp;&sect;0.8.5).</li>
 *   <li>The named {@code postTransactionStep} bean is consumed by {@code config/BatchConfig} to compose
 *       the master five-stage flow; the {@code postTransactionJob} bean allows this stage to be
 *       launched and tested independently. No job is auto-run at startup
 *       ({@code spring.batch.job.enabled=false}, declared in {@code application.yml}); launching is
 *       explicit. This class does not override that setting.</li>
 *   <li>The chunk size is externalized from the JCL {@code SYSIN}/PARM model to the Spring property
 *       {@code carddemo.batch.chunk-size} (default {@code 100}).</li>
 *   <li>The bean methods reference one another as sibling calls (the job starts the step), which relies
 *       on the CGLIB proxying of a full {@code @Configuration} ({@code proxyBeanMethods = true}, the
 *       default) to return the managed singletons.</li>
 * </ul>
 *
 * <p><strong>Bean naming.</strong> The configuration class is explicitly named
 * {@code "postTransactionJobConfig"} so that the class's own component bean name does not collide with
 * the {@code postTransactionJob} {@link Job} bean it exposes (the decapitalized class name and that
 * bean name would otherwise be identical, which Spring Boot rejects under
 * {@code spring.main.allow-bean-definition-overriding = false}). The two exposed bean names &mdash;
 * {@code postTransactionStep} and {@code postTransactionJob} &mdash; are contractually fixed by the AAP
 * file specification.</p>
 *
 * <p>This class is pure wiring: it holds no business logic, performs no arithmetic, and defines no
 * monetary fields, so no {@code float}/{@code double} decimal-precision concerns apply
 * (AAP&nbsp;&sect;0.8.2).</p>
 *
 * @see DailyTransactionItemReader
 * @see PostTransactionProcessor
 * @see PostTransactionItemWriter
 * @see PostingResult
 * @see BatchCorrelationIdListener
 */
@Configuration("postTransactionJobConfig")
public class PostTransactionJob {

    /** Spring Boot auto-configured Spring Batch job repository, backing the job and step meta-data. */
    private final JobRepository jobRepository;

    /** Spring Boot auto-configured transaction manager bounding each chunk's read/commit window. */
    private final PlatformTransactionManager transactionManager;

    /** Reader streaming the staged daily-transaction records (the {@code DALYTRAN} PS input). */
    private final DailyTransactionItemReader dailyTransactionItemReader;

    /** Processor performing validation, lookups, and balance updates for one daily transaction. */
    private final PostTransactionProcessor postTransactionProcessor;

    /** Writer persisting posted transactions and appending business rejects to the 430-byte object. */
    private final PostTransactionItemWriter postTransactionItemWriter;

    /** Listener that seeds the per-execution MDC correlation id; registered on the posting job. */
    private final BatchCorrelationIdListener batchCorrelationIdListener;

    /**
     * Chunk size and commit interval, externalized from the JCL {@code SYSIN}/PARM model to the Spring
     * property {@code carddemo.batch.chunk-size}. Defaults to {@code 100} when the property is not
     * supplied by the active profile.
     */
    private final int chunkSize;

    /**
     * Creates the posting-stage configuration with all collaborators injected by constructor (the
     * single constructor is auto-detected by Spring, so no {@code @Autowired} is required).
     *
     * @param jobRepository              the auto-configured Spring Batch job repository
     * @param transactionManager         the auto-configured platform transaction manager
     * @param dailyTransactionItemReader the reader streaming the staged daily-transaction records
     * @param postTransactionProcessor   the processor validating and posting a single transaction
     * @param postTransactionItemWriter  the writer persisting posts and appending business rejects
     * @param batchCorrelationIdListener the correlation-id listener registered on the posting job
     * @param chunkSize                  the chunk size / commit interval
     *                                   ({@code carddemo.batch.chunk-size}, default {@code 100})
     */
    PostTransactionJob(final JobRepository jobRepository,
                       final PlatformTransactionManager transactionManager,
                       final DailyTransactionItemReader dailyTransactionItemReader,
                       final PostTransactionProcessor postTransactionProcessor,
                       final PostTransactionItemWriter postTransactionItemWriter,
                       final BatchCorrelationIdListener batchCorrelationIdListener,
                       @Value("${carddemo.batch.chunk-size:100}") final int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dailyTransactionItemReader = dailyTransactionItemReader;
        this.postTransactionProcessor = postTransactionProcessor;
        this.postTransactionItemWriter = postTransactionItemWriter;
        this.batchCorrelationIdListener = batchCorrelationIdListener;
        this.chunkSize = chunkSize;
    }

    /**
     * The transaction-posting chunk step: reads staged daily transactions
     * ({@link DailyTransactionItemReader}), validates and posts each one
     * ({@link PostTransactionProcessor}), and persists posts while appending business rejects
     * ({@link PostTransactionItemWriter}) &mdash; the migrated {@code CBTRN02C} read/validate/post
     * loop.
     *
     * <p>The chunk is explicitly typed {@code <DailyTransaction, PostingResult>} (input records to
     * posting verdicts) and its commit interval equals {@link #chunkSize}, bounding the per-chunk
     * transaction that spans the processor's balance updates and the writer's persist. No skip or
     * retry policy is attached: business rejects are handled as data by the writer, preserving every
     * input's outcome for byte-parity (see the class-level parity contract).</p>
     *
     * @return the fully built {@code postTransactionStep}
     */
    @Bean
    Step postTransactionStep() {
        return new StepBuilder("postTransactionStep", jobRepository)
                .<DailyTransaction, PostingResult>chunk(chunkSize, transactionManager)
                .reader(dailyTransactionItemReader)
                .processor(postTransactionProcessor)
                .writer(postTransactionItemWriter)
                .build();
    }

    /**
     * The standalone posting job wrapping {@link #postTransactionStep()}. It registers a
     * {@link RunIdIncrementer} (so each launch is a distinct, re-runnable {@code JobInstance}, matching
     * the JCL rerun semantics) and the {@link BatchCorrelationIdListener} (so every log line emitted
     * during the run carries the MDC {@code correlationId}). No job is auto-run on startup
     * ({@code spring.batch.job.enabled=false}); launching is driven explicitly &mdash; either directly
     * for independent testing or via {@code config/BatchConfig}'s master flow, which reuses the same
     * {@code postTransactionStep} bean.
     *
     * @return the fully built {@code postTransactionJob}
     */
    @Bean
    Job postTransactionJob() {
        return new JobBuilder("postTransactionJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchCorrelationIdListener)
                .start(postTransactionStep())
                .build();
    }
}
