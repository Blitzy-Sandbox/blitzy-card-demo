package com.cardemo.unit.batch.writers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.config.AwsConfig;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

/**
 * Fast, fully-mocked unit test for {@link TransactionWriter} &mdash; the Spring Batch
 * {@code ItemWriter<PostedTransactionResult>} that reproduces the <em>output side</em> of the legacy
 * AWS CardDemo daily-posting batch program {@code app/cbl/CBTRN02C.cbl}: paragraph
 * {@code 2000-POST-TRANSACTION} and the {@code 2700-UPDATE-TCATBAL} / {@code 2800-UPDATE-ACCOUNT-REC} /
 * {@code 2900-WRITE-TRANSACTION-FILE} cascade, plus the new GDG&rarr;S3 backup of the posted stream.
 *
 * <h2>Provenance / governance</h2>
 * <p>The COBOL source is <strong>read-only reference</strong> at the frozen baseline commit SHA
 * {@code 27d6c6f}; it is never copied here and is referenced only by SHA and paragraph locator. Per the
 * Minimal Change Clause (AAP &sect;0.7.1) and 100% behavioural-parity requirement (AAP &sect;0.7.2),
 * these tests assert COBOL-identical posting arithmetic and ordering. The base package is
 * {@code com.cardemo} (decision D-006).</p>
 *
 * <h2>The parity traps this test locks down</h2>
 * <ol>
 *   <li><strong>TCATBAL upsert</strong> ({@code 2700}): {@code '23'} (not found) creates a balance equal
 *       to the amount; {@code '00'} (found) adds the amount to the existing balance.</li>
 *   <li><strong>Account update</strong> ({@code 2800}): {@code ACCT-CURR-BAL} is incremented
 *       <em>unconditionally</em>; the signed cycle split sends {@code amount >= 0} to cycle credit and a
 *       negative amount to cycle debit (the negative value is <em>added</em> to the debit field, never
 *       negated).</li>
 *   <li><strong>One insert per chunk</strong> ({@code 2900}): all posted transactions are inserted with a
 *       single {@code saveAll}.</li>
 *   <li><strong>Account-absent</strong> (the COBOL {@code 109} INVALID KEY quirk): the writer throws so
 *       the chunk rolls back rather than committing an orphaned transaction (AAP &sect;0.7.5).</li>
 *   <li><strong>S3 backup</strong> (GDG&rarr;S3): the posted stream is written to the config-resolved
 *       {@code carddemo-batch-output} bucket under a deterministic, generation-prefixed key.</li>
 * </ol>
 *
 * <h2>Test strategy</h2>
 * <p>Pure unit test: {@code @ExtendWith(MockitoExtension.class)} with {@code @Mock} repositories and a
 * mocked {@link S3Template}; a real {@link AwsConfig.AwsResourceProperties} (its defaults expose the
 * {@code carddemo-batch-output} bucket) and a fixed {@link Clock} so the stamped timestamp and S3 key are
 * deterministic. No Spring context, database, AWS or Testcontainers. Mockito runs in default
 * {@code STRICT_STUBS}; each test stubs only what its path consumes. All money is {@link BigDecimal} built
 * from {@link String} literals and asserted with {@code isEqualByComparingTo} (never {@code equals},
 * AAP &sect;0.7.3).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionWriter — CBTRN02C 2000-POST cascade (2700/2800/2900) + GDG→S3 backup (SHA 27d6c6f)")
class TransactionWriterTest {

    /** Account id resolved upstream (COBOL XREF-ACCT-ID), carried on the accepted result. */
    private static final Long ACCT_ID = 1L;

    /** Two-character transaction type code ({@code DALYTRAN-TYPE-CD}). */
    private static final String TYPE_CD = "01";

    /** Numeric transaction category code ({@code DALYTRAN-CAT-CD}). */
    private static final Integer CAT_CD = 1;

    /** A recognisable test PAN ({@code DALYTRAN-CARD-NUM}). */
    private static final String CARD_NUM = "4111111111111111";

    /**
     * Fixed instant ({@code 2024-01-15T10:20:30.680Z}) so the stamped {@code TRAN-PROC-TS} fallback and
     * the S3 generation prefix ({@code 20240115}) are deterministic. 680 ms &rarr; 68 hundredths.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-15T10:20:30.680Z"), ZoneOffset.UTC);

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private S3Template s3Template;

    @Captor
    private ArgumentCaptor<List<Transaction>> savedTransactionsCaptor;

    @Captor
    private ArgumentCaptor<TransactionCategoryBalance> savedBalanceCaptor;

    @Captor
    private ArgumentCaptor<Account> savedAccountCaptor;

    /** Real (not mocked) name holder; defaults expose getS3().getBatchOutputBucket() == carddemo-batch-output. */
    private final AwsConfig.AwsResourceProperties awsProps = new AwsConfig.AwsResourceProperties();

    private TransactionWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TransactionWriter(transactionRepository, categoryBalanceRepository,
                accountRepository, s3Template, awsProps, FIXED_CLOCK);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static Account account(final String currBal, final String cycCredit, final String cycDebit) {
        final Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setAcctCurrBal(new BigDecimal(currBal));
        account.setAcctCurrCycCredit(new BigDecimal(cycCredit));
        account.setAcctCurrCycDebit(new BigDecimal(cycDebit));
        return account;
    }

    private static Transaction transaction(final String tranId, final String amount) {
        final Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd(TYPE_CD);
        transaction.setTranCatCd(CAT_CD);
        transaction.setTranAmt(new BigDecimal(amount));
        transaction.setTranCardNum(CARD_NUM);
        return transaction;
    }

    /** Builds an ACCEPTED result. crossReference is intentionally null: the writer derives the account id from account(). */
    private static PostedTransactionResult accepted(final Transaction transaction, final Account account) {
        return new PostedTransactionResult(transaction, RejectCode.NONE, new DailyTransaction(), account, null);
    }

    private static TransactionCategoryBalanceId key() {
        return new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD);
    }

    // ---------------------------------------------------------------------------------------------
    // Step B — 2700-UPDATE-TCATBAL upsert
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Step B — 2700-UPDATE-TCATBAL upsert")
    class CategoryBalanceUpsert {

        @Test
        @DisplayName("'23' not found → 2700-A creates a balance equal to the amount and saves (insert)")
        void notFoundCreatesBalanceEqualToAmount() {
            final Transaction txn = transaction("0000000000000001", "100.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account("0.00", "0.00", "0.00")));
            // categoryBalanceRepository.findById is left unstubbed → Mockito returns Optional.empty() (the '23' path).

            writer.write(Chunk.of(accepted(txn, account("0.00", "0.00", "0.00"))));

            verify(categoryBalanceRepository).save(savedBalanceCaptor.capture());
            final TransactionCategoryBalance saved = savedBalanceCaptor.getValue();
            assertThat(saved.getId()).isEqualTo(key());
            assertThat(saved.getTranCatBal()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("'00' found → 2700-B adds the amount to the existing balance and saves (update)")
        void foundAddsAmountToExistingBalance() {
            final Transaction txn = transaction("0000000000000002", "25.50");
            final TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(key(), new BigDecimal("74.50"));
            when(categoryBalanceRepository.findById(key())).thenReturn(Optional.of(existing));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account("0.00", "0.00", "0.00")));

            writer.write(Chunk.of(accepted(txn, account("0.00", "0.00", "0.00"))));

            verify(categoryBalanceRepository).save(savedBalanceCaptor.capture());
            // 74.50 + 25.50 = 100.00 (BigDecimal.add, scale preserved).
            assertThat(savedBalanceCaptor.getValue().getTranCatBal()).isEqualByComparingTo("100.00");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step C — 2800-UPDATE-ACCOUNT-REC balance + signed cycle split
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Step C — 2800-UPDATE-ACCOUNT-REC")
    class AccountUpdate {

        @Test
        @DisplayName("positive amount → ACCT-CURR-BAL += amt and cycle CREDIT += amt")
        void positiveAmountAddsToCurrAndCredit() {
            final Account account = account("1000.00", "200.00", "-50.00");
            final Transaction txn = transaction("0000000000000003", "150.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            writer.write(Chunk.of(accepted(txn, account)));

            verify(accountRepository).save(savedAccountCaptor.capture());
            final Account saved = savedAccountCaptor.getValue();
            assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("1150.00");     // unconditional add
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("350.00"); // 200 + 150
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("-50.00");  // untouched
        }

        @Test
        @DisplayName("negative amount → ACCT-CURR-BAL += amt and the negative value is ADDED to cycle DEBIT")
        void negativeAmountAddsToCurrAndDebit() {
            final Account account = account("1000.00", "200.00", "-50.00");
            final Transaction txn = transaction("0000000000000004", "-30.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            writer.write(Chunk.of(accepted(txn, account)));

            verify(accountRepository).save(savedAccountCaptor.capture());
            final Account saved = savedAccountCaptor.getValue();
            assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("970.00");      // 1000 + (-30), unconditional
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("200.00"); // untouched
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("-80.00");  // -50 + (-30), NOT negated
        }

        @Test
        @DisplayName("zero amount → signum()>=0 routes to cycle CREDIT (matches COBOL IF >= 0)")
        void zeroAmountGoesToCredit() {
            final Account account = account("1000.00", "200.00", "-50.00");
            final Transaction txn = transaction("0000000000000005", "0.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            writer.write(Chunk.of(accepted(txn, account)));

            verify(accountRepository).save(savedAccountCaptor.capture());
            final Account saved = savedAccountCaptor.getValue();
            assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("1000.00");
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("200.00"); // 200 + 0 (credit branch)
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("-50.00");  // untouched
        }

        @Test
        @DisplayName("account absent → RecordNotFoundException (COBOL 109 INVALID KEY → chunk rollback)")
        void accountAbsentThrows() {
            final Account carried = account("0.00", "0.00", "0.00");
            final Transaction txn = transaction("0000000000000006", "10.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> writer.write(Chunk.of(accepted(txn, carried))))
                    .isInstanceOf(RecordNotFoundException.class);

            // The transaction is NEVER inserted when the account is missing (no orphaned posting).
            verify(transactionRepository, never()).saveAll(any());
            verifyNoInteractions(s3Template);
        }

        @Test
        @DisplayName("same account across the chunk accumulates on the managed instance (read-modify-write parity)")
        void sameAccountAccumulatesAcrossChunk() {
            final Account account = account("1000.00", "0.00", "0.00");
            // findById returns the SAME managed instance both times (mirrors the chunk first-level cache).
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            writer.write(Chunk.of(
                    accepted(transaction("0000000000000007", "100.00"), account),
                    accepted(transaction("0000000000000008", "25.00"), account)));

            assertThat(account.getAcctCurrBal()).isEqualByComparingTo("1125.00");      // 1000 + 100 + 25
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("125.00"); // 0 + 100 + 25
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step D — 2900-WRITE-TRANSACTION-FILE (one bulk insert per chunk)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Step D — 2900 one saveAll per chunk")
    class TransactionInsert {

        @Test
        @DisplayName("saveAll is called exactly once with every accepted transaction in the chunk")
        void saveAllOncePerChunkWithAllTransactions() {
            final Account account = account("0.00", "0.00", "0.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            final Transaction t1 = transaction("0000000000000009", "10.00");
            final Transaction t2 = transaction("0000000000000010", "20.00");

            writer.write(Chunk.of(accepted(t1, account), accepted(t2, account)));

            verify(transactionRepository).saveAll(savedTransactionsCaptor.capture());
            assertThat(savedTransactionsCaptor.getValue()).containsExactly(t1, t2);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step A — 2000 TRAN-PROC-TS stamping (fallback only)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Step A — TRAN-PROC-TS stamping")
    class ProcessingTimestamp {

        @Test
        @DisplayName("absent TRAN-PROC-TS is stamped from the clock at COBOL hundredths precision")
        void stampsWhenAbsent() {
            final Account account = account("0.00", "0.00", "0.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            final Transaction txn = transaction("0000000000000011", "10.00");
            assertThat(txn.getTranProcTs()).isNull();

            writer.write(Chunk.of(accepted(txn, account)));

            // 2024-01-15T10:20:30.680 truncated to hundredths (68) then zero-padded -> .680000.
            assertThat(txn.getTranProcTs()).isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 20, 30, 680_000_000));
        }

        @Test
        @DisplayName("a TRAN-PROC-TS already set by the processor is preserved (no double-stamp)")
        void preservesWhenAlreadySet() {
            final Account account = account("0.00", "0.00", "0.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            final Transaction txn = transaction("0000000000000012", "10.00");
            final LocalDateTime preset = LocalDateTime.of(2022, 7, 19, 23, 12, 32, 680_000_000);
            txn.setTranProcTs(preset);

            writer.write(Chunk.of(accepted(txn, account)));

            assertThat(txn.getTranProcTs()).isEqualTo(preset);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step E — GDG → S3 backup
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Step E — GDG → S3 posted-stream backup")
    class S3Backup {

        @Test
        @DisplayName("backs up to the config-resolved carddemo-batch-output bucket under a deterministic key")
        void backsUpToOutputBucketWithDeterministicKey() {
            final Account account = account("0.00", "0.00", "0.00");
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
            final Transaction first = transaction("0000000000000013", "10.00");
            final Transaction last = transaction("0000000000000099", "20.00");

            writer.write(Chunk.of(accepted(first, account), accepted(last, account)));

            final ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(s3Template).upload(bucketCaptor.capture(), keyCaptor.capture(),
                    any(InputStream.class), any(ObjectMetadata.class));

            assertThat(bucketCaptor.getValue()).isEqualTo("carddemo-batch-output");
            // posted/<yyyyMMdd from fixed clock>/<firstTranId>-<lastTranId>.dat — deterministic & idempotent.
            assertThat(keyCaptor.getValue()).isEqualTo("posted/20240115/0000000000000013-0000000000000099.dat");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Defensive routing — accepted-only sink
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Accepted-only sink (rejects routed to RejectWriter)")
    class AcceptedOnly {

        @Test
        @DisplayName("rejected items are skipped — no DB write, no S3 backup")
        void rejectedItemsSkipped() {
            final PostedTransactionResult rejected = new PostedTransactionResult(
                    null, RejectCode.INVALID_CARD_NUMBER, new DailyTransaction(), null, null);

            writer.write(Chunk.of(rejected));

            verifyNoInteractions(transactionRepository, categoryBalanceRepository, accountRepository, s3Template);
        }

        @Test
        @DisplayName("a chunk with no accepted items performs no insert and no backup")
        void allRejectedChunkNoOp() {
            final PostedTransactionResult r1 = new PostedTransactionResult(
                    null, RejectCode.ACCOUNT_NOT_FOUND, new DailyTransaction(), null, null);
            final PostedTransactionResult r2 = new PostedTransactionResult(
                    null, RejectCode.OVERLIMIT_TRANSACTION, new DailyTransaction(), null, null);

            writer.write(Chunk.of(r1, r2));

            verify(transactionRepository, never()).saveAll(any());
            verifyNoInteractions(s3Template);
        }
    }
}
