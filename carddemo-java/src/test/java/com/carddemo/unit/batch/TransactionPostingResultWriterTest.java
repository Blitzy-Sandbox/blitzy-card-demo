package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.processors.TransactionPostingProcessor;
import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.batch.writers.RejectWriter;
import com.carddemo.batch.writers.TransactionPostingResultWriter;
import com.carddemo.batch.writers.TransactionWriter;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Batch-step wiring test for {@link TransactionPostingResultWriter}: it connects the real
 * {@link TransactionPostingProcessor} through the adapter to the real {@link TransactionWriter} and
 * {@link RejectWriter}, proving the POSTTRAN processor&rarr;writer contract is complete and that
 * posted and rejected results are routed to the correct delegate.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the COBOL daily
 * transaction posting program {@code app/cbl/CBTRN02C.cbl} validates each daily transaction
 * ({@code 1500-VALIDATE-TRAN}) and then either posts it ({@code 2000-POST-TRANSACTION} &rarr;
 * {@code 2900}/{@code 2700}/{@code 2800}) or writes it to the {@code DALYREJS} reject file
 * ({@code 2500-WRITE-REJECT-REC}, {@code RECFM=F, LRECL=430}). The processor emits one
 * {@link PostingResult} per record; this adapter classifies each result and forwards it to the
 * matching writer, the bridge that lets a single posting step drive both outputs.</p>
 *
 * <p>The test runs entirely on the JVM with no Spring context: repositories and the {@code S3Client}
 * are Mockito mocks; the {@link MetricsConfig} and {@link SimpleMeterRegistry} are real. Each daily
 * transaction is driven through {@code processor.process(...)} to obtain a real {@code PostingResult},
 * then through the adapter's {@code open -> write -> close} {@code ItemStreamWriter} lifecycle; the
 * emitted S3 objects are captured and asserted byte-for-byte.</p>
 *
 * <p>Verified behaviours:</p>
 * <ul>
 *   <li>a posted result is routed to {@link TransactionWriter} (transaction persisted, account and
 *       category balance updated <strong>exactly once</strong> &mdash; the writer is the sole apply
 *       path, the processor projects only &mdash; and the posted record staged to S3 under the
 *       master-transaction backup key {@code TRANSACT.BKUP});</li>
 *   <li>a rejected result is routed to {@link RejectWriter} (430-byte record under key
 *       {@code DALYREJS}) with the 350-byte daily-transaction image reconstructed from the entity
 *       fields, including the trailing-sign zoned-decimal overpunch amount;</li>
 *   <li>a mixed chunk fans out to both delegates in a single {@code write};</li>
 *   <li>the close lifecycle is forwarded to both delegates so each flushes (and releases) its
 *       per-run buffer.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingResultWriter - POSTTRAN processor->adapter->writers wiring (CBTRN02C)")
class TransactionPostingResultWriterTest {

    /** Default output bucket from {@code carddemo.aws.s3.bucket-output}; passed explicitly here. */
    private static final String BUCKET = "carddemo-batch-output";

    /** Total length of the byte-exact reject record: 350 data + 4 reason + 76 description. */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Length of the reconstructed daily-transaction image portion of the reject record. */
    private static final int DALYTRAN_IMAGE_LENGTH = 350;

    /** Card number used as the cross-reference key (16 digits). */
    private static final String CARD = "4111111111111111";

    /** Cross-reference account id resolved for {@link #CARD}. */
    private static final Long ACCT = 12345678901L;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private S3Client s3Client;

    /** Real in-memory registry so the processed/rejected counters exercise production wiring. */
    private SimpleMeterRegistry registry;

    /** Real shared services (no Spring context required). */
    private MetricsConfig metricsConfig;

    /** System under test plus its two real delegates, reassembled before each test. */
    private TransactionPostingProcessor processor;
    private TransactionWriter transactionWriter;
    private RejectWriter rejectWriter;
    private TransactionPostingResultWriter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsConfig = new MetricsConfig();

        // Fixed clock so the posted transaction's processing timestamp is deterministic.
        Clock clock = Clock.fixed(Instant.parse("2024-01-16T02:00:00Z"), ZoneOffset.UTC);

        // The processor is read-only validation+projection (4-arg ctor): it does NOT receive the
        // category-balance repository or file-status mapper, because it never mutates the account or
        // upserts the category balance. Those updates are applied exactly once by the TransactionWriter
        // (the sole apply path), which is why this slice test can assert exactly-once below.
        processor = new TransactionPostingProcessor(
                cardCrossReferenceRepository, accountRepository, registry, clock);
        transactionWriter = new TransactionWriter(
                transactionRepository, categoryBalanceRepository, accountRepository,
                s3Client, registry, metricsConfig, BUCKET, true);
        rejectWriter = new RejectWriter(s3Client, registry, BUCKET);
        adapter = new TransactionPostingResultWriter(transactionWriter, rejectWriter);
    }

    /**
     * Builds a fully-populated daily transaction with the supplied amount. All other fields are
     * fixed so the reconstructed image can be asserted byte-for-byte. The values are the
     * already-trimmed form the reader produces (left-justified source fields), so reconstruction
     * re-pads them to the fixed width.
     *
     * @param amount the signed transaction amount
     * @return a populated {@link DailyTransaction}
     */
    private static DailyTransaction dailyTransaction(BigDecimal amount) {
        DailyTransaction tran = new DailyTransaction();
        tran.setDalytranId("TXN0000000000001");
        tran.setDalytranTypeCd("PR");
        tran.setDalytranCatCd(5);
        tran.setDalytranSource("POS");
        tran.setDalytranDesc("GROCERY PURCHASE");
        tran.setDalytranAmt(amount);
        tran.setDalytranMerchantId(999L);
        tran.setDalytranMerchantName("ACME STORE");
        tran.setDalytranMerchantCity("NEW YORK");
        tran.setDalytranMerchantZip("10001");
        tran.setDalytranCardNum(CARD);
        tran.setDalytranOrigTs("2024-01-15-10.30.00.000000");
        tran.setDalytranProcTs("2024-01-16-02.00.00.000000");
        return tran;
    }

    /**
     * Builds an account that passes the overlimit and expiration checks for a positive amount.
     *
     * @return a postable {@link Account}
     */
    private static Account postableAccount() {
        Account account = new Account();
        account.setAcctId(ACCT);
        account.setAcctCurrBal(new BigDecimal("500.00"));
        account.setAcctCreditLimit(new BigDecimal("10000.00"));
        account.setAcctCurrCycCredit(BigDecimal.ZERO.setScale(2));
        account.setAcctCurrCycDebit(BigDecimal.ZERO.setScale(2));
        account.setAcctExpiraionDate("2099-12-31");
        return account;
    }

    /**
     * Reads the captured S3 upload body that matches the given object key into a byte array.
     *
     * @param requests the captured {@link PutObjectRequest} values, in invocation order
     * @param bodies   the captured {@link RequestBody} values, in invocation order
     * @param key      the S3 object key whose body is wanted
     * @return the matching uploaded payload as bytes
     * @throws IOException if the request body stream cannot be read
     */
    private static byte[] bodyForKey(List<PutObjectRequest> requests, List<RequestBody> bodies, String key)
            throws IOException {
        for (int i = 0; i < requests.size(); i++) {
            if (key.equals(requests.get(i).key())) {
                try (InputStream in = bodies.get(i).contentStreamProvider().newStream()) {
                    return in.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("No S3 putObject captured for key " + key);
    }

    @Test
    @DisplayName("posted result routes to TransactionWriter: persisted, account+category applied exactly once, staged to TRANSACT.BKUP")
    void postedResultRoutesToTransactionWriter() throws IOException {
        Account account = postableAccount();
        when(cardCrossReferenceRepository.findById(CARD)).thenReturn(Optional.of(xref()));
        when(accountRepository.findById(ACCT)).thenReturn(Optional.of(account));
        when(categoryBalanceRepository.findById(any())).thenReturn(Optional.empty());

        PostingResult result = processor.process(dailyTransaction(new BigDecimal("100.00")));
        assertThat(result.rejected()).isFalse();

        adapter.open(new ExecutionContext());
        adapter.write(Chunk.of(result));
        adapter.close();

        // Routed to the DB + S3 writer: the posted transaction is persisted and the account updated.
        verify(transactionRepository).save(argThat(t -> "TXN0000000000001".equals(t.getTranId())));

        // ----------------------------------------------------------------------------------------
        // HEADLINE PARITY (resolves D-029 double-apply): the accepted transaction's account balance
        // and category balance must change EXACTLY ONCE across the whole processor->writer chunk.
        // The processor is read-only projection (it carries the account untouched); the writer is the
        // sole apply path. If the processor also mutated the managed account/category (the prior
        // defect), the +100.00 amount would be applied twice (currBal 700.00, cycCredit 200.00,
        // category 200.00). Asserting the single-apply values proves the fix.
        // ----------------------------------------------------------------------------------------
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(acctCaptor.capture());
        Account saved = acctCaptor.getValue();
        // 500.00 + 100.00 applied once = 600.00 (NOT 700.00).
        assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("600.00");
        // Positive amount adds to the cycle credit once = 100.00 (NOT 200.00); debit untouched.
        assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("100.00");
        assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");

        ArgumentCaptor<TransactionCategoryBalance> balCaptor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(categoryBalanceRepository).save(balCaptor.capture());
        // New category balance initialized to the amount applied once = 100.00 (NOT 200.00).
        assertThat(balCaptor.getValue().getTranCatBal()).isEqualByComparingTo("100.00");

        // Posted record staged to S3 under the master-transaction backup key TRANSACT.BKUP (the
        // input COMBTRAN reads first), NOT SYSTRAN (the INTCALC interest output); no reject object.
        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());
        assertThat(reqCaptor.getValue().key()).isEqualTo("TRANSACT.BKUP");
        byte[] staged = bodyForKey(reqCaptor.getAllValues(), bodyCaptor.getAllValues(), "TRANSACT.BKUP");
        // One posted TRAN-RECORD (CVTRA05Y, 350 bytes), no delimiter.
        assertThat(staged).hasSize(350);
    }

    @Test
    @DisplayName("rejected result routes to RejectWriter with the reconstructed 350-byte image and 0100 trailer")
    void rejectedResultRoutesToRejectWriter() throws IOException {
        // Card not found in the cross-reference -> reject 100 (1500-A-LOOKUP-XREF INVALID KEY).
        when(cardCrossReferenceRepository.findById(CARD)).thenReturn(Optional.empty());

        PostingResult result = processor.process(dailyTransaction(new BigDecimal("123.45")));
        assertThat(result.rejected()).isTrue();
        assertThat(result.rejectCode()).isEqualTo(100);

        adapter.open(new ExecutionContext());
        adapter.write(Chunk.of(result));
        adapter.close();

        // The posted writer wrote nothing (no posted records); only the reject object is emitted.
        verify(transactionRepository, never()).save(any(Transaction.class));

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());
        assertThat(reqCaptor.getValue().key()).isEqualTo("DALYREJS");

        byte[] bytes = bodyForKey(reqCaptor.getAllValues(), bodyCaptor.getAllValues(), "DALYREJS");
        assertThat(bytes).hasSize(REJECT_RECORD_LENGTH);
        String rec = new String(bytes, StandardCharsets.ISO_8859_1);

        // Reject trailer: 4-digit zero-padded reason + 76-char left-justified description.
        assertThat(rec.substring(DALYTRAN_IMAGE_LENGTH, DALYTRAN_IMAGE_LENGTH + 4)).isEqualTo("0100");
        assertThat(rec.substring(DALYTRAN_IMAGE_LENGTH + 4).strip()).isEqualTo("INVALID CARD NUMBER FOUND");

        // Reconstructed 350-byte CVTRA06Y image (inverse of DailyTransactionReader offsets).
        String image = rec.substring(0, DALYTRAN_IMAGE_LENGTH);
        assertThat(image.substring(0, 16)).isEqualTo("TXN0000000000001");   // DALYTRAN-ID  X(16)
        assertThat(image.substring(16, 18)).isEqualTo("PR");                 // TYPE-CD      X(02)
        assertThat(image.substring(18, 22)).isEqualTo("0005");               // CAT-CD       9(04)
        assertThat(image.substring(22, 32)).isEqualTo("POS       ");         // SOURCE       X(10)
        assertThat(image.substring(32, 132).strip()).isEqualTo("GROCERY PURCHASE"); // DESC  X(100)
        assertThat(image.substring(132, 143)).isEqualTo("0000001234E");      // AMT S9(09)V99 (+123.45)
        assertThat(image.substring(143, 152)).isEqualTo("000000999");        // MERCHANT-ID  9(09)
        assertThat(image.substring(262, 278)).isEqualTo(CARD);               // CARD-NUM     X(16)
        assertThat(image.substring(278, 304)).isEqualTo("2024-01-15-10.30.00.000000"); // ORIG-TS X(26)
        assertThat(image.substring(304, 330)).isEqualTo("2024-01-16-02.00.00.000000"); // PROC-TS X(26)
        assertThat(image.substring(330, 350)).isEqualTo(" ".repeat(20));     // FILLER       X(20)
    }

    @Test
    @DisplayName("negative amount is encoded with the negative trailing overpunch (N for -...4)")
    void rejectImageEncodesNegativeOverpunch() throws IOException {
        when(cardCrossReferenceRepository.findById(CARD)).thenReturn(Optional.empty());

        PostingResult result = processor.process(dailyTransaction(new BigDecimal("-123.45")));

        adapter.open(new ExecutionContext());
        adapter.write(Chunk.of(result));
        adapter.close();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

        byte[] bytes = bodyForKey(reqCaptor.getAllValues(), bodyCaptor.getAllValues(), "DALYREJS");
        String image = new String(bytes, StandardCharsets.ISO_8859_1).substring(0, DALYTRAN_IMAGE_LENGTH);
        // -123.45 -> 11 digits "00000012345", units digit 5 with negative overpunch -> 'N'.
        assertThat(image.substring(132, 143)).isEqualTo("0000001234N");
    }

    @Test
    @DisplayName("mixed chunk fans posted->TRANSACT.BKUP and rejected->DALYREJS in a single write")
    void mixedChunkRoutesEachResultToItsDelegate() throws IOException {
        Account account = postableAccount();
        // Posted card resolves; the reject card (a different number) does not.
        String rejectCard = "5500000000000004";
        DailyTransaction postedTran = dailyTransaction(new BigDecimal("100.00"));
        DailyTransaction rejectedTran = dailyTransaction(new BigDecimal("50.00"));
        rejectedTran.setDalytranId("TXN0000000000002");
        rejectedTran.setDalytranCardNum(rejectCard);

        when(cardCrossReferenceRepository.findById(CARD)).thenReturn(Optional.of(xref()));
        when(cardCrossReferenceRepository.findById(rejectCard)).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCT)).thenReturn(Optional.of(account));
        when(categoryBalanceRepository.findById(any())).thenReturn(Optional.empty());

        PostingResult posted = processor.process(postedTran);
        PostingResult rejected = processor.process(rejectedTran);
        assertThat(posted.rejected()).isFalse();
        assertThat(rejected.rejected()).isTrue();

        adapter.open(new ExecutionContext());
        adapter.write(Chunk.of(posted, rejected));
        adapter.close();

        // Posted persisted; both S3 objects (TRANSACT.BKUP posted, DALYREJS reject) written.
        verify(transactionRepository).save(argThat(t -> "TXN0000000000001".equals(t.getTranId())));

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, org.mockito.Mockito.times(2)).putObject(reqCaptor.capture(), bodyCaptor.capture());

        byte[] staged = bodyForKey(reqCaptor.getAllValues(), bodyCaptor.getAllValues(), "TRANSACT.BKUP");
        byte[] rejects = bodyForKey(reqCaptor.getAllValues(), bodyCaptor.getAllValues(), "DALYREJS");
        assertThat(staged).hasSize(350);
        assertThat(rejects).hasSize(REJECT_RECORD_LENGTH);
        // The reject image carries the rejected record's id, proving correct routing per item.
        String rejectImage = new String(rejects, StandardCharsets.ISO_8859_1).substring(0, 16);
        assertThat(rejectImage).isEqualTo("TXN0000000000002");
    }

    @Test
    @DisplayName("close with no items forwards to both delegates and writes nothing (no empty objects)")
    void closeWithNoItemsWritesNothing() {
        adapter.open(new ExecutionContext());
        adapter.write(new Chunk<>(List.of()));
        adapter.close();

        // Neither delegate buffered anything, so no S3 object is created (COBOL: no empty generation).
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /**
     * Builds the cross-reference record that resolves {@link #CARD} to {@link #ACCT}.
     *
     * @return a {@link CardCrossReference} for the postable card
     */
    private static CardCrossReference xref() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(CARD);
        xref.setXrefCustId(1L);
        xref.setXrefAcctId(ACCT);
        return xref;
    }
}
