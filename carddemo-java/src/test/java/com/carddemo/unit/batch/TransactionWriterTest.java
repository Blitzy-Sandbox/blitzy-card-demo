package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.dao.OptimisticLockingFailureException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.enums.RejectCode;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure-JVM unit tests for {@link TransactionWriter}, the persisting side of the daily-transaction
 * posting step and the Java replacement for the database-write paragraphs of COBOL program
 * {@code app/cbl/CBTRN02C.cbl} (REFERENCE-ONLY; COBOL not copied; source commit {@code 27d6c6f}).
 *
 * <p>These tests prove the CP3 review remediation of the POSTTRAN processor&harr;writer contract
 * (AAP &sect;0.5.1 row {@code POSTTRAN}; AAP-compliance item 6): the writer now consumes the
 * {@link PostingResult} emitted by the processor and</p>
 * <ul>
 *   <li>persists the processor's precomputed {@link Transaction}, {@link TransactionCategoryBalance},
 *       and {@link Account} <em>exactly once</em> &mdash; it never re-reads the account or the
 *       category balance and never re-applies the amount (no double application);</li>
 *   <li>routes a rejected result to the injected {@link RejectWriter}, which emits the byte-exact
 *       430-byte reject record and owns the {@code carddemo.batch.records.rejected} metric;</li>
 *   <li>maps an {@link OptimisticLockingFailureException} to a {@link ConcurrencyException} carrying
 *       the COBOL-equivalent message {@code "Record changed by some one else. Please review"} with
 *       no account id (the CP3 minor security finding);</li>
 *   <li>still stages each posted record as a byte-exact 350-byte {@code TRAN-RECORD} to S3.</li>
 * </ul>
 *
 * <p>The {@link S3Client} is mocked and the repositories are mocked; a <em>real</em>
 * {@link RejectWriter} (the "tested adapter") and a real {@link SimpleMeterRegistry} are used so the
 * end-to-end reject routing, its lifecycle delegation, and the metric series are all verified. No
 * Spring context, Testcontainers, or LocalStack is started.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionWriter - PostingResult contract: persist-exactly-once + reject routing (CBTRN02C)")
class TransactionWriterTest {

    /** Default output bucket (matches the {@code @Value} default in both writers). */
    private static final String BUCKET = "carddemo-batch-output";

    /** Fixed S3 object key the {@link TransactionWriter} stages posted records under. */
    private static final String SYSTRAN_KEY = "SYSTRAN";

    /** Fixed S3 object key the delegate {@link RejectWriter} writes reject records under. */
    private static final String DALYREJS_KEY = "DALYREJS";

    /** Byte-exact length of a posted {@code TRAN-RECORD} (CVTRA05Y). */
    private static final int TRAN_RECORD_LENGTH = 350;

    /** Byte-exact length of a reject record: 350 data + 4 reason + 76 description. */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Metric names asserted in these tests. */
    private static final String METRIC_PROCESSED = "carddemo.batch.records.processed";
    private static final String METRIC_REJECTED = "carddemo.batch.records.rejected";
    private static final String METRIC_AMOUNT = "carddemo.transaction.amount.total";

    /** COBOL-equivalent optimistic-lock message; intentionally carries no account id. */
    private static final String MSG_CONCURRENCY = "Record changed by some one else. Please review";

    private static final Long ACCOUNT_ID = 99_000_000_001L;

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionCategoryBalanceRepository categoryBalanceRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private S3Client s3Client;

    private SimpleMeterRegistry registry;
    private RejectWriter rejectWriter;
    private TransactionWriter writer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Real reject delegate (the "tested adapter"); shares the mock S3 client and real registry.
        rejectWriter = new RejectWriter(s3Client, registry, BUCKET);
    }

    /**
     * Builds the writer under test, allowing each test to toggle S3 staging of posted records.
     *
     * @param stageToS3 whether posted records are staged to the {@code SYSTRAN} object
     */
    private void newWriter(boolean stageToS3) {
        writer = new TransactionWriter(transactionRepository, categoryBalanceRepository,
                accountRepository, rejectWriter, s3Client, registry, BUCKET, stageToS3);
    }

    @Test
    @DisplayName("posted result persists precomputed state exactly once: no re-read, no recompute, no double-apply")
    void postedResult_persistsPrecomputedStateExactlyOnce() {
        newWriter(false);
        Transaction tx = transaction("0000000000000001", "01", 1000, new BigDecimal("100.00"));
        // The processor already applied +100.00; the writer must persist these values untouched.
        Account account = account(new BigDecimal("1100.00"), new BigDecimal("600.00"), new BigDecimal("0.00"));
        TransactionCategoryBalance balance = categoryBalance("01", 1000, new BigDecimal("100.00"));
        PostingResult posted = PostingResult.posted(tx, account, balance);

        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of(posted)));
        writer.close();

        // Persisted exactly once each, with the precomputed instances.
        verify(transactionRepository, times(1)).save(tx);
        verify(categoryBalanceRepository, times(1)).save(balance);
        ArgumentCaptor<Account> savedAccount = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(1)).saveAndFlush(savedAccount.capture());

        // No re-read of account or category balance (would be the double-apply path).
        verify(accountRepository, never()).findById(any());
        verify(categoryBalanceRepository, never()).findById(any());

        // The saved account is the processor's instance and its balance was NOT re-applied
        // (a buggy re-add would have produced 1200.00 instead of the precomputed 1100.00).
        assertThat(savedAccount.getValue()).isSameAs(account);
        assertThat(savedAccount.getValue().getAcctCurrBal()).isEqualByComparingTo("1100.00");
        assertThat(savedAccount.getValue().getAcctCurrCycCredit()).isEqualByComparingTo("600.00");

        // Staging disabled and nothing rejected -> no S3 writes at all.
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("posted result records processed + amount metrics exactly once")
    void postedResult_recordsProcessedAndAmountMetrics() {
        newWriter(false);
        Transaction tx = transaction("0000000000000002", "02", 2000, new BigDecimal("100.00"));
        PostingResult posted = PostingResult.posted(tx,
                account(new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("0.00")),
                categoryBalance("02", 2000, new BigDecimal("100.00")));

        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of(posted)));
        writer.close();

        assertThat(registry.get(METRIC_PROCESSED).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(METRIC_AMOUNT).counter().count()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("rejected result is routed to RejectWriter as a byte-exact 430-byte record and records.rejected[103]")
    void rejectedResult_routedToRejectWriter_produces430ByteRecord() throws IOException {
        newWriter(false);
        DailyTransaction daily = dailyTransaction(
                "0000000000000099", "9876543210987654", new BigDecimal("250.00"));
        // 103 = TRANSACTION RECEIVED AFTER ACCT EXPIRATION.
        PostingResult rejected = PostingResult.rejected(RejectCode.TRANSACTION_AFTER_EXPIRATION, daily);

        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of(rejected)));
        writer.close();

        // No posting writes occurred for a reject.
        verify(transactionRepository, never()).save(any());
        verify(categoryBalanceRepository, never()).save(any());
        verify(accountRepository, never()).saveAndFlush(any());

        // The delegate flushed exactly one DALYREJS object on close().
        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(1)).putObject(reqCaptor.capture(), bodyCaptor.capture());
        assertThat(reqCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(reqCaptor.getValue().key()).isEqualTo(DALYREJS_KEY);

        byte[] payload = capturedBytes(bodyCaptor);
        assertThat(payload).hasSize(REJECT_RECORD_LENGTH);
        String record = new String(payload, StandardCharsets.ISO_8859_1);
        // 350-byte data segment carries the rendered original daily transaction.
        assertThat(record.substring(0, 16)).isEqualTo("0000000000000099");          // TRAN-ID
        assertThat(record.substring(262, 278)).isEqualTo("9876543210987654");        // TRAN-CARD-NUM
        // 4-byte zero-padded reason then the 76-char description.
        assertThat(record.substring(350, 354)).isEqualTo("0103");
        assertThat(record.substring(354)).startsWith("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

        // Reject metric tagged by numeric code; no posted metric emitted.
        assertThat(registry.get(METRIC_REJECTED).tag("reason", "103").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(METRIC_PROCESSED).counter()).isNull();
    }

    @Test
    @DisplayName("optimistic-lock conflict surfaces the COBOL-equivalent message with no account id")
    void optimisticLockConflict_throwsCanonicalConcurrencyMessage() {
        newWriter(false);
        Transaction tx = transaction("0000000000000003", "01", 1000, new BigDecimal("75.00"));
        PostingResult posted = PostingResult.posted(tx,
                account(new BigDecimal("75.00"), new BigDecimal("75.00"), new BigDecimal("0.00")),
                categoryBalance("01", 1000, new BigDecimal("75.00")));
        when(accountRepository.saveAndFlush(any()))
                .thenThrow(new OptimisticLockingFailureException("version conflict"));

        writer.open(new ExecutionContext());
        Chunk<PostingResult> chunk = new Chunk<>(List.of(posted));

        assertThatThrownBy(() -> writer.write(chunk))
                .isInstanceOf(ConcurrencyException.class)
                .hasMessage(MSG_CONCURRENCY)
                .satisfies(ex -> {
                    // The message must not leak the account id (no digits at all).
                    assertThat(ex.getMessage()).doesNotContainPattern("\\d");
                    assertThat(ex.getMessage()).doesNotContain(String.valueOf(ACCOUNT_ID));
                    assertThat(((ConcurrencyException) ex).getEntity()).isEqualTo("Account");
                });
    }

    @Test
    @DisplayName("mixed chunk: accepted persisted once, rejected routed; both metric series advance")
    void mixedChunk_postsAcceptedOnce_andRoutesRejected() {
        newWriter(false);
        Transaction tx = transaction("0000000000000010", "01", 1000, new BigDecimal("40.00"));
        PostingResult posted = PostingResult.posted(tx,
                account(new BigDecimal("40.00"), new BigDecimal("40.00"), new BigDecimal("0.00")),
                categoryBalance("01", 1000, new BigDecimal("40.00")));
        PostingResult rejected = PostingResult.rejected(RejectCode.INVALID_CARD_NUMBER,
                dailyTransaction("0000000000000011", "0000000000000000", new BigDecimal("5.00")));

        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of(posted, rejected)));
        writer.close();

        verify(transactionRepository, times(1)).save(tx);
        verify(accountRepository, times(1)).saveAndFlush(any());
        assertThat(registry.get(METRIC_PROCESSED).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(METRIC_REJECTED).tag("reason", "100").counter().count()).isEqualTo(1.0);
        // Only the reject object is written (staging disabled): exactly one S3 put.
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("staging enabled flushes a single 350-byte SYSTRAN object on close")
    void stagingEnabled_flushesPosted350ByteRecordOnClose() throws IOException {
        newWriter(true);
        Transaction tx = transaction("0000000000000020", "03", 3000, new BigDecimal("12.34"));
        PostingResult posted = PostingResult.posted(tx,
                account(new BigDecimal("12.34"), new BigDecimal("12.34"), new BigDecimal("0.00")),
                categoryBalance("03", 3000, new BigDecimal("12.34")));

        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of(posted)));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(1)).putObject(reqCaptor.capture(), bodyCaptor.capture());
        assertThat(reqCaptor.getValue().key()).isEqualTo(SYSTRAN_KEY);
        assertThat(capturedBytes(bodyCaptor)).hasSize(TRAN_RECORD_LENGTH);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Transaction transaction(String id, String typeCd, int catCd, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setTranId(id);
        t.setTranTypeCd(typeCd);
        t.setTranCatCd(catCd);
        t.setTranSource("POS");
        t.setTranDesc("UNIT TEST TRANSACTION");
        t.setTranAmt(amount);
        // Numeric fields are always populated by the processor's buildTransaction (parsed from the
        // fixed-width input); set them here so the 350-byte staging render matches production.
        t.setTranMerchantId(123456789L);
        t.setTranMerchantName("ACME STORE");
        t.setTranMerchantCity("ANYTOWN");
        t.setTranMerchantZip("00000");
        t.setTranCardNum("1234567890123456");
        t.setTranOrigTs("2024-01-15-12.00.00.000000");
        t.setTranProcTs("2024-01-16-08.30.00.000000");
        return t;
    }

    private static Account account(BigDecimal currBal, BigDecimal cycCredit, BigDecimal cycDebit) {
        Account a = new Account();
        a.setAcctId(ACCOUNT_ID);
        a.setAcctCurrBal(currBal);
        a.setAcctCurrCycCredit(cycCredit);
        a.setAcctCurrCycDebit(cycDebit);
        a.setVersion(0L);
        return a;
    }

    private static TransactionCategoryBalance categoryBalance(String typeCd, int catCd, BigDecimal bal) {
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setId(new TransactionCategoryBalanceId(ACCOUNT_ID, typeCd, catCd));
        b.setTranCatBal(bal);
        return b;
    }

    private static DailyTransaction dailyTransaction(String id, String cardNum, BigDecimal amount) {
        DailyTransaction d = new DailyTransaction();
        d.setDalytranId(id);
        d.setDalytranTypeCd("02");
        d.setDalytranCatCd(2000);
        d.setDalytranSource("POS");
        d.setDalytranDesc("REJECTED UNIT TEST");
        d.setDalytranAmt(amount);
        d.setDalytranMerchantId(123456789L);
        d.setDalytranMerchantName("ACME STORE");
        d.setDalytranMerchantCity("ANYTOWN");
        d.setDalytranMerchantZip("00000");
        d.setDalytranCardNum(cardNum);
        d.setDalytranOrigTs("2024-01-15-12.00.00.000000");
        d.setDalytranProcTs("2024-01-16-08.30.00.000000");
        return d;
    }

    /**
     * Reads the bytes captured for an S3 {@link RequestBody}, mirroring {@code RejectWriterTest}.
     *
     * @param bodyCaptor the captor that captured the {@link RequestBody}
     * @return the raw bytes that would be uploaded to S3
     * @throws IOException if the content stream cannot be read
     */
    private static byte[] capturedBytes(ArgumentCaptor<RequestBody> bodyCaptor) throws IOException {
        try (InputStream in = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }
}
