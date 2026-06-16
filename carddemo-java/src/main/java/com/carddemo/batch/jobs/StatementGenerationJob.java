package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.StatementProcessor;
import com.carddemo.batch.readers.AccountReader;
import com.carddemo.batch.writers.StatementWriter;
import com.carddemo.model.entity.Account;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * Spring Batch re-host of the mainframe statement-creation job {@code app/jcl/CREASTMT.JCL} and its
 * COBOL programs {@code app/cbl/CBSTM03A.CBL} (statement-generation main) and
 * {@code app/cbl/CBSTM03B.CBL} (the file-access subroutine) &mdash; source commit {@code 27d6c6f};
 * REFERENCE ONLY, no COBOL/JCL is copied.
 *
 * <p>The original JCL ran a five-step stream, every step gated {@code COND=(0,NE)} (run only when
 * every predecessor ended RC=0):</p>
 * <ol>
 *   <li>{@code DELDEF01} (IDCAMS) defined the temporary card+tranid working KSDS
 *       ({@code TRXFL.VSAM.KSDS}, {@code KEYS(32 0)}, {@code RECORDSIZE 350}).</li>
 *   <li>{@code STEP010} (SORT) re-keyed the transaction backup by card number then transaction id
 *       ({@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}), producing a card-grouped, tranid-ordered
 *       stream.</li>
 *   <li>{@code STEP020} (IDCAMS REPRO) loaded the sorted stream into the working KSDS.</li>
 *   <li>{@code STEP030} (IEFBR14) deleted the prior-run outputs {@code STATEMNT.PS} and
 *       {@code STATEMNT.HTML}.</li>
 *   <li>{@code STEP040} ({@code PGM=CBSTM03A}) read the card+tranid transactions plus the
 *       cross-reference, account, and customer files and produced the text statement
 *       ({@code STMTFILE}, {@code LRECL=80}) and the HTML statement ({@code HTMLFILE},
 *       {@code LRECL=100}).</li>
 * </ol>
 *
 * <p><b>Target design.</b> The job exposes two steps in predecessor-success order (AAP &sect;0.8.5):
 * {@link #deletePriorStatementsStep} reproduces {@code STEP030} by clearing the prior
 * {@code STATEMNT.PS}/{@code STATEMNT.HTML} objects from the statements bucket, and
 * {@link #statementGenerationStep} reproduces {@code STEP040} as a chunk-oriented
 * reader &rarr; processor &rarr; writer pipeline. The {@code DELDEF01}/{@code STEP010}/{@code STEP020}
 * sort-and-stage to a working KSDS is not materialised as a separate artifact; the card+tranid
 * ordering ({@code FIELDS=(263,16,CH,A,1,16,CH,A)}) is reproduced inside the data-gathering of
 * {@link StatementProcessor} when it reads an account's transactions.</p>
 *
 * <p><b>Template Method (AAP &sect;0.4.3).</b> {@link StatementProcessor} renders both the 80-column
 * text variant and the 100-column HTML variant from one shared statement model, and
 * {@link StatementWriter} persists both to S3 ({@code STATEMNT.PS} and {@code STATEMNT.HTML}). The
 * processor emits {@link StatementProcessor.StatementResult} while the writer consumes
 * {@link StatementWriter.StatementDocument}; a {@link CompositeItemProcessor} chains the processor
 * with a thin adapter so the step's chunk type is the clean {@code <Account, StatementDocument>}.</p>
 *
 * <p><b>File service &rarr; repositories (AAP &sect;0.5.2).</b> The COBOL {@code CALL 'CBSTM03B'}
 * keyed reads are realised by the Spring Data JPA repositories injected into
 * {@link StatementProcessor}; this configuration therefore wires the processor rather than
 * re-implementing keyed file I/O.</p>
 *
 * <p><b>Cloud and execution (AAP &sect;0.8).</b> GDG/dataset outputs become S3 objects (decision
 * D-003) exercised against LocalStack only; the bucket resolves from configuration and is never
 * hardcoded. Jobs are not auto-run ({@code spring.batch.job.enabled=false}); the pipeline
 * orchestrator launches this job as stage 4a (concurrent with the transaction-report job).
 * Monetary values keep {@link java.math.BigDecimal} precision end to end (no floating point) in the
 * downstream processor and writer. Decision rationale is recorded in {@code DECISION_LOG.md}.</p>
 */
@Configuration(value = "statementGenerationJobConfig", proxyBeanMethods = false)
public class StatementGenerationJob {

    /** Canonical job name; referenced by the pipeline orchestrator to launch this job. */
    public static final String JOB_NAME = "statementGenerationJob";

    /** Name of step 1 &mdash; the {@code STEP030} (IEFBR14) replacement that clears prior outputs. */
    public static final String DELETE_PRIOR_STEP_NAME = "deletePriorStatementsStep";

    /** Name of step 2 &mdash; the {@code STEP040} ({@code PGM=CBSTM03A}) statement-generation step. */
    public static final String STATEMENT_GENERATION_STEP_NAME = "statementGenerationStep";

    /**
     * Number of accounts processed per chunk (per step-transaction commit of batch metadata). The
     * writer accumulates every statement and flushes the two run-level S3 objects once on close, so
     * this value governs commit cadence rather than the produced output.
     */
    public static final int CHUNK_SIZE = 10;

    /** Prior-run text statement object key cleared by the delete step (COBOL {@code STATEMNT.PS}). */
    private static final String STATEMENT_TEXT_KEY = "STATEMNT.PS";

    /** Prior-run HTML statement object key cleared by the delete step (COBOL {@code STATEMNT.HTML}). */
    private static final String STATEMENT_HTML_KEY = "STATEMNT.HTML";

    private static final Logger LOGGER = LoggerFactory.getLogger(StatementGenerationJob.class);

    /** Synchronous S3 client used to clear the prior-run statement objects. */
    private final S3Client s3Client;

    /** Target bucket for the text and HTML statement objects (also cleared before each run). */
    private final String statementsBucket;

    /**
     * Creates the statement-generation job configuration. The S3 endpoint, region, and credentials
     * are resolved by {@link com.carddemo.config.AwsConfig} from configuration (LocalStack for the
     * local/test profiles), never here; this constructor only binds the bucket name so it stays
     * externalized.
     *
     * @param s3Client         the synchronous S3 client bean (provided by {@code AwsConfig})
     * @param statementsBucket the destination/clear bucket, from
     *                         {@code carddemo.aws.s3.bucket-statements}
     *                         (default {@code carddemo-statements})
     */
    public StatementGenerationJob(
            S3Client s3Client,
            @Value("${carddemo.aws.s3.bucket-statements:carddemo-statements}") String statementsBucket) {
        this.s3Client = s3Client;
        this.statementsBucket = statementsBucket;
    }

    /**
     * Step 1 &mdash; the {@code STEP030} (IEFBR14) replacement. A single-shot tasklet that removes
     * the prior-run {@code STATEMNT.PS} and {@code STATEMNT.HTML} objects from the statements bucket
     * so each run starts clean. Deleting an absent object is a no-op in S3, so the step is safe on
     * the first run. It runs inside a step transaction managed by the supplied transaction manager
     * (no database writes occur).
     *
     * @param jobRepository      the Spring Batch job repository (Boot-auto-configured)
     * @param transactionManager the platform transaction manager (Boot-auto-configured)
     * @return the configured delete step
     */
    @Bean
    public Step deletePriorStatementsStep(
            JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder(DELETE_PRIOR_STEP_NAME, jobRepository)
                .tasklet(this::clearPriorStatements, transactionManager)
                .build();
    }

    /**
     * The {@code StatementResult -> StatementDocument} processor chain. A
     * {@link CompositeItemProcessor} runs {@link StatementProcessor} (which renders both statement
     * variants for an {@link Account}) and then a thin adapter that maps the rendered
     * {@link StatementProcessor.StatementResult} to the writer's
     * {@link StatementWriter.StatementDocument} contract. Returning the concrete composite type
     * keeps the bean injection unambiguous; the composite implements {@code InitializingBean}, so
     * Spring validates its delegates after construction. When the processor filters an account
     * (returns {@code null}), the composite short-circuits and the adapter is not invoked.
     *
     * @param statementProcessor the per-account statement renderer (text + HTML)
     * @return the composite processor producing {@link StatementWriter.StatementDocument} items
     */
    @Bean
    public CompositeItemProcessor<Account, StatementWriter.StatementDocument> statementCompositeProcessor(
            StatementProcessor statementProcessor) {
        List<ItemProcessor<?, ?>> delegates = new ArrayList<>();
        delegates.add(statementProcessor);
        delegates.add(statementResultToDocument());
        CompositeItemProcessor<Account, StatementWriter.StatementDocument> composite =
                new CompositeItemProcessor<>();
        composite.setDelegates(delegates);
        return composite;
    }

    /**
     * Step 2 &mdash; the {@code STEP040} ({@code PGM=CBSTM03A}) replacement. A chunk-oriented step
     * that reads {@link Account} rows (the statement subjects), renders each into a text+HTML
     * {@link StatementWriter.StatementDocument} via the composite processor, and writes both
     * variants to S3 through the statement writer. Because the writer is an
     * {@code ItemStreamWriter}, it is also registered as a stream so it receives the
     * open/update/close lifecycle callbacks that buffer and flush the run-level objects.
     *
     * @param jobRepository               the Spring Batch job repository (Boot-auto-configured)
     * @param transactionManager          the platform transaction manager (Boot-auto-configured)
     * @param accountReader               the {@code @StepScope} account reader (driving cursor)
     * @param statementCompositeProcessor the processor chain producing statement documents
     * @param statementWriter             the S3 statement writer (text + HTML)
     * @return the configured statement-generation step
     */
    @Bean
    public Step statementGenerationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            AccountReader accountReader,
            CompositeItemProcessor<Account, StatementWriter.StatementDocument> statementCompositeProcessor,
            StatementWriter statementWriter) {
        return new StepBuilder(STATEMENT_GENERATION_STEP_NAME, jobRepository)
                .<Account, StatementWriter.StatementDocument>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountReader)
                .processor(statementCompositeProcessor)
                .writer(statementWriter)
                .stream(statementWriter)
                .build();
    }

    /**
     * The two-step statement-generation job. The generation step runs only after the delete step
     * succeeds, reproducing the JCL's {@code COND=(0,NE)} predecessor-success ordering (AAP
     * &sect;0.8.5). The job is not auto-run; the pipeline orchestrator launches it as stage 4a.
     *
     * @param jobRepository             the Spring Batch job repository (Boot-auto-configured)
     * @param deletePriorStatementsStep the delete step bean (injected by name)
     * @param statementGenerationStep   the generation step bean (injected by name)
     * @return the configured statement-generation job, registered under {@link #JOB_NAME}
     */
    @Bean
    public Job statementGenerationJob(
            JobRepository jobRepository,
            Step deletePriorStatementsStep,
            Step statementGenerationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(deletePriorStatementsStep)
                .next(statementGenerationStep)
                .build();
    }

    /**
     * Tasklet body for {@link #deletePriorStatementsStep}: clears the prior-run text and HTML
     * statement objects so the run starts clean (COBOL {@code STEP030}). Completes in a single
     * execution.
     *
     * @param contribution the step contribution (no records are counted for a clear operation)
     * @param chunkContext the chunk context (unused; the clear is a single pass)
     * @return {@link RepeatStatus#FINISHED}
     */
    private RepeatStatus clearPriorStatements(StepContribution contribution, ChunkContext chunkContext) {
        deletePriorObject(STATEMENT_TEXT_KEY);
        deletePriorObject(STATEMENT_HTML_KEY);
        LOGGER.info("Cleared prior statement outputs {} and {} from s3://{}",
                STATEMENT_TEXT_KEY, STATEMENT_HTML_KEY, statementsBucket);
        return RepeatStatus.FINISHED;
    }

    /**
     * Deletes a single prior-run statement object from the statements bucket. A missing object is a
     * no-op in S3 (idempotent), so this is safe on the first run; any other SDK failure fails the
     * step, which &mdash; given the predecessor-success ordering &mdash; prevents the generation
     * step from running against a stale bucket.
     *
     * @param key the object key to delete
     * @throws IllegalStateException if the delete fails (other than the object being absent)
     */
    private void deletePriorObject(String key) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(statementsBucket).key(key).build());
            LOGGER.debug("Deleted prior statement object s3://{}/{} (no-op if absent)",
                    statementsBucket, key);
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "Failed to delete prior statement object: s3://" + statementsBucket + "/" + key, e);
        }
    }

    /**
     * The type-adaptation seam between the processor and the writer: maps a rendered
     * {@link StatementProcessor.StatementResult} to the writer's
     * {@link StatementWriter.StatementDocument}, carrying the text and HTML variants verbatim. It is
     * the second delegate of the composite processor and is reached only for a non-null result (the
     * composite short-circuits when the first delegate filters an account).
     *
     * @return the result-to-document adapter
     */
    private ItemProcessor<StatementProcessor.StatementResult, StatementWriter.StatementDocument>
            statementResultToDocument() {
        return result -> new StatementWriter.StatementDocument(
                result.textStatement(), result.htmlStatement());
    }
}
