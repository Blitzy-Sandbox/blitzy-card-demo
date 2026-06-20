package com.carddemo.batch.writers;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.TransactionPostingException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Spring Batch {@link ItemStreamWriter} (label <em>DB + S3</em>) that posts each validated daily
 * transaction to PostgreSQL and, optionally, stages the posted transactions to Amazon S3
 * (LocalStack-compatible) as a byte-exact fixed-width file.
 *
 * <p>Translated (logic only, never source text; traceability via commit {@code 27d6c6f}) from the
 * COBOL daily-transaction posting program {@code app/cbl/CBTRN02C.cbl}. Each item drives the three
 * posting paragraphs of that program, executed per item:</p>
 * <ul>
 *   <li>{@code 2900-WRITE-TRANSACTION-FILE} &mdash; persists the posted {@link Transaction} (COBOL
 *       {@code TRAN-RECORD}, copybook {@code app/cpy/CVTRA05Y.cpy}, RECLN 350) to the transaction
 *       table.</li>
 *   <li>{@code 2700-UPDATE-TCATBAL} &mdash; upserts the {@link TransactionCategoryBalance} (COBOL
 *       {@code TRAN-CAT-BAL-RECORD}, copybook {@code app/cpy/CVTRA01Y.cpy}) by its three-part key
 *       (account id + transaction-type code + category code): an absent balance is created with the
 *       transaction amount; an existing balance is incremented by the transaction amount.</li>
 *   <li>{@code 2800-UPDATE-ACCOUNT-REC} &mdash; updates the {@link Account} (COBOL
 *       {@code ACCOUNT-RECORD}, copybook {@code app/cpy/CVACT01Y.cpy}) current balance and the
 *       current-cycle credit or debit total; a non-negative amount accrues to the cycle credit and a
 *       negative amount to the cycle debit. An account that cannot be found at posting time yields
 *       reject code {@code 109} ({@code ACCOUNT RECORD NOT FOUND}).</li>
 * </ul>
 *
 * <h2>Transaction and concurrency model</h2>
 * <p>The chunk-oriented step wraps read &rarr; process &rarr; write in a single transaction owned by
 * the step's {@code PlatformTransactionManager}; this writer therefore declares no transaction
 * boundary of its own. Any exception thrown from {@link #write(Chunk)} rolls back the entire chunk,
 * which is the faithful replacement for the CICS {@code SYNCPOINT} / batch-commit semantics of the
 * source program. Optimistic concurrency is enforced by the {@code @Version} field on
 * {@link Account}: the account update is flushed eagerly so a stale-version conflict surfaces
 * deterministically as an {@link OptimisticLockingFailureException}, which is rethrown as a
 * {@link ConcurrencyException}.</p>
 *
 * <h2>Optional S3 staging (GDG &rarr; S3)</h2>
 * <p>The mainframe wrote posted transactions to a generation data group ({@code DEFGDGB.jcl} GDG base
 * {@code SYSTRAN}). When {@code carddemo.batch.posting.stage-to-s3} is enabled (the default), each
 * posted transaction is appended &mdash; as a byte-exact 350-byte {@code TRAN-RECORD} with no
 * delimiters &mdash; to a per-run buffer that {@link #close()} flushes to the output bucket under the
 * key {@value #STAGE_OBJECT_KEY}. Because the bucket is versioned, every run produces a new object
 * version, the relational/cloud equivalent of a new GDG generation. All bytes use
 * {@link StandardCharsets#ISO_8859_1}, a single-byte-per-character charset that preserves fixed-width
 * fidelity.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Single-threaded, sequential chunk execution is assumed (COBOL parity). The staging buffer is
 * allocated in {@link #open(ExecutionContext)} (only when staging is enabled), appended to in
 * {@link #write(Chunk)}, and flushed once in {@link #close()}; when no records were staged nothing is
 * written, faithful to the source program not creating an empty generation.</p>
 */
@Component
public class TransactionWriter implements ItemStreamWriter<TransactionWriter.PostedTransaction> {

    /** S3 object key for the staged posted-transaction file (GDG base {@code SYSTRAN}). */
    private static final String STAGE_OBJECT_KEY = "SYSTRAN";

    /** Fixed length of a {@code TRAN-RECORD} ({@code app/cpy/CVTRA05Y.cpy}, RECLN 350). */
    private static final int TRAN_RECORD_LENGTH = 350;

    /**
     * Trailing-overpunch characters for a non-negative zoned-decimal units digit, indexed by the
     * digit value {@code 0..9}: {@code 0} maps to <code>'{'</code> and {@code 1..9} map to
     * {@code 'A'..'I'}.
     */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /**
     * Trailing-overpunch characters for a negative zoned-decimal units digit, indexed by the digit
     * value {@code 0..9}: {@code 0} maps to <code>'}'</code> and {@code 1..9} map to
     * {@code 'J'..'R'}.
     */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    /** Repository for the posted {@link Transaction} (COBOL {@code 2900-WRITE-TRANSACTION-FILE}). */
    private final TransactionRepository transactionRepository;

    /** Repository for the {@link TransactionCategoryBalance} (COBOL {@code 2700-UPDATE-TCATBAL}). */
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    /** Repository for the {@link Account} (COBOL {@code 2800-UPDATE-ACCOUNT-REC}). */
    private final AccountRepository accountRepository;

    /** Synchronous S3 client (bean type provided by {@code com.carddemo.config.AwsConfig}). */
    private final S3Client s3Client;

    /** Micrometer registry used to resolve the records-processed counter. */
    private final MeterRegistry meterRegistry;

    /** Holder of the signed transaction-amount running total (gauge-backed). */
    private final MetricsConfig metricsConfig;

    /** Target S3 bucket for batch output (staged posted-transaction object). */
    private final String outputBucket;

    /** Whether posted transactions are staged to S3; gates all staging behaviour. */
    private final boolean stageToS3;

    /**
     * Per-run accumulation buffer for the staged file. Allocated in {@link #open(ExecutionContext)}
     * (only when {@link #stageToS3} is enabled) and released in {@link #close()}; intentionally
     * non-final mutable per-step state, {@code null} when staging is disabled.
     */
    private ByteArrayOutputStream stageBuffer;

    /**
     * Creates the transaction writer with its collaborators injected by the Spring container.
     *
     * @param transactionRepository     repository used to persist the posted transaction
     * @param categoryBalanceRepository repository used to upsert the transaction-category balance
     * @param accountRepository         repository used to update the owning account
     * @param s3Client                  synchronous S3 client used to stage posted transactions;
     *                                  endpoint, region, and credentials are never hardcoded here
     * @param meterRegistry             Micrometer registry used to resolve the records-processed
     *                                  counter
     * @param metricsConfig             holder of the signed transaction-amount running total
     * @param outputBucket              S3 output bucket name, resolved from
     *                                  {@code carddemo.aws.s3.bucket-output} (default
     *                                  {@code carddemo-batch-output})
     * @param stageToS3                 whether to stage posted transactions to S3, resolved from
     *                                  {@code carddemo.batch.posting.stage-to-s3} (default
     *                                  {@code true})
     */
    public TransactionWriter(
            TransactionRepository transactionRepository,
            TransactionCategoryBalanceRepository categoryBalanceRepository,
            AccountRepository accountRepository,
            S3Client s3Client,
            MeterRegistry meterRegistry,
            MetricsConfig metricsConfig,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") String outputBucket,
            @Value("${carddemo.batch.posting.stage-to-s3:true}") boolean stageToS3) {
        this.transactionRepository = transactionRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.accountRepository = accountRepository;
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.metricsConfig = metricsConfig;
        this.outputBucket = outputBucket;
        this.stageToS3 = stageToS3;
    }

    /**
     * Allocates a fresh staging buffer when S3 staging is enabled so that exactly one object is
     * produced per run; when staging is disabled the buffer is left {@code null} and the stream
     * methods are effectively no-ops for S3.
     *
     * @param executionContext the Spring Batch execution context (not used; no state is restored)
     */
    @Override
    public void open(ExecutionContext executionContext) {
        if (stageToS3) {
            this.stageBuffer = new ByteArrayOutputStream();
        } else {
            this.stageBuffer = null;
        }
    }

    /**
     * No-op: this writer persists no incremental state to the execution context (the whole run is
     * flushed once in {@link #close()}).
     *
     * @param executionContext the Spring Batch execution context (not used)
     */
    @Override
    public void update(ExecutionContext executionContext) {
        // no incremental state
    }

    /**
     * Posts each transaction in the chunk to PostgreSQL (and stages it to the S3 buffer when
     * enabled). Any exception propagates to roll back the entire chunk.
     *
     * @param chunk the chunk of posted transactions supplied by the step
     */
    @Override
    public void write(Chunk<? extends PostedTransaction> chunk) {
        for (PostedTransaction item : chunk) {
            post(item);
        }
    }

    /**
     * Executes the COBOL posting sequence for a single item in order: persist the transaction
     * ({@code 2900}), upsert the transaction-category balance ({@code 2700}), update the account
     * ({@code 2800}), record telemetry, and optionally append the staged record.
     *
     * @param item the posted transaction and its owning account id
     */
    private void post(PostedTransaction item) {
        Transaction transaction = item.transaction();
        Long accountId = item.accountId();
        BigDecimal amount = transaction.getTranAmt();

        // 2900-WRITE-TRANSACTION-FILE: persist the posted transaction.
        transactionRepository.save(transaction);

        // 2700-UPDATE-TCATBAL: upsert the category balance by its three-part key.
        TransactionCategoryBalanceId balanceId = new TransactionCategoryBalanceId(
                accountId, transaction.getTranTypeCd(), transaction.getTranCatCd());
        TransactionCategoryBalance balance = categoryBalanceRepository.findById(balanceId).orElse(null);
        if (balance == null) {
            balance = new TransactionCategoryBalance();
            balance.setId(balanceId);
            balance.setTranCatBal(amount);
        } else {
            balance.setTranCatBal(balance.getTranCatBal().add(amount));
        }
        categoryBalanceRepository.save(balance);

        // 2800-UPDATE-ACCOUNT-REC: update the owning account balance and cycle totals.
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new TransactionPostingException(109, "ACCOUNT RECORD NOT FOUND"));
        account.setAcctCurrBal(account.getAcctCurrBal().add(amount));
        if (amount.signum() >= 0) {
            account.setAcctCurrCycCredit(account.getAcctCurrCycCredit().add(amount));
        } else {
            account.setAcctCurrCycDebit(account.getAcctCurrCycDebit().add(amount));
        }
        try {
            accountRepository.saveAndFlush(account);
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrencyException(
                    "Optimistic lock conflict updating account " + accountId, "Account", e);
        }

        // Telemetry: count the posted record and add its (signed) amount to the running total.
        MetricsConfig.recordsProcessed(meterRegistry).increment();
        metricsConfig.addTransactionAmount(amount);

        // Optional S3 staging: append the byte-exact 350-byte TRAN-RECORD with no delimiter.
        if (stageToS3 && stageBuffer != null) {
            stageBuffer.writeBytes(buildTranRecord(transaction));
        }
    }

    /**
     * Flushes the accumulated posted-transaction records to S3 as a single object. When staging is
     * disabled or no records were accumulated, nothing is written (faithful to the source program
     * not creating an empty generation). The buffer is always released afterwards.
     *
     * @throws FileAccessException if the S3 upload fails
     */
    @Override
    public void close() {
        if (!stageToS3 || stageBuffer == null || stageBuffer.size() == 0) {
            return;
        }
        byte[] payload = stageBuffer.toByteArray();
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(outputBucket)
                            .key(STAGE_OBJECT_KEY)
                            .contentType("application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(payload));
        } catch (SdkException e) {
            throw new FileAccessException(
                    "Failed to stage posted transactions to S3 " + outputBucket + "/" + STAGE_OBJECT_KEY, e);
        } finally {
            this.stageBuffer = null;
        }
    }

    /**
     * Builds the byte-exact 350-byte {@code TRAN-RECORD} for a single posted transaction
     * ({@code app/cpy/CVTRA05Y.cpy}).
     *
     * <p>Layout (all offsets zero-based; total {@value #TRAN_RECORD_LENGTH} bytes):</p>
     * <ul>
     *   <li>offset 0, length 16 &mdash; {@code TRAN-ID} {@code X(16)}, left-justified.</li>
     *   <li>offset 16, length 2 &mdash; {@code TRAN-TYPE-CD} {@code X(02)}, left-justified.</li>
     *   <li>offset 18, length 4 &mdash; {@code TRAN-CAT-CD} {@code 9(04)}, zero-padded numeric.</li>
     *   <li>offset 22, length 10 &mdash; {@code TRAN-SOURCE} {@code X(10)}, left-justified.</li>
     *   <li>offset 32, length 100 &mdash; {@code TRAN-DESC} {@code X(100)}, left-justified.</li>
     *   <li>offset 132, length 11 &mdash; {@code TRAN-AMT} {@code S9(09)V99}, zoned-decimal with
     *       trailing overpunch, scale 2.</li>
     *   <li>offset 143, length 9 &mdash; {@code TRAN-MERCHANT-ID} {@code 9(09)}, zero-padded.</li>
     *   <li>offset 152, length 50 &mdash; {@code TRAN-MERCHANT-NAME} {@code X(50)}, left-justified.</li>
     *   <li>offset 202, length 50 &mdash; {@code TRAN-MERCHANT-CITY} {@code X(50)}, left-justified.</li>
     *   <li>offset 252, length 10 &mdash; {@code TRAN-MERCHANT-ZIP} {@code X(10)}, left-justified.</li>
     *   <li>offset 262, length 16 &mdash; {@code TRAN-CARD-NUM} {@code X(16)}, left-justified.</li>
     *   <li>offset 278, length 26 &mdash; {@code TRAN-ORIG-TS} {@code X(26)}, left-justified.</li>
     *   <li>offset 304, length 26 &mdash; {@code TRAN-PROC-TS} {@code X(26)}, left-justified.</li>
     *   <li>offset 330, length 20 &mdash; {@code FILLER} {@code X(20)}, spaces.</li>
     * </ul>
     *
     * @param t the posted transaction whose fields populate the record
     * @return a byte array of exactly {@value #TRAN_RECORD_LENGTH} bytes
     */
    private static byte[] buildTranRecord(Transaction t) {
        byte[] out = new byte[TRAN_RECORD_LENGTH];
        // Space-fill first so every short alphanumeric field is right-padded with blanks, and the
        // trailing FILLER (offset 330, length 20) is left as spaces.
        Arrays.fill(out, (byte) ' ');

        putAlpha(out, 0, 16, t.getTranId());
        putAlpha(out, 16, 2, t.getTranTypeCd());
        putNum(out, 18, 4, t.getTranCatCd());
        putAlpha(out, 22, 10, t.getTranSource());
        putAlpha(out, 32, 100, t.getTranDesc());
        putSignedZoned(out, 132, t.getTranAmt());
        putNum(out, 143, 9, t.getTranMerchantId());
        putAlpha(out, 152, 50, t.getTranMerchantName());
        putAlpha(out, 202, 50, t.getTranMerchantCity());
        putAlpha(out, 252, 10, t.getTranMerchantZip());
        putAlpha(out, 262, 16, t.getTranCardNum());
        putAlpha(out, 278, 26, t.getTranOrigTs());
        putAlpha(out, 304, 26, t.getTranProcTs());

        return out;
    }

    /**
     * Writes an alphanumeric field left-justified at the given offset: copies up to {@code len} bytes
     * of the {@code ISO_8859_1} encoding of {@code value}, leaving any remaining positions as the
     * space fill already present. A {@code null} value writes nothing (the field stays blank).
     *
     * @param out    the record buffer being populated
     * @param offset the zero-based start position of the field
     * @param len    the fixed field width in bytes
     * @param value  the field value, or {@code null} for an all-blank field
     */
    private static void putAlpha(byte[] out, int offset, int len, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.ISO_8859_1);
        int n = Math.min(len, bytes.length);
        System.arraycopy(bytes, 0, out, offset, n);
    }

    /**
     * Writes an unsigned numeric field zero-padded to {@code len} digits at the given offset. When
     * the formatted value exceeds {@code len} digits, the low-order {@code len} digits are retained
     * (matching the COBOL truncation of an oversized numeric move).
     *
     * @param out    the record buffer being populated
     * @param offset the zero-based start position of the field
     * @param len    the fixed field width in digits
     * @param value  the numeric value to format
     */
    private static void putNum(byte[] out, int offset, int len, long value) {
        String digits = String.format("%0" + len + "d", value);
        if (digits.length() > len) {
            digits = digits.substring(digits.length() - len);
        }
        byte[] bytes = digits.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, out, offset, len);
    }

    /**
     * Writes an {@code S9(09)V99} signed amount as an 11-byte zoned-decimal field with a trailing
     * sign overpunch on the units digit, at the given offset.
     *
     * <p>The amount is rounded to scale 2 ({@link RoundingMode#HALF_EVEN}), converted to its
     * unscaled cents magnitude, and formatted as eleven digits (low-order eleven retained if
     * longer). The first ten digits are written verbatim; the units digit is replaced by its
     * overpunch character drawn from {@link #POSITIVE_OVERPUNCH} when the amount is non-negative or
     * {@link #NEGATIVE_OVERPUNCH} when it is negative.</p>
     *
     * @param out    the record buffer being populated
     * @param offset the zero-based start position of the 11-byte field
     * @param amount the signed amount to encode
     */
    private static void putSignedZoned(byte[] out, int offset, BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_EVEN);
        BigInteger unscaledAbs = scaled.abs().movePointRight(2).toBigInteger();
        String digits = String.format("%011d", unscaledAbs);
        if (digits.length() > 11) {
            digits = digits.substring(digits.length() - 11);
        }
        String head = digits.substring(0, 10);
        int unitsDigit = digits.charAt(10) - '0';
        char[] table = scaled.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        String field = head + table[unitsDigit];
        byte[] bytes = field.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, out, offset, 11);
    }

    /**
     * Input contract for {@link TransactionWriter}: a fully-populated transaction together with the
     * id of its owning account.
     *
     * <p>The account id is carried separately because the {@link Transaction} entity has no account
     * column; it is resolved upstream from the card cross-reference (COBOL {@code XREF-ACCT-ID},
     * paragraph {@code 1500-A-LOOKUP-XREF}) and is required here for the transaction-category-balance
     * composite key and the account update.</p>
     *
     * @param transaction the posted transaction to persist (COBOL {@code TRAN-RECORD})
     * @param accountId   the owning account id (COBOL {@code XREF-ACCT-ID})
     */
    public record PostedTransaction(Transaction transaction, Long accountId) {
    }
}
