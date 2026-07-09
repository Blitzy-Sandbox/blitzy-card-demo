package com.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@link Configuration} for pipeline stage&nbsp;2 &mdash; the <strong>interest
 * calculation</strong> stage (job/step config&nbsp;#2 of&nbsp;5, {@code INTCALC}). It wires the
 * chunk-oriented {@code interestCalculationStep} and the standalone {@code interestCalculationJob},
 * reproducing the legacy batch program {@code CBACT04C} launched by {@code INTCALC.jcl}
 * ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}). Pipeline order (AAP&nbsp;&sect;0.8.5):
 * {@code POSTTRAN} &rarr; <strong>stage&nbsp;2 {@code INTCALC}</strong> &rarr; {@code COMBTRAN} &rarr;
 * {@code CREASTMT}/{@code TRANREPT}.
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f})</h2>
 * <p>The source artifacts are <strong>not</strong> copied into this repository; they are referenced by
 * commit SHA for traceability only (see {@code docs/traceability-matrix.md}). {@code CBACT04C} is a
 * single monolithic {@code PERFORM UNTIL END-OF-FILE} loop that scans the transaction-category-balance
 * file ({@code TCATBAL}) in ascending account-id order and drives a <em>control break</em> on the
 * account id &mdash; performing its per-account interest work exactly once for each distinct account
 * that owns at least one {@code TCATBAL} row:</p>
 *
 * <pre>
 *   //STEP15   EXEC PGM=CBACT04C,PARM='2022071800'
 *   //TCATBALF DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS   (txn category balance — driver)
 *   //XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS   (card cross-reference lookup)
 *   //ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS   (account master update)
 *   //DISCGRP  DD DISP=SHR,DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS    (disclosure-group interest rate)
 *   //TRANSACT DD DISP=(NEW,CATLG,DELETE),DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)  (interest txn output)
 * </pre>
 *
 * <p>In the modular target that read loop belongs to the Spring Batch layer, realized as the standard
 * {@code reader &rarr; processor &rarr; writer} chunk shape, one item per distinct account id:</p>
 * <ul>
 *   <li><strong>Read ({@code TCATBAL}, paragraph&nbsp;1000 / control break)</strong> &mdash;
 *       {@link InterestAccountItemReader} emits the distinct account ids present in the
 *       transaction-category-balance table, ascending, one {@link Long} per item. Exhaustion returns
 *       {@code null}, the idiomatic translation of the COBOL EOF {@code FILE STATUS '10'}
 *       loop-termination (EOF is <em>not</em> an exception).</li>
 *   <li><strong>Compute &amp; persist (paragraphs 1050/1100/1110/1200/1300)</strong> &mdash;
 *       {@link InterestCalculationProcessor} delegates each account to
 *       {@code InterestCalculationService.applyInterestToAccount(Long)}, which resolves the
 *       disclosure-group rate, computes the monthly interest ({@code BigDecimal} scale&nbsp;2), writes
 *       one interest transaction per category, rolls the accrued interest into the account balance, and
 *       zeroes the current-cycle credit/debit accumulators &mdash; all within its own
 *       {@code @Transactional(rollbackFor = Exception.class)} boundary. The processor returns the
 *       account id unchanged (an identity carrier) so the item is counted and flows to the writer.</li>
 *   <li><strong>Write ({@code interestCalculationWriter})</strong> &mdash; a lightweight,
 *       <em>non-persisting</em> logging writer (see the writer note below).</li>
 * </ul>
 *
 * <h2>Writer note &mdash; deliberate no-op/logging writer (decision-log-worthy, AAP&nbsp;&sect;0.8.6)</h2>
 * <p>Because {@link InterestCalculationProcessor} delegates to a service that
 * <strong>self-persists</strong> the interest transactions and the account-balance update inside its
 * own transaction, there is nothing further for a writer to persist. Re-saving the account here would
 * collide with the account's {@link jakarta.persistence.Version @Version} optimistic-lock column and
 * double-persist the interest. This stage therefore defines a lightweight <strong>inline</strong>
 * {@code @Bean ItemWriter<Long>} (a lambda that logs the processed account ids at {@code DEBUG} and
 * reports the per-chunk count) rather than a bespoke custom writer class. Keeping it inline also
 * preserves the migration's three-custom-writer budget (reserved for the posting, statement, and report
 * stages); the rationale is recorded in {@code docs/decision-log.md} rather than duplicated here as a
 * code comment.</p>
 *
 * <h2>PARM handling (AAP file spec)</h2>
 * <p>The JCL {@code PARM='2022071800'} (the interest/fee processing date, format {@code YYYYMMDDHH}) is
 * externalized to the Spring property {@code carddemo.batch.interest.processing-date}
 * (see {@code application.yml}); it is bound here via {@link Value} and <em>never</em> hardcoded into
 * the wiring. The current {@code InterestCalculationService.applyInterestToAccount(Long)} contract does
 * not consume the date (it seeds transaction ids from the shared cross-reference generator), so the
 * value is used only for a one-time configuration diagnostic. Should a future revision of the service
 * require the date, it is supplied as a {@code JobParameters} entry at launch time (by
 * {@code config/BatchConfig} or an operator) rather than baked into this {@link Job} definition &mdash;
 * consistent with {@link RunIdIncrementer}-based, on-demand re-runnable launches.</p>
 *
 * <h2>Wiring notes</h2>
 * <ul>
 *   <li>This configuration deliberately does <strong>not</strong> declare
 *       {@code @EnableBatchProcessing}; under Spring Boot 3.x that annotation would <em>disable</em>
 *       Boot's Batch auto-configuration. The {@link JobRepository} and
 *       {@link PlatformTransactionManager} are the auto-configured beans, injected by constructor
 *       (the Spring Batch 5.x replacement for the removed {@code JobBuilderFactory} /
 *       {@code StepBuilderFactory}).</li>
 *   <li>The reader, processor, and correlation-id listener are constructor-injected by type as their
 *       concrete {@code @Component}s: {@link InterestAccountItemReader},
 *       {@link InterestCalculationProcessor}, and {@link BatchCorrelationIdListener}. The step's chunk
 *       is explicitly typed {@code <Long, Long>} (account-id input, identity-carrier output).</li>
 *   <li>The job registers {@link BatchCorrelationIdListener} (Observability rule, AAP&nbsp;&sect;0.7.1)
 *       so every log line emitted during the run carries the MDC {@code correlationId}, and a
 *       {@link RunIdIncrementer} so each launch is a distinct, re-runnable {@code JobInstance} &mdash;
 *       the on-demand-rerun / idempotency analogue of the JCL rerun semantics (AAP&nbsp;&sect;0.8.5).</li>
 *   <li>The named {@code interestCalculationStep} bean is consumed by {@code config/BatchConfig} to
 *       compose the master five-stage flow; the {@code interestCalculationJob} bean allows this stage to
 *       be launched and tested independently. No job is auto-run at startup
 *       ({@code spring.batch.job.enabled=false}, declared in {@code application.yml}); launching is
 *       explicit. This class does not override that setting.</li>
 *   <li>The chunk size is externalized from the JCL {@code SYSIN}/PARM model to the Spring property
 *       {@code carddemo.batch.chunk-size} (default {@code 100}); aligning it with the reader's paging
 *       page size keeps the account scan memory-bounded.</li>
 *   <li>The bean methods reference one another as sibling calls (the step references the writer; the job
 *       starts the step), which relies on the CGLIB proxying of a full {@code @Configuration}
 *       ({@code proxyBeanMethods = true}, the default) to return the managed singletons.</li>
 * </ul>
 *
 * <p><strong>Bean naming.</strong> The configuration class is explicitly named
 * {@code "interestCalculationJobConfig"} so that the class's own component bean name does not collide
 * with the {@code interestCalculationJob} {@link Job} bean it exposes (the decapitalized class name and
 * that bean name would otherwise be identical, which Spring Boot rejects under
 * {@code spring.main.allow-bean-definition-overriding = false}). The three exposed bean names &mdash;
 * {@code interestCalculationStep}, {@code interestCalculationJob}, and {@code interestCalculationWriter}
 * &mdash; are contractually fixed by the AAP file specification.</p>
 *
 * <p>This class is pure wiring: it holds no business logic and performs no arithmetic. It defines no
 * monetary fields, so no {@code float}/{@code double} decimal-precision concerns apply
 * (AAP&nbsp;&sect;0.8.2); all interest math ({@code BigDecimal} scale&nbsp;2) lives in the service.</p>
 *
 * @see InterestAccountItemReader
 * @see InterestCalculationProcessor
 * @see BatchCorrelationIdListener
 */
@Configuration("interestCalculationJobConfig")
public class InterestCalculationJob {

    /**
     * Structured logger for one-time configuration diagnostics and the non-persisting chunk writer.
     * Emits only account ids and counts (never monetary values or card numbers); the per-execution
     * {@code correlationId} MDC entry is contributed by {@link BatchCorrelationIdListener}.
     */
    private static final Logger log = LoggerFactory.getLogger(InterestCalculationJob.class);

    /** Spring Boot auto-configured Spring Batch job repository, backing the job and step meta-data. */
    private final JobRepository jobRepository;

    /** Spring Boot auto-configured transaction manager bounding each chunk's read/commit window. */
    private final PlatformTransactionManager transactionManager;

    /** Reader emitting the distinct {@code TCATBAL} account ids, ascending (the {@code CBACT04C} driver). */
    private final InterestAccountItemReader interestAccountItemReader;

    /** Processor delegating each account to the self-persisting interest-calculation service. */
    private final InterestCalculationProcessor interestCalculationProcessor;

    /** Listener that seeds the per-execution MDC correlation id; registered on the interest job. */
    private final BatchCorrelationIdListener batchCorrelationIdListener;

    /**
     * Chunk size and commit interval, externalized from the JCL {@code SYSIN}/PARM model to the Spring
     * property {@code carddemo.batch.chunk-size}. Defaults to {@code 100} when the property is not
     * supplied by the active profile.
     */
    private final int chunkSize;

    /**
     * Interest/fee processing date ({@code YYYYMMDDHH}) &mdash; the {@code INTCALC.jcl}
     * {@code PARM='2022071800'} externalized to {@code carddemo.batch.interest.processing-date}. Bound
     * here rather than hardcoded; used only for a configuration diagnostic (the service does not
     * currently consume it &mdash; see the class-level PARM-handling note).
     */
    private final String interestProcessingDate;

    /**
     * Creates the interest-calculation-stage configuration with all collaborators injected by
     * constructor (the single constructor is auto-detected by Spring, so no {@code @Autowired} is
     * required). The constructor performs field assignment only &mdash; it invokes no overridable
     * method, so {@code this} never escapes during construction, keeping the {@code -Xlint:all} build
     * warning-free ({@code this-escape}).
     *
     * @param jobRepository                the auto-configured Spring Batch job repository
     * @param transactionManager           the auto-configured platform transaction manager
     * @param interestAccountItemReader    the reader emitting distinct {@code TCATBAL} account ids
     * @param interestCalculationProcessor the processor delegating per-account interest to the service
     * @param batchCorrelationIdListener   the correlation-id listener registered on the interest job
     * @param chunkSize                    the chunk size / commit interval
     *                                     ({@code carddemo.batch.chunk-size}, default {@code 100})
     * @param interestProcessingDate       the interest processing date
     *                                     ({@code carddemo.batch.interest.processing-date}, the
     *                                     {@code INTCALC} PARM; default {@code 2022071800})
     */
    InterestCalculationJob(final JobRepository jobRepository,
                           final PlatformTransactionManager transactionManager,
                           final InterestAccountItemReader interestAccountItemReader,
                           final InterestCalculationProcessor interestCalculationProcessor,
                           final BatchCorrelationIdListener batchCorrelationIdListener,
                           @Value("${carddemo.batch.chunk-size:100}") final int chunkSize,
                           @Value("${carddemo.batch.interest.processing-date:2022071800}")
                           final String interestProcessingDate) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.interestAccountItemReader = interestAccountItemReader;
        this.interestCalculationProcessor = interestCalculationProcessor;
        this.batchCorrelationIdListener = batchCorrelationIdListener;
        this.chunkSize = chunkSize;
        this.interestProcessingDate = interestProcessingDate;
    }

    /**
     * The deliberately lightweight, <strong>non-persisting</strong> chunk writer for the interest stage.
     *
     * <p>The per-account interest transactions and the account-balance update are already persisted by
     * {@code InterestCalculationService.applyInterestToAccount(Long)} within its own transaction (via
     * {@link InterestCalculationProcessor}), so this writer performs no persistence: it only logs the
     * processed account ids at {@code DEBUG} and reports the per-chunk count. Persisting the account
     * again here would collide with its {@code @Version} optimistic-lock column and double-persist the
     * interest &mdash; hence the inline logging lambda rather than a bespoke writer class (see the
     * class-level writer note; rationale in {@code docs/decision-log.md}).</p>
     *
     * <p>The lambda captures no mutable state (only the static {@code log} and its {@code Chunk}
     * argument), so the returned {@link ItemWriter} is stateless and thread-safe. Parameterised logging
     * defers all string building to when {@code DEBUG} is enabled; the cumulative processed count is
     * tracked independently by Spring Batch in the step's {@code writeCount} metadata.</p>
     *
     * @return a stateless {@link ItemWriter} of account ids that logs (never persists) each chunk
     */
    @Bean
    ItemWriter<Long> interestCalculationWriter() {
        return chunk -> log.debug(
                "interestCalculationStep processed {} account id(s) this chunk "
                        + "(interest already persisted by InterestCalculationService): {}",
                chunk.size(), chunk.getItems());
    }

    /**
     * The interest-calculation chunk step: reads the distinct {@code TCATBAL} account ids
     * ({@link InterestAccountItemReader}), applies monthly interest to each account by delegating to the
     * self-persisting service ({@link InterestCalculationProcessor}), and logs the processed ids
     * ({@link #interestCalculationWriter()}) &mdash; the migrated {@code CBACT04C} control-break loop.
     *
     * <p>The chunk is explicitly typed {@code <Long, Long>} (account-id input, identity-carrier output)
     * and its commit interval equals {@link #chunkSize}, bounding the reader's page fill; the durable
     * per-account transaction boundary is owned by the service, not the chunk. No skip or retry policy is
     * attached: a missing account/cross-reference or an optimistic-lock conflict must fail the chunk
     * (the {@code CBACT04C} {@code INVALID KEY} / abend semantics), never be silently skipped.</p>
     *
     * @return the fully built {@code interestCalculationStep}
     */
    @Bean
    Step interestCalculationStep() {
        // One-time configuration diagnostic. References the externalized JCL PARM (never hardcoded) so
        // the operative processing date and chunk size are visible in the logs at DEBUG.
        log.debug("Configuring interestCalculationStep (chunkSize={}, interestProcessingDate={} — "
                + "INTCALC PARM, consumed at launch only if the service requires it)",
                chunkSize, interestProcessingDate);
        return new StepBuilder("interestCalculationStep", jobRepository)
                .<Long, Long>chunk(chunkSize, transactionManager)
                .reader(interestAccountItemReader)
                .processor(interestCalculationProcessor)
                .writer(interestCalculationWriter())
                .build();
    }

    /**
     * The standalone interest-calculation job wrapping {@link #interestCalculationStep()}. It registers a
     * {@link RunIdIncrementer} (so each launch is a distinct, re-runnable {@code JobInstance}, matching
     * the JCL rerun semantics) and the {@link BatchCorrelationIdListener} (so every log line emitted
     * during the run carries the MDC {@code correlationId}). No job is auto-run on startup
     * ({@code spring.batch.job.enabled=false}); launching is driven explicitly &mdash; either directly
     * for independent testing or via {@code config/BatchConfig}'s master flow, which reuses the same
     * {@code interestCalculationStep} bean.
     *
     * @return the fully built {@code interestCalculationJob}
     */
    @Bean
    Job interestCalculationJob() {
        return new JobBuilder("interestCalculationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchCorrelationIdListener)
                .start(interestCalculationStep())
                .build();
    }
}
