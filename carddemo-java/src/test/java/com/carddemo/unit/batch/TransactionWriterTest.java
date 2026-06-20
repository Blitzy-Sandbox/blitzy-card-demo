package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.batch.writers.TransactionWriter.PostedTransaction;
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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Pure-JVM unit tests for {@link TransactionWriter}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the daily
 * transaction posting program {@code app/cbl/CBTRN02C.cbl} and its three posting paragraphs
 * &mdash; {@code 2900-WRITE-TRANSACTION-FILE} (copybook {@code CVTRA05Y}, RECLN 350),
 * {@code 2700-UPDATE-TCATBAL} (copybook {@code CVTRA01Y}), and {@code 2800-UPDATE-ACCOUNT-REC}
 * (copybook {@code CVACT01Y}).</p>
 *
 * <p>These tests exercise the migrated writer through its public {@code open -> write -> close}
 * {@link org.springframework.batch.item.ItemStreamWriter} lifecycle with no Spring context,
 * Testcontainers, or LocalStack: the three repositories and the {@link S3Client} are Mockito mocks,
 * while a real {@link SimpleMeterRegistry} and a real {@link MetricsConfig} (its core-metrics binder
 * bound to that registry) exercise production telemetry wiring. The emitted S3
 * {@link PutObjectRequest} and {@link RequestBody} are captured and asserted byte-for-byte.</p>
 *
 * <p>Verified behaviours (AAP sections 0.7 / 0.8.2 / 0.8.4):</p>
 * <ul>
 *   <li>the exact persistence order: transaction save &rarr; TCATBAL upsert &rarr; account update
 *       &rarr; telemetry &rarr; optional S3 stage;</li>
 *   <li>TCATBAL find-or-create (absent balance created with the amount; present balance incremented
 *       by the amount);</li>
 *   <li>the account {@code acctCurrBal += amount} update with the credit/debit sign-split
 *       ({@code signum() >= 0} accrues to the cycle credit, otherwise to the cycle debit);</li>
 *   <li>a missing account at posting time yields {@link TransactionPostingException} with reject
 *       code {@code 109};</li>
 *   <li>an {@link OptimisticLockingFailureException} on the eager account flush maps to
 *       {@link ConcurrencyException} (entity {@code "Account"});</li>
 *   <li>the byte-exact 350-byte {@code TRAN-RECORD} with signed zoned-decimal overpunch on the
 *       amount field;</li>
 *   <li>the {@code stage-to-s3} toggle (enabled stages on close; disabled writes no object while DB
 *       posting still occurs);</li>
 *   <li>the writer carries no {@link Transactional} annotation (it relies on the Spring Batch
 *       chunk-level transaction);</li>
 *   <li>the {@code carddemo.batch.records.processed} counter and the signed
 *       {@code carddemo.transaction.amount.total} gauge.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionWriter - posting order, sign-split, 109, concurrency, 350-byte overpunch (CBTRN02C)")
class TransactionWriterTest {

    /** Default output bucket from {@code carddemo.aws.s3.bucket-output}; passed explicitly here. */
    private static final String BUCKET = "carddemo-batch-output";

    /** Owning account id used across the posting scenarios (COBOL {@code XREF-ACCT-ID}). */
    private static final Long ACCOUNT_ID = 12345L;

    /** Repository for the posted transaction (COBOL {@code 2900-WRITE-TRANSACTION-FILE}). */
    @Mock
    private TransactionRepository transactionRepository;

    /** Repository for the transaction-category balance (COBOL {@code 2700-UPDATE-TCATBAL}). */
    @Mock
    private TransactionCategoryBalanceRepository tcatbalRepository;

    /** Repository for the owning account (COBOL {@code 2800-UPDATE-ACCOUNT-REC}). */
    @Mock
    private AccountRepository accountRepository;

    /** Mocked synchronous S3 client used for the optional staged-file upload. */
    @Mock
    private S3Client s3Client;

    /** Real in-memory Micrometer registry so meter assertions exercise production wiring. */
    private SimpleMeterRegistry registry;

    /** Real metrics holder; its core-metrics binder is bound to {@link #registry} per test. */
    private MetricsConfig metricsConfig;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsConfig = new MetricsConfig();
        // Bind the core-metrics binder so the processed counter and the signed amount gauge exist
        // (pre-registered at zero), exactly as Spring Boot applies the MeterBinder bean at runtime.
        metricsConfig.carddemoCoreMetrics().bindTo(registry);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    /**
     * Constructs the system under test with the mocked repositories, the mocked S3 client, the real
     * registry, and the real (bound) metrics holder, mirroring the production constructor argument
     * order.
     *
     * @param stageToS3 whether S3 staging is enabled for this writer instance
     * @return a fresh {@link TransactionWriter}
     */
    private TransactionWriter newWriter(boolean stageToS3) {
        return new TransactionWriter(
                transactionRepository,
                tcatbalRepository,
                accountRepository,
                s3Client,
                registry,
                metricsConfig,
                BUCKET,
                stageToS3);
    }

    /**
     * Builds a fully-populated {@link Transaction} so that every {@code TRAN-RECORD} field has a
     * deterministic value for the byte-layout assertions.
     *
     * @param id     the transaction id ({@code TRAN-ID}, {@code X(16)})
     * @param typeCd the transaction type code ({@code TRAN-TYPE-CD}, {@code X(02)})
     * @param catCd  the transaction category code ({@code TRAN-CAT-CD}, {@code 9(04)})
     * @param amt    the transaction amount as a decimal string ({@code TRAN-AMT}, {@code S9(09)V99})
     * @return a populated transaction
     */
    private static Transaction txn(String id, String typeCd, int catCd, String amt) {
        Transaction t = new Transaction();
        t.setTranId(id);
        t.setTranTypeCd(typeCd);
        t.setTranCatCd(catCd);
        t.setTranSource("System");
        t.setTranDesc("PURCHASE");
        t.setTranAmt(new BigDecimal(amt));
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
     * Builds an {@link Account} with the three balance fields the writer mutates; the {@code @Version}
     * field is left at its default (a fresh, never-persisted instance).
     *
     * @param id        the account id
     * @param currBal   the current balance as a decimal string
     * @param cycCredit the current-cycle credit total as a decimal string
     * @param cycDebit  the current-cycle debit total as a decimal string
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
     * Builds an existing {@link TransactionCategoryBalance} keyed by the three-part composite key.
     *
     * @param acct the account id component of the key
     * @param type the transaction-type-code component of the key
     * @param cat  the category-code component of the key
     * @param bal  the existing balance as a decimal string
     * @return a populated category balance
     */
    private static TransactionCategoryBalance tcatbal(Long acct, String type, int cat, String bal) {
        TransactionCategoryBalance tcb = new TransactionCategoryBalance();
        tcb.setId(new TransactionCategoryBalanceId(acct, type, cat));
        tcb.setTranCatBal(new BigDecimal(bal));
        return tcb;
    }

    /**
     * Reads the captured S3 upload body into a byte array.
     *
     * @param bodyCaptor the captor that recorded the {@link RequestBody} passed to {@code putObject}
     * @return the full uploaded payload as bytes
     * @throws IOException if the request body stream cannot be read
     */
    private static byte[] capturedBytes(ArgumentCaptor<RequestBody> bodyCaptor) throws IOException {
        try (InputStream in = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("positive amount: saves txn, creates TCATBAL=amount, credits account, in exact order")
    void post_positiveAmount_savesTxn_createsTcatbal_creditsAccount_inExactOrder() {
        Transaction t = txn("DT00000000000001", "01", 5, "150.00");
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCOUNT_ID, "01", 5)))
                .thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new PostedTransaction(t, ACCOUNT_ID)));

        // 2900: the posted transaction is persisted.
        verify(transactionRepository).save(t);

        // 2700 (create): an absent balance is created with the transaction amount.
        ArgumentCaptor<TransactionCategoryBalance> tcbCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(tcatbalRepository).save(tcbCaptor.capture());
        assertThat(tcbCaptor.getValue().getTranCatBal()).isEqualByComparingTo("150.00");

        // 2800 (credit): non-negative amount accrues to the current balance and the cycle credit.
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(acctCaptor.capture());
        Account saved = acctCaptor.getValue();
        assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("250.00");
        assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("150.00");
        assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");

        // Exact persistence order: save -> TCATBAL find/save -> account find/saveAndFlush.
        InOrder inOrder = inOrder(transactionRepository, tcatbalRepository, accountRepository);
        inOrder.verify(transactionRepository).save(t);
        inOrder.verify(tcatbalRepository).findById(any());
        inOrder.verify(tcatbalRepository).save(any());
        inOrder.verify(accountRepository).findById(ACCOUNT_ID);
        inOrder.verify(accountRepository).saveAndFlush(any());

        // Telemetry: one processed record; signed running total carries the (positive) amount.
        assertThat(registry.find(MetricsConfig.BATCH_RECORDS_PROCESSED).counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).gauge().value())
                .isEqualTo(150.0);
    }

    @Test
    @DisplayName("existing TCATBAL: the transaction amount is added to the existing balance")
    void post_tcatbalPresent_addsAmountToExistingBalance() {
        Transaction t = txn("DT00000000000002", "01", 5, "150.00");
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCOUNT_ID, "01", 5)))
                .thenReturn(Optional.of(tcatbal(ACCOUNT_ID, "01", 5, "40.00")));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new PostedTransaction(t, ACCOUNT_ID)));

        ArgumentCaptor<TransactionCategoryBalance> tcbCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(tcatbalRepository).save(tcbCaptor.capture());
        assertThat(tcbCaptor.getValue().getTranCatBal()).isEqualByComparingTo("190.00");
    }

    @Test
    @DisplayName("negative amount: debits the account; the signed gauge holds the negative total")
    void post_negativeAmount_debitsAccount() {
        Transaction t = txn("DT00000000000003", "02", 7, "-30.00");
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(ACCOUNT_ID, "02", 7)))
                .thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new PostedTransaction(t, ACCOUNT_ID)));

        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(acctCaptor.capture());
        Account saved = acctCaptor.getValue();
        assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("70.00");
        assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("-30.00");
        assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");

        // The amount gauge accumulates the SIGNED amount (DoubleAdder-backed), not its magnitude.
        assertThat(registry.find(MetricsConfig.TRANSACTION_AMOUNT_TOTAL).gauge().value())
                .isEqualTo(-30.0);
    }

    @Test
    @DisplayName("missing account at posting: TransactionPostingException(109), no account flush, no processed metric")
    void post_missingAccount_throwsTransactionPostingException109() {
        // The TCATBAL step precedes the account lookup, so its repository is exercised first.
        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        Chunk<PostedTransaction> chunk =
                Chunk.of(new PostedTransaction(txn("DT00000000000004", "01", 5, "10.00"), ACCOUNT_ID));

        assertThatThrownBy(() -> writer.write(chunk))
                .isInstanceOfSatisfying(
                        TransactionPostingException.class,
                        ex -> assertThat(ex.getRejectCode()).isEqualTo(109));

        // The account is never flushed and telemetry is never reached (the exception precedes both).
        verify(accountRepository, never()).saveAndFlush(any());
        assertThat(registry.find(MetricsConfig.BATCH_RECORDS_PROCESSED).counter().count()).isZero();
    }

    @Test
    @DisplayName("optimistic-lock conflict on the account flush maps to ConcurrencyException(entity=Account)")
    void post_optimisticLockConflict_throwsConcurrencyException() {
        Account acct = account(ACCOUNT_ID, "100.00", "0.00", "0.00");
        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));
        when(accountRepository.saveAndFlush(any()))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        Chunk<PostedTransaction> chunk =
                Chunk.of(new PostedTransaction(txn("DT00000000000005", "01", 5, "10.00"), ACCOUNT_ID));

        assertThat(catchThrowable(() -> writer.write(chunk)))
                .isInstanceOfSatisfying(
                        ConcurrencyException.class,
                        ce -> assertThat(ce.getEntity()).isEqualTo("Account"));
    }

    @Test
    @DisplayName("close stages a 350-byte TRAN-RECORD with positive overpunch (504.77 -> ...G)")
    void close_stagesTransactionRecord_350Bytes_withPositiveOverpunch() throws IOException {
        Transaction t = txn("DT00000000000001", "01", 5, "504.77");
        Account acct = account(ACCOUNT_ID, "0.00", "0.00", "0.00");
        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(true);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new PostedTransaction(t, ACCOUNT_ID)));
        writer.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

        // S3 destination contract: GDG base key SYSTRAN in the configured output bucket.
        assertThat(reqCaptor.getValue().key()).isEqualTo("SYSTRAN");
        assertThat(reqCaptor.getValue().bucket()).isEqualTo(BUCKET);

        byte[] bytes = capturedBytes(bodyCaptor);
        assertThat(bytes).hasSize(350);

        String rec = new String(bytes, StandardCharsets.ISO_8859_1);
        // Fixed-width layout fields (CVTRA05Y, 0-indexed offsets).
        assertThat(rec.substring(0, 16)).isEqualTo("DT00000000000001");
        assertThat(rec.substring(16, 18)).isEqualTo("01");
        assertThat(rec.substring(18, 22)).isEqualTo("0005");
        // TRAN-AMT S9(09)V99: 504.77 -> 50477 cents -> zoned overpunch units digit 7 = 'G'.
        assertThat(rec.substring(132, 143)).isEqualTo("0000005047G");
        // TRAN-MERCHANT-ID 9(09): 123456789 zero-padded to nine digits.
        assertThat(rec.substring(143, 152)).isEqualTo("123456789");
    }

    @Test
    @DisplayName("staged record uses a negative overpunch for a negative amount (-919.00 -> ...})")
    void stagedRecord_negativeAmount_usesNegativeOverpunch() throws IOException {
        Transaction t = txn("DT00000000000006", "02", 7, "-919.00");
        Account acct = account(ACCOUNT_ID, "0.00", "0.00", "0.00");
        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(true);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new PostedTransaction(t, ACCOUNT_ID)));
        writer.close();

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

        String rec = new String(capturedBytes(bodyCaptor), StandardCharsets.ISO_8859_1);
        // -919.00 -> 91900 cents -> negative zoned overpunch units digit 0 = '}'.
        assertThat(rec.substring(132, 143)).isEqualTo("0000009190}");
    }

    @Test
    @DisplayName("stage-to-s3 disabled: no S3 object is written, but DB posting still occurs")
    void stageToS3False_noStaging() {
        Transaction t = txn("DT00000000000007", "01", 5, "10.00");
        Account acct = account(ACCOUNT_ID, "0.00", "0.00", "0.00");
        when(tcatbalRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(acct));

        TransactionWriter writer = newWriter(false);
        writer.open(new ExecutionContext());
        writer.write(Chunk.of(new PostedTransaction(t, ACCOUNT_ID)));
        writer.close();

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        // Persistence is independent of staging: the transaction is still saved.
        verify(transactionRepository).save(any());
    }

    @Test
    @DisplayName("writer carries no @Transactional (relies on the Spring Batch chunk transaction)")
    void writer_isNotAnnotatedTransactional() {
        assertThat(TransactionWriter.class.isAnnotationPresent(Transactional.class)).isFalse();
        for (Method m : TransactionWriter.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Transactional.class))
                    .as("method %s must not be annotated @Transactional", m.getName())
                    .isFalse();
        }
    }
}
