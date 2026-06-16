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
 * Spring Batch {@link ItemStreamWriter} that posts each accepted daily transaction to
 * PostgreSQL (and optionally stages the posted record to AWS S3), reproducing the database
 * write side of COBOL program {@code CBTRN02C} (source commit {@code 27d6c6f}).
 *
 * <p>For every {@link PostedTransaction} item the writer executes the COBOL posting sequence,
 * mapping three paragraphs onto Spring Data JPA operations:</p>
 * <ul>
 *   <li>{@code 2900-WRITE-TRANSACTION-FILE} &rarr; persist the {@link Transaction}
 *       ({@code TRAN-RECORD}, copybook {@code CVTRA05Y}).</li>
 *   <li>{@code 2700-UPDATE-TCATBAL} &rarr; upsert the {@link TransactionCategoryBalance}
 *       keyed by account id, transaction type code, and transaction category code
 *       (copybook {@code CVTRA01Y}): a missing balance is created with the transaction
 *       amount, an existing balance is incremented by the amount.</li>
 *   <li>{@code 2800-UPDATE-ACCOUNT-REC} &rarr; add the amount to the {@link Account} current
 *       balance and to either the current-cycle credit (amount &ge; 0) or current-cycle debit
 *       (amount &lt; 0) total (copybook {@code CVACT01Y}); a missing account surfaces as
 *       reject code {@code 109}.</li>
 * </ul>
 *
 * <p>The three database writes run inside the chunk-oriented step transaction owned by the
 * step's {@code PlatformTransactionManager}; any exception thrown here rolls back the whole
 * chunk, the faithful replacement for the CICS {@code SYNCPOINT}/batch-commit boundary. The
 * writer therefore declares no transaction boundary of its own. The {@link Account} entity
 * carries a JPA {@code @Version}; {@code saveAndFlush} forces the version check to surface as
 * an {@link OptimisticLockingFailureException}, which is rethrown as a
 * {@link ConcurrencyException}.</p>
 *
 * <p>When {@code carddemo.batch.posting.stage-to-s3} is enabled, each posted transaction is
 * additionally appended to an in-memory buffer as a byte-exact 350-byte {@code TRAN-RECORD}
 * (no delimiters) and the whole buffer is flushed as a single S3 object on {@link #close()},
 * keyed {@code SYSTRAN} — the S3 mapping (D-003) of the {@code SYSTRAN} generation-data group
 * defined in {@code DEFGDGB.jcl}; S3 bucket versioning supplies the generation history. All
 * staged bytes use {@link StandardCharsets#ISO_8859_1} (one byte per character) so the fixed
 * offsets stay byte-accurate. Monetary fields use {@link BigDecimal} arithmetic exclusively;
 * the metric volume value is the only place a {@code double} is taken, and only as telemetry.</p>
 *
 * <p>The writer assumes single-threaded, sequential chunk processing, matching the COBOL batch;
 * its staging buffer is per-run state created in {@link #open(ExecutionContext)} and released in
 * {@link #close()}.</p>
 */
@Component
public class TransactionWriter implements ItemStreamWriter<TransactionWriter.PostedTransaction> {

    /** S3 object key for the staged posted-transaction file, the GDG base {@code SYSTRAN} (D-003). */
    private static final String STAGE_OBJECT_KEY = "SYSTRAN";

    /** Fixed length of a {@code TRAN-RECORD} (copybook {@code CVTRA05Y}), in bytes. */
    private static final int TRAN_RECORD_LENGTH = 350;

    /** Content type used for the binary, fixed-width staged object. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /** Scale of monetary {@code S9(09)V99} fields. */
    private static final int AMOUNT_SCALE = 2;

    /** Byte length of the zoned-decimal {@code TRAN-AMT S9(09)V99} field. */
    private static final int AMOUNT_FIELD_LENGTH = 11;

    /**
     * Trailing-overpunch characters for a non-negative units digit, indexed by {@code digit - '0'}.
     * This is the ASCII zoned-decimal representation: digit 0 maps to the open-brace character and
     * digits 1 through 9 map to the letters A through I.
     */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /**
     * Trailing-overpunch characters for a negative units digit, indexed by {@code digit - '0'}.
     * Digit 0 maps to the close-brace character and digits 1 through 9 map to the letters J through R.
     */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    private final TransactionRepository transactionRepository;
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;
    private final AccountRepository accountRepository;
    private final S3Client s3Client;
    private final MeterRegistry meterRegistry;
    private final String outputBucket;
    private final boolean stageToS3;

    /** Per-run staging buffer; created in {@link #open} and released in {@link #close}. Null when staging is disabled. */
    private ByteArrayOutputStream stageBuffer;

    /**
     * Creates the transaction writer.
     *
     * @param transactionRepository     repository for persisting posted transactions
     * @param categoryBalanceRepository repository for the transaction-category-balance upsert
     * @param accountRepository         repository for the versioned account update
     * @param s3Client                  the synchronous S3 client; endpoint, region, and credentials
     *                                  are resolved from configuration (for example LocalStack)
     * @param meterRegistry             the Micrometer registry used to record posting metrics
     * @param outputBucket              the destination S3 bucket, defaulting to {@code carddemo-batch-output}
     * @param stageToS3                 whether posted records are staged to S3, defaulting to {@code true}
     */
    public TransactionWriter(TransactionRepository transactionRepository,
                             TransactionCategoryBalanceRepository categoryBalanceRepository,
                             AccountRepository accountRepository,
                             S3Client s3Client,
                             MeterRegistry meterRegistry,
                             @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") String outputBucket,
                             @Value("${carddemo.batch.posting.stage-to-s3:true}") boolean stageToS3) {
        this.transactionRepository = transactionRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.accountRepository = accountRepository;
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.outputBucket = outputBucket;
        this.stageToS3 = stageToS3;
    }

    /**
     * Initializes a fresh staging buffer at the start of the step when staging is enabled, so that
     * each run produces exactly one {@code SYSTRAN} S3 object. When staging is disabled the buffer
     * stays {@code null} and the stream methods are effectively no-ops for S3.
     *
     * @param executionContext the step execution context (unused; no state is restored)
     */
    @Override
    public void open(ExecutionContext executionContext) {
        if (stageToS3) {
            this.stageBuffer = new ByteArrayOutputStream();
        }
    }

    /**
     * No-op: this writer persists no incremental state to the execution context.
     *
     * @param executionContext the step execution context (unused)
     */
    @Override
    public void update(ExecutionContext executionContext) {
        // no incremental state persisted to the execution context
    }

    /**
     * Posts every item in the chunk. Each item runs the full posting sequence; any exception
     * propagates and rolls back the surrounding chunk transaction.
     *
     * @param chunk the chunk of posted transactions to write
     */
    @Override
    public void write(Chunk<? extends PostedTransaction> chunk) {
        for (PostedTransaction item : chunk) {
            post(item);
        }
    }

    /**
     * Executes the COBOL posting sequence for a single accepted transaction: persist the
     * transaction, upsert its category balance, update the owning account (versioned), record
     * metrics, and optionally append the posted record to the S3 staging buffer.
     *
     * @param item the posted transaction together with its resolved owning account id
     */
    private void post(PostedTransaction item) {
        Transaction transaction = item.transaction();
        Long accountId = item.accountId();
        BigDecimal amount = transaction.getTranAmt();

        // 2900-WRITE-TRANSACTION-FILE: persist the posted transaction record.
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

        // 2800-UPDATE-ACCOUNT-REC: update balance and cycle totals on the versioned account.
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

        // Telemetry: amount.doubleValue() is used only for the monotonic volume counter.
        MetricsConfig.recordsProcessed(meterRegistry).increment();
        MetricsConfig.transactionAmountTotal(meterRegistry).increment(Math.abs(amount.doubleValue()));

        // Optional S3 staging: append the byte-exact 350-byte posted record (no delimiters).
        if (stageToS3 && stageBuffer != null) {
            stageBuffer.writeBytes(buildTranRecord(transaction));
        }
    }

    /**
     * Flushes the accumulated posted-transaction records to a single {@code SYSTRAN} S3 object.
     * When staging is disabled, or nothing was staged, no object is written. The staging buffer is
     * always released afterwards.
     *
     * @throws FileAccessException if the S3 put fails
     */
    @Override
    public void close() {
        try {
            if (!stageToS3 || stageBuffer == null || stageBuffer.size() == 0) {
                return;
            }
            byte[] payload = stageBuffer.toByteArray();
            try {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(outputBucket)
                                .key(STAGE_OBJECT_KEY)
                                .contentType(CONTENT_TYPE)
                                .build(),
                        RequestBody.fromBytes(payload));
            } catch (SdkException e) {
                throw new FileAccessException(
                        "Failed to stage posted transactions to S3 " + outputBucket + "/" + STAGE_OBJECT_KEY, e);
            }
        } finally {
            this.stageBuffer = null;
        }
    }

    /**
     * Builds one byte-exact 350-byte {@code TRAN-RECORD} (copybook {@code CVTRA05Y}). The buffer is
     * space-filled first, so any short field is right-padded with spaces and any over-long field is
     * truncated to its fixed width. Field layout:
     *
     * <pre>
     *   offset  length  field                PIC          encoding
     *   ------  ------  -------------------  -----------  -----------------------------------------
     *        0      16  TRAN-ID              X(16)        left-justified, space-padded
     *       16       2  TRAN-TYPE-CD         X(02)        left-justified, space-padded
     *       18       4  TRAN-CAT-CD          9(04)        zero-padded numeric
     *       22      10  TRAN-SOURCE          X(10)        left-justified, space-padded
     *       32     100  TRAN-DESC            X(100)       left-justified, space-padded
     *      132      11  TRAN-AMT             S9(09)V99    zoned decimal, trailing sign overpunch
     *      143       9  TRAN-MERCHANT-ID     9(09)        zero-padded numeric
     *      152      50  TRAN-MERCHANT-NAME   X(50)        left-justified, space-padded
     *      202      50  TRAN-MERCHANT-CITY   X(50)        left-justified, space-padded
     *      252      10  TRAN-MERCHANT-ZIP    X(10)        left-justified, space-padded
     *      262      16  TRAN-CARD-NUM        X(16)        left-justified, space-padded
     *      278      26  TRAN-ORIG-TS         X(26)        left-justified, space-padded
     *      304      26  TRAN-PROC-TS         X(26)        left-justified, space-padded
     *      330      20  FILLER               X(20)        spaces
     *   ------------------------------------------------------------------------------------------
     *      total = 350 bytes (RECLN = 350)
     * </pre>
     *
     * @param t the posted transaction
     * @return a new array of exactly 350 bytes
     */
    private byte[] buildTranRecord(Transaction t) {
        byte[] out = new byte[TRAN_RECORD_LENGTH];
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
        // FILLER (offset 330, length 20) remains spaces.

        return out;
    }

    /**
     * Writes an alphanumeric value left-justified into a fixed-width slot, truncating to the slot
     * length and leaving any unused trailing bytes as the pre-filled spaces.
     *
     * @param out    the record buffer being assembled
     * @param offset the start offset of the slot
     * @param len    the fixed width of the slot
     * @param value  the value to write; {@code null} is treated as empty
     */
    private void putAlpha(byte[] out, int offset, int len, String value) {
        String safe = value == null ? "" : value;
        byte[] bytes = safe.getBytes(StandardCharsets.ISO_8859_1);
        int n = Math.min(len, bytes.length);
        System.arraycopy(bytes, 0, out, offset, n);
    }

    /**
     * Writes an unsigned numeric value as a zero-padded, right-justified field, keeping only the
     * low-order {@code len} digits if the formatted value is longer than the slot.
     *
     * @param out    the record buffer being assembled
     * @param offset the start offset of the slot
     * @param len    the fixed width of the slot
     * @param value  the numeric value to write
     */
    private void putNum(byte[] out, int offset, int len, long value) {
        String formatted = String.format("%0" + len + "d", value);
        if (formatted.length() > len) {
            formatted = formatted.substring(formatted.length() - len);
        }
        byte[] bytes = formatted.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, out, offset, len);
    }

    /**
     * Writes a signed {@code S9(09)V99} value as an 11-byte zoned-decimal field with a trailing
     * sign overpunch on the units digit, the byte-exact COBOL {@code DISPLAY} representation. The
     * value is scaled to two fraction digits ({@link RoundingMode#HALF_EVEN}), reduced to its
     * eleven low-order unscaled digits, and the final digit is replaced by the overpunch character
     * for its sign.
     *
     * @param out    the record buffer being assembled
     * @param offset the start offset of the 11-byte slot
     * @param amount the signed monetary amount to encode
     */
    private void putSignedZoned(byte[] out, int offset, BigDecimal amount) {
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

    /**
     * Input contract produced upstream by the posting processor.
     *
     * @param transaction the fully populated transaction to persist (COBOL {@code TRAN-RECORD})
     * @param accountId   the owning account id resolved upstream (COBOL {@code XREF-ACCT-ID}); kept
     *                    separate because {@link Transaction} carries no account-id column, yet the
     *                    category-balance key and the account update both require it
     */
    public record PostedTransaction(Transaction transaction, Long accountId) {
    }
}
