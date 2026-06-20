package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.TransactionPostingProcessor;
import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.batch.readers.DailyTransactionReader;
import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.model.entity.DailyTransaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.classify.Classifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration that re-hosts the mainframe JCL job {@code POSTTRAN.jcl}
 * (single step {@code EXEC PGM=CBTRN02C}) and the COBOL daily-transaction posting program
 * {@code CBTRN02C.cbl} (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; the
 * COBOL/JCL is not copied).
 *
 * <p>The COBOL per-record main loop ({@code PERFORM UNTIL END-OF-FILE = 'Y'}: read the next daily
 * transaction, {@code 1500-VALIDATE-TRAN}, then either {@code 2000-POST-TRANSACTION} or
 * {@code ADD 1 TO WS-REJECT-COUNT} + {@code 2500-WRITE-REJECT-REC}) is reproduced as a single
 * chunk-oriented {@link Step}: the {@link DailyTransactionReader} reads the 350-byte
 * {@code CVTRA06Y} records, the {@link TransactionPostingProcessor} runs the four-stage validation
 * cascade in memory and emits a {@link PostingResult}, and a
 * {@link ClassifierCompositeItemWriter} routes each result to one of two sibling writers &mdash;
 * the {@link TransactionWriter} (persist the transaction, upsert the category balance, update the
 * account) for a posted result, or the {@link RejectWriter} (emit the 430-byte {@code DALYREJS}
 * record) for a reject. The chunk-oriented step's transaction manager provides the single logical
 * unit of work that replaces the COBOL commit boundary, so a failure anywhere in the chunk rolls
 * back the whole chunk.</p>
 *
 * <p>Both sibling writers are {@code ItemStreamWriter}s and are registered as step streams
 * ({@code .stream(...)}) because a {@link ClassifierCompositeItemWriter} does not propagate the
 * {@code open}/{@code update}/{@code close} stream lifecycle to its delegates; without this their
 * S3 buffers would never be allocated or flushed.</p>
 *
 * <p>The COBOL condition code ({@code IF WS-REJECT-COUNT &gt; 0 MOVE 4 TO RETURN-CODE}) is mapped
 * by a {@link StepExecutionListener} that sets the {@value #COMPLETED_WITH_REJECTS} exit status
 * when any record was rejected while leaving the batch status {@code COMPLETED} &mdash; RC=4 is a
 * warning, not a failure, so downstream pipeline stages may still run.</p>
 *
 * <p><strong>Integration seam:</strong> the reject adapter re-serializes each rejected
 * {@link DailyTransaction} to its 350-byte {@code CVTRA06Y} fixed-width image (the verbatim
 * {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}) via {@link #encodeDailyTransaction}, because
 * the staging entity does not retain the raw input line and {@link PostingResult#rejectTrailer()}
 * carries only the 80-byte trailer.</p>
 *
 * <p>This is a {@code @Configuration} only: the job is a {@code @Bean} and is <strong>not</strong>
 * auto-run at boot ({@code spring.batch.job.enabled=false}); it is launched by the batch pipeline
 * orchestrator. The {@link JobRepository} and {@link PlatformTransactionManager} are the beans
 * auto-configured by Spring Boot (see {@code com.carddemo.config.BatchConfig}, which intentionally
 * does not declare them); no {@code @EnableBatchProcessing} is used. All monetary values are
 * handled with {@link BigDecimal}; {@code float}/{@code double} are never used.</p>
 */
@Configuration(value = "dailyTransactionPostingJobConfig", proxyBeanMethods = false)
public final class DailyTransactionPostingJob {

    /** Canonical Spring Batch job name (referenced by name from the pipeline orchestrator). */
    public static final String JOB_NAME = "dailyTransactionPostingJob";

    /** Bean name and step name of the single posting step ({@code POSTTRAN.jcl STEP15}). */
    public static final String STEP_NAME = "dailyTransactionPostingStep";

    /**
     * Custom step/job exit status equivalent to the COBOL {@code RETURN-CODE = 4}: the run
     * completed but at least one daily transaction was rejected. A pipeline
     * {@code JobExecutionDecider} keys off this exit code to honor the RC=4-but-continue semantics.
     */
    public static final String COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /** Number of daily-transaction records processed per chunk (the chunk commit interval). */
    private static final int CHUNK_SIZE = 100;

    /** Fixed record length of the {@code CVTRA06Y} daily-transaction layout (RECLN = 350). */
    private static final int RECORD_LENGTH = 350;

    // --- CVTRA06Y field widths used when re-encoding a rejected record (sum = 350) -------------
    private static final int WIDTH_TRAN_ID = 16;
    private static final int WIDTH_TYPE_CD = 2;
    private static final int WIDTH_CAT_CD = 4;
    private static final int WIDTH_SOURCE = 10;
    private static final int WIDTH_DESC = 100;
    private static final int WIDTH_AMT = 11;
    private static final int WIDTH_MERCHANT_ID = 9;
    private static final int WIDTH_MERCHANT_NAME = 50;
    private static final int WIDTH_MERCHANT_CITY = 50;
    private static final int WIDTH_MERCHANT_ZIP = 10;
    private static final int WIDTH_CARD_NUM = 16;
    private static final int WIDTH_ORIG_TS = 26;
    private static final int WIDTH_PROC_TS = 26;
    private static final int WIDTH_FILLER = 20;

    /** Monetary scale of the {@code DALYTRAN-AMT PIC S9(09)V99} field. */
    private static final int AMOUNT_SCALE = 2;

    /** Step-scoped reader of the 350-byte daily-transaction staging object ({@code DALYTRAN} DD). */
    private final DailyTransactionReader dailyTransactionReader;

    /** Processor running the four-stage validation cascade ({@code 1500-VALIDATE-TRAN}). */
    private final TransactionPostingProcessor transactionPostingProcessor;

    /** Writer that persists a posted transaction, upserts the category balance, updates the account. */
    private final TransactionWriter transactionWriter;

    /** Writer that emits the 430-byte reject record to S3 ({@code DALYREJS} equivalent). */
    private final RejectWriter rejectWriter;

    /**
     * Creates the daily-transaction posting job configuration with its sibling step components
     * injected by the Spring container.
     *
     * @param dailyTransactionReader      step-scoped reader for the daily-transaction staging object
     * @param transactionPostingProcessor processor performing the validation cascade per record
     * @param transactionWriter           writer that persists posted transactions and balances
     * @param rejectWriter                writer that emits 430-byte reject records to S3
     */
    public DailyTransactionPostingJob(
            final DailyTransactionReader dailyTransactionReader,
            final TransactionPostingProcessor transactionPostingProcessor,
            final TransactionWriter transactionWriter,
            final RejectWriter rejectWriter) {
        this.dailyTransactionReader =
                Objects.requireNonNull(dailyTransactionReader, "dailyTransactionReader must not be null");
        this.transactionPostingProcessor =
                Objects.requireNonNull(transactionPostingProcessor, "transactionPostingProcessor must not be null");
        this.transactionWriter =
                Objects.requireNonNull(transactionWriter, "transactionWriter must not be null");
        this.rejectWriter =
                Objects.requireNonNull(rejectWriter, "rejectWriter must not be null");
    }

    /**
     * The reject-count step listener (also the holder of the per-run reject counter). Exposed as a
     * singleton bean so the same instance is shared by the composite writer (which increments it
     * for every routed reject) and the step (which registers it to translate the count into the
     * {@value #COMPLETED_WITH_REJECTS} exit status).
     *
     * @return the shared reject-count step listener
     */
    @Bean
    public RejectCountStepListener dailyTransactionPostingRejectListener() {
        return new RejectCountStepListener();
    }

    /**
     * Builds the {@link ClassifierCompositeItemWriter} that routes each {@link PostingResult} to the
     * correct sibling writer, performing the type adaptation between the processor's output and the
     * two writers' distinct input contracts.
     *
     * <p>A posted result ({@link PostingResult#rejected()} {@code == false}) is mapped to a
     * {@link TransactionWriter.PostedTransaction} (the posted transaction plus its owning account
     * id) and delegated to the {@link TransactionWriter}. A reject is mapped to a
     * {@link RejectWriter.RejectedTransaction} (the re-serialized 350-byte record, the numeric
     * reject code, and the reason description) and delegated to the {@link RejectWriter}; the shared
     * reject counter is advanced by the number of rejects routed in the chunk. Because the composite
     * writer classifies each item individually, each adapter receives only its own items, so the
     * chunk size of the rejected adapter is exactly the reject count for that chunk.</p>
     *
     * @param dailyTransactionPostingRejectListener the shared reject-count holder to advance
     * @return the configured classifier composite writer over {@link PostingResult}
     */
    @Bean
    public ClassifierCompositeItemWriter<PostingResult> dailyTransactionPostingWriter(
            final RejectCountStepListener dailyTransactionPostingRejectListener) {

        // Posted adapter: map to the transaction writer's contract and delegate the routed chunk.
        final ItemWriter<PostingResult> postedWriter = chunk -> {
            final Chunk<TransactionWriter.PostedTransaction> mapped = new Chunk<>();
            for (final PostingResult result : chunk) {
                mapped.add(new TransactionWriter.PostedTransaction(
                        result.postedTransaction(), result.account().getAcctId()));
            }
            this.transactionWriter.write(mapped);
        };

        // Rejected adapter: re-serialize the 350-byte record, count the rejects (RC=4 mapping),
        // and delegate the routed chunk to the reject writer (which assembles the 430-byte record).
        final ItemWriter<PostingResult> rejectedWriter = chunk -> {
            final Chunk<RejectWriter.RejectedTransaction> mapped = new Chunk<>();
            for (final PostingResult result : chunk) {
                mapped.add(new RejectWriter.RejectedTransaction(
                        encodeDailyTransaction(result.originalDailyTransaction()),
                        result.rejectCode(),
                        result.rejectReasonDescription()));
            }
            dailyTransactionPostingRejectListener.addRejects(mapped.size());
            this.rejectWriter.write(mapped);
        };

        final Classifier<PostingResult, ItemWriter<? super PostingResult>> classifier =
                result -> result.rejected() ? rejectedWriter : postedWriter;

        final ClassifierCompositeItemWriter<PostingResult> writer = new ClassifierCompositeItemWriter<>();
        writer.setClassifier(classifier);
        return writer;
    }

    /**
     * Builds the single chunk-oriented posting step ({@code POSTTRAN.jcl STEP15}).
     *
     * <p>The chunk types are {@code <DailyTransaction, PostingResult>}: the reader supplies daily
     * transactions, the processor validates each into a {@link PostingResult}, and the classifier
     * composite writer routes each result to the posted or reject delegate. Both sibling
     * {@code ItemStreamWriter}s are registered as streams so their {@code open}/{@code update}/
     * {@code close} lifecycle (and therefore their S3 flush) fires, and the reject-count listener is
     * attached to translate any rejects into the {@value #COMPLETED_WITH_REJECTS} exit status.</p>
     *
     * @param jobRepository                         the auto-configured Spring Batch job repository
     * @param transactionManager                    the auto-configured platform transaction manager
     *                                              (owns the per-chunk unit of work)
     * @param dailyTransactionPostingWriter         the classifier composite writer routing each result
     * @param dailyTransactionPostingRejectListener the shared reject-count listener (RC=4 mapping)
     * @return the configured daily-transaction posting step
     */
    @Bean
    public Step dailyTransactionPostingStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final ClassifierCompositeItemWriter<PostingResult> dailyTransactionPostingWriter,
            final RejectCountStepListener dailyTransactionPostingRejectListener) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostingResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(this.dailyTransactionReader)
                .processor(this.transactionPostingProcessor)
                .writer(dailyTransactionPostingWriter)
                .stream(this.transactionWriter)
                .stream(this.rejectWriter)
                .listener(dailyTransactionPostingRejectListener)
                .build();
    }

    /**
     * Builds the daily-transaction posting job ({@code POSTTRAN.jcl}). The job has the single
     * posting step as its only stage; it is the first stage of the batch pipeline and therefore
     * carries no predecessor condition ({@code POSTTRAN.jcl} declares no {@code COND}).
     *
     * @param jobRepository                the auto-configured Spring Batch job repository
     * @param dailyTransactionPostingStep  the single posting step
     * @return the configured daily-transaction posting job
     */
    @Bean
    public Job dailyTransactionPostingJob(
            final JobRepository jobRepository,
            final Step dailyTransactionPostingStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(dailyTransactionPostingStep)
                .build();
    }

    /**
     * Re-serializes a {@link DailyTransaction} to its {@value #RECORD_LENGTH}-character
     * {@code CVTRA06Y} fixed-width image, the equivalent of the COBOL
     * {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}: alphanumeric fields are left-justified and
     * space-padded, numeric fields are right-justified and zero-padded, the amount is re-encoded as
     * an 11-character zoned-decimal trailing-sign overpunch, and the trailing 20-byte filler is
     * spaces. Every produced character is in the single-byte Latin range, so the reject writer's
     * ISO-8859-1 encoding preserves the byte-exact external record contract.
     *
     * @param dalytran the rejected daily transaction to serialize
     * @return the {@value #RECORD_LENGTH}-character {@code CVTRA06Y} record
     */
    static String encodeDailyTransaction(final DailyTransaction dalytran) {
        final StringBuilder record = new StringBuilder(RECORD_LENGTH);
        record.append(fixedAlpha(dalytran.getDalytranId(), WIDTH_TRAN_ID));
        record.append(fixedAlpha(dalytran.getDalytranTypeCd(), WIDTH_TYPE_CD));
        record.append(fixedNumeric(dalytran.getDalytranCatCd(), WIDTH_CAT_CD));
        record.append(fixedAlpha(dalytran.getDalytranSource(), WIDTH_SOURCE));
        record.append(fixedAlpha(dalytran.getDalytranDesc(), WIDTH_DESC));
        record.append(encodeSignedAmount(dalytran.getDalytranAmt(), WIDTH_AMT));
        record.append(fixedNumeric(dalytran.getDalytranMerchantId(), WIDTH_MERCHANT_ID));
        record.append(fixedAlpha(dalytran.getDalytranMerchantName(), WIDTH_MERCHANT_NAME));
        record.append(fixedAlpha(dalytran.getDalytranMerchantCity(), WIDTH_MERCHANT_CITY));
        record.append(fixedAlpha(dalytran.getDalytranMerchantZip(), WIDTH_MERCHANT_ZIP));
        record.append(fixedAlpha(dalytran.getDalytranCardNum(), WIDTH_CARD_NUM));
        record.append(fixedAlpha(dalytran.getDalytranOrigTs(), WIDTH_ORIG_TS));
        record.append(fixedAlpha(dalytran.getDalytranProcTs(), WIDTH_PROC_TS));
        record.append(" ".repeat(WIDTH_FILLER));
        return record.toString();
    }

    /**
     * Left-justifies and space-pads an alphanumeric value to a fixed width, truncating an
     * over-length value to the field width.
     *
     * @param value the value to format (may be {@code null}, treated as empty)
     * @param width the fixed field width
     * @return the formatted, width-exact field
     */
    private static String fixedAlpha(final String value, final int width) {
        final String text = (value == null) ? "" : value;
        if (text.length() == width) {
            return text;
        }
        if (text.length() > width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * Right-justifies and zero-pads a numeric value to a fixed width. A {@code null} value formats
     * as zeros; an over-length value keeps its low-order {@code width} digits, mirroring the COBOL
     * high-order truncation of a {@code MOVE} into a smaller {@code PIC 9} field.
     *
     * @param value the numeric value to format (may be {@code null})
     * @param width the fixed field width
     * @return the formatted, width-exact field
     */
    private static String fixedNumeric(final Number value, final int width) {
        final long magnitude = (value == null) ? 0L : Math.abs(value.longValue());
        final String digits = leftPadZero(Long.toString(magnitude), width);
        return (digits.length() > width) ? digits.substring(digits.length() - width) : digits;
    }

    /**
     * Encodes a {@link BigDecimal} amount as a fixed-width zoned-decimal {@code S9(09)V99} token
     * with a trailing-sign overpunch on the final byte (the inverse of the reader's decode). A
     * {@code null} amount encodes as zero. The magnitude is taken to scale {@value #AMOUNT_SCALE}
     * ({@link RoundingMode#HALF_EVEN}) and zero-padded to {@code width} digits before the final
     * digit's overpunch is applied: a non-negative units digit {@code 0} becomes <code>'{'</code>
     * and {@code 1..9} become {@code 'A'..'I'}; a negative units digit {@code 0} becomes
     * <code>'}'</code> and {@code 1..9} become {@code 'J'..'R'}.
     *
     * @param amount the amount to encode (may be {@code null} or negative)
     * @param width  the total field width (11 for {@code S9(09)V99})
     * @return the encoded fixed-width amount token
     */
    private static String encodeSignedAmount(final BigDecimal amount, final int width) {
        final BigDecimal scaled =
                (amount == null ? BigDecimal.ZERO : amount).setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        final boolean negative = scaled.signum() < 0;
        final String digits = leftPadZero(scaled.abs().unscaledValue().toString(), width);
        if (digits.length() > width) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " exceeds the " + width + "-digit S9(09)V99 capacity");
        }
        final String head = digits.substring(0, width - 1);
        final int lastDigit = digits.charAt(width - 1) - '0';
        final char overpunch;
        if (negative) {
            overpunch = (lastDigit == 0) ? '}' : (char) ('J' + lastDigit - 1);
        } else {
            overpunch = (lastDigit == 0) ? '{' : (char) ('A' + lastDigit - 1);
        }
        return head + overpunch;
    }

    /**
     * Left-pads a digit string with {@code '0'} up to the given width; strings already at or beyond
     * the width are returned unchanged.
     *
     * @param digits the digit string
     * @param width  the desired minimum width
     * @return the zero-padded string
     */
    private static String leftPadZero(final String digits, final int width) {
        if (digits.length() >= width) {
            return digits;
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Step listener that counts rejected records and maps the COBOL condition code
     * ({@code IF WS-REJECT-COUNT &gt; 0 MOVE 4 TO RETURN-CODE}) onto a custom exit status.
     *
     * <p>The composite writer's reject adapter advances {@link #addRejects(long)} as it routes
     * rejects; {@link #beforeStep(StepExecution)} resets the counter so a re-run starts clean, and
     * {@link #afterStep(StepExecution)} returns the {@value #COMPLETED_WITH_REJECTS} exit status when
     * the step completed with at least one reject (leaving the batch status {@code COMPLETED} so the
     * pipeline may continue). A non-successful step preserves its framework-assigned exit status.
     * Single-threaded chunk execution is assumed (COBOL parity); the {@link AtomicLong} keeps the
     * count safe and unambiguous regardless.</p>
     */
    static final class RejectCountStepListener implements StepExecutionListener {

        /** Per-run count of rejected daily transactions ({@code WS-REJECT-COUNT}). */
        private final AtomicLong rejectCount = new AtomicLong();

        /**
         * Advances the reject count by the number of rejects routed in a chunk.
         *
         * @param delta the number of rejects to add (the rejected adapter's chunk size)
         */
        void addRejects(final long delta) {
            this.rejectCount.addAndGet(delta);
        }

        /**
         * Resets the reject count at the start of the step so a restart or re-run begins clean.
         *
         * @param stepExecution the current step execution (not inspected)
         */
        @Override
        public void beforeStep(final StepExecution stepExecution) {
            this.rejectCount.set(0L);
        }

        /**
         * Returns the {@value #COMPLETED_WITH_REJECTS} exit status when the step completed with at
         * least one reject (COBOL RC=4); otherwise preserves the framework-assigned exit status.
         *
         * @param stepExecution the completed step execution
         * @return the reject-aware exit status, or the existing exit status when there were no
         *         rejects or the step did not complete successfully
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            if (stepExecution.getStatus() == BatchStatus.COMPLETED && this.rejectCount.get() > 0L) {
                return new ExitStatus(COMPLETED_WITH_REJECTS);
            }
            return stepExecution.getExitStatus();
        }
    }
}
