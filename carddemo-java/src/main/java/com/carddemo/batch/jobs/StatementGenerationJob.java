package com.carddemo.batch.jobs;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.batch.item.support.builder.CompositeItemProcessorBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import com.carddemo.batch.processors.StatementProcessor;
import com.carddemo.batch.readers.AccountReader;
import com.carddemo.batch.writers.StatementWriter;
import com.carddemo.model.entity.Account;

/**
 * Spring Batch configuration that re-hosts the mainframe JCL job {@code CREASTMT.JCL} together with
 * the COBOL statement-generation main {@code CBSTM03A.CBL} and its file-service subroutine
 * {@code CBSTM03B.CBL} (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; the
 * COBOL/JCL is not copied into the target).
 *
 * <p>The job produces per-account customer statements in <strong>both</strong> output variants the
 * original program emits &mdash; a plain-text statement ({@code STMTFILE}, {@code FD-STMTFILE-REC
 * PIC X(80)}, LRECL=80) and an HTML statement ({@code HTMLFILE}, {@code FD-HTMLFILE-REC PIC X(100)},
 * LRECL=100) &mdash; and persists them to the {@code carddemo-statements} S3 bucket as the objects
 * {@code STATEMNT.PS} and {@code STATEMNT.HTML} (Decision D-003: GDG generations &rarr; S3 versioned
 * objects).</p>
 *
 * <h2>JCL job stream &rarr; Spring Batch mapping</h2>
 * <p>The original {@code CREASTMT.JCL} is a five-step stream, every step gated {@code COND=(0,NE)}
 * (run only if all predecessors ended RC=0). It is reproduced as a two-step Spring Batch {@link Job}
 * wired predecessor-success ({@code start(delete).next(generation)}):</p>
 * <ol>
 *   <li>{@code DELDEF01}/{@code STEP010}/{@code STEP020} (define temp KSDS, {@code SORT
 *       FIELDS=(263,16,CH,A,1,16,CH,A)} by card number then transaction id, {@code IDCAMS REPRO})
 *       &mdash; the card-grouped, transaction-id-ordered working stream is <em>not</em> a separate
 *       Java sort artifact; the ordering is reproduced inside {@link StatementProcessor} when it
 *       aggregates an account's transactions (card number then transaction id), so no temporary
 *       dataset is materialized.</li>
 *   <li>{@code STEP030} ({@code IEFBR14}, {@code DISP=(MOD,DELETE,DELETE)} on {@code STATEMNT.HTML}
 *       and {@code STATEMNT.PS}) &mdash; mirrored by {@link #deletePriorStatementsStep} below, a
 *       {@link Tasklet} that idempotently removes the two prior statement objects from S3 so each
 *       run starts clean.</li>
 *   <li>{@code STEP040} ({@code EXEC PGM=CBSTM03A}) &mdash; the chunk-oriented
 *       {@link #statementGenerationStep} below: {@link AccountReader} (reader) &rarr;
 *       {@link StatementProcessor} + adapter (composite processor) &rarr; {@link StatementWriter}
 *       (writer/stream).</li>
 * </ol>
 *
 * <h2>Template Method &amp; the {@code CALL 'CBSTM03B'} file service (AAP &sect;0.4.3, &sect;0.5.2)</h2>
 * <p>{@code CBSTM03A} drives the run while delegating every keyed/sequential read to
 * {@code CBSTM03B}. That {@code CALL} maps to <strong>bean injection</strong>: {@link StatementProcessor}
 * is the {@code CBSTM03B} equivalent &mdash; it performs the cross-reference, customer, account and
 * transaction reads through the Spring Data JPA repositories it injects, and it renders both
 * statement variants via the Template Method pattern (a shared rendering skeleton with text and HTML
 * variants). This configuration therefore injects the {@link StatementProcessor} bean rather than
 * re-implementing any file I/O.</p>
 *
 * <h2>Reader &rarr; Processor &rarr; Writer pipeline</h2>
 * <p>{@link AccountReader} is the driving cursor (ascending {@code ACCT-ID}, mirroring the VSAM
 * {@code ACCTFILE} KSDS primary-key order); each {@link Account} is the subject of one statement.
 * {@link StatementProcessor} emits a {@link StatementProcessor.StatementResult}; an adapter
 * {@link ItemProcessor} re-shapes that into the writer's {@link StatementWriter.StatementDocument}
 * input. The two delegates are composed with a {@link CompositeItemProcessor} so the step chunk
 * types stay clean: {@code <Account, StatementDocument>}. {@link StatementWriter} is an
 * {@code ItemStreamWriter}, so it is registered via {@code .stream(...)} on the step to receive its
 * {@code open}/{@code update}/{@code close} lifecycle callbacks (it buffers the run and flushes both
 * objects in {@code close}).</p>
 *
 * <h2>Wiring constraints</h2>
 * <ul>
 *   <li>The {@link JobRepository} and {@link PlatformTransactionManager} are the beans
 *       auto-configured by Spring Boot (see {@code com.carddemo.config.BatchConfig}, which
 *       intentionally declares neither and adds no {@code @EnableBatchProcessing}); they are
 *       injected here by type.</li>
 *   <li>This is a {@code @Configuration} only; the job is <strong>not</strong> auto-run
 *       ({@code spring.batch.job.enabled=false}) and is launched by {@code BatchPipelineOrchestrator}
 *       as pipeline stage&nbsp;4a (concurrent with {@code TransactionReportJob}).</li>
 *   <li>The S3 endpoint, credentials, and bucket are resolved from configuration
 *       ({@code spring.cloud.aws.*} / {@code carddemo.aws.s3.bucket-statements}, LocalStack for the
 *       {@code local} and {@code test} profiles) and are <strong>never</strong> hardcoded.</li>
 *   <li>All monetary values flow through {@link java.math.BigDecimal} end-to-end (rendered by
 *       {@link StatementProcessor}); no {@code float}/{@code double} is used for any amount.</li>
 * </ul>
 *
 * <p>The decision rationale for this design (file-service-as-bean, Template Method, GDG&rarr;S3) is
 * recorded in {@code DECISION_LOG.md}, not in code comments.</p>
 */
@Configuration(value = "statementGenerationJobConfig", proxyBeanMethods = false)
public final class StatementGenerationJob {

    /** Logger for statement-job lifecycle and prior-output cleanup diagnostics (Observability, &sect;0.7.1). */
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementGenerationJob.class);

    /** Canonical Spring Batch job name (also the {@code CREASTMT.JCL} job identity). */
    public static final String JOB_NAME = "statementGenerationJob";

    /** Bean name and step name of the prior-output cleanup step ({@code CREASTMT.JCL} STEP030 / IEFBR14). */
    public static final String DELETE_STEP_NAME = "deletePriorStatementsStep";

    /** Bean name and step name of the statement-generation step ({@code CREASTMT.JCL} STEP040 / CBSTM03A). */
    public static final String GENERATION_STEP_NAME = "statementGenerationStep";

    /**
     * Number of accounts processed per chunk commit. The writer accumulates every statement and
     * flushes the two run-level S3 objects only in its {@code close()} callback, so the chunk size
     * governs commit cadence and not output composition; the emitted objects are identical for any
     * positive chunk size.
     */
    static final int CHUNK_SIZE = 10;

    /** S3 object key for the accumulated plain-text statement output (COBOL {@code STMTFILE}). */
    static final String TEXT_OBJECT_KEY = "STATEMNT.PS";

    /** S3 object key for the accumulated HTML statement output (COBOL {@code HTMLFILE}). */
    static final String HTML_OBJECT_KEY = "STATEMNT.HTML";

    /** Synchronous S3 client (see {@code com.carddemo.config.AwsConfig}) used by the cleanup tasklet. */
    private final S3Client s3Client;

    /**
     * Destination bucket for the statement objects, resolved from
     * {@code carddemo.aws.s3.bucket-statements} (default {@code carddemo-statements}); identical to
     * the bucket the {@link StatementWriter} writes to, so the cleanup targets exactly the objects a
     * subsequent generation run will overwrite.
     */
    private final String statementsBucket;

    /**
     * Creates the statement-generation job configuration with its externalized S3 coordinates.
     *
     * @param s3Client         the synchronous S3 client bean (from {@code AwsConfig}); never {@code null}
     * @param statementsBucket the destination S3 bucket name, resolved from the
     *                         {@code carddemo.aws.s3.bucket-statements} property (default
     *                         {@code carddemo-statements})
     */
    public StatementGenerationJob(
            final S3Client s3Client,
            @Value("${carddemo.aws.s3.bucket-statements:carddemo-statements}") final String statementsBucket) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.statementsBucket = statementsBucket;
    }

    /**
     * Defines the statement-generation job: the prior-output cleanup step followed by the
     * generation step, with the generation step running only after the cleanup step completes
     * successfully (predecessor-success ordering, the {@code COND=(0,NE)} gate of &sect;0.8.5).
     *
     * @param jobRepository             the auto-configured Spring Batch job repository
     * @param deletePriorStatementsStep the cleanup step bean (STEP030 / IEFBR14 replacement)
     * @param statementGenerationStep   the generation step bean (STEP040 / CBSTM03A replacement)
     * @return the two-step statement-generation job
     */
    @Bean
    public Job statementGenerationJob(
            final JobRepository jobRepository,
            @Qualifier(DELETE_STEP_NAME) final Step deletePriorStatementsStep,
            @Qualifier(GENERATION_STEP_NAME) final Step statementGenerationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(deletePriorStatementsStep)
                .next(statementGenerationStep)
                .build();
    }

    /**
     * Cleanup step ({@code CREASTMT.JCL} STEP030, {@code IEFBR14} with
     * {@code DISP=(MOD,DELETE,DELETE)}): removes the prior {@link #TEXT_OBJECT_KEY} and
     * {@link #HTML_OBJECT_KEY} objects from the statements bucket so each run starts from a clean
     * slate. Running as the job's first step preserves the predecessor-success ordering of the
     * original stream.
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     * @param transactionManager the auto-configured platform transaction manager
     * @return the configured cleanup step
     */
    @Bean(DELETE_STEP_NAME)
    public Step deletePriorStatementsStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager) {
        return new StepBuilder(DELETE_STEP_NAME, jobRepository)
                .tasklet(buildDeleteTasklet(), transactionManager)
                .build();
    }

    /**
     * Statement-generation step ({@code CREASTMT.JCL} STEP040, {@code EXEC PGM=CBSTM03A}): a
     * chunk-oriented {@code <Account, StatementDocument>} step that reads accounts, renders each into
     * both statement variants through the composite processor, and writes the accumulated text and
     * HTML objects to S3. The {@link StatementWriter} is registered both as the writer and, because it
     * is an {@code ItemStreamWriter}, via {@code .stream(...)} so its open/flush/close lifecycle runs.
     *
     * @param jobRepository                the auto-configured Spring Batch job repository
     * @param transactionManager           the auto-configured platform transaction manager
     * @param accountReader                the account-driving reader (ascending {@code acctId})
     * @param statementCompositeProcessor  the composite processor ({@link StatementProcessor} + adapter)
     * @param statementWriter              the S3 statement writer (also registered as a stream)
     * @return the configured statement-generation step
     */
    @Bean(GENERATION_STEP_NAME)
    public Step statementGenerationStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final AccountReader accountReader,
            final CompositeItemProcessor<Account, StatementWriter.StatementDocument> statementCompositeProcessor,
            final StatementWriter statementWriter) {
        return new StepBuilder(GENERATION_STEP_NAME, jobRepository)
                .<Account, StatementWriter.StatementDocument>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountReader)
                .processor(statementCompositeProcessor)
                .writer(statementWriter)
                .stream(statementWriter)
                .build();
    }

    /**
     * Composite processor bridging the generation step's chunk types. The first delegate,
     * {@link StatementProcessor}, maps an {@link Account} to a
     * {@link StatementProcessor.StatementResult} (resolving the customer/card linkage and rendering
     * both variants &mdash; the {@code CBSTM03B} file service plus the Template Method renderers);
     * the second delegate is a thin adapter that re-shapes that result into the writer's
     * {@link StatementWriter.StatementDocument} input. Exposed as a bean so Spring runs its
     * {@code InitializingBean} validation and injects it into {@link #statementGenerationStep}.
     *
     * @param statementProcessor the locked statement-rendering processor delegate; never {@code null}
     * @return the composed {@code Account -> StatementDocument} processor
     */
    @Bean
    public CompositeItemProcessor<Account, StatementWriter.StatementDocument> statementCompositeProcessor(
            final StatementProcessor statementProcessor) {
        Objects.requireNonNull(statementProcessor, "statementProcessor must not be null");
        final ItemProcessor<StatementProcessor.StatementResult, StatementWriter.StatementDocument> documentAdapter =
                result -> new StatementWriter.StatementDocument(result.textStatement(), result.htmlStatement());
        return new CompositeItemProcessorBuilder<Account, StatementWriter.StatementDocument>()
                .delegates(statementProcessor, documentAdapter)
                .build();
    }

    /**
     * Builds the cleanup tasklet as a plain factory method (not a bean) so it is safe under
     * {@code proxyBeanMethods = false}: the returned {@link Tasklet} is a fresh, un-proxied lambda
     * consumed once by {@link #deletePriorStatementsStep}. It deletes both prior statement objects
     * and reports {@link RepeatStatus#FINISHED}.
     *
     * @return the cleanup tasklet implementing the IEFBR14 prior-output delete
     */
    private Tasklet buildDeleteTasklet() {
        return (contribution, chunkContext) -> {
            deletePriorStatement(TEXT_OBJECT_KEY);
            deletePriorStatement(HTML_OBJECT_KEY);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Deletes a single prior statement object from the configured statements bucket. S3 object
     * deletion is idempotent &mdash; deleting an absent key succeeds &mdash; which faithfully mirrors
     * the {@code IEFBR14}/{@code DISP=(MOD,DELETE,DELETE)} behaviour that always clears the prior
     * artifact whether or not it existed. A genuine S3 access failure aborts the step (mapped to an
     * {@link IllegalStateException}), so the predecessor-success gate prevents generation from
     * proceeding over a bucket that could not be cleaned.
     *
     * @param key the S3 object key to delete
     * @throws IllegalStateException if the S3 {@code deleteObject} call fails
     */
    private void deletePriorStatement(final String key) {
        try {
            this.s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(this.statementsBucket)
                    .key(key)
                    .build());
            LOGGER.info("Cleared prior statement object s3://{}/{}", this.statementsBucket, key);
        } catch (final SdkException sdkFailure) {
            throw new IllegalStateException(
                    "Failed to delete prior statement object s3://" + this.statementsBucket + "/" + key,
                    sdkFailure);
        }
    }
}
