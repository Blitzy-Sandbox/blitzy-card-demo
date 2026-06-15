package com.cardemo.batch.jobs;

import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.config.AwsConfig;
import com.cardemo.model.dto.TransactionReportLine;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.IteratorItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@code @Configuration} that assembles <strong>Stage&nbsp;4b</strong> of the greenfield
 * Java&nbsp;25 LTS + Spring Boot&nbsp;3.5.x migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS
 * mainframe application &mdash; the <strong>Transaction Detail Report</strong>.
 *
 * <h2>COBOL/JCL provenance (read-only reference, never copied)</h2>
 * <p>This job is the faithful translation of the JCL job {@code app/jcl/TRANREPT.jcl} executing the
 * batch COBOL program {@code app/cbl/CBTRN03C.cbl}. Both are read at the frozen legacy baseline commit
 * SHA {@code 27d6c6f}; the COBOL/JCL source is <strong>read-only</strong> reference material and is
 * <strong>never copied</strong> into this repository &mdash; traceability is by commit SHA only (AAP
 * &sect;0.7.2). Per the <strong>Minimal Change Clause</strong> (AAP &sect;0.7.1) the observable
 * behaviour of {@code CBTRN03C} is reproduced <em>exactly</em>, including its edge cases, and every
 * technology substitution is documented at its point of change. The application base package is
 * {@code com.cardemo} (decision <strong>D-006</strong> &mdash; deliberately <em>not</em>
 * {@code com.carddemo}).</p>
 *
 * <h2>{@code TRANREPT.jcl} &rarr; this job (three legacy steps, two of them subsumed by the reader)</h2>
 * <p>{@code TRANREPT.jcl} runs three steps; only the third is a COBOL program, and the first two are
 * collapsed into a single repository query by this migration:</p>
 * <ol>
 *   <li><strong>{@code STEP05R EXEC PROC=REPROC}</strong> &mdash; IDCAMS unload of
 *       {@code TRANSACT.VSAM.KSDS} to the GDG {@code TRANSACT.BKUP(+1)}. <em>Subsumed</em>: no separate
 *       unload is needed because the data already lives in the PostgreSQL {@code transaction} table.</li>
 *   <li><strong>{@code STEP05R EXEC PGM=SORT}</strong> &mdash; DFSORT with
 *       {@code SORT FIELDS=(TRAN-CARD-NUM,A)} (ascending by card number) and
 *       {@code INCLUDE COND=(TRAN-PROC-DT GE PARM-START-DATE AND TRAN-PROC-DT LE PARM-END-DATE)} where
 *       {@code PARM-START-DATE=C'2022-01-01'} and {@code PARM-END-DATE=C'2022-07-06'}
 *       (<strong>inclusive both ends</strong>), writing {@code TRANSACT.DALY(+1)}. <em>Subsumed</em>:
 *       {@link TransactionRepository#findByProcessingDateRange(String, String)} reproduces the inclusive
 *       date {@code INCLUDE COND} <strong>and</strong> the {@code SORT FIELDS=(TRAN-CARD-NUM,A)} ordering
 *       in a single call (JPQL {@code WHERE SUBSTRING(...tranProcTs...,1,10) >= :startDate AND <=
 *       :endDate ORDER BY t.tranCardNum}), so the explicit REPROC unload + DFSORT steps are
 *       unnecessary.</li>
 *   <li><strong>{@code STEP10R EXEC PGM=CBTRN03C}</strong> &mdash; the report generator (inputs
 *       {@code TRANFILE}=DALY(+1), {@code CARDXREF}, {@code TRANTYPE}, {@code TRANCATG},
 *       {@code DATEPARM}; output {@code TRANREPT(+1)}, {@code LRECL=133}, GDG). This is the step this job
 *       reproduces: the per-record reference-data enrichment is delegated to
 *       {@link TransactionReportProcessor} and the stateful, paginated report assembly is performed by
 *       the inline {@link TransactionReportWriter}.</li>
 * </ol>
 *
 * <h2>Technology substitutions (documented per Minimal Change Clause, AAP &sect;0.7.1 / &sect;0.1.2)</h2>
 * <ul>
 *   <li><strong>REPROC unload + DFSORT {@code SORT}/{@code INCLUDE} &rarr;
 *       {@code findByProcessingDateRange}.</strong> The inclusive date filter and the card-number sort
 *       are realised SQL-side by the repository query, so no Java-side re-sort or re-filter is performed
 *       here (the card-number ordering is REQUIRED for correct account-break detection in the writer).</li>
 *   <li><strong>{@code DATEPARM} sequential file &rarr; Spring Batch job parameters.</strong> COBOL reads
 *       {@code WS-START-DATE}/{@code WS-END-DATE} ({@code PIC X(10)}, {@code yyyy-MM-dd}) from the
 *       {@code DATEPARM} file ({@code 0550-DATEPARM-READ}). Here they arrive via Spring Batch late binding
 *       as the {@code startDate}/{@code endDate} job parameters (defaults {@value #DEFAULT_START_DATE} /
 *       {@value #DEFAULT_END_DATE}), which is why the reader and writer beans are {@link StepScope
 *       step-scoped}.</li>
 *   <li><strong>{@code TRANREPT(+1)} GDG ({@code LRECL=133} FB) &rarr; a versioned S3 object.</strong>
 *       The fixed-width 133-byte report is written to the configuration-resolved S3 bucket
 *       {@code carddemo-batch-output} (decision <strong>D-003</strong>: GDG generations &rarr; S3 object
 *       versioning). Each 133-byte COBOL record becomes one 133-character line terminated by a newline in
 *       the S3 text object; the 133-character record width is preserved exactly (external-interface
 *       contract, AAP &sect;0.7.2).</li>
 *   <li><strong>{@code FILE STATUS} write error &rarr; propagated exception.</strong> A failed S3 upload
 *       is allowed to propagate so the step fails, mirroring the {@code ABEND}-on-write-error behaviour of
 *       the COBOL sequential {@code WRITE} ({@code 1111-WRITE-REPORT-REC}).</li>
 * </ul>
 *
 * <h2>Two launch paths &mdash; pipeline stage&nbsp;4b and out-of-band report submission</h2>
 * <p>The {@link #transactionReportJob(JobRepository, Step) transactionReportJob} bean is launched on
 * two paths:</p>
 * <ol>
 *   <li><strong>Stage&nbsp;4b of the batch pipeline.</strong> After {@code COMBTRAN} completes,
 *       {@code BatchPipelineOrchestrator} may run statement generation (4a) and this transaction report
 *       (4b) concurrently via {@code FlowBuilder.split(...)} (AAP &sect;0.7.6).</li>
 *   <li><strong>On-demand report submission &mdash; a PUBLISH-ONLY bridge.</strong> The sole
 *       online&rarr;batch bridge {@code CORPT00C} (which on the mainframe issued
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} to enqueue a report job) is migrated by
 *       {@code ReportSubmissionService} ({@code com.cardemo.service.report}) to a single SQS publish
 *       onto {@code carddemo-report-jobs.fifo} (AAP &sect;0.6.3 / &sect;0.4.1; decision
 *       <strong>D-004</strong>). That publish is the migrated deliverable and the full extent of the
 *       bridge in this repository: there is deliberately <strong>NO</strong> in-repo
 *       {@code @SqsListener} that consumes the queue and calls
 *       {@code JobLauncher.run(transactionReportJob, params)}. This faithfully reproduces the legacy
 *       behaviour &mdash; {@code CORPT00C} performed only the {@code WRITEQ}, and the actual job pickup
 *       happened OUT-OF-BAND in JES, never inside the COBOL program. Adding a consumer that launches
 *       this job would introduce behaviour beyond the technology transition (feature expansion barred
 *       by AAP &sect;0.7.2 / &sect;0.7.1); the rationale and the explicit out-of-band-trigger boundary
 *       are recorded in {@code DECISION_LOG.md} (decision <strong>D-012</strong>). This class therefore
 *       exposes only the launchable {@link Job} bean; it is invoked by the orchestrator (path&nbsp;1)
 *       or by an out-of-band / JES-equivalent trigger, exactly as on the mainframe.</li>
 * </ol>
 *
 * <h2>Job-parameter contract</h2>
 * <p>The report is driven by two job parameters, {@code startDate} and {@code endDate} (both
 * {@code yyyy-MM-dd} strings; defaults {@value #DEFAULT_START_DATE} / {@value #DEFAULT_END_DATE}). The
 * orchestrator (Stage&nbsp;4b) and the SQS report bridge (on-demand) MUST supply them when launching this
 * job; when absent the step-scoped {@code @Value} defaults preserve the JCL {@code PARM-START-DATE}/
 * {@code PARM-END-DATE} constants.</p>
 *
 * <h2>Division of responsibility (processor vs. writer)</h2>
 * <p>The single COBOL read loop is split across two Spring Batch components: the
 * {@link TransactionReportProcessor} (a {@code batch/processors} component) owns the per-record work
 * &mdash; the {@code 1500-A/B/C} {@code CARDXREF}/{@code TRANTYPE}/{@code TRANCATG} lookups and the
 * {@code 1120-WRITE-DETAIL} field mapping &mdash; and emits a {@link TransactionReportLine} carrier per
 * qualifying row; this class's inline {@link TransactionReportWriter} owns only the
 * <strong>stateful report assembly</strong> (the {@code 1100}/{@code 1110}/{@code 1120} paragraphs:
 * page breaks at {@value #PAGE_SIZE} lines, per-account subtotals on a card/account break, and the
 * end-of-file page + grand totals). The writer keeps the three running totals as
 * {@link BigDecimal} of scale&nbsp;2 and compares with {@link BigDecimal#compareTo(BigDecimal)}, never
 * {@code float}/{@code double}/{@code equals} (AAP &sect;0.7.3).</p>
 *
 * @see TransactionReportProcessor
 * @see TransactionReportLine
 * @see TransactionRepository#findByProcessingDateRange(String, String)
 * @see Job
 * @see Step
 */
// The decapitalized simple class name "transactionReportJob" would collide with the Job @Bean method of
// the same name below (Spring Boot disables bean-definition overriding by default). Naming the
// @Configuration bean distinctly lets the Job bean keep the natural name "transactionReportJob" (the name
// the pipeline orchestrator and the SQS report bridge resolve).
@Configuration("transactionReportJobConfiguration")
public class TransactionReportJob {

    /**
     * Chunk size for the report step. This is a <strong>tuning</strong> value only and does
     * <strong>not</strong> affect parity: the page-break, account-break and grand-total state spans
     * chunk boundaries and is held by the stateful {@link TransactionReportWriter} (which retains the
     * line counter and the three running totals between {@code write(...)} invocations and performs the
     * end-of-file closing in {@link StepExecutionListener#afterStep(StepExecution)}), so chunking the
     * card-number-ordered stream never splits or mis-breaks the report.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Lines per printed page &mdash; the COBOL {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}. A page
     * break ({@code 1110-WRITE-PAGE-TOTALS} + {@code 1120-WRITE-HEADERS}) fires in
     * {@code 1100-WRITE-TRANSACTION-REPORT} when {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}.
     * The line counter counts <em>every</em> emitted record (headers, details, totals), not just detail
     * lines, so the per-page detail count varies &mdash; this is faithful to {@code CBTRN03C}.
     */
    private static final int PAGE_SIZE = 20;

    /**
     * Default inclusive start of the report date window &mdash; the JCL DFSORT symbol
     * {@code PARM-START-DATE=C'2022-01-01'}. Used when the {@code startDate} job parameter is absent.
     */
    private static final String DEFAULT_START_DATE = "2022-01-01";

    /**
     * Default inclusive end of the report date window &mdash; the JCL DFSORT symbol
     * {@code PARM-END-DATE=C'2022-07-06'}. Used when the {@code endDate} job parameter is absent.
     */
    private static final String DEFAULT_END_DATE = "2022-07-06";

    /**
     * Per-record reference-data + detail-mapping processor &mdash; the sibling {@code batch/processors}
     * component that owns the {@code 1500-A/B/C} {@code CARDXREF}/{@code TRANTYPE}/{@code TRANCATG}
     * lookups and the {@code 1120-WRITE-DETAIL} field mapping (and a belt-and-suspenders date filter). It
     * is {@link StepScope step-scoped} (a CGLIB scoped proxy) so its late-bound {@code startDate}/
     * {@code endDate} job parameters resolve at step-execution time; injecting it here wires it into
     * {@link #transactionReportStep(JobRepository, PlatformTransactionManager, IteratorItemReader,
     * TransactionReportWriter)}.
     */
    private final TransactionReportProcessor transactionReportProcessor;

    /**
     * Transaction repository &mdash; the JPA replacement for the {@code TRANSACT} KSDS. The report reader
     * drives it through {@link TransactionRepository#findByProcessingDateRange(String, String)}, which
     * reproduces the inclusive date {@code INCLUDE COND} and the {@code SORT FIELDS=(TRAN-CARD-NUM,A)}
     * ordering of {@code TRANREPT.jcl} STEP05R in one call.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Strongly-typed holder of the application-owned AWS resource <em>names</em>. The report destination
     * bucket is read from {@code getS3().getBatchOutputBucket()} ({@code carddemo-batch-output}); the
     * bucket name is therefore resolved from configuration and never hardcoded (AAP &sect;0.7.7).
     */
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Spring Cloud AWS S3 abstraction used to write the assembled 133-character report (the
     * {@code TRANREPT(+1)} GDG &rarr; S3 substitution). Auto-configured by
     * {@code spring-cloud-aws-starter-s3} from {@code spring.cloud.aws.*}; never hand-built here, so the
     * endpoint can only ever resolve to LocalStack (zero live AWS).
     */
    private final S3Template s3Template;

    /**
     * Clock used to derive the deterministic S3 "generation" key prefix ({@code yyyyMMdd}) for the report
     * object, emulating the {@code TRANREPT(+1)} GDG generation. Injectable so tests can pin a fixed
     * instant and assert a deterministic key; defaults to the system zone in the Spring-used constructor.
     */
    private final Clock clock;

    /**
     * Primary (Spring-injected) constructor. Delegates to
     * {@link #TransactionReportJob(TransactionReportProcessor, TransactionRepository,
     * AwsConfig.AwsResourceProperties, S3Template, Clock)} with a system-zone {@link Clock}.
     *
     * @param transactionReportProcessor the per-record enrichment/detail-mapping processor; must not be
     *                                   {@code null}
     * @param transactionRepository      the transaction repository (report driver); must not be
     *                                   {@code null}
     * @param awsResourceProperties      the AWS resource-name holder (report output bucket); must not be
     *                                   {@code null}
     * @param s3Template                 the auto-configured S3 template (report output); must not be
     *                                   {@code null}
     */
    @Autowired
    public TransactionReportJob(
            final TransactionReportProcessor transactionReportProcessor,
            final TransactionRepository transactionRepository,
            final AwsConfig.AwsResourceProperties awsResourceProperties,
            final S3Template s3Template) {
        this(transactionReportProcessor, transactionRepository, awsResourceProperties, s3Template,
                Clock.systemDefaultZone());
    }

    /**
     * Full constructor (also used by tests to pin a deterministic {@link Clock} so the {@code yyyyMMdd}
     * generation prefix of the S3 key is reproducible).
     *
     * @param transactionReportProcessor the per-record enrichment/detail-mapping processor; must not be
     *                                   {@code null}
     * @param transactionRepository      the transaction repository (report driver); must not be
     *                                   {@code null}
     * @param awsResourceProperties      the AWS resource-name holder (report output bucket); must not be
     *                                   {@code null}
     * @param s3Template                 the S3 template used for the report output; must not be
     *                                   {@code null}
     * @param clock                      the clock used for the deterministic S3 generation prefix; must
     *                                   not be {@code null}
     */
    public TransactionReportJob(
            final TransactionReportProcessor transactionReportProcessor,
            final TransactionRepository transactionRepository,
            final AwsConfig.AwsResourceProperties awsResourceProperties,
            final S3Template s3Template,
            final Clock clock) {
        this.transactionReportProcessor = transactionReportProcessor;
        this.transactionRepository = transactionRepository;
        this.awsResourceProperties = awsResourceProperties;
        this.s3Template = s3Template;
        this.clock = clock;
    }

    /**
     * Inline date-range reader for the report &mdash; the relational replacement for
     * {@code TRANREPT.jcl} steps&nbsp;1&ndash;2 (the REPROC unload + DFSORT {@code SORT}/{@code INCLUDE}).
     *
     * <p>{@link TransactionRepository#findByProcessingDateRange(String, String)} returns the in-range
     * transactions <strong>already ordered ascending by card number</strong>, exactly reproducing the
     * inclusive {@code INCLUDE COND=(TRAN-PROC-DT GE PARM-START-DATE AND TRAN-PROC-DT LE PARM-END-DATE)}
     * filter <em>and</em> the {@code SORT FIELDS=(TRAN-CARD-NUM,A)} ordering of the DFSORT step in a
     * single call. Because the query supplies both the date window and the card-number ordering, the
     * explicit REPROC unload and DFSORT steps are unnecessary and <strong>no</strong> Java-side re-sort or
     * re-filter is performed. The card-number ordering is <strong>required</strong>: the writer detects
     * an account break by a change of the resolved account id, and that detection is only correct when
     * rows for the same card/account arrive contiguously (see {@link TransactionReportWriter}).</p>
     *
     * <p>The fully-materialised result list is wrapped in an {@link IteratorItemReader}; the dataset is
     * the bounded set of transactions inside a single reporting window, so an in-memory iterator is
     * appropriate (the COBOL {@code CBTRN03C} likewise reads a single, already-filtered DALY generation
     * sequentially). The bean is {@link StepScope step-scoped} so the late-bound {@code startDate}/
     * {@code endDate} job parameters resolve afresh for each step execution (the COBOL {@code DATEPARM}
     * arrives the same way from JCL); defaults preserve the JCL {@code PARM-START-DATE}/{@code PARM-END-DATE}
     * constants when the parameters are absent.</p>
     *
     * @param startDate the inclusive start of the report window ({@code yyyy-MM-dd}); the
     *                  {@code startDate} job parameter, defaulting to {@value #DEFAULT_START_DATE}
     * @param endDate   the inclusive end of the report window ({@code yyyy-MM-dd}); the {@code endDate}
     *                  job parameter, defaulting to {@value #DEFAULT_END_DATE}
     * @return a step-scoped, card-number-ordered iterator reader over the in-range transactions
     */
    @Bean
    @StepScope
    public IteratorItemReader<Transaction> transactionReportReader(
            @Value("#{jobParameters['startDate'] ?: '" + DEFAULT_START_DATE + "'}") final String startDate,
            @Value("#{jobParameters['endDate'] ?: '" + DEFAULT_END_DATE + "'}") final String endDate) {
        // REPROC unload + DFSORT SORT FIELDS=(TRAN-CARD-NUM,A) + INCLUDE COND date filter are all subsumed
        // by this one query: it returns the in-range rows ordered ascending by card number. Do NOT re-sort
        // or re-filter here; the card-number ordering is what the writer's account-break detection relies on.
        final List<Transaction> rows = transactionRepository.findByProcessingDateRange(startDate, endDate);
        return new IteratorItemReader<>(rows);
    }

    /**
     * Step-scoped, stateful writer bean that performs the {@code CBTRN03C} report assembly
     * ({@code 1100}/{@code 1110}/{@code 1120} paragraphs) and the final flush of the 133-character report
     * to S3. {@code @StepScope} guarantees a fresh {@link TransactionReportWriter} (with a zeroed line
     * counter, zeroed running totals, {@code null} current account and {@code firstTime == true}) per step
     * execution, so its mutable report state never leaks between job runs.
     *
     * <p>The return type is deliberately the concrete {@link TransactionReportWriter} (not an interface):
     * it lets {@link #transactionReportStep(JobRepository, PlatformTransactionManager, IteratorItemReader,
     * TransactionReportWriter)} register the <em>same</em> bean as both the chunk {@code writer(ItemWriter)}
     * and the {@code listener(StepExecutionListener)}, so the writer's end-of-file closing
     * ({@link TransactionReportWriter#afterStep(StepExecution)}) is wired. The report header carries the
     * date window, so the writer also receives the {@code startDate}/{@code endDate} job parameters; the
     * destination bucket is resolved from {@link AwsConfig.AwsResourceProperties} ({@code carddemo-batch-output})
     * and never hardcoded.</p>
     *
     * @param startDate the inclusive start of the report window ({@code yyyy-MM-dd}); the {@code startDate}
     *                  job parameter, defaulting to {@value #DEFAULT_START_DATE} (rendered into
     *                  {@code REPORT-NAME-HEADER})
     * @param endDate   the inclusive end of the report window ({@code yyyy-MM-dd}); the {@code endDate} job
     *                  parameter, defaulting to {@value #DEFAULT_END_DATE} (rendered into
     *                  {@code REPORT-NAME-HEADER})
     * @return a fresh, step-scoped transaction-report writer
     */
    @Bean
    @StepScope
    public TransactionReportWriter transactionReportWriter(
            @Value("#{jobParameters['startDate'] ?: '" + DEFAULT_START_DATE + "'}") final String startDate,
            @Value("#{jobParameters['endDate'] ?: '" + DEFAULT_END_DATE + "'}") final String endDate) {
        return new TransactionReportWriter(
                s3Template,
                awsResourceProperties.getS3().getBatchOutputBucket(),
                clock,
                startDate,
                endDate);
    }

    /**
     * Builds the single chunk-oriented step that constitutes {@code TRANREPT.jcl}'s
     * {@code STEP10R EXEC PGM=CBTRN03C}. The reader drives the card-number-ordered, date-filtered
     * transaction browse, the {@link TransactionReportProcessor} resolves the per-row reference data and
     * maps the detail fields, and the stateful {@link TransactionReportWriter} assembles the paginated
     * report and writes it to S3.
     *
     * <p>The step-scoped reader and writer are injected as method parameters (Spring supplies their CGLIB
     * scoped proxies), which keeps the late-bound {@code startDate}/{@code endDate} job-parameter
     * resolution clean and the compile zero-warning. The same {@link TransactionReportWriter} proxy is
     * registered as <strong>both</strong> the chunk writer and a {@link StepExecutionListener}: the writer
     * implements only {@link ItemWriter} and {@link StepExecutionListener}, so the
     * {@code listener(StepExecutionListener)} overload is selected unambiguously, wiring
     * {@link TransactionReportWriter#afterStep(StepExecution)} to perform the end-of-file page + grand
     * totals and the S3 flush.</p>
     *
     * <p>Spring&nbsp;Batch&nbsp;5.x builders are used with an explicit {@link JobRepository} and
     * {@link PlatformTransactionManager} (no {@code StepBuilderFactory}). The chunk generics are
     * {@code <Transaction, TransactionReportLine>}: the reader yields {@link Transaction}, the processor
     * maps it to {@link TransactionReportLine}, and the writer consumes {@link TransactionReportLine}.</p>
     *
     * @param jobRepository              the auto-configured Spring Batch job repository
     * @param transactionManager         the auto-configured transaction manager bounding each chunk
     *                                   transaction
     * @param transactionReportReader    the step-scoped, card-number-ordered date-range reader (proxy)
     * @param transactionReportWriter    the step-scoped, stateful report writer (proxy), used as both
     *                                   writer and listener
     * @return the configured transaction-report step
     */
    @Bean
    public Step transactionReportStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final IteratorItemReader<Transaction> transactionReportReader,
            final TransactionReportWriter transactionReportWriter) {
        return new StepBuilder("transactionReportStep", jobRepository)
                .<Transaction, TransactionReportLine>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionReportReader)
                .processor(transactionReportProcessor)
                .writer(transactionReportWriter)
                .listener(transactionReportWriter)
                .build();
    }

    /**
     * Assembles the Stage&nbsp;4b job from its single step, mirroring {@code TRANREPT.jcl}'s report
     * generator step ({@code STEP10R EXEC PGM=CBTRN03C}). The Spring&nbsp;Batch&nbsp;5.x {@link JobBuilder}
     * is used with an explicit {@link JobRepository}.
     *
     * <p><strong>Job-parameter contract.</strong> This job expects the {@code startDate}/{@code endDate}
     * job parameters (both {@code yyyy-MM-dd}; defaults {@value #DEFAULT_START_DATE} /
     * {@value #DEFAULT_END_DATE}). The launcher MUST supply them: the Stage&nbsp;4b orchestrator passes the
     * pipeline's processing window, and the SQS report bridge ({@code ReportSubmissionService}, consuming
     * {@code carddemo-report-jobs.fifo}) passes the window requested by {@code CORPT00C}. The bean keeps the
     * natural name {@code "transactionReportJob"} (the {@code @Configuration} bean is named distinctly) so
     * those callers resolve it by that name.</p>
     *
     * @param jobRepository         the auto-configured Spring Batch job repository
     * @param transactionReportStep the single step assembled by
     *                              {@link #transactionReportStep(JobRepository, PlatformTransactionManager,
     *                              IteratorItemReader, TransactionReportWriter)}
     * @return the configured transaction-report job
     */
    @Bean
    public Job transactionReportJob(final JobRepository jobRepository, final Step transactionReportStep) {
        return new JobBuilder("transactionReportJob", jobRepository)
                .start(transactionReportStep)
                .build();
    }

    /**
     * Stateful, step-scoped {@link ItemWriter} + {@link StepExecutionListener} that reproduces the
     * report-assembly paragraphs of {@code CBTRN03C} ({@code 1100-WRITE-TRANSACTION-REPORT},
     * {@code 1110-WRITE-PAGE-TOTALS}, {@code 1110-WRITE-GRAND-TOTALS}, {@code 1120-WRITE-ACCOUNT-TOTALS},
     * {@code 1120-WRITE-HEADERS}, {@code 1120-WRITE-DETAIL}) and the program's end-of-file closing
     * ({@code CBTRN03C} L197&ndash;204). Each emitted record is exactly {@value #RECORD_WIDTH} characters
     * &mdash; the {@code FD-REPTFILE-REC PIC X(133)} / {@code TRANREPT} {@code LRECL=133} contract &mdash;
     * with byte-level column layouts taken verbatim from copybook {@code CVTRA07Y}.
     *
     * <h2>State (mirrors {@code CBTRN03C} WORKING-STORAGE)</h2>
     * <ul>
     *   <li>{@code lineCounter} &harr; {@code WS-LINE-COUNTER PIC 9(09) COMP-3} &mdash; counts
     *       <em>every</em> emitted record (header, blank, detail, total) and drives the page break via
     *       {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)}.</li>
     *   <li>{@code pageTotal}/{@code accountTotal}/{@code grandTotal} &harr; the three
     *       {@code PIC S9(09)V99 VALUE 0} accumulators, held as {@link BigDecimal} of scale&nbsp;2 with
     *       {@link RoundingMode#HALF_EVEN} (AAP &sect;0.7.3; never {@code float}/{@code double}).</li>
     *   <li>{@code currentAccountId} &harr; {@code WS-CURR-CARD-NUM PIC X(16) VALUE SPACES} &mdash; the
     *       account-break key (see the account-break note below).</li>
     *   <li>{@code firstTime} &harr; {@code WS-FIRST-TIME PIC X VALUE 'Y'}.</li>
     *   <li>{@code lastAmount} &mdash; the amount of the last detail line written, used for the EOF re-add
     *       described below.</li>
     * </ul>
     *
     * <h2>Account-break key substitution (documented divergence of mechanism, not behaviour)</h2>
     * <p>{@code CBTRN03C} breaks per-account subtotals on a change of {@code TRAN-CARD-NUM} (L181) and
     * resolves the account id by an {@code CARDXREF} lookup ({@code 1500-A}). The migrated
     * {@link TransactionReportLine} carrier intentionally exposes the <em>already-resolved</em>
     * {@code accountId} (its Javadoc designates it the writer's break key) and does <strong>not</strong>
     * carry the raw card number, so this writer breaks on {@code accountId}. This is exactly equivalent to
     * the COBOL card-number break because the {@code CARDXREF} relationship is strictly one card &rarr; one
     * account and the reader delivers rows ordered by card number (so equal-account rows are contiguous);
     * the only theoretical divergence &mdash; two adjacent-by-card cards sharing one account &mdash; cannot
     * occur under that 1:1 cross-reference and does not occur in the canonical ASCII fixtures.</p>
     *
     * <h2>Parity-sensitive end-of-file behaviour (reproduced exactly)</h2>
     * <p>At EOF {@code CBTRN03C} executes its {@code READ ... INTO} which, on end-of-file, leaves the
     * record area <em>unchanged</em> &mdash; so {@code TRAN-AMT} still holds the last record's amount &mdash;
     * and then (L200&ndash;203) <strong>re-adds</strong> that retained amount to {@code WS-PAGE-TOTAL} and
     * {@code WS-ACCOUNT-TOTAL} before writing the final page totals and grand totals. This double-counts
     * the last record in the final page total and (via page&rarr;grand roll-up) in the grand total. That
     * quirk is faithfully reproduced in {@link #afterStep(StepExecution)}. Equally important, {@code CBTRN03C}
     * writes <strong>no</strong> final account-totals line at EOF; this writer likewise omits it.</p>
     *
     * <p>The legacy out-of-range {@code NEXT SENTENCE} path (L176&ndash;177) is unreachable here: the
     * {@code TRANREPT.jcl} DFSORT {@code INCLUDE COND} pre-filtered the input by date, and that filter is
     * reproduced upstream by {@link TransactionRepository#findByProcessingDateRange(String, String)} and the
     * {@link TransactionReportProcessor} (which drops out-of-range rows), so this writer only ever receives
     * in-range rows and never needs to reproduce that branch.</p>
     *
     * <p>Instances are not thread-safe and are intended to be confined to a single step execution (the
     * surrounding {@code @StepScope} bean guarantees this), so the mutable report state is never shared.</p>
     */
    static class TransactionReportWriter implements ItemWriter<TransactionReportLine>, StepExecutionListener {

        /** Report record width &mdash; {@code FD-REPTFILE-REC PIC X(133)} / {@code TRANREPT LRECL=133}. */
        private static final int RECORD_WIDTH = 133;

        /** Integer-portion width of the edited amount &mdash; {@code ZZZ,ZZZ,ZZZ} (9 digits + 2 commas). */
        private static final int AMOUNT_INTEGER_WIDTH = 11;

        /** Numeric portion width of the edited amount &mdash; integer (11) + {@code .} (1) + fraction (2). */
        private static final int AMOUNT_NUMERIC_WIDTH = 14;

        /** Scale-2 zero, matching {@code PIC S9(09)V99 VALUE 0}; reused for every running-total reset. */
        private static final BigDecimal ZERO_SCALE2 = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);

        /** Hundred, for cents &harr; (integer, fraction) decomposition during amount editing. */
        private static final BigInteger HUNDRED = BigInteger.valueOf(100);

        /** {@code yyyyMMdd} S3 "generation" prefix formatter, emulating the {@code TRANREPT(+1)} GDG. */
        private static final DateTimeFormatter GENERATION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

        // ---- Immutable collaborators / parameters ----

        /** Spring Cloud AWS S3 abstraction used for the single end-of-step report upload. */
        private final S3Template s3Template;

        /** Destination bucket ({@code carddemo-batch-output}); resolved from configuration, never hardcoded. */
        private final String outputBucket;

        /** Clock for the deterministic {@code yyyyMMdd} S3 generation prefix. */
        private final Clock clock;

        /** Inclusive report start date ({@code yyyy-MM-dd}) rendered into {@code REPORT-NAME-HEADER}. */
        private final String startDate;

        /** Inclusive report end date ({@code yyyy-MM-dd}) rendered into {@code REPORT-NAME-HEADER}. */
        private final String endDate;

        // ---- Mutable report state (CBTRN03C WORKING-STORAGE) ----

        /** {@code WS-LINE-COUNTER} &mdash; total emitted records; drives the {@code MOD} page break. */
        private long lineCounter;

        /** {@code WS-PAGE-TOTAL} &mdash; running per-page subtotal (scale 2). */
        private BigDecimal pageTotal = ZERO_SCALE2;

        /** {@code WS-ACCOUNT-TOTAL} &mdash; running per-account subtotal (scale 2). */
        private BigDecimal accountTotal = ZERO_SCALE2;

        /** {@code WS-GRAND-TOTAL} &mdash; running report grand total (scale 2). */
        private BigDecimal grandTotal = ZERO_SCALE2;

        /** {@code WS-CURR-CARD-NUM} surrogate &mdash; current account-break key ({@code null} before first row). */
        private Long currentAccountId;

        /** {@code WS-FIRST-TIME} &mdash; {@code true} until the first detail row triggers the initial headers. */
        private boolean firstTime = true;

        /** Amount of the most recently written detail line &mdash; the EOF "retained record" re-add source. */
        private BigDecimal lastAmount;

        /** Assembled {@value #RECORD_WIDTH}-character report records, flushed to S3 in {@link #afterStep}. */
        private final List<String> reportLines = new ArrayList<>();

        /**
         * Creates a report writer for a single step execution.
         *
         * @param s3Template   the S3 abstraction for the end-of-step report upload; must not be {@code null}
         * @param outputBucket the destination bucket name ({@code carddemo-batch-output}); must not be
         *                     {@code null}
         * @param clock        the clock supplying the {@code yyyyMMdd} S3 generation prefix; must not be
         *                     {@code null}
         * @param startDate    the inclusive report start date ({@code yyyy-MM-dd}) for the report header
         * @param endDate      the inclusive report end date ({@code yyyy-MM-dd}) for the report header
         */
        TransactionReportWriter(final S3Template s3Template, final String outputBucket, final Clock clock,
                final String startDate, final String endDate) {
            this.s3Template = s3Template;
            this.outputBucket = outputBucket;
            this.clock = clock;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        /**
         * Processes one chunk of report lines, reproducing the in-range per-record branch of the
         * {@code CBTRN03C} main loop (L179&ndash;196) for every item, in the card-number order guaranteed by
         * the reader.
         *
         * @param chunk the chunk of enriched report lines emitted by {@link TransactionReportProcessor}
         */
        @Override
        public void write(final Chunk<? extends TransactionReportLine> chunk) {
            for (final TransactionReportLine line : chunk.getItems()) {
                final Long accountId = line.accountId();
                final BigDecimal amount = scale2(line.tranAmt());

                // CBTRN03C L181-188: account (card) break. On a change of the break key, flush the prior
                // account's totals (1120-WRITE-ACCOUNT-TOTALS) unless this is the very first record
                // (WS-FIRST-TIME = 'Y'), then adopt the new key (MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM).
                if (currentAccountId == null || !currentAccountId.equals(accountId)) {
                    if (!firstTime) {
                        writeAccountTotals();
                    }
                    currentAccountId = accountId;
                }

                // CBTRN03C 1100-WRITE-TRANSACTION-REPORT (L275-285) is TWO SEQUENTIAL IFs (not if/else).
                // First record: emit the initial headers (which advances lineCounter to 4, so the MOD test
                // below is non-zero). Thereafter: a page break fires whenever lineCounter is a multiple of
                // the page size, writing the page totals followed by a fresh header block.
                if (firstTime) {
                    firstTime = false;
                    writeHeaders();
                }
                if (lineCounter % PAGE_SIZE == 0) {
                    writePageTotals();
                    writeHeaders();
                }

                // CBTRN03C L287-289: accumulate, then write the detail line (1120-WRITE-DETAIL).
                pageTotal = pageTotal.add(amount);
                accountTotal = accountTotal.add(amount);
                writeDetail(line, amount);
                lastAmount = amount;
            }
        }

        /**
         * Performs the {@code CBTRN03C} end-of-file closing (L197&ndash;204) and flushes the assembled
         * report to S3. Faithfully reproduces the two parity-sensitive EOF quirks: (a) the retained last
         * record's amount is <strong>re-added</strong> to the page and account totals (the COBOL
         * {@code READ ... INTO} leaves the record area unchanged at EOF), and (b) <strong>no</strong> final
         * account-totals line is written &mdash; only the final page totals and grand totals.
         *
         * @param stepExecution the completing step execution
         * @return the step's existing {@link ExitStatus} (this writer does not alter the exit status)
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            // Only close the report if at least one detail line was written. CBTRN03C's behaviour on a
            // completely empty input is undefined (it would add the uninitialised record area to the
            // totals); we instead emit a clean, empty report object, a documented divergence limited to the
            // degenerate empty-input case (the canonical fixtures always contain in-range rows).
            if (lastAmount != null) {
                // (a) Retained-record re-add (CBTRN03C L200-201): double-counts the last record in the
                // final page total and, through the page->grand roll-up in writePageTotals(), the grand total.
                pageTotal = pageTotal.add(lastAmount);
                accountTotal = accountTotal.add(lastAmount);
                // CBTRN03C L202-203: final page totals then grand totals. (b) No account-totals line at EOF.
                writePageTotals();
                writeGrandTotals();
            }
            flushReportToS3();
            return stepExecution.getExitStatus();
        }

        // -------------------------------------------------------------------------------------------------
        // Report-paragraph emitters. Each mirrors a CBTRN03C paragraph, including its exact per-line
        // WS-LINE-COUNTER increments, which determine the page-break cadence.
        // -------------------------------------------------------------------------------------------------

        /**
         * {@code 1120-WRITE-HEADERS} (L324&ndash;341): emits the name header, a blank line,
         * {@code TRANSACTION-HEADER-1}, and {@code TRANSACTION-HEADER-2}, advancing the line counter by four.
         */
        private void writeHeaders() {
            emit(reportNameHeader());
            lineCounter++;
            emit(blankLine());
            lineCounter++;
            emit(transactionHeader1());
            lineCounter++;
            emit(transactionHeader2());
            lineCounter++;
        }

        /**
         * {@code 1120-WRITE-DETAIL} (L361&ndash;374): emits one detail line and advances the line counter by
         * one. The amount is rendered with the MINUS sign style ({@code PIC -ZZZ,ZZZ,ZZZ.ZZ}).
         *
         * @param line   the enriched report line
         * @param amount the scale-2 transaction amount for this line
         */
        private void writeDetail(final TransactionReportLine line, final BigDecimal amount) {
            emit(detailLine(line, amount));
            lineCounter++;
        }

        /**
         * {@code 1110-WRITE-PAGE-TOTALS} (L293&ndash;304): emits the page-total line (using the current page
         * total), rolls the page total into the grand total, resets the page total to zero, emits a
         * {@code TRANSACTION-HEADER-2} separator, and advances the line counter by two.
         */
        private void writePageTotals() {
            emit(pageTotalsLine());
            grandTotal = grandTotal.add(pageTotal);
            pageTotal = ZERO_SCALE2;
            lineCounter++;
            emit(transactionHeader2());
            lineCounter++;
        }

        /**
         * {@code 1120-WRITE-ACCOUNT-TOTALS} (L306&ndash;316): emits the account-total line (using the current
         * account total), resets the account total to zero, emits a {@code TRANSACTION-HEADER-2} separator,
         * and advances the line counter by two.
         */
        private void writeAccountTotals() {
            emit(accountTotalsLine());
            accountTotal = ZERO_SCALE2;
            lineCounter++;
            emit(transactionHeader2());
            lineCounter++;
        }

        /**
         * {@code 1110-WRITE-GRAND-TOTALS} (L318&ndash;322): emits the grand-total line. Faithful to the
         * COBOL, this paragraph does <strong>not</strong> advance the line counter.
         */
        private void writeGrandTotals() {
            emit(grandTotalsLine());
        }

        // -------------------------------------------------------------------------------------------------
        // 133-character record builders. Column positions, FILLER values, and PIC edits are taken verbatim
        // from copybook CVTRA07Y; every builder right-pads/truncates to exactly RECORD_WIDTH.
        // -------------------------------------------------------------------------------------------------

        /**
         * Builds {@code REPORT-NAME-HEADER} (CVTRA07Y L4&ndash;13): {@code REPT-SHORT-NAME X(38)}='DALYREPT',
         * {@code REPT-LONG-NAME X(41)}='Daily Transaction Report', {@code REPT-DATE-HEADER X(12)}='Date Range: ',
         * {@code REPT-START-DATE X(10)}, {@code FILLER X(04)}=' to ', {@code REPT-END-DATE X(10)}.
         *
         * @return the 133-character report name/date header record
         */
        private String reportNameHeader() {
            final StringBuilder sb = new StringBuilder(RECORD_WIDTH);
            sb.append(field("DALYREPT", 38));
            sb.append(field("Daily Transaction Report", 41));
            sb.append(field("Date Range: ", 12));
            sb.append(field(startDate, 10));
            sb.append(" to ");
            sb.append(field(endDate, 10));
            return padTo133(sb.toString());
        }

        /**
         * Builds {@code TRANSACTION-HEADER-1} (CVTRA07Y L33&ndash;46): the column captions. Note the literal
         * {@code '        Amount'} carries eight leading spaces, preserved exactly.
         *
         * @return the 133-character column-caption header record
         */
        private String transactionHeader1() {
            final StringBuilder sb = new StringBuilder(RECORD_WIDTH);
            sb.append(field("Transaction ID", 17));
            sb.append(field("Account ID", 12));
            sb.append(field("Transaction Type", 19));
            sb.append(field("Tran Category", 35));
            sb.append(field("Tran Source", 14));
            sb.append(' ');
            sb.append(field("        Amount", 16));
            return padTo133(sb.toString());
        }

        /**
         * Builds {@code TRANSACTION-HEADER-2} (CVTRA07Y L48): {@code PIC X(133) VALUE ALL '-'} &mdash; a full
         * row of {@value #RECORD_WIDTH} hyphens.
         *
         * @return the 133-character hyphen separator record
         */
        private String transactionHeader2() {
            return "-".repeat(RECORD_WIDTH);
        }

        /**
         * Builds {@code WS-BLANK-LINE} (CBTRN03C L133): {@code PIC X(133) VALUE SPACES}.
         *
         * @return a record of {@value #RECORD_WIDTH} spaces
         */
        private String blankLine() {
            return " ".repeat(RECORD_WIDTH);
        }

        /**
         * Builds {@code TRANSACTION-DETAIL-REPORT} (CVTRA07Y L15&ndash;31) for one line, applying the field
         * MOVEs of {@code 1120-WRITE-DETAIL} (L362&ndash;371). The two {@code '-'} separators are FILLER
         * VALUEs (untouched by COBOL {@code INITIALIZE}) and are preserved literally; the account id is the
         * {@code XREF-ACCT-ID PIC 9(11)} resolved by the processor (rendered as 11 zero-padded digits) and
         * the category code is {@code TRAN-CAT-CD PIC 9(04)} (4 zero-padded digits); the amount uses the
         * MINUS sign style.
         *
         * @param line   the enriched report line
         * @param amount the scale-2 transaction amount
         * @return the 133-character detail record
         */
        private String detailLine(final TransactionReportLine line, final BigDecimal amount) {
            final int categoryCode = line.tranCatCd() == null ? 0 : line.tranCatCd();
            final StringBuilder sb = new StringBuilder(RECORD_WIDTH);
            sb.append(field(line.tranId(), 16));                                 // TRAN-REPORT-TRANS-ID  X(16)
            sb.append(' ');                                                       // FILLER                X(01)
            sb.append(String.format(Locale.US, "%011d", line.accountId()));       // TRAN-REPORT-ACCOUNT-ID X(11) <- 9(11)
            sb.append(' ');                                                       // FILLER                X(01)
            sb.append(field(line.tranTypeCd(), 2));                              // TRAN-REPORT-TYPE-CD   X(02)
            sb.append('-');                                                       // FILLER X(01) VALUE '-'
            sb.append(field(line.tranTypeDesc(), 15));                           // TRAN-REPORT-TYPE-DESC X(15)
            sb.append(' ');                                                       // FILLER                X(01)
            sb.append(String.format(Locale.US, "%04d", categoryCode));            // TRAN-REPORT-CAT-CD    9(04)
            sb.append('-');                                                       // FILLER X(01) VALUE '-'
            sb.append(field(line.tranCatTypeDesc(), 29));                        // TRAN-REPORT-CAT-DESC  X(29)
            sb.append(' ');                                                       // FILLER                X(01)
            sb.append(field(line.tranSource(), 10));                             // TRAN-REPORT-SOURCE    X(10)
            sb.append("    ");                                                     // FILLER                X(04)
            sb.append(editAmount(amount, false));                                 // TRAN-REPORT-AMT  -ZZZ,ZZZ,ZZZ.ZZ
            sb.append("  ");                                                       // FILLER                X(02)
            return padTo133(sb.toString());
        }

        /**
         * Builds {@code REPORT-PAGE-TOTALS} (CVTRA07Y L50&ndash;54): {@code FILLER X(11)}='Page Total',
         * {@code FILLER X(86)}=ALL '.', {@code REPT-PAGE-TOTAL +ZZZ,ZZZ,ZZZ.ZZ}.
         *
         * @return the 133-character page-totals record (using the current page total)
         */
        private String pageTotalsLine() {
            final StringBuilder sb = new StringBuilder(RECORD_WIDTH);
            sb.append(field("Page Total", 11));
            sb.append(".".repeat(86));
            sb.append(editAmount(pageTotal, true));
            return padTo133(sb.toString());
        }

        /**
         * Builds {@code REPORT-ACCOUNT-TOTALS} (CVTRA07Y L56&ndash;60): {@code FILLER X(13)}='Account Total',
         * {@code FILLER X(84)}=ALL '.', {@code REPT-ACCOUNT-TOTAL +ZZZ,ZZZ,ZZZ.ZZ}.
         *
         * @return the 133-character account-totals record (using the current account total)
         */
        private String accountTotalsLine() {
            final StringBuilder sb = new StringBuilder(RECORD_WIDTH);
            sb.append(field("Account Total", 13));
            sb.append(".".repeat(84));
            sb.append(editAmount(accountTotal, true));
            return padTo133(sb.toString());
        }

        /**
         * Builds {@code REPORT-GRAND-TOTALS} (CVTRA07Y L62&ndash;66): {@code FILLER X(11)}='Grand Total',
         * {@code FILLER X(86)}=ALL '.', {@code REPT-GRAND-TOTAL +ZZZ,ZZZ,ZZZ.ZZ}.
         *
         * @return the 133-character grand-totals record (using the current grand total)
         */
        private String grandTotalsLine() {
            final StringBuilder sb = new StringBuilder(RECORD_WIDTH);
            sb.append(field("Grand Total", 11));
            sb.append(".".repeat(86));
            sb.append(editAmount(grandTotal, true));
            return padTo133(sb.toString());
        }

        /** Appends a fully-assembled record to the report buffer. */
        private void emit(final String line) {
            reportLines.add(line);
        }

        /**
         * Flushes the assembled report to S3 as a single {@code text/plain} object whose key carries a
         * {@code yyyyMMdd} generation prefix &mdash; the {@code TRANREPT(+1)} GDG &rarr; versioned-S3-object
         * substitution. Records are newline-separated; each record is exactly {@value #RECORD_WIDTH}
         * characters. An empty report (degenerate empty-input case) is uploaded as a zero-length object.
         */
        private void flushReportToS3() {
            final String generation = LocalDate.now(clock).format(GENERATION_FORMAT);
            final String key = "tranrept/" + generation + "/transaction-detail-report.txt";
            final String body = String.join("\n", reportLines);
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            final ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType("text/plain")
                    .contentLength((long) payload.length)
                    .build();
            s3Template.upload(outputBucket, key, new ByteArrayInputStream(payload), metadata);
        }

        /**
         * Returns the assembled {@value #RECORD_WIDTH}-character report records in emission order. Exposed
         * (package-private) for characterization testing of the stateful assembly against {@code CBTRN03C};
         * not part of the public API.
         *
         * @return the live, ordered list of assembled report records
         */
        List<String> getReportLines() {
            return reportLines;
        }

        // -------------------------------------------------------------------------------------------------
        // COBOL-faithful formatting helpers.
        // -------------------------------------------------------------------------------------------------

        /** Normalises an amount to scale 2 with banker's rounding; {@code null} becomes scale-2 zero. */
        private static BigDecimal scale2(final BigDecimal value) {
            return value == null ? ZERO_SCALE2 : value.setScale(2, RoundingMode.HALF_EVEN);
        }

        /**
         * Renders a numeric value using the COBOL fixed-sign + zero-suppression edit of
         * {@code -ZZZ,ZZZ,ZZZ.ZZ} (MINUS style) or {@code +ZZZ,ZZZ,ZZZ.ZZ} (PLUS style), producing a
         * 15-character string.
         *
         * <p>Editing rules reproduced: the fixed sign occupies position&nbsp;1 always ({@code '+'} &rarr;
         * {@code '+'} for &ge;0 / {@code '-'} for &lt;0; {@code '-'} &rarr; space for &ge;0 / {@code '-'} for
         * &lt;0); leading zeros and the commas among them are blanked (Z suppression); and when the
         * magnitude is zero the 14-character numeric portion (digits, commas, and the decimal point) is
         * entirely blanked while the fixed sign still occupies position&nbsp;1.</p>
         *
         * @param value     the value to edit (scale is normalised to 2)
         * @param plusStyle {@code true} for the {@code '+'} fixed sign, {@code false} for the {@code '-'} fixed sign
         * @return the 15-character edited representation
         */
        private static String editAmount(final BigDecimal value, final boolean plusStyle) {
            final BigDecimal scaled = scale2(value);
            final char sign = plusStyle
                    ? (scaled.signum() < 0 ? '-' : '+')
                    : (scaled.signum() < 0 ? '-' : ' ');
            final String numericPortion;
            if (scaled.signum() == 0) {
                // PIC ...ZZ all-zero rule: digit positions, commas, and the decimal point are all blanked.
                numericPortion = " ".repeat(AMOUNT_NUMERIC_WIDTH);
            } else {
                final BigDecimal abs = scaled.abs();
                final BigInteger cents = abs.movePointRight(2).toBigIntegerExact();
                final BigInteger intPart = cents.divide(HUNDRED);
                final int frac = cents.mod(HUNDRED).intValue();
                final String intField = intPart.signum() == 0
                        ? " ".repeat(AMOUNT_INTEGER_WIDTH)
                        : padLeftSpaces(String.format(Locale.US, "%,d", intPart), AMOUNT_INTEGER_WIDTH);
                numericPortion = intField + "." + String.format(Locale.US, "%02d", frac);
            }
            return sign + numericPortion;
        }

        /**
         * COBOL alphanumeric MOVE into a {@code PIC X(width)} receiver: left-justify, pad on the right with
         * spaces, and truncate on the right if the source is longer. {@code null} is treated as spaces.
         *
         * @param value the source text ({@code null} treated as empty)
         * @param width the receiving field width
         * @return the value fitted to exactly {@code width} characters
         */
        private static String field(final String value, final int width) {
            final String s = value == null ? "" : value;
            if (s.length() >= width) {
                return s.substring(0, width);
            }
            return s + " ".repeat(width - s.length());
        }

        /** Left-pads {@code s} with spaces to {@code width}; returns it unchanged when already &ge; width. */
        private static String padLeftSpaces(final String s, final int width) {
            if (s.length() >= width) {
                return s;
            }
            return " ".repeat(width - s.length()) + s;
        }

        /** Fits a record to exactly {@value #RECORD_WIDTH} characters (right-pad with spaces / truncate). */
        private static String padTo133(final String s) {
            if (s.length() >= RECORD_WIDTH) {
                return s.substring(0, RECORD_WIDTH);
            }
            return s + " ".repeat(RECORD_WIDTH - s.length());
        }
    }
}
