package com.carddemo.batch;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.Counter;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.exception.RejectReason;
import com.carddemo.repository.TransactionRepository;

/**
 * Chunk-step {@link ItemStreamWriter} for the CardDemo transaction-posting job
 * ({@code PostTransactionJob}) — writer #1 of 3 in the batch pipeline.
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This writer
 * reproduces the two mutually-exclusive output branches of the legacy batch posting program
 * {@code CBTRN02C.cbl}:</p>
 * <ul>
 *   <li><strong>Posted branch</strong> — {@code 2900-WRITE-TRANSACTION-FILE} writes the built
 *       transaction to the {@code TRANSACT} VSAM KSDS (keyed by {@code TRAN-ID}). Here the posted
 *       {@link Transaction} carried on each {@link PostingResult} is persisted through
 *       {@link TransactionRepository#saveAll(Iterable)} — the category-balance and account updates
 *       ({@code 2700}/{@code 2800}) having already been applied by {@code PostTransactionProcessor}
 *       within the same chunk transaction.</li>
 *   <li><strong>Reject branch</strong> — {@code 2500-WRITE-REJECT-REC} builds
 *       {@code REJECT-RECORD} = {@code REJECT-TRAN-DATA PIC X(350)} (the original
 *       {@code DALYTRAN-RECORD}) + {@code VALIDATION-TRAILER PIC X(80)}
 *       ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)} + {@code WS-VALIDATION-FAIL-REASON-DESC
 *       PIC X(76)}) and writes it to the {@code DALYREJS} dataset ({@code RECFM=F,LRECL=430}).
 *       Here each rejected {@link PostingResult} is serialized to a fixed-width 430-byte record and
 *       appended to a per-run reject buffer that is uploaded to S3 at end of step.</li>
 * </ul>
 *
 * <p><strong>Byte-parity (Gate 1/4/5).</strong> The 350-byte reject data portion is serialized
 * field-by-field per copybook {@code app/cpy/CVTRA06Y.cpy} ({@code DALYTRAN-RECORD}); alphanumeric
 * fields are left-justified and space-padded, unsigned numeric fields are right-justified and
 * zero-padded, and the signed amount ({@code DALYTRAN-AMT PIC S9(09)V99}) is rendered as an
 * 11-byte zoned-decimal value with the sign carried as an overpunch on the trailing digit — exactly
 * the on-file representation of the fixture {@code app/data/ASCII/dailytran.txt}. The assembled
 * reject record is therefore byte-identical to the legacy {@code DALYREJS} record, and each record
 * is newline-terminated to match the documented baseline.</p>
 *
 * <p><strong>Exit-code parity ({@code RETURN-CODE 4}).</strong> The running reject count (mirroring
 * {@code WS-REJECT-COUNT}) is published to the {@link StepExecution} execution context under
 * {@link #REJECT_COUNT_KEY}, allowing {@code config/BatchConfig}'s {@code JobExecutionDecider} /
 * the job's {@code afterStep} to reproduce {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}.
 * The rejected {@link Counter} carries the same signal for metrics.</p>
 *
 * <p><strong>Observability (AAP §0.7.1).</strong> Two Micrometer counters mirror the COBOL tallies:
 * {@code transactionsPostedCounter} ({@code carddemo.transactions.posted}, {@code WS-TRANSACTION-COUNT})
 * and {@code transactionsRejectedCounter} ({@code carddemo.transactions.rejected},
 * {@code WS-REJECT-COUNT}), both defined in {@code com.carddemo.observability.MetricsConfig}. The MDC
 * {@code correlationId} is established at job scope by {@code BatchCorrelationIdListener}; this writer
 * only emits structured SLF4J logs.</p>
 *
 * <p><strong>Fault handling (AAP §0.8.3).</strong> A rejected transaction is <em>data</em> written to
 * a file, never an exception. Genuine reject-buffer I/O faults or S3 upload faults are wrapped in
 * {@link FileProcessingException} (an unchecked exception, the structured-Java analogue of the COBOL
 * {@code 9999-ABEND-PROGRAM} paragraph) with structured logging; the JVM is never terminated. JPA
 * persistence failures from {@code saveAll} surface as Spring {@code DataAccessException}s and are
 * left to propagate so the step's configured retry/skip policy can act on them.</p>
 *
 * <p><strong>Lifecycle.</strong> As an {@link ItemStreamWriter} the reject buffer spans chunks:
 * {@link #open(ExecutionContext)} allocates a fresh per-run temp file and resets the counters,
 * {@link #write(Chunk)} routes each item, {@link #update(ExecutionContext)} surfaces the running
 * reject count, and {@link #close()} flushes, uploads the reject file to S3 and deletes the temp
 * file (idempotently). The class is {@link StepScope step-scoped} so each run gets an isolated
 * buffer and object key.</p>
 *
 * @see PostingResult
 * @see RejectReason
 * @see TransactionRepository
 */
@Component
@StepScope
public class PostTransactionItemWriter
        implements ItemStreamWriter<PostingResult>, StepExecutionListener {

    /** SLF4J logger for the posting writer. */
    private static final Logger log = LoggerFactory.getLogger(PostTransactionItemWriter.class);

    /** Total length of the serialized {@code DALYTRAN-RECORD} data portion (copybook CVTRA06Y). */
    private static final int DAILY_TRANSACTION_RECORD_LENGTH = 350;

    /** Length of the reject validation trailer ({@code WS-VALIDATION-TRAILER}): 4 + 76. */
    private static final int REJECT_TRAILER_LENGTH = 80;

    /** Fixed reject-record length ({@code DALYREJS LRECL}): 350 + 80. */
    private static final int REJECT_RECORD_LENGTH =
            DAILY_TRANSACTION_RECORD_LENGTH + REJECT_TRAILER_LENGTH;

    /** Width of the reject reason-code field ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}). */
    private static final int REASON_CODE_WIDTH = 4;

    /** Total digit positions of {@code DALYTRAN-AMT PIC S9(09)V99} (9 integer + 2 fraction). */
    private static final int AMOUNT_TOTAL_DIGITS = 11;

    /** Fractional digit count of {@code DALYTRAN-AMT} ({@code V99}). */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Zoned-decimal overpunch characters for a <em>positive</em> trailing digit ({@code 0}–{@code 9}).
     * Index is the digit value; {@code '{'} encodes {@code +0} through {@code 'I'} encoding {@code +9}.
     */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /**
     * Zoned-decimal overpunch characters for a <em>negative</em> trailing digit ({@code 0}–{@code 9}).
     * Index is the digit value; {@code '}'} encodes {@code -0} through {@code 'R'} encoding {@code -9}.
     */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    /**
     * Record separator appended after every fixed-width reject record. A literal line feed
     * ({@code \n}) is used (never {@code System.lineSeparator()}) so the emitted reject file matches
     * the documented byte baseline on every platform.
     */
    private static final String RECORD_SEPARATOR = "\n";

    /**
     * Step-execution-context key under which the running reject count is published for exit-code
     * parity ({@code RETURN-CODE 4}); read by {@code config/BatchConfig}.
     */
    public static final String REJECT_COUNT_KEY = "rejectCount";

    /** Prefix for the per-run reject temp file. */
    private static final String TEMP_FILE_PREFIX = "carddemo-dalyrejs-";

    /** Suffix for the per-run reject temp file. */
    private static final String TEMP_FILE_SUFFIX = ".dat";

    // --- Injected collaborators (constructor injection only) -----------------------------------

    /** Repository backing the posted-transaction master ({@code 2900-WRITE-TRANSACTION-FILE}). */
    private final TransactionRepository transactionRepository;

    /**
     * Framework S3 client used to upload the reject file. The auto-configured {@link S3Template} bean
     * is injected directly (not {@code config/AwsConfig}) to avoid a {@code batch → config} cycle.
     */
    private final S3Template s3Template;

    /** Posted-transactions counter ({@code carddemo.transactions.posted}). */
    private final Counter transactionsPostedCounter;

    /** Rejected-transactions counter ({@code carddemo.transactions.rejected}). */
    private final Counter transactionsRejectedCounter;

    /** Target S3 bucket for the reject file (GDG output generation). */
    private final String rejectBucket;

    /** Stable S3 object key for the reject file; bucket versioning provides the GDG {@code (+1)} generation. */
    private final String rejectObjectKey;

    // --- Per-run mutable state (reset in open, cleared in close) --------------------------------

    /** Backing temp file that accumulates reject records for the current step execution. */
    private Path rejectFile;

    /** Buffered writer over {@link #rejectFile}; ISO-8859-1 so one character maps to one byte. */
    private Writer rejectWriter;

    /** Running count of rejected records written this run ({@code WS-REJECT-COUNT}). */
    private long rejectCount;

    /** Running count of posted records persisted this run ({@code WS-TRANSACTION-COUNT}). */
    private long postedCount;

    /** Current step execution, captured in {@link #beforeStep(StepExecution)} for count publication. */
    private StepExecution stepExecution;

    /**
     * Creates the posting writer with all collaborators injected.
     *
     * @param transactionRepository       repository for persisting posted transactions; must not be
     *                                    {@code null}
     * @param s3Template                  auto-configured Spring Cloud AWS S3 template used to upload
     *                                    the reject file; must not be {@code null}
     * @param transactionsPostedCounter   posted-transactions counter bean; must not be {@code null}
     * @param transactionsRejectedCounter rejected-transactions counter bean; must not be {@code null}
     * @param rejectBucket                target S3 bucket for the reject file (defaults to
     *                                    {@code carddemo-batch-output})
     * @param rejectObjectKey             S3 object key for the reject file (defaults to
     *                                    {@code dalyrejs.dat})
     */
    public PostTransactionItemWriter(
            final TransactionRepository transactionRepository,
            final S3Template s3Template,
            @Qualifier("transactionsPostedCounter") final Counter transactionsPostedCounter,
            @Qualifier("transactionsRejectedCounter") final Counter transactionsRejectedCounter,
            @Value("${carddemo.batch.reject.bucket:carddemo-batch-output}") final String rejectBucket,
            @Value("${carddemo.batch.reject.object-key:dalyrejs.dat}") final String rejectObjectKey) {
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.s3Template = Objects.requireNonNull(s3Template, "s3Template must not be null");
        this.transactionsPostedCounter =
                Objects.requireNonNull(transactionsPostedCounter, "transactionsPostedCounter must not be null");
        this.transactionsRejectedCounter =
                Objects.requireNonNull(transactionsRejectedCounter, "transactionsRejectedCounter must not be null");
        this.rejectBucket = rejectBucket;
        this.rejectObjectKey = rejectObjectKey;
    }

    // --- ItemWriter ----------------------------------------------------------------------------

    /**
     * Routes each {@link PostingResult} in the chunk by outcome, reproducing the posted / reject
     * branch dispatch of the COBOL main posting loop in {@code CBTRN02C}.
     *
     * <p><strong>Posted</strong> ({@link PostingResult#isPosted()}): the built {@link Transaction} is
     * collected and the whole batch is persisted with a single
     * {@link TransactionRepository#saveAll(Iterable)} call (the {@code 2900-WRITE-TRANSACTION-FILE}
     * analogue), incrementing {@code transactionsPostedCounter} once per posted item.</p>
     *
     * <p><strong>Rejected</strong> ({@link PostingResult#isRejected()}): a fixed-width 430-byte reject
     * record is serialized from {@link PostingResult#sourceTransaction()} and appended to the run's
     * reject buffer in read order ({@code 2500-WRITE-REJECT-REC}), incrementing
     * {@code transactionsRejectedCounter} and the running reject count once per reject.</p>
     *
     * <p>The running reject count is surfaced to the step execution context at the end of the chunk
     * so it is committed alongside the chunk transaction (exit-code parity).</p>
     *
     * @param items the chunk of posting results to write; never {@code null}
     */
    @Override
    public void write(final Chunk<? extends PostingResult> items) {
        if (items == null) {
            return;
        }
        final List<? extends PostingResult> results = items.getItems();
        if (results.isEmpty()) {
            return;
        }
        final List<Transaction> postedBatch = new ArrayList<>(results.size());
        for (final PostingResult result : results) {
            if (result.isPosted()) {
                // 2900-WRITE-TRANSACTION-FILE: collect for a single keyed batch insert.
                postedBatch.add(result.postedTransaction());
                this.transactionsPostedCounter.increment();
            } else {
                // 2500-WRITE-REJECT-REC: serialize + append the 430-byte reject record.
                appendRejectRecord(result);
            }
        }
        if (!postedBatch.isEmpty()) {
            this.transactionRepository.saveAll(postedBatch);
            this.postedCount += postedBatch.size();
        }
        publishRejectCount();
        log.debug("POSTTRAN chunk written: postedInChunk={} rejectRunningTotal={}",
                postedBatch.size(), this.rejectCount);
    }

    /**
     * Serializes a rejected result to its fixed-width 430-byte record, appends it (newline-terminated)
     * to the reject buffer, and updates the rejected counter and running reject count.
     *
     * @param result a rejected posting result carrying the original daily transaction and reason
     * @throws FileProcessingException if the reject record cannot be appended to the buffer
     */
    private void appendRejectRecord(final PostingResult result) {
        final String rejectRecord = buildRejectRecord(result.sourceTransaction(), result.rejectReason());
        try {
            this.rejectWriter.write(rejectRecord);
            this.rejectWriter.write(RECORD_SEPARATOR);
        } catch (final IOException e) {
            throw new FileProcessingException(
                    "POSTTRAN failed to append reject record for dalytranId="
                            + safeId(result.sourceTransaction()), e);
        }
        this.rejectCount++;
        this.transactionsRejectedCounter.increment();
    }

    // --- StepExecutionListener -----------------------------------------------------------------

    /**
     * Captures the current {@link StepExecution} so the running reject count can be published to its
     * execution context for exit-code parity. Invoked by Spring Batch before the step body runs (and
     * therefore before {@link #open(ExecutionContext)}) when the writer is registered as a step
     * listener by {@code config/BatchConfig}.
     *
     * @param stepExecution the step execution about to run; never {@code null}
     */
    @Override
    public void beforeStep(final StepExecution stepExecution) {
        this.stepExecution = stepExecution;
    }

    /**
     * Finalizes the reject artifact and publishes the reject count. This is where the reject file is
     * flushed and <strong>uploaded to S3</strong> — deliberately here rather than in {@link #close()}
     * (QA finding <strong>F4</strong>, decision {@code D-027}).
     *
     * <p><strong>Why the upload lives here.</strong> {@code AbstractStep} persists the step and job
     * batch/exit status <em>after</em> {@code afterStep()} returns but <em>before</em> {@code close()}
     * runs, and it swallows any exception thrown from either callback. An upload performed in
     * {@code close()} therefore cannot influence the recorded outcome: a failed upload was previously
     * only logged as "Exception while closing step execution resources" while the step/job was already
     * recorded {@code COMPLETED} — a silently lost artifact. Performing the upload here, and marking the
     * step {@code FAILED} via {@link StepExecution#setStatus(BatchStatus)} on failure, makes that
     * failure durable (the FAILED status is persisted by the framework right after this method).</p>
     *
     * <p>On a step that already failed during item processing (this callback also runs on the failure
     * path, from {@code AbstractStep}'s finally block), the upload is skipped and the existing
     * {@code FAILED} status is preserved. On the normal path the reject object is always uploaded — even
     * when empty — so each run produces a new S3 version, mirroring the legacy {@code DALYREJS(+1)} GDG
     * generation. The reject count is left in the execution context so the decider can still map it to
     * the {@code RETURN-CODE 4} disposition.</p>
     *
     * @param stepExecution the completed (or failing) step execution; never {@code null}
     * @return {@code null} on success (leaving the exit status for the job flow to decide), or
     *         {@link ExitStatus#FAILED} when the reject-file finalization fails
     */
    @Override
    public ExitStatus afterStep(final StepExecution stepExecution) {
        // The step already failed during item processing: do not attempt the upload, preserve FAILED.
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            log.warn("POSTTRAN step status is {} at afterStep; skipping reject-file upload",
                    stepExecution.getStatus());
            return null;
        }
        try {
            // Flush + close the buffer, then upload. A failure here fails the step (see method Javadoc).
            flushAndCloseRejectWriter();
            if (this.rejectFile != null) {
                uploadRejectFile();
            }
        } catch (final IOException | RuntimeException e) {
            log.error("POSTTRAN reject-file finalization failed; failing the step", e);
            stepExecution.addFailureException(e);
            stepExecution.setStatus(BatchStatus.FAILED);
            return ExitStatus.FAILED;
        }
        publishRejectCount();
        log.info("POSTTRAN posting step complete: postedCount={} rejectCount={}",
                this.postedCount, this.rejectCount);
        return null;
    }

    // --- ItemStream lifecycle ------------------------------------------------------------------

    /**
     * Opens a fresh per-run reject buffer: allocates a temp file, opens an ISO-8859-1 buffered writer
     * over it, resets the posted/reject counters, and publishes an initial reject count of zero.
     * Called once at the start of the step.
     *
     * @param executionContext the step execution context (state is per-run; not read for input)
     * @throws FileProcessingException if the temp file or its writer cannot be created
     */
    @Override
    public void open(final ExecutionContext executionContext) {
        this.rejectCount = 0L;
        this.postedCount = 0L;
        try {
            this.rejectFile = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
            // Files.newBufferedWriter defaults to CREATE, TRUNCATE_EXISTING and WRITE.
            this.rejectWriter = Files.newBufferedWriter(this.rejectFile, StandardCharsets.ISO_8859_1);
        } catch (final IOException e) {
            throw new FileProcessingException(
                    "POSTTRAN failed to open the reject buffer temp file", e);
        }
        publishRejectCount();
        log.info("POSTTRAN reject writer opened: tempFile={} s3Bucket={} s3Key={}",
                this.rejectFile, this.rejectBucket, this.rejectObjectKey);
    }

    /**
     * Surfaces the running reject count into the step execution context so it is durable across chunk
     * commits and visible to the job's decider / {@code afterStep} (exit-code parity). This is the
     * guaranteed publication path: Spring Batch always passes the step execution context here.
     *
     * @param executionContext the step execution context to update; never {@code null}
     */
    @Override
    public void update(final ExecutionContext executionContext) {
        executionContext.putLong(REJECT_COUNT_KEY, this.rejectCount);
    }

    /**
     * Releases the per-run reject buffer and deletes the temp file. <strong>Cleanup only</strong> (QA
     * finding <strong>F4</strong>, decision {@code D-027}): the flush and S3 upload were moved to
     * {@link #afterStep(StepExecution)} because {@code AbstractStep} swallows any exception thrown from
     * {@code close()} and has already persisted the step/job status by the time {@code close()} runs, so
     * an upload here could never fail the step. This method therefore performs no upload; it defensively
     * closes the buffer (in case {@code afterStep} skipped it on a failed step) and removes the temp
     * file. It is null-safe and idempotent: a second invocation is a no-op.
     */
    @Override
    public void close() {
        closeRejectWriterQuietly();
        deleteTempFileQuietly();
        this.rejectFile = null;
    }

    /**
     * Flushes and closes the reject buffer, propagating any {@link IOException} so
     * {@link #afterStep(StepExecution)} can fail the step. Null-safe and idempotent.
     *
     * @throws IOException if flushing or closing the underlying buffer fails
     */
    private void flushAndCloseRejectWriter() throws IOException {
        if (this.rejectWriter != null) {
            this.rejectWriter.flush();
            this.rejectWriter.close();
            this.rejectWriter = null;
        }
    }

    /**
     * Best-effort close of the reject buffer used during {@link #close()} cleanup. A failure is logged
     * at {@code WARN} and never propagated (cleanup must not mask a real processing outcome).
     */
    private void closeRejectWriterQuietly() {
        if (this.rejectWriter != null) {
            try {
                this.rejectWriter.close();
            } catch (final IOException e) {
                log.warn("POSTTRAN could not close the reject buffer during cleanup", e);
            } finally {
                this.rejectWriter = null;
            }
        }
    }

    /**
     * Uploads the accumulated reject file to the configured S3 bucket/key via the framework
     * {@link S3Template}. Any I/O fault or S3 client fault is wrapped as a
     * {@link FileProcessingException}; the JVM is never terminated.
     */
    private void uploadRejectFile() {
        try (InputStream in = Files.newInputStream(this.rejectFile)) {
            this.s3Template.upload(this.rejectBucket, this.rejectObjectKey, in);
        } catch (final IOException | RuntimeException e) {
            throw new FileProcessingException(
                    "POSTTRAN failed to upload reject file to s3://"
                            + this.rejectBucket + "/" + this.rejectObjectKey, e);
        }
        log.info("POSTTRAN reject file uploaded: s3://{}/{} rejectCount={}",
                this.rejectBucket, this.rejectObjectKey, this.rejectCount);
    }

    /**
     * Best-effort deletion of the per-run temp file. A failure is logged at {@code WARN} and never
     * propagated, so cleanup can never mask a real processing outcome.
     */
    private void deleteTempFileQuietly() {
        if (this.rejectFile != null) {
            try {
                Files.deleteIfExists(this.rejectFile);
            } catch (final IOException e) {
                log.warn("POSTTRAN could not delete reject temp file {}", this.rejectFile, e);
            }
        }
    }

    /**
     * Publishes the running reject count to the captured {@link StepExecution}'s execution context,
     * when a step execution was supplied via {@link #beforeStep(StepExecution)}.
     */
    private void publishRejectCount() {
        if (this.stepExecution != null) {
            this.stepExecution.getExecutionContext().putLong(REJECT_COUNT_KEY, this.rejectCount);
        }
    }

    // --- Fixed-width serialization (byte-parity: CVTRA06Y + WS-VALIDATION-TRAILER) -------------

    /**
     * Builds the fixed-width 430-byte reject record: the 350-byte {@code DALYTRAN-RECORD} data
     * portion followed by the 80-byte validation trailer (a {@code 4}-digit reason code plus a
     * {@code 76}-character reason description). Reproduces COBOL {@code 2500-WRITE-REJECT-REC}
     * ({@code REJECT-RECORD} = {@code REJECT-TRAN-DATA} + {@code VALIDATION-TRAILER}).
     *
     * @param dt     the original daily transaction to re-materialize; never {@code null}
     * @param reason the rejection reason supplying the trailer code and description; never {@code null}
     * @return a 430-character reject record (one character per byte under ISO-8859-1)
     * @throws FileProcessingException if the assembled record is not exactly 430 characters
     */
    private String buildRejectRecord(final DailyTransaction dt, final RejectReason reason) {
        final String dataPortion = formatDailyTransactionRecord(dt);
        final String trailer = unsignedNumeric(reason.getCode(), REASON_CODE_WIDTH)
                + reason.formattedDescription();
        final String rejectRecord = dataPortion + trailer;
        if (rejectRecord.length() != REJECT_RECORD_LENGTH) {
            throw new FileProcessingException(
                    "POSTTRAN assembled reject record length " + rejectRecord.length()
                            + " != expected " + REJECT_RECORD_LENGTH + " for dalytranId=" + safeId(dt));
        }
        return rejectRecord;
    }

    /**
     * Serializes a {@link DailyTransaction} to the 350-byte fixed-width {@code DALYTRAN-RECORD} layout
     * of copybook {@code CVTRA06Y}, field-by-field in declared order. Alphanumeric fields are
     * left-justified and space-padded; the unsigned numeric fields are right-justified and
     * zero-padded; the amount is zoned-decimal with an overpunch sign on the trailing digit; and a
     * 20-byte space {@code FILLER} completes the record.
     *
     * @param dt the daily transaction to serialize; never {@code null}
     * @return a 350-character record (one character per byte under ISO-8859-1)
     * @throws FileProcessingException if the assembled record is not exactly 350 characters
     */
    private String formatDailyTransactionRecord(final DailyTransaction dt) {
        final StringBuilder sb = new StringBuilder(DAILY_TRANSACTION_RECORD_LENGTH);
        sb.append(alphanumeric(dt.getDalytranId(), 16));             // DALYTRAN-ID            X(16)
        sb.append(alphanumeric(dt.getDalytranTypeCd(), 2));          // DALYTRAN-TYPE-CD       X(02)
        sb.append(unsignedNumeric(dt.getDalytranCatCd(), 4));        // DALYTRAN-CAT-CD        9(04)
        sb.append(alphanumeric(dt.getDalytranSource(), 10));         // DALYTRAN-SOURCE        X(10)
        sb.append(alphanumeric(dt.getDalytranDesc(), 100));          // DALYTRAN-DESC          X(100)
        sb.append(signedAmount(dt.getDalytranAmt()));                // DALYTRAN-AMT           S9(09)V99
        sb.append(unsignedNumeric(dt.getDalytranMerchantId(), 9));   // DALYTRAN-MERCHANT-ID   9(09)
        sb.append(alphanumeric(dt.getDalytranMerchantName(), 50));   // DALYTRAN-MERCHANT-NAME X(50)
        sb.append(alphanumeric(dt.getDalytranMerchantCity(), 50));   // DALYTRAN-MERCHANT-CITY X(50)
        sb.append(alphanumeric(dt.getDalytranMerchantZip(), 10));    // DALYTRAN-MERCHANT-ZIP  X(10)
        sb.append(alphanumeric(dt.getDalytranCardNum(), 16));        // DALYTRAN-CARD-NUM      X(16)
        sb.append(alphanumeric(dt.getDalytranOrigTs(), 26));         // DALYTRAN-ORIG-TS       X(26)
        sb.append(alphanumeric(dt.getDalytranProcTs(), 26));         // DALYTRAN-PROC-TS       X(26)
        sb.append(" ".repeat(20));                                   // FILLER                X(20)
        final String record = sb.toString();
        if (record.length() != DAILY_TRANSACTION_RECORD_LENGTH) {
            throw new FileProcessingException(
                    "POSTTRAN serialized daily-transaction record length " + record.length()
                            + " != expected " + DAILY_TRANSACTION_RECORD_LENGTH
                            + " for dalytranId=" + safeId(dt));
        }
        return record;
    }

    /**
     * Renders an alphanumeric COBOL field ({@code PIC X(width)}) left-justified and space-padded to
     * {@code width} characters. A {@code null} value renders as all spaces; a longer value is
     * truncated on the right, matching a COBOL {@code MOVE} into a shorter alphanumeric picture.
     *
     * @param value the field value (may be {@code null})
     * @param width the fixed field width
     * @return a {@code width}-character left-justified, space-padded string
     */
    private static String alphanumeric(final String value, final int width) {
        final String v = (value == null) ? "" : value;
        if (v.length() == width) {
            return v;
        }
        if (v.length() > width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /**
     * Renders an unsigned COBOL numeric field ({@code PIC 9(width)}) right-justified and zero-padded
     * to {@code width} characters. A {@code null} value renders as all zeros; a value with more than
     * {@code width} digits is truncated on the high-order (left) side, matching a COBOL {@code MOVE}
     * into a shorter numeric picture. The magnitude is used, so an (unexpected) negative renders as
     * its absolute value — consistent with an unsigned {@code PIC 9} picture.
     *
     * @param value the numeric value (may be {@code null})
     * @param width the fixed field width
     * @return a {@code width}-character zero-padded numeric string
     */
    private static String unsignedNumeric(final Number value, final int width) {
        final long magnitude = (value == null) ? 0L : Math.abs(value.longValue());
        final String digits = Long.toString(magnitude);
        if (digits.length() == width) {
            return digits;
        }
        if (digits.length() > width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders {@code DALYTRAN-AMT} ({@code PIC S9(09)V99}, {@code USAGE DISPLAY}) as an 11-byte
     * zoned-decimal value: the two implied fraction digits are folded into the digit run and the sign
     * is carried as an overpunch on the trailing (units-of-cents) digit — {@code '{'}–{@code 'I'} for
     * a positive {@code 0}–{@code 9} and {@code '}'}–{@code 'R'} for a negative {@code 0}–{@code 9}.
     * This is the exact on-file representation of the {@code app/data/ASCII/dailytran.txt} fixture.
     *
     * <p>Decimal fidelity (AAP §0.8.2): the value stays a {@link BigDecimal}; no {@code float} or
     * {@code double} is used. A {@code null} amount is treated as {@code +0.00}.</p>
     *
     * @param amount the signed amount (may be {@code null})
     * @return an 11-character zoned-decimal representation carrying an overpunch sign
     */
    private static String signedAmount(final BigDecimal amount) {
        final BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        final boolean negative = value.signum() < 0;
        final BigInteger unscaled =
                value.abs().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).unscaledValue();
        String digits = unscaled.toString();
        if (digits.length() < AMOUNT_TOTAL_DIGITS) {
            digits = "0".repeat(AMOUNT_TOTAL_DIGITS - digits.length()) + digits;
        } else if (digits.length() > AMOUNT_TOTAL_DIGITS) {
            digits = digits.substring(digits.length() - AMOUNT_TOTAL_DIGITS);
        }
        final int lastDigit = digits.charAt(AMOUNT_TOTAL_DIGITS - 1) - '0';
        final char overpunch = negative ? NEGATIVE_OVERPUNCH[lastDigit] : POSITIVE_OVERPUNCH[lastDigit];
        return digits.substring(0, AMOUNT_TOTAL_DIGITS - 1) + overpunch;
    }

    /**
     * Returns the daily-transaction id for diagnostics, tolerating a {@code null} record or id.
     *
     * @param dt the daily transaction (may be {@code null})
     * @return the id, or a placeholder when it is unavailable
     */
    private static String safeId(final DailyTransaction dt) {
        if (dt == null || dt.getDalytranId() == null) {
            return "<unknown>";
        }
        return dt.getDalytranId();
    }
}
