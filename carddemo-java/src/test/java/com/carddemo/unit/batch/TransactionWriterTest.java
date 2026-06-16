package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.exception.ConcurrencyException;
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
 * Pure-JVM unit tests for {@link TransactionWriter}, the Java replacement for the database
 * write side of COBOL program {@code app/cbl/CBTRN02C.cbl} (REFERENCE-ONLY; COBOL not copied;
 * source commit {@code 27d6c6f}). The writer reproduces three posting paragraphs:
 * {@code 2900-WRITE-TRANSACTION-FILE} (persist the transaction), {@code 2700-UPDATE-TCATBAL}
 * (find-or-create the category balance), and {@code 2800-UPDATE-ACCOUNT-REC} (versioned account
 * update; a missing account is reject code {@code 109}).
 *
 * <p>The writer is exercised strictly through its public
 * {@link TransactionWriter#open(ExecutionContext) open} &rarr;
 * {@link TransactionWriter#write(Chunk) write} &rarr; {@link TransactionWriter#close() close}
 * lifecycle. The three repositories and the {@link S3Client} are Mockito mocks; a real
 * {@link SimpleMeterRegistry} verifies the posting metrics. No Spring context, Testcontainers,
 * or LocalStack is started.</p>
 *
 * <p>These tests prove the binding parity rules (AAP &sect;0.7 / &sect;0.8.2 / &sect;0.8.4): the
 * exact persistence order (save transaction &rarr; TCATBAL upsert &rarr; account update &rarr;
 * metrics &rarr; S3 stage), the TCATBAL find-or-create semantics, the
 * {@code acctCurrBal += amount} update with the {@code signum() >= 0} credit / else debit
 * sign-split, the missing-account &rarr; {@link TransactionPostingException} (109) mapping, the
 * {@link OptimisticLockingFailureException} &rarr; {@link ConcurrencyException} mapping, the
 * byte-exact 350-byte staged {@code TRAN-RECORD} with signed zoned-decimal overpunch, the
 * {@code stage-to-s3} toggle, and the structural rule that the writer carries no
 * {@code @Transactional} (it relies on the Spring Batch chunk transaction).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionWriter - posting order, sign-split, reject 109, concurrency, 350-byte overpunch (CBTRN02C 2900/2700/2800)")
class TransactionWriterTest {

    /** Default output bucket; matches the {@code @Value} default on the production constructor. */
    private static final String BUCKET = "carddemo-batch-output";

    /** Fixed S3 object key for the staged posted-transaction file (COBOL {@code SYSTRAN} GDG base). */
    private static final String STAGE_KEY = "SYSTRAN";

    /** Content type the writer sets on the binary, fixed-width staged object. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /** Fixed length of a {@code TRAN-RECORD} (copybook {@code CVTRA05Y}), in bytes. */
    private static final int RECORD_LENGTH = 350;

    /** Owning account id used across the tests (COBOL {@code XREF-ACCT-ID}). */
    private static final long ACCOUNT_ID = 12345L;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionCategoryBalanceRepository tcatbalRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private S3Client s3Client;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    // ---------------------------------------------------------------------------------------
    // Phase 2 - persistence ORDER + TCATBAL create + credit sign-split (positive amount)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("positive amount: save txn -> create TCATBAL(=amount) -> credit account, in exact order")
    void post_positiveAmount_savesTxn_createsTcatbal_creditsAccount_inExactOrder() {
        Transaction t = txn("DT00000000000001", "01", 5, "150.00");
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");

        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCOUNT_ID, "01", 5)))
                .thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new TransactionWriter.PostedTransaction(t, ACCOUNT_ID)));

        // 2900-WRITE-TRANSACTION-FILE: the posted transaction is persisted.
        verify(transactionRepository).save(t);

        // 2700-UPDATE-TCATBAL (create path): a new balance is created equal to the amount.
        ArgumentCaptor<TransactionCategoryBalance> tcbCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(tcatbalRepository).save(tcbCaptor.capture());
        assertThat(tcbCaptor.getValue().getTranCatBal()).isEqualByComparingTo(new BigDecimal("150.00"));

        // 2800-UPDATE-ACCOUNT-REC (credit path): balance and current-cycle credit both rise by amount.
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(acctCaptor.capture());
        Account saved = acctCaptor.getValue();
        assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("250.00");
        assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("150.00");
        assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");

        // Exact ordering of the three database operations (CBTRN02C 2900 -> 2700 -> 2800).
        InOrder inOrder = inOrder(transactionRepository, tcatbalRepository, accountRepository);
        inOrder.verify(transactionRepository).save(t);
        inOrder.verify(tcatbalRepository).findById(any());
        inOrder.verify(tcatbalRepository).save(any());
        inOrder.verify(accountRepository).findById(ACCOUNT_ID);
        inOrder.verify(accountRepository).saveAndFlush(any());

        // Metrics: one record processed; running amount total carries the absolute amount.
        assertThat(registry.get(MetricsConfig.BATCH_RECORDS_PROCESSED).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).counter().count()).isEqualTo(150.0);
    }

    // ---------------------------------------------------------------------------------------
    // Phase 3 - TCATBAL update (present) + debit sign-split (negative amount)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("TCATBAL present: amount is added to the existing balance (40.00 + 150.00 = 190.00)")
    void post_tcatbalPresent_addsAmountToExistingBalance() {
        Transaction t = txn("DT00000000000001", "01", 5, "150.00");
        TransactionCategoryBalance existing = tcatbal(ACCOUNT_ID, "01", 5, "40.00");
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");

        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCOUNT_ID, "01", 5)))
                .thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new TransactionWriter.PostedTransaction(t, ACCOUNT_ID)));

        ArgumentCaptor<TransactionCategoryBalance> tcbCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(tcatbalRepository).save(tcbCaptor.capture());
        assertThat(tcbCaptor.getValue().getTranCatBal()).isEqualByComparingTo("190.00");
    }

    @Test
    @DisplayName("negative amount: balance falls and current-cycle DEBIT carries the amount")
    void post_negativeAmount_debitsAccount() {
        Transaction t = txn("DT00000000000002", "02", 7, "-30.00");
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");

        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new TransactionWriter.PostedTransaction(t, ACCOUNT_ID)));

        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(acctCaptor.capture());
        Account saved = acctCaptor.getValue();
        assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("70.00");
        assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("-30.00");
        assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");

        // Amount metric uses the absolute value (Math.abs).
        assertThat(registry.get(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).counter().count()).isEqualTo(30.0);
    }

    // ---------------------------------------------------------------------------------------
    // Phase 4 - error paths
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("missing account -> TransactionPostingException(109); no account write, no metric")
    void post_missingAccount_throwsTransactionPostingException109() {
        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());

        Throwable thrown = catchThrowable(() -> writer.write(
                Chunk.of(new TransactionWriter.PostedTransaction(txn("DT00000000000003", "01", 5, "10.00"), ACCOUNT_ID))));

        assertThat(thrown).isInstanceOf(TransactionPostingException.class);
        assertThat(((TransactionPostingException) thrown).getRejectCode()).isEqualTo(109);

        verify(accountRepository, never()).saveAndFlush(any());
        // The exception precedes the metrics increment, so the processed counter is never created.
        assertThat(registry.find(MetricsConfig.BATCH_RECORDS_PROCESSED).counter()).isNull();
    }

    @Test
    @DisplayName("optimistic-lock conflict on account saveAndFlush -> ConcurrencyException(entity=Account)")
    void post_optimisticLockConflict_throwsConcurrencyException() {
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");

        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));
        when(accountRepository.saveAndFlush(any()))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());

        Throwable thrown = catchThrowable(() -> writer.write(
                Chunk.of(new TransactionWriter.PostedTransaction(txn("DT00000000000004", "01", 5, "10.00"), ACCOUNT_ID))));

        assertThat(thrown).isInstanceOf(ConcurrencyException.class);
        assertThat(((ConcurrencyException) thrown).getEntity()).isEqualTo("Account");
    }

    // ---------------------------------------------------------------------------------------
    // Phase 5 - 350-byte staged record + signed overpunch (via the S3 stage path)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("stage-to-s3: close writes one 350-byte SYSTRAN record with positive overpunch (504.77 -> ...G)")
    void close_stagesTransactionRecord_350Bytes_withPositiveOverpunch() throws Exception {
        Account acct = account(ACCOUNT_ID, "1000.00", "0.00", "0.00");

        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(true);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new TransactionWriter.PostedTransaction(txn("DT00000000000001", "01", 5, "504.77"), ACCOUNT_ID)));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest req = reqCaptor.getValue();
        assertThat(req.key()).isEqualTo(STAGE_KEY);
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.contentType()).isEqualTo(CONTENT_TYPE);

        byte[] bytes = capturedBytes(bodyCaptor);
        assertThat(bytes).hasSize(RECORD_LENGTH);

        String rec = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(rec.substring(0, 16)).isEqualTo("DT00000000000001");   // TRAN-ID  X(16)
        assertThat(rec.substring(16, 18)).isEqualTo("01");                // TRAN-TYPE-CD X(02)
        assertThat(rec.substring(18, 22)).isEqualTo("0005");              // TRAN-CAT-CD  9(04)
        assertThat(rec.substring(132, 143)).isEqualTo("0000005047G");     // TRAN-AMT S9(09)V99: 504.77 -> units 7 -> 'G'
        assertThat(rec.substring(143, 152)).isEqualTo("123456789");       // TRAN-MERCHANT-ID 9(09)
    }

    @Test
    @DisplayName("stage-to-s3: negative amount uses the negative overpunch (-919.00 -> ...})")
    void stagedRecord_negativeAmount_usesNegativeOverpunch() throws Exception {
        Account acct = account(ACCOUNT_ID, "1000.00", "0.00", "0.00");

        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(true);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new TransactionWriter.PostedTransaction(txn("DT00000000000002", "02", 7, "-919.00"), ACCOUNT_ID)));
        writer.close();

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

        String rec = new String(capturedBytes(bodyCaptor), StandardCharsets.ISO_8859_1);
        assertThat(rec.substring(132, 143)).isEqualTo("0000009190}");     // -919.00 -> units 0, negative -> '}'
    }

    @Test
    @DisplayName("stage-to-s3 disabled: no S3 object is written, but the database writes still occur")
    void stageToS3False_noStaging() {
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");

        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new TransactionWriter.PostedTransaction(txn("DT00000000000001", "01", 5, "10.00"), ACCOUNT_ID)));
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(transactionRepository).save(any(Transaction.class));
    }

    // ---------------------------------------------------------------------------------------
    // Phase 6 - NO @Transactional (structural rule)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("writer declares NO @Transactional on the class or any method (relies on the chunk transaction)")
    void writer_isNotAnnotatedTransactional() {
        assertThat(TransactionWriter.class.isAnnotationPresent(Transactional.class)).isFalse();
        for (Method m : TransactionWriter.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Transactional.class))
                    .as("method %s must not be annotated @Transactional", m.getName())
                    .isFalse();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Constructs the writer under test with the supplied staging toggle, the Mockito repository
     * and S3 mocks, the real meter registry, and the default output bucket.
     *
     * @param stageToS3 whether posted records are staged to S3
     * @return a fresh {@link TransactionWriter}
     */
    private TransactionWriter newWriter(boolean stageToS3) {
        return new TransactionWriter(transactionRepository, tcatbalRepository, accountRepository,
                s3Client, registry, BUCKET, stageToS3);
    }

    /**
     * Builds a fully populated {@link Transaction} so the 350-byte {@code TRAN-RECORD} can be
     * assembled without null fields.
     *
     * @param id     the 16-character transaction id
     * @param typeCd the 2-character transaction type code
     * @param catCd  the transaction category code
     * @param amt    the signed transaction amount (string parsed to {@link BigDecimal})
     * @return a populated transaction
     */
    private static Transaction txn(String id, String typeCd, int catCd, String amt) {
        Transaction t = new Transaction();
        t.setTranId(id);
        t.setTranTypeCd(typeCd);
        t.setTranCatCd(catCd);
        t.setTranAmt(new BigDecimal(amt));
        t.setTranSource("System");
        t.setTranDesc("PURCHASE");
        t.setTranMerchantId(123456789L);
        t.setTranMerchantName("ACME");
        t.setTranMerchantCity("SPRINGFIELD");
        t.setTranMerchantZip("12345");
        t.setTranCardNum("1234567890123456");
        t.setTranOrigTs("2022-07-18-00.00.00.000000");
        t.setTranProcTs("2022-07-18-10.15.30.123456");
        return t;
    }

    /**
     * Builds an {@link Account} with the three monetary fields the writer reads and updates. The
     * {@code @Version} is left at its default; the other columns are irrelevant to posting.
     *
     * @param id        the account id
     * @param currBal   the current balance
     * @param cycCredit the current-cycle credit total
     * @param cycDebit  the current-cycle debit total
     * @return a populated account
     */
    private static Account account(Long id, String currBal, String cycCredit, String cycDebit) {
        Account a = new Account();
        a.setAcctId(id);
        a.setAcctCurrBal(new BigDecimal(currBal));
        a.setAcctCurrCycCredit(new BigDecimal(cycCredit));
        a.setAcctCurrCycDebit(new BigDecimal(cycDebit));
        return a;
    }

    /**
     * Builds an existing {@link TransactionCategoryBalance} for the find-or-create update path.
     *
     * @param acct the owning account id
     * @param type the transaction type code
     * @param cat  the transaction category code
     * @param bal  the existing balance (string parsed to {@link BigDecimal})
     * @return a populated category balance
     */
    private static TransactionCategoryBalance tcatbal(Long acct, String type, int cat, String bal) {
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setId(new TransactionCategoryBalanceId(acct, type, cat));
        b.setTranCatBal(new BigDecimal(bal));
        return b;
    }

    /**
     * Reads the bytes captured as the S3 object payload.
     *
     * @param bodyCaptor the captor that captured the {@link RequestBody} passed to
     *                   {@link S3Client#putObject(PutObjectRequest, RequestBody)}
     * @return the full payload byte array
     * @throws IOException if the captured content stream cannot be read
     */
    private static byte[] capturedBytes(ArgumentCaptor<RequestBody> bodyCaptor) throws IOException {
        try (InputStream in = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }
}
