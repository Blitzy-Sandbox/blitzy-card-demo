package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.RejectReason;
import com.carddemo.repository.TransactionRepository;

import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.Counter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Unit tests for {@link PostTransactionItemWriter}, the chunk-step {@code ItemStreamWriter} that
 * reproduces the write side of the legacy COBOL batch posting program {@code CBTRN02C.cbl}
 * (writer #1 of 3 in the CardDemo transaction-posting pipeline, source SHA {@code 27d6c6f} —
 * referenced, never copied).
 *
 * <p><strong>Why this test exists.</strong> The writer has two mutually-exclusive output branches
 * that must both be preserved byte-for-byte and count-for-count:</p>
 * <ul>
 *   <li><strong>Posted branch</strong> ({@code 2900-WRITE-TRANSACTION-FILE}) — the built
 *       {@link Transaction} carried on each posted {@link PostingResult} is persisted through a
 *       single {@link TransactionRepository#saveAll(Iterable)} call, and the posted counter
 *       ({@code carddemo.transactions.posted} / {@code WS-TRANSACTION-COUNT}) is incremented once
 *       per posted item.</li>
 *   <li><strong>Reject branch</strong> ({@code 2500-WRITE-REJECT-REC}) — each rejected
 *       {@link PostingResult} is serialized to the fixed-width <strong>430-byte</strong>
 *       {@code DALYREJS} record ({@code RECFM=F,LRECL=430}): the 350-byte {@code DALYTRAN-RECORD}
 *       body (copybook {@code CVTRA06Y}) followed by the 80-byte {@code WS-VALIDATION-TRAILER}
 *       ({@code PIC 9(04)} reason code + {@code PIC X(76)} description). The rejected counter
 *       ({@code carddemo.transactions.rejected} / {@code WS-REJECT-COUNT}) and the running reject
 *       count are incremented once per reject, and the buffer is uploaded to S3 on
 *       {@link PostTransactionItemWriter#close() close()}.</li>
 * </ul>
 *
 * <p><strong>Byte-parity (Gate&nbsp;1/4/5 precursor).</strong> These tests build the expected
 * 430-byte record from an <em>independent</em>, spec-driven serialization oracle (the
 * {@code alphanumeric}/{@code unsignedNumeric}/{@code zonedDecimal} helpers below reproduce the
 * COBOL {@code CVTRA06Y} field layout and the zoned-decimal overpunch rule directly, rather than
 * delegating to the production code), plus literal anchored assertions on the id, amount and
 * trailer fields. Proving the 430-byte formatter in isolation here is the unit-level precursor to
 * {@code PostTransactionJobIT}'s whole-file byte/MD5 comparison against
 * {@code src/test/resources/expected/dalyrejs-expected.txt} (38&nbsp;&times;&nbsp;430&nbsp;bytes,
 * every trailer {@code "0102"} + {@code "OVERLIMIT TRANSACTION"} + 55 spaces).</p>
 *
 * <p><strong>Charset.</strong> The writer emits with ISO-8859-1 (one character maps to one byte for
 * fixed-width mainframe data); every byte assertion in this test therefore uses
 * {@link StandardCharsets#ISO_8859_1} and never the platform default.</p>
 *
 * <p><strong>Exit-code parity ({@code RETURN-CODE 4}).</strong> The writer publishes the running
 * reject count to the {@link StepExecution} execution context under
 * {@link PostTransactionItemWriter#REJECT_COUNT_KEY} and via {@link PostTransactionItemWriter#update
 * update(ExecutionContext)}. Matching production, {@code afterStep} intentionally returns
 * {@code null} in both the reject and no-reject cases: the {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO
 * RETURN-CODE} mapping is delegated to {@code config/BatchConfig}'s {@code JobExecutionDecider},
 * which reads the {@code rejectCount} key. These tests therefore assert the surfaced count, not an
 * {@code ExitStatus} synthesized by {@code afterStep}.</p>
 *
 * <p><strong>Isolation.</strong> The repository, both Micrometer counters and the S3 client are
 * Mockito mocks (no real database and no real S3); the S3 upload payload is captured with a
 * {@code doAnswer} that reads the supplied {@link InputStream} <em>during</em> the upload call,
 * because the writer opens that stream inside a try-with-resources and deletes the backing temp
 * file immediately afterwards. The tests run in milliseconds and contribute fast line coverage
 * toward the Gate&nbsp;8 (&ge;80%) JaCoCo threshold, and compile warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2). No COBOL source is reproduced; rationale lives in
 * {@code docs/decision-log.md}.</p>
 *
 * @see PostTransactionItemWriter
 * @see PostingResult
 * @see RejectReason
 */
@DisplayName("PostTransactionItemWriter — CBTRN02C posted/reject write-side contract")
@ExtendWith(MockitoExtension.class)
class PostTransactionItemWriterTest {

    // --- Fixed contract constants (mirrored from the DALYREJS / CVTRA06Y layout) ---------------

    /** Configured reject bucket ({@code carddemo.batch.reject.bucket} default). */
    private static final String REJECT_BUCKET = "carddemo-batch-output";

    /** Configured reject object key ({@code carddemo.batch.reject.object-key} default). */
    private static final String REJECT_KEY = "dalyrejs.dat";

    /** Full fixed reject-record length ({@code DALYREJS LRECL}). */
    private static final int RECORD_LENGTH = 430;

    /** Length of the {@code DALYTRAN-RECORD} body portion (copybook {@code CVTRA06Y}). */
    private static final int BODY_LENGTH = 350;

    /** Width of the reject-trailer description field ({@code PIC X(76)}). */
    private static final int DESCRIPTION_WIDTH = 76;

    /** Width of the reject reason-code field ({@code PIC 9(04)}). */
    private static final int REASON_CODE_WIDTH = 4;

    /** Byte offset of {@code DALYTRAN-AMT} within the 350-byte body (16+2+4+10+100). */
    private static final int AMOUNT_OFFSET = 132;

    /** Length of the {@code DALYTRAN-AMT} zoned-decimal field ({@code PIC S9(09)V99}). */
    private static final int AMOUNT_LENGTH = 11;

    /** Charset the writer uses for the fixed-width record (one char per byte). */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /** Overpunch characters for a positive trailing digit {@code 0}–{@code 9} ({@code '{'}–{@code 'I'}). */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /** Overpunch characters for a negative trailing digit {@code 0}–{@code 9} ({@code '}'}–{@code 'R'}). */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    // --- Mocked collaborators ------------------------------------------------------------------

    /** Mock posted-transaction repository ({@code 2900-WRITE-TRANSACTION-FILE}). */
    @Mock
    private TransactionRepository transactionRepository;

    /** Mock framework S3 client used to upload the reject file. */
    @Mock
    private S3Template s3Template;

    /** Mock posted-transactions counter ({@code transactionsPostedCounter}). */
    @Mock
    private Counter postedCounter;

    /** Mock rejected-transactions counter ({@code transactionsRejectedCounter}). */
    @Mock
    private Counter rejectedCounter;

    // --- Class under test + step context -------------------------------------------------------

    /** The writer under test, constructed fresh for every test with mock collaborators. */
    private PostTransactionItemWriter writer;

    /** A real (in-memory) step execution used to observe the surfaced reject count. */
    private StepExecution stepExecution;

    /**
     * Constructs a fresh writer with all mock collaborators and the default bucket/key, registers it
     * as a step listener (so the reject count is published to the step context), and opens the reject
     * buffer. Mirrors the Spring Batch lifecycle: {@code beforeStep} then {@code open}.
     */
    @BeforeEach
    void setUp() {
        writer = new PostTransactionItemWriter(
                transactionRepository, s3Template, postedCounter, rejectedCounter,
                REJECT_BUCKET, REJECT_KEY);
        stepExecution = MetaDataInstanceFactory.createStepExecution();
        writer.beforeStep(stepExecution);
        writer.open(stepExecution.getExecutionContext());
    }

    /**
     * Idempotent cleanup: flushes/closes the reject buffer and deletes the per-run temp file. When a
     * test has already called {@code close()} this is a no-op; any late upload lands on the mock
     * only. Cleanup never fails a test — the unit under test owns its own error signalling.
     */
    @AfterEach
    void tearDown() {
        try {
            writer.close();
        } catch (final RuntimeException ignored) {
            // Intentionally ignored: teardown must not mask or fabricate a test outcome.
        }
    }

    // ==========================================================================================
    // Independent, spec-driven serialization oracle (CVTRA06Y body + WS-VALIDATION-TRAILER).
    // These helpers deliberately do NOT call the production code; they encode the COBOL layout so
    // that the assertions validate the writer's output against the specification.
    // ==========================================================================================

    /**
     * Renders an alphanumeric COBOL field ({@code PIC X(width)}): left-justified, space-padded, and
     * right-truncated when longer than {@code width}. A {@code null} renders as all spaces.
     *
     * @param value the field value (may be {@code null})
     * @param width the fixed field width
     * @return a {@code width}-character field
     */
    private static String alphanumeric(final String value, final int width) {
        final String v = (value == null) ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /**
     * Renders an unsigned COBOL numeric field ({@code PIC 9(width)}): right-justified, zero-padded,
     * magnitude only, low-order truncation when longer than {@code width}.
     *
     * @param value the numeric value
     * @param width the fixed field width
     * @return a {@code width}-character zero-padded numeric field
     */
    private static String unsignedNumeric(final long value, final int width) {
        final String digits = Long.toString(Math.abs(value));
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders {@code DALYTRAN-AMT} ({@code PIC S9(09)V99}, {@code USAGE DISPLAY}) as an 11-byte
     * zoned-decimal value: the two implied fraction digits are folded into the digit run and the sign
     * is carried as an overpunch on the trailing digit ({@code '{'}–{@code 'I'} positive,
     * {@code '}'}–{@code 'R'} negative). A {@code null} amount is treated as {@code +0.00}.
     *
     * @param amount the signed amount (may be {@code null})
     * @return an 11-character zoned-decimal field carrying an overpunch sign
     */
    private static String zonedDecimal(final BigDecimal amount) {
        final BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        final boolean negative = value.signum() < 0;
        String digits = value.abs().setScale(2, RoundingMode.HALF_UP).unscaledValue().toString();
        if (digits.length() < AMOUNT_LENGTH) {
            digits = "0".repeat(AMOUNT_LENGTH - digits.length()) + digits;
        } else if (digits.length() > AMOUNT_LENGTH) {
            digits = digits.substring(digits.length() - AMOUNT_LENGTH);
        }
        final int lastDigit = digits.charAt(AMOUNT_LENGTH - 1) - '0';
        final char overpunch = negative ? NEGATIVE_OVERPUNCH[lastDigit] : POSITIVE_OVERPUNCH[lastDigit];
        return digits.substring(0, AMOUNT_LENGTH - 1) + overpunch;
    }

    /**
     * Serializes the 350-byte {@code DALYTRAN-RECORD} body, field-by-field in copybook order.
     *
     * @param dt the daily transaction to serialize
     * @return a 350-character body
     */
    private static String expectedBody(final DailyTransaction dt) {
        final StringBuilder sb = new StringBuilder(BODY_LENGTH);
        sb.append(alphanumeric(dt.getDalytranId(), 16));
        sb.append(alphanumeric(dt.getDalytranTypeCd(), 2));
        sb.append(unsignedNumeric(dt.getDalytranCatCd() == null ? 0L : dt.getDalytranCatCd(), 4));
        sb.append(alphanumeric(dt.getDalytranSource(), 10));
        sb.append(alphanumeric(dt.getDalytranDesc(), 100));
        sb.append(zonedDecimal(dt.getDalytranAmt()));
        sb.append(unsignedNumeric(dt.getDalytranMerchantId() == null ? 0L : dt.getDalytranMerchantId(), 9));
        sb.append(alphanumeric(dt.getDalytranMerchantName(), 50));
        sb.append(alphanumeric(dt.getDalytranMerchantCity(), 50));
        sb.append(alphanumeric(dt.getDalytranMerchantZip(), 10));
        sb.append(alphanumeric(dt.getDalytranCardNum(), 16));
        sb.append(alphanumeric(dt.getDalytranOrigTs(), 26));
        sb.append(alphanumeric(dt.getDalytranProcTs(), 26));
        sb.append(" ".repeat(20));
        return sb.toString();
    }

    /**
     * Builds the 80-byte reject trailer: a 4-digit zero-padded reason code followed by the
     * description left-justified and space-padded to 76 characters.
     *
     * @param code        the numeric reject code
     * @param description the reason description
     * @return an 80-character trailer
     */
    private static String expectedTrailer(final int code, final String description) {
        final String desc = (description.length() >= DESCRIPTION_WIDTH)
                ? description.substring(0, DESCRIPTION_WIDTH)
                : description + " ".repeat(DESCRIPTION_WIDTH - description.length());
        return unsignedNumeric(code, REASON_CODE_WIDTH) + desc;
    }

    // ==========================================================================================
    // Deterministic domain builders.
    // ==========================================================================================

    /**
     * Builds a fully-populated, deterministic {@link DailyTransaction} whose fields exactly fill or
     * under-fill each fixed-width column (so padding is exercised). The amount {@code 100.00}
     * serializes to the zoned-decimal field {@code "0000001000{"}.
     *
     * @param sequence the numeric suffix used to derive a unique 16-character id
     * @return a populated daily transaction
     */
    private static DailyTransaction dailyTran(final int sequence) {
        final DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId(String.format("DT%014d", sequence));
        dt.setDalytranTypeCd("01");
        dt.setDalytranCatCd(1);
        dt.setDalytranSource("POS TERM");
        dt.setDalytranDesc("Purchase at Test Merchant");
        dt.setDalytranAmt(new BigDecimal("100.00"));
        dt.setDalytranMerchantId(123456789L);
        dt.setDalytranMerchantName("TEST MERCHANT");
        dt.setDalytranMerchantCity("SEATTLE");
        dt.setDalytranMerchantZip("98101");
        dt.setDalytranCardNum("4111111111111111");
        dt.setDalytranOrigTs("2024-01-01-12.00.00.000000");
        dt.setDalytranProcTs("2024-01-02-13.30.45.123456");
        return dt;
    }

    /**
     * Builds a posted {@link Transaction} with the given id (other fields left at defaults;
     * {@link Transaction} defines no {@code equals}/{@code hashCode}, so tests rely on reference
     * identity).
     *
     * @param sequence the numeric suffix used to derive a unique 16-character id
     * @return a transaction instance
     */
    private static Transaction transaction(final int sequence) {
        final Transaction t = new Transaction();
        t.setTranId(String.format("TX%014d", sequence));
        t.setTranAmt(new BigDecimal("100.00"));
        return t;
    }

    /**
     * Convenience factory for a posted {@link PostingResult}.
     *
     * @param sequence the numeric suffix shared by the source daily-tran and posted transaction
     * @return a posted posting result
     */
    private static PostingResult posted(final int sequence) {
        return PostingResult.posted(dailyTran(sequence), transaction(sequence));
    }

    /**
     * Convenience factory for a rejected {@link PostingResult}.
     *
     * @param sequence the numeric suffix used for the source daily-tran
     * @param reason   the rejection reason
     * @return a rejected posting result
     */
    private static PostingResult rejected(final int sequence, final RejectReason reason) {
        return PostingResult.rejected(dailyTran(sequence), reason);
    }

    /**
     * Stubs {@link S3Template#upload(String, String, InputStream)} to copy the uploaded stream into a
     * buffer <em>during</em> the call (before the writer closes the stream and deletes the temp
     * file), finalizes the step via {@link PostTransactionItemWriter#afterStep(StepExecution)}, and
     * returns the captured bytes.
     *
     * <p>Per QA finding <strong>F4</strong> (decision {@code D-027}) the flush + S3 upload were moved
     * out of {@code close()} (which {@code AbstractStep} runs after it has already persisted the step
     * status, swallowing any exception) into {@code afterStep}, which runs before the status is
     * persisted and can therefore fail the step. The step status is set to {@link BatchStatus#COMPLETED}
     * first because {@code afterStep} only performs the terminal upload on the success path.</p>
     *
     * @return the exact bytes the writer streamed to S3
     */
    private byte[] closeAndCaptureUploadedBytes() {
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        doAnswer(invocation -> {
            final InputStream in = invocation.getArgument(2);
            in.transferTo(captured);
            return null;
        }).when(s3Template).upload(anyString(), anyString(), any(InputStream.class));
        stepExecution.setStatus(BatchStatus.COMPLETED);
        writer.afterStep(stepExecution);
        return captured.toByteArray();
    }

    // ==========================================================================================
    // Phase 2 — Posted path: saveAll receives exactly the posted transactions; posted counter += N.
    // ==========================================================================================

    @Test
    @DisplayName("Posted-only chunk: saveAll gets exactly the posted transactions (order preserved), "
            + "posted counter += N, no reject/S3 activity")
    void postedOnlyChunkPersistsAllAndCountsPosted() {
        final Transaction t1 = transaction(1);
        final Transaction t2 = transaction(2);
        final Transaction t3 = transaction(3);
        final Chunk<PostingResult> chunk = Chunk.of(
                PostingResult.posted(dailyTran(1), t1),
                PostingResult.posted(dailyTran(2), t2),
                PostingResult.posted(dailyTran(3), t3));

        writer.write(chunk);

        final ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.captor();
        verify(transactionRepository).saveAll(saved.capture());
        // Exactly the three posted transactions, same instances, in read order (identity: Transaction
        // defines no equals/hashCode, so containsExactly degrades to reference equality here).
        assertThat(saved.getValue()).containsExactly(t1, t2, t3);

        verify(postedCounter, times(3)).increment();
        verifyNoInteractions(rejectedCounter);
        // Nothing is buffered or uploaded for a posted-only chunk prior to close().
        verifyNoInteractions(s3Template);
        assertThat(stepExecution.getExecutionContext()
                .getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isZero();
    }

    // ==========================================================================================
    // Phase 3 — Mixed chunk: posted persisted, rejects buffered (not yet uploaded).
    // ==========================================================================================

    @Test
    @DisplayName("Mixed chunk (2 posted + 3 rejected): saveAll gets only the 2 posted; "
            + "counters split 2/3; rejects buffered (no S3 upload before close)")
    void mixedChunkPersistsOnlyPostedAndBuffersRejects() {
        final Transaction p1 = transaction(1);
        final Transaction p2 = transaction(2);
        final Chunk<PostingResult> chunk = Chunk.of(
                PostingResult.posted(dailyTran(1), p1),
                PostingResult.rejected(dailyTran(11), RejectReason.OVERLIMIT),
                PostingResult.posted(dailyTran(2), p2),
                PostingResult.rejected(dailyTran(12), RejectReason.INVALID_CARD_NUMBER),
                PostingResult.rejected(dailyTran(13), RejectReason.ACCOUNT_NOT_FOUND));

        writer.write(chunk);

        final ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.captor();
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).containsExactly(p1, p2);

        verify(postedCounter, times(2)).increment();
        verify(rejectedCounter, times(3)).increment();
        // Rejects accumulate in the per-run buffer; the upload only happens on close().
        verifyNoInteractions(s3Template);
        // The running reject count is surfaced immediately (durable across chunk commits).
        assertThat(stepExecution.getExecutionContext()
                .getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isEqualTo(3L);
    }

    // ==========================================================================================
    // Phase 4 — 430-byte reject byte-parity (body + trailer), the Gate 1/4/5 precursor.
    // ==========================================================================================

    @Test
    @DisplayName("Reject byte-parity: a single OVERLIMIT reject is a 430-byte record (350 body + 80 "
            + "trailer) + LF, byte-exact for body, code and description")
    void rejectRecordIsByteExactForOverlimit() {
        final DailyTransaction dt = dailyTran(1);
        writer.write(Chunk.of(PostingResult.rejected(dt, RejectReason.OVERLIMIT)));

        final byte[] uploaded = closeAndCaptureUploadedBytes();

        // One record = 430 data bytes + one LF record separator.
        assertThat(uploaded).hasSize(RECORD_LENGTH + 1);
        assertThat(uploaded[RECORD_LENGTH]).isEqualTo((byte) '\n');

        final byte[] record = Arrays.copyOfRange(uploaded, 0, RECORD_LENGTH);
        final byte[] expected =
                (expectedBody(dt) + expectedTrailer(102, "OVERLIMIT TRANSACTION")).getBytes(RECORD_CHARSET);
        assertThat(record).isEqualTo(expected);

        // Anchored, literal field assertions independent of the oracle:
        assertThat(new String(record, 0, 16, RECORD_CHARSET)).isEqualTo("DT00000000000001");
        // DALYTRAN-AMT 100.00 -> zoned-decimal "0000001000{" (trailing overpunch '{' encodes +0).
        assertThat(new String(record, AMOUNT_OFFSET, AMOUNT_LENGTH, RECORD_CHARSET))
                .isEqualTo("0000001000{");
        // Trailer: reason code then the full 76-byte description field (trailing spaces included).
        assertThat(new String(record, BODY_LENGTH, REASON_CODE_WIDTH, RECORD_CHARSET)).isEqualTo("0102");
        assertThat(new String(record, BODY_LENGTH + REASON_CODE_WIDTH, DESCRIPTION_WIDTH, RECORD_CHARSET))
                .isEqualTo("OVERLIMIT TRANSACTION" + " ".repeat(55));

        // Reject-branch side effects: one reject counted, no posting activity.
        verify(rejectedCounter).increment();
        verifyNoInteractions(postedCounter);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * Reject reasons exercised by {@link #rejectTrailerIsByteExactPerReason}: the constant, its
     * numeric code, and its verbatim description (each shorter than the 76-byte field, so none is
     * truncated).
     *
     * @return the parameterized reject-reason arguments
     */
    static Stream<Arguments> rejectReasons() {
        return Stream.of(
                Arguments.of(RejectReason.INVALID_CARD_NUMBER, 100, "INVALID CARD NUMBER FOUND"),
                Arguments.of(RejectReason.ACCOUNT_NOT_FOUND, 101, "ACCOUNT RECORD NOT FOUND"),
                Arguments.of(RejectReason.OVERLIMIT, 102, "OVERLIMIT TRANSACTION"),
                Arguments.of(RejectReason.ACCOUNT_EXPIRED, 103,
                        "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"));
    }

    @ParameterizedTest(name = "[{index}] {0} -> code {1}")
    @MethodSource("rejectReasons")
    @DisplayName("Reject trailer byte-parity across reasons: 4-digit zero-padded code + description "
            + "space-padded to exactly 76 bytes")
    void rejectTrailerIsByteExactPerReason(
            final RejectReason reason, final int code, final String description) {
        final DailyTransaction dt = dailyTran(7);
        writer.write(Chunk.of(PostingResult.rejected(dt, reason)));

        final byte[] uploaded = closeAndCaptureUploadedBytes();
        assertThat(uploaded).hasSize(RECORD_LENGTH + 1);

        final byte[] record = Arrays.copyOfRange(uploaded, 0, RECORD_LENGTH);
        // The 350-byte body is identical regardless of reject reason.
        assertThat(new String(record, 0, BODY_LENGTH, RECORD_CHARSET)).isEqualTo(expectedBody(dt));
        // Reason code is zero-padded to 4 digits.
        assertThat(new String(record, BODY_LENGTH, REASON_CODE_WIDTH, RECORD_CHARSET))
                .isEqualTo(String.format("%04d", code));
        // Description field is exactly 76 bytes: text left-justified, remainder spaces.
        final String descField =
                new String(record, BODY_LENGTH + REASON_CODE_WIDTH, DESCRIPTION_WIDTH, RECORD_CHARSET);
        assertThat(descField).hasSize(DESCRIPTION_WIDTH);
        assertThat(descField).isEqualTo(description + " ".repeat(DESCRIPTION_WIDTH - description.length()));
        // Whole trailer equals the independent oracle.
        assertThat(new String(record, BODY_LENGTH, RECORD_LENGTH - BODY_LENGTH, RECORD_CHARSET))
                .isEqualTo(expectedTrailer(code, description));
    }

    // ==========================================================================================
    // Phase 5 — S3 upload target: bucket/key on close(); empty object on zero rejects.
    // ==========================================================================================

    @Test
    @DisplayName("S3 target: exactly one upload to carddemo-batch-output/dalyrejs.dat, only on afterStep")
    void uploadsRejectFileToConfiguredBucketAndKeyOnClose() {
        writer.write(Chunk.of(PostingResult.rejected(dailyTran(1), RejectReason.OVERLIMIT)));
        // No S3 interaction before the stream is flushed and uploaded in afterStep (F4 / D-027).
        verifyNoInteractions(s3Template);

        stepExecution.setStatus(BatchStatus.COMPLETED);
        writer.afterStep(stepExecution);

        verify(s3Template).upload(eq(REJECT_BUCKET), eq(REJECT_KEY), any(InputStream.class));
        verifyNoMoreInteractions(s3Template);
    }

    @Test
    @DisplayName("F4: a reject-file S3 upload failure fails the step (FAILED + ExitStatus.FAILED), "
            + "never a false success")
    void uploadFailureFailsStep() {
        // A reject is buffered, so afterStep will attempt the (now failure-injected) S3 upload.
        writer.write(Chunk.of(PostingResult.rejected(dailyTran(1), RejectReason.OVERLIMIT)));
        doThrow(new RuntimeException("simulated S3 outage"))
                .when(s3Template).upload(anyString(), anyString(), any(InputStream.class));

        // The chunk phase completed, so the step optimistically holds COMPLETED at afterStep entry.
        stepExecution.setStatus(BatchStatus.COMPLETED);
        final ExitStatus exit = writer.afterStep(stepExecution);

        // The upload failure must flip the step to FAILED (D-027) rather than being swallowed.
        assertThat(exit).isEqualTo(ExitStatus.FAILED);
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecution.getFailureExceptions()).isNotEmpty();
    }

    @Test
    @DisplayName("Zero rejects: writer still uploads an EMPTY reject object on close (DALYREJS(+1) "
            + "GDG-generation parity)")
    void uploadsEmptyRejectObjectWhenNoRejects() {
        // A posted-only chunk buffers no reject records.
        writer.write(Chunk.of(posted(1)));

        final byte[] uploaded = closeAndCaptureUploadedBytes();

        // Production uploads unconditionally, even when empty, to mirror the legacy unconditional
        // DALYREJS(+1) generation allocation; the object is therefore present but zero-length.
        assertThat(uploaded).isEmpty();
        verify(s3Template).upload(eq(REJECT_BUCKET), eq(REJECT_KEY), any(InputStream.class));
        assertThat(stepExecution.getExecutionContext()
                .getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isZero();
    }

    // ==========================================================================================
    // Phase 6 — Reject count -> RETURN-CODE 4 contract.
    // ==========================================================================================

    @Test
    @DisplayName("Reject count is surfaced to the step context and equals the number of rejects "
            + "(the RC-4 signal); afterStep returns null (mapping delegated to the job decider)")
    void rejectCountSurfacedToStepExecutionContext() {
        writer.write(Chunk.of(
                rejected(1, RejectReason.OVERLIMIT),
                rejected(2, RejectReason.INVALID_CARD_NUMBER)));

        assertThat(stepExecution.getExecutionContext()
                .getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isEqualTo(2L);

        // Production leaves the exit status to config/BatchConfig's JobExecutionDecider, which reads
        // the rejectCount key (IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE); afterStep returns null.
        assertThat(writer.afterStep(stepExecution)).isNull();
        assertThat(stepExecution.getExecutionContext()
                .getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isEqualTo(2L);
    }

    @Test
    @DisplayName("update(ExecutionContext) publishes the running reject count under the rejectCount key")
    void updatePublishesRunningRejectCountIntoProvidedContext() {
        writer.write(Chunk.of(rejected(1, RejectReason.OVERLIMIT)));

        final ExecutionContext ctx = new ExecutionContext();
        writer.update(ctx);

        assertThat(ctx.getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Zero rejects: surfaced reject count is 0 and afterStep returns null (RC 0)")
    void rejectCountIsZeroWhenNoRejects() {
        writer.write(Chunk.of(posted(1), posted(2)));

        assertThat(stepExecution.getExecutionContext()
                .getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isZero();
        assertThat(writer.afterStep(stepExecution)).isNull();
    }

    // ==========================================================================================
    // Defensive guards and type contract (coverage + robustness).
    // ==========================================================================================

    @Test
    @DisplayName("write(null) and an empty chunk are no-ops (no persistence, counting or upload)")
    void writeNullAndEmptyChunkAreNoOps() {
        final List<PostingResult> emptyItems = List.of();
        writer.write(null);
        writer.write(new Chunk<>(emptyItems));

        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(postedCounter);
        verifyNoInteractions(rejectedCounter);
        verifyNoInteractions(s3Template);
        assertThat(stepExecution.getExecutionContext()
                .getLong(PostTransactionItemWriter.REJECT_COUNT_KEY)).isZero();
    }

    @Test
    @DisplayName("Writer honours the ItemStreamWriter + StepExecutionListener contract and the "
            + "rejectCount context key")
    void writerImplementsExpectedContracts() {
        assertThat(writer).isInstanceOf(org.springframework.batch.item.ItemStreamWriter.class);
        assertThat(writer).isInstanceOf(org.springframework.batch.core.StepExecutionListener.class);
        assertThat(PostTransactionItemWriter.REJECT_COUNT_KEY).isEqualTo("rejectCount");
    }
}
