package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.TransactionPostingProcessor;
import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.batch.readers.DailyTransactionReader;
import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.model.entity.DailyTransaction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
 * Spring Batch re-host of the mainframe daily-transaction posting job
 * {@code app/jcl/POSTTRAN.jcl} (single step {@code EXEC PGM=CBTRN02C}) and COBOL program
 * {@code app/cbl/CBTRN02C.cbl} (source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no
 * COBOL/JCL is copied).
 *
 * <p>The COBOL main loop (PROCEDURE DIVISION) read each {@code DALYTRAN} record, validated it
 * ({@code 1500-VALIDATE-TRAN}) and then either posted it ({@code 2000-POST-TRANSACTION}
 * &rarr; {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC},
 * {@code 2900-WRITE-TRANSACTION-FILE}) or wrote a reject record
 * ({@code 2500-WRITE-REJECT-REC}). This configuration wires that behaviour as a
 * chunk-oriented step over sibling {@code @Component} beans: a {@link DailyTransactionReader}
 * (350-byte {@code CVTRA06Y} input), a {@link TransactionPostingProcessor} (the in-memory
 * 4-stage validation cascade producing a typed {@link PostingResult}), and a
 * {@link ClassifierCompositeItemWriter} that routes each result on
 * {@link PostingResult#rejected()} to either the {@link TransactionWriter} (persist
 * transaction + category-balance upsert + account update) or the {@link RejectWriter}
 * (byte-exact 430-byte reject record to S3, {@code LRECL=430}).</p>
 *
 * <p>The three posting effects share the chunk-oriented step transaction, the Java equivalent
 * of the single COBOL logical unit of work. The job is a {@link Job} bean only and is never
 * auto-run at boot ({@code spring.batch.job.enabled=false}); a pipeline orchestrator launches
 * it. Following {@code CBTRN02C}'s {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}, the
 * step still completes when records are rejected but its exit status becomes
 * {@link #COMPLETED_WITH_REJECTS} (the {@code RC=4} warning equivalent) so downstream pipeline
 * stages may proceed where their condition permits.</p>
 *
 * <p><b>Integration seam.</b> {@code 2500-WRITE-REJECT-REC} copies the original 350-byte
 * {@code DALYTRAN-RECORD} verbatim into the reject record. The parsed {@link DailyTransaction}
 * carried by {@link PostingResult#originalDailyTransaction()} does not retain that raw image,
 * so the rejected-branch adapter re-serializes it to the 350-byte {@code CVTRA06Y} fixed-width
 * layout (matching {@link DailyTransactionReader}'s field boundaries and the writers'
 * zoned-decimal trailing-sign overpunch encoding) before handing it to {@link RejectWriter}.</p>
 */
@Configuration(value = "dailyTransactionPostingJobConfig", proxyBeanMethods = false)
public class DailyTransactionPostingJob {

    /** Canonical job name; referenced by the pipeline orchestrator to launch this job. */
    public static final String JOB_NAME = "dailyTransactionPostingJob";

    /** Name of the single chunk-oriented posting step (POSTTRAN.jcl {@code STEP15}). */
    public static final String STEP_NAME = "dailyTransactionPostingStep";

    /**
     * Custom step/job exit code emitted when at least one transaction was rejected, mirroring
     * the COBOL {@code MOVE 4 TO RETURN-CODE} warning while the batch status stays
     * {@code COMPLETED}. The pipeline orchestrator's {@code JobExecutionDecider} keys off this
     * code to honour the RC=4-but-continue semantics (AAP §0.8.5).
     */
    public static final String COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /**
     * Number of records processed per chunk (and therefore per step-transaction commit). The
     * COBOL program committed once at end of job; a bounded chunk keeps memory and transaction
     * size predictable while preserving the per-record posting semantics.
     */
    private static final int CHUNK_SIZE = 100;

    private final DailyTransactionReader dailyTransactionReader;
    private final TransactionPostingProcessor transactionPostingProcessor;
    private final TransactionWriter transactionWriter;
    private final RejectWriter rejectWriter;

    /**
     * Creates the posting job configuration, injecting the sibling reader, processor, and the two
     * writers it wires into the step. The reader is {@code @StepScope}; Spring injects a scoped
     * proxy here that resolves to the per-execution instance when the step runs.
     *
     * @param dailyTransactionReader      the 350-byte fixed-width daily-transaction reader
     * @param transactionPostingProcessor the validation/posting processor emitting {@link PostingResult}
     * @param transactionWriter           the writer that persists posted transactions
     * @param rejectWriter                the writer that emits 430-byte reject records to S3
     */
    public DailyTransactionPostingJob(
            DailyTransactionReader dailyTransactionReader,
            TransactionPostingProcessor transactionPostingProcessor,
            TransactionWriter transactionWriter,
            RejectWriter rejectWriter) {
        this.dailyTransactionReader = dailyTransactionReader;
        this.transactionPostingProcessor = transactionPostingProcessor;
        this.transactionWriter = transactionWriter;
        this.rejectWriter = rejectWriter;
    }

    /**
     * The rejected-branch routing writer, exposed as a bean so the same instance is shared by the
     * {@link #dailyTransactionPostingResultWriter(RejectRoutingWriter) classifier writer} (as the
     * reject delegate) and by the {@link #dailyTransactionPostingStep step} (as the
     * reject-counting {@link StepExecutionListener}). Sharing one instance keeps the reject tally
     * that drives the {@link #COMPLETED_WITH_REJECTS} exit status consistent.
     *
     * @return the reject routing writer wrapping the injected {@link RejectWriter}
     */
    @Bean
    public RejectRoutingWriter dailyTransactionRejectRoutingWriter() {
        return new RejectRoutingWriter(rejectWriter);
    }

    /**
     * The step's composite writer. It routes each {@link PostingResult} on
     * {@link PostingResult#rejected()}: posted results go to an adapter that forwards them
     * directly to {@link TransactionWriter} (which consumes {@link PostingResult});
     * rejected results go to the supplied {@link RejectRoutingWriter}. A lambda classifier is
     * used deliberately because {@link Classifier} extends {@link java.io.Serializable} and a
     * named implementation would require explicit serialization handling.
     *
     * @param dailyTransactionRejectRoutingWriter the shared reject routing writer (reject delegate)
     * @return the classifier-composite writer over {@link PostingResult}
     */
    @Bean
    public ClassifierCompositeItemWriter<PostingResult> dailyTransactionPostingResultWriter(
            RejectRoutingWriter dailyTransactionRejectRoutingWriter) {
        ItemWriter<PostingResult> postedDelegate = postedTransactionDelegate();
        ItemWriter<PostingResult> rejectDelegate = dailyTransactionRejectRoutingWriter;
        Classifier<PostingResult, ItemWriter<? super PostingResult>> classifier =
                result -> result.rejected() ? rejectDelegate : postedDelegate;
        ClassifierCompositeItemWriter<PostingResult> writer = new ClassifierCompositeItemWriter<>();
        writer.setClassifier(classifier);
        return writer;
    }

    /**
     * The single chunk-oriented posting step ({@code <DailyTransaction, PostingResult>}). Both
     * {@link TransactionWriter} and {@link RejectWriter} are {@code ItemStreamWriter}s registered
     * as streams so their {@code open}/{@code update}/{@code close} lifecycle fires (the
     * classifier writer does not propagate streams to its delegates, so without this their S3
     * buffers would never flush). The {@link RejectRoutingWriter} is registered as the step
     * listener that maps the reject tally onto the {@link #COMPLETED_WITH_REJECTS} exit status.
     *
     * @param jobRepository                        the Spring Batch job repository (Boot-auto-configured)
     * @param transactionManager                   the platform transaction manager (Boot-auto-configured)
     * @param dailyTransactionPostingResultWriter  the classifier-composite writer over {@link PostingResult}
     * @param dailyTransactionRejectRoutingWriter  the shared reject routing writer (step listener)
     * @return the configured posting step
     */
    @Bean
    public Step dailyTransactionPostingStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ClassifierCompositeItemWriter<PostingResult> dailyTransactionPostingResultWriter,
            RejectRoutingWriter dailyTransactionRejectRoutingWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostingResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionReader)
                .processor(transactionPostingProcessor)
                .writer(dailyTransactionPostingResultWriter)
                .stream(transactionWriter)
                .stream(rejectWriter)
                .listener(dailyTransactionRejectRoutingWriter)
                .build();
    }

    /**
     * The daily-transaction posting job: a single-step job starting at
     * {@link #dailyTransactionPostingStep}. Registered under {@link #JOB_NAME}; not auto-run
     * ({@code spring.batch.job.enabled=false}).
     *
     * @param jobRepository               the Spring Batch job repository (Boot-auto-configured)
     * @param dailyTransactionPostingStep the posting step bean (injected by name)
     * @return the configured posting job
     */
    @Bean
    public Job dailyTransactionPostingJob(
            JobRepository jobRepository, Step dailyTransactionPostingStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(dailyTransactionPostingStep)
                .build();
    }

    /**
     * Builds the posted-branch adapter: an {@link ItemWriter} over {@link PostingResult} that
     * forwards each accepted result's chunk directly to the injected {@link TransactionWriter},
     * which consumes {@link PostingResult} and persists the processor's precomputed posted
     * transaction, versioned account, and category balance exactly once. The amount and all
     * monetary state stay {@link BigDecimal}. The classifier routes only accepted results onto
     * this delegate, so the writer's own reject branch is never exercised on this path.
     *
     * @return the posted-branch item writer delegating to {@link TransactionWriter}
     */
    private ItemWriter<PostingResult> postedTransactionDelegate() {
        return transactionWriter::write;
    }

    /**
     * Rejected-branch adapter and reject-counting step listener in one cohesive unit. As an
     * {@link ItemWriter} it maps each rejected {@link PostingResult} onto a
     * {@link RejectWriter.RejectedTransaction} &mdash; re-serializing the original
     * {@link DailyTransaction} to its 350-byte {@code CVTRA06Y} image (the integration seam) and
     * carrying the numeric reject code and description &mdash; then delegates the chunk to the
     * wrapped {@link RejectWriter}, tallying the rejects. As a {@link StepExecutionListener} it
     * resets that tally at step start and, at step end, returns
     * {@link DailyTransactionPostingJob#COMPLETED_WITH_REJECTS} when any record was rejected
     * (otherwise {@link ExitStatus#COMPLETED}), reproducing {@code CBTRN02C}'s
     * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}. The {@link RejectWriter}'s own
     * stream lifecycle (and S3 flush) is driven by its step-stream registration, not by this
     * adapter.
     */
    public static final class RejectRoutingWriter
            implements ItemWriter<PostingResult>, StepExecutionListener {

        /** Total fixed length of a {@code CVTRA06Y} daily-transaction record, in bytes. */
        private static final int RECORD_LENGTH = 350;

        /** Implied decimal positions of the {@code DALYTRAN-AMT} field ({@code PIC S9(09)V99}). */
        private static final int AMOUNT_SCALE = 2;

        /** Byte length of the zoned-decimal {@code DALYTRAN-AMT S9(09)V99} field. */
        private static final int AMOUNT_FIELD_LENGTH = 11;

        /**
         * Trailing-overpunch characters for a non-negative units digit, indexed by
         * {@code digit - '0'}: digit 0 maps to the open-brace character and digits 1 through 9 map
         * to the letters A through I (the ASCII zoned-decimal representation).
         */
        private static final char[] POSITIVE_OVERPUNCH =
                {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

        /**
         * Trailing-overpunch characters for a negative units digit, indexed by {@code digit - '0'}:
         * digit 0 maps to the close-brace character and digits 1 through 9 map to the letters J
         * through R.
         */
        private static final char[] NEGATIVE_OVERPUNCH =
                {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

        private final RejectWriter delegate;

        /** Per-step reject tally; reset in {@link #beforeStep(StepExecution)}. */
        private final AtomicLong rejectCount = new AtomicLong();

        /**
         * Creates the routing writer.
         *
         * @param delegate the wrapped reject writer that emits the 430-byte S3 reject records
         */
        public RejectRoutingWriter(RejectWriter delegate) {
            this.delegate = delegate;
        }

        /**
         * Resets the reject tally at the start of each step execution so repeated job runs (for
         * example across tests) start from zero.
         *
         * @param stepExecution the starting step execution
         */
        @Override
        public void beforeStep(StepExecution stepExecution) {
            rejectCount.set(0L);
        }

        /**
         * Maps each rejected {@link PostingResult} onto a {@link RejectWriter.RejectedTransaction}
         * (350-byte re-serialized record + numeric code + description), delegates the chunk to the
         * wrapped {@link RejectWriter}, and adds the chunk size to the reject tally.
         *
         * @param chunk the chunk of rejected posting results
         */
        @Override
        public void write(Chunk<? extends PostingResult> chunk) {
            List<RejectWriter.RejectedTransaction> mapped = new ArrayList<>(chunk.size());
            for (PostingResult result : chunk) {
                mapped.add(new RejectWriter.RejectedTransaction(
                        serializeCvtra06y(result.originalDailyTransaction()),
                        result.rejectCode(),
                        result.rejectReasonDescription()));
            }
            delegate.write(new Chunk<>(mapped));
            rejectCount.addAndGet(mapped.size());
        }

        /**
         * Maps the reject tally onto the step exit status: {@link #COMPLETED_WITH_REJECTS} when at
         * least one record was rejected (the COBOL {@code RC=4} warning), otherwise
         * {@link ExitStatus#COMPLETED}. The batch status itself stays {@code COMPLETED}.
         *
         * @param stepExecution the finishing step execution
         * @return the resolved exit status
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            return rejectCount.get() > 0L
                    ? new ExitStatus(COMPLETED_WITH_REJECTS)
                    : ExitStatus.COMPLETED;
        }

        /**
         * Re-serializes a {@link DailyTransaction} to its byte-exact 350-byte {@code CVTRA06Y}
         * fixed-width image (the {@code 2500-WRITE-REJECT-REC} {@code REJECT-TRAN-DATA} payload).
         * The buffer is space-filled first, so any short field is right-padded with spaces and any
         * over-long field is truncated to its fixed width; the trailing 20-byte filler stays
         * spaces. All bytes use {@link StandardCharsets#ISO_8859_1} (one byte per character) to
         * preserve the fixed-width offsets, matching the reader and the sibling writers.
         *
         * @param tran the rejected daily transaction
         * @return the 350-character ISO-8859-1 record image
         */
        private static String serializeCvtra06y(DailyTransaction tran) {
            byte[] out = new byte[RECORD_LENGTH];
            Arrays.fill(out, (byte) ' ');

            putAlpha(out, 0, 16, tran.getDalytranId());
            putAlpha(out, 16, 2, tran.getDalytranTypeCd());
            putNum(out, 18, 4, tran.getDalytranCatCd() == null ? 0L : tran.getDalytranCatCd());
            putAlpha(out, 22, 10, tran.getDalytranSource());
            putAlpha(out, 32, 100, tran.getDalytranDesc());
            putSignedZoned(out, 132,
                    tran.getDalytranAmt() == null ? BigDecimal.ZERO : tran.getDalytranAmt());
            putNum(out, 143, 9,
                    tran.getDalytranMerchantId() == null ? 0L : tran.getDalytranMerchantId());
            putAlpha(out, 152, 50, tran.getDalytranMerchantName());
            putAlpha(out, 202, 50, tran.getDalytranMerchantCity());
            putAlpha(out, 252, 10, tran.getDalytranMerchantZip());
            putAlpha(out, 262, 16, tran.getDalytranCardNum());
            putAlpha(out, 278, 26, tran.getDalytranOrigTs());
            putAlpha(out, 304, 26, tran.getDalytranProcTs());
            // FILLER (offset 330, length 20) remains spaces.

            return new String(out, StandardCharsets.ISO_8859_1);
        }

        /**
         * Writes an alphanumeric value left-justified into a fixed-width slot, truncating to the
         * slot length and leaving any unused trailing bytes as the pre-filled spaces.
         *
         * @param out    the record buffer being assembled
         * @param offset the start offset of the slot
         * @param len    the fixed width of the slot
         * @param value  the value to write; {@code null} is treated as empty
         */
        private static void putAlpha(byte[] out, int offset, int len, String value) {
            String safe = value == null ? "" : value;
            byte[] bytes = safe.getBytes(StandardCharsets.ISO_8859_1);
            int n = Math.min(len, bytes.length);
            System.arraycopy(bytes, 0, out, offset, n);
        }

        /**
         * Writes an unsigned numeric value as a zero-padded, right-justified field, keeping only
         * the low-order {@code len} digits if the formatted value is longer than the slot.
         *
         * @param out    the record buffer being assembled
         * @param offset the start offset of the slot
         * @param len    the fixed width of the slot
         * @param value  the numeric value to write
         */
        private static void putNum(byte[] out, int offset, int len, long value) {
            String formatted = String.format("%0" + len + "d", value);
            if (formatted.length() > len) {
                formatted = formatted.substring(formatted.length() - len);
            }
            byte[] bytes = formatted.getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(bytes, 0, out, offset, len);
        }

        /**
         * Writes a signed {@code S9(09)V99} value as an 11-byte zoned-decimal field with a
         * trailing sign overpunch on the units digit, the byte-exact COBOL {@code DISPLAY}
         * representation. The value is scaled to two fraction digits
         * ({@link RoundingMode#HALF_EVEN}), reduced to its eleven low-order unscaled digits, and
         * the final digit is replaced by the overpunch character for its sign.
         *
         * @param out    the record buffer being assembled
         * @param offset the start offset of the 11-byte slot
         * @param amount the signed monetary amount to encode
         */
        private static void putSignedZoned(byte[] out, int offset, BigDecimal amount) {
            BigDecimal scaled = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
            BigInteger unscaledAbs = scaled.abs().movePointRight(AMOUNT_SCALE).toBigInteger();
            String digits = String.format("%0" + AMOUNT_FIELD_LENGTH + "d", unscaledAbs);
            if (digits.length() > AMOUNT_FIELD_LENGTH) {
                digits = digits.substring(digits.length() - AMOUNT_FIELD_LENGTH);
            }
            String head = digits.substring(0, AMOUNT_FIELD_LENGTH - 1);
            int unitsDigit = digits.charAt(AMOUNT_FIELD_LENGTH - 1) - '0';
            char overpunched = scaled.signum() < 0
                    ? NEGATIVE_OVERPUNCH[unitsDigit]
                    : POSITIVE_OVERPUNCH[unitsDigit];
            byte[] bytes = (head + overpunched).getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(bytes, 0, out, offset, bytes.length);
        }
    }
}
