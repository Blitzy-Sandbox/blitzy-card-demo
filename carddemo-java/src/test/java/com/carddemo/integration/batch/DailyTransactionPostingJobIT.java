package com.carddemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.jobs.DailyTransactionPostingJob;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Job-level integration test for the {@code dailyTransactionPostingJob} Spring Batch job, proving
 * behavioral parity with the mainframe daily-posting pipeline {@code POSTTRAN.jcl} +
 * {@code CBTRN02C.cbl} (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL
 * is copied).
 *
 * <p><strong>Why this test exists (resolves D-029 / the CP4 double-apply defect).</strong> The prior
 * CP4 wiring let two components apply the same accepted transaction: the
 * {@code TransactionPostingProcessor} mutated the <em>managed</em> {@link Account} (and category
 * balance) it read inside the chunk transaction &mdash; which Hibernate dirty-flushes at chunk
 * commit &mdash; <em>and</em> the {@code TransactionWriter} independently re-read and re-applied the
 * amount, so every accepted transaction was posted twice. The component unit tests passed because
 * each component was exercised in isolation; only a test that runs the <strong>real processor and
 * the real writer together through the actual job against a real JPA/PostgreSQL persistence
 * context</strong> reproduces the managed-entity dirty-flush mechanism. This test is that proof: it
 * launches the production job end-to-end and asserts that an accepted transaction changes the
 * account balance, the cycle credit, and the category balance <strong>exactly once</strong>.</p>
 *
 * <p>It extends {@link AbstractBatchIntegrationTest} and reuses all of its scaffolding (singleton
 * Testcontainers PostgreSQL + LocalStack, the {@code @DynamicPropertySource} wiring and AWS
 * self-provisioning, the fixture locator, the S3 helpers, the {@code JobLauncher}, the per-test
 * {@code @BeforeEach} S3 cleanup, and the shared constants). No container, property,
 * {@code @SpringBootTest}, {@code @ActiveProfiles}, {@code @Testcontainers}, or {@code @Tag}
 * scaffolding is re-declared here.</p>
 *
 * <p>To keep delta assertions deterministic against the shared singleton database (the seeded rows
 * are never mutated, exactly as {@code CombineTransactionsJobIT} does), the test provisions its own
 * collision-free account and cross-reference: an account id ({@value #TEST_ACCT_ID}) far outside the
 * seeded {@code 1..50} range and a non-numeric test card that cannot match any seeded 16-digit PAN.
 * The single staged daily record is minted from the real {@code dailytran.txt} fixture (so every
 * non-overwritten field already parses under the strict reader), overwriting only the transaction
 * id, the amount, and the card number; it is therefore guaranteed to pass all four validation stages
 * (cross-reference found, account found, within the generous credit limit, and unexpired).</p>
 */
class DailyTransactionPostingJobIT extends AbstractBatchIntegrationTest {

    /** Collision-free test account id (far outside the seeded {@code 1..50} range). */
    private static final Long TEST_ACCT_ID = 99999999999L;

    /** Collision-free, non-numeric 16-character test card (cannot match a seeded numeric PAN). */
    private static final String TEST_CARD = "ZZPOSTITCARD0001";

    /** Collision-free 16-character transaction id for the single accepted record. */
    private static final String TEST_TRAN_ID = "ZZPOSTITTXN00001";

    /** Transaction type code carried by the base fixture record (CVTRA06Y {@code TYPE-CD}). */
    private static final String TYPE_CD = "01";

    /** Transaction category code carried by the base fixture record (CVTRA06Y {@code CAT-CD}). */
    private static final int CAT_CD = 1;

    /**
     * The transaction amount, encoded as the 11-character zoned-decimal {@code S9(09)V99} trailing
     * overpunch for {@code +100.00}: ten digits {@code "0000001000"} followed by {@code '{'} (units
     * digit 0, positive). Decodes to {@code 100.00}.
     */
    private static final String AMOUNT_ENCODED = "0000001000{";

    /** Expected applied amount (decoded form of {@link #AMOUNT_ENCODED}). */
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /** S3 key under the input bucket for the single-record staging object. */
    private static final String INPUT_KEY = "dailytran-posting-it.txt";

    /** 0-indexed CVTRA06Y field bounds overwritten on the base record (all same-width). */
    private static final int TRAN_ID_START = 0;
    private static final int TRAN_ID_END = 16;
    private static final int AMOUNT_START = 132;
    private static final int AMOUNT_END = 143;
    private static final int CARD_START = 262;
    private static final int CARD_END = 278;

    /** The posting job under test, injected by its bean name. */
    @Autowired
    @Qualifier("dailyTransactionPostingJob")
    private Job dailyTransactionPostingJob;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * Removes the test-only rows after each test so the shared singleton database is left exactly as
     * it was found (the seeded rows are never touched). Deletes in primary-key order; there are no
     * inter-table foreign keys among the CardDemo domain tables, so order is not constrained.
     */
    @AfterEach
    void cleanupTestRows() {
        transactionRepository.findById(TEST_TRAN_ID).ifPresent(transactionRepository::delete);
        categoryBalanceRepository.findById(new TransactionCategoryBalanceId(TEST_ACCT_ID, TYPE_CD, CAT_CD))
                .ifPresent(categoryBalanceRepository::delete);
        cardCrossReferenceRepository.findById(TEST_CARD).ifPresent(cardCrossReferenceRepository::delete);
        accountRepository.findById(TEST_ACCT_ID).ifPresent(accountRepository::delete);
    }

    /**
     * Launches the real posting job for a single accepted daily transaction and asserts that the
     * account balance, the current-cycle credit, and the category balance each change EXACTLY ONCE.
     *
     * <p>Starting from a zeroed account, a single {@code +100.00} transaction must leave the current
     * balance and cycle credit at {@code 100.00} (not {@code 200.00}) and create the category balance
     * at {@code 100.00} (not {@code 200.00}). A double-apply (the D-029 defect) would yield the
     * doubled values; asserting the single-apply values is the byte-precise regression guard.</p>
     *
     * @throws Exception if the fixture cannot be read or the job launch fails
     */
    @Test
    void singleAcceptedTransaction_appliesAccountAndCategoryExactlyOnce() throws Exception {
        // Arrange: clear any leftover test rows from a prior aborted run, then provision a
        // collision-free, zeroed account and its cross-reference (seed rows are never mutated).
        cleanupTestRows();
        accountRepository.saveAndFlush(newZeroedTestAccount());
        cardCrossReferenceRepository.saveAndFlush(newTestXref());

        // Stage exactly one accepted 350-byte daily record (newline-terminated for the line reader).
        putS3Object(BUCKET_INPUT, INPUT_KEY, buildAcceptedRecord());

        // Act: launch the production job pointing the step-scoped reader at the staged object.
        JobExecution execution = launch(
                dailyTransactionPostingJob,
                baseParams()
                        .addString("inputLocation", "s3://" + BUCKET_INPUT + "/" + INPUT_KEY)
                        .toJobParameters());

        // Assert: the job completed cleanly with zero rejects (no RC=4 warning, no DALYREJS object).
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(DailyTransactionPostingJob.COMPLETED_WITH_REJECTS);
        assertThat(countRejectRecords()).isZero();

        // Exactly one posted transaction row carrying our id was persisted.
        assertThat(transactionRepository.findById(TEST_TRAN_ID)).isPresent();

        // ------------------------------------------------------------------------------------------
        // HEADLINE PARITY (resolves D-029 double-apply) against the REAL JPA managed-entity path:
        // the account balance and cycle credit changed EXACTLY ONCE. The processor projects only and
        // never mutates the managed account; the writer is the sole apply path. A double-apply would
        // have produced 200.00 here.
        // ------------------------------------------------------------------------------------------
        Account after = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(after.getAcctCurrBal()).isEqualByComparingTo(AMOUNT);          // 0.00 + 100.00, once
        assertThat(after.getAcctCurrCycCredit()).isEqualByComparingTo(AMOUNT);    // positive -> cycle credit, once
        assertThat(after.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");     // debit untouched

        // The category balance was created once at the transaction amount (not doubled).
        TransactionCategoryBalance balance = categoryBalanceRepository
                .findById(new TransactionCategoryBalanceId(TEST_ACCT_ID, TYPE_CD, CAT_CD))
                .orElseThrow();
        assertThat(balance.getTranCatBal()).isEqualByComparingTo(AMOUNT);
    }

    /**
     * Builds a zeroed, postable test account: a generous credit limit and a far-future expiration so
     * the staged record passes the overlimit (C) and expiration (D) checks, with zero starting
     * balance and cycle totals so the post-run values equal the single applied amount.
     *
     * @return the new test account
     */
    private static Account newZeroedTestAccount() {
        Account account = new Account();
        account.setAcctId(TEST_ACCT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("0.00"));
        account.setAcctCreditLimit(new BigDecimal("100000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("0.00"));
        account.setAcctOpenDate("2020-01-01");
        account.setAcctExpiraionDate("2099-12-31");
        account.setAcctReissueDate("2020-01-01");
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctAddrZip("78701");
        account.setAcctGroupId("DEFAULT   ");
        return account;
    }

    /**
     * Builds the cross-reference that resolves {@link #TEST_CARD} to {@link #TEST_ACCT_ID}, so stage A
     * ({@code 1500-A-LOOKUP-XREF}) finds the card and stage B finds the account.
     *
     * @return the new test cross-reference
     */
    private static CardCrossReference newTestXref() {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(TEST_CARD);
        xref.setXrefCustId(1L);
        xref.setXrefAcctId(TEST_ACCT_ID);
        return xref;
    }

    /**
     * Mints the single accepted 350-byte CVTRA06Y record by cloning the first record of the real
     * {@code dailytran.txt} fixture (so every non-overwritten field already parses under the strict
     * fixed-width reader) and overwriting only the three same-width fields the test controls: the
     * transaction id, the {@code +100.00} amount, and the test card number. The record is
     * newline-terminated so the {@code FlatFileItemReader} reads it as exactly one line.
     *
     * @return the staged object bytes (one 350-character record plus a trailing newline)
     * @throws IOException if the {@code dailytran.txt} fixture is located but cannot be read
     */
    private byte[] buildAcceptedRecord() throws IOException {
        byte[] fixture = requireFixtureBytes(DAILY_TRAN_KEY);
        byte[] base = Arrays.copyOfRange(fixture, 0, DAILY_TRAN_RECORD_LENGTH);
        StringBuilder record = new StringBuilder(new String(base, StandardCharsets.ISO_8859_1));
        record.replace(TRAN_ID_START, TRAN_ID_END, TEST_TRAN_ID);
        record.replace(AMOUNT_START, AMOUNT_END, AMOUNT_ENCODED);
        record.replace(CARD_START, CARD_END, TEST_CARD);
        String line = record.toString();
        // Defensive width guard: the three replacements are all same-width, so the record must remain
        // exactly 350 characters; a drift here would make the strict tokenizer reject the record.
        if (line.length() != DAILY_TRAN_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "Minted record is " + line.length() + " chars, expected " + DAILY_TRAN_RECORD_LENGTH);
        }
        return (line + "\n").getBytes(StandardCharsets.ISO_8859_1);
    }
}
