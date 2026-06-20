package com.carddemo.batch.writers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.stereotype.Component;

import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.batch.writers.RejectWriter.RejectedTransaction;
import com.carddemo.batch.writers.TransactionWriter.PostedTransaction;
import com.carddemo.model.entity.DailyTransaction;

/**
 * Spring Batch classifier/adapter {@link ItemStreamWriter} that bridges the daily-transaction
 * posting <em>processor</em> to the two posting <em>writers</em>, completing the POSTTRAN
 * processor&rarr;writer contract.
 *
 * <p>The upstream {@link TransactionPostingProcessor} emits one
 * {@link PostingResult} per validated daily transaction: a <em>posted</em> result (a
 * {@code Transaction} plus its recomputed {@code Account} and category balance) or a
 * <em>rejected</em> result (a numeric reject reason, the {@code X(76)} reason description, and the
 * source {@code DailyTransaction}). The two downstream writers, however, consume different,
 * narrower item types:</p>
 * <ul>
 *   <li>{@link TransactionWriter} (label <em>DB + S3</em>) consumes
 *       {@link PostedTransaction} &mdash; it persists the posted transaction to PostgreSQL, upserts
 *       the transaction-category balance, updates the owning account, and optionally stages the
 *       byte-exact {@code TRAN-RECORD} to S3.</li>
 *   <li>{@link RejectWriter} consumes {@link RejectedTransaction} &mdash; it emits the byte-exact
 *       430-byte reject record (350-byte daily-transaction image + 4-byte reason + 76-byte
 *       description) to S3.</li>
 * </ul>
 *
 * <p>This adapter is the classifier the migration needs between them: for each item in a chunk it
 * inspects {@link PostingResult#rejected()} and routes the item to the correct delegate, translating
 * the {@code PostingResult} into the delegate's input type. It therefore preserves the COBOL
 * {@code CBTRN02C} control flow in which every read daily transaction is either posted
 * ({@code 2900}/{@code 2700}/{@code 2800}) or written to the reject file
 * ({@code 2500-WRITE-REJECT-REC}). (Logic only, never source text; traceability via commit
 * {@code 27d6c6f}.)</p>
 *
 * <h2>Reject-image reconstruction</h2>
 * <p>The reject writer requires the original raw 350-byte daily-transaction image
 * (COBOL {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}). The {@link DailyTransaction} entity
 * carries the <em>parsed</em> fields but not the raw line, so this adapter rebuilds the image from
 * those fields with {@link #buildDailyTransactionImage(DailyTransaction)}. That method is the exact
 * inverse of {@code com.carddemo.batch.readers.DailyTransactionReader}: the same {@code CVTRA06Y}
 * fixed-width offsets and the same trailing-sign zoned-decimal overpunch for the {@code S9(09)V99}
 * amount, so a record read and then rejected reproduces its original bytes. {@link BigDecimal} is
 * used exclusively for the amount (never {@code float}/{@code double}), per AAP D-001 / &sect;0.8.2.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>As an {@link ItemStreamWriter} this adapter owns no buffer of its own; it simply forwards the
 * {@code open}/{@code update}/{@code close} stream callbacks to both delegates so their per-run S3
 * staging buffers are allocated and flushed exactly once. {@link #close()} closes both delegates even
 * if the first throws, so neither delegate's buffer is leaked.</p>
 */
@Component
public class TransactionPostingResultWriter
        implements ItemStreamWriter<PostingResult> {

    /** Total length of the {@code CVTRA06Y} daily-transaction image ({@code DALYTRAN}, 350 bytes). */
    private static final int DALYTRAN_RECORD_LENGTH = 350;

    /**
     * Trailing-overpunch characters for a non-negative zoned-decimal units digit, indexed by the
     * digit value {@code 0..9}: {@code 0} maps to <code>'{'</code> and {@code 1..9} map to
     * {@code 'A'..'I'}. Mirrors {@code DailyTransactionReader.decodeSignedAmount}.
     */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /**
     * Trailing-overpunch characters for a negative zoned-decimal units digit, indexed by the digit
     * value {@code 0..9}: {@code 0} maps to <code>'}'</code> and {@code 1..9} map to
     * {@code 'J'..'R'}. Mirrors {@code DailyTransactionReader.decodeSignedAmount}.
     */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    /** Delegate that persists posted transactions to PostgreSQL and (optionally) stages them to S3. */
    private final TransactionWriter transactionWriter;

    /** Delegate that emits the byte-exact 430-byte reject records to S3. */
    private final RejectWriter rejectWriter;

    /**
     * Creates the adapter with both posting writers injected by the Spring container.
     *
     * @param transactionWriter the DB + S3 writer for posted transactions
     * @param rejectWriter      the S3 writer for rejected transactions
     */
    public TransactionPostingResultWriter(
            TransactionWriter transactionWriter,
            RejectWriter rejectWriter) {
        this.transactionWriter = transactionWriter;
        this.rejectWriter = rejectWriter;
    }

    /**
     * Opens both delegates so each allocates its per-run S3 staging buffer.
     *
     * @param executionContext the Spring Batch execution context, forwarded unchanged
     * @throws ItemStreamException if either delegate fails to open
     */
    @Override
    public void open(ExecutionContext executionContext) {
        transactionWriter.open(executionContext);
        rejectWriter.open(executionContext);
    }

    /**
     * Forwards the incremental-state callback to both delegates (each is a no-op; the whole run is
     * flushed in {@link #close()}).
     *
     * @param executionContext the Spring Batch execution context, forwarded unchanged
     * @throws ItemStreamException if either delegate fails to update
     */
    @Override
    public void update(ExecutionContext executionContext) {
        transactionWriter.update(executionContext);
        rejectWriter.update(executionContext);
    }

    /**
     * Closes both delegates so each flushes (and releases) its per-run S3 staging buffer. The reject
     * writer is closed even when the transaction writer's close throws, so neither buffer is leaked;
     * the first failure is propagated after both have been attempted.
     *
     * @throws ItemStreamException propagated from a delegate close failure
     */
    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            transactionWriter.close();
        } catch (RuntimeException ex) {
            failure = ex;
        }
        try {
            rejectWriter.close();
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Partitions the chunk by {@link PostingResult#rejected()} and forwards each partition to its
     * delegate: posted results (translated to {@link PostedTransaction}) to the transaction writer
     * and rejected results (translated to {@link RejectedTransaction} with a reconstructed 350-byte
     * image) to the reject writer. A partition is forwarded only when it is non-empty, so a chunk of
     * all-posted or all-rejected items touches a single delegate.
     *
     * <p>Any exception from a delegate's {@code write} propagates to roll back the whole chunk,
     * faithful to the single per-chunk transaction of the posting step.</p>
     *
     * @param chunk the chunk of {@link PostingResult} items produced by the processor
     */
    @Override
    public void write(Chunk<? extends PostingResult> chunk) {
        List<PostedTransaction> posted = new ArrayList<>();
        List<RejectedTransaction> rejected = new ArrayList<>();

        for (PostingResult result : chunk) {
            if (result.rejected()) {
                rejected.add(toRejectedTransaction(result));
            } else {
                posted.add(toPostedTransaction(result));
            }
        }

        if (!posted.isEmpty()) {
            transactionWriter.write(new Chunk<>(posted));
        }
        if (!rejected.isEmpty()) {
            rejectWriter.write(new Chunk<>(rejected));
        }
    }

    /**
     * Translates a posted {@link PostingResult} into the transaction writer's input. The owning
     * account id is taken from the result's recomputed account (COBOL {@code XREF-ACCT-ID}); the
     * transaction writer re-reads and persists the account, balance, and transaction itself.
     *
     * @param result a posted result ({@code rejected == false})
     * @return the {@link PostedTransaction} for {@link TransactionWriter}
     */
    private static PostedTransaction toPostedTransaction(PostingResult result) {
        return new PostedTransaction(
                result.postedTransaction(),
                result.updatedAccount().getAcctId());
    }

    /**
     * Translates a rejected {@link PostingResult} into the reject writer's input, reconstructing the
     * raw 350-byte daily-transaction image from the source record's parsed fields.
     *
     * @param result a rejected result ({@code rejected == true})
     * @return the {@link RejectedTransaction} for {@link RejectWriter}
     */
    private static RejectedTransaction toRejectedTransaction(PostingResult result) {
        String image = buildDailyTransactionImage(result.originalDailyTransaction());
        return new RejectedTransaction(
                image,
                result.rejectCode(),
                result.rejectReasonDescription());
    }

    /**
     * Rebuilds the byte-exact 350-byte {@code DALYTRAN-RECORD} ({@code app/cpy/CVTRA06Y.cpy}) for a
     * single daily transaction, returned as an {@code ISO_8859_1} string (1:1 byte-to-character).
     *
     * <p>This is the exact inverse of {@code DailyTransactionReader}'s fixed-width tokenizer; offsets
     * are zero-based and lengths match the reader's {@code Range} columns:</p>
     * <ul>
     *   <li>offset 0, length 16 &mdash; {@code DALYTRAN-ID} {@code X(16)}, left-justified.</li>
     *   <li>offset 16, length 2 &mdash; {@code DALYTRAN-TYPE-CD} {@code X(02)}, left-justified.</li>
     *   <li>offset 18, length 4 &mdash; {@code DALYTRAN-CAT-CD} {@code 9(04)}, zero-padded numeric.</li>
     *   <li>offset 22, length 10 &mdash; {@code DALYTRAN-SOURCE} {@code X(10)}, left-justified.</li>
     *   <li>offset 32, length 100 &mdash; {@code DALYTRAN-DESC} {@code X(100)}, left-justified.</li>
     *   <li>offset 132, length 11 &mdash; {@code DALYTRAN-AMT} {@code S9(09)V99}, zoned-decimal with
     *       trailing overpunch, scale 2.</li>
     *   <li>offset 143, length 9 &mdash; {@code DALYTRAN-MERCHANT-ID} {@code 9(09)}, zero-padded.</li>
     *   <li>offset 152, length 50 &mdash; {@code DALYTRAN-MERCHANT-NAME} {@code X(50)}, left-justified.</li>
     *   <li>offset 202, length 50 &mdash; {@code DALYTRAN-MERCHANT-CITY} {@code X(50)}, left-justified.</li>
     *   <li>offset 252, length 10 &mdash; {@code DALYTRAN-MERCHANT-ZIP} {@code X(10)}, left-justified.</li>
     *   <li>offset 262, length 16 &mdash; {@code DALYTRAN-CARD-NUM} {@code X(16)}, left-justified.</li>
     *   <li>offset 278, length 26 &mdash; {@code DALYTRAN-ORIG-TS} {@code X(26)}, left-justified.</li>
     *   <li>offset 304, length 26 &mdash; {@code DALYTRAN-PROC-TS} {@code X(26)}, left-justified.</li>
     *   <li>offset 330, length 20 &mdash; {@code FILLER} {@code X(20)}, spaces.</li>
     * </ul>
     *
     * <p>The reader trims alphanumeric fields and re-padding here restores the original left-justified
     * layout; numeric fields are zero-padded and the amount is re-encoded with the same overpunch
     * table the reader decodes, so a round trip is byte-exact for every well-formed source record.</p>
     *
     * @param tran the source daily transaction (the reject's {@code originalDailyTransaction})
     * @return a 350-character {@code ISO_8859_1} string equal to the original record bytes
     */
    private static String buildDailyTransactionImage(DailyTransaction tran) {
        byte[] out = new byte[DALYTRAN_RECORD_LENGTH];
        // Space-fill first so every short alphanumeric field is right-padded with blanks and the
        // trailing FILLER (offset 330, length 20) is left as spaces.
        Arrays.fill(out, (byte) ' ');

        putAlpha(out, 0, 16, tran.getDalytranId());
        putAlpha(out, 16, 2, tran.getDalytranTypeCd());
        putNum(out, 18, 4, tran.getDalytranCatCd());
        putAlpha(out, 22, 10, tran.getDalytranSource());
        putAlpha(out, 32, 100, tran.getDalytranDesc());
        putSignedZoned(out, 132, tran.getDalytranAmt());
        putNum(out, 143, 9, tran.getDalytranMerchantId());
        putAlpha(out, 152, 50, tran.getDalytranMerchantName());
        putAlpha(out, 202, 50, tran.getDalytranMerchantCity());
        putAlpha(out, 252, 10, tran.getDalytranMerchantZip());
        putAlpha(out, 262, 16, tran.getDalytranCardNum());
        putAlpha(out, 278, 26, tran.getDalytranOrigTs());
        putAlpha(out, 304, 26, tran.getDalytranProcTs());

        return new String(out, StandardCharsets.ISO_8859_1);
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
     * Writes an unsigned numeric field zero-padded to {@code len} digits at the given offset. A
     * {@code null} value is treated as zero (COBOL numeric default). When the formatted value exceeds
     * {@code len} digits, the low-order {@code len} digits are retained (matching the COBOL truncation
     * of an oversized numeric move).
     *
     * @param out    the record buffer being populated
     * @param offset the zero-based start position of the field
     * @param len    the fixed field width in digits
     * @param value  the numeric value to format, or {@code null} for zero
     */
    private static void putNum(byte[] out, int offset, int len, Number value) {
        long v = value == null ? 0L : value.longValue();
        String digits = String.format("%0" + len + "d", v);
        if (digits.length() > len) {
            digits = digits.substring(digits.length() - len);
        }
        byte[] bytes = digits.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, out, offset, len);
    }

    /**
     * Writes an {@code S9(09)V99} signed amount as an 11-byte zoned-decimal field with a trailing
     * sign overpunch on the units digit, at the given offset &mdash; the exact inverse of
     * {@code DailyTransactionReader.decodeSignedAmount}.
     *
     * <p>The amount is rounded to scale 2 ({@link RoundingMode#HALF_EVEN}), converted to its unscaled
     * cents magnitude, and formatted as eleven digits (low-order eleven retained if longer). The first
     * ten digits are written verbatim; the units digit is replaced by its overpunch character drawn
     * from {@link #POSITIVE_OVERPUNCH} when the amount is non-negative or {@link #NEGATIVE_OVERPUNCH}
     * when it is negative. A {@code null} amount is treated as {@code 0.00}.</p>
     *
     * @param out    the record buffer being populated
     * @param offset the zero-based start position of the 11-byte field
     * @param amount the signed amount to encode, or {@code null} for zero
     */
    private static void putSignedZoned(byte[] out, int offset, BigDecimal amount) {
        BigDecimal scaled = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_EVEN);
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
}
