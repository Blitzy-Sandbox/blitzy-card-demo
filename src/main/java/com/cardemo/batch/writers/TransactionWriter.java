/*
 * TransactionWriter.java — Spring Batch ItemWriter for Transaction Posting
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - CBTRN02C.cbl (731 lines) — Daily Transaction Posting Engine
 *   - CVTRA05Y.cpy — TRAN-RECORD layout (350 bytes)
 *   - CVACT01Y.cpy — ACCT-RECORD layout (300 bytes)
 *   - CVTRA01Y.cpy — TRAN-CAT-BAL-RECORD layout (50 bytes)
 *   - CVACT03Y.cpy — CARD-XREF-RECORD layout (50 bytes)
 *
 * This class replaces the write/posting portion of the COBOL batch program
 * CBTRN02C.cbl. The original program performs a 4-stage validation cascade
 * on daily transaction records (DALYTRAN dataset) and — for valid records —
 * posts them to the TRANSACT VSAM KSDS dataset while updating the account
 * balance (ACCTDAT) and transaction category balance (TCATBALF) datasets.
 *
 * This writer handles steps 3 and 4 of the CBTRN02C pipeline:
 *   Step 3: Post validated transactions to the TRANSACT table
 *   Step 4: Update TCATBALF and ACCTDAT balance records
 *
 * COBOL Paragraph → Java Method Mapping:
 *   3000-POST-TRANSACTION           → write() / postTransaction()
 *   3100-UPDATE-TCATBAL             → updateCategoryBalance()
 *   3200-UPDATE-ACCOUNT-BALANCE     → updateAccountBalance()
 *   3300-WRITE-TRANSACTION-RECORD   → transactionRepository.saveAll()
 *   3400-WRITE-S3-BACKUP            → backupToS3() [replaces TRANSACT GDG backup]
 *
 * Key Behavioral Parity Requirements:
 *   - ALL arithmetic uses BigDecimal — zero floating-point substitution
 *   - BigDecimal comparisons use compareTo(), never equals() (scale-insensitive)
 *   - Interest formula precision: (balance × rate) / 1200 with HALF_EVEN rounding
 *   - Credit/debit segregation in account cycle accumulators
 *   - Atomicity via @Transactional (replacing COBOL SYNCPOINT semantics)
 *   - S3 backup is non-fatal — failure logged but does not abort the transaction
 */
package com.cardemo.batch.writers;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Batch {@link ItemWriter} that posts validated daily transactions to
 * the PostgreSQL {@code transactions} table, updates account balances, and
 * updates transaction category balance records.
 *
 * <p>This writer replaces the posting logic in COBOL program {@code CBTRN02C.cbl}
 * (731 lines — Daily Transaction Posting Engine). The original program validates
 * daily transactions (DALYTRAN dataset) through a 4-stage cascade (handled by
 * {@code TransactionPostingProcessor}), then posts valid transactions to the
 * TRANSACT VSAM KSDS dataset and updates two additional datasets:
 * <ul>
 *   <li>{@code ACCTDAT} — Account balance and cycle accumulator updates</li>
 *   <li>{@code TCATBALF} — Transaction category balance running totals</li>
 * </ul></p>
 *
 * <h3>Transaction Posting Logic (COBOL §3000-POST-TRANSACTION)</h3>
 * <p>For each validated transaction in the chunk:</p>
 * <ol>
 *   <li><strong>Resolve account</strong> — Look up the card cross-reference by
 *       card number to find the account ID, then fetch the account record</li>
 *   <li><strong>Update category balance</strong> (§3100-UPDATE-TCATBAL) —
 *       Find or create the composite-key record in TCATBALF and add the
 *       transaction amount to the running balance</li>
 *   <li><strong>Update account balance</strong> (§3200-UPDATE-ACCOUNT-BALANCE) —
 *       Add the transaction amount to the account's current balance, and
 *       segregate into credit or debit cycle accumulators</li>
 *   <li><strong>Save transaction</strong> (§3300-WRITE-TRANSACTION-RECORD) —
 *       Persist the transaction record to the transactions table</li>
 * </ol>
 *
 * <h3>S3 Backup (Non-Fatal)</h3>
 * <p>After successful database persistence, a backup copy is written to S3
 * (replacing the COBOL GDG generation pattern). Backup failures are logged
 * but do not cause the transaction to roll back — this matches the COBOL
 * behavior where the GDG backup step runs after SYNCPOINT commit.</p>
 *
 * <h3>Atomicity</h3>
 * <p>The {@link #write(Chunk)} method is annotated with {@link Transactional},
 * ensuring that all database operations within a chunk (category balance
 * updates, account balance updates, and transaction inserts) are committed
 * atomically or rolled back together — equivalent to the COBOL
 * {@code EXEC CICS SYNCPOINT} / {@code SYNCPOINT ROLLBACK} pattern.</p>
 *
 * @see Transaction
 * @see Account
 * @see TransactionCategoryBalance
 * @see TransactionPostingProcessor
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cbl/CBTRN02C.cbl">
 *      CBTRN02C.cbl</a>
 */
@Component
public class TransactionWriter implements ItemWriter<Transaction> {

    private static final Logger log = LoggerFactory.getLogger(TransactionWriter.class);

    /**
     * COBOL program identifier for traceability logging.
     * Matches the original program-id 'CBTRN02C' from CBTRN02C.cbl line 7.
     */
    private static final String COBOL_PROGRAM_ID = "CBTRN02C";

    /**
     * Timestamp formatter for S3 backup file key generation.
     * Produces DB2-compatible timestamp format for file naming.
     */
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * JPA repository for persisting posted transaction records.
     * Replaces COBOL WRITE to the TRANSACT VSAM KSDS dataset.
     */
    private final TransactionRepository transactionRepository;

    /**
     * JPA repository for updating account balance records.
     * Replaces COBOL REWRITE to the ACCTDAT VSAM KSDS dataset.
     */
    private final AccountRepository accountRepository;

    /**
     * JPA repository for updating transaction category balance records.
     * Replaces COBOL READ/WRITE/REWRITE to the TCATBALF VSAM KSDS dataset.
     */
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * JPA repository for resolving card numbers to account IDs.
     * Replaces COBOL READ from the CARDXREF VSAM KSDS dataset with
     * CXACAIX alternate index access.
     */
    private final CardCrossReferenceRepository crossReferenceRepository;

    /**
     * AWS S3 client for writing backup copies of posted transactions.
     * Replaces the COBOL GDG (Generation Data Group) backup pattern.
     */
    private final S3Client s3Client;

    /**
     * S3 bucket name for transaction backup output.
     * Defaults to 'carddemo-batch-output' matching the LocalStack
     * bucket provisioned in init-aws.sh.
     */
    @Value("${carddemo.batch.output-bucket:carddemo-batch-output}")
    private String outputBucket;

    /**
     * S3 key prefix for transaction backup files.
     * Defaults to 'transactions/' for organized S3 storage.
     */
    @Value("${carddemo.batch.transaction-backup-prefix:transactions/}")
    private String backupPrefix;

    /**
     * Running count of transactions posted in the current job execution.
     * Used for diagnostic logging and job metrics.
     */
    private final AtomicLong transactionCount = new AtomicLong(0);

    /**
     * Running count of chunks processed, used for S3 backup key generation.
     */
    private final AtomicLong chunkCount = new AtomicLong(0);

    /**
     * Constructs a new TransactionWriter with all required dependencies.
     *
     * @param transactionRepository       repository for transaction persistence
     * @param accountRepository           repository for account balance updates
     * @param categoryBalanceRepository   repository for category balance updates
     * @param crossReferenceRepository    repository for card-to-account resolution
     * @param s3Client                    S3 client for backup file output
     */
    public TransactionWriter(TransactionRepository transactionRepository,
                             AccountRepository accountRepository,
                             TransactionCategoryBalanceRepository categoryBalanceRepository,
                             CardCrossReferenceRepository crossReferenceRepository,
                             S3Client s3Client) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.crossReferenceRepository = crossReferenceRepository;
        this.s3Client = s3Client;
    }

    /**
     * Posts a chunk of validated transactions to the database and backs them up to S3.
     *
     * <p>This method implements the posting logic from COBOL paragraph
     * {@code 3000-POST-TRANSACTION}. For each transaction in the chunk:</p>
     * <ol>
     *   <li>Resolves the account via card cross-reference lookup</li>
     *   <li>Updates or creates the transaction category balance record</li>
     *   <li>Updates the account balance and cycle accumulators</li>
     *   <li>Saves all transactions in the chunk via bulk insert</li>
     *   <li>Writes a backup to S3 (non-fatal on failure)</li>
     * </ol>
     *
     * <p>All database operations are wrapped in a single transaction via
     * {@link Transactional}, ensuring atomicity — equivalent to COBOL
     * {@code EXEC CICS SYNCPOINT} semantics.</p>
     *
     * @param chunk the chunk of validated transactions to post
     * @throws Exception if a database error prevents transaction posting
     */
    @Override
    @Transactional
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        long currentChunk = chunkCount.incrementAndGet();
        log.info("Processing transaction chunk #{} — {} transactions", currentChunk, chunk.size());

        for (Transaction transaction : chunk.getItems()) {
            postTransaction(transaction);
        }

        // Bulk save all transactions in the chunk — COBOL §3300-WRITE-TRANSACTION-RECORD
        transactionRepository.saveAll(chunk.getItems());
        long totalPosted = transactionCount.addAndGet(chunk.size());
        log.info("Chunk #{} posted — {} transactions in chunk, {} total posted",
                currentChunk, chunk.size(), totalPosted);

        // S3 backup — non-fatal, equivalent to COBOL GDG backup step
        backupToS3(chunk, currentChunk);
    }

    /**
     * Posts a single transaction by updating category balance and account balance.
     *
     * <p>Implements COBOL paragraphs {@code 3000-POST-TRANSACTION},
     * {@code 3100-UPDATE-TCATBAL}, and {@code 3200-UPDATE-ACCOUNT-BALANCE}.</p>
     *
     * @param transaction the validated transaction to post
     */
    private void postTransaction(Transaction transaction) {
        String cardNum = transaction.getTranCardNum();
        String tranId = transaction.getTranId();
        BigDecimal amount = transaction.getTranAmt();

        // Resolve account ID from card number via cross-reference — COBOL CARDXREF READ
        Optional<CardCrossReference> xrefOpt = crossReferenceRepository.findById(cardNum);
        if (xrefOpt.isEmpty()) {
            log.warn("No cross-reference found for card number {} — skipping account/balance updates for transaction {}",
                    cardNum, tranId);
            return;
        }

        String acctId = xrefOpt.get().getXrefAcctId();
        Optional<Account> accountOpt = accountRepository.findById(acctId);
        if (accountOpt.isEmpty()) {
            log.warn("Account {} not found for card {} — skipping balance updates for transaction {}",
                    acctId, cardNum, tranId);
            return;
        }

        Account account = accountOpt.get();

        // Step 1: Update transaction category balance — COBOL §3100-UPDATE-TCATBAL
        updateCategoryBalance(acctId, transaction);

        // Step 2: Update account balance — COBOL §3200-UPDATE-ACCOUNT-BALANCE
        updateAccountBalance(account, amount);

        log.debug("Transaction {} posted: card={}, acct={}, amount={}, type={}, cat={}",
                tranId, cardNum, acctId, amount, transaction.getTranTypeCd(), transaction.getTranCatCd());
    }

    /**
     * Updates or creates the transaction category balance record for the
     * given account and transaction type/category combination.
     *
     * <p>Implements COBOL paragraph {@code 3100-UPDATE-TCATBAL}. The COBOL
     * program reads the TCATBALF VSAM KSDS dataset with a composite key
     * (ACCT-ID + TRAN-TYPE-CD + TRAN-CAT-CD). If the record exists, the
     * transaction amount is added to the running balance. If not found
     * (FILE STATUS '23'), a new record is created with the transaction
     * amount as the initial balance.</p>
     *
     * <p>All arithmetic operations use {@link BigDecimal} to preserve
     * COBOL COMP-3 decimal precision. The balance field maps to
     * {@code TRAN-CAT-BAL PIC S9(9)V99} — 11 digits with 2 decimal places.</p>
     *
     * @param acctId       the account ID from the card cross-reference
     * @param transaction  the transaction whose amount updates the balance
     */
    private void updateCategoryBalance(String acctId, Transaction transaction) {
        String typeCode = transaction.getTranTypeCd();
        Short catCode = transaction.getTranCatCd();
        BigDecimal amount = transaction.getTranAmt();

        TransactionCategoryBalanceId compositeKey =
                new TransactionCategoryBalanceId(acctId, typeCode, catCode);

        Optional<TransactionCategoryBalance> existingOpt =
                categoryBalanceRepository.findById(compositeKey);

        if (existingOpt.isPresent()) {
            // Record exists — add transaction amount to running balance
            TransactionCategoryBalance existing = existingOpt.get();
            BigDecimal updatedBalance = existing.getTranCatBal().add(amount);
            existing.setTranCatBal(updatedBalance);
            categoryBalanceRepository.save(existing);
            log.debug("Updated category balance: acct={}, type={}, cat={}, newBalance={}",
                    acctId, typeCode, catCode, updatedBalance);
        } else {
            // Record not found (COBOL FILE STATUS '23') — create new with amount as initial balance
            TransactionCategoryBalance newBalance =
                    new TransactionCategoryBalance(compositeKey, amount);
            categoryBalanceRepository.save(newBalance);
            log.debug("Created new category balance: acct={}, type={}, cat={}, initialBalance={}",
                    acctId, typeCode, catCode, amount);
        }
    }

    /**
     * Updates the account balance and cycle accumulators based on the
     * transaction amount.
     *
     * <p>Implements COBOL paragraph {@code 3200-UPDATE-ACCOUNT-BALANCE}. The
     * logic adds the transaction amount to the account's current balance
     * ({@code ACCT-CURR-BAL}) and segregates it into the appropriate cycle
     * accumulator:</p>
     * <ul>
     *   <li>If amount &lt; 0 (debit): add absolute value to {@code ACCT-CURR-CYC-DEBIT}</li>
     *   <li>If amount ≥ 0 (credit): add to {@code ACCT-CURR-CYC-CREDIT}</li>
     * </ul>
     *
     * <p>All arithmetic uses {@link BigDecimal#add(BigDecimal)} to preserve
     * COBOL COMP-3 precision. Comparison uses {@link BigDecimal#compareTo(BigDecimal)}
     * (not {@code equals()}) per project decimal precision rules.</p>
     *
     * <p>The account is saved via {@link AccountRepository#save(Object)}, which
     * triggers JPA {@code @Version} optimistic locking — equivalent to the
     * COBOL REWRITE with record snapshot comparison.</p>
     *
     * @param account the account to update
     * @param amount  the transaction amount (negative for debits, positive for credits)
     */
    private void updateAccountBalance(Account account, BigDecimal amount) {
        // Update current balance: ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
        BigDecimal updatedBalance = account.getAcctCurrBal().add(amount);
        account.setAcctCurrBal(updatedBalance);

        // Segregate into credit/debit cycle accumulators
        // COBOL: IF TRAN-AMT < 0 → debit accumulator; ELSE → credit accumulator
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            // Debit — add absolute value to cycle debit accumulator
            BigDecimal currentDebit = account.getAcctCurrCycDebit() != null
                    ? account.getAcctCurrCycDebit() : BigDecimal.ZERO;
            account.setAcctCurrCycDebit(currentDebit.add(amount.abs()));
        } else {
            // Credit — add to cycle credit accumulator
            BigDecimal currentCredit = account.getAcctCurrCycCredit() != null
                    ? account.getAcctCurrCycCredit() : BigDecimal.ZERO;
            account.setAcctCurrCycCredit(currentCredit.add(amount));
        }

        // Save triggers @Version optimistic locking — equivalent to COBOL REWRITE
        accountRepository.save(account);
        log.debug("Updated account {}: newBalance={}, amount={}", account.getAcctId(), updatedBalance, amount);
    }

    /**
     * Writes a backup of the posted transaction chunk to S3.
     *
     * <p>This replaces the COBOL GDG (Generation Data Group) backup pattern
     * where posted transactions are written to a new generation of the
     * TRANSACT GDG base. In the Java target, each chunk is written as a
     * timestamped object in the S3 output bucket.</p>
     *
     * <p><strong>Non-fatal:</strong> S3 backup failures are logged as warnings
     * but do not cause the database transaction to roll back. This matches
     * the COBOL behavior where the GDG backup runs after SYNCPOINT commit.</p>
     *
     * @param chunk      the chunk of transactions to back up
     * @param chunkIndex the sequential chunk number for key generation
     */
    private void backupToS3(Chunk<? extends Transaction> chunk, long chunkIndex) {
        try {
            String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMAT);
            String s3Key = String.format("%sTRANSACT-%s-%04d.txt", backupPrefix, timestamp, chunkIndex);

            StringBuilder content = new StringBuilder();
            for (Transaction transaction : chunk.getItems()) {
                content.append(formatTransactionForBackup(transaction));
                content.append(System.lineSeparator());
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(outputBucket)
                    .key(s3Key)
                    .contentType("text/plain")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(content.toString(), StandardCharsets.UTF_8));

            log.debug("S3 backup written: s3://{}/{} — {} transactions", outputBucket, s3Key, chunk.size());
        } catch (Exception ex) {
            // Non-fatal — log warning but do not roll back the database transaction
            log.warn("S3 backup failed for chunk #{} — transactions are persisted in database: {}",
                    chunkIndex, ex.getMessage());
        }
    }

    /**
     * Formats a single transaction record for S3 backup output.
     *
     * <p>Produces a pipe-delimited text representation of the transaction,
     * matching the field layout from CVTRA05Y.cpy (350-byte record). This
     * format is suitable for both archival and potential reload into the
     * batch pipeline.</p>
     *
     * @param transaction the transaction to format
     * @return pipe-delimited string representation
     */
    private String formatTransactionForBackup(Transaction transaction) {
        return String.join("|",
                nullSafe(transaction.getTranId()),
                nullSafe(transaction.getTranTypeCd()),
                String.valueOf(transaction.getTranCatCd()),
                nullSafe(transaction.getTranSource()),
                nullSafe(transaction.getTranDesc()),
                transaction.getTranAmt() != null ? transaction.getTranAmt().toPlainString() : "0.00",
                nullSafe(transaction.getTranMerchantId()),
                nullSafe(transaction.getTranMerchantName()),
                nullSafe(transaction.getTranMerchantCity()),
                nullSafe(transaction.getTranMerchantZip()),
                nullSafe(transaction.getTranCardNum()),
                transaction.getTranOrigTs() != null ? transaction.getTranOrigTs().toString() : "",
                transaction.getTranProcTs() != null ? transaction.getTranProcTs().toString() : ""
        );
    }

    /**
     * Null-safe string converter for backup formatting.
     *
     * @param value the value to convert
     * @return the value as a string, or empty string if null
     */
    private String nullSafe(Object value) {
        return value != null ? value.toString() : "";
    }

    /**
     * Returns the total number of transactions posted in the current
     * job execution.
     *
     * @return the transaction count
     */
    public long getTransactionCount() {
        return transactionCount.get();
    }

    /**
     * Resets the transaction and chunk counters for a new job execution.
     *
     * <p>Should be called at the start of each batch job run to reset
     * the running totals. Equivalent to the COBOL WORKING-STORAGE
     * initialization that occurs at program entry.</p>
     */
    public void resetCounters() {
        transactionCount.set(0);
        chunkCount.set(0);
        log.debug("TransactionWriter counters reset — ready for new job execution");
    }
}
