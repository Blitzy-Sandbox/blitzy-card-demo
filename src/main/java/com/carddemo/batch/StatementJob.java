package com.carddemo.batch;

import com.carddemo.entity.CardXref;
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
 * Spring Batch {@link Configuration} for pipeline stage&nbsp;4 &mdash; the <strong>statement
 * generation</strong> stage (job/step config&nbsp;#4 of&nbsp;5, {@code CREASTMT}). It wires the
 * chunk-oriented {@code statementStep} and the standalone {@code statementJob}, reproducing the
 * legacy {@code CBSTM03A} batch program launched by {@code CREASTMT.jcl}
 * ({@code STEP040 EXEC PGM=CBSTM03A}). Pipeline order (AAP&nbsp;&sect;0.8.5): {@code POSTTRAN}
 * &rarr; {@code INTCALC} &rarr; {@code COMBTRAN} &rarr; <strong>stage&nbsp;4 {@code CREASTMT}</strong>
 * / {@code TRANREPT}. This stage runs <em>in parallel</em> with {@code TRANREPT} (the transaction
 * report stage, {@code TransactionReportJob}).
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f})</h2>
 * <p>The source artifacts are <strong>not</strong> copied into this repository; they are referenced
 * by commit SHA for traceability only (see {@code docs/traceability-matrix.md}). The migrated job is
 * a one-to-one translation of {@code app/jcl/CREASTMT.jcl}, whose {@code STEP040} drives
 * {@code CBSTM03A} &mdash; a program whose stated purpose is to
 * &ldquo;Print Account Statements from Transaction data in two formats: 1/plain text and
 * 2/HTML&rdquo; &mdash; over its statement inputs and two fixed-block output datasets:</p>
 *
 * <pre>
 *   //STEP040  EXEC PGM=CBSTM03A
 *   //TRNXFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS       (per-card transactions)
 *   //XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS    (driving card cross-reference)
 *   //ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS    (account master lookup)
 *   //CUSTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS    (customer master lookup)
 *   //STMTFILE DD ... DCB=(LRECL=80,...,RECFM=FB)                    (plain-text statement, .PS)
 *   //HTMLFILE DD ... DCB=(LRECL=100,...,RECFM=FB)                   (HTML statement, .HTML)
 * </pre>
 *
 * <p>{@code CBSTM03A}'s {@code 1000-MAINLINE} {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop &mdash;
 * which reads the card cross-reference sequentially ({@code 1000-XREFFILE-GET-NEXT}), joins the
 * owning customer and account, assembles the per-card transactions, and emits <em>one statement per
 * card</em> in two parallel renderings ({@code 5000-CREATE-STATEMENT}) &mdash; is realized as the
 * standard Spring Batch {@code reader &rarr; processor &rarr; writer} chunk shape, exactly one item
 * per card ({@code one card == one statement}):</p>
 * <ul>
 *   <li><strong>Read ({@code XREFFILE}, paragraph&nbsp;1000)</strong> &mdash;
 *       {@link StatementCardXrefItemReader} streams the {@link CardXref} rows in ascending
 *       card-number order; the reader returns {@code null} at end-of-input, the idiomatic
 *       translation of the COBOL EOF {@code FILE STATUS '10'} loop-termination condition (EOF is
 *       <em>not</em> an exception, AAP&nbsp;&sect;0.8.3).</li>
 *   <li><strong>Assemble (paragraphs 2000&ndash;6000)</strong> &mdash; {@link StatementProcessor}
 *       joins the customer and account and formats one {@link StatementDocument} carrying both the
 *       plain-text and HTML renderings. Per the CALL&rarr;bean mandate (AAP&nbsp;&sect;0.4.3), the
 *       legacy {@code CALL 'CBSTM03B'} file-access linkage (13 call sites in {@code CBSTM03A}) is
 *       realized as the constructor-injected {@code StatementFileService} bean <em>inside</em> the
 *       processor &mdash; not in this configuration.</li>
 *   <li><strong>Emit ({@code STMTFILE} + {@code HTMLFILE}, {@code OPEN}/{@code WRITE}/{@code CLOSE})</strong>
 *       &mdash; {@link StatementItemWriter} accumulates every card's rendered lines and ships the two
 *       aggregate files to versioned S3 objects (the {@code carddemo-batch-statements} bucket) as
 *       fixed-block records: {@code LRECL=80} for the {@code .PS} text file and {@code LRECL=100}
 *       for the {@code .HTML} file, preserving the byte-for-byte record contract
 *       (AAP&nbsp;Gate&nbsp;1/5).</li>
 * </ul>
 *
 * <h2>Chunk shape and transaction boundary</h2>
 * <p>The chunk is explicitly typed {@code <CardXref, StatementDocument>} (one driving cross-reference
 * row to one assembled statement) and its commit interval equals {@link #chunkSize}. The step is
 * read-mostly: the processor and the injected {@code StatementFileService} perform
 * {@code @Transactional(readOnly = true)} reads, and the writer defers all output to a single
 * end-of-step S3 upload on {@code close()}. The chunk transaction bounded by the injected
 * {@link PlatformTransactionManager} therefore frames the read/assemble window; the aggregate write
 * is intentionally all-or-nothing and versioned, matching the JCL rerun semantics of
 * {@code CREASTMT.jcl} (its {@code STEP030} deletes the prior {@code .PS}/{@code .HTML} outputs and
 * {@code STEP040} regenerates them wholesale &mdash; AAP&nbsp;&sect;0.8.5).</p>
 *
 * <h2>Wiring notes</h2>
 * <ul>
 *   <li>This configuration deliberately does <strong>not</strong> declare
 *       {@code @EnableBatchProcessing}; under Spring Boot 3.x that annotation would <em>disable</em>
 *       Boot's Batch auto-configuration. The {@link JobRepository} and
 *       {@link PlatformTransactionManager} are the auto-configured beans, injected by constructor
 *       (the Spring Batch 5.x replacement for the removed {@code JobBuilderFactory} /
 *       {@code StepBuilderFactory}).</li>
 *   <li>The three chunk components are constructor-injected by their concrete types:
 *       {@link StatementCardXrefItemReader} (an ordinary singleton {@link org.springframework.stereotype.Component}),
 *       {@link StatementProcessor} (a stateless singleton), and {@link StatementItemWriter} (a
 *       {@code @StepScope} {@link org.springframework.batch.item.ItemStreamWriter}). The step-scoped
 *       writer is resolved as a scoped proxy, so a fresh instance &mdash; and therefore fresh output
 *       buffers &mdash; backs each step execution while this configuration remains a singleton. Because
 *       the writer is an {@link org.springframework.batch.item.ItemStream}, Spring Batch's
 *       {@code SimpleStepBuilder} registers it as a step stream automatically when it is supplied to
 *       {@code .writer(...)}, so its {@code open}/{@code update}/{@code close} lifecycle is driven by
 *       the step with no manual {@code .stream(...)} registration required here.</li>
 *   <li>The job registers {@link BatchCorrelationIdListener} (Observability rule, AAP&nbsp;&sect;0.7.1)
 *       so every log line emitted during the run carries the MDC {@code correlationId}, and a
 *       {@link RunIdIncrementer} so each launch is a distinct, re-runnable {@code JobInstance} &mdash;
 *       the on-demand-rerun / idempotency analogue of the JCL rerun semantics (AAP&nbsp;&sect;0.8.5).</li>
 *   <li>The named {@code statementStep} bean is consumed by {@code config/BatchConfig} to compose the
 *       master five-stage flow; the {@code statementJob} bean allows this stage to be launched and
 *       tested independently. No job is auto-run at startup
 *       ({@code spring.batch.job.enabled=false}, declared in {@code application.yml}); launching is
 *       explicit. This class does not override that setting.</li>
 *   <li>The chunk size is externalized from the JCL {@code SYSIN}/PARM model to the Spring property
 *       {@code carddemo.batch.chunk-size} (default {@code 100}); the same value is the reader's fetch
 *       page size, so each page read maps to exactly one chunk.</li>
 *   <li>The bean methods reference one another as sibling calls (the job starts the step), which
 *       relies on the CGLIB proxying of a full {@code @Configuration}
 *       ({@code proxyBeanMethods = true}, the default) to return the managed singletons.</li>
 * </ul>
 *
 * <p><strong>Bean naming.</strong> The configuration class is explicitly named
 * {@code "statementJobConfig"} so that the class's own component bean name does not collide with the
 * {@code statementJob} {@link Job} bean it exposes (the decapitalized class name and that bean name
 * would otherwise be identical, which Spring Boot rejects under
 * {@code spring.main.allow-bean-definition-overriding = false}). The two exposed bean names &mdash;
 * {@code statementStep} and {@code statementJob} &mdash; are contractually fixed by the AAP file
 * specification.</p>
 *
 * <p>This class is pure wiring: it holds no business logic, performs no arithmetic, and defines no
 * monetary fields, so no {@code float}/{@code double} decimal-precision concerns apply
 * (AAP&nbsp;&sect;0.8.2). All statement content &mdash; including every monetary value, already
 * formatted from a {@link java.math.BigDecimal} of scale&nbsp;2 &mdash; is produced upstream by
 * {@link StatementProcessor} via the injected {@code StatementFileService}, and shipped by
 * {@link StatementItemWriter}.</p>
 *
 * @see StatementCardXrefItemReader
 * @see StatementProcessor
 * @see StatementItemWriter
 * @see StatementDocument
 * @see BatchCorrelationIdListener
 * @see CardXref
 */
@Configuration("statementJobConfig")
public class StatementJob {

    /** Spring Boot auto-configured Spring Batch job repository, backing the job and step meta-data. */
    private final JobRepository jobRepository;

    /** Spring Boot auto-configured transaction manager bounding each chunk's read/commit window. */
    private final PlatformTransactionManager transactionManager;

    /** Listener that seeds the per-execution MDC correlation id; registered on the statement job. */
    private final BatchCorrelationIdListener batchCorrelationIdListener;

    /** Reader streaming the driving {@link CardXref} rows in ascending card-number order. */
    private final StatementCardXrefItemReader statementCardXrefItemReader;

    /** Processor assembling one {@link StatementDocument} (text + HTML renderings) per card. */
    private final StatementProcessor statementProcessor;

    /** Step-scoped streaming writer aggregating all statements into the dual {@code .PS}/{@code .HTML} S3 objects. */
    private final StatementItemWriter statementItemWriter;

    /**
     * Chunk size and commit interval, externalized from the JCL {@code SYSIN}/PARM model to the Spring
     * property {@code carddemo.batch.chunk-size}. Defaults to {@code 100} when the property is not
     * supplied by the active profile.
     */
    private final int chunkSize;

    /**
     * Creates the statement-stage configuration with all collaborators injected by constructor (the
     * single constructor is auto-detected by Spring, so no {@code @Autowired} is required).
     *
     * @param jobRepository               the auto-configured Spring Batch job repository
     * @param transactionManager          the auto-configured platform transaction manager
     * @param batchCorrelationIdListener  the correlation-id listener registered on the statement job
     * @param statementCardXrefItemReader the reader streaming the driving card cross-reference rows
     * @param statementProcessor          the processor assembling one statement document per card
     * @param statementItemWriter         the step-scoped writer emitting the dual {@code .PS}/{@code .HTML}
     *                                    S3 statement objects
     * @param chunkSize                   the chunk size / commit interval
     *                                    ({@code carddemo.batch.chunk-size}, default {@code 100})
     */
    StatementJob(final JobRepository jobRepository,
                 final PlatformTransactionManager transactionManager,
                 final BatchCorrelationIdListener batchCorrelationIdListener,
                 final StatementCardXrefItemReader statementCardXrefItemReader,
                 final StatementProcessor statementProcessor,
                 final StatementItemWriter statementItemWriter,
                 @Value("${carddemo.batch.chunk-size:100}") final int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchCorrelationIdListener = batchCorrelationIdListener;
        this.statementCardXrefItemReader = statementCardXrefItemReader;
        this.statementProcessor = statementProcessor;
        this.statementItemWriter = statementItemWriter;
        this.chunkSize = chunkSize;
    }

    /**
     * The statement-generation chunk step: reads the driving card cross-reference rows
     * ({@link StatementCardXrefItemReader}), assembles one statement per card
     * ({@link StatementProcessor}), and streams all statements to the dual {@code .PS}/{@code .HTML}
     * S3 objects ({@link StatementItemWriter}) &mdash; the migrated {@code CBSTM03A}
     * read/assemble/emit loop.
     *
     * <p>The chunk is explicitly typed {@code <CardXref, StatementDocument>} (one driving cross-reference
     * row to one assembled statement), and the commit interval equals {@link #chunkSize}. The
     * {@code @StepScope} writer is supplied as a scoped proxy and, being an
     * {@link org.springframework.batch.item.ItemStream}, is registered as a step stream automatically
     * by the builder, so its buffer lifecycle ({@code open}/{@code close}) is driven by the step.</p>
     *
     * @return the fully built {@code statementStep}
     */
    @Bean
    Step statementStep() {
        return new StepBuilder("statementStep", jobRepository)
                .<CardXref, StatementDocument>chunk(chunkSize, transactionManager)
                .reader(statementCardXrefItemReader)
                .processor(statementProcessor)
                .writer(statementItemWriter)
                .build();
    }

    /**
     * The standalone statement job wrapping {@link #statementStep()}. It registers a
     * {@link RunIdIncrementer} (so each launch is a distinct, re-runnable {@code JobInstance}, matching
     * the JCL rerun semantics) and the {@link BatchCorrelationIdListener} (so every log line emitted
     * during the run carries the MDC {@code correlationId}). No job is auto-run on startup
     * ({@code spring.batch.job.enabled=false}); launching is driven explicitly &mdash; either directly
     * for independent testing or via {@code config/BatchConfig}'s master flow, which reuses the same
     * {@code statementStep} bean.
     *
     * @return the fully built {@code statementJob}
     */
    @Bean
    Job statementJob() {
        return new JobBuilder("statementJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchCorrelationIdListener)
                .start(statementStep())
                .build();
    }
}
