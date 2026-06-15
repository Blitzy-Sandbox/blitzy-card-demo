package com.cardemo.batch.jobs;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.config.AwsConfig;
import com.cardemo.model.dto.AccountStatement;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;
import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.s3.S3Template;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@code @Configuration} that assembles <strong>Stage&nbsp;4a</strong> of the greenfield
 * Java&nbsp;25 LTS + Spring Boot&nbsp;3.5.x migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS
 * mainframe application: the <strong>account-statement generation</strong> job.
 *
 * <h2>Provenance &mdash; what this job translates</h2>
 * <p>This is the faithful Java translation of the JCL job {@code app/jcl/CREASTMT.JCL}, which executes
 * the COBOL program {@code app/cbl/CBSTM03A.CBL} (the Statement Generator). {@code CBSTM03A} performs
 * <em>all</em> of its file I/O by {@code CALL 'CBSTM03B'} &mdash; the generic
 * OPEN/READ/READ-K/WRITE/CLOSE file-service subroutine {@code app/cbl/CBSTM03B.CBL}. Traceability is to
 * the frozen COBOL baseline at commit SHA {@code 27d6c6f} only; the COBOL/JCL sources are
 * <strong>read-only</strong> reference material and are <strong>never copied</strong> into this
 * repository (AAP &sect;0.7.2). The application base package is {@code com.cardemo} (decision
 * <strong>D-006</strong> &mdash; deliberately <em>not</em> {@code com.carddemo}).</p>
 *
 * <h2>CRITICAL &mdash; {@code CBSTM03B} is an injected file-service bean, NOT a job file</h2>
 * <p>Per AAP &sect;0.4.2 the COBOL {@code CALL 'CBSTM03B'} maps to an {@code @Autowired} file-service
 * bean that is used <em>inside</em> the sibling {@link StatementProcessor} / {@link StatementWriter};
 * {@code CBSTM03B} is therefore owned by the processor/writer (and the shared/batch layer), and is
 * <strong>not</strong> a separate batch-job class. Consequently this {@code @Configuration} contains
 * <strong>no</strong> statement-formatting logic and <strong>no</strong> physical file/S3 write logic:
 * it only composes the reader, the sibling processor, and the sibling writer into steps and a job. All
 * statement layout ({@code 5000-CREATE-STATEMENT} / {@code 5100}/{@code 5200}/{@code 6000-WRITE-TRANS})
 * lives in {@link StatementProcessor}; all emission to S3 lives in {@link StatementWriter}.</p>
 *
 * <h2>Source contract &mdash; {@code CREASTMT.JCL} (5 steps; preserve behaviour exactly)</h2>
 * <p>The legacy job runs five steps; the last three are gated with {@code COND=(0,NE)} (a step runs
 * <em>only</em> if every prior step ended {@code RC=0}). Each is reproduced or documented as a
 * technology substitution at its point of change (Minimal Change Clause, AAP &sect;0.7.1):</p>
 * <ol>
 *   <li><strong>{@code DELDEF01 EXEC PGM=IDCAMS}</strong> (no {@code COND}) &mdash; {@code DELETE} +
 *       {@code DEFINE} the work cluster {@code TRXFL.VSAM.KSDS} ({@code KEYS(32 0)},
 *       {@code RECORDSIZE(350 350)}, {@code INDEXED}); {@code SET MAXCC=0}. <em>Substitution:</em> the
 *       {@code TRXFL} work cluster existed only to hold a per-card-ordered transaction work file for
 *       {@code CBSTM03A} to read sequentially; in the relational target the per-card transaction set is
 *       obtained directly by {@link StatementProcessor} via
 *       {@code TransactionRepository.findByTranCardNum(cardNumber)}, so the work cluster is
 *       <strong>subsumed</strong> by that query and is not provisioned. Its cleanup <em>intent</em> is
 *       folded into {@link #prepareStatementsTasklet()}.</li>
 *   <li><strong>{@code STEP010 EXEC PGM=SORT}</strong> (no {@code COND}) &mdash;
 *       {@code SORT FIELDS=(263,16,CH,A, 1,16,CH,A)} (card number at offset&nbsp;263 ascending, then
 *       transaction id at offset&nbsp;1 ascending) writing {@code TRXFL.SEQ}. <em>Substitution:</em>
 *       this DFSORT pass produced the per-card transaction ordering for the work file; it is
 *       <strong>subsumed</strong> by the same per-card {@code findByTranCardNum} query in
 *       {@link StatementProcessor}, so no Java {@code Comparator}/sort step is needed here.</li>
 *   <li><strong>{@code STEP020 EXEC PGM=IDCAMS, COND=(0,NE)}</strong> &mdash;
 *       {@code REPRO TRXFL.SEQ -> TRXFL.VSAM.KSDS}. <em>Substitution:</em> subsumed with
 *       {@code STEP010}/{@code DELDEF01} (no work-file reload is required).</li>
 *   <li><strong>{@code STEP030 EXEC PGM=IEFBR14, COND=(0,NE)}</strong> &mdash; delete the prior run's
 *       {@code STATEMNT.HTML} and {@code STATEMNT.PS} outputs. <em>Substitution:</em> reproduced by
 *       {@link #prepareStatementsTasklet()}, which deletes the previous run's statement objects from
 *       the S3 statements bucket (idempotent).</li>
 *   <li><strong>{@code STEP040 EXEC PGM=CBSTM03A, COND=(0,NE)}</strong> &mdash; inputs
 *       {@code TRNXFILE}/{@code XREFFILE}/{@code ACCTFILE}/{@code CUSTFILE}; outputs {@code STMTFILE}
 *       ({@code STATEMNT.PS}, {@code LRECL 80}, text) and {@code HTMLFILE} ({@code STATEMNT.HTML},
 *       {@code LRECL 100}). <em>Substitution:</em> reproduced by {@link #generateStatementsStep()} (the
 *       XREF-driven chunk: read cross-references &rarr; {@link StatementProcessor} assembles the dual
 *       bodies &rarr; {@link StatementWriter} emits them); the {@code STMTFILE}/{@code HTMLFILE}
 *       sequential PS datasets (GDG generations) are redirected to the S3 bucket
 *       {@code carddemo-statements} by the writer (decision <strong>D-003</strong>).</li>
 * </ol>
 *
 * <h2>{@code COND=(0,NE)} &rarr; sequential {@code .next(...)} (the single multi-step stage)</h2>
 * <p>Stage&nbsp;4a is the only multi-step stage in the pipeline. The two surviving steps &mdash; the
 * prepare/cleanup tasklet and the statement-generation chunk &mdash; are wired with the Spring Batch
 * <em>default</em> transition {@code .start(prepareStatementsStep).next(generateStatementsStep)}. By
 * default a Spring Batch step transitions to the next step <strong>only</strong> when the prior step's
 * {@link org.springframework.batch.core.ExitStatus} is {@code COMPLETED}; if the prior step is
 * {@code FAILED} the job stops. That is exactly the {@code COND=(0,NE)} "run only if all prior steps
 * ended RC=0" semantic, so <strong>no</strong> custom {@code JobExecutionDecider} is required within
 * this job (AAP &sect;0.7.6). The cross-stage condition-code routing of the wider 5-stage pipeline is an
 * orchestration concern owned by {@code BatchPipelineOrchestrator}, not by this job.</p>
 *
 * <h2>Spring Batch 5.x builder API (AAP &sect;0.7.8)</h2>
 * <p>The job and steps are assembled with the Spring&nbsp;Batch&nbsp;5.x {@link JobBuilder} /
 * {@link StepBuilder} taking an explicit {@link JobRepository} and {@link PlatformTransactionManager}.
 * The removed {@code StepBuilderFactory}/{@code JobBuilderFactory} are <strong>not</strong> used, and
 * {@code @EnableBatchProcessing} is <strong>not</strong> declared anywhere (it would disable Boot's
 * batch auto-configuration in Boot&nbsp;3.x); the {@code JobRepository} and transaction manager are the
 * auto-configured beans documented by {@code com.cardemo.config.BatchConfig}.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>This configuration holds only immutable, thread-safe Spring collaborators (the sibling processor
 * and writer, the cross-reference repository, the AWS resource-name holder, and the
 * {@link S3Template}); all bean-factory methods create fresh builders per invocation. The reader is
 * {@code @StepScope} so a fresh, correctly-positioned reader is created per step execution.</p>
 *
 * @see StatementProcessor
 * @see StatementWriter
 * @see AccountStatement
 * @see CardCrossReferenceRepository
 * @see Configuration
 */
// Explicit configuration-bean name to avoid a BeanDefinitionOverrideException. The default component
// name for this class is its decapitalized simple name, "statementGenerationJob", which would collide
// with the Job @Bean method of the same name below (Spring Boot disables bean-definition overriding by
// default). Naming the @Configuration bean distinctly lets the Job bean keep the natural name
// "statementGenerationJob" (the name the pipeline orchestrator / SQS-triggered launcher resolves).
@Configuration("statementGenerationJobConfiguration")
public class StatementGenerationJob {

    /**
     * Logger for the prepare/cleanup tasklet (reports how many prior-run statement objects were
     * removed). Mirrors the structured-logging convention used by the sibling batch jobs
     * (observability cross-cutting requirement, AAP &sect;0.7.7).
     */
    private static final Logger log = LoggerFactory.getLogger(StatementGenerationJob.class);

    /**
     * Chunk size for the statement-generation step. This is a <strong>tuning</strong> value only and
     * does <strong>not</strong> affect parity: each input item (one card cross-reference) yields exactly
     * one self-contained {@link AccountStatement}, so chunk boundaries never split or merge a statement.
     * A modest size is used because each item is comparatively heavy (it assembles a full text and HTML
     * statement body and is uploaded as two S3 objects by the writer).
     */
    private static final int CHUNK_SIZE = 50;

    /**
     * S3 key prefix under which generated statement objects live. This intentionally matches the
     * {@code StatementWriter}'s own {@code statements/} key prefix so the prepare tasklet's
     * delete-by-prefix cleanup targets exactly the objects a prior run wrote (reproducing
     * {@code STEP030}'s deletion of {@code STATEMNT.HTML}/{@code STATEMNT.PS}). The legacy sequential PS
     * datasets / GDG generations are realized as S3 objects under this prefix (decision
     * <strong>D-003</strong>).
     */
    private static final String S3_STATEMENTS_KEY_PREFIX = "statements/";

    /**
     * Per-card statement-assembly processor &mdash; the sibling component that reproduces
     * {@code CBSTM03A}'s {@code 1000-MAINLINE} body (gather the card's transactions via
     * {@code TransactionRepository.findByTranCardNum}, build the text and HTML bodies, accumulate the
     * {@code WS-TOTAL-AMT} running total). Injected here so it can be wired into
     * {@link #generateStatementsStep()}. It owns the {@code CBSTM03B}-equivalent file-service bean.
     */
    private final StatementProcessor statementProcessor;

    /**
     * Dual-format statement writer &mdash; the sibling component that emits the per-account text
     * ({@code .txt}, {@code text/plain}) and HTML ({@code .html}, {@code text/html}) renderings to the
     * S3 {@code carddemo-statements} bucket (the {@code STMTFILE}/{@code HTMLFILE} sequential-dataset
     * substitution). Injected here so it can be wired into {@link #generateStatementsStep()}.
     */
    private final StatementWriter statementWriter;

    /**
     * Driver repository for the {@code CARDXREF} cross-reference dataset. The XREF is the statement
     * driver &mdash; "one statement per card present in the XREF file" &mdash; so its inherited
     * {@code findAll(Pageable)} browse backs {@link #statementXrefReader()}, reproducing
     * {@code CBSTM03A}'s {@code 1000-XREFFILE-GET-NEXT} sequential walk of the cross-reference file.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Strongly-typed holder of the application-owned AWS resource <em>names</em>. The statements bucket
     * is read from {@code getS3().getStatementsBucket()} ({@code carddemo-statements}); the bucket name
     * is therefore resolved from configuration and never hardcoded (AAP &sect;0.7.7). Used by the
     * prepare tasklet to locate the bucket whose prior-run objects must be cleaned.
     */
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Spring Cloud AWS S3 abstraction used by the prepare tasklet to delete the prior run's statement
     * objects ({@code STEP030} cleanup). Auto-configured by {@code spring-cloud-aws-starter-s3} from
     * {@code spring.cloud.aws.*}; never hand-built here, so the endpoint can only ever resolve to
     * LocalStack (zero live AWS). The statement <em>writes</em> are the {@link StatementWriter}'s
     * responsibility; this template is used here solely for the idempotent pre-run cleanup.
     */
    private final S3Template s3Template;

    /**
     * Spring-injected constructor wiring the sibling processor and writer, the cross-reference driver
     * repository, the AWS resource-name holder, and the S3 template used for the prepare-step cleanup.
     *
     * @param statementProcessor           the per-card statement-assembly processor; must not be
     *                                     {@code null}
     * @param statementWriter              the dual-format S3 statement writer; must not be {@code null}
     * @param cardCrossReferenceRepository the {@code CARDXREF} driver repository; must not be
     *                                     {@code null}
     * @param awsResourceProperties        the AWS resource-name holder (statements-bucket source); must
     *                                     not be {@code null}
     * @param s3Template                   the auto-configured S3 template used for the prepare-step
     *                                     cleanup; must not be {@code null}
     */
    @Autowired
    public StatementGenerationJob(
            final StatementProcessor statementProcessor,
            final StatementWriter statementWriter,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final AwsConfig.AwsResourceProperties awsResourceProperties,
            final S3Template s3Template) {
        this.statementProcessor = statementProcessor;
        this.statementWriter = statementWriter;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.awsResourceProperties = awsResourceProperties;
        this.s3Template = s3Template;
    }

    /**
     * Prepare/cleanup tasklet &mdash; reproduces {@code CREASTMT.JCL}'s {@code STEP030}
     * ({@code EXEC PGM=IEFBR14} deleting the prior run's {@code STATEMNT.HTML} and {@code STATEMNT.PS}
     * outputs) and folds in the cleanup <em>intent</em> of {@code DELDEF01} (the
     * {@code DELETE}-before-{@code DEFINE} of the work file). It deletes every statement object the
     * previous run wrote under the {@code statements/} prefix of the configured statements bucket so
     * each run starts from a clean slate, exactly as the mainframe deleted the prior PS/HTML datasets
     * before regenerating them.
     *
     * <h3>Technology substitution (documented at the point of change)</h3>
     * <ul>
     *   <li><strong>{@code STEP030} delete of {@code STATEMNT.PS}/{@code STATEMNT.HTML}</strong> &rarr;
     *       delete-by-prefix of the S3 objects the {@link StatementWriter} emits (same
     *       {@code statements/} key prefix). The bucket name is resolved from configuration
     *       ({@code awsResourceProperties.getS3().getStatementsBucket()} = {@code carddemo-statements})
     *       and is never hardcoded (AAP &sect;0.7.7).</li>
     *   <li><strong>{@code DELDEF01} {@code DELETE}/{@code DEFINE} of the {@code TRXFL} work
     *       cluster</strong> &rarr; <em>not</em> recreated: the {@code TRXFL} per-card transaction work
     *       file is <strong>subsumed</strong> by {@link StatementProcessor}'s per-card
     *       {@code TransactionRepository.findByTranCardNum(...)} query, so only the cleanup intent (a
     *       clean output target) survives, satisfied by the object deletion above.</li>
     * </ul>
     *
     * <h3>Idempotence and at-least-once safety</h3>
     * <p>The tasklet is idempotent: if the bucket does not yet exist (for example a fresh environment
     * before any statements have been written) it logs and returns without error rather than failing;
     * if the bucket exists with no prior objects, the list is empty and nothing is deleted. This makes
     * a Spring Batch restart of the prepare step safe. The bucket-existence probe is a cheap head
     * check; absence is treated as "nothing to clean", mirroring {@code DELDEF01}'s {@code SET MAXCC=0}
     * which forced a not-found {@code DELETE} to be a non-fatal condition.</p>
     *
     * <h3>Error handling (COBOL parity)</h3>
     * <p>A delete that fails for a reason other than absence (an S3 error) is allowed to propagate so
     * the step &mdash; and therefore the job &mdash; fails before any statements are regenerated,
     * mirroring the {@code COND=(0,NE)} gate that prevented {@code STEP040} from running unless the
     * preparatory steps succeeded.</p>
     *
     * @return a {@link Tasklet} that clears the prior run's statement objects and signals
     *         {@link RepeatStatus#FINISHED}
     */
    @Bean
    public Tasklet prepareStatementsTasklet() {
        // The Tasklet SAM is (StepContribution, ChunkContext) -> RepeatStatus; neither argument is
        // needed for this single-shot cleanup, so they are intentionally unused. "throws Exception" on
        // the SAM lets any S3 error propagate and fail the step (COBOL ABEND / COND-gate parity).
        return (contribution, chunkContext) -> {
            // Bucket name resolved from configuration (never hardcoded) -- AAP S0.7.7.
            final String bucket = awsResourceProperties.getS3().getStatementsBucket();

            // Idempotent guard: a missing bucket means there is nothing from a prior run to clean. This
            // mirrors DELDEF01's "SET MAXCC=0" tolerance of a not-found DELETE; the bucket is
            // provisioned by localstack-init/init-aws.sh (local) or by the integration tests.
            if (!s3Template.bucketExists(bucket)) {
                log.info("Statements bucket '{}' does not exist yet; no prior-run statement objects to "
                        + "delete (CREASTMT STEP030/DELDEF01 cleanup is a no-op).", bucket);
                return RepeatStatus.FINISHED;
            }

            // STEP030: delete the prior run's STATEMNT.PS / STATEMNT.HTML outputs -> delete every S3
            // object the StatementWriter wrote under the shared "statements/" prefix.
            final List<S3Resource> priorStatements = s3Template.listObjects(bucket, S3_STATEMENTS_KEY_PREFIX);
            int deleted = 0;
            for (final S3Resource priorStatement : priorStatements) {
                // getLocation().getObject() yields the full object key (the prefix-relative path is part
                // of it), which is what deleteObject(bucket, key) requires.
                s3Template.deleteObject(bucket, priorStatement.getLocation().getObject());
                deleted++;
            }
            log.info("Deleted {} prior-run statement object(s) from s3://{}/{} before regeneration "
                    + "(CREASTMT STEP030 cleanup).", deleted, bucket, S3_STATEMENTS_KEY_PREFIX);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The first step of the job &mdash; the prepare/cleanup step, wrapping {@link #prepareStatementsTasklet()}
     * in a single-shot {@code Tasklet} step (reproducing {@code CREASTMT.JCL}'s {@code STEP030} and the
     * cleanup intent of {@code DELDEF01}).
     *
     * <p>Assembled with the Spring&nbsp;Batch&nbsp;5.x {@link StepBuilder} taking an explicit
     * {@link JobRepository} and {@link PlatformTransactionManager} (no {@code StepBuilderFactory}). The
     * transaction manager bounds the tasklet's (metadata) transaction; the S3 cleanup itself is not
     * transactional but is idempotent, so a retry re-runs the cleanup harmlessly.</p>
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     * @param transactionManager the auto-configured transaction manager bounding the tasklet transaction
     * @return the configured prepare/cleanup step
     */
    @Bean
    public Step prepareStatementsStep(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager) {
        return new StepBuilder("prepareStatementsStep", jobRepository)
                .tasklet(prepareStatementsTasklet(), transactionManager)
                .build();
    }

    /**
     * Inline driver reader for the {@code CARDXREF} cross-reference dataset, reproducing
     * {@code CBSTM03A}'s sequential cross-reference browse ({@code 1000-XREFFILE-GET-NEXT}) &mdash; the
     * loop that drives "one statement per CARD present in the XREF file".
     *
     * <p>A {@link RepositoryItemReader} pages the entire {@code card_xref} table through the inherited
     * {@code findAll(Pageable)} of {@link CardCrossReferenceRepository}. Rows are ordered ascending by
     * the cross-reference card number ({@link CardCrossReference#getXrefCardNum() xrefCardNum}, the
     * {@code @Id} mapped from {@code XREF-CARD-NUM}), matching {@code CBSTM03A} iterating the cards in
     * cross-reference key order. The per-card transaction set each statement needs is fetched
     * downstream by {@link StatementProcessor} (the {@code findByTranCardNum} query that subsumes the
     * {@code STEP010} SORT and the {@code TRXFL} work file), so this reader only needs to supply the
     * cross-references in a stable order.</p>
     *
     * <p>The reader is {@code @StepScope} so a fresh, correctly-positioned reader is created for each
     * step execution; the page size is {@link #CHUNK_SIZE}. Paging the driver does not affect parity
     * because each cross-reference produces an independent, self-contained statement.</p>
     *
     * @return a step-scoped, card-number-ordered repository reader over the cross-reference driver
     *         dataset
     */
    @Bean
    @StepScope
    public RepositoryItemReader<CardCrossReference> statementXrefReader() {
        final RepositoryItemReader<CardCrossReference> reader = new RepositoryItemReader<>();
        reader.setRepository(cardCrossReferenceRepository);
        // Inherited JpaRepository.findAll(Pageable) -- the sequential CARDXREF browse
        // (CBSTM03A 1000-XREFFILE-GET-NEXT). "one statement per CARD present in the XREF file".
        reader.setMethodName("findAll");
        reader.setPageSize(CHUNK_SIZE);
        // Order ascending by the cross-reference card number (XREF-CARD-NUM -> CardCrossReference.xrefCardNum)
        // so cards are visited in cross-reference key order, matching the COBOL XREF walk.
        reader.setSort(Map.of("xrefCardNum", Sort.Direction.ASC));
        reader.setName("statementXrefReader");
        return reader;
    }

    /**
     * The second step of the job &mdash; the statement-generation chunk, reproducing
     * {@code CREASTMT.JCL}'s {@code STEP040} ({@code EXEC PGM=CBSTM03A}). For each card cross-reference
     * supplied by {@link #statementXrefReader()}, {@link StatementProcessor} assembles the dual-format
     * statement (the {@code CBSTM03A} {@code 1000-MAINLINE} body) and {@link StatementWriter} emits the
     * text and HTML renderings to the S3 {@code carddemo-statements} bucket.
     *
     * <p>Chunk generics are {@code <CardCrossReference, AccountStatement>}: the reader yields
     * {@link CardCrossReference}, the processor maps each to an {@link AccountStatement}, and the writer
     * consumes {@link AccountStatement}. Assembled with the Spring&nbsp;Batch&nbsp;5.x
     * {@link StepBuilder} taking an explicit {@link JobRepository} and {@link PlatformTransactionManager}
     * (no {@code StepBuilderFactory}); the transaction manager bounds each chunk's transaction.</p>
     *
     * <p>The {@link StatementWriter} is a plain {@link org.springframework.batch.item.ItemWriter} (it is
     * <strong>not</strong> an {@link org.springframework.batch.item.ItemStream}), so it requires no
     * {@code .stream(...)} registration; its S3 uploads need no open/close lifecycle handshake.</p>
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     * @param transactionManager the auto-configured transaction manager bounding each chunk transaction
     * @return the configured statement-generation step
     */
    @Bean
    public Step generateStatementsStep(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager) {
        return new StepBuilder("generateStatementsStep", jobRepository)
                .<CardCrossReference, AccountStatement>chunk(CHUNK_SIZE, transactionManager)
                .reader(statementXrefReader())
                .processor(statementProcessor)
                .writer(statementWriter)
                .build();
    }

    /**
     * Assembles the Stage&nbsp;4a job from its two steps, mirroring {@code CREASTMT.JCL}.
     *
     * <p>The Spring&nbsp;Batch&nbsp;5.x {@link JobBuilder} (with an explicit {@link JobRepository}) wires
     * {@code .start(prepareStatementsStep).next(generateStatementsStep)}. This default sequential
     * transition reproduces the JCL {@code COND=(0,NE)} gate exactly: Spring Batch advances from the
     * prepare step to the generate step <strong>only</strong> when the prepare step's
     * {@link org.springframework.batch.core.ExitStatus} is {@code COMPLETED}; if the prepare step is
     * {@code FAILED}, the job stops and statement generation never runs &mdash; the precise "run only if
     * all prior steps ended RC=0" semantic. No custom {@code JobExecutionDecider} is needed within this
     * job (AAP &sect;0.7.6).</p>
     *
     * <p><strong>Launch policy.</strong> Like every job in this pipeline, this job does not auto-run on
     * startup ({@code spring.batch.job.enabled=false}); it is launched on demand by the
     * {@code BatchPipelineOrchestrator} (Stage&nbsp;4a of {@code POSTTRAN -> INTCALC -> COMBTRAN ->
     * CREASTMT/TRANREPT}) through the auto-configured {@code JobLauncher}.</p>
     *
     * @param jobRepository          the auto-configured Spring Batch job repository
     * @param prepareStatementsStep  the prepare/cleanup step (run first)
     * @param generateStatementsStep the statement-generation chunk step (run only if prepare COMPLETED)
     * @return the configured statement-generation job
     */
    @Bean
    public Job statementGenerationJob(final JobRepository jobRepository,
            final Step prepareStatementsStep,
            final Step generateStatementsStep) {
        return new JobBuilder("statementGenerationJob", jobRepository)
                .start(prepareStatementsStep)
                .next(generateStatementsStep)
                .build();
    }
}
