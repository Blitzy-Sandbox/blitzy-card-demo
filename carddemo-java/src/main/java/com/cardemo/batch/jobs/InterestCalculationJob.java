package com.cardemo.batch.jobs;

import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.config.AwsConfig;
import com.cardemo.model.dto.InterestCalculationResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@code @Configuration} that assembles <strong>Stage&nbsp;2</strong> of the greenfield
 * Java&nbsp;25 LTS + Spring&nbsp;Boot&nbsp;3.5.x CardDemo batch pipeline &mdash; the
 * <strong>Interest Calculation</strong> job. It is the faithful translation of the legacy JCL job
 * {@code app/jcl/INTCALC.jcl}, which executes the COBOL batch program
 * {@code app/cbl/CBACT04C.cbl} (the interest calculator).
 *
 * <h2>Provenance &amp; authority</h2>
 * <p>Translated from the frozen AWS CardDemo COBOL/JCL baseline at commit SHA {@code 27d6c6f}. The
 * COBOL/JCL sources are <strong>read-only</strong> reference material and are <strong>never copied</strong>
 * into this repository; traceability is by commit SHA only (AAP &sect;0.7.2). Per the
 * <strong>Minimal Change Clause</strong> (AAP &sect;0.7.1) this migration reproduces the COBOL behaviour
 * <em>exactly</em> &mdash; no business-rule "improvements", no feature additions &mdash; and documents
 * every technology substitution at its point of change. The application base package is
 * {@code com.cardemo} (decision <strong>D-006</strong> &mdash; deliberately <em>not</em>
 * {@code com.carddemo}).
 *
 * <h2>Source contract &mdash; {@code INTCALC.jcl} + {@code CBACT04C.cbl}</h2>
 * <p>{@code INTCALC.jcl} is a <strong>single step</strong>:
 * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} (there is <strong>no</strong> {@code COND}). The
 * {@code PARM} is a 10-character run date. Its DD surface maps to this job as follows:</p>
 * <ul>
 *   <li>{@code TCATBALF} &rarr; {@code TCATBALF.VSAM.KSDS} &mdash; the <strong>driver</strong> input
 *       (transaction-category balances), read here through {@link TransactionCategoryBalanceRepository}
 *       by {@link #interestTcatbalReader()}.</li>
 *   <li>{@code XREFFILE}/{@code XREFFIL1} &rarr; {@code CARDXREF.VSAM.KSDS}/{@code .AIX.PATH} &mdash; the
 *       account&rarr;card alternate-index lookup, performed by {@link InterestCalculationProcessor}
 *       (not here).</li>
 *   <li>{@code ACCTFILE} &rarr; {@code ACCTDATA.VSAM.KSDS} &mdash; the account master, the
 *       interest roll-up target updated by this job's writer via {@link AccountRepository}.</li>
 *   <li>{@code DISCGRP} &rarr; {@code DISCGRP.VSAM.KSDS} &mdash; the disclosure-group interest rates,
 *       resolved (with the {@code DEFAULT}-group fallback) by {@link InterestCalculationProcessor}.</li>
 *   <li>{@code TRANSACT} &rarr; {@code SYSTRAN(+1)} GDG, {@code RECFM=F LRECL=350} &mdash; the generated
 *       interest transactions. <strong>Technology substitution:</strong> the GDG generation is replaced
 *       by versioned objects in the S3 bucket {@code carddemo-batch-output} (decision <strong>D-003</strong>);
 *       this job's writer stages each interest transaction there (see {@link InterestRollupWriter}).</li>
 * </ul>
 *
 * <p>{@code CBACT04C} sequentially browses {@code TCATBAL} ordered by account. For each
 * category-balance row it looks up the disclosure-group interest rate (with a {@code DEFAULT}-group
 * fallback when the specific group is absent), computes the monthly interest
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, generates an interest {@link Transaction} written to the
 * {@code SYSTRAN} file, and accumulates {@code WS-TOTAL-INT}. On an <strong>account-number change</strong>
 * (paragraph {@code 1050-UPDATE-ACCOUNT}) <strong>and at end-of-file</strong> it adds
 * {@code WS-TOTAL-INT} to {@code ACCT-CURR-BAL}, zeroes {@code ACCT-CURR-CYC-CREDIT} and
 * {@code ACCT-CURR-CYC-DEBIT}, then resets {@code WS-TOTAL-INT}. Paragraph {@code 1400-COMPUTE-FEES} is an
 * empty stub ("To be implemented") in the baseline and is therefore intentionally <strong>not</strong>
 * implemented (no behaviour to preserve).
 *
 * <h2>Division of responsibility (critical for parity)</h2>
 * <ul>
 *   <li>The <strong>per-row</strong> work &mdash; rate lookup, the {@code DEFAULT}-group fallback, the
 *       interest formula {@code (bal * rate)/1200}, and construction of the interest {@link Transaction}
 *       &mdash; belongs to the sibling {@link InterestCalculationProcessor} and is <strong>not</strong>
 *       reimplemented here. This job never performs the interest arithmetic.</li>
 *   <li>The <strong>account-boundary roll-up</strong> ({@code 1050-UPDATE-ACCOUNT}) and the
 *       <strong>persistence/staging</strong> belong to this job's inline {@link InterestRollupWriter}. A
 *       chunk-oriented {@code ItemProcessor} cannot observe account boundaries across the chunk stream, so
 *       the roll-up is intentionally a <em>stateful writer</em> that fires both on an account change and at
 *       end-of-step (its {@link StepExecutionListener#afterStep(StepExecution)} flush), exactly matching
 *       {@code CBACT04C}'s two roll-up boundaries.</li>
 * </ul>
 *
 * <h2>Account ordering is the linchpin</h2>
 * <p>For the stateful roll-up to be correct, category-balance rows MUST arrive
 * <strong>account-ordered</strong>. {@link #interestTcatbalReader()} therefore sorts by the embedded-key
 * components ({@code id.acctId}, then {@code id.typeCode}, then {@code id.catCode}), reproducing the
 * contiguous-by-account read order of {@code CBACT04C}'s sequential {@code TCATBALF} browse.</p>
 *
 * <h2>Spring Batch 5.x infrastructure (AAP &sect;0.7.8)</h2>
 * <p>Built with the Spring&nbsp;Batch&nbsp;5.x builder API ({@link StepBuilder}/{@link JobBuilder} with an
 * explicit {@link JobRepository} and {@link PlatformTransactionManager}). The deprecated
 * {@code StepBuilderFactory}/{@code JobBuilderFactory} are <strong>not</strong> used, and
 * {@code @EnableBatchProcessing} is <strong>not</strong> declared anywhere (in Spring&nbsp;Boot&nbsp;3.x it
 * would disable Boot's batch auto-configuration). The {@link JobRepository} and
 * {@link PlatformTransactionManager} are the auto-configured beans documented by
 * {@code com.cardemo.config.BatchConfig}.</p>
 *
 * <h2>Job-parameter contract</h2>
 * <p>This job (its step's {@link InterestCalculationProcessor}) expects a late-bound {@code parmDate}
 * job parameter &mdash; the 10-character run date that the COBOL {@code PARM-DATE} supplied from JCL
 * (default {@value #DEFAULT_PARM_DATE}). The processor reads it via
 * {@code @Value("#{jobParameters['parmDate']}")}; the orchestrator/launcher that starts
 * {@link #interestCalculationJob(JobRepository, Step)} MUST supply it as a job parameter.</p>
 *
 * @see InterestCalculationProcessor
 * @see InterestCalculationResult
 * @see Configuration
 */
// Explicit configuration-bean name to avoid a BeanDefinitionOverrideException. The default
// component name for this class is its decapitalized simple name, "interestCalculationJob", which
// would collide with the Job @Bean method of the same name below (Spring Boot disables
// bean-definition overriding by default). Naming the @Configuration bean distinctly lets the Job
// bean keep the natural name "interestCalculationJob" (the name the pipeline orchestrator resolves).
@Configuration("interestCalculationJobConfiguration")
public class InterestCalculationJob {

    /**
     * Chunk size for the interest-calculation step. This is a <strong>tuning</strong> value only and does
     * <strong>not</strong> affect parity: the account-boundary roll-up ({@code 1050-UPDATE-ACCOUNT}) is
     * handled across chunk boundaries by the stateful {@link InterestRollupWriter} (which retains the
     * running account id and accumulated interest between {@code write(...)} invocations and flushes the
     * final account in {@link StepExecutionListener#afterStep(StepExecution)}), so chunking the
     * account-ordered stream never splits or double-counts an account's roll-up.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Default value of the {@code parmDate} job parameter &mdash; the 10-character run date carried by
     * the COBOL {@code PARM} of {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}. The value is
     * consumed by {@link InterestCalculationProcessor}; it is documented here as the canonical default the
     * orchestrator/launcher should supply when none is provided.
     */
    private static final String DEFAULT_PARM_DATE = "2022071800";

    /**
     * Per-row interest processor &mdash; the sibling component that owns the rate lookup, the
     * {@code DEFAULT}-group fallback, the {@code (bal * rate)/1200} formula and the interest
     * {@link Transaction} construction ({@code CBACT04C} paragraphs {@code 1100}/{@code 1110}/{@code 1200}/
     * {@code 1300}). It is {@code @StepScope} (a CGLIB scoped proxy) so the late-bound {@code parmDate}
     * resolves at step-execution time; injecting it here wires it into {@link #interestCalculationStep}.
     */
    private final InterestCalculationProcessor interestCalculationProcessor;

    /**
     * Driver repository for the {@code TCATBALF} category-balance KSDS. Supplies the account-ordered
     * {@code findAll(Pageable)} browse used by {@link #interestTcatbalReader()}.
     */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Account-master repository &mdash; the interest roll-up target. Used by {@link InterestRollupWriter}
     * to {@code findById}/{@code save} during {@code 1050-UPDATE-ACCOUNT}.
     */
    private final AccountRepository accountRepository;

    /**
     * Strongly-typed holder of the application-owned AWS resource <em>names</em>. The SYSTRAN staging
     * bucket is read from {@code getS3().getBatchOutputBucket()} ({@code carddemo-batch-output}); the
     * bucket name is therefore resolved from configuration and never hardcoded (AAP &sect;0.7.7).
     */
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Spring Cloud AWS S3 abstraction used to stage the generated interest transactions (the
     * {@code SYSTRAN(+1)} GDG &rarr; S3 substitution). Auto-configured by {@code spring-cloud-aws-starter-s3}
     * from {@code spring.cloud.aws.*}; never hand-built here, so the endpoint can only ever resolve to
     * LocalStack (zero live AWS).
     */
    private final S3Template s3Template;

    /**
     * Clock used to derive the deterministic S3 "generation" key prefix ({@code yyyyMMdd}) for staged
     * SYSTRAN objects. Injectable so tests can pin a fixed instant; defaults to the system zone in the
     * Spring-used constructor.
     */
    private final Clock clock;

    /**
     * Primary (Spring-injected) constructor. Delegates to {@link #InterestCalculationJob(
     * InterestCalculationProcessor, TransactionCategoryBalanceRepository, AccountRepository,
     * AwsConfig.AwsResourceProperties, S3Template, Clock)} with a system-zone {@link Clock}.
     *
     * @param interestCalculationProcessor        the per-row interest processor (rate lookup + formula)
     * @param transactionCategoryBalanceRepository the TCATBAL driver repository
     * @param accountRepository                   the account-master repository (roll-up target)
     * @param awsResourceProperties               the AWS resource-name holder (SYSTRAN output bucket)
     * @param s3Template                          the auto-configured S3 template (SYSTRAN staging)
     */
    @Autowired
    public InterestCalculationJob(
            final InterestCalculationProcessor interestCalculationProcessor,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final AccountRepository accountRepository,
            final AwsConfig.AwsResourceProperties awsResourceProperties,
            final S3Template s3Template) {
        this(interestCalculationProcessor, transactionCategoryBalanceRepository, accountRepository,
                awsResourceProperties, s3Template, Clock.systemDefaultZone());
    }

    /**
     * Full constructor (also used by tests to pin a deterministic {@link Clock}).
     *
     * @param interestCalculationProcessor        the per-row interest processor (rate lookup + formula)
     * @param transactionCategoryBalanceRepository the TCATBAL driver repository
     * @param accountRepository                   the account-master repository (roll-up target)
     * @param awsResourceProperties               the AWS resource-name holder (SYSTRAN output bucket)
     * @param s3Template                          the S3 template used for SYSTRAN staging
     * @param clock                               the clock used for the deterministic S3 generation prefix
     */
    public InterestCalculationJob(
            final InterestCalculationProcessor interestCalculationProcessor,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final AccountRepository accountRepository,
            final AwsConfig.AwsResourceProperties awsResourceProperties,
            final S3Template s3Template,
            final Clock clock) {
        this.interestCalculationProcessor = interestCalculationProcessor;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.accountRepository = accountRepository;
        this.awsResourceProperties = awsResourceProperties;
        this.s3Template = s3Template;
        this.clock = clock;
    }

    /**
     * Inline driver reader for the {@code TCATBALF} category-balance dataset, reproducing
     * {@code CBACT04C}'s sequential {@code TCATBALF} browse ({@code 1000-TCATBALF-GET-NEXT}).
     *
     * <p>A {@link RepositoryItemReader} pages the entire {@code TransactionCategoryBalance} table through
     * the inherited {@code findAll(Pageable)} of {@link TransactionCategoryBalanceRepository}. The
     * <strong>sort order is the parity-critical part</strong>: rows are ordered by the embedded-key
     * components in COBOL key order &mdash; {@code id.acctId} ascending, then {@code id.typeCode}, then
     * {@code id.catCode} &mdash; so they arrive <strong>contiguously by account</strong>. That ordering is
     * what lets the stateful {@link InterestRollupWriter} detect account boundaries and fire
     * {@code 1050-UPDATE-ACCOUNT} exactly once per account (on the account change) plus once at
     * end-of-step, matching {@code CBACT04C}. The {@code @EmbeddedId} field on
     * {@link TransactionCategoryBalance} is named {@code id}, so the sort property paths are
     * {@code id.acctId} / {@code id.typeCode} / {@code id.catCode}.</p>
     *
     * <p>The reader is {@code @StepScope} so a fresh, correctly-positioned reader is created for each step
     * execution. The page size is {@link #CHUNK_SIZE}; paging the driver does not affect parity because the
     * roll-up is carried across pages/chunks by the stateful writer.</p>
     *
     * @return a step-scoped, account-ordered repository reader over the category-balance driver dataset
     */
    @Bean
    @StepScope
    public RepositoryItemReader<TransactionCategoryBalance> interestTcatbalReader() {
        // Account-ordered browse of TCATBALF (CBACT04C 1000-TCATBALF-GET-NEXT). LinkedHashMap preserves
        // the COBOL key order acctId -> typeCode -> catCode so records arrive contiguously by account.
        final Map<String, Sort.Direction> sortByAccountOrder = new LinkedHashMap<>();
        sortByAccountOrder.put("id.acctId", Sort.Direction.ASC);
        sortByAccountOrder.put("id.typeCode", Sort.Direction.ASC);
        sortByAccountOrder.put("id.catCode", Sort.Direction.ASC);

        final RepositoryItemReader<TransactionCategoryBalance> reader = new RepositoryItemReader<>();
        reader.setRepository(transactionCategoryBalanceRepository);
        reader.setMethodName("findAll");
        reader.setPageSize(CHUNK_SIZE);
        reader.setSort(sortByAccountOrder);
        reader.setName("interestTcatbalReader");
        return reader;
    }

    /**
     * Step-scoped, stateful writer bean that performs the SYSTRAN S3 staging and the
     * {@code 1050-UPDATE-ACCOUNT} account roll-up. {@code @StepScope} guarantees a fresh
     * {@link InterestRollupWriter} (with cleared roll-up state) per step execution, so its mutable
     * running-account state never leaks between job runs.
     *
     * <p>The return type is deliberately the concrete {@link InterestRollupWriter} (not an interface): it
     * lets {@link #interestCalculationStep(JobRepository, PlatformTransactionManager)} select <em>both</em>
     * the chunk {@code writer(ItemWriter)} overload and the {@code listener(StepExecutionListener)} overload
     * for the same bean, so the writer's end-of-file flush
     * ({@link InterestRollupWriter#afterStep(StepExecution)}) is registered. The destination bucket is
     * resolved from {@link AwsConfig.AwsResourceProperties} ({@code carddemo-batch-output}) and never
     * hardcoded.</p>
     *
     * @return a fresh, step-scoped interest roll-up writer
     */
    @Bean
    @StepScope
    public InterestRollupWriter interestRollupWriter() {
        return new InterestRollupWriter(
                accountRepository,
                s3Template,
                awsResourceProperties.getS3().getBatchOutputBucket(),
                clock);
    }

    /**
     * Builds the single chunk-oriented step that constitutes {@code INTCALC.jcl}'s lone
     * {@code //STEP15 EXEC PGM=CBACT04C}. The reader drives the account-ordered {@code TCATBALF} browse,
     * the {@link InterestCalculationProcessor} computes the per-row interest (rate lookup +
     * {@code DEFAULT}-group fallback + the {@code (bal * rate)/1200} formula + interest-{@link Transaction}
     * construction), and the stateful {@link InterestRollupWriter} stages each generated transaction to S3
     * and performs the account-boundary roll-up.
     *
     * <p>The same {@link #interestRollupWriter()} bean is registered as <strong>both</strong> the chunk
     * writer and a {@link StepExecutionListener}; the {@code listener(StepExecutionListener)} overload is
     * selected (the writer implements only {@link ItemWriter} and {@link StepExecutionListener}, so it is
     * unambiguously more specific than {@code listener(Object)}), which wires
     * {@link InterestRollupWriter#afterStep(StepExecution)} to flush the final account at end-of-file. The
     * bean method is invoked once and the resulting (step-scoped proxy) reference is shared so the writer
     * and listener resolve to the same step-scoped target.</p>
     *
     * <p>The chunk transaction is bounded by {@code transactionManager}; an unchecked exception thrown
     * during the roll-up {@code save} (e.g. the optimistic-lock failure on the {@code Account}
     * {@code @Version}, or the defensive {@link IllegalStateException}) rolls the chunk back &mdash; the
     * faithful equivalent of the COBOL {@code REWRITE}-failure abend. Spring Batch&nbsp;5.x builders are used
     * with explicit {@link JobRepository} and {@link PlatformTransactionManager} (no
     * {@code StepBuilderFactory}).</p>
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     * @param transactionManager the auto-configured transaction manager bounding each chunk transaction
     * @return the configured interest-calculation step
     */
    @Bean
    public Step interestCalculationStep(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager) {
        // Resolve the step-scoped writer proxy once and register it as BOTH writer and StepExecutionListener
        // so write(...) (per-row staging + roll-up) and afterStep(...) (EOF flush) share one target.
        final InterestRollupWriter rollupWriter = interestRollupWriter();
        return new StepBuilder("interestCalculationStep", jobRepository)
                .<TransactionCategoryBalance, InterestCalculationResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(interestTcatbalReader())
                .processor(interestCalculationProcessor)
                .writer(rollupWriter)
                .listener(rollupWriter)
                .build();
    }

    /**
     * Assembles the Stage&nbsp;2 job from its single step, mirroring {@code INTCALC.jcl} (one
     * {@code EXEC PGM=CBACT04C}, with <strong>no</strong> {@code COND}). The Spring&nbsp;Batch&nbsp;5.x
     * {@link JobBuilder} is used with an explicit {@link JobRepository}.
     *
     * <p><strong>Job-parameter contract.</strong> This job (through {@link InterestCalculationProcessor})
     * expects a {@code parmDate} job parameter &mdash; the 10-character {@code PARM='2022071800'} run date
     * carried by the JCL (default {@value #DEFAULT_PARM_DATE}). The launcher/orchestrator (the Stage&nbsp;0
     * {@code BatchPipelineOrchestrator}) MUST supply it when starting this job.</p>
     *
     * @param jobRepository           the auto-configured Spring Batch job repository
     * @param interestCalculationStep the single step assembled by
     *                                {@link #interestCalculationStep(JobRepository, PlatformTransactionManager)}
     * @return the configured interest-calculation job
     */
    @Bean
    public Job interestCalculationJob(final JobRepository jobRepository,
            final Step interestCalculationStep) {
        return new JobBuilder("interestCalculationJob", jobRepository)
                .start(interestCalculationStep)
                .build();
    }

    /**
     * Stateful chunk writer that reproduces {@code CBACT04C}'s account-boundary roll-up
     * ({@code 1050-UPDATE-ACCOUNT}) and stages the generated interest transactions to S3 (the
     * {@code SYSTRAN(+1)} GDG &rarr; {@code carddemo-batch-output} substitution, decision
     * <strong>D-003</strong>).
     *
     * <p><strong>Why a stateful writer (and not the processor).</strong> COBOL accumulates each row's
     * {@code WS-MONTHLY-INT} into a per-account {@code WS-TOTAL-INT} and applies that total to
     * {@code ACCT-CURR-BAL} only at an <em>account boundary</em> &mdash; when {@code TRANCAT-ACCT-ID}
     * changes and again at end-of-file. A chunk-oriented {@code ItemProcessor} processes one item in
     * isolation and cannot observe account boundaries across the chunk stream, so the roll-up is
     * intentionally implemented here as a stateful writer that retains the running account id and
     * accumulated interest between {@code write(...)} calls and performs the final flush in
     * {@link #afterStep(StepExecution)}. This is correct precisely because {@link #interestTcatbalReader()}
     * delivers rows <strong>account-ordered</strong>, so every account's rows are contiguous.</p>
     *
     * <p><strong>Scope.</strong> The owning {@code @Bean} method is {@code @StepScope}, so a fresh writer
     * (with {@link #currentAccountId} {@code null} and {@link #accumulatedInterest}
     * {@link BigDecimal#ZERO}) is created for each step execution; the per-run state therefore never
     * leaks between job runs. The class is package-private and non-final so the {@code @StepScope}
     * CGLIB scoped proxy can be created.</p>
     *
     * <p><strong>Boundary with the processor.</strong> The interest arithmetic, rate lookup,
     * {@code DEFAULT}-group fallback and interest-{@link Transaction} construction are entirely the
     * processor's responsibility; this writer only stages the already-built transaction and applies the
     * account roll-up. It performs no interest math.</p>
     */
    // CBACT04C 1050-UPDATE-ACCOUNT roll-up + SYSTRAN(+1) GDG -> S3 staging. Non-final/package-private so
    // the @StepScope CGLIB scoped proxy can subclass it; instantiated via the @Bean method below.
    static class InterestRollupWriter
            implements ItemWriter<InterestCalculationResult>, StepExecutionListener {

        /** Key prefix under which staged SYSTRAN interest transactions are stored (GDG -> S3, D-003). */
        private static final String S3_SYSTRAN_KEY_PREFIX = "systran/";

        /** Object-key suffix for a staged SYSTRAN record. */
        private static final String S3_OBJECT_SUFFIX = ".dat";

        /** Generation-prefix date pattern emulating a GDG generation bucket ({@code SYSTRAN(+1)}). */
        private static final DateTimeFormatter S3_GENERATION_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

        /** Content type of a staged SYSTRAN object (fixed-width, decimal-faithful text). */
        private static final String SYSTRAN_CONTENT_TYPE = "text/plain";

        /**
         * Full 26-character DB2 timestamp pattern ({@code yyyy-MM-dd-HH.mm.ss.SSSSSS}) matching the COBOL
         * {@code Z-GET-DB2-FORMAT-TIMESTAMP} rendering of {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS}
         * ({@code PIC X(26)}).
         */
        private static final DateTimeFormatter DB2_TIMESTAMP_FULL =
                DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

        /**
         * Fixed SYSTRAN record length. The legacy {@code TRANSACT} DD is {@code RECFM=F LRECL=350} and the
         * record layout is {@code CVTRA05Y} ({@code 01 TRAN-RECORD}, RECLN 350); the staged object
         * preserves that exact record length (external-interface contract, AAP &sect;0.7.2).
         */
        private static final int SYSTRAN_RECORD_LENGTH = 350;

        /** Scale of {@code TRAN-AMT PIC S9(09)V99} (two fractional digits). */
        private static final int AMOUNT_SCALE = 2;

        /** Account-master repository &mdash; the roll-up target ({@code ACCTDAT REWRITE}). */
        private final AccountRepository accountRepository;

        /** S3 abstraction used to stage interest transactions (SYSTRAN replacement). */
        private final S3Template s3Template;

        /** Destination bucket for staged SYSTRAN objects ({@code carddemo-batch-output}). */
        private final String outputBucket;

        /** Clock for the deterministic {@code yyyyMMdd} S3 generation prefix. */
        private final Clock clock;

        /**
         * The account id currently being accumulated ({@code WS-LAST-ACCT-NUM}). {@code null} until the
         * first row is seen (the COBOL {@code WS-FIRST-TIME = 'Y'} state, on which no roll-up fires).
         */
        private Long currentAccountId;

        /**
         * Running per-account interest total ({@code WS-TOTAL-INT}). Reset to {@link BigDecimal#ZERO} on
         * each account boundary, exactly as COBOL {@code MOVE 0 TO WS-TOTAL-INT}.
         */
        private BigDecimal accumulatedInterest = BigDecimal.ZERO;

        /**
         * Constructs the writer with its (immutable) collaborators. The mutable roll-up state is created
         * fresh per step execution because the owning bean is {@code @StepScope}.
         *
         * @param accountRepository the account-master repository (roll-up target); must not be {@code null}
         * @param s3Template        the S3 template used for SYSTRAN staging; must not be {@code null}
         * @param outputBucket      the SYSTRAN staging bucket ({@code carddemo-batch-output}); must not be
         *                          {@code null}
         * @param clock             the clock for the deterministic S3 generation prefix; must not be
         *                          {@code null}
         */
        InterestRollupWriter(final AccountRepository accountRepository,
                final S3Template s3Template,
                final String outputBucket,
                final Clock clock) {
            this.accountRepository = accountRepository;
            this.s3Template = s3Template;
            this.outputBucket = outputBucket;
            this.clock = clock;
        }

        /**
         * Processes one chunk of {@link InterestCalculationResult}s in arrival (account) order,
         * reproducing the body of {@code CBACT04C}'s main loop for the staging and roll-up concerns.
         *
         * <p>For each result, in order:</p>
         * <ol>
         *   <li><strong>Stage the interest transaction</strong> ({@code 1300-B-WRITE-TX} {@code WRITE}).
         *       Only when interest was applied ({@code DIS-INT-RATE NOT = 0}); on the zero-rate path COBOL
         *       writes no transaction, so nothing is staged.</li>
         *   <li><strong>Account boundary</strong> ({@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM} with
         *       {@code WS-FIRST-TIME NOT = 'Y'}). When the account changes and a previous account is in
         *       progress, flush it via {@link #applyAccountRollup(Long, BigDecimal)} (this IS
         *       {@code 1050-UPDATE-ACCOUNT}) and reset the accumulator ({@code MOVE 0 TO WS-TOTAL-INT}).
         *       On the very first row ({@link #currentAccountId} {@code null}) no flush occurs, matching
         *       {@code WS-FIRST-TIME = 'Y'}.</li>
         *   <li><strong>Advance &amp; accumulate</strong> ({@code MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM}
         *       then {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}). The monthly interest is always present
         *       ({@link BigDecimal#ZERO} on the zero-rate path), so the addition is a no-op there.</li>
         * </ol>
         *
         * @param chunk the account-ordered chunk of per-row interest results (never {@code null})
         */
        @Override
        public void write(final Chunk<? extends InterestCalculationResult> chunk) {
            for (final InterestCalculationResult result : chunk) {
                // 1) 1300-B-WRITE-TX: stage the generated interest transaction to S3 (SYSTRAN replacement),
                //    only when interest was applied (DIS-INT-RATE NOT = 0). On the zero-rate path COBOL
                //    writes no transaction, so nothing is staged.
                if (result.interestApplied()) {
                    stageInterestTransactionToS3(result.interestTransaction());
                }

                // 2) Account boundary (TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM, WS-FIRST-TIME NOT = 'Y'):
                //    flush the previous account (1050-UPDATE-ACCOUNT) then reset the accumulator
                //    (MOVE 0 TO WS-TOTAL-INT). No flush on the first row (WS-FIRST-TIME = 'Y').
                final Long resultAccountId = result.accountId();
                if (currentAccountId != null && !currentAccountId.equals(resultAccountId)) {
                    applyAccountRollup(currentAccountId, accumulatedInterest);
                    accumulatedInterest = BigDecimal.ZERO;
                }

                // 3) Advance to the current account (MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM) and
                //    accumulate this row's monthly interest (ADD WS-MONTHLY-INT TO WS-TOTAL-INT).
                currentAccountId = resultAccountId;
                accumulatedInterest = accumulatedInterest.add(result.monthlyInterest());
            }
        }

        /**
         * Applies the accumulated interest to the account and resets the cycle credit/debit totals &mdash;
         * the faithful translation of COBOL paragraph {@code 1050-UPDATE-ACCOUNT}:
         * <pre>{@code
         * ADD WS-TOTAL-INT  TO ACCT-CURR-BAL
         * MOVE 0 TO ACCT-CURR-CYC-CREDIT
         * MOVE 0 TO ACCT-CURR-CYC-DEBIT
         * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
         * }</pre>
         *
         * <p>The COBOL {@code READ ACCOUNT-FILE} ({@code 1100-GET-ACCT-DATA}) was already performed for
         * this account by {@link InterestCalculationProcessor} (which abends &mdash; mapped to an
         * exception &mdash; if the account is absent), so by the time a row reaches this writer the
         * account exists. The {@code findById} guard below is therefore a defensive invariant check: a
         * missing account here is an inconsistent/illegal state (it faithfully maps the COBOL
         * {@code REWRITE} error path that performs {@code 9999-ABEND-PROGRAM}). An
         * {@link IllegalStateException} is used rather than a domain exception because no domain exception
         * type is among this file's permitted dependencies, and an unchecked throw here rolls the chunk
         * back &mdash; the faithful equivalent of the COBOL abend. The balance arithmetic uses
         * {@link BigDecimal#add(BigDecimal)} with no {@code float}/{@code double} (AAP &sect;0.7.3); the
         * cycle totals are reset to {@link BigDecimal#ZERO}. {@code save} honours the {@code Account}
         * {@code @Version} optimistic lock.</p>
         *
         * @param acctId        the account id to roll up ({@code WS-LAST-ACCT-NUM})
         * @param totalInterest the accumulated interest for the account ({@code WS-TOTAL-INT})
         */
        // COBOL: 1050-UPDATE-ACCOUNT (ADD WS-TOTAL-INT TO ACCT-CURR-BAL; zero cycle credit/debit; REWRITE).
        private void applyAccountRollup(final Long acctId, final BigDecimal totalInterest) {
            final Account account = accountRepository.findById(acctId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Account " + acctId + " not found during interest roll-up (1050-UPDATE-ACCOUNT); "
                                    + "the processor's 1100-GET-ACCT-DATA read should have guaranteed it exists"));

            // ADD WS-TOTAL-INT TO ACCT-CURR-BAL (BigDecimal add; no float/double; scale preserved).
            account.setAcctCurrBal(account.getAcctCurrBal().add(totalInterest));
            // MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT.
            account.setAcctCurrCycCredit(BigDecimal.ZERO);
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            // REWRITE FD-ACCTFILE-REC; save honours the Account @Version optimistic lock.
            accountRepository.save(account);
        }

        /**
         * Flushes the final account at end-of-step &mdash; the {@code CBACT04C} end-of-file roll-up. The
         * last account's rows are never followed by an account change, so its {@code 1050-UPDATE-ACCOUNT}
         * is performed here, ensuring every account (including the last) is rolled up exactly once.
         *
         * @param stepExecution the completing step execution
         * @return the step's existing exit status (this listener does not alter it)
         */
        // COBOL: end-of-file 1050-UPDATE-ACCOUNT for the final account.
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            if (currentAccountId != null) {
                applyAccountRollup(currentAccountId, accumulatedInterest);
            }
            return stepExecution.getExitStatus();
        }

        /**
         * Stages a generated interest {@link Transaction} to S3 &mdash; the technology substitution for
         * the COBOL {@code WRITE FD-TRANFILE-REC} to the {@code SYSTRAN(+1)} GDG ({@code RECFM=F
         * LRECL=350}). The object body is the fixed-width 350-byte SYSTRAN record (see
         * {@link #serializeSystranRecord(Transaction)}); the destination bucket is resolved from
         * configuration ({@code carddemo-batch-output}) and never hardcoded. The key is deterministic and
         * idempotent (see {@link #buildSystranObjectKey(Transaction)}), so a Spring Batch retry of the
         * same chunk re-emits the same key and overwrites rather than duplicating. The upload uses the
         * auto-configured {@link S3Template}, so the endpoint resolves only to LocalStack (zero live AWS).
         *
         * @param interestTransaction the interest transaction to stage (never {@code null} when interest
         *                            was applied)
         */
        private void stageInterestTransactionToS3(final Transaction interestTransaction) {
            final String objectKey = buildSystranObjectKey(interestTransaction);
            final byte[] payload = serializeSystranRecord(interestTransaction).getBytes(StandardCharsets.UTF_8);
            final ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType(SYSTRAN_CONTENT_TYPE)
                    .contentLength((long) payload.length)
                    .build();
            // ByteArrayInputStream is backed by the byte[] and holds no external resource, so it needs no
            // explicit close; S3Template fully consumes it during the upload.
            s3Template.upload(outputBucket, objectKey, new ByteArrayInputStream(payload), metadata);
        }

        /**
         * Builds the deterministic, generation-prefixed S3 key for a staged SYSTRAN record:
         * {@code systran/<yyyyMMdd>/<tranId>.dat}. The {@code <yyyyMMdd>} segment (from {@link #clock})
         * emulates a GDG generation bucket; keying on the unique {@code TRAN-ID} (the COBOL interest
         * transaction id is {@code PARM-DATE} + a monotonic suffix) makes the key stable for the same
         * record so a retry overwrites rather than duplicates.
         *
         * @param interestTransaction the transaction being staged
         * @return the deterministic S3 object key
         */
        private String buildSystranObjectKey(final Transaction interestTransaction) {
            final String generation = LocalDate.now(clock).format(S3_GENERATION_DATE);
            return S3_SYSTRAN_KEY_PREFIX + generation + "/"
                    + safeKeySegment(interestTransaction.getTranId()) + S3_OBJECT_SUFFIX;
        }

        /**
         * Serializes a {@link Transaction} into the fixed-width 350-byte {@code SYSTRAN} record, preserving
         * the {@code CVTRA05Y} field order, lengths and the {@code LRECL=350} contract (AAP &sect;0.7.2).
         * Alphanumeric ({@code PIC X}) fields are left-justified and space-padded; numeric
         * ({@code PIC 9}) fields are right-justified and zero-padded. {@code TRAN-AMT PIC S9(09)V99} is
         * rendered as 11 digit positions (nine integer + two fractional) with the implied decimal point
         * removed, matching the COBOL display layout; its sign-overpunch is intentionally not reproduced
         * because exact EBCDIC byte encoding is byte-level reference only (out of scope, AAP &sect;0.6.5)
         * and interest amounts are non-negative. No {@code float}/{@code double} is used (AAP &sect;0.7.3).
         *
         * @param tx the transaction to serialize
         * @return a string of exactly {@link #SYSTRAN_RECORD_LENGTH} characters
         */
        // CVTRA05Y 01 TRAN-RECORD (RECLN 350) fixed-width layout; preserves the SYSTRAN external contract.
        private String serializeSystranRecord(final Transaction tx) {
            final StringBuilder record = new StringBuilder(SYSTRAN_RECORD_LENGTH);
            record.append(padRight(tx.getTranId(), 16));                            // TRAN-ID            X(16)
            record.append(padRight(tx.getTranTypeCd(), 2));                         // TRAN-TYPE-CD       X(02)
            record.append(leftPadZeros(integerToDigits(tx.getTranCatCd()), 4));     // TRAN-CAT-CD        9(04)
            record.append(padRight(tx.getTranSource(), 10));                        // TRAN-SOURCE        X(10)
            record.append(padRight(tx.getTranDesc(), 100));                         // TRAN-DESC          X(100)
            record.append(leftPadZeros(amountToDigits(tx.getTranAmt()), 11));       // TRAN-AMT           S9(09)V99
            record.append(leftPadZeros(longToDigits(tx.getTranMerchantId()), 9));   // TRAN-MERCHANT-ID   9(09)
            record.append(padRight(tx.getTranMerchantName(), 50));                  // TRAN-MERCHANT-NAME X(50)
            record.append(padRight(tx.getTranMerchantCity(), 50));                  // TRAN-MERCHANT-CITY X(50)
            record.append(padRight(tx.getTranMerchantZip(), 10));                   // TRAN-MERCHANT-ZIP  X(10)
            record.append(padRight(tx.getTranCardNum(), 16));                       // TRAN-CARD-NUM      X(16)
            record.append(padRight(formatTimestamp(tx.getTranOrigTs()), 26));       // TRAN-ORIG-TS       X(26)
            record.append(padRight(formatTimestamp(tx.getTranProcTs()), 26));       // TRAN-PROC-TS       X(26)
            record.append(" ".repeat(20));                                          // FILLER             X(20)
            // Guarantee the LRECL=350 contract regardless of any oversized input field.
            return padRight(record.toString(), SYSTRAN_RECORD_LENGTH);
        }

        /**
         * Renders a {@code TRAN-AMT} ({@code PIC S9(09)V99}) as its 11 implied-decimal digit positions
         * (value &times; 100, absolute, no decimal point). Scale-2 with {@link java.math.RoundingMode#HALF_EVEN}
         * is applied for completeness (banker's rounding, AAP &sect;0.7.3); posted amounts already carry
         * scale&nbsp;2 so no rounding actually occurs. No {@code float}/{@code double} is used.
         *
         * @param amount the monetary amount (may be {@code null})
         * @return the unscaled digit string, or {@code "0"} when {@code amount} is {@code null}
         */
        private static String amountToDigits(final BigDecimal amount) {
            if (amount == null) {
                return "0";
            }
            return amount.setScale(AMOUNT_SCALE, java.math.RoundingMode.HALF_EVEN)
                    .movePointRight(AMOUNT_SCALE)
                    .toBigInteger()
                    .abs()
                    .toString();
        }

        /**
         * Renders an {@link Integer} code as its digit string, or {@code "0"} when {@code null}.
         *
         * @param value the value (may be {@code null})
         * @return the digit string
         */
        private static String integerToDigits(final Integer value) {
            return value == null ? "0" : Integer.toString(Math.abs(value));
        }

        /**
         * Renders a {@link Long} id as its digit string, or {@code "0"} when {@code null}.
         *
         * @param value the value (may be {@code null})
         * @return the digit string
         */
        private static String longToDigits(final Long value) {
            return value == null ? "0" : Long.toString(Math.abs(value));
        }

        /**
         * Formats a {@link LocalDateTime} as the 26-character DB2 timestamp ({@code TRAN-ORIG-TS}/
         * {@code TRAN-PROC-TS} {@code PIC X(26)}); returns an empty string when {@code null} (which the
         * caller space-pads to the field width).
         *
         * @param timestamp the timestamp (may be {@code null})
         * @return the 26-character DB2 rendering, or {@code ""} when {@code null}
         */
        private static String formatTimestamp(final LocalDateTime timestamp) {
            return timestamp == null ? "" : timestamp.format(DB2_TIMESTAMP_FULL);
        }

        /**
         * Left-justifies {@code value} in a field of {@code width}, space-padding when shorter and
         * truncating when longer (an alphanumeric {@code PIC X} field).
         *
         * @param value the value (may be {@code null}, treated as empty)
         * @param width the fixed field width
         * @return a string of exactly {@code width} characters
         */
        private static String padRight(final String value, final int width) {
            final String safe = value == null ? "" : value;
            if (safe.length() >= width) {
                return safe.substring(0, width);
            }
            return safe + " ".repeat(width - safe.length());
        }

        /**
         * Right-justifies a digit string in a field of {@code width}, zero-padding when shorter and keeping
         * the rightmost {@code width} digits when longer (a numeric {@code PIC 9} field, matching COBOL
         * low-order truncation).
         *
         * @param digits the digit string (assumed non-{@code null})
         * @param width  the fixed field width
         * @return a string of exactly {@code width} characters
         */
        private static String leftPadZeros(final String digits, final int width) {
            if (digits.length() >= width) {
                return digits.substring(digits.length() - width);
            }
            return "0".repeat(width - digits.length()) + digits;
        }

        /**
         * Normalizes a transaction id into a safe single S3 key segment by trimming surrounding whitespace
         * and replacing any path separator.
         *
         * @param tranId the transaction id (may be {@code null} or blank)
         * @return a non-blank key segment ({@code "unknown"} when the id is {@code null}/blank)
         */
        private static String safeKeySegment(final String tranId) {
            if (tranId == null) {
                return "unknown";
            }
            final String trimmed = tranId.trim();
            return trimmed.isEmpty() ? "unknown" : trimmed.replace('/', '_');
        }
    }
}
